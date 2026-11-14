package kr.triprecap.app.domain

import kr.triprecap.app.domain.Fx.at
import kr.triprecap.app.domain.Fx.stay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StopClassifierTest {
    private val c = StopClassifier(Fx.zone)
    private fun kind(s: Stay, places: KnownPlaces = Fx.places) = c.classify(s, places)

    @Test
    fun `parked 10 minutes with a door opened is a visit`() {
        assertEquals(StopKind.VISIT, kind(stay(at(7, 11, 0), at(7, 11, 10), Fx.beach)))
    }

    @Test
    fun `parked under 10 minutes is a brief stop even with a door opened`() {
        assertEquals(StopKind.BRIEF, kind(stay(at(7, 11, 0), at(7, 11, 9), Fx.beach)))
    }

    @Test
    fun `parked long without any door opened is a brief stop`() {
        assertEquals(StopKind.BRIEF, kind(stay(at(7, 11, 0), at(7, 12, 0), Fx.beach, door = false)))
    }

    @Test
    fun `stay within 300m of home or office is not a stop`() {
        assertNull(kind(stay(at(7, 11, 0), at(7, 12, 0), Fx.north(Fx.home, 250.0))))
        assertNull(kind(stay(at(7, 9, 0), at(7, 18, 0), Fx.north(Fx.office, 250.0))))
        assertEquals(StopKind.VISIT, kind(stay(at(7, 11, 0), at(7, 12, 0), Fx.north(Fx.home, 350.0))))
    }

    @Test
    fun `stay with unknown location is classified by time and door only`() {
        assertEquals(StopKind.VISIT, kind(stay(at(7, 11, 0), at(7, 12, 0), null)))
    }

    @Test
    fun `six hours or more across 3 am is lodging`() {
        assertEquals(StopKind.LODGING, kind(stay(at(7, 22, 0), at(8, 9, 0), Fx.beach)))
        assertEquals(StopKind.LODGING, kind(stay(at(7, 21, 0), at(8, 3, 0), Fx.beach, door = false)))
    }

    @Test
    fun `six hours in daytime is a visit not lodging`() {
        assertEquals(StopKind.VISIT, kind(stay(at(7, 10, 0), at(7, 17, 0), Fx.beach)))
    }

    @Test
    fun `under six hours at night is a visit not lodging`() {
        assertEquals(StopKind.VISIT, kind(stay(at(8, 0, 0), at(8, 5, 0), Fx.beach)))
    }

    @Test
    fun `more than 48 hours parked is not lodging`() {
        assertEquals(StopKind.VISIT, kind(stay(at(7, 22, 0), at(10, 9, 0), Fx.beach)))
    }

    @Test
    fun `re-parking within 150m of the previous stop merges into one stay`() {
        val prev = Stop(1, StopKind.BRIEF, "주차장", Fx.beach, at(7, 11, 0), at(7, 11, 5), rainy = true, doorOpened = false)
        val merged = c.merge(prev, stay(at(7, 11, 7), at(7, 11, 40), Fx.north(Fx.beach, 120.0), door = true, name = "경포해변"))!!
        assertEquals(at(7, 11, 0), merged.arrivedAt)
        assertEquals(at(7, 11, 40), merged.departedAt)
        assertEquals(true, merged.doorOpened)
        assertEquals(true, merged.rainy)
        assertEquals("주차장", merged.placeName)
        assertEquals(Fx.beach, merged.point)
        assertEquals(StopKind.VISIT, kind(merged))
    }

    @Test
    fun `merged stay keeps the outside temperature of the stop that came first`() {
        val prev = Stop(1, StopKind.BRIEF, "주차장", Fx.beach, at(7, 11, 0), at(7, 11, 5), outsideTempC = 12)
        val next = stay(at(7, 11, 7), at(7, 11, 40), Fx.north(Fx.beach, 120.0)).copy(outsideTempC = 14)
        assertEquals(12, c.merge(prev, next)!!.outsideTempC)
    }

    @Test
    fun `merged stay takes the later temperature when the first stop had none`() {
        val prev = Stop(1, StopKind.BRIEF, "주차장", Fx.beach, at(7, 11, 0), at(7, 11, 5))
        val next = stay(at(7, 11, 7), at(7, 11, 40), Fx.north(Fx.beach, 120.0)).copy(outsideTempC = 14)
        assertEquals(14, c.merge(prev, next)!!.outsideTempC)
    }

    @Test
    fun `re-parking farther than 150m does not merge`() {
        val prev = Stop(1, StopKind.VISIT, null, Fx.beach, at(7, 11, 0), at(7, 11, 30))
        assertNull(c.merge(prev, stay(at(7, 11, 33), at(7, 12, 0), Fx.north(Fx.beach, 160.0))))
        assertNull(c.merge(prev, stay(at(7, 11, 33), at(7, 12, 0), null)))
    }
}
