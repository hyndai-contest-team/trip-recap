package kr.triprecap.app.domain

import kr.triprecap.app.LiveStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class DrivingViewTest {
    private val start = 1_000_000_000L
    private fun min(m: Long) = start + m * 60_000L
    private fun live(distanceM: Double = 0.0, visits: Int = 0, started: Long? = start, recording: Boolean = true) =
        LiveStatus(moving = true, recording = recording, tripStartedAt = started, visitCount = visits, distanceM = distanceM)

    @Test
    fun `recording turned off`() {
        assertEquals(
            DrivingView("여행 기록이 꺼져 있어요", emptyList(), "차를 세운 뒤 설정에서 켤 수 있어요"),
            DrivingView.of(recordingEnabled = false, live = LiveStatus(moving = true), now = min(5), hasPreviousScreen = true),
        )
    }

    @Test
    fun `recording on but no drive yet`() {
        assertEquals(
            DrivingView("기록 중", emptyList(), null),
            DrivingView.of(true, live(started = null, recording = false), min(5), hasPreviousScreen = false),
        )
    }

    @Test
    fun `just after departure there are no numbers`() {
        assertEquals(
            DrivingView("기록 중 · 2분", emptyList(), null),
            DrivingView.of(true, live(distanceM = 999.0), min(2), hasPreviousScreen = false),
        )
    }

    @Test
    fun `without visits only the distance is shown, floored to whole km`() {
        assertEquals(
            DrivingView("기록 중 · 12분", listOf(Stat("9km", "달린 거리")), "차를 세우면 보던 화면으로 돌아가요"),
            DrivingView.of(true, live(distanceM = 9_870.0), min(12), hasPreviousScreen = true),
        )
        assertEquals(listOf(Stat("1km", "달린 거리")), DrivingView.of(true, live(distanceM = 1_000.0), min(3), false).stats)
    }

    @Test
    fun `with visits the distance and the visit count are shown`() {
        assertEquals(
            DrivingView("기록 중 · 3시간 10분", listOf(Stat("213km", "달린 거리"), Stat("2곳", "방문지")), "차를 세우면 보던 화면으로 돌아가요"),
            DrivingView.of(true, live(distanceM = 213_900.0, visits = 2), min(190), hasPreviousScreen = true),
        )
    }

    @Test
    fun `a visit is shown even before the first kilometer`() {
        assertEquals(
            listOf(Stat("0km", "달린 거리"), Stat("1곳", "방문지")),
            DrivingView.of(true, live(distanceM = 500.0, visits = 1), min(40), false).stats,
        )
    }
}
