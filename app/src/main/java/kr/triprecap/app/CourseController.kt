package kr.triprecap.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kr.triprecap.app.domain.CourseLeg
import kr.triprecap.app.domain.CoursePlace
import kr.triprecap.app.domain.CourseRunner
import kr.triprecap.app.domain.NamedPoint
import kr.triprecap.app.ports.NaviSource

/** 코스 다시 가기의 진행 상태. 화면에 보여 준다 */
data class CourseProgress(val legNumber: Int, val legCount: Int, val places: List<String>, val requestOk: Boolean)

/**
 * 코스를 구간별로 내비에 요청한다 (docs/product-plan.md 2-6).
 * 첫 구간은 바로 요청하고, 구간 목적지 도착 뒤 다시 출발하면 다음 구간을 요청한다.
 */
class CourseController(private val naviProvider: () -> NaviSource) {
    private val navi: NaviSource get() = naviProvider()
    private var runner: CourseRunner? = null

    private val _progress = MutableStateFlow<CourseProgress?>(null)
    val progress: StateFlow<CourseProgress?> = _progress.asStateFlow()

    suspend fun start(legs: List<CourseLeg>) {
        if (legs.isEmpty()) return
        val r = CourseRunner(legs)
        runner = r
        request(r, legs.first())
    }

    /** 방문지 1곳으로 안내 */
    suspend fun goTo(place: CoursePlace): Boolean {
        cancel()
        return navi.requestRoute(NamedPoint(place.name, place.point))
    }

    /** NaviHelper 목적지 도착 콜백 */
    fun onDestinationArrived() {
        val r = runner ?: return
        r.onDestinationArrived()
        if (r.finished) cancel()
    }

    /** 차가 출발했을 때 (기어 D 또는 R) */
    suspend fun onDeparted() {
        val r = runner ?: return
        r.onDeparted()?.let { request(r, it) }
    }

    fun cancel() {
        runner = null
        _progress.value = null
    }

    private suspend fun request(r: CourseRunner, leg: CourseLeg) {
        val dest = NamedPoint(leg.destination.name, leg.destination.point)
        val ok = if (leg.waypoints.isEmpty()) navi.requestRoute(dest)
        else navi.requestRouteWithWaypoints(leg.waypoints.map { NamedPoint(it.name, it.point) }, dest)
        _progress.value = CourseProgress(r.legNumber, r.legCount, (leg.waypoints + leg.destination).map { it.name }, ok)
    }
}
