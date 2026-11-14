package kr.triprecap.app.ports

import kotlinx.coroutines.flow.StateFlow
import kr.triprecap.app.domain.KnownPlaces

/** 사용자 설정과 앱이 기억해 둘 작은 값 (docs/product-plan.md 2-1 집·회사 위치, 2-3, 2-9) */
interface Settings {
    /** 첫 실행 때 받은 위치 기록 동의 */
    val consentGiven: StateFlow<Boolean>
    /** 기록 켜기/끄기. 동의가 없으면 켜져 있어도 기록하지 않는다 */
    val recordingEnabled: StateFlow<Boolean>
    /** 여행 종료 때 TTS 한 문장 */
    val arrivalVoiceEnabled: StateFlow<Boolean>
    /** 사용자가 설정에서 "지금 위치로 정하기"로 정한 집·회사. 정하지 않은 칸은 null */
    val userPlaces: StateFlow<KnownPlaces>
    /** 정차 요약으로 추정해 둔 집·회사. 한 번 정하면 사용자가 되돌리거나 전체 삭제하기 전까지 그대로 쓴다 */
    val estimatedPlaces: StateFlow<KnownPlaces>
    /**
     * 다음 시동 때 띄울 도착 카드의 이동 id. 없으면 null.
     * 집이 아닌 곳에 오래 서 있어서 닫힌 이동(endedAway)의 카드를 프로세스가 다시 시작돼도 띄울 수 있게 기억해 둔다
     */
    var deferredArrivalId: Long?
    fun setConsentGiven(value: Boolean)
    fun setRecordingEnabled(value: Boolean)
    fun setArrivalVoiceEnabled(value: Boolean)
    fun setUserPlaces(value: KnownPlaces)
    fun setEstimatedPlaces(value: KnownPlaces)
}
