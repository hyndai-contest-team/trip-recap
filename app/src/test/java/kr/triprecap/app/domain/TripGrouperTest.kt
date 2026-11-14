package kr.triprecap.app.domain

import kr.triprecap.app.domain.Fx.at
import kr.triprecap.app.domain.Fx.beach
import kr.triprecap.app.domain.Fx.cafe
import kr.triprecap.app.domain.Fx.drive
import kr.triprecap.app.domain.Fx.home
import kr.triprecap.app.domain.Fx.market
import kr.triprecap.app.domain.Fx.office
import kr.triprecap.app.domain.Fx.stay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripGrouperTest {
    private val g = TripGrouper(StopClassifier(Fx.zone))
    private var open: Trip? = null
    private val closed = mutableListOf<Trip>()
    private var places = Fx.places
    private var log = emptyList<StopSummary>()
    private var lastClosed: Trip? = null
    private val reopened = mutableListOf<Trip>()

    private fun apply(r: TripGrouper.Result) {
        open = r.open
        closed += r.closed
        r.closed.lastOrNull()?.let { lastClosed = it }
        r.reopened?.let { reopened += it }
    }

    private fun add(stayBefore: Stay?, d: Drive) = apply(g.onDrive(open, stayBefore, d, places, log, lastClosed))

    private fun settle(current: Stay?) = apply(g.settle(open, current, places, log, lastClosed))

    @Test
    fun `first drive opens a trip`() {
        add(null, drive(at(7, 9), at(7, 11), home, beach))
        assertEquals(1, open!!.drives.size)
        assertFalse(open!!.closed)
        assertTrue(closed.isEmpty())
    }

    @Test
    fun `drives less than 6 hours apart join one trip with the stay between as a visit`() {
        add(null, drive(at(7, 9), at(7, 11), home, beach))
        add(stay(at(7, 11), at(7, 12, 20), beach, name = "경포해변", rainy = true), drive(at(7, 12, 20), at(7, 13), beach, cafe))
        val t = open!!
        assertEquals(2, t.drives.size)
        val v = t.stops.single()
        assertEquals(StopKind.VISIT, v.kind)
        assertEquals("경포해변", v.name)
        assertEquals(80L, v.stayMinutes)
        assertTrue(v.rainy)
        assertEquals(1, v.id)
    }

    @Test
    fun `arriving within 300m of home closes the trip`() {
        add(null, drive(at(7, 9), at(7, 11), home, beach))
        add(stay(at(7, 11), at(7, 12), beach), drive(at(7, 12), at(7, 15), beach, Fx.north(home, 200.0)))
        assertNull(open)
        val t = closed.single()
        assertTrue(t.closed)
        assertEquals(1, t.visits.size)
        assertFalse(t.endedAway)
        // 강릉까지 간 방문지 1곳: 30km 이상이므로 확실한 여행
        assertEquals(TripCategory.TRIP, t.category)
    }

    @Test
    fun `home to office and back with no visit is kept as two daily trips`() {
        add(null, drive(at(9, 8), at(9, 9), home, office))
        add(stay(at(9, 9), at(9, 18), office), drive(at(9, 18), at(9, 19), office, home))
        assertNull(open)
        assertEquals(listOf(TripCategory.DAILY, TripCategory.DAILY), closed.map { it.category })
        // 아침 이동은 회사에 6시간 서 있던 시점에 닫혔다
        assertEquals(listOf(true, false), closed.map { it.endedAway })
        assertTrue(closed.all { it.closed })
    }

    @Test
    fun `office stay is not kept as a visit when a lunch trip happens in between`() {
        add(null, drive(at(9, 8), at(9, 9), home, office))
        add(stay(at(9, 9), at(9, 12), office), drive(at(9, 12), at(9, 12, 20), office, market))
        add(stay(at(9, 12, 20), at(9, 13), market, name = "식당"), drive(at(9, 13), at(9, 13, 20), market, office))
        add(stay(at(9, 13, 20), at(9, 18), office), drive(at(9, 18), at(9, 19), office, home))
        val t = closed.single()
        assertEquals(listOf("식당"), t.stops.map { it.name })
        assertEquals(4, t.drives.size)
    }

    @Test
    fun `round trip from home with only a brief stop nearby is ambiguous`() {
        val shop = Fx.north(home, 2_000.0)
        add(null, drive(at(7, 9), at(7, 9, 10), home, shop))
        add(stay(at(7, 9, 10), at(7, 9, 15), shop), drive(at(7, 9, 15), at(7, 9, 25), shop, home))
        assertNull(open)
        assertEquals(TripCategory.PENDING, closed.single().category)
    }

    @Test
    fun `a single visit to a regular place is daily`() {
        val shop = Fx.north(home, 2_000.0)
        log = (1..3).map { Fx.summary(at(it, 19), at(it, 19, 30), shop) }
        add(null, drive(at(7, 9), at(7, 9, 10), home, shop))
        add(stay(at(7, 9, 10), at(7, 9, 40), shop), drive(at(7, 9, 40), at(7, 9, 50), shop, home))
        assertEquals(StopKind.VISIT, closed.single().stops.single().kind)
        assertEquals(TripCategory.DAILY, closed.single().category)
    }

    @Test
    fun `gap of 6 hours or more splits trips and the long stay ends the earlier trip at the 6 hour mark`() {
        add(null, drive(at(7, 9), at(7, 10), home, beach))
        add(stay(at(7, 10), at(7, 17), beach, name = "경포해변"), drive(at(7, 17), at(7, 18), beach, cafe))
        val first = closed.single()
        assertEquals(listOf("경포해변"), first.stops.map { it.name })
        assertEquals(1, first.drives.size)
        assertEquals(at(7, 16), first.endedAt)
        assertTrue(first.endedAway)
        assertEquals(1, open!!.drives.size)
        assertTrue(open!!.stops.isEmpty())
    }

    @Test
    fun `overnight lodging away from home keeps the trip going`() {
        add(null, drive(at(7, 9), at(7, 12), home, beach))
        add(stay(at(7, 12), at(7, 13), beach), drive(at(7, 13), at(7, 20), beach, cafe))
        add(stay(at(7, 20), at(8, 9), cafe, name = "호텔"), drive(at(8, 9), at(8, 10), cafe, market))
        val t = open!!
        assertTrue(closed.isEmpty())
        assertEquals(3, t.drives.size)
        assertEquals(listOf(StopKind.VISIT, StopKind.LODGING), t.stops.map { it.kind })
        assertEquals(listOf(1, 2), t.stops.map { it.id })
    }

    @Test
    fun `without home an overnight stay ends the trip at the 6 hour mark and is not reopened`() {
        places = KnownPlaces()
        add(null, drive(at(7, 9), at(7, 20), home, beach))
        add(stay(at(7, 20), at(8, 9), beach), drive(at(8, 9), at(8, 10), beach, market))
        assertEquals(1, closed.size)
        // 6시간째(오전 2시)에 닫혀 그때까지의 정차만 붙는다. 오전 3시를 넣지 않았으므로 숙박이 아니다
        val stop = closed[0].stops.single()
        assertEquals(StopKind.VISIT, stop.kind)
        assertEquals(at(8, 2), stop.departedAt)
        assertTrue(reopened.isEmpty())
        assertEquals(1, open!!.drives.size)
    }

    @Test
    fun `re-parking within 150m merges with the previous stop`() {
        add(null, drive(at(7, 9), at(7, 11), home, beach))
        add(stay(at(7, 11), at(7, 11, 5), beach, door = false), drive(at(7, 11, 5), at(7, 11, 7), beach, Fx.north(beach, 120.0)))
        add(stay(at(7, 11, 7), at(7, 11, 45), Fx.north(beach, 120.0)), drive(at(7, 11, 45), at(7, 12), beach, cafe))
        val s = open!!.stops.single()
        assertEquals(StopKind.VISIT, s.kind)
        assertEquals(at(7, 11), s.arrivedAt)
        assertEquals(at(7, 11, 45), s.departedAt)
        assertEquals(1, s.id)
    }

    @Test
    fun `a third stop near the first is not merged across another stop`() {
        add(null, drive(at(7, 9), at(7, 11), home, beach))
        add(stay(at(7, 11), at(7, 11, 30), beach), drive(at(7, 11, 30), at(7, 12), beach, cafe))
        add(stay(at(7, 12), at(7, 12, 30), cafe), drive(at(7, 12, 30), at(7, 13), cafe, beach))
        add(stay(at(7, 13), at(7, 13, 30), beach), drive(at(7, 13, 30), at(7, 14), beach, market))
        assertEquals(3, open!!.stops.size)
    }

    @Test
    fun `settle leaves a trip open while the current stay is short`() {
        add(null, drive(at(7, 9), at(7, 11), home, beach))
        settle(stay(at(7, 11), at(7, 16), beach))
        assertEquals(1, open!!.drives.size)
        assertTrue(closed.isEmpty())
    }

    @Test
    fun `settle closes a trip after 6 hours parked away from home and keeps the last stay`() {
        add(null, drive(at(7, 9), at(7, 11), home, beach))
        settle(stay(at(7, 11), at(7, 17), beach, name = "경포해변"))
        assertNull(open)
        assertEquals("경포해변", closed.single().stops.single().name)
    }

    @Test
    fun `settle closes at the 6 hour mark however late it is called`() {
        add(null, drive(at(7, 9), at(7, 14), home, beach))
        settle(stay(at(7, 14), at(7, 22, 30), beach))
        val t = closed.single()
        assertEquals(at(7, 20), t.stops.single().departedAt)
        assertEquals(at(7, 20), t.endedAt)
        assertTrue(t.endedAway)
        assertEquals(TripCategory.TRIP, t.category)
    }

    @Test
    fun `a trip closed away from home reopens when the stay becomes lodging and continues on the next drive`() {
        add(null, drive(at(7, 9), at(7, 14), home, beach))
        open = open!!.copy(id = 7)
        settle(stay(at(7, 14), at(7, 20, 1), beach, name = "부모님 댁"))
        assertNull(open)
        // 오전 3시 전: 아직 숙박이 아니다
        settle(stay(at(7, 14), at(8, 2), beach))
        assertNull(open)
        settle(stay(at(7, 14), at(8, 3), beach))
        val back = reopened.single()
        assertEquals(7L, back.id)
        assertFalse(back.closed)
        assertFalse(back.endedAway)
        assertEquals(back, open)
        assertEquals(StopKind.LODGING, g.preview(open!!, stay(at(7, 14), at(8, 3), beach), places).stops.single().kind)
        add(stay(at(7, 14), at(8, 9), beach), drive(at(8, 9), at(8, 12), beach, home))
        assertNull(open)
        val t = closed.last()
        assertEquals(2, closed.size)
        assertEquals(7L, t.id)
        assertEquals(2, t.drives.size)
        val lodging = t.stops.single()
        assertEquals(StopKind.LODGING, lodging.kind)
        assertEquals("부모님 댁", lodging.name)
        assertEquals(at(7, 14), lodging.arrivedAt)
        assertEquals(at(8, 9), lodging.departedAt)
        assertFalse(t.endedAway)
        assertEquals(TripCategory.TRIP, t.category)
    }

    @Test
    fun `the next drive reopens a trip closed away when the stay turned into lodging`() {
        add(null, drive(at(7, 9), at(7, 14), home, beach))
        settle(stay(at(7, 14), at(7, 21), beach))
        add(stay(at(7, 14), at(8, 9), beach), drive(at(8, 9), at(8, 10), beach, cafe))
        // 다시 연 여행이 이번 드라이브로 이어졌다
        assertEquals(1, reopened.size)
        assertEquals(2, open!!.drives.size)
        assertEquals(StopKind.LODGING, open!!.stops.single().kind)
    }

    @Test
    fun `reopened then closed at home in one drive is reported only as closed`() {
        add(null, drive(at(7, 9), at(7, 14), home, beach))
        settle(stay(at(7, 14), at(7, 21), beach))
        add(stay(at(7, 14), at(8, 9), beach), drive(at(8, 9), at(8, 12), beach, home))
        assertTrue(reopened.isEmpty())
        assertEquals(StopKind.LODGING, closed.last().stops.single().kind)
        assertEquals(closed.first().startedAt, closed.last().startedAt)
    }

    @Test
    fun `a trip closed away is not reopened after more than 48 hours`() {
        add(null, drive(at(7, 9), at(7, 14), home, beach))
        settle(stay(at(7, 14), at(7, 21), beach))
        add(stay(at(7, 14), at(9, 14, 1), beach), drive(at(9, 14, 1), at(9, 15), beach, cafe))
        assertTrue(reopened.isEmpty())
        assertEquals(1, closed.size)
        assertEquals(1, open!!.drives.size)
    }

    @Test
    fun `a trip closed on arriving home is never reopened`() {
        add(null, drive(at(7, 9), at(7, 20), beach, home))
        settle(stay(at(7, 20), at(8, 9), home))
        add(stay(at(7, 20), at(8, 9), home), drive(at(8, 9), at(8, 10), home, cafe))
        assertTrue(reopened.isEmpty())
        assertEquals(1, open!!.drives.size)
    }

    @Test
    fun `lodging longer than 48 hours closes the trip at the 48 hour mark`() {
        add(null, drive(at(7, 9), at(7, 20), home, beach))
        settle(stay(at(7, 20), at(9, 20), beach))
        assertTrue(closed.isEmpty())
        settle(stay(at(7, 20), at(9, 23), beach))
        val stop = closed.single().stops.single()
        assertEquals(at(9, 20) + 1, stop.departedAt)
        assertTrue(closed.single().endedAway)
    }

    @Test
    fun `settle keeps the trip open during overnight lodging`() {
        add(null, drive(at(7, 9), at(7, 20), home, beach))
        settle(stay(at(7, 20), at(8, 8), beach))
        assertEquals(1, open!!.drives.size)
        assertTrue(open!!.stops.isEmpty())
    }

    @Test
    fun `trip with unknown start location is kept even without visits`() {
        add(null, drive(at(7, 9), at(7, 11), null, null))
        settle(stay(at(7, 11), at(7, 18), null, door = false))
        assertEquals(1, closed.size)
    }

    @Test
    fun `people count is the largest number seated in one drive and rain comes from any drive`() {
        add(null, drive(at(7, 9), at(7, 11), home, beach, seats = 3))
        add(stay(at(7, 11), at(7, 12), beach), drive(at(7, 12), at(7, 13), beach, cafe, rainy = true, seats = 2))
        assertEquals(3, open!!.peopleCount)
        assertTrue(open!!.rainy)
    }

    @Test
    fun `title joins first and last visit names and numbers unnamed visits`() {
        add(null, drive(at(7, 9), at(7, 11), home, beach))
        add(stay(at(7, 11), at(7, 12), beach, name = "경포해변"), drive(at(7, 12), at(7, 13), beach, cafe))
        assertEquals("경포해변", open!!.title)
        add(stay(at(7, 13), at(7, 13, 5), cafe), drive(at(7, 13, 5), at(7, 14), cafe, market))
        add(stay(at(7, 14), at(7, 15), market), drive(at(7, 15), at(7, 16), market, cafe))
        assertEquals("경포해변 → 방문지 2", open!!.title)
        assertEquals("잠깐 정차", open!!.nameOf(open!!.stops[1]))
    }
}
