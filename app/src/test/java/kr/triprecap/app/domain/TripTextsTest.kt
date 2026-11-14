package kr.triprecap.app.domain

import kr.triprecap.app.domain.Fx.at
import kr.triprecap.app.scenario.GoldenScenarios
import kr.triprecap.app.scenario.replay
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZonedDateTime

class TripTextsTest {
    private val now = at(11, 12)

    // 여행 시간

    @Test
    fun `trip length counts nights by lodging stops`() {
        assertEquals("2박 3일", Texts.tripLength(GoldenScenarios.jejuThreeDays.replay().single()))
        assertEquals("2박 3일", Texts.tripLength(Samples.jeju))
        val oneNight = Samples.jeju.copy(stops = Samples.jeju.stops.filter { it.id != 6 })
        assertEquals("1박 2일", Texts.tripLength(oneNight))
    }

    @Test
    fun `trip length without lodging is hours and minutes`() {
        assertEquals("10시간 35분", Texts.tripLength(Samples.gangneung))
    }

    @Test
    fun `a day trip past midnight is not counted as a night`() {
        val late = Trip(drives = listOf(Fx.drive(at(7, 20, 0), at(8, 2, 0), Fx.home, Fx.beach)), closed = true)
        assertEquals("6시간", Texts.tripLength(late))
    }

    // 날짜

    @Test
    fun `date drops the year when it is the same as now`() {
        assertEquals("11월 7일 (토)", Texts.date(at(7, 9, 10), Fx.zone, now))
        assertEquals("10월 9일 (금)", Texts.date(Samples.jeju.startedAt, Fx.zone, now))
    }

    @Test
    fun `date of another year keeps the year`() {
        val lastYear = ZonedDateTime.of(2025, 11, 10, 10, 0, 0, 0, Fx.zone).toInstant().toEpochMilli()
        assertEquals("2025년 11월 10일 (월)", Texts.date(lastYear, Fx.zone, now))
    }

    // 목록 카드

    @Test
    fun `list meta line has the date, visits, people and distance`() {
        assertEquals("11월 7일 (토) · 방문 3곳 · 3명 · 413km", Texts.listMeta(Samples.gangneung, Fx.zone, now))
    }

    @Test
    fun `list meta line leaves out a single person`() {
        assertEquals("11월 15일 (일) · 방문 1곳 · 143km", Texts.listMeta(Samples.lake, Fx.zone, now))
    }

    @Test
    fun `list meta line puts nights right after the date as in the design`() {
        assertEquals("10월 9일 (금) · 2박 3일 · 방문 6곳 · 2명 · 156km", Texts.listMeta(Samples.jeju, Fx.zone, now))
    }

    // 상세 숫자 칸

    @Test
    fun `detail stats are distance, trip time, visits and people`() {
        assertEquals(
            listOf(Stat("413km", "달린 거리"), Stat("10시간 35분", "여행 시간"), Stat("3곳", "방문"), Stat("3명", "함께 탄 사람")),
            Texts.detailStats(Samples.gangneung),
        )
    }

    @Test
    fun `detail stats use nights and leave out a single person`() {
        assertEquals(
            listOf(Stat("156km", "달린 거리"), Stat("2박 3일", "여행 시간"), Stat("6곳", "방문"), Stat("2명", "함께 탄 사람")),
            Texts.detailStats(Samples.jeju),
        )
        assertEquals(
            listOf(Stat("143km", "달린 거리"), Stat("3시간 10분", "여행 시간"), Stat("1곳", "방문")),
            Texts.detailStats(Samples.lake),
        )
    }

    // 방문지 시각 줄

    @Test
    fun `stop clock line has the times and the region without the stay`() {
        val stops = Samples.gangneung.stops
        assertEquals("11:20 – 12:40 · 강릉시", Texts.stopClock(stops[0], Fx.zone))
        assertEquals("17:45 – 17:53", Texts.stopClock(stops[3], Fx.zone))
        assertEquals("1시간 20분", Texts.stay(stops[0]))
        assertEquals("8분", Texts.stay(stops[3]))
    }

    @Test
    fun `stop clock line marks a departure on the next day`() {
        val lodging = Stop(9, StopKind.LODGING, "호텔", Fx.beach, at(7, 20, 0), at(8, 9, 30))
        assertEquals("20:00 – 다음 날 9:30", Texts.stopClock(lodging, Fx.zone))
        assertEquals("13시간 30분", Texts.stay(lodging))
    }

    @Test
    fun `stop clock line ends with the outside temperature when it is known`() {
        val stops = Samples.gangneung.stops
        assertEquals("11:20 – 12:40 · 강릉시 · 12°C", Texts.stopClock(stops[0].copy(outsideTempC = 12), Fx.zone))
        assertEquals("17:45 – 17:53 · -3°C", Texts.stopClock(stops[3].copy(outsideTempC = -3), Fx.zone))
        assertEquals("17:45 – 17:53 · 0°C", Texts.stopClock(stops[3].copy(outsideTempC = 0), Fx.zone))
    }

    // 지우기 확인

    @Test
    fun `delete stop question matches the particle`() {
        assertEquals("강릉중앙시장을 지울까요?", Texts.deleteStopQuestion("강릉중앙시장"))
        assertEquals("안목해변 카페를 지울까요?", Texts.deleteStopQuestion("안목해변 카페"))
    }

    // 빈 제목

    @Test
    fun `a trip without visits is titled drive`() {
        assertEquals("드라이브", Samples.driveOnly.title)
    }
}
