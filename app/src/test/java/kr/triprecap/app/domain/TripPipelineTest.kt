package kr.triprecap.app.domain

import kr.triprecap.app.domain.Fx.at
import kr.triprecap.app.domain.TripRecorder.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripPipelineTest {
    private fun TripPipeline.driveTo(from: Long, to: Long, a: GeoPoint, b: GeoPoint, name: String? = null): List<TripPipeline.Update> = listOf(
        onEvent(Event.LocationChanged(LocationSample(a)), from),
        onEvent(Event.GearChanged(Gear.DRIVE), from),
        onEvent(Event.LocationChanged(LocationSample(b, name)), to - 1),
        onEvent(Event.GearChanged(Gear.PARK), to),
        onEvent(Event.IgnitionOff, to + 60_000L),
    )

    private fun List<TripPipeline.Update>.closed() = flatMap { it.closed }

    /** 집(Fx.home)에서 P 단으로 서 있다가 부모님 댁(Fx.beach)으로 가서 도어를 연다. 도착은 7일 14:00 */
    private fun TripPipeline.goToParents() {
        onEvent(Event.GearChanged(Gear.PARK), at(7, 8, 59))
        driveTo(at(7, 12), at(7, 14), Fx.home, Fx.beach, "부모님 댁")
        onEvent(Event.DoorChanged(true), at(7, 14, 2))
    }

    /** 8일 오전에 부모님 댁에서 집으로 돌아온다 */
    private fun TripPipeline.comeHome(): List<TripPipeline.Update> {
        val u = listOf(onEvent(Event.GearChanged(Gear.PARK), at(8, 9, 50))) + driveTo(at(8, 10), at(8, 12), Fx.beach, Fx.home)
        return u + tick(at(8, 12, 5))
    }

    @Test
    fun `after a restart the time since the last saved drive becomes a stay`() {
        val first = TripPipeline().apply { places = Fx.places }
        first.onEvent(Event.GearChanged(Gear.PARK), at(7, 8, 59))
        first.driveTo(at(7, 9), at(7, 11), Fx.home, Fx.beach)
        val saved = first.open!!

        // 시동 OFF 로 프로세스가 끝났다가 다시 켜졌다
        val second = TripPipeline().apply { places = Fx.places; restore(saved) }
        second.onEvent(Event.GearChanged(Gear.PARK), at(7, 12, 29))
        second.driveTo(at(7, 12, 30), at(7, 13), Fx.beach, Fx.cafe)
        val stop = second.open!!.stops.single()
        assertEquals(StopKind.VISIT, stop.kind)
        assertEquals(at(7, 11), stop.arrivedAt)
        assertEquals(at(7, 12, 30), stop.departedAt)
        assertEquals(Fx.beach, stop.point)
        assertEquals(2, second.open!!.drives.size)
    }

    @Test
    fun `a stop gets the outside temperature known on arrival`() {
        val p = TripPipeline().apply { places = Fx.places }
        p.onEvent(Event.GearChanged(Gear.PARK), at(7, 8, 59))
        p.onEvent(Event.OutsideTempChanged(13.6), at(7, 8, 59))
        p.driveTo(at(7, 9), at(7, 11), Fx.home, Fx.beach)
        p.onEvent(Event.OutsideTempChanged(17.0), at(7, 12))
        p.driveTo(at(7, 12, 30), at(7, 13), Fx.beach, Fx.cafe)
        assertEquals(listOf(14), p.open!!.stops.map { it.outsideTempC })
    }

    @Test
    fun `after a restart the stay takes the temperature saved with the last drive`() {
        val first = TripPipeline().apply { places = Fx.places }
        first.onEvent(Event.GearChanged(Gear.PARK), at(7, 8, 59))
        first.onEvent(Event.OutsideTempChanged(13.6), at(7, 8, 59))
        first.driveTo(at(7, 9), at(7, 11), Fx.home, Fx.beach)
        val saved = first.open!!
        assertEquals(14, saved.drives.last().arrivalTempC)

        val second = TripPipeline().apply { places = Fx.places; restore(saved) }
        second.onEvent(Event.GearChanged(Gear.PARK), at(7, 12, 29))
        second.onEvent(Event.OutsideTempChanged(5.0), at(7, 12, 29))
        second.driveTo(at(7, 12, 30), at(7, 13), Fx.beach, Fx.cafe)
        assertEquals(14, second.open!!.stops.single().outsideTempC)
    }

    @Test
    fun `after a restart without a saved temperature the first one before departing is used`() {
        val first = TripPipeline().apply { places = Fx.places }
        first.onEvent(Event.GearChanged(Gear.PARK), at(7, 8, 59))
        first.driveTo(at(7, 9), at(7, 11), Fx.home, Fx.beach)
        val saved = first.open!!

        val second = TripPipeline().apply { places = Fx.places; restore(saved) }
        second.onEvent(Event.GearChanged(Gear.PARK), at(7, 12, 28))
        second.onEvent(Event.OutsideTempChanged(9.4), at(7, 12, 28))
        second.onEvent(Event.OutsideTempChanged(11.0), at(7, 12, 29))
        second.driveTo(at(7, 12, 30), at(7, 13), Fx.beach, Fx.cafe)
        assertEquals(9, second.open!!.stops.single().outsideTempC)
    }

    @Test
    fun `live shows the ongoing stay as a stop without closing the trip`() {
        val p = TripPipeline().apply { places = Fx.places }
        p.onEvent(Event.GearChanged(Gear.PARK), at(7, 8, 59))
        p.driveTo(at(7, 9), at(7, 11), Fx.home, Fx.beach)
        p.onEvent(Event.DoorChanged(true), at(7, 11, 2))
        assertTrue(p.live(at(7, 11, 5))!!.stops.isEmpty().not())
        assertEquals(StopKind.BRIEF, p.live(at(7, 11, 5))!!.stops.single().kind)
        assertEquals(StopKind.VISIT, p.live(at(7, 11, 30))!!.stops.single().kind)
        assertTrue(p.open!!.stops.isEmpty())
    }

    @Test
    fun `tick closes a trip once the car has been parked longer than the lodging limit`() {
        val p = TripPipeline().apply { places = Fx.places }
        p.onEvent(Event.GearChanged(Gear.PARK), at(7, 8, 59))
        p.driveTo(at(7, 9), at(7, 11), Fx.home, Fx.beach)
        p.onEvent(Event.DoorChanged(true), at(7, 11, 2))
        assertTrue(p.tick(at(8, 11)).closed.isEmpty())
        val closed = p.tick(at(9, 11, 1)).closed.single()
        assertEquals(1, closed.stops.size)
        assertNull(p.open)
    }

    @Test
    fun `tick closes the trip after 6 hours away from home at the 6 hour mark`() {
        val p = TripPipeline().apply { places = Fx.places }
        p.goToParents()
        assertTrue(p.tick(at(7, 19, 59)).closed.isEmpty())
        val t = p.tick(at(7, 20, 1)).closed.single()
        assertTrue(t.endedAway)
        assertEquals(at(7, 20), t.stops.single().departedAt)
        assertEquals(TripCategory.TRIP, t.category)
        assertNull(p.open)
        assertEquals(t, p.lastClosed)
    }

    @Test
    fun `parked overnight away the trip is closed at 6 hours, reopened at 3 am and continued home`() {
        val p = TripPipeline().apply { places = Fx.places }
        p.goToParents()
        val closed = p.tick(at(7, 20, 1)).closed.single()
        assertNull(p.tick(at(8, 2, 59)).reopened)
        val back = p.tick(at(8, 3, 0)).reopened!!
        assertEquals(closed.startedAt, back.startedAt)
        assertEquals(back, p.open)
        assertFalse(back.closed)
        val final = p.comeHome().closed().single()
        assertEquals(closed.startedAt, final.startedAt)
        assertEquals(listOf(StopKind.LODGING), final.stops.map { it.kind })
        assertEquals(TripCategory.TRIP, final.category)
        assertFalse(final.endedAway)
    }

    @Test
    fun `without ticks while parked the overnight trip ends the same`() {
        val ticked = TripPipeline().apply { places = Fx.places }
        ticked.goToParents()
        ticked.tick(at(7, 20, 1)); ticked.tick(at(8, 3))
        val withTicks = ticked.comeHome().closed().single()

        val quiet = TripPipeline().apply { places = Fx.places }
        quiet.goToParents()
        assertEquals(withTicks, quiet.comeHome().closed().single())
    }

    @Test
    fun `after a restart the first tick closes a trip parked more than 6 hours away`() {
        val first = TripPipeline().apply { places = Fx.places }
        first.goToParents()
        val saved = first.open!!

        val second = TripPipeline().apply { places = Fx.places; restore(saved) }
        val t = second.tick(at(7, 22)).closed.single()
        assertTrue(t.endedAway)
        assertEquals(at(7, 20), t.stops.single().departedAt)
        assertEquals(StopKind.VISIT, t.stops.single().kind)
    }

    @Test
    fun `a trip closed away before a restart is reopened on the first event after it`() {
        val first = TripPipeline().apply { places = Fx.places }
        first.goToParents()
        val closedAway = first.tick(at(7, 20, 1)).closed.single().copy(id = 5)

        // 밤에 시동이 꺼져 프로세스가 끝났다. 다음 날 아침 다시 켜졌다
        val second = TripPipeline().apply { places = Fx.places; restore(null, closedAway) }
        val back = second.onEvent(Event.GearChanged(Gear.PARK), at(8, 9, 50)).reopened!!
        assertEquals(5L, back.id)
        val final = second.comeHome().closed().single()
        assertEquals(5L, final.id)
        assertEquals(listOf(StopKind.LODGING), final.stops.map { it.kind })
        assertEquals(at(7, 14), final.stops.single().arrivedAt)
        assertEquals(at(8, 10), final.stops.single().departedAt)
        assertEquals("부모님 댁", final.stops.single().name)
    }

    @Test
    fun `an open trip restored the next morning keeps the overnight stay as lodging`() {
        val first = TripPipeline().apply { places = Fx.places }
        first.goToParents()
        val saved = first.open!!

        val second = TripPipeline().apply { places = Fx.places; restore(saved, null) }
        val u = second.onEvent(Event.GearChanged(Gear.PARK), at(8, 9, 50))
        assertTrue(u.closed.isEmpty())
        assertNull(u.reopened)
        val final = second.comeHome().closed().single()
        assertEquals(listOf(StopKind.LODGING), final.stops.map { it.kind })
        assertEquals(saved.startedAt, final.startedAt)
    }

    @Test
    fun `every ended stay is summarized, including the night at home`() {
        val p = TripPipeline().apply { places = Fx.places }
        val u = mutableListOf<TripPipeline.Update>()
        u += p.onEvent(Event.GearChanged(Gear.PARK), at(7, 8, 59))
        u += p.driveTo(at(7, 9), at(7, 11), Fx.home, Fx.beach)
        u += p.onEvent(Event.DoorChanged(true), at(7, 11, 2))
        u += p.driveTo(at(7, 12), at(7, 15), Fx.beach, Fx.home)
        u += p.driveTo(at(8, 9), at(8, 9, 30), Fx.home, Fx.cafe)
        val s = u.flatMap { it.summaries }
        assertEquals(
            listOf(
                StopSummary(StopLog.round(Fx.beach), at(7, 11), at(7, 12), visit = true),
                StopSummary(StopLog.round(Fx.home), at(7, 15), at(8, 9), visit = false),
            ),
            s,
        )
        // 파이프라인이 낸 요약은 판정용 기록에도 덧붙는다
        assertEquals(s, p.stopLog)
    }

    @Test
    fun `a single visit to a place in the stop log three times is daily`() {
        val shop = Fx.north(Fx.home, 2_000.0)
        val p = TripPipeline().apply {
            places = Fx.places
            stopLog = (1..3).map { Fx.summary(at(it, 19), at(it, 19, 30), StopLog.round(shop)) }
        }
        p.onEvent(Event.GearChanged(Gear.PARK), at(7, 8, 59))
        p.driveTo(at(7, 9), at(7, 9, 10), Fx.home, shop)
        p.onEvent(Event.DoorChanged(true), at(7, 9, 12))
        val t = p.driveTo(at(7, 9, 40), at(7, 9, 50), shop, Fx.home).closed().single()
        assertEquals(TripCategory.DAILY, t.category)
    }

    @Test
    fun `clear forgets the trip closed away`() {
        val p = TripPipeline().apply { places = Fx.places }
        p.goToParents()
        p.tick(at(7, 20, 1))
        p.clear()
        assertNull(p.lastClosed)
        assertNull(p.tick(at(8, 3, 1)).reopened)
    }
}
