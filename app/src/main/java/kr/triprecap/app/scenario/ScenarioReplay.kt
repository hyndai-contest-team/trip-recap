package kr.triprecap.app.scenario

import kr.triprecap.app.domain.KnownPlaces
import kr.triprecap.app.domain.LocationSample
import kr.triprecap.app.domain.Trip
import kr.triprecap.app.domain.TripPipeline
import kr.triprecap.app.domain.TripRecorder

/** 시나리오 이벤트 → 기록기 이벤트. 내비 이벤트(HOME·OFFICE·목적지)는 null */
fun ScenarioEvent.toSignal(): TripRecorder.Event? = when (this) {
    is ScenarioEvent.GearTo -> TripRecorder.Event.GearChanged(gear)
    is ScenarioEvent.Door -> TripRecorder.Event.DoorChanged(open)
    is ScenarioEvent.Seats -> TripRecorder.Event.SeatsChanged(seats)
    is ScenarioEvent.Wiper -> TripRecorder.Event.WiperChanged(on)
    is ScenarioEvent.OutsideTemp -> TripRecorder.Event.OutsideTempChanged(celsius)
    is ScenarioEvent.Location -> TripRecorder.Event.LocationChanged(LocationSample(point, placeName))
    ScenarioEvent.IgnitionOff -> TripRecorder.Event.IgnitionOff
    is ScenarioEvent.Navi, is ScenarioEvent.Home, is ScenarioEvent.Office -> null
}

/**
 * 시나리오 전체를 한 번에 흘려 닫힌 이동 목록(여행·일상 이동·묻는 중 모두)을 얻는다. 화면 재생(ScenarioPlayer)과 같은 변환을 쓴다.
 * 재생 전에 [Scenario.priorLog] 를 파이프라인의 stopLog 에 더한다.
 * [tickMs] 가 0 보다 크면 이벤트 사이에 epochAtZero 기준 [tickMs] 간격으로 tick 을 넣는다(앱이 켜져 있는 경우). 0 이면 tick 없이 이벤트만 넣는다.
 * 다시 열린 이동은 목록에서 빼고, 다시 닫히면 그때의 모습으로 넣는다. [onUpdate] 는 매 호출 결과를 받는다.
 */
fun Scenario.replay(
    pipeline: TripPipeline = TripPipeline(),
    tickMs: Long = 0,
    onUpdate: (TripPipeline.Update) -> Unit = {},
): List<Trip> {
    pipeline.stopLog = pipeline.stopLog + priorLog
    val closed = mutableListOf<Trip>()
    fun take(u: TripPipeline.Update) {
        onUpdate(u)
        u.reopened?.let { r -> closed.removeAll { it.startedAt == r.startedAt } }
        for (t in u.closed) {
            closed.removeAll { it.startedAt == t.startedAt }
            closed += t
        }
    }
    var lastAt: Long? = null
    for (step in steps) {
        if (tickMs > 0 && lastAt != null) {
            var t = (lastAt / tickMs + 1) * tickMs
            while (t < step.atMs) {
                take(pipeline.tick(epochAtZero + t))
                t += tickMs
            }
        }
        lastAt = step.atMs
        val now = epochAtZero + step.atMs
        when (val e = step.event) {
            is ScenarioEvent.Home -> pipeline.places = pipeline.places.copy(home = e.point)
            is ScenarioEvent.Office -> pipeline.places = pipeline.places.copy(office = e.point)
            else -> e.toSignal()?.let { take(pipeline.onEvent(it, now)) }
        }
    }
    return closed
}

/** 시나리오에 들어 있는 HOME·OFFICE */
fun Scenario.knownPlaces(): KnownPlaces = steps.fold(KnownPlaces()) { acc, s ->
    when (val e = s.event) {
        is ScenarioEvent.Home -> acc.copy(home = e.point)
        is ScenarioEvent.Office -> acc.copy(office = e.point)
        else -> acc
    }
}

/** 화면 재생(ScenarioPlayer)과 테스트가 시나리오 이벤트를 세션에 넣는 같은 경로 */
suspend fun kr.triprecap.app.TripSession.onScenarioEvent(event: ScenarioEvent, at: Long) {
    when (event) {
        is ScenarioEvent.Home -> setNaviPlaces(naviPlaces.value.copy(home = event.point))
        is ScenarioEvent.Office -> setNaviPlaces(naviPlaces.value.copy(office = event.point))
        is ScenarioEvent.Navi -> onDestination(event.destination?.let { d -> d.point?.let { kr.triprecap.app.domain.NamedPoint(d.name, it) } })
        else -> event.toSignal()?.let { onSignal(it, at) }
    }
}
