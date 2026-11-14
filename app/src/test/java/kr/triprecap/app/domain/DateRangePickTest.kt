package kr.triprecap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class DateRangePickTest {
    private val today = LocalDate.of(2026, 11, 14)
    private fun d(m: Int, day: Int, y: Int = 2026) = LocalDate.of(y, m, day)

    // ── 날짜 누름 규칙 ──

    @Test
    fun `first tap sets the start day`() {
        assertEquals(DateRangePick(d(11, 3), null), DateRangePick().tap(d(11, 3), today))
    }

    @Test
    fun `second tap on a later day sets the end day`() {
        assertEquals(DateRangePick(d(11, 3), d(11, 9)), DateRangePick(d(11, 3)).tap(d(11, 9), today))
    }

    @Test
    fun `second tap on the same day makes a one day range`() {
        assertEquals(DateRangePick(d(11, 3), d(11, 3)), DateRangePick(d(11, 3)).tap(d(11, 3), today))
    }

    @Test
    fun `second tap on an earlier day becomes the new start`() {
        assertEquals(DateRangePick(d(11, 1), null), DateRangePick(d(11, 3)).tap(d(11, 1), today))
    }

    @Test
    fun `tap after a finished range starts over`() {
        assertEquals(DateRangePick(d(10, 20), null), DateRangePick(d(11, 3), d(11, 9)).tap(d(10, 20), today))
    }

    @Test
    fun `days after today cannot be picked`() {
        val pick = DateRangePick(d(11, 3))
        assertEquals(pick, pick.tap(d(11, 15), today))
        assertEquals(DateRangePick(d(11, 3), d(11, 14)), pick.tap(d(11, 14), today))
    }

    @Test
    fun `range is the start to end, or the start day alone`() {
        assertNull(DateRangePick().range)
        assertEquals(d(11, 3) to d(11, 3), DateRangePick(d(11, 3)).range)
        assertEquals(d(11, 3) to d(11, 9), DateRangePick(d(11, 3), d(11, 9)).range)
    }

    @Test
    fun `marks of each day`() {
        val pick = DateRangePick(d(11, 3), d(11, 5))
        assertEquals(DayMark.NONE, pick.markOf(d(11, 2)))
        assertEquals(DayMark.START, pick.markOf(d(11, 3)))
        assertEquals(DayMark.MIDDLE, pick.markOf(d(11, 4)))
        assertEquals(DayMark.END, pick.markOf(d(11, 5)))
        assertEquals(DayMark.NONE, pick.markOf(d(11, 6)))
        assertEquals(DayMark.SINGLE, DateRangePick(d(11, 3)).markOf(d(11, 3)))
        assertEquals(DayMark.SINGLE, DateRangePick(d(11, 3), d(11, 3)).markOf(d(11, 3)))
        assertEquals(DayMark.NONE, DateRangePick().markOf(d(11, 3)))
    }

    // ── 달력 칸 배열 ──

    @Test
    fun `month starting on sunday fills from the first cell`() {
        val cells = CalendarMonth.cells(YearMonth.of(2026, 11))
        assertEquals(42, cells.size)
        assertEquals(d(11, 1), cells[0])
        assertEquals(d(11, 30), cells[29])
        assertNull(cells[30])
        assertNull(cells[41])
    }

    @Test
    fun `month starting on thursday leaves four blank cells`() {
        val cells = CalendarMonth.cells(YearMonth.of(2026, 10))
        assertEquals(42, cells.size)
        assertEquals(listOf(null, null, null, null), cells.take(4))
        assertEquals(d(10, 1), cells[4])
        assertEquals(d(10, 31), cells[34])
        assertNull(cells[35])
    }

    @Test
    fun `weekday header starts on sunday`() {
        assertEquals(listOf("일", "월", "화", "수", "목", "금", "토"), CalendarMonth.WEEKDAYS)
    }

    @Test
    fun `month title has the year`() {
        assertEquals("2026년 11월", CalendarMonth.title(YearMonth.of(2026, 11)))
    }

    @Test
    fun `next month is allowed only before this month`() {
        assertTrue(CalendarMonth.canGoNext(YearMonth.of(2026, 10), today))
        assertFalse(CalendarMonth.canGoNext(YearMonth.of(2026, 11), today))
    }

    @Test
    fun `first month shown is the start month of the range, otherwise this month`() {
        assertEquals(YearMonth.of(2025, 8), CalendarMonth.initial(d(8, 30, 2025), today))
        assertEquals(YearMonth.of(2026, 11), CalendarMonth.initial(null, today))
    }

    // ── 올해·작년 판정 ──

    @Test
    fun `full year range is detected`() {
        assertEquals(2025, SearchPeriod.fullYear(d(1, 1, 2025), d(12, 31, 2025)))
        assertNull(SearchPeriod.fullYear(d(1, 1, 2025), d(12, 30, 2025)))
        assertNull(SearchPeriod.fullYear(d(1, 1, 2025), d(12, 31, 2026)))
        assertNull(SearchPeriod.fullYear(null, null))
    }

    @Test
    fun `custom range excludes a full year`() {
        assertEquals(d(1, 1) to d(3, 31), SearchPeriod.custom(d(1, 1), d(3, 31)))
        assertNull(SearchPeriod.custom(d(1, 1, 2025), d(12, 31, 2025)))
        assertNull(SearchPeriod.custom(null, null))
    }

    // ── 칩·안내 글자 ──

    @Test
    fun `chip label without a custom range`() {
        assertEquals("기간", SearchPeriod.chipLabel(null, null, today))
        assertEquals("기간", SearchPeriod.chipLabel(d(1, 1, 2025), d(12, 31, 2025), today))
    }

    @Test
    fun `chip label of a range this year`() {
        assertEquals("11월 3일 – 11월 9일", SearchPeriod.chipLabel(d(11, 3), d(11, 9), today))
    }

    @Test
    fun `chip label of one day`() {
        assertEquals("11월 3일", SearchPeriod.chipLabel(d(11, 3), d(11, 3), today))
        assertEquals("2025년 11월 3일", SearchPeriod.chipLabel(d(11, 3, 2025), d(11, 3, 2025), today))
    }

    @Test
    fun `chip label of a range in another year shows the year once`() {
        assertEquals("2025년 11월 3일 – 11월 9일", SearchPeriod.chipLabel(d(11, 3, 2025), d(11, 9, 2025), today))
    }

    @Test
    fun `chip label of a range across years shows both years`() {
        assertEquals("2025년 12월 28일 – 2026년 1월 3일", SearchPeriod.chipLabel(d(12, 28, 2025), d(1, 3), today))
    }

    @Test
    fun `dialog hint follows the pick`() {
        assertEquals("첫날을 눌러 주세요", SearchPeriod.hint(DateRangePick(), today))
        assertEquals("11월 3일 · 마지막 날을 눌러 주세요", SearchPeriod.hint(DateRangePick(d(11, 3)), today))
        assertEquals("11월 3일 – 11월 9일", SearchPeriod.hint(DateRangePick(d(11, 3), d(11, 9)), today))
        assertEquals("11월 3일", SearchPeriod.hint(DateRangePick(d(11, 3), d(11, 3)), today))
    }

    @Test
    fun `day label for the screen reader`() {
        assertEquals("11월 3일", SearchPeriod.day(d(11, 3), today))
        assertEquals("2025년 8월 30일", SearchPeriod.day(d(8, 30, 2025), today))
    }
}
