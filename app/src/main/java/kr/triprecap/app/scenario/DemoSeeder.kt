package kr.triprecap.app.scenario

import kr.triprecap.app.domain.Drive
import kr.triprecap.app.domain.GeoPoint
import kr.triprecap.app.domain.SeatId
import kr.triprecap.app.domain.TrackPoint
import kr.triprecap.app.domain.Trip
import kr.triprecap.app.domain.TripCategory
import kr.triprecap.app.ports.TripStore
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 시연용 데이터: 골든 시나리오를 한 번에 재생해 저장소에 넣는다. 목록·검색·"1년 전 이맘때" 카드를 보여 줄 때 쓴다.
 * 일상 이동 화면을 보여 주려고 집↔회사 일상 이동 2건(아침 출근, 그 전날 저녁 퇴근)도 넣는다 (docs/product-plan.md 4 시연 데이터)
 */
object DemoSeeder {
    private val zone = ZoneId.of("Asia/Seoul")

    /**
     * 모든 여행은 [now] 전에 끝난다. 시나리오 날짜가 [now] 전에 끝나면 그 날짜를 쓰고,
     * 아니면 [now] 전에 끝나고 다른 시연 여행과 시간이 겹치지 않을 때까지 7일씩 앞으로 옮긴다(요일 유지)
     */
    fun trips(now: Long): List<Trip> {
        // "1년 전 이맘때" 카드용: 의암호 여행을 작년 오늘 하루 전으로 옮긴다
        val lake = GoldenScenarios.tunnelGap.replay().single()
        val target = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().minusYears(1).minusDays(1)
        val from = Instant.ofEpochMilli(lake.startedAt).atZone(zone).toLocalDate()
        val delta = java.time.temporal.ChronoUnit.DAYS.between(from, target) * DAY_MS
        val fixed = listOf(shift(lake, delta)) + dailyMoves(now)
        val placed = fixed.toMutableList()
        val base = listOf(
            GoldenScenarios.gangneungDayTrip, GoldenScenarios.jejuThreeDays,
            GoldenScenarios.reparkSamePlace, GoldenScenarios.tunnelGap,
        ).flatMap { it.replay() }
        val shifted = base.map { trip ->
            var weeks = if (trip.endedAt < now) 0L else (trip.endedAt - now) / WEEK_MS + 1
            while (placed.any { overlaps(it, trip, -weeks * WEEK_MS) }) weeks++
            shift(trip, -weeks * WEEK_MS).also { placed += it }
        }
        return shifted + fixed
    }

    private const val DAY_MS = 86_400_000L
    private const val WEEK_MS = 7 * DAY_MS

    /** [trip] 을 [deltaMs] 만큼 옮기면 [other] 와 시간이 겹치는지 */
    private fun overlaps(other: Trip, trip: Trip, deltaMs: Long): Boolean =
        trip.startedAt + deltaMs <= other.endedAt && other.startedAt <= trip.endedAt + deltaMs

    suspend fun seed(store: TripStore, now: Long) {
        // 시연 여행은 새 여행 표시를 띄우지 않는다
        for (t in trips(now)) store.save(t.copy(stops = t.stops.map { it.copy(placeResolved = true) }, seen = true))
    }

    /**
     * 일상 이동 2건: 가장 최근에 끝난 아침 출근(8:10~8:55, 집 → 회사)과 그 전날 저녁 퇴근(18:40~19:30, 회사 → 집).
     * [now] 가 오늘 8:55 전이면 아침 출근은 어제 것이다. 둘 다 7일 안이다
     */
    internal fun dailyMoves(now: Long): List<Trip> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val morningDay = if (epoch(today, 8, 55) <= now) today else today.minusDays(1)
        val home = GoldenScenarios.songpaHome
        val office = GoldenScenarios.officeCityHall
        return listOf(
            daily(epoch(morningDay.minusDays(1), 18, 40), epoch(morningDay.minusDays(1), 19, 30), office, home),
            daily(epoch(morningDay, 8, 10), epoch(morningDay, 8, 55), home, office),
        )
    }

    /** 두 지점 사이를 직선 10구간으로 나눈 경로의 일상 이동 1건 */
    private fun daily(start: Long, end: Long, from: GeoPoint, to: GeoPoint): Trip {
        val n = 10
        val track = (0..n).map { i ->
            val f = i.toDouble() / n
            TrackPoint(start + (end - start) * i / n, GeoPoint(from.lat + (to.lat - from.lat) * f, from.lon + (to.lon - from.lon) * f))
        }
        val distance = track.zipWithNext().sumOf { (a, b) -> a.point.distanceM(b.point) }
        val drive = Drive(startedAt = start, endedAt = end, track = track, distanceM = distance, seats = setOf(SeatId.DRIVER))
        return Trip(drives = listOf(drive), closed = true, category = TripCategory.DAILY)
    }

    private fun epoch(d: LocalDate, h: Int, m: Int): Long = d.atTime(LocalTime.of(h, m)).atZone(zone).toInstant().toEpochMilli()

    internal fun shift(trip: Trip, deltaMs: Long): Trip = trip.copy(
        drives = trip.drives.map { d ->
            d.copy(startedAt = d.startedAt + deltaMs, endedAt = d.endedAt + deltaMs, track = d.track.map { it.copy(t = it.t + deltaMs) })
        },
        stops = trip.stops.map { it.copy(arrivedAt = it.arrivedAt + deltaMs, departedAt = it.departedAt + deltaMs) },
    )
}
