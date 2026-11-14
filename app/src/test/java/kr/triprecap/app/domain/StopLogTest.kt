package kr.triprecap.app.domain

import kr.triprecap.app.domain.Fx.at
import kr.triprecap.app.domain.Fx.summary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StopLogTest {

    @Test
    fun `summarize keeps one entry per stop with a point, for every kind`() {
        val trip = Samples.gangneung
        val log = StopLog.summarize(trip)
        assertEquals(4, log.size)
        assertEquals(listOf(true, true, true, false), log.map { it.visit })
        assertEquals(trip.stops.map { it.arrivedAt }, log.map { it.arrivedAt })
        assertEquals(trip.stops.map { it.departedAt }, log.map { it.departedAt })
    }

    @Test
    fun `lodging is summarized as not a visit`() {
        val log = StopLog.summarize(Samples.jeju)
        assertEquals(8, log.size)
        assertFalse(log[2].visit)
        assertFalse(log[5].visit)
        assertTrue(log[0].visit)
    }

    @Test
    fun `stop without a point is skipped`() {
        val trip = Samples.lake.copy(
            stops = Samples.lake.stops + Stop(2, StopKind.VISIT, null, null, at(15, 13, 10), at(15, 14, 0)),
        )
        assertEquals(1, StopLog.summarize(trip).size)
    }

    @Test
    fun `point is rounded to 3 decimal places`() {
        val trip = Samples.lake.copy(
            stops = listOf(Stop(1, StopKind.VISIT, "x", GeoPoint(37.51449, 127.10551), at(15, 11), at(15, 12))),
        )
        assertEquals(GeoPoint(37.514, 127.106), StopLog.summarize(trip).single().point)
    }

    @Test
    fun `stay summary is rounded and marks a visit only for the VISIT kind`() {
        val s = Fx.stay(at(7, 22), at(8, 8), GeoPoint(37.51449, 127.10551))
        assertEquals(StopSummary(GeoPoint(37.514, 127.106), at(7, 22), at(8, 8), false), StopLog.summarize(s, null))
        assertFalse(StopLog.summarize(s, StopKind.LODGING)!!.visit)
        assertTrue(StopLog.summarize(s, StopKind.VISIT)!!.visit)
        assertNull(StopLog.summarize(Fx.stay(at(7, 22), at(8, 8), null), null))
    }

    @Test
    fun `prune drops entries that departed more than 30 days ago`() {
        val now = at(13, 12)
        val old = summary(now - StopLog.RETENTION_MS - 2 * 3_600_000L, now - StopLog.RETENTION_MS - 1, Fx.beach)
        val edge = summary(now - StopLog.RETENTION_MS - 3_600_000L, now - StopLog.RETENTION_MS, Fx.beach)
        val recent = summary(at(12, 10), at(12, 11), Fx.cafe)
        assertEquals(listOf(edge, recent), StopLog.prune(listOf(old, edge, recent), now))
    }

    @Test
    fun `retention is 30 days`() {
        assertEquals(30L * 24 * 3_600_000L, StopLog.RETENTION_MS)
    }
}
