package kr.triprecap.app.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 내비에 HOME/OFFICE 가 없을 때 정차 요약으로 집·회사를 추정한다. (docs/product-plan.md 2-1 집·회사 위치)
 *
 * - 집: 최근 7일 중 4일 이상 오전 3시에 서 있던 곳(150m 안)
 * - 회사: 최근 14일 중 평일(월~금) 6일 이상, 그날 09~18시에 4시간 넘게 서 있던 곳(150m 안). 집 300m 안은 회사가 아니다
 * - 조건을 채우는 곳이 없으면 null
 *
 * 장소를 고르는 방법: 조건 구간에 걸친 정차 요약 하나하나를 후보로 놓고, 후보 좌표 150m 안의 요약으로 날짜 수를 센다.
 * 날짜 수가 가장 많은 후보를 고르고, 같으면 도착 시각이 가장 늦은 후보를 고른다.
 * 돌려주는 좌표는 고른 후보 요약의 좌표다. 같은 곳의 요약이 여럿이면 가장 최근 요약의 좌표가 나온다.
 */
object PlaceEstimator {
    val ZONE: ZoneId = ZoneId.of("Asia/Seoul")
    const val RADIUS_M = 150.0

    const val HOME_WINDOW_DAYS = 7
    const val HOME_MIN_DAYS = 4
    const val NIGHT_HOUR = 3

    const val OFFICE_WINDOW_DAYS = 14
    const val OFFICE_MIN_DAYS = 6
    const val WORK_START_HOUR = 9
    const val WORK_END_HOUR = 18
    const val OFFICE_MIN_MS = 4 * 3_600_000L

    /**
     * 집 추정. [now] 이전(같은 시각 포함)의 가장 최근 오전 3시 7개를 본다.
     * 정차 요약이 오전 3시 시각 t 를 포함한다는 것은 arrivedAt <= t < departedAt 이다.
     */
    fun estimateHome(log: List<StopSummary>, now: Long): GeoPoint? {
        var last = localDate(now)
        if (epochMs(last, NIGHT_HOUR) > now) last = last.minusDays(1)
        val nights = (0 until HOME_WINDOW_DAYS).map { epochMs(last.minusDays(it.toLong()), NIGHT_HOUR) }
        // 오전 3시마다 그 시각에 서 있던 요약
        val byNight = nights.map { t -> log.filter { it.arrivedAt <= t && t < it.departedAt } }
        val candidates = byNight.flatten().distinct()
        return pick(candidates, HOME_MIN_DAYS) { c -> byNight.count { day -> day.any { it.point.distanceM(c) <= RADIUS_M } } }
    }

    /**
     * 회사 추정. 오늘을 포함한 최근 14개 날짜 중 평일만 본다.
     * 날짜마다 후보 150m 안 요약들이 그날 09:00~18:00 과 겹친 시간을 더해 4시간을 넘으면 그날을 센다.
     * [home] 300m 안의 후보는 고르지 않는다.
     */
    fun estimateOffice(log: List<StopSummary>, now: Long, home: GeoPoint?): GeoPoint? {
        val today = localDate(now)
        val windows = (0 until OFFICE_WINDOW_DAYS)
            .map { today.minusDays(it.toLong()) }
            .filter { it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY }
            .map { epochMs(it, WORK_START_HOUR)..epochMs(it, WORK_END_HOUR) }
        // 평일마다 그날 09~18시와 겹친 요약과 겹친 시간
        val byDay = windows.map { w -> log.mapNotNull { s -> overlap(s, w).takeIf { it > 0 }?.let { s to it } } }
        val candidates = byDay.flatten().map { it.first }.distinct()
            .filter { home == null || it.point.distanceM(home) > KnownPlaces.RADIUS_M }
        return pick(candidates, OFFICE_MIN_DAYS) { c ->
            byDay.count { day -> day.filter { it.first.point.distanceM(c) <= RADIUS_M }.sumOf { it.second } > OFFICE_MIN_MS }
        }
    }

    /** 날짜 수가 [minDays] 이상인 후보 중 날짜 수가 가장 많고, 같으면 도착이 가장 늦은 후보의 좌표 */
    private fun pick(candidates: List<StopSummary>, minDays: Int, days: (GeoPoint) -> Int): GeoPoint? =
        candidates.map { it to days(it.point) }
            .filter { it.second >= minDays }
            .maxWithOrNull(compareBy<Pair<StopSummary, Int>> { it.second }.thenBy { it.first.arrivedAt }.thenBy { it.first.departedAt })
            ?.first?.point

    /** 요약 [s] 가 구간 [w](시작 포함, 끝 제외)와 겹친 시간(ms) */
    private fun overlap(s: StopSummary, w: LongRange): Long =
        (minOf(s.departedAt, w.last) - maxOf(s.arrivedAt, w.first)).coerceAtLeast(0)

    private fun localDate(t: Long): LocalDate = Instant.ofEpochMilli(t).atZone(ZONE).toLocalDate()

    private fun epochMs(d: LocalDate, hour: Int): Long = d.atTime(hour, 0).atZone(ZONE).toInstant().toEpochMilli()
}
