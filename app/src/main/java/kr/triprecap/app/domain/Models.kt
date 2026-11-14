package kr.triprecap.app.domain

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 좌석. Pleos Vehicle SDK 의 SeatArea(운전석·동승석·뒷좌석 좌우)와 1:1 로 맞춘다. */
enum class SeatId(val label: String) {
    DRIVER("운전석"),
    PASSENGER("조수석"),
    REAR_LEFT("뒷좌석 왼쪽"),
    REAR_RIGHT("뒷좌석 오른쪽"),
}

enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

data class GeoPoint(val lat: Double, val lon: Double) {
    /** 두 지점 사이 거리(m). 구면 거리 공식. */
    fun distanceM(other: GeoPoint): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(other.lat - lat)
        val dLon = Math.toRadians(other.lon - lon)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat)) * cos(Math.toRadians(other.lat)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }
}

/** 위치 표본. placeName 은 시나리오가 준 장소 이름이다. 실제 주행에서는 null 이고, 정차 이름은 PlaceNameResolver 가 나중에 채운다. */
data class LocationSample(val point: GeoPoint, val placeName: String? = null)

/** 저장하는 경로 점 */
data class TrackPoint(val t: Long, val point: GeoPoint)

/** 드라이브 1회: 출발(P→D/R)부터 종료(P 유지 3분 또는 시동 OFF)까지 */
data class Drive(
    val startedAt: Long,
    val endedAt: Long,
    /** 20m 간격으로 줄인 경로 */
    val track: List<TrackPoint> = emptyList(),
    val distanceM: Double = 0.0,
    /** 주행 중 와이퍼가 켜진 적이 있으면 true */
    val rainy: Boolean = false,
    /** 주행 중 탑승이 감지된 좌석 전체 */
    val seats: Set<SeatId> = emptySet(),
    /**
     * 도착(P 단) 때 알던 가장 최근 외기온(섭씨, 반올림). 그때 값이 없었으면 드라이브가 끝나기 전에 처음 받은 값.
     * 시동을 꺼서 프로세스가 끝나면 진행 중인 정차가 사라지므로, 다시 켠 뒤 그 정차의 외기온을 이 값에서 가져온다
     */
    val arrivalTempC: Int? = null,
) {
    val startPoint: GeoPoint? get() = track.firstOrNull()?.point
    val endPoint: GeoPoint? get() = track.lastOrNull()?.point
}

/** 드라이브가 끝난 뒤 다음 드라이브가 시작될 때까지 서 있던 구간 */
data class Stay(
    val arrivedAt: Long,
    val departedAt: Long,
    val point: GeoPoint?,
    /** 서 있는 동안 도어가 한 번이라도 열렸는지 */
    val doorOpened: Boolean,
    val placeName: String? = null,
    /** 이 정차로 오는 드라이브에서 비가 왔는지 */
    val rainy: Boolean = false,
    /** 도착 때 알던 가장 최근 외기온(섭씨, 반올림). 그때 값이 없었으면 서 있는 동안 처음 받은 값. 받지 못했으면 null */
    val outsideTempC: Int? = null,
) {
    val durationMs: Long get() = departedAt - arrivedAt
}

enum class StopKind {
    /** 방문지: P단 + 도어 열림 + 10분 이상 */
    VISIT,
    /** 잠깐 정차: 방문지 조건에 못 미치는 정차 (휴게소·주유 등) */
    BRIEF,
    /** 숙박: 6시간 이상 + 야간 */
    LODGING,
}

data class Stop(
    val id: Int,
    val kind: StopKind,
    val name: String?,
    val point: GeoPoint?,
    val arrivedAt: Long,
    val departedAt: Long,
    val rainy: Boolean = false,
    val doorOpened: Boolean = true,
    val memo: String? = null,
    /** 시·군·구 (예: "강릉시"). 장소 이름 조회 때 함께 채운다 */
    val region: String? = null,
    /** 장소 이름·시군구 조회를 끝냈는지. false 면 온라인일 때 다시 조회한다 */
    val placeResolved: Boolean = false,
    /** 공유 데이터에서 뺄지 (사용자 선택) */
    val excludedFromShare: Boolean = false,
    /** 외기온(섭씨). [Stay.outsideTempC] 와 같다. 지원하지 않는 차이거나 받지 못했으면 null */
    val outsideTempC: Int? = null,
) {
    /** 체류 시간(분). 초 단위는 반올림한다 */
    val stayMinutes: Long get() = Math.round((departedAt - arrivedAt) / 60_000.0)
}

/** 닫힌 이동의 분류. 판정 결과(Verdict)와 사용자 답으로 정한다 (docs/product-plan.md 2-1, 2-3) */
enum class TripCategory {
    /** 여행. 목록에 보인다 */
    TRIP,
    /** 일상 이동. 목록에 보이지 않고 7일 뒤 지운다 */
    DAILY,
    /** 애매한 이동. 도착 카드에서 여행으로 남길지 묻는 중이다 */
    PENDING,
}

/** 여행 1회 = 여러 드라이브 + 그 사이 정차 */
data class Trip(
    val id: Long = 0,
    val drives: List<Drive>,
    val stops: List<Stop> = emptyList(),
    /** 사용자가 고친 제목. null 이면 autoTitle 을 쓴다 */
    val customTitle: String? = null,
    val closed: Boolean = false,
    /**
     * 사용자가 이 여행을 열어 봤는지. 새로 닫힌 여행은 false 다.
     * 도착 카드가 뜨지 않는 경우(내비에 집 미등록, 집이 아닌 곳에서 끝남)에도 목록에서 새 여행을 알아볼 수 있게 둔다
     */
    val seen: Boolean = false,
    /** 여행 / 일상 이동 / 묻는 중. 기본값은 여행이다 */
    val category: TripCategory = TripCategory.TRIP,
    /**
     * 집 300m 안 도착이 아니라, 집이 아닌 곳에 오래 서 있어서 닫혔는지 (docs/product-plan.md 2-1 닫는 시점 ②③).
     * true 면 사용자가 차에 없을 때 닫힌 것이므로 도착 카드는 다음 시동 때 띄운다(2-3).
     * 집 도착으로 닫힌 이동과 열린 이동은 false 다. 다시 열면 false 로 돌아간다
     */
    val endedAway: Boolean = false,
) {
    val startedAt: Long get() = drives.first().startedAt
    val endedAt: Long get() = maxOf(drives.last().endedAt, stops.lastOrNull()?.departedAt ?: 0L)
    val distanceM: Double get() = drives.sumOf { it.distanceM }
    /** 방문지(잠깐 정차·숙박 제외) */
    val visits: List<Stop> get() = stops.filter { it.kind == StopKind.VISIT }
    val rainy: Boolean get() = drives.any { it.rainy }
    /** 한 드라이브에서 동시에 탄 인원의 최댓값 */
    val peopleCount: Int get() = drives.maxOfOrNull { it.seats.size } ?: 0
    val startPoint: GeoPoint? get() = drives.firstNotNullOfOrNull { it.startPoint }
    val endPoint: GeoPoint? get() = drives.asReversed().firstNotNullOfOrNull { it.endPoint }
    /** 숙박 정차 수. 날짜 차이로 세면 자정을 넘긴 당일 여행이 1박이 되므로 숙박으로 센다 */
    val nights: Int get() = stops.count { it.kind == StopKind.LODGING }
    /** 닫혔지만 사용자가 아직 열어 보지 않은 여행. 기록 중인 여행은 새 여행이 아니다 */
    val isNew: Boolean get() = closed && !seen

    /** 화면에 쓰는 정차 이름. 이름이 아직 없으면 방문지는 "방문지 n"(여행 안 방문 순서) */
    fun nameOf(stop: Stop): String = stop.name ?: when (stop.kind) {
        StopKind.VISIT -> "방문지 ${visits.indexOfFirst { it.id == stop.id } + 1}"
        StopKind.BRIEF -> "잠깐 정차"
        StopKind.LODGING -> "숙소"
    }

    /** "첫 방문지 → 마지막 방문지". 방문지가 1곳이면 그 이름, 없으면 "드라이브" */
    val autoTitle: String
        get() {
            val v = visits
            return when {
                v.isEmpty() -> "드라이브"
                v.size == 1 || nameOf(v.first()) == nameOf(v.last()) -> nameOf(v.first())
                else -> "${nameOf(v.first())} → ${nameOf(v.last())}"
            }
        }
    val title: String get() = customTitle ?: autoTitle
}

/** 기록에 쓰는 집·회사 좌표. 출처(내비 즐겨찾기·직접 정함·추정)와 상관없이 같은 값으로 쓴다 */
data class KnownPlaces(val home: GeoPoint? = null, val office: GeoPoint? = null) {
    fun isHome(p: GeoPoint?, radiusM: Double = RADIUS_M): Boolean =
        p != null && home != null && p.distanceM(home) <= radiusM

    fun isHomeOrOffice(p: GeoPoint?, radiusM: Double = RADIUS_M): Boolean =
        isHome(p, radiusM) || (p != null && office != null && p.distanceM(office) <= radiusM)

    companion object {
        const val RADIUS_M = 300.0
    }
}
