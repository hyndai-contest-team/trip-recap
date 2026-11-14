package kr.triprecap.app.domain

import kr.triprecap.app.domain.Fx.at
import org.junit.Assert.assertEquals
import org.junit.Test

class ArrivalCardTest {
    @Test
    fun `a trip with three visits`() {
        assertEquals(
            ArrivalCard(
                topLine = "여행을 저장했어요",
                headline = "3곳을 다녀왔어요",
                subline = "경포해변 → 강릉중앙시장",
                stats = listOf(Stat("413km", "달린 거리"), Stat("10시간 35분", "여행 시간"), Stat("3명", "함께 탄 사람")),
                longestStayLine = "강릉중앙시장에서 가장 오래 머물렀어요 · 1시간 30분",
                rainLine = "안목해변 카페 가는 길에 비가 왔어요",
            ),
            ArrivalCard.of(Samples.gangneung),
        )
    }

    @Test
    fun `a trip with one visit names it and has no second line`() {
        assertEquals(
            ArrivalCard(
                topLine = "여행을 저장했어요",
                headline = "의암호에 다녀왔어요",
                subline = null,
                stats = listOf(Stat("143km", "달린 거리"), Stat("3시간 10분", "여행 시간")),
                longestStayLine = null,
                rainLine = null,
            ),
            ArrivalCard.of(Samples.lake),
        )
    }

    @Test
    fun `a renamed one visit trip shows the title as the second line`() {
        assertEquals("가을 호수", ArrivalCard.of(Samples.lake.copy(customTitle = "가을 호수")).subline)
    }

    @Test
    fun `a drive without visits puts the distance in the headline`() {
        assertEquals(
            ArrivalCard(
                topLine = "여행을 저장했어요",
                headline = "41km를 달렸어요",
                subline = null,
                stats = listOf(Stat("1시간", "여행 시간")),
                longestStayLine = null,
                rainLine = null,
            ),
            ArrivalCard.of(Samples.driveOnly),
        )
    }

    @Test
    fun `a trip with nights uses nights as the trip time`() {
        val card = ArrivalCard.of(Samples.jeju)
        assertEquals("6곳을 다녀왔어요", card.headline)
        assertEquals("협재해변 → 함덕해변", card.subline)
        assertEquals(listOf(Stat("156km", "달린 거리"), Stat("2박 3일", "여행 시간"), Stat("2명", "함께 탄 사람")), card.stats)
    }

    private fun rainyAt(vararg ids: Int): Trip =
        Samples.gangneung.copy(stops = Samples.gangneung.stops.map { it.copy(rainy = it.id in ids) })

    @Test
    fun `rain line lists two places and summarizes three or more`() {
        assertEquals("경포해변, 안목해변 카페 가는 길에 비가 왔어요", ArrivalCard.of(rainyAt(1, 2)).rainLine)
        assertEquals("경포해변 등 3곳 가는 길에 비가 왔어요", ArrivalCard.of(rainyAt(1, 2, 3)).rainLine)
        assertEquals(null, ArrivalCard.of(rainyAt()).rainLine)
    }

    @Test
    fun `rain on the way to a brief stop is not mentioned`() {
        assertEquals(null, ArrivalCard.of(rainyAt(4)).rainLine)
    }

    @Test
    fun `two visits name the longest stay`() {
        val two = Samples.gangneung.copy(stops = Samples.gangneung.stops.take(2))
        assertEquals("2곳을 다녀왔어요", ArrivalCard.of(two).headline)
        assertEquals("경포해변에서 가장 오래 머물렀어요 · 1시간 20분", ArrivalCard.of(two).longestStayLine)
    }

    @Test
    fun `nobody counted means no people stat`() {
        val noSeats = Trip(drives = listOf(Fx.drive(at(8, 14, 0), at(8, 15, 0), Fx.home, Fx.home, seats = 0, distanceM = 8_460.0)), closed = true)
        assertEquals("8.5km를 달렸어요", ArrivalCard.of(noSeats).headline)
        assertEquals(listOf(Stat("1시간", "여행 시간")), ArrivalCard.of(noSeats).stats)
    }

    @Test
    fun `a saved trip asks nothing`() {
        assertEquals(null, ArrivalCard.of(Samples.lake).question)
        assertEquals(ArrivalCard.of(Samples.lake), ArrivalCard.of(Samples.lake, ArrivalKind.SAVED))
    }

    @Test
    fun `a trip shown at the next ignition says it was saved earlier`() {
        val card = ArrivalCard.of(Samples.gangneung, ArrivalKind.SAVED_EARLIER)
        assertEquals("지난 여행을 저장했어요", card.topLine)
        assertEquals("3곳을 다녀왔어요", card.headline)
        assertEquals(null, card.question)
    }

    @Test
    fun `an unsure move keeps the summary, drops the saved line and asks`() {
        val saved = ArrivalCard.of(Samples.driveOnly)
        val ask = ArrivalCard.of(Samples.driveOnly, ArrivalKind.ASK)
        assertEquals(null, ask.topLine)
        assertEquals("이번 이동도 여행으로 남길까요?", ask.question)
        assertEquals(saved.copy(topLine = null, question = "이번 이동도 여행으로 남길까요?"), ask)
    }
}
