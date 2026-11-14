package kr.triprecap.app.ports

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kr.triprecap.app.domain.Gear
import kr.triprecap.app.domain.GeoPoint
import kr.triprecap.app.domain.LocationSample
import kr.triprecap.app.domain.NamedPoint
import kr.triprecap.app.domain.SeatId

/**
 * 차량·위치·내비·시계는 모두 이 인터페이스 뒤에 둔다.
 * 구현은 두 가지다. `pleos/` 는 실제 Pleos SDK·AAOS, `scenario/` 는 시나리오 재생이다.
 */

interface VehicleSignals {
    val gear: StateFlow<Gear>
    /** 어느 문이든 하나라도 열려 있으면 true */
    val doorOpen: StateFlow<Boolean>
    val seatOccupancy: StateFlow<Set<SeatId>>
    val wiperOn: StateFlow<Boolean>
    /** 차속(km/h). 기어 신호가 없을 때만 드라이브 판정에 쓴다. 지원하지 않으면 null */
    val speedKmh: StateFlow<Double?>
    /** 외기온(섭씨). 지원하지 않거나 아직 받지 못했으면 null */
    val outsideTempC: StateFlow<Double?>
    /** 시동 OFF 가 될 때마다 한 번 */
    val ignitionOff: SharedFlow<Unit>
    /** 프로세스가 살아 있는 동안 시동이 다시 ON 이 될 때마다 한 번. 다음 시동 때 띄우는 도착 카드에 쓴다 */
    val ignitionOn: SharedFlow<Unit>
    fun start()
    fun stop()
}

interface LocationSource {
    /** 최신 위치. 아직 없으면 null. 주행 중 1초 간격을 목표로 한다 */
    val location: StateFlow<LocationSample?>
    fun start()
    fun stop()
}

data class Destination(val name: String, val point: GeoPoint?, val remainTimeS: Int)

interface NaviSource {
    /** 경로 안내 중인 목적지. 안내가 없으면 null */
    val destination: StateFlow<Destination?>
    /** 즐겨찾기 HOME 좌표. 등록되지 않았으면 null */
    val home: StateFlow<GeoPoint?>
    /** 즐겨찾기 OFFICE 좌표. 등록되지 않았으면 null */
    val office: StateFlow<GeoPoint?>
    /** 목적지에 도착할 때마다 한 번 (NaviHelper onDestinationArrived) */
    val arrivals: SharedFlow<Unit>
    fun start()
    fun stop()
    /** 목적지 1곳으로 경로 안내를 요청한다. 요청이 받아들여지면 true */
    suspend fun requestRoute(destination: NamedPoint): Boolean
    /** 경유지(최대 2개)를 거쳐 목적지로 가는 경로 안내를 요청한다 */
    suspend fun requestRouteWithWaypoints(waypoints: List<NamedPoint>, destination: NamedPoint): Boolean
}

/** 주행 중 화면 제한 (CarUxRestrictionsManager). 제한 중이면 true */
interface UxRestrictionSource {
    val restricted: StateFlow<Boolean>
    fun start()
    fun stop()
}

/** 여행 시각의 기준. 시나리오 재생기는 가속된 가상 시계를 준다. */
interface Clock {
    fun now(): Long
}

/** TTS(도착 안내 한 문장)와 STT(정차 중 음성 메모 1회 인식) */
interface VoiceIo {
    /** 문장을 읽고 재생이 끝나면 돌아온다 */
    suspend fun speak(text: String)
    /** 마이크를 열어 한 번 인식한다. 시간 안에 아무 말도 없으면 null */
    suspend fun listen(timeoutMs: Long): String?
}
