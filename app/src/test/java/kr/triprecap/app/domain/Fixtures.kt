package kr.triprecap.app.domain

import java.time.ZoneId
import java.time.ZonedDateTime

/** 테스트용 좌표·시각 도우미 */
object Fx {
    val zone: ZoneId = ZoneId.of("Asia/Seoul")
    /** 집·회사 좌표는 구청·시청 근처 공공 지점의 근사값이다(개인 위치가 아니다) */
    val home = GeoPoint(37.5145, 127.1059)
    val office = GeoPoint(37.5665, 126.9780)
    val beach = GeoPoint(37.8057, 128.9087)
    val cafe = GeoPoint(37.7729, 128.9499)
    val market = GeoPoint(37.7553, 128.8963)
    val places = KnownPlaces(home, office)

    /** 2026-11-(day) h:m (KST) 의 epoch ms */
    fun at(day: Int, h: Int, m: Int = 0): Long = ZonedDateTime.of(2026, 11, day, h, m, 0, 0, zone).toInstant().toEpochMilli()

    /** p 에서 북쪽으로 m 미터 */
    fun north(p: GeoPoint, m: Double) = GeoPoint(p.lat + m / 111_195.0, p.lon)

    fun drive(start: Long, end: Long, from: GeoPoint?, to: GeoPoint?, rainy: Boolean = false, seats: Int = 1, distanceM: Double? = null): Drive {
        val track = listOfNotNull(from?.let { TrackPoint(start, it) }, to?.let { TrackPoint(end, it) })
        return Drive(
            startedAt = start, endedAt = end, track = track,
            distanceM = distanceM ?: if (from != null && to != null) from.distanceM(to) else 0.0,
            rainy = rainy, seats = SeatId.entries.take(seats).toSet(),
        )
    }

    fun stay(arrive: Long, depart: Long, at: GeoPoint?, door: Boolean = true, name: String? = null, rainy: Boolean = false) =
        Stay(arrive, depart, at, door, name, rainy)

    /** 정차 요약 1건. 좌표는 그대로 넣는다 (반올림은 StopLog.summarize 가 한다) */
    fun summary(arrive: Long, depart: Long, at: GeoPoint, visit: Boolean = true) = StopSummary(at, arrive, depart, visit)
}
