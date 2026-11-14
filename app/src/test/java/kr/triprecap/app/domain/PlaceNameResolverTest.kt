package kr.triprecap.app.domain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class FakeLookup(var poi: String? = null, var region: RegionName? = null, var offline: Boolean = false) : PlaceLookup {
    var poiCalls = 0
    override suspend fun nearbyPoi(point: GeoPoint): String? { poiCalls++; if (offline) throw IOException("offline"); return poi }
    override suspend fun region(point: GeoPoint): RegionName? { if (offline) throw IOException("offline"); return region }
}

class PlaceNameResolverTest {
    private val gangneung = RegionName("강릉시", "강문동")
    private fun resolve(lookup: FakeLookup, name: String? = null, dest: List<NamedPoint> = emptyList(), point: GeoPoint? = Fx.beach) =
        runBlocking { PlaceNameResolver(lookup).resolve(point, name, dest) }

    @Test
    fun `navi destination within 300m gives the name without asking for a poi`() {
        val lookup = FakeLookup(poi = "경포 주차장", region = gangneung)
        val r = resolve(lookup, dest = listOf(NamedPoint("경포해변", Fx.north(Fx.beach, 250.0))))
        assertEquals(ResolvedPlace("경포해변", "강릉시", true), r)
        assertEquals(0, lookup.poiCalls)
    }

    @Test
    fun `navi destination farther than 300m is ignored and the nearby poi is used`() {
        val r = resolve(FakeLookup(poi = "경포 주차장", region = gangneung), dest = listOf(NamedPoint("경포해변", Fx.north(Fx.beach, 350.0))))
        assertEquals("경포 주차장", r.name)
    }

    @Test
    fun `without a poi the neighbourhood name is used`() {
        assertEquals(ResolvedPlace("강문동", "강릉시", true), resolve(FakeLookup(poi = null, region = gangneung)))
    }

    @Test
    fun `nothing found is complete with no name`() {
        assertEquals(ResolvedPlace(null, null, true), resolve(FakeLookup()))
    }

    @Test
    fun `offline leaves the name empty and incomplete`() {
        assertEquals(ResolvedPlace(null, null, false), resolve(FakeLookup(offline = true)))
    }

    @Test
    fun `offline with a navi destination keeps that name but stays incomplete for the region`() {
        val r = resolve(FakeLookup(offline = true), dest = listOf(NamedPoint("경포해변", Fx.beach)))
        assertEquals("경포해변", r.name)
        assertFalse(r.complete)
    }

    @Test
    fun `an existing name is kept and only the region is added`() {
        assertEquals(ResolvedPlace("우리 단골집", "강릉시", true), resolve(FakeLookup(poi = "다른 가게", region = gangneung), name = "우리 단골집"))
    }

    @Test
    fun `a stop without location is complete`() {
        assertTrue(resolve(FakeLookup(offline = true), point = null).complete)
    }

    @Test
    fun `resolveStops fills unresolved stops and a later retry fills what failed offline`() = runBlocking {
        val lookup = FakeLookup(offline = true)
        val resolver = PlaceNameResolver(lookup)
        val trip = Trip(
            drives = listOf(Fx.drive(0, 1, Fx.home, Fx.beach)),
            stops = listOf(
                Stop(1, StopKind.VISIT, null, Fx.beach, 10, 20),
                Stop(2, StopKind.VISIT, "직접 지은 이름", Fx.cafe, 30, 40, placeResolved = true),
            ),
        )
        val offline = resolver.resolveStops(trip, emptyList())
        assertNull(offline.stops[0].name)
        assertEquals("방문지 1", offline.nameOf(offline.stops[0]))
        assertFalse(offline.stops[0].placeResolved)

        lookup.offline = false; lookup.poi = "경포해변"; lookup.region = gangneung
        val online = resolver.resolveStops(offline, emptyList())
        assertEquals("경포해변", online.stops[0].name)
        assertEquals("강릉시", online.stops[0].region)
        assertTrue(online.stops[0].placeResolved)
        assertEquals("직접 지은 이름", online.stops[1].name)
        assertSame(online, resolver.resolveStops(online, emptyList()))
    }
}
