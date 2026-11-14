package kr.triprecap.app.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** 다시 찾기: 검색, 1년 전 오늘, 계절 묶음 (docs/product-plan.md 2-5) */
class TripSearch(private val zone: ZoneId = ZoneId.of("Asia/Seoul")) {

    data class Query(
        /** 장소 이름, 시·군·구, 여행 제목. 공백으로 나눈 단어가 모두 들어 있어야 한다 */
        val text: String = "",
        val from: LocalDate? = null,
        val to: LocalDate? = null,
        /** 동승 2명 이상 */
        val withCompanions: Boolean = false,
        val rainy: Boolean = false,
    ) {
        val isEmpty: Boolean get() = text.isBlank() && from == null && to == null && !withCompanions && !rainy
    }

    data class SeasonGroup(val label: String, val trips: List<Trip>)

    fun search(trips: List<Trip>, query: Query): List<Trip> {
        val words = query.text.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return trips.filter { t ->
            val date = dateOf(t)
            (query.from == null || !date.isBefore(query.from)) &&
                (query.to == null || !date.isAfter(query.to)) &&
                (!query.withCompanions || t.peopleCount >= 2) &&
                (!query.rainy || t.rainy) &&
                words.all { w -> haystack(t).any { it.contains(w) } }
        }.sortedByDescending { it.startedAt }
    }

    /** 작년 같은 날 ±3일 안에 시작한 여행 중 날짜가 가장 가까운 것 */
    fun oneYearAgo(trips: List<Trip>, today: LocalDate): Trip? {
        val target = today.minusYears(1)
        return trips
            .map { it to abs(ChronoUnit.DAYS.between(target, dateOf(it))) }
            .filter { it.second <= ONE_YEAR_AGO_WINDOW_DAYS }
            .minByOrNull { it.second }?.first
    }

    /** 계절·연도별 묶음. 최신 계절이 먼저다. 1·2월은 전년도 겨울에 넣는다 */
    fun seasonGroups(trips: List<Trip>): List<SeasonGroup> =
        trips.sortedByDescending { it.startedAt }
            .groupBy { seasonKey(dateOf(it)) }
            .toSortedMap(compareByDescending<Pair<Int, Int>> { it.first }.thenByDescending { it.second })
            .map { (key, list) -> SeasonGroup("${key.first} ${SEASONS[key.second]}", list) }

    private fun dateOf(t: Trip): LocalDate = Instant.ofEpochMilli(t.startedAt).atZone(zone).toLocalDate()

    private fun haystack(t: Trip): List<String> =
        (listOf(t.title) + t.stops.flatMap { listOfNotNull(it.name, it.region) }).map { it.lowercase() }

    /** (연도, 계절 순번) 봄 0 · 여름 1 · 가을 2 · 겨울 3 */
    private fun seasonKey(d: LocalDate): Pair<Int, Int> = when (d.monthValue) {
        in 3..5 -> d.year to 0
        in 6..8 -> d.year to 1
        in 9..11 -> d.year to 2
        12 -> d.year to 3
        else -> d.year - 1 to 3
    }

    companion object {
        const val ONE_YEAR_AGO_WINDOW_DAYS = 3L
        private val SEASONS = listOf("봄", "여름", "가을", "겨울")
    }
}
