package kr.triprecap.app.domain

import kr.triprecap.app.domain.Fx.at
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaceSetupTest {
    private val naviHome = GeoPoint(37.50, 127.00)
    private val userHome = GeoPoint(37.51, 127.01)
    private val estHome = GeoPoint(37.52, 127.02)
    private val estOffice = GeoPoint(37.56, 126.97)

    @Test
    fun `navi beats the user value and the user value beats the estimate`() {
        val setup = PlaceSetup.of(
            navi = KnownPlaces(home = naviHome),
            user = KnownPlaces(home = userHome),
            estimated = KnownPlaces(home = estHome, office = estOffice),
        )
        assertEquals(PlaceChoice(naviHome, PlaceSource.NAVI), setup.home)
        assertEquals(PlaceChoice(estOffice, PlaceSource.ESTIMATE), setup.office)
        assertEquals(KnownPlaces(naviHome, estOffice), setup.known)

        val noNavi = PlaceSetup.of(KnownPlaces(), KnownPlaces(home = userHome), KnownPlaces(home = estHome))
        assertEquals(PlaceChoice(userHome, PlaceSource.USER), noNavi[PlaceSlot.HOME])
        assertEquals(PlaceChoice(null, PlaceSource.NONE), noNavi[PlaceSlot.OFFICE])
    }

    /** 11월 5~10일 밤마다 집에 서 있고, 11월 2~6일·9일(평일 6일) 9~18시 회사에 서 있던 기록 */
    private val log: List<StopSummary> =
        (5..10).map { Fx.summary(at(it, 20), at(it + 1, 8), Fx.home, visit = false) } +
            listOf(2, 3, 4, 5, 6, 9).map { Fx.summary(at(it, 8, 50), at(it, 18, 10), Fx.office, visit = false) }

    private val now = at(12, 12)

    @Test
    fun `empty slots are estimated from the stop log`() {
        assertEquals(KnownPlaces(Fx.home, Fx.office), PlaceSetup.estimate(log, now, KnownPlaces(), KnownPlaces(), KnownPlaces()))
    }

    @Test
    fun `a slot with a navi or user value or a stored estimate is not estimated again`() {
        assertEquals(KnownPlaces(null, Fx.office), PlaceSetup.estimate(log, now, KnownPlaces(home = naviHome), KnownPlaces(), KnownPlaces()))
        assertEquals(KnownPlaces(Fx.home, null), PlaceSetup.estimate(log, now, KnownPlaces(), KnownPlaces(office = userHome), KnownPlaces()))
        // 저장된 추정은 그대로 둔다
        assertEquals(KnownPlaces(estHome, Fx.office), PlaceSetup.estimate(log, now, KnownPlaces(), KnownPlaces(), KnownPlaces(home = estHome)))
    }

    @Test
    fun `the office estimate leaves out the home in use`() {
        // 내비 집이 회사 자리에 있으면 그곳은 회사가 될 수 없다
        assertEquals(KnownPlaces(), PlaceSetup.estimate(log, now, KnownPlaces(home = Fx.office), KnownPlaces(), KnownPlaces()))
    }

    @Test
    fun `nothing is estimated from an empty log`() {
        assertEquals(KnownPlaces(), PlaceSetup.estimate(emptyList(), now, KnownPlaces(), KnownPlaces(), KnownPlaces()))
    }

    @Test
    fun `slot values can be read and replaced`() {
        val p = KnownPlaces(home = naviHome)
        assertEquals(naviHome, p[PlaceSlot.HOME])
        assertEquals(KnownPlaces(naviHome, estOffice), p.with(PlaceSlot.OFFICE, estOffice))
        assertEquals(KnownPlaces(), p.with(PlaceSlot.HOME, null))
    }

    @Test
    fun `settings rows describe the source and the place`() {
        assertEquals("집으로 쓰는 위치", PlaceSlot.HOME.title)
        assertEquals("회사로 쓰는 위치", PlaceSlot.OFFICE.title)
        assertEquals("위치가 정해지지 않았어요", PlaceSetup.describe(PlaceChoice(null, PlaceSource.NONE), regionName = null))
        assertEquals("내비 등록 · 송파구 잠실동", PlaceSetup.describe(PlaceChoice(naviHome, PlaceSource.NAVI), "송파구 잠실동"))
        assertEquals("직접 정함 · 37.515, 127.106", PlaceSetup.describe(PlaceChoice(GeoPoint(37.51461, 127.10591), PlaceSource.USER), null))
        assertEquals("추정 · 37.5, 127.0", PlaceSetup.describe(PlaceChoice(GeoPoint(37.5, 127.0), PlaceSource.ESTIMATE), null))
    }

    @Test
    fun `the region name comes from the nearest stored stop within 300m that has one`() {
        val near = Fx.north(Fx.home, 250.0)
        val trips = listOf(
            Trip(
                drives = listOf(Fx.drive(at(7, 9), at(7, 10), Fx.office, near)),
                stops = listOf(
                    Stop(1, StopKind.VISIT, "동네 마트", near, at(7, 10), at(7, 11), region = "송파구"),
                    Stop(2, StopKind.VISIT, "먼 곳", Fx.north(Fx.home, 400.0), at(7, 12), at(7, 13), region = "강동구"),
                    Stop(3, StopKind.BRIEF, null, Fx.north(Fx.home, 100.0), at(7, 14), at(7, 14, 5)),
                ),
            ),
        )
        assertEquals("송파구", PlaceSetup.regionNear(Fx.home, trips))
        assertEquals(null, PlaceSetup.regionNear(Fx.office, trips))
        assertEquals(null, PlaceSetup.regionNear(null, trips))
    }
}
