package kr.triprecap.app.domain

import kr.triprecap.app.domain.Fx.at
import kr.triprecap.app.domain.Fx.home
import kr.triprecap.app.domain.Fx.north
import kr.triprecap.app.domain.Fx.office
import kr.triprecap.app.domain.Fx.summary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 2026-11-13 은 금요일이다. 평일: 2~6, 9~13일. 주말: 7·8일 */
class PlaceEstimatorTest {

    /** [day] 일 오전 3시를 포함하는 밤샘 정차 (전날 22시 ~ 당일 8시) */
    private fun night(day: Int, p: GeoPoint = home) = summary(at(day - 1, 22), at(day, 8), p, visit = false)

    /** [day] 일 [fromH]:[fromM] ~ [toH]:[toM] 정차 */
    private fun work(day: Int, fromH: Int, toH: Int, p: GeoPoint = office, fromM: Int = 0, toM: Int = 0) =
        summary(at(day, fromH, fromM), at(day, toH, toM), p, visit = false)

    // 집

    @Test
    fun `standing at 3 am on 4 of the last 7 days is home`() {
        val log = listOf(8, 10, 11, 13).map { night(it) }
        assertEquals(home, PlaceEstimator.estimateHome(log, at(13, 12)))
    }

    @Test
    fun `3 nights are not enough for home`() {
        val log = listOf(10, 11, 13).map { night(it) }
        assertNull(PlaceEstimator.estimateHome(log, at(13, 12)))
    }

    @Test
    fun `nights older than the last 7 days do not count`() {
        // 7일 창: 7일 3시 ~ 13일 3시. 6일 밤은 창 밖이다
        val log = listOf(6, 8, 9, 10).map { night(it) }
        assertNull(PlaceEstimator.estimateHome(log, at(13, 12)))
        val inside = listOf(7, 8, 9, 10).map { night(it) }
        assertEquals(home, PlaceEstimator.estimateHome(inside, at(13, 12)))
    }

    @Test
    fun `3 am that has not come yet today is not in the window`() {
        val log = listOf(6, 8, 9, 12).map { night(it) }
        // 13일 2시 기준 창은 6일 3시 ~ 12일 3시다 (13일 3시는 아직 오지 않았다). 4일
        assertEquals(home, PlaceEstimator.estimateHome(log, at(13, 2)))
        // 13일 12시 기준 창은 7일 3시 ~ 13일 3시다. 6일이 빠져서 3일
        assertNull(PlaceEstimator.estimateHome(log, at(13, 12)))
    }

    @Test
    fun `a stay covers 3 am when it arrives at or before 3 am and departs after it`() {
        val atThree = listOf(10, 11, 12, 13).map { summary(at(it, 3), at(it, 8), home, visit = false) }
        assertEquals(home, PlaceEstimator.estimateHome(atThree, at(13, 12)))
        val leftAtThree = listOf(10, 11, 12).map { night(it) } + summary(at(12, 22), at(13, 3), home, visit = false)
        assertNull(PlaceEstimator.estimateHome(leftAtThree, at(13, 12)))
    }

    @Test
    fun `one stay over several nights counts each night`() {
        val log = listOf(summary(at(8, 22), at(11, 8), home, visit = false), night(13))
        assertEquals(home, PlaceEstimator.estimateHome(log, at(13, 12)))
    }

    @Test
    fun `nights within 150m count as one place and the most recent point is returned`() {
        val log = listOf(night(10, home), night(11, north(home, 100.0)), night(12, home), night(13, north(home, 140.0)))
        assertEquals(north(home, 140.0), PlaceEstimator.estimateHome(log, at(13, 12)))
        val split = listOf(night(10, home), night(11, north(home, 200.0)), night(12, home), night(13, home))
        assertNull(PlaceEstimator.estimateHome(split, at(13, 12)))
    }

    @Test
    fun `place with most nights wins`() {
        val other = Fx.beach
        val log = listOf(night(7, other), night(8, other), night(9, other)) + listOf(10, 11, 12, 13).map { night(it) }
        assertEquals(home, PlaceEstimator.estimateHome(log, at(13, 12)))
    }

    // 회사

    @Test
    fun `more than 4 hours in 9 to 18 on 6 weekdays of the last 14 days is office`() {
        val log = listOf(2, 3, 4, 9, 10, 11).map { work(it, 9, 14) }
        assertEquals(office, PlaceEstimator.estimateOffice(log, at(13, 20), home))
    }

    @Test
    fun `5 weekdays are not enough for office`() {
        val log = listOf(2, 3, 4, 9, 10).map { work(it, 9, 14) }
        assertNull(PlaceEstimator.estimateOffice(log, at(13, 20), home))
    }

    @Test
    fun `exactly 4 hours is not more than 4 hours`() {
        val days = listOf(2, 3, 4, 9, 10, 11)
        assertNull(PlaceEstimator.estimateOffice(days.map { work(it, 9, 13) }, at(13, 20), home))
        assertEquals(office, PlaceEstimator.estimateOffice(days.map { work(it, 9, 13, toM = 1) }, at(13, 20), home))
    }

    @Test
    fun `only the part inside 9 to 18 counts`() {
        val days = listOf(2, 3, 4, 9, 10, 11)
        // 6~13시 정차는 9~13시 4시간만 센다
        assertNull(PlaceEstimator.estimateOffice(days.map { work(it, 6, 13) }, at(13, 20), home))
        // 14~23시 정차는 14~18시 4시간만 센다
        assertNull(PlaceEstimator.estimateOffice(days.map { work(it, 14, 23) }, at(13, 20), home))
    }

    @Test
    fun `several stays at the same place in one day are summed`() {
        val days = listOf(2, 3, 4, 9, 10, 11)
        val log = days.flatMap { listOf(work(it, 8, 11), work(it, 12, 14, north(office, 100.0)), work(it, 17, 19)) }
        // 9~11시 2시간 + 12~14시 2시간 + 17~18시 1시간 = 5시간
        assertEquals(office, PlaceEstimator.estimateOffice(log, at(13, 20), home))
    }

    @Test
    fun `weekend days are not counted`() {
        val log = listOf(2, 3, 4, 9, 10).map { work(it, 9, 14) } + listOf(7, 8).map { work(it, 9, 18) }
        assertNull(PlaceEstimator.estimateOffice(log, at(13, 20), home))
    }

    @Test
    fun `weekdays older than the last 14 days do not count`() {
        // 16일(월) 기준 14일 창은 3일 ~ 16일이다. 2일은 창 밖이다
        val log = listOf(2, 3, 4, 9, 10, 11).map { work(it, 9, 14) }
        assertEquals(office, PlaceEstimator.estimateOffice(log, at(13, 20), null))
        assertNull(PlaceEstimator.estimateOffice(log, at(16, 20), null))
    }

    @Test
    fun `office within 300m of home is not returned`() {
        val nearHome = north(home, 250.0)
        val log = listOf(2, 3, 4, 9, 10, 11).map { work(it, 9, 14, nearHome) }
        assertNull(PlaceEstimator.estimateOffice(log, at(13, 20), home))
        assertEquals(nearHome, PlaceEstimator.estimateOffice(log, at(13, 20), null))
    }

    @Test
    fun `place near home is skipped and another qualifying place is returned`() {
        val days = listOf(2, 3, 4, 9, 10, 11)
        val log = days.flatMap { listOf(work(it, 9, 13, home, toM = 30), work(it, 13, 18, office, fromM = 30)) }
        assertEquals(office, PlaceEstimator.estimateOffice(log, at(13, 20), home))
    }

    @Test
    fun `place with more weekdays wins and a tie goes to the most recent place`() {
        val a = office
        val b = Fx.market
        val more = listOf(2, 3, 4, 5, 6, 9).map { work(it, 9, 14, a) } + listOf(10, 11, 12, 13).map { work(it, 9, 14, b) } +
            listOf(2, 3).map { work(it, 14, 18, b) }
        // a 6일, b 4일 (2·3일 14~18시는 4시간이라 세지 않는다)
        assertEquals(a, PlaceEstimator.estimateOffice(more, at(13, 20), home))
        val days = listOf(2, 3, 4, 9, 10, 11)
        val tie = days.flatMap { listOf(work(it, 9, 13, a, toM = 1), work(it, 13, 17, b, fromM = 1, toM = 2)) }
        assertEquals(b, PlaceEstimator.estimateOffice(tie, at(13, 20), home))
    }
}
