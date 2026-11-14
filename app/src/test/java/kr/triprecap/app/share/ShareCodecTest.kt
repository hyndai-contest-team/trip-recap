package kr.triprecap.app.share

import kr.triprecap.app.domain.Drive
import kr.triprecap.app.domain.Fx
import kr.triprecap.app.domain.Fx.at
import kr.triprecap.app.domain.GeoPoint
import kr.triprecap.app.domain.KnownPlaces
import kr.triprecap.app.domain.Stop
import kr.triprecap.app.domain.StopKind
import kr.triprecap.app.domain.TrackPoint
import kr.triprecap.app.domain.Trip
import kr.triprecap.app.domain.TripCategory
import kr.triprecap.app.scenario.GoldenScenarios
import kr.triprecap.app.scenario.knownPlaces
import kr.triprecap.app.scenario.replay
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ShareCodecTest {
    private val a = GeoPoint(37.80571, 128.90874)
    private val b = GeoPoint(37.7729, 128.9499)
    private fun track(vararg p: GeoPoint) = p.mapIndexed { i, pt -> TrackPoint(at(7, 9) + i * 60_000L, pt) }

    /** web/test/fixtures/share-v1.json 과 같은 여행 */
    private val trip = Trip(
        drives = listOf(
            Drive(at(7, 9, 10), at(7, 11, 20), track(Fx.home, Fx.north(Fx.home, 400.0), Fx.north(Fx.home, 600.0), a), distanceM = 230_440.0, rainy = false, seats = Fx.drive(0, 1, null, null, seats = 3).seats),
            Drive(at(7, 12, 40), at(7, 13, 5), track(a, b), distanceM = 5_120.0, rainy = true),
        ),
        stops = listOf(
            Stop(1, StopKind.VISIT, "경포해변", a, at(7, 11, 20), at(7, 12, 40)),
            Stop(2, StopKind.BRIEF, "주유소", GeoPoint(37.79, 128.93), at(7, 12, 45), at(7, 12, 50)),
            Stop(3, StopKind.LODGING, null, b, at(7, 13, 5), at(8, 9, 5), rainy = true),
        ),
        closed = true,
    )

    @Test
    fun `payload decodes back to the same shared trip`() {
        val p = ShareCodec.build(trip, Fx.places)
        assertEquals(p.trip, ShareCodec.decodeHash(p.hash))
    }

    @Test
    fun `shared trip carries rounded coordinates, minutes and no brief stop`() {
        val s = ShareCodec.build(trip, Fx.places).trip
        assertEquals("경포해변", s.title)
        assertEquals(at(7, 9, 10) / 60_000L, s.startMinute)
        assertEquals(24 * 60 - 5, s.durationMinutes)          // 7일 9:10 → 8일 9:05
        assertEquals(235_600, s.distanceM)
        assertEquals(3, s.people)
        assertEquals(
            listOf(
                SharedStop(StopKind.VISIT, false, GeoPoint(37.8057, 128.9087), at(7, 11, 20) / 60_000L, 80, "경포해변"),
                SharedStop(StopKind.LODGING, true, GeoPoint(37.7729, 128.9499), at(7, 13, 5) / 60_000L, 20 * 60, "숙소"),
            ),
            s.stops,
        )
    }

    @Test
    fun `route points within 500m of home or office are left out`() {
        val s = ShareCodec.build(trip, Fx.places).trip
        // 집, 집에서 400m 는 빠지고 600m 부터 남는다
        assertEquals(listOf(round(Fx.north(Fx.home, 600.0)), GeoPoint(37.8057, 128.9087), GeoPoint(37.7729, 128.9499)), s.route)
    }

    private fun round(p: GeoPoint) = GeoPoint(Math.round(p.lat * 1e4) / 1e4, Math.round(p.lon * 1e4) / 1e4)

    @Test
    fun `stops near home or office and stops excluded by the user are left out`() {
        val t = trip.copy(
            stops = listOf(
                Stop(1, StopKind.VISIT, "집 앞 카페", Fx.north(Fx.home, 450.0), at(7, 10), at(7, 11)),
                Stop(2, StopKind.VISIT, "비밀 장소", a, at(7, 11, 20), at(7, 12, 40), excludedFromShare = true),
                Stop(3, StopKind.VISIT, "카페", b, at(7, 13, 5), at(7, 14)),
            ),
        )
        assertEquals(listOf("카페"), ShareCodec.build(t, Fx.places).trip.stops.map { it.name })
    }

    @Test
    fun `preview rows split label and value`() {
        assertEquals(
            listOf(ShareRow("보내는 것", "방문지 1곳, 경로"), ShareRow("보내지 않는 것", "집·회사 근처, 사진")),
            ShareCodec.build(trip, Fx.places).preview.rows,
        )
        assertEquals(
            listOf(ShareRow("보내는 것", "방문지 1곳, 경로"), ShareRow("보내지 않는 것", "사진")),
            ShareCodec.build(trip, KnownPlaces()).preview.rows,
        )
    }

    @Test
    fun `preview rows count the places the user left out`() {
        val t = trip.copy(stops = trip.stops.map { if (it.id == 1 || it.id == 3) it.copy(excludedFromShare = true) else it })
        assertEquals(
            listOf(ShareRow("보내는 것", "경로"), ShareRow("보내지 않는 것", "집·회사 근처, 직접 뺀 2곳, 사진")),
            ShareCodec.build(t, Fx.places).preview.rows,
        )
        assertEquals(ShareRow("보내지 않는 것", "직접 뺀 2곳, 사진"), ShareCodec.build(t, KnownPlaces()).preview.rows[1])
    }

    @Test
    fun `preview says nothing is sent when there is neither a visit nor a route`() {
        assertEquals(ShareRow("보내는 것", "없음"), SharePreview(visitCount = 0, hasRoute = false, hidesHomeOrOffice = true).rows[0])
        assertEquals(ShareRow("보내는 것", "방문지 2곳"), SharePreview(visitCount = 2, hasRoute = false, hidesHomeOrOffice = true).rows[0])
    }

    @Test
    fun `no url without a share address`() {
        val p = ShareCodec.build(trip, Fx.places)
        assertEquals(null, p.urlOrNull(""))
        assertEquals(null, p.urlOrNull("  "))
        assertEquals(null, p.urlOrNull(null))
        assertEquals(p.url("https://example.invalid/t/"), p.urlOrNull("https://example.invalid/t/"))
    }

    @Test
    fun `url puts the data after the hash sign only`() {
        val p = ShareCodec.build(trip, Fx.places)
        val url = p.url("https://example.invalid/t/")
        assertTrue(url.startsWith("https://example.invalid/t/#"))
        assertTrue(Regex("^[A-Za-z0-9_-]+$").matches(url.substringAfter('#')))
    }

    @Test
    fun `every golden scenario trip fits in 1KB with its stops intact`() {
        val trips = GoldenScenarios.all.flatMap { s -> s.replay().filter { it.category != TripCategory.DAILY }.map { s to it } }
        assertTrue(trips.size >= 5)
        for ((scenario, t) in trips) {
            val p = ShareCodec.build(t, scenario.knownPlaces())
            assertTrue("${scenario.name}: ${p.bytes.size}B", p.bytes.size <= 1024)
            assertEquals(t.stops.count { it.kind != StopKind.BRIEF }, p.trip.stops.size)
            assertTrue("${scenario.name}: route ${p.trip.route.size}", p.trip.route.size >= 10)
            assertEquals(p.trip, ShareCodec.decodeHash(p.hash))
        }
    }

    @Test
    fun `a long route is simplified but stays within 300m of the original`() {
        val t = GoldenScenarios.jejuThreeDays.replay().single()
        val original = t.drives.flatMap { d -> d.track.map { it.point } }
        val shared = ShareCodec.build(t, KnownPlaces()).trip.route
        assertTrue(shared.size < original.size)
        for (p in original) assertTrue(shared.zipWithNext().minOf { (x, y) -> distToSeg(p, x, y) } < 300.0)
    }

    private fun distToSeg(p: GeoPoint, x: GeoPoint, y: GeoPoint): Double =
        (0..20).minOf { i -> val f = i / 20.0; p.distanceM(GeoPoint(x.lat + (y.lat - x.lat) * f, x.lon + (y.lon - x.lon) * f)) }

    @Test
    fun `when even the stops alone exceed the limit names are shortened`() {
        val many = (1..40).map { Stop(it, StopKind.VISIT, "아주 긴 이름을 가진 방문지 번호 $it 입니다", GeoPoint(37.0 + it * 0.01, 128.0), at(7, 10) + it * 600_000L, at(7, 10) + it * 600_000L + 300_000L) }
        val p = ShareCodec.build(trip.copy(stops = many), Fx.places)
        assertTrue(p.bytes.size <= 1024)
        assertEquals(40, p.trip.stops.size)
        assertFalse(p.preview.hasRoute)
    }

    @Test
    fun `hash matches the fixture shared with the phone page`() {
        val f = JSONObject(File("../web/test/fixtures/share-v1.json").readText())
        val p = ShareCodec.build(trip, Fx.places)
        assertEquals(f.getString("hash"), p.hash)
        val e = f.getJSONObject("expected")
        assertEquals(e.getString("title"), p.trip.title)
        assertEquals(e.getLong("startMinute"), p.trip.startMinute)
        assertEquals(e.getJSONArray("stops").length(), p.trip.stops.size)
        assertEquals(e.getJSONArray("route").length(), p.trip.route.size)
    }
}
