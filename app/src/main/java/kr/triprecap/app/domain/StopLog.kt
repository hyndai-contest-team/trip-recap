package kr.triprecap.app.domain

/**
 * 정차 요약 1건. 단골·집·회사 추정에만 쓴다. (docs/product-plan.md 2-1 정차 요약, 2-9)
 *
 * 좌표(소수 3자리), 도착·출발 시각, 방문지 여부만 담는다. 경로·장소 이름·메모는 넣지 않는다.
 */
data class StopSummary(
    /** 위도·경도를 소수 3자리(약 100m)로 반올림한 좌표 */
    val point: GeoPoint,
    val arrivedAt: Long,
    val departedAt: Long,
    /** 방문지(StopKind.VISIT)였는지 */
    val visit: Boolean,
)

/** 정차 요약을 만들고 보관 기간이 지난 것을 고른다. 저장은 하지 않는다 */
object StopLog {
    /** 보관 기간 30일 */
    const val RETENTION_MS = 30L * 24 * 3_600_000L

    /** 여행의 정차마다 요약 1건. 종류(방문지·잠깐 정차·숙박)와 관계없이 넣고, 좌표가 없는 정차는 뺀다 */
    fun summarize(trip: Trip): List<StopSummary> =
        trip.stops.mapNotNull { s -> s.point?.let { StopSummary(round(it), s.arrivedAt, s.departedAt, s.kind == StopKind.VISIT) } }

    /**
     * 여행의 정차가 되지 않은 정차(집·회사 300m 안 정차 등)의 요약. 좌표가 없으면 null.
     * [kind] 는 StopClassifier 결과이고, VISIT 일 때만 방문지로 적는다.
     */
    fun summarize(stay: Stay, kind: StopKind?): StopSummary? =
        stay.point?.let { StopSummary(round(it), stay.arrivedAt, stay.departedAt, kind == StopKind.VISIT) }

    /** 출발 시각이 [now] 기준 30일보다 오래된 요약을 뺀다. 정확히 30일 전에 출발한 것은 남긴다 */
    fun prune(log: List<StopSummary>, now: Long): List<StopSummary> =
        log.filter { it.departedAt >= now - RETENTION_MS }

    /** 위도·경도를 소수 3자리로 반올림한다 */
    fun round(p: GeoPoint): GeoPoint = GeoPoint(round3(p.lat), round3(p.lon))

    private fun round3(v: Double): Double = Math.round(v * 1000.0) / 1000.0
}
