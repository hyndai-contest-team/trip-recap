package kr.triprecap.app.domain

import kr.triprecap.app.domain.Fx.at
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripEditorTest {
    private val trip = Trip(
        drives = listOf(Fx.drive(at(7, 9), at(7, 11), Fx.home, Fx.beach)),
        stops = listOf(
            Stop(1, StopKind.VISIT, "경포해변", Fx.beach, at(7, 11), at(7, 12)),
            Stop(2, StopKind.VISIT, null, Fx.cafe, at(7, 13), at(7, 14)),
        ),
    )

    @Test
    fun `renaming the trip overrides the automatic title and blank restores it`() {
        val renamed = TripEditor.renameTrip(trip, "  가을 강릉  ")
        assertEquals("가을 강릉", renamed.title)
        assertEquals("경포해변 → 방문지 2", TripEditor.renameTrip(renamed, " ").title)
    }

    @Test
    fun `renaming a stop changes the title derived from it and stops later lookups`() {
        val t = TripEditor.renameStop(trip, 2, "안목 카페")
        assertEquals("경포해변 → 안목 카페", t.title)
        assertTrue(t.stops[1].placeResolved)
        assertEquals(trip, TripEditor.renameStop(trip, 2, "  "))
    }

    @Test
    fun `deleting a stop removes it and renumbers unnamed visits`() {
        val t = TripEditor.deleteStop(trip, 1)
        assertEquals(listOf(2), t.stops.map { it.id })
        assertEquals("방문지 1", t.title)
    }

    @Test
    fun `memo is trimmed and blank clears it`() {
        val t = TripEditor.setMemo(trip, 1, " 파도가 높았다 ")
        assertEquals("파도가 높았다", t.stops[0].memo)
        assertNull(TripEditor.setMemo(t, 1, "").stops[0].memo)
    }

    @Test
    fun `a stop can be excluded from sharing`() {
        assertEquals(listOf(false, true), TripEditor.setExcludedFromShare(trip, 2, true).stops.map { it.excludedFromShare })
    }
}
