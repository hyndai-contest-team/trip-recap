package kr.triprecap.app.scenario

import kr.triprecap.app.domain.StopKind
import kr.triprecap.app.domain.Trip
import kr.triprecap.app.domain.TripCategory
import kr.triprecap.app.domain.TripPipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 골든 시나리오 9개의 기대 결과 (docs/product-plan.md 4절). 기대값은 시나리오 설계에서 온 값이다 */
class GoldenScenarioTest {

    private fun Trip.kindsAndNames() = stops.map { it.kind to it.name }
    private fun Trip.stayMinutes() = stops.map { it.stayMinutes }

    /** (여행, 일상 이동, 묻는 중) 건수 */
    private fun List<Trip>.counts() = Triple(
        count { it.category == TripCategory.TRIP },
        count { it.category == TripCategory.DAILY },
        count { it.category == TripCategory.PENDING },
    )

    @Test
    fun `1 gangneung day trip - three visits, rain on the way to the cafe, three people`() {
        val pipeline = TripPipeline()
        val trip = GoldenScenarios.gangneungDayTrip.replay(pipeline).single()
        assertEquals(
            listOf(
                StopKind.VISIT to "경포해변",
                StopKind.VISIT to "안목해변 카페",
                StopKind.VISIT to "강릉중앙시장",
                StopKind.BRIEF to "횡성휴게소",
            ),
            trip.kindsAndNames(),
        )
        assertEquals(listOf(80L, 65L, 90L, 8L), trip.stayMinutes())
        assertEquals(listOf(false, true, false, false), trip.stops.map { it.rainy })
        // 도착 때 알던 가장 최근 외기온: 11:15 14.3도, 12:55 10.6도(비), 14:40 13.2도, 17:30 7.8도
        assertEquals(listOf(14, 11, 13, 8), trip.stops.map { it.outsideTempC })
        assertEquals(3, trip.peopleCount)
        assertEquals(5, trip.drives.size)
        assertEquals("경포해변 → 강릉중앙시장", trip.title)
        assertTrue(trip.closed)
        assertEquals(TripCategory.TRIP, trip.category)
        assertNull(pipeline.open)
        // 서울 송파 ↔ 강릉 왕복. 주요 지점을 직선으로 이은 근사 경로라 실제 도로(약 440km)와 거리가 다르다
        assertTrue("distance ${trip.distanceM}", trip.distanceM in 330_000.0..460_000.0)
    }

    @Test
    fun `2 jeju three days - nine drives make one trip and hotels are lodging`() {
        val trip = GoldenScenarios.jejuThreeDays.replay().single()
        assertEquals(9, trip.drives.size)
        assertEquals(
            listOf(
                StopKind.VISIT to "협재해변",
                StopKind.VISIT to "오설록 티뮤지엄",
                StopKind.LODGING to "서귀포 호텔",
                StopKind.VISIT to "천지연폭포",
                StopKind.VISIT to "쇠소깍",
                StopKind.LODGING to "성산 숙소",
                StopKind.VISIT to "성산일출봉",
                StopKind.VISIT to "함덕해변",
            ),
            trip.kindsAndNames(),
        )
        assertEquals(6, trip.visits.size)
        assertEquals(listOf(22, 23, 23, 20, 22, 22, 19, 21), trip.stops.map { it.outsideTempC })
        assertEquals(2, trip.peopleCount)
        assertEquals("협재해변 → 함덕해변", trip.title)
        assertEquals(TripCategory.TRIP, trip.category)
    }

    @Test
    fun `3 commute week - no trip, ten daily moves, no question`() {
        val pipeline = TripPipeline()
        val trips = GoldenScenarios.commuteWeek.replay(pipeline)
        assertEquals(Triple(0, 10, 0), trips.counts())
        assertNull(pipeline.open)
        // 수요일 퇴근길 주유 5분은 잠깐 정차라서 일상 판정에 영향이 없다
        assertEquals(listOf(StopKind.BRIEF), trips.flatMap { it.stops }.map { it.kind })
    }

    @Test
    fun `4 long drive - 15 minute rest area is a visit and 8 minute one is a brief stop`() {
        val pipeline = TripPipeline()
        val scenario = GoldenScenarios.longDriveHome
        assertTrue(scenario.replay(pipeline).isEmpty())
        // 부모님 댁에 도착해 서 있는 상태: 여행은 아직 열려 있다
        val trip = pipeline.live(scenario.epochAtZero + scenario.steps.last().atMs)!!
        assertFalse(trip.closed)
        assertEquals(
            listOf(StopKind.VISIT to "천안삼거리휴게소", StopKind.BRIEF to "추풍령휴게소"),
            trip.stops.take(2).map { it.kind to it.name },
        )
        assertEquals(listOf(15L, 8L), trip.stops.take(2).map { it.stayMinutes })
        assertEquals(4, trip.peopleCount)
        assertEquals(3, trip.drives.size)
        assertEquals(TripCategory.TRIP, trip.category)
    }

    @Test
    fun `5 re-parking 120m away - one visit`() {
        val trip = GoldenScenarios.reparkSamePlace.replay().single()
        val stop = trip.stops.single()
        assertEquals(StopKind.VISIT, stop.kind)
        assertEquals("양평 카페", stop.name)
        // 11:00 첫 주차 ~ 11:50 출발
        assertEquals(50L, stop.stayMinutes)
        // 합친 정차는 첫 주차(11:00) 때 알던 9.6도. 11:30 에 받은 11.8도는 쓰지 않는다
        assertEquals(10, stop.outsideTempC)
        assertEquals(3, trip.drives.size)
        assertEquals(TripCategory.TRIP, trip.category)
    }

    @Test
    fun `6 tunnel without location - route stays connected and the visit is unaffected`() {
        val trip = GoldenScenarios.tunnelGap.replay().single()
        assertEquals(listOf(StopKind.VISIT to "의암호"), trip.kindsAndNames())
        assertEquals(50L, trip.stops.single().stayMinutes)
        assertEquals(8, trip.stops.single().outsideTempC)
        val out = trip.drives[0]
        val back = trip.drives[1]
        val gaps = out.track.zipWithNext { a, b -> a.point.distanceM(b.point) }
        // 가는 길에만 3km 빈 구간이 하나 있고, 그 양 끝이 직선으로 이어진다
        assertEquals(1, gaps.count { it > 2_900.0 })
        assertTrue(gaps.max() < 3_200.0)
        // 빈 구간이 있어도 거리는 돌아오는 길(표본 있음)과 1% 안에서 같다
        assertEquals(back.distanceM, out.distanceM, back.distanceM * 0.01)
        assertEquals(TripCategory.TRIP, trip.category)
    }

    @Test
    fun `7 coastal drive from home back home - no stop, under 30km, asked once`() {
        val trips = GoldenScenarios.coastalDrive.replay()
        assertEquals(Triple(0, 0, 1), trips.counts())
        val trip = trips.single()
        assertTrue(trip.stops.isEmpty())
        assertEquals(1, trip.drives.size)
        assertFalse(trip.endedAway)
        val far = trip.drives.flatMap { it.track }.maxOf { it.point.distanceM(GoldenScenarios.jejuHome) }
        assertTrue("farthest $far", far in 15_000.0..30_000.0)
    }

    @Test
    fun `8 regular mart on the way home - daily, no question`() {
        val scenario = GoldenScenarios.regularMart
        // 최근 30일 안에 마트 방문 3번
        assertEquals(3, scenario.priorLog.count { it.visit && it.point.distanceM(GoldenScenarios.jamsilMart) <= 150.0 })
        val trips = scenario.replay()
        assertEquals(Triple(0, 1, 0), trips.counts())
        val stop = trips.single().stops.single()
        assertEquals(StopKind.VISIT to "동네 마트", stop.kind to stop.name)
        assertEquals(20L, stop.stayMinutes)
    }

    @Test
    fun `8 without the stop log the same mart trip is asked about`() {
        val noHistory = GoldenScenarios.regularMart.copy(priorLog = emptyList())
        assertEquals(Triple(0, 0, 1), noHistory.replay().counts())
    }

    @Test
    fun `9 overnight at parents - closed 6 hours after arrival, reopened as lodging, one trip`() {
        val scenario = GoldenScenarios.parentsOvernight
        val updates = mutableListOf<TripPipeline.Update>()
        val pipeline = TripPipeline()
        // 10분마다 tick: 실제 차에서 앱이 켜져 있는 경우
        val trips = scenario.replay(pipeline, tickMs = 10 * 60_000L) { updates += it }
        assertEquals(Triple(1, 0, 0), trips.counts())
        val trip = trips.single()
        assertEquals(listOf(StopKind.LODGING to "부모님 댁"), trip.kindsAndNames())
        assertEquals(1, trip.nights)
        // 다시 열려 늘어난 숙박도 도착(12:10) 때 값 8.6도를 유지한다. 다음 날 아침 -1.4도는 쓰지 않는다
        assertEquals(9, trip.stops.single().outsideTempC)
        assertEquals(2, trip.drives.size)
        assertFalse(trip.endedAway)
        assertNull(pipeline.open)
        // 세종 부모님 댁까지 직선 근사 경로 편도 약 120km, 왕복 약 240km
        assertTrue("distance ${trip.distanceM}", trip.distanceM in 220_000.0..260_000.0)

        // 도착(12:10) 6시간 뒤 닫히고, 오전 3시에 다시 열렸다
        val arrived = scenario.epochAtZero + 12 * 3_600_000L + 10 * 60_000L
        val first = updates.flatMap { it.closed }.first()
        assertTrue(first.endedAway)
        assertEquals(arrived + 6 * 3_600_000L, first.endedAt)
        assertEquals(trip.startedAt, first.startedAt)
        val reopened = updates.mapNotNull { it.reopened }.single()
        assertEquals(trip.startedAt, reopened.startedAt)
        assertEquals(2, updates.sumOf { it.closed.size })
    }

    @Test
    fun `every scenario ends the same with or without ticks while parked`() {
        for (scenario in GoldenScenarios.all) {
            assertEquals(scenario.name, scenario.replay(), scenario.replay(tickMs = 10 * 60_000L))
        }
    }
}
