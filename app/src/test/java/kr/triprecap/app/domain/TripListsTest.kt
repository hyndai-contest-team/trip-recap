package kr.triprecap.app.domain

import kr.triprecap.app.domain.Fx.at
import org.junit.Assert.assertEquals
import org.junit.Test

class TripListsTest {
    private val elsewhere = GeoPoint(37.60, 127.30)

    private fun move(id: Long, day: Int, h: Int, m: Int, from: GeoPoint?, to: GeoPoint?, minutes: Int = 45, category: TripCategory = TripCategory.DAILY, closed: Boolean = true) =
        Trip(id = id, drives = listOf(Fx.drive(at(day, h, m), at(day, h, m) + minutes * 60_000L, from, to)), closed = closed, category = category)

    private val withVisit = Trip(
        id = 9, drives = listOf(Fx.drive(at(12, 9), at(12, 10), Fx.home, Fx.beach)),
        stops = listOf(Stop(1, StopKind.VISIT, "경포해변", Fx.beach, at(12, 10), at(12, 11))),
        closed = false,
    )

    @Test
    fun `the list shows trips only - closed ones and open ones with a visit`() {
        val trips = listOf(
            move(1, 12, 8, 0, Fx.home, Fx.office, category = TripCategory.TRIP),
            move(2, 12, 9, 0, Fx.home, Fx.office, category = TripCategory.DAILY),
            move(3, 12, 10, 0, Fx.home, Fx.home, category = TripCategory.PENDING),
            move(4, 12, 11, 0, Fx.home, null, category = TripCategory.TRIP, closed = false),
            withVisit,
        )
        assertEquals(listOf(1L, 9L), TripLists.listed(trips).map { it.id })
    }

    @Test
    fun `daily moves are the closed daily ones, newest first`() {
        val trips = listOf(
            move(1, 11, 18, 40, Fx.office, Fx.home),
            move(2, 12, 8, 10, Fx.home, Fx.office),
            move(3, 12, 9, 0, Fx.home, Fx.office, category = TripCategory.TRIP),
            move(4, 12, 10, 0, Fx.home, Fx.office, category = TripCategory.PENDING),
        )
        assertEquals(listOf(2L, 1L), TripLists.daily(trips).map { it.id })
    }

    @Test
    fun `the list footer counts daily moves`() {
        assertEquals("일상 이동 2건 · 7일 뒤 자동으로 지워요", TripLists.dailyFooter(2))
    }

    @Test
    fun `a daily move row has the start, duration, distance and the ends when they are home or office`() {
        val row = DailyMoveRow.of(move(2, 12, 8, 10, Fx.home, Fx.office, minutes = 45), Fx.places, Fx.zone, now = at(13, 9))
        assertEquals("11월 12일 (목) 8:10", row.title)
        assertEquals("집 → 회사 · 45분 · ${Texts.distance(Fx.home.distanceM(Fx.office))}", row.meta)
    }

    @Test
    fun `the ends are left out when either one is unknown`() {
        val row = DailyMoveRow.of(move(2, 12, 18, 5, Fx.office, elsewhere, minutes = 70), Fx.places, Fx.zone, now = at(13, 9))
        assertEquals("11월 12일 (목) 18:05", row.title)
        assertEquals("1시간 10분 · ${Texts.distance(Fx.office.distanceM(elsewhere))}", row.meta)
        assertEquals(
            "45분 · ${Texts.distance(Fx.home.distanceM(Fx.office))}",
            DailyMoveRow.of(move(2, 12, 8, 10, Fx.home, Fx.office), KnownPlaces(), Fx.zone, at(13, 9)).meta,
        )
    }
}
