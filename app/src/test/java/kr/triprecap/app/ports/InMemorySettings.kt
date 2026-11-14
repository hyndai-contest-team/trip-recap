package kr.triprecap.app.ports

import kotlinx.coroutines.flow.MutableStateFlow
import kr.triprecap.app.domain.KnownPlaces

/** 메모리 설정. 테스트에서 쓴다 */
class InMemorySettings(consent: Boolean = true, recording: Boolean = true, arrivalVoice: Boolean = true) : Settings {
    override val consentGiven = MutableStateFlow(consent)
    override val recordingEnabled = MutableStateFlow(recording)
    override val arrivalVoiceEnabled = MutableStateFlow(arrivalVoice)
    override val userPlaces = MutableStateFlow(KnownPlaces())
    override val estimatedPlaces = MutableStateFlow(KnownPlaces())
    override var deferredArrivalId: Long? = null
    override fun setConsentGiven(value: Boolean) { consentGiven.value = value }
    override fun setRecordingEnabled(value: Boolean) { recordingEnabled.value = value }
    override fun setArrivalVoiceEnabled(value: Boolean) { arrivalVoiceEnabled.value = value }
    override fun setUserPlaces(value: KnownPlaces) { userPlaces.value = value }
    override fun setEstimatedPlaces(value: KnownPlaces) { estimatedPlaces.value = value }
}
