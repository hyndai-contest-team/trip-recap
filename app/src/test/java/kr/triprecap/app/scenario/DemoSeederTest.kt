package kr.triprecap.app.scenario

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kr.triprecap.app.data.InMemoryTripStore
import kr.triprecap.app.domain.Fx
import kr.triprecap.app.domain.KnownPlaces
import kr.triprecap.app.domain.TripCategory
import kr.triprecap.app.domain.TripSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime

class DemoSeederTest {
    @Test
    fun `demo trips are not shown as new`() = runBlocking {
        val store = InMemoryTripStore()
        DemoSeeder.seed(store, Fx.at(11, 12))
        val trips = store.trips.first()
        assertTrue(trips.isNotEmpty())
        assertTrue(trips.all { it.seen })
        assertFalse(trips.any { it.isNew })
    }

    @Test
    fun `two daily moves between home and office are seeded within the last seven days`() {
        for (now in listOf(Fx.at(13, 7), Fx.at(13, 12), Fx.at(13, 23))) {
            val daily = DemoSeeder.trips(now).filter { it.category == TripCategory.DAILY }
            assertEquals(2, daily.size)
            val places = KnownPlaces(GoldenScenarios.songpaHome, GoldenScenarios.officeCityHall)
            assertTrue(daily.all { it.closed && it.endedAt <= now && now - it.endedAt < 7 * 86_400_000L })
            // 아침 출근(집 → 회사)과 그 전날 저녁 퇴근(회사 → 집)
            val (morning, evening) = daily.sortedByDescending { it.startedAt }
            assertTrue(places.isHome(morning.startPoint) && !places.isHome(morning.endPoint))
            assertTrue(places.isHome(evening.endPoint) && !places.isHome(evening.startPoint))
            assertTrue(daily.all { it.distanceM > 10_000.0 && it.stops.isEmpty() })
        }
    }

    @Test
    fun `every demo trip ends before now and the year-ago card still appears`() {
        val nows = listOf(
            ZonedDateTime.of(2026, 11, 14, 16, 30, 0, 0, Fx.zone),
            ZonedDateTime.of(2026, 11, 10, 12, 0, 0, 0, Fx.zone),
            ZonedDateTime.of(2027, 3, 1, 12, 0, 0, 0, Fx.zone),
        )
        for (z in nows) {
            val now = z.toInstant().toEpochMilli()
            val trips = DemoSeeder.trips(now)
            assertEquals(7, trips.size)
            assertTrue(z.toString(), trips.all { it.endedAt < now })
            // 시연 여행끼리 시간이 겹치지 않는다
            val sorted = trips.sortedBy { it.startedAt }
            assertTrue(z.toString(), sorted.zipWithNext().all { (a, b) -> a.endedAt < b.startedAt })
            // "1년 전 이맘때": 작년 오늘 하루 전에 시작한 의암호 여행
            val yearAgo = TripSearch().oneYearAgo(trips.filter { it.closed }, z.toLocalDate())
            assertEquals(z.toLocalDate().minusYears(1).minusDays(1), yearAgo?.let { Instant.ofEpochMilli(it.startedAt).atZone(Fx.zone).toLocalDate() })
            assertEquals(TripCategory.TRIP, yearAgo?.category)
        }
    }

    @Test
    fun `demo trips that already ended keep their scenario dates`() {
        val now = ZonedDateTime.of(2027, 3, 1, 12, 0, 0, 0, Fx.zone).toInstant().toEpochMilli()
        val starts = DemoSeeder.trips(now).filter { it.category == TripCategory.TRIP }.map { Instant.ofEpochMilli(it.startedAt).atZone(Fx.zone).toLocalDate() }
        assertTrue(starts.containsAll(listOf(LocalDate.of(2026, 11, 7), LocalDate.of(2026, 10, 9), LocalDate.of(2026, 11, 14), LocalDate.of(2026, 11, 15))))
    }

    @Test
    fun `future demo trips move back by whole weeks`() {
        // 2026-11-10(화) 12:00: 재정차(11-14 토)는 11-07 강릉 당일과 겹치지 않게 2주 전 10-31(토)로, 의암호(11-15 일)는 11-08(일)로 간다
        val now = ZonedDateTime.of(2026, 11, 10, 12, 0, 0, 0, Fx.zone).toInstant().toEpochMilli()
        val starts = DemoSeeder.trips(now).filter { it.category == TripCategory.TRIP }.map { Instant.ofEpochMilli(it.startedAt).atZone(Fx.zone).toLocalDate() }
        assertTrue(starts.toString(), starts.containsAll(listOf(LocalDate.of(2026, 11, 7), LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 8))))
    }

    @Test
    fun `the other demo trips stay trips`() {
        val trips = DemoSeeder.trips(Fx.at(13, 12)).filter { it.category != TripCategory.DAILY }
        assertEquals(5, trips.size)
        assertTrue(trips.all { it.category == TripCategory.TRIP })
    }

    @Test
    fun `every stop of the demo trips has an outside temperature`() {
        val trips = DemoSeeder.trips(Fx.at(13, 12)).filter { it.category != TripCategory.DAILY }
        assertTrue(trips.flatMap { it.stops }.isNotEmpty())
        assertTrue(trips.flatMap { it.stops }.all { it.outsideTempC != null })
    }
}
