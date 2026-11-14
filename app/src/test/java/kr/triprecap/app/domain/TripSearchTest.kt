package kr.triprecap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZonedDateTime

class TripSearchTest {
    private val search = TripSearch(Fx.zone)
    private fun ms(y: Int, m: Int, d: Int, h: Int = 10) = ZonedDateTime.of(y, m, d, h, 0, 0, 0, Fx.zone).toInstant().toEpochMilli()

    private fun trip(id: Long, y: Int, m: Int, d: Int, names: List<String>, regions: List<String?> = names.map { null }, people: Int = 1, rainy: Boolean = false, title: String? = null): Trip {
        val start = ms(y, m, d)
        return Trip(
            id = id,
            drives = listOf(Fx.drive(start, start + 3_600_000L, Fx.home, Fx.beach, rainy = rainy, seats = people)),
            stops = names.mapIndexed { i, n -> Stop(i + 1, StopKind.VISIT, n, Fx.beach, start + 3_600_000L, start + 7_200_000L, region = regions[i]) },
            customTitle = title,
            closed = true,
        )
    }

    private val gangneung = trip(1, 2026, 11, 7, listOf("경포해변", "강릉중앙시장"), listOf("강릉시", "강릉시"), people = 3, rainy = true)
    private val jeju = trip(2, 2026, 10, 9, listOf("협재해변", "함덕해변"), listOf("제주시", "제주시"), people = 2)
    private val sokcho = trip(3, 2025, 11, 9, listOf("속초해변"), listOf("속초시"))
    private val spring = trip(4, 2026, 4, 12, listOf("여의도 벚꽃길"), listOf("영등포구"), title = "벚꽃 구경")
    private val all = listOf(sokcho, spring, jeju, gangneung)
    private fun ids(q: TripSearch.Query) = search.search(all, q).map { it.id }

    @Test
    fun `empty query returns every trip newest first`() {
        assertEquals(listOf(1L, 2L, 4L, 3L), ids(TripSearch.Query()))
    }

    @Test
    fun `text matches a place name`() {
        assertEquals(listOf(2L), ids(TripSearch.Query(text = "협재")))
    }

    @Test
    fun `text matches region and custom title`() {
        assertEquals(listOf(1L), ids(TripSearch.Query(text = "강릉시")))
        assertEquals(listOf(4L), ids(TripSearch.Query(text = "벚꽃 구경")))
    }

    @Test
    fun `every word of the text must match and case and spaces around are ignored`() {
        assertEquals(listOf(1L, 2L, 3L), ids(TripSearch.Query(text = " 해변 ")))
        assertEquals(listOf(1L), ids(TripSearch.Query(text = "해변 시장")))
        assertEquals(emptyList<Long>(), ids(TripSearch.Query(text = "해변 벚꽃")))
    }

    @Test
    fun `date range includes both end days`() {
        val q = TripSearch.Query(from = LocalDate.of(2026, 10, 9), to = LocalDate.of(2026, 11, 7))
        assertEquals(listOf(1L, 2L), ids(q))
        assertEquals(listOf(2L, 4L, 3L), ids(TripSearch.Query(to = LocalDate.of(2026, 11, 6))))
    }

    @Test
    fun `with companions means two or more people`() {
        assertEquals(listOf(1L, 2L), ids(TripSearch.Query(withCompanions = true)))
    }

    @Test
    fun `rainy only`() {
        assertEquals(listOf(1L), ids(TripSearch.Query(rainy = true)))
    }

    @Test
    fun `conditions combine`() {
        assertEquals(emptyList<Long>(), ids(TripSearch.Query(text = "제주", rainy = true)))
    }

    @Test
    fun `a year ago today finds a trip within 3 days of the same date last year`() {
        assertEquals(3L, search.oneYearAgo(all, LocalDate.of(2026, 11, 10))?.id)
        assertEquals(3L, search.oneYearAgo(all, LocalDate.of(2026, 11, 12))?.id)
        assertEquals(3L, search.oneYearAgo(all, LocalDate.of(2026, 11, 6))?.id)
        assertNull(search.oneYearAgo(all, LocalDate.of(2026, 11, 13)))
        assertNull(search.oneYearAgo(all, LocalDate.of(2026, 11, 5)))
    }

    @Test
    fun `a year ago today picks the closest date when several match`() {
        val closer = trip(9, 2025, 11, 11, listOf("남이섬"))
        assertEquals(9L, search.oneYearAgo(all + closer, LocalDate.of(2026, 11, 11))?.id)
    }

    @Test
    fun `season groups are newest first and winter belongs to the december year`() {
        val jan = trip(5, 2026, 1, 20, listOf("눈꽃축제"))
        val dec = trip(6, 2025, 12, 24, listOf("스키장"))
        val groups = search.seasonGroups(all + jan + dec)
        assertEquals(
            listOf("2026 가을" to listOf(1L, 2L), "2026 봄" to listOf(4L), "2025 겨울" to listOf(5L, 6L), "2025 가을" to listOf(3L)),
            groups.map { it.label to it.trips.map { t -> t.id } },
        )
    }
}
