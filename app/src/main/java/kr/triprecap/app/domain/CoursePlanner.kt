package kr.triprecap.app.domain

data class CoursePlace(val name: String, val point: GeoPoint)

/** 내비에 한 번에 요청하는 구간: 경유지 최대 2개 + 목적지 */
data class CourseLeg(val waypoints: List<CoursePlace>, val destination: CoursePlace)

/** 다시 가기: 방문지 목록 → 구간 목록 (docs/product-plan.md 2-6) */
object CoursePlanner {
    /** NaviHelper requestRouteWithWaypoints 의 경유지 상한 */
    const val MAX_WAYPOINTS = 2

    /**
     * 방문지·숙박을 순서대로 3곳씩 나눈다. 잠깐 정차는 [includeBriefIds] 에 든 것만 넣는다.
     * 좌표가 없는 정차는 뺀다.
     */
    fun plan(trip: Trip, includeBriefIds: Set<Int> = emptySet()): List<CourseLeg> =
        trip.stops
            .filter { it.kind != StopKind.BRIEF || it.id in includeBriefIds }
            .mapNotNull { s -> s.point?.let { CoursePlace(trip.nameOf(s), it) } }
            .chunked(MAX_WAYPOINTS + 1)
            .map { CourseLeg(waypoints = it.dropLast(1), destination = it.last()) }

    /** 지난번 잠깐 정차 가운데 휴게소. 경유지 후보로 제안한다 */
    fun restAreaCandidates(trip: Trip): List<Stop> =
        trip.stops.filter { it.kind == StopKind.BRIEF && it.name?.contains("휴게소") == true && it.point != null }

    /**
     * 코스 화면의 "지난번" 숫자 칸: 전체 시간, 가장 오래 머문 방문지("강릉중앙시장에서"), 잠깐 들른 곳 수.
     * 방문지가 없으면 가운데 칸, 잠깐 정차가 없으면 마지막 칸을 뺀다.
     * 잠깐 정차에는 휴게소가 아닌 곳(주유소 등)도 들어가므로 "휴게소"라고 쓰지 않는다
     */
    fun lastTimeStats(trip: Trip): List<Stat> = buildList {
        add(Stat(Texts.tripLength(trip), "전체 시간"))
        trip.visits.maxByOrNull { it.stayMinutes }?.let { add(Stat(Texts.stay(it), "${trip.nameOf(it)}에서")) }
        val brief = trip.stops.count { it.kind == StopKind.BRIEF }
        if (brief > 0) add(Stat("${brief}곳", "잠깐 들른 곳"))
    }
}

/**
 * 구간을 차례로 내비에 요청하기 위한 진행 상태.
 * 구간 목적지 도착(onDestinationArrived) 뒤 출발(기어 D)하면 다음 구간을 돌려준다.
 */
class CourseRunner(private val legs: List<CourseLeg>) {
    private var index = 0
    private var arrived = false

    /** 지금 안내 중인 구간. 코스가 끝났으면 null */
    val current: CourseLeg? get() = if (finished) null else legs.getOrNull(index)
    /** 1부터 세는 구간 번호 */
    val legNumber: Int get() = index + 1
    val legCount: Int get() = legs.size
    val finished: Boolean get() = legs.isEmpty() || (index == legs.lastIndex && arrived)

    fun onDestinationArrived() {
        arrived = true
    }

    /** 출발했을 때 부른다. 다음 구간을 요청해야 하면 그 구간을, 아니면 null 을 돌려준다 */
    fun onDeparted(): CourseLeg? {
        if (!arrived || index >= legs.lastIndex) return null
        index++
        arrived = false
        return legs[index]
    }
}
