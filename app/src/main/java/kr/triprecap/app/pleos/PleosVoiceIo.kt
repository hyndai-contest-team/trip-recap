package kr.triprecap.app.pleos

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInitListener
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInitListener
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kr.triprecap.app.ports.VoiceIo
import java.util.Locale
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode

/**
 * Pleos TextToSpeech 3.3.1 + SpeechToText 3.3.1 (둘 다 ON_DEVICE 모드, 앱 등록 불필요).
 *
 * speak: 선행 묵음 180ms 뒤 speak(text) → EventListener.onDone/onStop/onError 에서 돌아온다.
 * listen: request() → ResultListener.onUpdated(text, completed=true) 에서 stop() 하고 텍스트를 돌려준다.
 *         completed 가 오지 않고 onEndedRecognition 이 오면 마지막 중간 결과를 쓴다. 시간 초과면 stop() 후 중간 결과 또는 null.
 * 권한: pleos.car.permission.TTS_SERVICE, STT_SERVICE
 * 미확인: TTS 가 쓰는 AudioAttributes 기본값과 오디오 포커스 처리. 에뮬레이터에서 내비 안내와 겹칠 때 확인한다.
 */
class PleosVoiceIo(context: Context) : VoiceIo {

    private val tts = TextToSpeech(context.applicationContext, TtsMode.ON_DEVICE)
    private val stt = SpeechToText(context.applicationContext, SttMode.ON_DEVICE)

    private val ttsReady = CompletableDeferred<Boolean>()
    private val sttReady = CompletableDeferred<Boolean>()

    @Volatile private var speakDone: CompletableDeferred<Unit>? = null
    @Volatile private var heard: CompletableDeferred<String?>? = null
    @Volatile private var partial: String? = null
    /** 한 번의 listen 동안 들어온 마이크 신호의 최대 RMS. 0 이면 소리가 전혀 들어오지 않은 것이다 */
    @Volatile private var maxRms: Double = 0.0

    private val ttsListener = object : EventListener {
        override fun onReady() { Log.i(TAG, "tts onReady") }
        override fun onStart() { Log.i(TAG, "tts onStart") }
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onDone() { Log.i(TAG, "tts onDone"); speakDone?.complete(Unit) }
        override fun onStop() { Log.i(TAG, "tts onStop"); speakDone?.complete(Unit) }
        override fun onError(errorType: TtsError) {
            Log.w(TAG, "tts error: $errorType")
            speakDone?.complete(Unit)
        }
    }

    private val sttListener = object : ResultListener {
        override fun onReady() { Log.i(TAG, "stt onReady") }
        override fun onStartedRecognition() { Log.i(TAG, "stt onStartedRecognition") }
        override fun onUpdated(stt: String, completed: Boolean) {
            Log.i(TAG, "stt onUpdated completed=$completed text=$stt")
            partial = stt
            if (completed) heard?.complete(stt)
        }
        override fun onUpdatedEpdData(on: Long, off: Long) { Log.i(TAG, "stt epd on=$on off=$off") }
        override fun onUpdatedRms(rms: Double) { if (rms > maxRms) maxRms = rms }
        override fun onEndedRecognition() { Log.i(TAG, "stt onEndedRecognition partial=$partial"); heard?.complete(partial) }
        override fun onError(errorType: SttError) {
            Log.w(TAG, "stt error: $errorType")
            heard?.complete(partial)
        }
    }

    init {
        tts.initialize(object : TtsInitListener {
            override fun onSuccess() {
                Log.i(TAG, "tts initialized")
                tts.addEventListener(ttsListener)
                tts.setLocale(Locale.KOREA)
                ttsReady.complete(true)
            }
            override fun onFailure(errorType: TtsError) {
                Log.e(TAG, "tts init failed: $errorType")
                ttsReady.complete(false)
            }
        })
        stt.initialize(object : SttInitListener {
            override fun onSuccess() {
                Log.i(TAG, "stt initialized")
                stt.addListener(sttListener)
                stt.setLocale(Locale.KOREA)
                sttReady.complete(true)
            }
            override fun onFailure(errorType: SttError) {
                Log.e(TAG, "stt init failed: $errorType")
                sttReady.complete(false)
            }
        })
    }

    override suspend fun speak(text: String) {
        if (!ttsReady.await()) return
        val done = CompletableDeferred<Unit>()
        speakDone = done
        try {
            delay(PREROLL_SILENCE_MS)   // 음성 안내 선행 묵음 150~200ms 권장 (voice-guidance-preroll-silence-guide)
            Log.i(TAG, "tts speak: $text")
            tts.speak(text)
            // 글자 수 기준 상한. onDone 이 오지 않는 경우를 대비한다.
            withTimeoutOrNull(3_000L + text.length * 250L) { done.await() } ?: run {
                Log.w(TAG, "tts onDone timeout; stop()")
                runCatching { tts.stop() }
            }
        } catch (e: CancellationException) {
            // 호출한 코루틴이 취소되면 재생도 멈춘다
            runCatching { tts.stop() }
            throw e
        } finally {
            speakDone = null
        }
    }

    override suspend fun listen(timeoutMs: Long): String? {
        if (!sttReady.await()) return null
        partial = null
        maxRms = 0.0
        val result = CompletableDeferred<String?>()
        heard = result
        var text: String? = null
        try {
            Log.i(TAG, "stt request()")
            stt.request()
            text = withTimeoutOrNull(timeoutMs) { result.await() } ?: partial.also { Log.i(TAG, "stt timeout, partial=$it") }
        } finally {
            // 정상 종료·시간 초과·취소 모두 마이크를 닫는다
            runCatching { stt.stop() }
            heard = null
            Log.i(TAG, "stt result: $text (maxRms=${"%.1f".format(maxRms)})")
        }
        val cleaned = text?.trim()?.takeIf { it.isNotEmpty() }
        return cleaned
    }

    companion object {
        private const val TAG = "TripRecapVoice"
        private const val PREROLL_SILENCE_MS = 180L
    }
}
