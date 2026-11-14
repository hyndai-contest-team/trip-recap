package kr.triprecap.app.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.MonthDay
import java.time.YearMonth

/** 달력 칸에 그릴 선택 표시 */
enum class DayMark { NONE, SINGLE, START, MIDDLE, END }

/**
 * 검색 기간 대화상자에서 고르는 중인 날짜.
 * [start] 만 있으면 그 하루, [end] 까지 있으면 [start]~[end] 이다.
 */
data class DateRangePick(val start: LocalDate? = null, val end: LocalDate? = null) {

    /**
     * 날짜를 눌렀을 때의 다음 상태.
     * 첫 누름은 시작일, 두 번째는 종료일(시작일보다 앞이면 새 시작일), 범위가 정해진 뒤 누르면 새 시작일부터 다시 고른다.
     * [today] 이후 날짜는 기록이 있을 수 없으므로 무시한다.
     */
    fun tap(day: LocalDate, today: LocalDate): DateRangePick = when {
        day.isAfter(today) -> this
        start == null || end != null || day.isBefore(start) -> DateRangePick(day, null)
        else -> DateRangePick(start, day)
    }

    /** 검색 조건에 넣을 (시작일, 종료일). 아무것도 고르지 않았으면 null */
    val range: Pair<LocalDate, LocalDate>?
        get() = start?.let { it to (end ?: it) }

    fun markOf(day: LocalDate): DayMark {
        val (from, to) = range ?: return DayMark.NONE
        return when {
            from == to -> if (day == from) DayMark.SINGLE else DayMark.NONE
            day == from -> DayMark.START
            day == to -> DayMark.END
            day.isAfter(from) && day.isBefore(to) -> DayMark.MIDDLE
            else -> DayMark.NONE
        }
    }
}

/** 한 달 달력(일요일 시작 7열) */
object CalendarMonth {
    val WEEKDAYS = listOf("일", "월", "화", "수", "목", "금", "토")

    /** 6주 고정이라 늘 42칸이다(달마다 대화상자 높이가 바뀌지 않게). 그 달이 아닌 칸은 null */
    const val CELLS = 42

    fun cells(month: YearMonth): List<LocalDate?> {
        val lead = month.atDay(1).dayOfWeek.let { if (it == DayOfWeek.SUNDAY) 0 else it.value }
        return List(CELLS) { i ->
            val day = i - lead + 1
            if (day in 1..month.lengthOfMonth()) month.atDay(day) else null
        }
    }

    fun title(month: YearMonth): String = "${month.year}년 ${month.monthValue}월"

    /** 이번 달 뒤로는 고를 날짜가 없다 */
    fun canGoNext(month: YearMonth, today: LocalDate): Boolean = month.isBefore(YearMonth.from(today))

    /** 처음 보여 줄 달: 정한 기간의 시작 달, 없으면 이번 달 */
    fun initial(from: LocalDate?, today: LocalDate): YearMonth = YearMonth.from(from ?: today)
}

/** 검색 기간 조건(TripSearch.Query 의 from·to)의 판정과 글자 */
object SearchPeriod {
    private val JAN_1 = MonthDay.of(1, 1)
    private val DEC_31 = MonthDay.of(12, 31)

    /** from~to 가 한 해 1월 1일~12월 31일과 정확히 같으면 그 해 (올해·작년 칩이 켜지는 조건) */
    fun fullYear(from: LocalDate?, to: LocalDate?): Int? {
        if (from == null || to == null || from.year != to.year) return null
        return if (MonthDay.from(from) == JAN_1 && MonthDay.from(to) == DEC_31) from.year else null
    }

    /** 직접 고른 기간. 한 해 전체(올해·작년 칩)이거나 기간이 없으면 null */
    fun custom(from: LocalDate?, to: LocalDate?): Pair<LocalDate, LocalDate>? =
        if (from != null && to != null && fullYear(from, to) == null) from to to else null

    /** 기간 칩 글자: 직접 고른 기간이 없으면 "기간" */
    fun chipLabel(from: LocalDate?, to: LocalDate?, today: LocalDate): String =
        custom(from, to)?.let { (f, t) -> range(f, t, today) } ?: "기간"

    /** 대화상자 제목 아래 안내 한 줄 */
    fun hint(pick: DateRangePick, today: LocalDate): String {
        val start = pick.start ?: return "첫날을 눌러 주세요"
        val end = pick.end ?: return "${day(start, today)} · 마지막 날을 눌러 주세요"
        return range(start, end, today)
    }

    /** "11월 3일". [today] 와 다른 해면 "2025년 11월 3일" (Texts.date 와 같은 연도 규칙, 요일은 뺀다) */
    fun day(d: LocalDate, today: LocalDate): String = if (d.year == today.year) monthDay(d) else "${d.year}년 ${monthDay(d)}"

    /**
     * "11월 3일 – 11월 9일", 하루면 "11월 3일".
     * 시작일은 올해가 아니면 연도를 붙인다. 종료일은 시작일과 해가 다를 때만 연도를 붙인다
     * ("2025년 11월 3일 – 11월 9일", "2025년 12월 28일 – 2026년 1월 3일").
     */
    private fun range(from: LocalDate, to: LocalDate, today: LocalDate): String {
        if (from == to) return day(from, today)
        val end = if (to.year == from.year) monthDay(to) else "${to.year}년 ${monthDay(to)}"
        return "${day(from, today)} – $end"
    }

    private fun monthDay(d: LocalDate) = "${d.monthValue}월 ${d.dayOfMonth}일"
}
