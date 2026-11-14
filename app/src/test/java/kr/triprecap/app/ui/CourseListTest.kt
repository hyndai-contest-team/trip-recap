package kr.triprecap.app.ui

import kr.triprecap.app.domain.Fx
import kr.triprecap.app.domain.Fx.at
import kr.triprecap.app.domain.GeoPoint
import kr.triprecap.app.domain.Stop
import kr.triprecap.app.domain.StopKind
import kr.triprecap.app.domain.Trip
import org.junit.Assert.assertEquals
import org.junit.Test

class CourseListTest {
    private fun p(i: Int) = GeoPoint(37.0 + i * 0.01, 128.0)
    private fun stop(id: Int, name: String?, kind: StopKind = StopKind.VISIT, point: GeoPoint? = p(id)) =
        Stop(id, kind, name, point, at(7, 9 + id), at(7, 9 + id) + 30 * 60_000L)

    private fun trip(vararg stops: Stop) = Trip(
        drives = listOf(Fx.drive(at(7, 9, 10), at(7, 16, 30), Fx.home, Fx.home)),
        stops = stops.toList(),
    )

    private fun show(rows: List<CourseRow>) = rows.map { "${it.leg}:${it.dot ?: "점"}:${it.name}" }

    @Test
    fun `rows follow the guide order and split into legs of three`() {
        val t = trip(stop(1, "A"), stop(2, "횡성휴게소", StopKind.BRIEF), stop(3, "B"), stop(4, "숙소", StopKind.LODGING), stop(5, "C"))
        assertEquals(listOf("0:1:A", "0:2:B", "0:숙:숙소", "1:3:C"), show(CourseList.rows(t, emptySet())))
        assertEquals(listOf("0:1:A", "0:점:횡성휴게소", "0:2:B", "1:숙:숙소", "1:3:C"), show(CourseList.rows(t, setOf(2))))
    }

    @Test
    fun `visit numbers match the trip even when a visit has no location`() {
        val t = trip(stop(1, "A", point = null), stop(2, "B"))
        assertEquals(listOf("0:2:B"), show(CourseList.rows(t, emptySet())))
    }

    @Test
    fun `sent toast names the leg only when there are several`() {
        assertEquals("내비에 1구간 안내를 보냈어요 (전체 2구간)", CourseList.sentToast(legNumber = 1, legCount = 2))
        assertEquals("내비에 코스 안내를 보냈어요", CourseList.sentToast(legNumber = 1, legCount = 1))
    }
}
