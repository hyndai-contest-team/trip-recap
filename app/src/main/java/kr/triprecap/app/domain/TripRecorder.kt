package kr.triprecap.app.domain

/**
 * 차량 신호를 받아 드라이브와 정차(Stay)를 만든다. Android 의존성이 없는 순수 Kotlin 이다.
 *
 * 규칙 (docs/product-plan.md 2-1)
 * - 드라이브 시작: 기어 P → D/R
 * - 드라이브 종료: P 유지 3분 또는 시동 OFF. 종료 시각은 P 단이 된 시각이다
 * - 기어 신호가 한 번도 없으면: 시속 5km 이상 10초 유지로 시작, 정지 3분으로 종료
 * - 경로: 직전 저장 점에서 20m 이상 떨어진 표본만 저장한다
 * - 정차(Stay): 드라이브 종료부터 다음 드라이브 시작까지. 방문지·잠깐 정차·숙박 구분은 StopClassifier 가 한다
 * - 외기온: 차가 서기 시작한 시각(P 단 또는 정지)에 알던 가장 최근 값을 정차와 그 앞 드라이브에 붙인다.
 *   그때 값이 없었으면 서 있는 동안 처음 받은 값을 쓴다. 정수(반올림) 섭씨로 저장한다
 */
class TripRecorder(
    private val endAfterParkedMs: Long = END_AFTER_PARKED_MS,
    private val trackSpacingM: Double = TRACK_SPACING_M,
) {

    sealed interface Event {
        data class GearChanged(val gear: Gear) : Event
        data class DoorChanged(val open: Boolean) : Event
        data class SeatsChanged(val seats: Set<SeatId>) : Event
        data class WiperChanged(val on: Boolean) : Event
        data class LocationChanged(val sample: LocationSample) : Event
        data class SpeedChanged(val kmh: Double) : Event
        /** 외기온(섭씨). AAOS ENV_OUTSIDE_TEMPERATURE, 지원하는 차에서만 온다 */
        data class OutsideTempChanged(val celsius: Double) : Event
        data object IgnitionOff : Event
    }

    sealed interface Output {
        data class DriveEnded(val drive: Drive) : Output
        data class StayEnded(val stay: Stay) : Output
    }

    var gear: Gear = Gear.UNKNOWN
        private set
    var seats: Set<SeatId> = emptySet()
        private set
    var lastLocation: LocationSample? = null
        private set

    /** 드라이브가 진행 중인지 (P 단으로 잠깐 선 동안도 3분이 지나기 전에는 true) */
    val isDriving: Boolean get() = drive != null
    val driveStartedAt: Long? get() = drive?.startedAt
    /** 차가 지금 서 있기 시작한 시각 (P 단 또는 정지). 움직이는 중이면 null */
    var stoppedAt: Long? = null
        private set
    /** 기록을 시작한 뒤 달린 거리 합계(m). 진행 중인 드라이브 포함 */
    var totalDistanceM: Double = 0.0
        private set
    val currentDriveDistanceM: Double get() = drive?.distanceM ?: 0.0
    /**
     * 이 기록기가 첫 출발 전에 처음 받은 외기온(반올림). 프로세스 재시작 뒤 저장된 드라이브에 외기온이 없을 때
     * 재시작 전부터 이어진 정차의 "서 있는 동안 처음 받은 값"으로 쓴다 (TripPipeline.stayAcrossRestart)
     */
    var tempBeforeFirstDrive: Int? = null
        private set

    private class DriveInProgress(val startedAt: Long) {
        val track = mutableListOf<TrackPoint>()
        var distanceM = 0.0
        var rainy = false
        val seats = mutableSetOf<SeatId>()
    }

    private class StayInProgress(
        val arrivedAt: Long,
        var location: LocationSample?,
        var doorOpened: Boolean,
        val rainy: Boolean,
        var outsideTempC: Int?,
    )

    private var drive: DriveInProgress? = null
    private var stay: StayInProgress? = null
    private var wiperOn = false
    private var gearSeen = false
    /** 서 있는 동안(드라이브 종료 전 포함) 도어가 열렸는지, 그때의 위치 */
    private var doorOpenedWhileStopped = false
    private var stoppedLocation: LocationSample? = null
    /** 가장 최근에 받은 외기온(반올림) */
    private var latestTempC: Int? = null
    /** 지금 서 있는 구간의 외기온: 서기 시작할 때의 [latestTempC], 없었으면 서 있는 동안 처음 받은 값 */
    private var stoppedTempC: Int? = null
    private var departedOnce = false
    /** 기어 신호가 없을 때: 시속 5km 를 넘기 시작한 시각 */
    private var fastSince: Long? = null

    fun onEvent(event: Event, now: Long): List<Output> {
        val out = mutableListOf<Output>()
        endDriveIfParkedLongEnough(now, out)
        when (event) {
            is Event.GearChanged -> onGear(event.gear, now, out)
            is Event.DoorChanged -> if (event.open && stoppedAt != null) {
                doorOpenedWhileStopped = true
                stay?.doorOpened = true
            }
            is Event.SeatsChanged -> {
                seats = event.seats
                drive?.seats?.addAll(event.seats)
            }
            is Event.WiperChanged -> {
                wiperOn = event.on
                if (event.on) drive?.rainy = true
            }
            is Event.LocationChanged -> onLocation(event.sample, now)
            is Event.SpeedChanged -> if (!gearSeen) onSpeed(event.kmh, now, out)
            is Event.OutsideTempChanged -> onOutsideTemp(event.celsius)
            Event.IgnitionOff -> endDrive(stoppedAt ?: now, out)
        }
        return out
    }

    /** 이벤트가 없어도 시간이 지난 것을 알린다 (P 유지 3분 판정) */
    fun tick(now: Long): List<Output> {
        val out = mutableListOf<Output>()
        endDriveIfParkedLongEnough(now, out)
        return out
    }

    /** 진행 중인 드라이브와 정차를 버린다 (전체 삭제). 기어·위치 상태는 그대로 둔다 */
    fun discardInProgress() {
        drive = null
        stay = null
    }

    /** 드라이브가 끝난 뒤 지금까지 서 있는 구간. 드라이브 중이거나 기록 전이면 null */
    fun currentStay(now: Long): Stay? = stay?.toStay(now)

    private fun StayInProgress.toStay(departedAt: Long) = Stay(
        arrivedAt = arrivedAt,
        departedAt = departedAt,
        point = location?.point,
        doorOpened = doorOpened,
        placeName = location?.placeName,
        rainy = rainy,
        outsideTempC = outsideTempC,
    )

    private fun onOutsideTemp(celsius: Double) {
        val c = Math.round(celsius).toInt()
        latestTempC = c
        if (!departedOnce && tempBeforeFirstDrive == null) tempBeforeFirstDrive = c
        if (stoppedAt != null && stoppedTempC == null) stoppedTempC = c
        stay?.let { if (it.outsideTempC == null) it.outsideTempC = c }
    }

    private fun onGear(newGear: Gear, now: Long, out: MutableList<Output>) {
        // UNKNOWN 은 "아직 값을 받지 못함"이다. 기어 신호가 있는 것으로 치지 않는다
        if (newGear == Gear.UNKNOWN) return
        gearSeen = true
        val old = gear
        gear = newGear
        if (newGear == old) return
        when (newGear) {
            Gear.PARK -> markStopped(now)
            Gear.DRIVE, Gear.REVERSE -> depart(now, out)
            else -> Unit
        }
    }

    private fun onSpeed(kmh: Double, now: Long, out: MutableList<Output>) {
        if (kmh >= START_SPEED_KMH) {
            val since = fastSince ?: now.also { fastSince = it }
            if (now - since >= START_SPEED_HOLD_MS && (drive == null || stoppedAt != null)) depart(since, out)
        } else {
            fastSince = null
            if (kmh < STILL_SPEED_KMH && drive != null && stoppedAt == null) markStopped(now)
        }
    }

    private fun markStopped(now: Long) {
        if (stoppedAt != null) return
        stoppedAt = now
        stoppedLocation = lastLocation
        stoppedTempC = latestTempC
        doorOpenedWhileStopped = false
    }

    private fun depart(now: Long, out: MutableList<Output>) {
        departedOnce = true
        stoppedAt = null
        stay?.let { out += Output.StayEnded(it.toStay(now)) }
        stay = null
        if (drive == null) {
            drive = DriveInProgress(now).also { d ->
                d.seats.addAll(seats)
                d.rainy = wiperOn
                lastLocation?.let { d.track += TrackPoint(now, it.point) }
            }
        }
    }

    private fun endDriveIfParkedLongEnough(now: Long, out: MutableList<Output>) {
        val since = stoppedAt ?: return
        if (drive != null && now - since >= endAfterParkedMs) endDrive(since, out)
    }

    private fun endDrive(endedAt: Long, out: MutableList<Output>) {
        val d = drive ?: return
        drive = null
        if (stoppedAt == null) {
            stoppedAt = endedAt
            stoppedLocation = lastLocation
            stoppedTempC = latestTempC
        }
        out += Output.DriveEnded(
            Drive(
                startedAt = d.startedAt, endedAt = endedAt, track = d.track.toList(), distanceM = d.distanceM, rainy = d.rainy, seats = d.seats.toSet(),
                arrivalTempC = stoppedTempC,
            ),
        )
        stay = StayInProgress(endedAt, stoppedLocation, doorOpenedWhileStopped, d.rainy, stoppedTempC)
    }

    private fun onLocation(sample: LocationSample, now: Long) {
        lastLocation = sample
        if (stoppedAt != null) {
            // 위치가 정차 뒤에 늦게 오거나, 장소 이름이 나중에 붙는 경우를 받아들인다
            val known = stoppedLocation
            if (known == null || (known.placeName == null && sample.placeName != null && known.point.distanceM(sample.point) <= trackSpacingM)) {
                stoppedLocation = sample
                stay?.location = sample
            }
            return
        }
        val d = drive ?: return
        val last = d.track.lastOrNull()
        if (last == null) {
            d.track += TrackPoint(now, sample.point)
            return
        }
        val step = last.point.distanceM(sample.point)
        if (step >= trackSpacingM) {
            d.track += TrackPoint(now, sample.point)
            d.distanceM += step
            totalDistanceM += step
        }
    }

    companion object {
        const val END_AFTER_PARKED_MS = 3 * 60_000L
        const val TRACK_SPACING_M = 20.0
        const val START_SPEED_KMH = 5.0
        const val START_SPEED_HOLD_MS = 10_000L
        const val STILL_SPEED_KMH = 1.0
    }
}
