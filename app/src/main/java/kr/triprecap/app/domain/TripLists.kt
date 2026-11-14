package kr.triprecap.app.domain

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 화면별로 보여 줄 이동을 고른다 (docs/product-plan.md 2-4) */
object TripLists {
    /** 일상 이동 보관 기간(일) */
    const val DAILY_KEEP_DAYS = 7
    const val DAILY_KEEP_MS = DAILY_KEEP_DAYS * 86_400_000L

    /**
     * 목록·검색·1년 전 이맘때·공유·다시 가기에 쓰는 여행.
     * 분류가 여행이고, 닫혔거나 열려 있어도 방문지가 생긴 것(열린 여행은 "기록 중"으로 보인다). 일상 이동과 묻는 중인 이동은 넣지 않는다
     */
    fun listed(trips: List<Trip>): List<Trip> =
        trips.filter { it.category == TripCategory.TRIP && (it.closed || it.visits.isNotEmpty()) }

    /** 일상 이동 화면: 닫힌 일상 이동, 시작 시각 최신순 */
    fun daily(trips: List<Trip>): List<Trip> =
        trips.filter { it.closed && it.category == TripCategory.DAILY }.sortedByDescending { it.startedAt }

    /** 목록 맨 아래 줄: "일상 이동 2건 · 7일 뒤 자동으로 지워요" */
    fun dailyFooter(count: Int): String = "일상 이동 ${count}건 · ${DAILY_KEEP_DAYS}일 뒤 자동으로 지워요"
}

/** 일상 이동 화면의 행 글자 */
data class DailyMoveRow(
    /** 날짜와 출발 시각: "11월 12일 (목) 8:10" */
    val title: String,
    /** "집 → 회사 · 45분 · 13km". 출발·도착 중 하나라도 집·회사가 아니면 앞부분을 뺀다 */
    val meta: String,
) {
    companion object {
        private val clock = DateTimeFormatter.ofPattern("H:mm")

        fun of(trip: Trip, places: KnownPlaces, zone: ZoneId, now: Long): DailyMoveRow {
            val start = Instant.ofEpochMilli(trip.startedAt).atZone(zone)
            val title = "${Texts.date(trip.startedAt, zone, now)} ${clock.format(start)}"
            val from = label(trip.startPoint, places)
            val to = label(trip.endPoint, places)
            val meta = listOfNotNull(
                if (from != null && to != null) "$from → $to" else null,
                Texts.durationMs(trip.endedAt - trip.startedAt),
                Texts.distance(trip.distanceM),
            ).joinToString(" · ")
            return DailyMoveRow(title, meta)
        }

        /** 집 300m 안이면 "집", 회사 300m 안이면 "회사". 그 밖은 null */
        private fun label(p: GeoPoint?, places: KnownPlaces): String? = when {
            p == null -> null
            places.isHome(p) -> "집"
            places.office != null && p.distanceM(places.office) <= KnownPlaces.RADIUS_M -> "회사"
            else -> null
        }
    }
}
