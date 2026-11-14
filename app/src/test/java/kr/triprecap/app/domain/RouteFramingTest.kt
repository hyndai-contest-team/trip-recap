package kr.triprecap.app.domain

import kr.triprecap.app.domain.RouteProjection.Xy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

class RouteFramingTest {
    private fun assertPoint(expected: GeoPoint, actual: GeoPoint) {
        assertEquals(expected.lat, actual.lat, 1e-9)
        assertEquals(expected.lon, actual.lon, 1e-9)
    }

    @Test
    fun `places gathered in one area are zoomed in`() {
        val frame = RouteFraming.frame(Samples.gangneung)
        assertTrue(frame.zoomed)
        assertEquals(2, frame.bounds.size)
        assertPoint(GeoPoint(37.7553, 128.8963), frame.bounds[0])
        assertPoint(GeoPoint(37.8057, 128.9499), frame.bounds[1])
    }

    @Test
    fun `places spread over the route show the whole route`() {
        val route = listOf(GeoPoint(37.0, 127.0), GeoPoint(37.2, 127.6), GeoPoint(37.5, 127.5))
        val frame = RouteFraming.frame(route, listOf(GeoPoint(37.0, 127.0), GeoPoint(37.5, 127.5)))
        assertFalse(frame.zoomed)
        assertPoint(GeoPoint(37.0, 127.0), frame.bounds[0])
        assertPoint(GeoPoint(37.5, 127.6), frame.bounds[1])
    }

    @Test
    fun `a single place is not zoomed`() {
        val frame = RouteFraming.frame(listOf(GeoPoint(36.0, 127.0), GeoPoint(37.0, 128.0)), listOf(GeoPoint(37.0, 128.0)))
        assertFalse(frame.zoomed)
        assertPoint(GeoPoint(36.0, 127.0), frame.bounds[0])
    }

    @Test
    fun `a zoomed area shorter than 1_5km is widened around its center`() {
        val a = GeoPoint(37.0, 128.0)
        val b = GeoPoint(37.001, 128.0)
        val frame = RouteFraming.frame(listOf(GeoPoint(36.0, 127.0), b), listOf(a, b))
        assertTrue(frame.zoomed)
        val (sw, ne) = frame.bounds
        assertEquals(37.0005, (sw.lat + ne.lat) / 2, 1e-9)
        assertEquals(128.0, (sw.lon + ne.lon) / 2, 1e-9)
        assertEquals(1500.0, (ne.lat - sw.lat) * 111_195.0, 0.5)
        assertEquals(1500.0, (ne.lon - sw.lon) * 111_195.0 * cos(Math.toRadians(37.0005)), 0.5)
    }

    @Test
    fun `no points give no bounds`() {
        val frame = RouteFraming.frame(emptyList(), emptyList())
        assertTrue(frame.bounds.isEmpty())
        assertFalse(frame.zoomed)
    }

    // 점 겹침 풀기

    private fun assertXy(x: Double, y: Double, actual: Xy) {
        assertEquals(x, actual.x, 1e-6)
        assertEquals(y, actual.y, 1e-6)
    }

    @Test
    fun `points far enough apart stay where they are`() {
        val points = listOf(Xy(0.0, 0.0), Xy(100.0, 0.0), Xy(0.0, 95.0))
        assertEquals(points, RouteFraming.separate(points, 90.0))
    }

    @Test
    fun `a later point too close is pushed away from the earlier one`() {
        val out = RouteFraming.separate(listOf(Xy(100.0, 100.0), Xy(130.0, 100.0)), 90.0)
        assertXy(100.0, 100.0, out[0])
        assertXy(190.0, 100.0, out[1])
    }

    @Test
    fun `a point on top of another is pushed to the right`() {
        val out = RouteFraming.separate(listOf(Xy(100.0, 100.0), Xy(100.0, 100.0)), 90.0)
        assertXy(190.0, 100.0, out[1])
    }

    @Test
    fun `a point between two others turns until it is clear of both`() {
        val out = RouteFraming.separate(listOf(Xy(100.0, 100.0), Xy(190.0, 100.0), Xy(145.0, 100.0)), 90.0)
        assertXy(190.0, 100.0, out[1])
        assertXy(145.0, 177.94228634, out[2])
    }

    @Test
    fun `the result is the same every time`() {
        val points = List(12) { Xy(100.0 + (it % 3) * 10.0, 100.0 + (it / 3) * 10.0) }
        val first = RouteFraming.separate(points, 90.0)
        assertEquals(first, RouteFraming.separate(points, 90.0))
        for (i in first.indices) for (j in 0 until i) {
            val d = kotlin.math.hypot(first[i].x - first[j].x, first[i].y - first[j].y)
            assertTrue("$i-$j $d", d >= 90.0 - 1e-6)
        }
    }

    // 미니맵 모서리. 그림 800×600, 미니맵 220×160, 여백 24 일 때 모서리 칸:
    // 왼쪽 위 x 24~244 y 24~184 / 오른쪽 위 x 556~776 y 24~184 / 왼쪽 아래 x 24~244 y 416~576 / 오른쪽 아래 x 556~776 y 416~576
    private fun corner(marks: List<RouteFraming.Mark>, lines: List<List<Xy>> = emptyList()) =
        RouteFraming.miniMapCorner(800.0, 600.0, 220.0, 160.0, 24.0, marks, lines)

    private fun mark(x: Double, y: Double) = RouteFraming.Mark(Xy(x, y), 27.0)

    @Test
    fun `an empty picture keeps the mini map at the top left`() {
        assertEquals(MiniMapCorner.TOP_LEFT, corner(emptyList()))
    }

    @Test
    fun `a dot in the top left moves the mini map to the top right`() {
        assertEquals(MiniMapCorner.TOP_RIGHT, corner(listOf(mark(100.0, 100.0))))
    }

    @Test
    fun `a dot whose circle reaches into the corner counts as overlapping`() {
        // 중심은 칸 밖(x 260)이지만 반지름 27 이 칸 오른쪽 변(x 244)을 넘는다
        assertEquals(MiniMapCorner.TOP_RIGHT, corner(listOf(mark(260.0, 100.0))))
        // 칸 오른쪽 변에서 28 떨어지면 겹치지 않는다
        assertEquals(MiniMapCorner.TOP_LEFT, corner(listOf(mark(272.0, 100.0))))
    }

    @Test
    fun `dots in every corner pick the corner with the fewest dots`() {
        val marks = listOf(
            mark(100.0, 100.0), mark(200.0, 150.0),
            mark(600.0, 100.0), mark(700.0, 150.0),
            mark(100.0, 500.0),
            mark(600.0, 500.0), mark(700.0, 450.0),
        )
        assertEquals(MiniMapCorner.BOTTOM_LEFT, corner(marks))
    }

    @Test
    fun `with no dots in the way the corner with the least line inside wins`() {
        val lines = listOf(
            // 위쪽 두 칸을 가로지른다: 왼쪽 위 220, 오른쪽 위 220
            listOf(Xy(0.0, 100.0), Xy(800.0, 100.0)),
            // 왼쪽 아래 칸 안 길이 176 (x 24~200)
            listOf(Xy(0.0, 500.0), Xy(200.0, 500.0)),
            // 오른쪽 아래 칸 안 길이 126 (x 650~776). 칸 밖으로 나가는 대각선 부분은 세지 않는다
            listOf(Xy(650.0, 500.0), Xy(800.0, 500.0), Xy(900.0, 700.0)),
        )
        assertEquals(MiniMapCorner.BOTTOM_RIGHT, corner(emptyList(), lines))
    }

    @Test
    fun `a corner free of dots beats a corner with less line but a dot`() {
        val marks = listOf(mark(100.0, 100.0), mark(600.0, 100.0), mark(100.0, 500.0))
        val lines = listOf(listOf(Xy(556.0, 400.0), Xy(776.0, 600.0)))
        assertEquals(MiniMapCorner.BOTTOM_RIGHT, corner(marks, lines))
    }

    @Test
    fun `the mini map origin is the corner cell's top left`() {
        assertXy(24.0, 24.0, MiniMapCorner.TOP_LEFT.origin(800.0, 600.0, 220.0, 160.0, 24.0))
        assertXy(556.0, 24.0, MiniMapCorner.TOP_RIGHT.origin(800.0, 600.0, 220.0, 160.0, 24.0))
        assertXy(24.0, 416.0, MiniMapCorner.BOTTOM_LEFT.origin(800.0, 600.0, 220.0, 160.0, 24.0))
        assertXy(556.0, 416.0, MiniMapCorner.BOTTOM_RIGHT.origin(800.0, 600.0, 220.0, 160.0, 24.0))
    }
}
