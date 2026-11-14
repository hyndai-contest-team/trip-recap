package kr.triprecap.app.ui

import kr.triprecap.app.domain.CoursePlanner
import kr.triprecap.app.domain.StopKind
import kr.triprecap.app.domain.Trip

/**
 * 코스 화면 순서 목록의 한 행.
 * @property dot 점 안 글자: 방문지는 번호, 숙박은 "숙". null 이면 잠깐 정차(회색 점)
 * @property leg 0부터 세는 구간 번호
 */
internal data class CourseRow(val name: String, val dot: String?, val leg: Int)

internal object CourseList {
    /** 한 구간에 들어가는 곳 수: 경유지 + 목적지 */
    private const val PER_LEG = CoursePlanner.MAX_WAYPOINTS + 1

    /**
     * [CoursePlanner.plan] 과 같은 순서·같은 구간 나눔으로 행을 만든다.
     * 번호는 여행 전체 방문지 순서를 따른다(위치가 없어 빠진 방문지도 번호를 차지한다). 상세 화면 번호와 같게 하려는 것이다.
     */
    fun rows(trip: Trip, includeBriefIds: Set<Int>): List<CourseRow> {
        val visitIds = trip.visits.map { it.id }
        return trip.stops
            .filter { (it.kind != StopKind.BRIEF || it.id in includeBriefIds) && it.point != null }
            .mapIndexed { i, s ->
                val dot = when (s.kind) {
                    StopKind.VISIT -> "${visitIds.indexOf(s.id) + 1}"
                    StopKind.LODGING -> "숙"
                    StopKind.BRIEF -> null
                }
                CourseRow(trip.nameOf(s), dot, i / PER_LEG)
            }
    }

    /** 안내를 보낸 뒤 알림. 구간이 하나면 구간 번호를 쓰지 않는다 */
    fun sentToast(legNumber: Int, legCount: Int): String =
        if (legCount <= 1) "내비에 코스 안내를 보냈어요" else "내비에 ${legNumber}구간 안내를 보냈어요 (전체 ${legCount}구간)"
}
