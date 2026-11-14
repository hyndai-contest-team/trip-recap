package kr.triprecap.app.scenario

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kr.triprecap.app.domain.Gear
import kr.triprecap.app.domain.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenarioPlayerTest {
    private var real = 1_000_000L
    private val seen = mutableListOf<Pair<ScenarioEvent, Long>>()
    private val scenario = Scenario(
        name = "t",
        epochAtZero = 5_000_000_000L,
        steps = listOf(
            Step(0, ScenarioEvent.GearTo(Gear.PARK)),
            Step(60_000, ScenarioEvent.GearTo(Gear.DRIVE)),
            Step(120_000, ScenarioEvent.Location(GeoPoint(37.0, 127.0))),
            Step(600_000, ScenarioEvent.GearTo(Gear.PARK)),
            Step(900_000, ScenarioEvent.Door(true)),
        ),
        chapters = listOf(Chapter("a", 0), Chapter("b", 600_000)),
    )
    private val player = ScenarioPlayer(TestScope(), initialSpeed = 60.0, realNow = { real }).apply {
        sink = { e, at -> seen += e to at }
        load(this@ScenarioPlayerTest.scenario)
    }

    private fun step() = runBlocking { player.step() }

    @Test
    fun `loaded scenario stays at the first step until resumed`() {
        real += 60_000
        step()
        assertEquals(listOf(5_000_000_000L), seen.map { it.second })
        assertEquals(Gear.PARK, player.gear.value)
    }

    @Test
    fun `at 60x one real second advances one scenario minute and events carry their own times`() {
        step(); player.resume()
        real += 2_000
        step()
        assertEquals(listOf(0L, 60_000L, 120_000L), seen.map { it.second - scenario.epochAtZero })
        assertEquals(5_000_000_000L + 120_000L, player.now())
        assertEquals(Gear.DRIVE, player.gear.value)
    }

    @Test
    fun `pause freezes the clock and speed changes apply from that moment`() {
        step(); player.resume()
        real += 1_000; step()
        player.pause()
        real += 60_000; step()
        assertEquals(2, seen.size)
        player.resume(); player.setSpeed(10.0)
        real += 6_000; step()          // 6초 × 10배속 = 1분 → 120초 지점
        assertEquals(3, seen.size)
    }

    @Test
    fun `jumping to a chapter delivers every earlier event in order`() {
        step(); player.resume()
        player.jumpToChapter(1)
        step()
        assertEquals(listOf(0L, 60_000L, 120_000L, 600_000L), seen.map { it.second - scenario.epochAtZero })
        assertEquals(1, player.chapterIndex.value)
        assertFalse(player.finished.value)
    }

    @Test
    fun `next chapter after the last one runs to the end`() {
        step(); player.resume()
        player.nextChapter(); step()
        player.nextChapter(); step()
        assertEquals(5, seen.size)
        assertTrue(player.finished.value)
    }

    @Test
    fun `tick callback reports the scenario time while playing`() {
        val ticks = mutableListOf<Long>()
        player.onTick = { ticks += it }
        step()
        assertTrue(ticks.isEmpty())
        player.resume(); real += 1_000; step()
        assertEquals(listOf(5_000_000_000L + 60_000L), ticks)
    }

    @Test
    fun `loading starts at the first vehicle event and still delivers the home registration before it`() {
        val withHome = Scenario(
            name = "h",
            epochAtZero = 0L,
            steps = listOf(Step(0, ScenarioEvent.Home(GeoPoint(37.5, 127.1))), Step(9 * 3_600_000L, ScenarioEvent.GearTo(Gear.PARK))),
        )
        seen.clear()
        player.load(withHome)
        step()
        assertEquals(listOf(0L, 9 * 3_600_000L), seen.map { it.second })
        assertEquals(9 * 3_600_000L, player.now())
    }
}
