package kr.triprecap.app.domain

import kr.triprecap.app.domain.Fx.at
import kr.triprecap.app.domain.Fx.beach
import kr.triprecap.app.domain.Fx.home
import kr.triprecap.app.domain.Fx.north
import kr.triprecap.app.domain.Fx.office
import kr.triprecap.app.domain.Fx.summary
import org.junit.Assert.assertEquals
import org.junit.Test

class TripJudgeTest {
    /** 집에서 북쪽 2km 마트. 단골 테스트용 */
    private val mart = north(home, 2_000.0)
    private val regulars = Regulars.from(listOf(3, 6, 10).map { summary(at(it, 19), at(it, 19, 20), mart) }, at(12, 20))

    /** [points] 를 차례로 잇는 드라이브들. 드라이브 i 는 12일 (8+i)시에 출발해 30분 달린다 */
    private fun trip(vararg points: GeoPoint, stops: List<Stop> = emptyList()) = Trip(
        drives = points.toList().zipWithNext().mapIndexed { i, (a, b) -> Fx.drive(at(12, 8 + i), at(12, 8 + i, 30), a, b) },
        stops = stops,
        closed = true,
    )

    private fun stop(id: Int, kind: StopKind, p: GeoPoint) = Stop(id, kind, null, p, at(12, 8 + id - 1, 30), at(12, 8 + id))

    private fun judge(t: Trip, places: KnownPlaces = Fx.places, r: Regulars = regulars) = TripJudge.judge(t, places, r)

    // 확실한 일상

    @Test
    fun `home to office without stops is daily in either direction`() {
        assertEquals(Verdict.DAILY, judge(trip(home, office)))
        assertEquals(Verdict.DAILY, judge(trip(office, home)))
    }

    @Test
    fun `brief stops are ignored`() {
        assertEquals(Verdict.DAILY, judge(trip(home, beach, office, stops = listOf(stop(1, StopKind.BRIEF, beach)))))
    }

    @Test
    fun `home and office ends are matched within 300m`() {
        assertEquals(Verdict.DAILY, judge(trip(north(home, 290.0), north(office, 290.0))))
        assertEquals(Verdict.ASK, judge(trip(north(home, 310.0), office)))
        assertEquals(Verdict.ASK, judge(trip(home, north(office, 310.0))))
    }

    @Test
    fun `home to home or office to office without stops is not daily`() {
        assertEquals(Verdict.ASK, judge(trip(home, mart, home)))
        assertEquals(Verdict.ASK, judge(trip(office, mart, office)))
    }

    @Test
    fun `commute is not daily when home or office is unknown`() {
        assertEquals(Verdict.ASK, judge(trip(home, office), KnownPlaces(home = home)))
        assertEquals(Verdict.ASK, judge(trip(home, office), KnownPlaces(office = office)))
    }

    @Test
    fun `one visit at a regular place is daily`() {
        assertEquals(Verdict.DAILY, judge(trip(home, mart, home, stops = listOf(stop(1, StopKind.VISIT, mart)))))
        assertEquals(Verdict.DAILY, judge(trip(office, mart, home, stops = listOf(stop(1, StopKind.VISIT, mart)))))
    }

    @Test
    fun `one regular visit is daily even when the route goes far, because daily is checked first`() {
        val far = north(home, -40_000.0)
        val r = Regulars.from(listOf(3, 6, 10).map { summary(at(it, 19), at(it, 19, 20), far) }, at(12, 20))
        assertEquals(Verdict.DAILY, judge(trip(home, far, home, stops = listOf(stop(1, StopKind.VISIT, far))), r = r))
    }

    @Test
    fun `commute is daily even when the route goes far, because daily is checked first`() {
        assertEquals(Verdict.DAILY, judge(trip(home, north(home, -40_000.0), office)))
    }

    // 애매한 이동

    @Test
    fun `one visit at a place that is not regular is ask`() {
        val r = Regulars.from(listOf(6, 10).map { summary(at(it, 19), at(it, 19, 20), mart) }, at(12, 20))
        assertEquals(Verdict.ASK, judge(trip(home, mart, home, stops = listOf(stop(1, StopKind.VISIT, mart))), r = r))
        assertEquals(Verdict.ASK, judge(trip(home, mart, office, stops = listOf(stop(1, StopKind.VISIT, mart))), r = Regulars.NONE))
    }

    @Test
    fun `one visit without a point is ask`() {
        val t = trip(home, mart, home, stops = listOf(Stop(1, StopKind.VISIT, null, null, at(12, 8, 30), at(12, 9))))
        assertEquals(Verdict.ASK, judge(t))
    }

    // 확실한 여행

    @Test
    fun `two visits are a trip even at regular places`() {
        val t = trip(home, mart, mart, home, stops = listOf(stop(1, StopKind.VISIT, mart), stop(2, StopKind.VISIT, mart)))
        assertEquals(Verdict.TRIP, judge(t))
        val commute = trip(home, beach, office, stops = listOf(stop(1, StopKind.VISIT, mart), stop(2, StopKind.VISIT, beach)))
        assertEquals(Verdict.TRIP, judge(commute))
    }

    @Test
    fun `one lodging is a trip`() {
        assertEquals(Verdict.TRIP, judge(trip(home, mart, home, stops = listOf(stop(1, StopKind.LODGING, mart)))))
        val regularPlusLodging = listOf(stop(1, StopKind.VISIT, mart), stop(2, StopKind.LODGING, mart))
        assertEquals(Verdict.TRIP, judge(trip(home, mart, mart, home, stops = regularPlusLodging)))
    }

    @Test
    fun `farthest point 30km from both home and office is a trip, 29_9km is ask`() {
        // 집 남쪽. 회사는 집 북서쪽 약 12km 라 남쪽 점은 회사에서 더 멀다
        assertEquals(Verdict.ASK, judge(trip(home, north(home, -29_900.0), home)))
        assertEquals(Verdict.TRIP, judge(trip(home, north(home, -30_010.0), home)))
    }

    @Test
    fun `point far from home but within 30km of office is not far`() {
        val p = north(home, 30_010.0)
        assertEquals(true, p.distanceM(office) < TripJudge.FAR_M)
        assertEquals(Verdict.ASK, judge(trip(home, p, home)))
    }

    @Test
    fun `only the known one of home and office is a reference`() {
        val p = north(home, 30_010.0)
        assertEquals(Verdict.TRIP, judge(trip(home, p, home), KnownPlaces(home = home)))
    }

    @Test
    fun `when home and office are both unknown the start point is the reference`() {
        val none = KnownPlaces()
        assertEquals(Verdict.ASK, judge(trip(beach, north(beach, 29_900.0), beach), none))
        assertEquals(Verdict.TRIP, judge(trip(beach, north(beach, 30_010.0), beach), none))
        assertEquals(Verdict.ASK, judge(trip(home, office), none))
    }

    @Test
    fun `sample trips`() {
        assertEquals(Verdict.TRIP, judge(Samples.gangneung))
        assertEquals(Verdict.TRIP, judge(Samples.jeju))
        assertEquals(Verdict.TRIP, judge(Samples.lake))
    }

    @Test
    fun `far threshold is 30km`() {
        assertEquals(30_000.0, TripJudge.FAR_M, 0.0)
    }

    @Test
    fun `trip category defaults to TRIP`() {
        assertEquals(TripCategory.TRIP, trip(home, office).category)
        assertEquals(listOf("TRIP", "DAILY", "PENDING"), TripCategory.entries.map { it.name })
    }
}
