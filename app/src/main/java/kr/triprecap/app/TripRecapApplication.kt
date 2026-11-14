package kr.triprecap.app

import android.app.Application
import android.content.Intent
import android.util.Log
import kr.triprecap.app.service.TripRecordingService

class TripRecapApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) watchMainThread()
        container = AppContainer(this)
        container.start()
        startRecordingService()
    }

    /** debug 빌드: 메인 스레드가 3초 넘게 멈추면 그 스택을 로그에 남긴다 */
    private fun watchMainThread() {
        val main = android.os.Looper.getMainLooper()
        val handler = android.os.Handler(main)
        Thread {
            while (true) {
                val beat = java.util.concurrent.atomic.AtomicBoolean(false)
                handler.post { beat.set(true) }
                Thread.sleep(3_000)
                if (!beat.get()) Log.w("TripRecapApp", "main thread blocked:\n" + main.thread.stackTrace.take(25).joinToString("\n") { "  at $it" })
            }
        }.apply { isDaemon = true; name = "main-watchdog" }.start()
    }

    /** 위치 기록 동의가 있을 때만 기록 서비스를 띄운다 */
    fun startRecordingService() {
        if (!container.settings.consentGiven.value) return
        runCatching { startForegroundService(Intent(this, TripRecordingService::class.java)) }
            .onFailure { Log.w("TripRecapApp", "recording service not started: ${it.message}") }
    }
}
