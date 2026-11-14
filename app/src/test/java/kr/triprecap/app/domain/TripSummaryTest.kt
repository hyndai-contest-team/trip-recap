package kr.triprecap.app.domain

import kr.triprecap.app.domain.Fx.at
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TripSummaryTest {
    private fun stop(id: Int, name: String?, from: Long, to: Long, kind: StopKind = StopKind.VISIT, rainy: Boolean = false) =
        Stop(id, kind, name, Fx.beach, from, to, rainy)

    private val trip = Trip(
        drives = listOf(
            Fx.drive(at(7, 9, 10), at(7, 11, 20), Fx.home, Fx.beach, seats = 3, distanceM = 230_400.0),
            Fx.drive(at(7, 17, 53), at(7, 19, 45), Fx.beach, Fx.home, seats = 3, distanceM = 181_900.0),
        ),
        stops = listOf(
            stop(1, "경포해변", at(7, 11, 20), at(7, 12, 40)),
            stop(2, "안목해변 카페", at(7, 13, 5), at(7, 14, 10), rainy = true),
            stop(3, "강릉중앙시장", at(7, 15, 0), at(7, 16, 30)),
            stop(4, "횡성휴게소", at(7, 17, 45), at(7, 17, 53), kind = StopKind.BRIEF),
        ),
    )

    @Test
    fun `summary counts visits, picks the longest stay and lists rainy stops`() {
        val s = TripSummarizer.summarize(trip)
        assertEquals(3, s.visitCount)
        assertEquals(412_300.0, s.distanceM, 0.1)
        assertEquals("강릉중앙시장", s.longestName)
        assertEquals(90L, s.longestMinutes)
        assertEquals(listOf("안목해변 카페"), s.rainyNames)
        assertEquals(3, s.peopleCount)
        assertEquals(at(7, 19, 45) - at(7, 9, 10), s.durationMs)
    }

    @Test
    fun `summary of a trip without visits has no longest stay`() {
        val s = TripSummarizer.summarize(trip.copy(stops = trip.stops.takeLast(1)))
        assertEquals(0, s.visitCount)
        assertNull(s.longestName)
    }

    @Test
    fun `arrival sentence uses native korean counters`() {
        fun sentence(n: Int) = TripSummarizer.arrivalSentence(n)
        assertEquals("세 곳 다녀온 여행을 저장했어요.", sentence(3))
        assertEquals("한 곳 다녀온 여행을 저장했어요.", sentence(1))
        assertEquals("열 곳 다녀온 여행을 저장했어요.", sentence(10))
        assertEquals("열두 곳 다녀온 여행을 저장했어요.", sentence(12))
        assertEquals("여행을 저장했어요.", sentence(0))
        assertEquals("120곳 다녀온 여행을 저장했어요.", sentence(120))
    }

    @Test
    fun `arrival sentence names the nights`() {
        assertEquals("여섯 곳 다녀온 2박 3일 여행을 저장했어요.", TripSummarizer.arrivalSentence(6, nights = 2))
        assertEquals("여섯 곳 다녀온 2박 3일 여행을 저장했어요.", TripSummarizer.arrivalSentence(Samples.jeju))
        assertEquals("세 곳 다녀온 여행을 저장했어요.", TripSummarizer.arrivalSentence(Samples.gangneung))
        assertEquals("여행을 저장했어요.", TripSummarizer.arrivalSentence(Samples.driveOnly))
    }

    @Test
    fun `native korean counters up to ninety nine`() {
        fun c(n: Int) = TripSummarizer.nativeCounter(n)
        assertEquals("한", c(1))
        assertEquals("네", c(4))
        assertEquals("열", c(10))
        assertEquals("열한", c(11))
        assertEquals("스무", c(20))
        assertEquals("스물한", c(21))
        assertEquals("서른세", c(33))
        assertEquals("마흔네", c(44))
        assertEquals("쉰", c(50))
        assertEquals("예순다섯", c(65))
        assertEquals("일흔", c(70))
        assertEquals("여든여덟", c(88))
        assertEquals("아흔아홉", c(99))
        assertEquals(null, c(100))
        assertEquals(null, c(0))
    }

    @Test
    fun `duration and distance texts`() {
        assertEquals("1시간 20분", Texts.duration(80))
        assertEquals("45분", Texts.duration(45))
        assertEquals("2시간", Texts.duration(120))
        assertEquals("0분", Texts.duration(0))
        assertEquals("412km", Texts.distance(412_300.0))
        assertEquals("8.5km", Texts.distance(8_460.0))
        assertEquals("0.3km", Texts.distance(310.0))
        assertEquals("3시간 10분", Texts.durationMs(3 * 3_600_000L + 10 * 60_000L + 59_000L))
    }
}
