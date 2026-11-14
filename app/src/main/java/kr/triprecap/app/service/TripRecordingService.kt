package kr.triprecap.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kr.triprecap.app.LiveStatus
import kr.triprecap.app.R
import kr.triprecap.app.TripRecapApplication

/** 기록 알림 문구. 기록 설정(동의 포함)과 현재 기록 상태로 고른다 */
object RecordingNotice {
    const val RECORDING = "여행을 기록하고 있어요"
    const val WAITING = "차가 출발하면 여행을 기록해요"
    const val OFF = "여행 기록이 꺼져 있어요"

    fun text(recordingOn: Boolean, live: LiveStatus): String = when {
        !recordingOn -> OFF
        live.recording -> RECORDING
        else -> WAITING
    }
}

/**
 * 화면이 꺼져 있거나 다른 앱이 앞에 있어도 여행 기록이 이어지게 프로세스를 유지하는 Foreground Service.
 * 신호 수신과 기록은 AppContainer 가 한다. 이 서비스는 알림을 띄우고 프로세스가 종료되지 않게 유지한다.
 */
class TripRecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val container = (application as TripRecapApplication).container
        container.start()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "여행 기록", NotificationManager.IMPORTANCE_LOW))
        val settings = container.settings
        val first = RecordingNotice.text(settings.consentGiven.value && settings.recordingEnabled.value, container.session.live.value)
        runCatching {
            val notification = build(first)
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIFICATION_ID, notification)
        }.onFailure { Log.w("TripRecapApp", "startForeground failed: ${it.message}") }
        // 문구가 바뀔 때만 알림을 다시 띄운다. live 는 5초마다 거리·방문 수가 바뀌므로 문구로 비교한다
        var shown = first
        combine(settings.consentGiven, settings.recordingEnabled, container.session.live) { consent, enabled, live ->
            RecordingNotice.text(consent && enabled, live)
        }
            .onEach { text ->
                if (text != shown) {
                    shown = text
                    runCatching { nm.notify(NOTIFICATION_ID, build(text)) }
                }
            }
            .launchIn(scope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun build(text: String): Notification = Notification.Builder(this, CHANNEL)
        .setContentTitle("왔다감")
        .setContentText(text)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()

    companion object {
        private const val CHANNEL = "recording"
        private const val NOTIFICATION_ID = 1
    }
}

/** 시동을 켜면(부팅 완료) 앱을 열지 않아도 기록을 시작한다 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        (context.applicationContext as TripRecapApplication).startRecordingService()
    }
}
