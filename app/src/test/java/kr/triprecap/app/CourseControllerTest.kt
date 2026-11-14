package kr.triprecap.app

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kr.triprecap.app.domain.CourseLeg
import kr.triprecap.app.domain.CoursePlace
import kr.triprecap.app.domain.GeoPoint
import kr.triprecap.app.scenario.ScenarioPlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CourseControllerTest {
    private val navi = ScenarioPlayer(TestScope())
    private val c = CourseController { navi }
    private fun place(n: String) = CoursePlace(n, GeoPoint(37.0, 128.0))
    private val legs = listOf(
        CourseLeg(listOf(place("A"), place("B")), place("C")),
        CourseLeg(emptyList(), place("D")),
    )

    @Test
    fun `starting a course requests the first leg with its waypoints`() = runBlocking {
        c.start(legs)
        assertEquals("A → B → C", navi.lastRouteRequest.value)
        assertEquals(CourseProgress(1, 2, listOf("A", "B", "C"), true), c.progress.value)
    }

    @Test
    fun `the next leg is requested on departure after arriving`() = runBlocking {
        c.start(legs)
        c.onDeparted()
        assertEquals("A → B → C", navi.lastRouteRequest.value)
        c.onDestinationArrived()
        c.onDeparted()
        assertEquals("D", navi.lastRouteRequest.value)
        assertEquals(2, c.progress.value!!.legNumber)
    }

    @Test
    fun `progress clears after the last arrival and going to a single place cancels a course`() = runBlocking {
        c.start(legs)
        c.onDestinationArrived(); c.onDeparted(); c.onDestinationArrived()
        assertNull(c.progress.value)
        c.start(legs)
        c.goTo(place("Z"))
        assertEquals("Z", navi.lastRouteRequest.value)
        assertNull(c.progress.value)
    }
}
