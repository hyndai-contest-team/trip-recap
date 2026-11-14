package kr.triprecap.app.domain

/**
 * 단골 판정. (docs/product-plan.md 2-1 단골)
 *
 * 최근 30일 동안 방문지가 된 횟수가 3번 이상인 곳(150m 안)이 단골이다.
 * 묻는 지점에서 150m 안에 있는 방문지 요약 수를 센다. 군집을 미리 만들지 않으므로 결과가 요약 순서에 따라 달라지지 않는다.
 *
 * @param visitPoints 셀 대상 방문지 요약의 좌표. 보통 [from] 으로 만든다
 */
class Regulars(private val visitPoints: List<GeoPoint>) {

    /** [p] 에서 150m 안의 방문지가 3번 이상이면 true. [p] 가 null 이면 false */
    fun isRegular(p: GeoPoint?): Boolean {
        if (p == null) return false
        return visitPoints.count { it.distanceM(p) <= RADIUS_M } >= MIN_VISITS
    }

    companion object {
        const val RADIUS_M = 150.0
        const val MIN_VISITS = 3
        const val WINDOW_MS = 30L * 24 * 3_600_000L

        /** 단골이 없는 상태 */
        val NONE = Regulars(emptyList())

        /**
         * 정차 요약에서 방문지만 골라 만든다.
         * 도착 시각이 [now] 30일 전 이상이고 [excludeFrom] 보다 이른 것만 센다.
         * 이번 이동을 세지 않으려면 [excludeFrom] 에 그 이동의 시작 시각(trip.startedAt)을 넘긴다.
         */
        fun from(log: List<StopSummary>, now: Long, excludeFrom: Long = Long.MAX_VALUE): Regulars =
            Regulars(log.filter { it.visit && it.arrivedAt >= now - WINDOW_MS && it.arrivedAt < excludeFrom }.map { it.point })
    }
}
