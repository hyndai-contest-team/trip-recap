package kr.triprecap.app.domain

import kr.triprecap.app.domain.Fx.at
import java.time.ZonedDateTime

/** 목록·요약·공유 화면 문자열 시험에 쓰는 여행 */
object Samples {
    val hoengseongRest = GeoPoint(37.4783, 127.9989)

    /** 강릉 당일: 11월 7일 (토), 3명, 413km, 10시간 35분, 방문 3곳, 카페 가는 길에 비 */
    val gangneung = Trip(
        id = 1,
        drives = listOf(
            Fx.drive(at(7, 9, 10), at(7, 11, 20), Fx.home, Fx.beach, seats = 3, distanceM = 230_000.0),
            Fx.drive(at(7, 12, 40), at(7, 13, 5), Fx.beach, Fx.cafe, rainy = true, seats = 3, distanceM = 6_000.0),
            Fx.drive(at(7, 14, 10), at(7, 15, 0), Fx.cafe, Fx.market, seats = 3, distanceM = 3_000.0),
            Fx.drive(at(7, 16, 30), at(7, 17, 45), Fx.market, hoengseongRest, seats = 3, distanceM = 92_000.0),
            Fx.drive(at(7, 17, 53), at(7, 19, 45), hoengseongRest, Fx.home, seats = 3, distanceM = 82_000.0),
        ),
        stops = listOf(
            Stop(1, StopKind.VISIT, "경포해변", Fx.beach, at(7, 11, 20), at(7, 12, 40), region = "강릉시"),
            Stop(2, StopKind.VISIT, "안목해변 카페", Fx.cafe, at(7, 13, 5), at(7, 14, 10), rainy = true, region = "강릉시"),
            Stop(3, StopKind.VISIT, "강릉중앙시장", Fx.market, at(7, 15, 0), at(7, 16, 30), region = "강릉시"),
            Stop(4, StopKind.BRIEF, "횡성휴게소", hoengseongRest, at(7, 17, 45), at(7, 17, 53), doorOpened = false),
        ),
        closed = true,
    )

    private fun oct(day: Int, h: Int, m: Int = 0): Long = ZonedDateTime.of(2026, 10, day, h, m, 0, 0, Fx.zone).toInstant().toEpochMilli()

    /**
     * 제주 2박 3일: 10월 9일 (금), 2명, 156km, 방문 6곳, 숙박 2곳.
     * 좌표는 제주가 아니라 강릉 표본의 것(Fx.home, Fx.beach)을 다시 썼다. 이 여행을 쓰는 시험은 날짜·거리·정차 이름·종류만 보고
     * 좌표 값은 결과에 영향을 주지 않는다(정차에 좌표가 있는지만 본다)
     */
    val jeju = Trip(
        id = 2,
        drives = listOf(
            Fx.drive(oct(9, 9, 30), oct(9, 10, 20), Fx.home, Fx.beach, seats = 2, distanceM = 150_000.0),
            Fx.drive(oct(11, 11, 40), oct(11, 12, 10), Fx.beach, Fx.home, seats = 2, distanceM = 6_000.0),
        ),
        stops = listOf(
            Stop(1, StopKind.VISIT, "협재해변", Fx.beach, oct(9, 10, 20), oct(9, 11, 20)),
            Stop(2, StopKind.VISIT, "오설록 티뮤지엄", Fx.beach, oct(9, 11, 50), oct(9, 12, 40)),
            Stop(3, StopKind.LODGING, "서귀포 호텔", Fx.beach, oct(9, 13, 40), oct(10, 9, 30)),
            Stop(4, StopKind.VISIT, "천지연폭포", Fx.beach, oct(10, 9, 40), oct(10, 10, 20)),
            Stop(5, StopKind.VISIT, "쇠소깍", Fx.beach, oct(10, 10, 40), oct(10, 11, 25)),
            Stop(6, StopKind.LODGING, "성산 숙소", Fx.beach, oct(10, 12, 30), oct(11, 8, 30)),
            Stop(7, StopKind.VISIT, "성산일출봉", Fx.beach, oct(11, 8, 40), oct(11, 10, 10)),
            Stop(8, StopKind.VISIT, "함덕해변", Fx.beach, oct(11, 10, 50), oct(11, 11, 40)),
        ),
        closed = true,
    )

    /** 의암호 당일: 혼자, 143km, 방문 1곳 */
    val lake = Trip(
        id = 3,
        drives = listOf(
            Fx.drive(at(15, 10, 0), at(15, 11, 10), Fx.home, Fx.beach, seats = 1, distanceM = 71_500.0),
            Fx.drive(at(15, 12, 0), at(15, 13, 10), Fx.beach, Fx.home, seats = 1, distanceM = 71_500.0),
        ),
        stops = listOf(Stop(1, StopKind.VISIT, "의암호", Fx.beach, at(15, 11, 10), at(15, 12, 0))),
        closed = true,
    )

    /** 방문지 없는 드라이브: 41km */
    val driveOnly = Trip(
        id = 4,
        drives = listOf(Fx.drive(at(8, 14, 0), at(8, 15, 0), Fx.home, Fx.home, seats = 1, distanceM = 41_200.0)),
        closed = true,
    )
}
