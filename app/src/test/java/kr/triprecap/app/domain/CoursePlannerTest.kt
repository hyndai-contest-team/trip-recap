package kr.triprecap.app.domain

import kr.triprecap.app.domain.Fx.at
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoursePlannerTest {
    private fun p(i: Int) = GeoPoint(37.0 + i * 0.01, 128.0)
    private fun stop(id: Int, name: String?, kind: StopKind = StopKind.VISIT, stayMin: Int = 30, point: GeoPoint? = p(id)) =
        Stop(id, kind, name, point, at(7, 9 + id), at(7, 9 + id) + stayMin * 60_000L)

    private fun trip(vararg stops: Stop) = Trip(
        drives = listOf(Fx.drive(at(7, 9, 10), at(7, 16, 30), Fx.home, Fx.home)),
        stops = stops.toList(),
    )

    private fun names(legs: List<CourseLeg>) = legs.map { it.waypoints.map { w -> w.name } to it.destination.name }

    @Test
    fun `up to three places fit in one leg with at most two waypoints`() {
        assertEquals(listOf(emptyList<String>() to "A"), names(CoursePlanner.plan(trip(stop(1, "A")))))
        assertEquals(listOf(listOf("A", "B") to "C"), names(CoursePlanner.plan(trip(stop(1, "A"), stop(2, "B"), stop(3, "C")))))
    }

    @Test
    fun `four or more places are split three at a time`() {
        val t = trip(stop(1, "A"), stop(2, "B"), stop(3, "C"), stop(4, "D"), stop(5, "E"), stop(6, "F"), stop(7, "G"))
        assertEquals(
            listOf(listOf("A", "B") to "C", listOf("D", "E") to "F", emptyList<String>() to "G"),
            names(CoursePlanner.plan(t)),
        )
    }

    @Test
    fun `brief stops are left out unless chosen and stops without a location are skipped`() {
        val rest = stop(2, "횡성휴게소", StopKind.BRIEF, 8)
        val t = trip(stop(1, "A"), rest, stop(3, "숙소", StopKind.LODGING), stop(4, null, point = null))
        assertEquals(listOf(listOf("A") to "숙소"), names(CoursePlanner.plan(t)))
        assertEquals(listOf(listOf("A", "횡성휴게소") to "숙소"), names(CoursePlanner.plan(t, includeBriefIds = setOf(2))))
    }

    @Test
    fun `unnamed visit uses its numbered name`() {
        assertEquals(listOf(emptyList<String>() to "방문지 1"), names(CoursePlanner.plan(trip(stop(1, null)))))
    }

    @Test
    fun `a trip with no place to go gives no legs`() {
        assertTrue(CoursePlanner.plan(trip(stop(1, "휴게소", StopKind.BRIEF))).isEmpty())
    }

    @Test
    fun `rest area candidates are brief stops named as rest areas`() {
        val t = trip(stop(1, "A"), stop(2, "횡성휴게소", StopKind.BRIEF), stop(3, "주유소", StopKind.BRIEF), stop(4, "휴게소 식당", StopKind.VISIT))
        assertEquals(listOf(2), CoursePlanner.restAreaCandidates(t).map { it.id })
    }

    @Test
    fun `last time stats are total time, the longest visit and brief stops`() {
        assertEquals(
            listOf(Stat("10시간 35분", "전체 시간"), Stat("1시간 30분", "강릉중앙시장에서"), Stat("1곳", "잠깐 들른 곳")),
            CoursePlanner.lastTimeStats(Samples.gangneung),
        )
    }

    @Test
    fun `last time stats leave out missing visits and brief stops`() {
        assertEquals(listOf(Stat("1시간", "전체 시간")), CoursePlanner.lastTimeStats(Samples.driveOnly))
        val noBrief = Samples.gangneung.copy(stops = Samples.gangneung.stops.take(3))
        assertEquals(listOf(Stat("10시간 35분", "전체 시간"), Stat("1시간 30분", "강릉중앙시장에서")), CoursePlanner.lastTimeStats(noBrief))
        val briefOnly = Samples.gangneung.copy(stops = Samples.gangneung.stops.drop(3))
        assertEquals(listOf(Stat("10시간 35분", "전체 시간"), Stat("1곳", "잠깐 들른 곳")), CoursePlanner.lastTimeStats(briefOnly))
    }

    @Test
    fun `last time stats use nights for a trip with lodging`() {
        assertEquals(Stat("2박 3일", "전체 시간"), CoursePlanner.lastTimeStats(Samples.jeju).first())
    }

    // 구간 이어 붙이기

    private val legs = CoursePlanner.plan(trip(stop(1, "A"), stop(2, "B"), stop(3, "C"), stop(4, "D")))

    @Test
    fun `runner starts with the first leg`() {
        val r = CourseRunner(legs)
        assertEquals("C", r.current!!.destination.name)
        assertFalse(r.finished)
    }

    @Test
    fun `next leg is requested when departing after arriving at the leg destination`() {
        val r = CourseRunner(legs)
        assertNull(r.onDeparted())            // 아직 도착 전: 출발해도 다음 구간을 요청하지 않는다
        r.onDestinationArrived()
        val next = r.onDeparted()!!
        assertEquals("D", next.destination.name)
        assertEquals(2, r.legNumber)
        assertNull(r.onDeparted())            // 같은 구간을 두 번 요청하지 않는다
    }

    @Test
    fun `runner finishes after arriving at the last destination`() {
        val r = CourseRunner(legs)
        r.onDestinationArrived(); r.onDeparted()
        assertFalse(r.finished)
        r.onDestinationArrived()
        assertTrue(r.finished)
        assertNull(r.onDeparted())
    }
}
