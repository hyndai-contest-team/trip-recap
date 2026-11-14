package kr.triprecap.app.domain

import kr.triprecap.app.domain.Fx.at
import kr.triprecap.app.domain.Fx.market
import kr.triprecap.app.domain.Fx.north
import kr.triprecap.app.domain.Fx.summary
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegularsTest {
    private val now = at(13, 20)

    /** 11월 [days] 일 저녁마다 [p] 에 20분 들른 기록 */
    private fun visits(p: GeoPoint, vararg days: Int, visit: Boolean = true) =
        days.map { summary(at(it, 19), at(it, 19, 20), p, visit) }

    @Test
    fun `three visits within 30 days make a regular`() {
        val r = Regulars.from(visits(market, 3, 6, 10), now)
        assertTrue(r.isRegular(market))
    }

    @Test
    fun `two visits are not enough`() {
        val r = Regulars.from(visits(market, 6, 10), now)
        assertFalse(r.isRegular(market))
    }

    @Test
    fun `visits count within 150m of the queried point`() {
        val log = visits(market, 3) + visits(north(market, 100.0), 6) + visits(north(market, 140.0), 10)
        assertTrue(Regulars.from(log, now).isRegular(market))
        val far = visits(market, 3, 6) + visits(north(market, 160.0), 10)
        assertFalse(Regulars.from(far, now).isRegular(market))
    }

    @Test
    fun `brief stops and lodging do not count as visits`() {
        val log = visits(market, 3, 6) + visits(market, 10, visit = false)
        assertFalse(Regulars.from(log, now).isRegular(market))
    }

    @Test
    fun `visits older than 30 days do not count`() {
        val old = summary(now - 31L * 24 * 3_600_000L, now - 31L * 24 * 3_600_000L + 1_200_000L, market)
        val log = listOf(old) + visits(market, 6, 10)
        assertFalse(Regulars.from(log, now).isRegular(market))
        val inside = summary(now - 29L * 24 * 3_600_000L, now - 29L * 24 * 3_600_000L + 1_200_000L, market)
        assertTrue(Regulars.from(listOf(inside) + visits(market, 6, 10), now).isRegular(market))
    }

    @Test
    fun `visits of the current trip are excluded`() {
        val log = visits(market, 3, 6, 13)
        assertTrue(Regulars.from(log, now).isRegular(market))
        assertFalse(Regulars.from(log, now, excludeFrom = at(13, 18)).isRegular(market))
        assertFalse(Regulars.from(log, now, excludeFrom = at(13, 19)).isRegular(market))
    }

    @Test
    fun `unknown point and empty log are never regular`() {
        assertFalse(Regulars.from(visits(market, 3, 6, 10), now).isRegular(null))
        assertFalse(Regulars.NONE.isRegular(market))
    }
}
