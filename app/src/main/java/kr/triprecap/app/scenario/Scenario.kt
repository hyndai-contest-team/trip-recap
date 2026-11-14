package kr.triprecap.app.scenario

import kr.triprecap.app.domain.Gear
import kr.triprecap.app.domain.GeoPoint
import kr.triprecap.app.domain.SeatId
import kr.triprecap.app.domain.StopSummary
import kr.triprecap.app.ports.Destination

/** 시나리오 한 단계. atMs 는 시나리오 시작 기준 경과 시각(ms) */
data class Step(val atMs: Long, val event: ScenarioEvent)

sealed interface ScenarioEvent {
    data class GearTo(val gear: Gear) : ScenarioEvent
    data class Door(val open: Boolean) : ScenarioEvent
    data class Seats(val seats: Set<SeatId>) : ScenarioEvent
    data class Wiper(val on: Boolean) : ScenarioEvent
    /** 외기온(섭씨) */
    data class OutsideTemp(val celsius: Double) : ScenarioEvent
    data class Location(val point: GeoPoint, val placeName: String? = null) : ScenarioEvent
    data class Navi(val destination: Destination?) : ScenarioEvent
    data class Home(val point: GeoPoint) : ScenarioEvent
    data class Office(val point: GeoPoint) : ScenarioEvent
    data object IgnitionOff : ScenarioEvent
}

/** 시연에서 한 번에 건너뛰는 단위 */
data class Chapter(val title: String, val startMs: Long)

data class Scenario(
    val name: String,
    /** 시나리오 0ms 에 해당하는 실제 epoch ms */
    val epochAtZero: Long,
    val steps: List<Step>,
    val chapters: List<Chapter> = emptyList(),
    /** 시나리오 시작 전에 쌓여 있던 정차 요약 (단골 판정용). 재생 전에 파이프라인의 stopLog 에 넣는다 */
    val priorLog: List<StopSummary> = emptyList(),
) {
    init {
        require(steps.zipWithNext().all { (a, b) -> a.atMs <= b.atMs }) { "steps must be sorted by atMs" }
    }
}

