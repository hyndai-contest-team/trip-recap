package kr.triprecap.app.domain

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RouteProjectionTest {
    private fun fit(vararg p: GeoPoint, w: Double = 400.0, h: Double = 200.0, pad: Double = 20.0) =
        RouteProjection.fit(p.toList(), w, h, pad)

    @Test
    fun `mercator maps the equator and prime meridian to the centre`() {
        val c = RouteProjection.mercator(GeoPoint(0.0, 0.0))
        assertEquals(0.5, c.x, 1e-12)
        assertEquals(0.5, c.y, 1e-12)
        // 위도 85.0511° 가 정사각형의 위쪽 끝이다
        assertEquals(0.0, RouteProjection.mercator(GeoPoint(85.0511287798, 180.0)).y, 1e-9)
        assertEquals(1.0, RouteProjection.mercator(GeoPoint(0.0, 180.0)).x, 1e-12)
    }

    @Test
    fun `an east-west line spans the padded width at mid height`() {
        val (a, b) = fit(GeoPoint(37.0, 127.0), GeoPoint(37.0, 128.0))
        assertEquals(20.0, a.x, 1e-6); assertEquals(100.0, a.y, 1e-6)
        assertEquals(380.0, b.x, 1e-6); assertEquals(100.0, b.y, 1e-6)
    }

    @Test
    fun `north is up and a north-south line spans the padded height at mid width`() {
        val (south, north) = fit(GeoPoint(37.0, 127.0), GeoPoint(38.0, 127.0))
        assertEquals(200.0, south.x, 1e-6); assertEquals(180.0, south.y, 1e-6)
        assertEquals(200.0, north.x, 1e-6); assertEquals(20.0, north.y, 1e-6)
    }

    @Test
    fun `aspect ratio is kept - at latitude 60 one degree north is twice as long as one degree east`() {
        val (o, n, e) = fit(GeoPoint(60.0, 10.0), GeoPoint(60.1, 10.0), GeoPoint(60.0, 10.1), w = 400.0, h = 400.0, pad = 0.0)
        val northLen = o.y - n.y
        val eastLen = e.x - o.x
        assertEquals(2.0, northLen / eastLen, 0.01)
        assertEquals(400.0, northLen, 1e-6)
    }

    @Test
    fun `a single point sits in the centre`() {
        val p = fit(GeoPoint(37.0, 127.0)).single()
        assertEquals(200.0, p.x, 1e-9); assertEquals(100.0, p.y, 1e-9)
    }

    @Test
    fun `points can be placed with bounds taken from another list`() {
        val bounds = listOf(GeoPoint(37.0, 127.0), GeoPoint(37.0, 128.0))
        val p = RouteProjection.fit(listOf(GeoPoint(37.0, 127.5)), 400.0, 200.0, 20.0, bounds).single()
        assertEquals(200.0, p.x, 1e-6)
    }

    @Test
    fun `matches the fixture shared with the phone page`() {
        val f = JSONObject(File("../web/test/fixtures/projection.json").readText())
        val pts = f.getJSONArray("points").let { a -> (0 until a.length()).map { GeoPoint(a.getJSONArray(it).getDouble(0), a.getJSONArray(it).getDouble(1)) } }
        val out = RouteProjection.fit(pts, f.getDouble("width"), f.getDouble("height"), f.getDouble("padding"))
        val expected = f.getJSONArray("expected")
        assertTrue(expected.length() == out.size && out.size >= 4)
        out.forEachIndexed { i, xy ->
            assertEquals(expected.getJSONArray(i).getDouble(0), xy.x, 0.005)
            assertEquals(expected.getJSONArray(i).getDouble(1), xy.y, 0.005)
        }
    }
}
