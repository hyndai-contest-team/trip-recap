package kr.triprecap.app

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import kr.triprecap.app.ui.AppRoot

class MainActivity : ComponentActivity() {
    private val app get() = application as TripRecapApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppRoot(app.container, onConsent = ::onConsent)
        }
        if (app.container.settings.consentGiven.value) requestRuntimePermissions()
    }

    /** 첫 실행 동의 뒤: 기록을 켜고 런타임 권한을 요청한다 */
    private fun onConsent() {
        app.container.settings.setConsentGiven(true)
        app.startRecordingService()
        requestRuntimePermissions()
    }

    private fun requestRuntimePermissions() {
        val wanted = listOf(FUSED_LOCATION, "android.car.permission.CAR_SPEED", "android.permission.POST_NOTIFICATIONS")
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val i = permissions.indexOf(FUSED_LOCATION)
        if (i >= 0 && grantResults.getOrNull(i) == PackageManager.PERMISSION_GRANTED) app.container.restartLocation()
    }

    companion object {
        private const val FUSED_LOCATION = "pleos.car.permission.FUSED_LOCATION"
    }
}
