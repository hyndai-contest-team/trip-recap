package kr.triprecap.app.scenario

import kr.triprecap.app.domain.GeoPoint
import kr.triprecap.app.domain.SeatId
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 골든 시나리오 9개 (docs/product-plan.md 4절). 기대 결과는 GoldenScenarioTest 에 고정돼 있다.
 * 좌표는 주요 지점의 근사값이다. 집·회사·부모님 댁 좌표는 구청·시청 근처 공공 지점의 근사값이다(개인 위치가 아니다).
 * 외기온은 날짜·지역에 맞춰 정한 예시 값이다(9월 하순 19~27도, 10월 제주 18~23도, 11월 서울·경기·강원·세종 -2~15도)
 */
object GoldenScenarios {
    private val zone = ZoneId.of("Asia/Seoul")
    private fun day(y: Int, m: Int, d: Int) = ZonedDateTime.of(y, m, d, 0, 0, 0, 0, zone)

    // 서울·영동고속도로·강릉
    val songpaHome = GeoPoint(37.5145, 127.1059)
    val officeCityHall = GeoPoint(37.5665, 126.9780)
    private val hanam = GeoPoint(37.5393, 127.2148)
    private val hobeop = GeoPoint(37.2187, 127.4153)
    private val yeoju = GeoPoint(37.2700, 127.6300)
    private val wonju = GeoPoint(37.3400, 127.9200)
    val hoengseongRest = GeoPoint(37.4783, 127.9989)
    private val pyeongchang = GeoPoint(37.5570, 128.3710)
    private val daegwallyeong = GeoPoint(37.6870, 128.7500)
    private val gangneungIc = GeoPoint(37.7650, 128.8500)
    val gyeongpo = GeoPoint(37.8057, 128.9087)
    val anmokCafe = GeoPoint(37.7729, 128.9499)
    val jungangMarket = GeoPoint(37.7553, 128.8963)

    private val toGangneung = listOf(songpaHome, hanam, hobeop, yeoju, wonju, hoengseongRest, pyeongchang, daegwallyeong, gangneungIc)

    /** 1. 강릉 당일: 방문지 3곳, 카페 구간 비, 3명. 귀갓길 휴게소 8분은 잠깐 정차 */
    val gangneungDayTrip: Scenario = ScenarioBuilder("강릉 당일", day(2026, 11, 7)).run {
        home(songpaHome)
        office(officeCityHall)
        startParked(t(0, 9, 0), songpaHome, "집")
        temp(t(0, 9, 0), 6.2)
        seats(t(0, 9, 5), SeatId.DRIVER, SeatId.PASSENGER, SeatId.REAR_LEFT)
        door(t(0, 9, 8))
        chapter("출발", t(0, 9, 0))
        drive(t(0, 9, 10), t(0, 11, 20), toGangneung + gyeongpo, "경포해변")
        temp(t(0, 10, 30), 11.4)
        temp(t(0, 11, 15), 14.3)
        chapter("경포해변", t(0, 11, 20))
        door(t(0, 11, 21)); door(t(0, 12, 38))
        drive(t(0, 12, 40), t(0, 13, 5), listOf(gyeongpo, GeoPoint(37.7900, 128.9300), anmokCafe), "안목해변 카페")
        wiper(t(0, 12, 48), t(0, 13, 0))
        temp(t(0, 12, 55), 10.6)
        chapter("비 오는 길에 카페", t(0, 13, 5))
        door(t(0, 13, 6)); door(t(0, 14, 8))
        drive(t(0, 14, 10), t(0, 15, 0), listOf(anmokCafe, GeoPoint(37.7640, 128.9200), jungangMarket), "강릉중앙시장")
        temp(t(0, 14, 40), 13.2)
        chapter("강릉중앙시장", t(0, 15, 0))
        door(t(0, 15, 1)); door(t(0, 16, 28))
        drive(t(0, 16, 30), t(0, 17, 45), listOf(jungangMarket, gangneungIc, daegwallyeong, pyeongchang, hoengseongRest), "횡성휴게소")
        temp(t(0, 17, 30), 7.8)
        chapter("휴게소", t(0, 17, 45))
        door(t(0, 17, 47))
        drive(t(0, 17, 53), t(0, 19, 45), listOf(hoengseongRest, wonju, yeoju, hobeop, hanam, songpaHome), "집")
        temp(t(0, 19, 30), 9.1)
        chapter("집 도착", t(0, 19, 45))
        door(t(0, 19, 46))
        add(t(0, 19, 49), ScenarioEvent.IgnitionOff)
        build()
    }

    // 제주
    val jejuHome = GeoPoint(33.4996, 126.5312)
    private val hyeopjae = GeoPoint(33.3940, 126.2397)
    private val osulloc = GeoPoint(33.3059, 126.2895)
    private val seogwipoHotel = GeoPoint(33.2480, 126.5650)
    private val cheonjiyeon = GeoPoint(33.2470, 126.5545)
    private val soesokkak = GeoPoint(33.2520, 126.6230)
    private val seongsanStay = GeoPoint(33.4480, 126.9150)
    private val ilchulbong = GeoPoint(33.4590, 126.9400)
    private val hamdeok = GeoPoint(33.5430, 126.6690)

    /** 2. 제주 2박 3일: 드라이브 9개가 여행 1개. 숙소 2곳은 숙박 */
    val jejuThreeDays: Scenario = ScenarioBuilder("제주 2박 3일", day(2026, 10, 9)).run {
        home(jejuHome)
        startParked(t(0, 9, 0), jejuHome, "집")
        temp(t(0, 9, 0), 19.4)
        seats(t(0, 9, 20), SeatId.DRIVER, SeatId.PASSENGER)
        chapter("1일째", t(0, 9, 0))
        temp(t(0, 10, 10), 21.6)
        temp(t(0, 11, 40), 22.8)
        temp(t(0, 13, 30), 23.1)
        temp(t(1, 9, 20), 20.2)
        temp(t(1, 10, 30), 21.5)
        temp(t(1, 12, 20), 22.4)
        temp(t(2, 8, 20), 18.6)
        temp(t(2, 10, 40), 21.3)
        drive(t(0, 9, 30), t(0, 10, 20), listOf(jejuHome, GeoPoint(33.4700, 126.3500), hyeopjae), "협재해변")
        door(t(0, 10, 21))
        drive(t(0, 11, 20), t(0, 11, 50), listOf(hyeopjae, osulloc), "오설록 티뮤지엄")
        door(t(0, 11, 51))
        drive(t(0, 12, 40), t(0, 13, 40), listOf(osulloc, GeoPoint(33.2600, 126.4200), seogwipoHotel), "서귀포 호텔")
        door(t(0, 13, 41))
        chapter("2일째", t(1, 9, 0))
        drive(t(1, 9, 30), t(1, 9, 40), listOf(seogwipoHotel, cheonjiyeon), "천지연폭포")
        door(t(1, 9, 41))
        drive(t(1, 10, 20), t(1, 10, 40), listOf(cheonjiyeon, soesokkak), "쇠소깍")
        door(t(1, 10, 41))
        drive(t(1, 11, 25), t(1, 12, 30), listOf(soesokkak, GeoPoint(33.3300, 126.8300), seongsanStay), "성산 숙소")
        door(t(1, 12, 31))
        chapter("3일째", t(2, 8, 0))
        drive(t(2, 8, 30), t(2, 8, 40), listOf(seongsanStay, ilchulbong), "성산일출봉")
        door(t(2, 8, 41))
        drive(t(2, 10, 10), t(2, 10, 50), listOf(ilchulbong, GeoPoint(33.5200, 126.8500), hamdeok), "함덕해변")
        door(t(2, 10, 51))
        drive(t(2, 11, 40), t(2, 12, 10), listOf(hamdeok, jejuHome), "집")
        add(t(2, 12, 12), ScenarioEvent.IgnitionOff)
        build()
    }

    /** 3. 출퇴근 5일: 여행 0개, 일상 이동 10건. 수요일 퇴근길에 주유 5분 */
    val commuteWeek: Scenario = ScenarioBuilder("출퇴근 5일", day(2026, 11, 2)).run {
        home(songpaHome)
        office(officeCityHall)
        val gasStation = GeoPoint(37.5300, 127.0400)
        val mid = GeoPoint(37.5400, 127.0500)
        startParked(t(0, 7, 50), songpaHome, "집")
        seats(t(0, 7, 55), SeatId.DRIVER)
        for (d in 0..4) {
            chapter("${d + 1}일째", t(d, 7, 50))
            temp(t(d, 7, 50), 3.0 + d * 0.8)
            temp(t(d, 18, 20), 8.4 + d * 0.5)
            drive(t(d, 8, 0), t(d, 8, 50), listOf(songpaHome, mid, officeCityHall), "회사")
            door(t(d, 8, 51))
            if (d == 2) {
                drive(t(d, 18, 30), t(d, 18, 55), listOf(officeCityHall, gasStation), "주유소")
                door(t(d, 18, 56))
                drive(t(d, 19, 0), t(d, 19, 25), listOf(gasStation, songpaHome), "집")
            } else {
                drive(t(d, 18, 30), t(d, 19, 20), listOf(officeCityHall, mid, songpaHome), "집")
            }
            door(t(d, 19, 30))
        }
        build()
    }

    // 경부고속도로
    private val giheung = GeoPoint(37.2750, 127.1100)
    val cheonanRest = GeoPoint(36.8200, 127.1700)
    private val daejeon = GeoPoint(36.3700, 127.4300)
    val chupungRest = GeoPoint(36.2200, 127.9900)
    private val daegu = GeoPoint(35.8900, 128.6000)
    val busanParents = GeoPoint(35.1580, 129.0600)

    /** 4. 장거리 귀성: 휴게소 15분은 방문지, 8분은 잠깐 정차 */
    val longDriveHome: Scenario = ScenarioBuilder("장거리 귀성", day(2026, 9, 24)).run {
        home(songpaHome)
        startParked(t(0, 7, 50), songpaHome, "집")
        seats(t(0, 7, 55), SeatId.DRIVER, SeatId.PASSENGER, SeatId.REAR_LEFT, SeatId.REAR_RIGHT)
        temp(t(0, 7, 50), 19.0)
        temp(t(0, 9, 0), 21.4)
        temp(t(0, 10, 50), 24.6)
        temp(t(0, 13, 20), 27.2)
        drive(t(0, 8, 0), t(0, 9, 10), listOf(songpaHome, giheung, cheonanRest), "천안삼거리휴게소")
        door(t(0, 9, 11))
        drive(t(0, 9, 25), t(0, 11, 0), listOf(cheonanRest, daejeon, chupungRest), "추풍령휴게소")
        door(t(0, 11, 1))
        drive(t(0, 11, 8), t(0, 13, 30), listOf(chupungRest, daegu, busanParents), "부모님 댁")
        door(t(0, 13, 31))
        add(t(0, 13, 35), ScenarioEvent.IgnitionOff)
        build()
    }

    val yangpyeongCafe = GeoPoint(37.4900, 127.4900)

    /** 5. 같은 장소 재정차: 주차장에서 4분 뒤 120m 옮겨 다시 주차 → 방문지 1개 */
    val reparkSamePlace: Scenario = ScenarioBuilder("같은 장소 재정차", day(2026, 11, 14)).run {
        home(songpaHome)
        val lot2 = GeoPoint(yangpyeongCafe.lat + 120.0 / 111_195.0, yangpyeongCafe.lon)
        startParked(t(0, 9, 50), songpaHome, "집")
        temp(t(0, 9, 50), 7.2)
        seats(t(0, 9, 55), SeatId.DRIVER, SeatId.PASSENGER)
        drive(t(0, 10, 0), t(0, 11, 0), listOf(songpaHome, hanam, yangpyeongCafe), "양평 카페")
        temp(t(0, 10, 50), 9.6)
        temp(t(0, 11, 30), 11.8)
        drive(t(0, 11, 4), t(0, 11, 6), listOf(yangpyeongCafe, lot2), sampleS = 5)
        door(t(0, 11, 7))
        drive(t(0, 11, 50), t(0, 12, 50), listOf(lot2, hanam, songpaHome), "집")
        add(t(0, 12, 52), ScenarioEvent.IgnitionOff)
        build()
    }

    val tunnelPath = listOf(songpaHome, hanam, GeoPoint(37.6000, 127.4500), GeoPoint(37.7500, 127.6500))
    val chuncheonLake = GeoPoint(37.8800, 127.7200)

    /** 6. 위치 신호 끊김: 출발 40km 지점부터 3km 구간(터널)에 위치 표본이 없다 */
    val tunnelGap: Scenario = ScenarioBuilder("위치 신호 끊김", day(2026, 11, 15)).run {
        home(songpaHome)
        startParked(t(0, 9, 50), songpaHome, "집")
        seats(t(0, 9, 55), SeatId.DRIVER)
        temp(t(0, 9, 50), 5.4)
        drive(t(0, 10, 0), t(0, 11, 10), tunnelPath + chuncheonLake, "의암호", sampleS = 2, blackoutM = 40_000.0..43_000.0)
        temp(t(0, 11, 0), 7.6)
        door(t(0, 11, 11))
        drive(t(0, 12, 0), t(0, 13, 10), (tunnelPath + chuncheonLake).reversed(), "집", sampleS = 2)
        add(t(0, 13, 12), ScenarioEvent.IgnitionOff)
        build()
    }

    // 제주 애월 해안도로
    private val ihoTewoo = GeoPoint(33.4980, 126.4530)
    val aewolCoast = GeoPoint(33.4620, 126.3110)

    /** 7. 집→집 해안도로 드라이브: 서지 않고 애월까지 갔다가 돌아온다. 가장 먼 점이 집에서 약 21km → 애매한 이동 */
    val coastalDrive: Scenario = ScenarioBuilder("집→집 해안도로 드라이브", day(2026, 11, 8)).run {
        home(jejuHome)
        startParked(t(0, 15, 50), jejuHome, "집")
        seats(t(0, 15, 55), SeatId.DRIVER, SeatId.PASSENGER)
        chapter("출발", t(0, 15, 50))
        drive(t(0, 16, 0), t(0, 17, 20), listOf(jejuHome, ihoTewoo, aewolCoast, ihoTewoo, jejuHome), "집")
        chapter("집 도착", t(0, 17, 20))
        door(t(0, 17, 21))
        add(t(0, 17, 23), ScenarioEvent.IgnitionOff)
        build()
    }

    val jamsilMart = GeoPoint(37.5110, 127.0980)

    /** 8. 퇴근길 단골 마트: 최근 30일 마트 방문 3번 + 회사→마트 20분→집 → 일상 이동 */
    val regularMart: Scenario = ScenarioBuilder("퇴근길 단골 마트", day(2026, 11, 12)).run {
        home(songpaHome)
        office(officeCityHall)
        for (d in listOf(-3, -10, -17)) priorVisit(t(d, 19, 10), t(d, 19, 35), jamsilMart)
        startParked(t(0, 18, 20), officeCityHall, "회사")
        seats(t(0, 18, 25), SeatId.DRIVER)
        temp(t(0, 18, 20), 9.3)
        chapter("퇴근", t(0, 18, 20))
        drive(t(0, 18, 30), t(0, 19, 10), listOf(officeCityHall, GeoPoint(37.5400, 127.0500), jamsilMart), "동네 마트")
        chapter("마트", t(0, 19, 10))
        door(t(0, 19, 11)); door(t(0, 19, 28))
        drive(t(0, 19, 30), t(0, 19, 40), listOf(jamsilMart, songpaHome), "집")
        chapter("집 도착", t(0, 19, 40))
        door(t(0, 19, 41))
        add(t(0, 19, 43), ScenarioEvent.IgnitionOff)
        build()
    }

    val sejongParents = GeoPoint(36.4800, 127.2890)

    /**
     * 9. 부모님 댁 편도, 1박 뒤 귀가: 집→세종 부모님 댁(약 120km) 12:10 도착.
     * 18:10(도착 6시간 뒤) 닫힘 → 오전 3시가 지나 숙박이 되어 다시 열림 → 다음 날 귀가로 여행 1건
     */
    val parentsOvernight: Scenario = ScenarioBuilder("부모님 댁 1박", day(2026, 11, 21)).run {
        home(songpaHome)
        office(officeCityHall)
        startParked(t(0, 9, 50), songpaHome, "집")
        seats(t(0, 9, 55), SeatId.DRIVER, SeatId.PASSENGER, SeatId.REAR_LEFT)
        temp(t(0, 9, 50), 3.8)
        chapter("출발", t(0, 9, 50))
        drive(t(0, 10, 0), t(0, 12, 10), listOf(songpaHome, giheung, cheonanRest, sejongParents), "부모님 댁")
        temp(t(0, 12, 0), 8.6)
        temp(t(1, 9, 0), -1.4)
        chapter("부모님 댁", t(0, 12, 10))
        door(t(0, 12, 11))
        add(t(0, 12, 15), ScenarioEvent.IgnitionOff)
        chapter("6시간 뒤", t(0, 18, 10))
        chapter("다음 날 아침", t(1, 9, 50))
        drive(t(1, 10, 0), t(1, 12, 10), listOf(sejongParents, cheonanRest, giheung, songpaHome), "집")
        chapter("집 도착", t(1, 12, 10))
        door(t(1, 12, 11))
        add(t(1, 12, 15), ScenarioEvent.IgnitionOff)
        build()
    }

    val all: List<Scenario> = listOf(
        gangneungDayTrip, jejuThreeDays, commuteWeek, longDriveHome, reparkSamePlace, tunnelGap,
        coastalDrive, regularMart, parentsOvernight,
    )
}
