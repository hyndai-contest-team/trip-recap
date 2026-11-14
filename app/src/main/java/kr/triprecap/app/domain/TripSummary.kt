package kr.triprecap.app.domain

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 여행 요약 (docs/product-plan.md 2-3). 도착 카드([ArrivalCard])는 이 가운데 [rainyNames] 만 쓴다 */
data class TripSummary(
    val visitCount: Int,
    val distanceM: Double,
    /** 가장 오래 머문 방문지(숙박·잠깐 정차는 제외) */
    val longestName: String?,
    val longestMinutes: Long?,
    /** 오는 길에 비가 온 정차 이름 */
    val rainyNames: List<String>,
    val peopleCount: Int,
    val durationMs: Long,
)

object TripSummarizer {
    fun summarize(trip: Trip): TripSummary {
        val longest = trip.visits.maxByOrNull { it.stayMinutes }
        return TripSummary(
            visitCount = trip.visits.size,
            distanceM = trip.distanceM,
            longestName = longest?.let { trip.nameOf(it) },
            longestMinutes = longest?.stayMinutes,
            rainyNames = trip.stops.filter { it.rainy && it.kind != StopKind.BRIEF }.map { trip.nameOf(it) },
            peopleCount = trip.peopleCount,
            durationMs = trip.endedAt - trip.startedAt,
        )
    }

    private val ones = listOf("한", "두", "세", "네", "다섯", "여섯", "일곱", "여덟", "아홉")
    private val tens = listOf("열", "스물", "서른", "마흔", "쉰", "예순", "일흔", "여든", "아흔")

    /**
     * 수 관형사(고유어): 1 "한", 12 "열두", 20 "스무", 21 "스물한" … 99 "아흔아홉".
     * 1~99 밖이면 null 이다(그때는 숫자로 쓴다)
     */
    fun nativeCounter(n: Int): String? {
        if (n !in 1..99) return null
        val t = n / 10
        val o = n % 10
        if (n == 20) return "스무"
        return (if (t > 0) tens[t - 1] else "") + (if (o > 0) ones[o - 1] else "")
    }

    /**
     * 여행 종료 때 TTS 로 읽는 한 문장. "세 곳 다녀온 여행을 저장했어요." / 숙박 있음 "여섯 곳 다녀온 2박 3일 여행을 저장했어요."
     * 100곳 이상은 "120곳"처럼 숫자로 쓴다
     */
    fun arrivalSentence(visitCount: Int, nights: Int = 0): String {
        val visits = if (visitCount <= 0) "" else (nativeCounter(visitCount)?.let { "$it 곳" } ?: "${visitCount}곳") + " 다녀온 "
        val length = if (nights > 0) "${Texts.nights(nights)} " else ""
        return "${visits}${length}여행을 저장했어요."
    }

    fun arrivalSentence(trip: Trip): String = arrivalSentence(trip.visits.size, trip.nights)
}

/** 화면·음성에 쓰는 숫자 표기 */
object Texts {
    const val LABEL_DISTANCE = "달린 거리"
    const val LABEL_TRIP_TIME = "여행 시간"
    const val LABEL_VISITS = "방문"
    const val LABEL_PEOPLE = "함께 탄 사람"

    /** 80 → "1시간 20분", 45 → "45분", 120 → "2시간" */
    fun duration(minutes: Long): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h > 0 && m > 0 -> "${h}시간 ${m}분"
            h > 0 -> "${h}시간"
            else -> "${m}분"
        }
    }

    fun durationMs(ms: Long): String = duration(ms / 60_000L)

    /** 10km 이상은 정수, 미만은 소수 1자리 */
    fun distance(meters: Double): String {
        val km = meters / 1000.0
        return if (km >= 10.0) "${Math.round(km)}km" else "${Math.round(km * 10) / 10.0}km"
    }

    /** 숙박 n번 → "n박 (n+1)일" */
    fun nights(n: Int): String = "${n}박 ${n + 1}일"

    /** 여행 시간: 숙박이 있으면 "2박 3일", 없으면 "10시간 35분" */
    fun tripLength(trip: Trip): String =
        if (trip.nights > 0) nights(trip.nights) else durationMs(trip.endedAt - trip.startedAt)

    private val weekdays = listOf("월", "화", "수", "목", "금", "토", "일")

    /** [now] 와 같은 해면 "11월 7일 (토)", 다른 해면 "2025년 11월 10일 (월)" */
    fun date(epochMs: Long, zone: ZoneId, now: Long): String {
        val d = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
        val thisYear = Instant.ofEpochMilli(now).atZone(zone).year
        val day = "${d.monthValue}월 ${d.dayOfMonth}일 (${weekdays[d.dayOfWeek.value - 1]})"
        return if (d.year == thisYear) day else "${d.year}년 $day"
    }

    /**
     * 목록 카드의 한 줄: "11월 7일 (토) · 방문 3곳 · 3명 · 413km".
     * 숙박이 있으면 날짜 바로 뒤에 "2박 3일"을 넣는다(목록 화면의 제주 카드 순서). 혼자면 인원을 적지 않는다
     */
    fun listMeta(trip: Trip, zone: ZoneId, now: Long): String = buildList {
        add(date(trip.startedAt, zone, now))
        if (trip.nights > 0) add(nights(trip.nights))
        add("방문 ${trip.visits.size}곳")
        if (trip.peopleCount >= 2) add("${trip.peopleCount}명")
        add(distance(trip.distanceM))
    }.joinToString(" · ")

    /** 상세 화면 숫자 칸: 달린 거리, 여행 시간, 방문, 함께 탄 사람(2명 이상일 때만) */
    fun detailStats(trip: Trip): List<Stat> = buildList {
        add(Stat(distance(trip.distanceM), LABEL_DISTANCE))
        add(Stat(tripLength(trip), LABEL_TRIP_TIME))
        add(Stat("${trip.visits.size}곳", LABEL_VISITS))
        if (trip.peopleCount >= 2) add(Stat("${trip.peopleCount}명", LABEL_PEOPLE))
    }

    /**
     * 방문지 시각 줄: "11:20 – 12:40 · 강릉시 · 12°C". 지역·외기온은 있을 때만 붙인다. 머문 시간은 [stay] 로 따로 쓴다
     */
    fun stopClock(stop: Stop, zone: ZoneId): String {
        val f = DateTimeFormatter.ofPattern("H:mm").withZone(zone)
        val a = Instant.ofEpochMilli(stop.arrivedAt)
        val d = Instant.ofEpochMilli(stop.departedAt)
        val nextDay = a.atZone(zone).toLocalDate() != d.atZone(zone).toLocalDate()
        val clock = "${f.format(a)} – ${if (nextDay) "다음 날 " else ""}${f.format(d)}"
        return listOfNotNull(clock, stop.region, stop.outsideTempC?.let { "$it°C" }).joinToString(" · ")
    }

    /** 머문 시간: "1시간 20분" */
    fun stay(stop: Stop): String = duration(stop.stayMinutes)

    /** 방문지 지우기 확인 대화상자 제목: "강릉중앙시장을 지울까요?" */
    fun deleteStopQuestion(name: String): String = "${Josa.eulReul(name)} 지울까요?"
}
