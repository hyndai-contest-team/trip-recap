package kr.triprecap.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kr.triprecap.app.domain.GeoPoint
import kr.triprecap.app.domain.KnownPlaces
import kr.triprecap.app.ports.Settings

/** SharedPreferences 에 저장하는 설정. 기기 밖으로 나가지 않는다 (allowBackup=false) */
class PrefsSettings(context: Context) : Settings {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _consent = MutableStateFlow(prefs.getBoolean(KEY_CONSENT, false))
    override val consentGiven: StateFlow<Boolean> = _consent
    private val _recording = MutableStateFlow(prefs.getBoolean(KEY_RECORDING, true))
    override val recordingEnabled: StateFlow<Boolean> = _recording
    private val _arrivalVoice = MutableStateFlow(prefs.getBoolean(KEY_ARRIVAL_VOICE, true))
    override val arrivalVoiceEnabled: StateFlow<Boolean> = _arrivalVoice
    private val _userPlaces = MutableStateFlow(prefs.places(KEY_USER_HOME, KEY_USER_OFFICE))
    override val userPlaces: StateFlow<KnownPlaces> = _userPlaces
    private val _estimatedPlaces = MutableStateFlow(prefs.places(KEY_EST_HOME, KEY_EST_OFFICE))
    override val estimatedPlaces: StateFlow<KnownPlaces> = _estimatedPlaces

    override var deferredArrivalId: Long?
        get() = prefs.getLong(KEY_DEFERRED_ARRIVAL, 0L).takeIf { it != 0L }
        set(value) { prefs.edit().putLong(KEY_DEFERRED_ARRIVAL, value ?: 0L).apply() }

    override fun setConsentGiven(value: Boolean) { _consent.value = value; prefs.edit().putBoolean(KEY_CONSENT, value).apply() }
    override fun setRecordingEnabled(value: Boolean) { _recording.value = value; prefs.edit().putBoolean(KEY_RECORDING, value).apply() }
    override fun setArrivalVoiceEnabled(value: Boolean) { _arrivalVoice.value = value; prefs.edit().putBoolean(KEY_ARRIVAL_VOICE, value).apply() }
    override fun setUserPlaces(value: KnownPlaces) { _userPlaces.value = value; prefs.putPlaces(value, KEY_USER_HOME, KEY_USER_OFFICE) }
    override fun setEstimatedPlaces(value: KnownPlaces) { _estimatedPlaces.value = value; prefs.putPlaces(value, KEY_EST_HOME, KEY_EST_OFFICE) }

    /** 신호를 어디서 받는지: 실제 차량(vehicle) 또는 시나리오 재생(scenario) */
    var sourceMode: String
        get() = prefs.getString(KEY_SOURCE, null) ?: SOURCE_VEHICLE
        set(value) { prefs.edit().putString(KEY_SOURCE, value).apply() }

    companion object {
        private const val KEY_CONSENT = "consent"
        private const val KEY_RECORDING = "recording"
        private const val KEY_ARRIVAL_VOICE = "arrival_voice"
        private const val KEY_SOURCE = "source"
        private const val KEY_USER_HOME = "user_home"
        private const val KEY_USER_OFFICE = "user_office"
        private const val KEY_EST_HOME = "estimated_home"
        private const val KEY_EST_OFFICE = "estimated_office"
        private const val KEY_DEFERRED_ARRIVAL = "deferred_arrival"
        const val SOURCE_VEHICLE = "vehicle"
        const val SOURCE_SCENARIO = "scenario"

        /** 좌표는 "위도,경도" 글자로 저장한다. 없으면 키를 지운다 */
        private fun SharedPreferences.point(key: String): GeoPoint? =
            getString(key, null)?.split(',')?.takeIf { it.size == 2 }?.let { (a, b) ->
                val lat = a.toDoubleOrNull()
                val lon = b.toDoubleOrNull()
                if (lat != null && lon != null) GeoPoint(lat, lon) else null
            }

        private fun SharedPreferences.places(homeKey: String, officeKey: String) = KnownPlaces(point(homeKey), point(officeKey))

        private fun SharedPreferences.putPlaces(value: KnownPlaces, homeKey: String, officeKey: String) {
            val e = edit()
            for ((key, p) in listOf(homeKey to value.home, officeKey to value.office)) {
                if (p == null) e.remove(key) else e.putString(key, "${p.lat},${p.lon}")
            }
            e.apply()
        }
    }
}
