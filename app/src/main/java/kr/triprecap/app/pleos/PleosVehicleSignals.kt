package kr.triprecap.app.pleos

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import android.car.Car
import android.car.VehicleGear as AaosGear
import android.car.VehicleIgnitionState
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kr.triprecap.app.domain.Gear
import kr.triprecap.app.domain.SeatId
import kr.triprecap.app.ports.VehicleSignals
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * 차량 신호 어댑터. Pleos Vehicle SDK 2.3.2 와 AAOS `android.car` 를 함께 쓴다. 조회·구독만 하고 제어 API(set*)는 호출하지 않는다.
 *
 * Pleos Vehicle SDK (AAR javap 로 시그니처 확인)
 * - `Vehicle(context)`, `initialize()`, `release()`, `getGear()`, `getDoor()`, `getSeat()`, `getWiper()`
 * - 기어: `Gear.registerGearSelection(GearSelectionListener)` → `onSelected(VehicleGear?)`, `getCurrentGearSelectionState(onComplete, onFailed)`
 * - 도어: `Door.registerDoorPosition(DoorArea, DoorPositionListener)` → `onPositionUpdated(Int?)`,
 *   `getCurrentDoorPosition(DoorArea, onComplete, onFailed)`, `checkDoorPositionCapabilities(onComplete, onFailed)`
 *   대상은 탑승 도어 4개(ROW_1_LEFT, ROW_1_RIGHT, ROW_2_LEFT, ROW_2_RIGHT). 후드·트렁크(HOOD, REAR)는 방문 판정과 무관해서 뺐다.
 * - 좌석: `Seat.registerSeatOccupancy(SeatArea, SeatOccupancyListener)` → `onSeatOccupancyUpdated(Boolean?)`, `getCurrentSeatOccupancy(...)`
 *   ROW_1_LEFT=운전석, ROW_1_RIGHT=조수석, ROW_2_LEFT·ROW_2_RIGHT=뒷좌석 (좌측 운전석 기준)
 * - 와이퍼: `Wiper.registerWiperState(WindowArea(FRONT_WINDSHIELD), WiperStateListener)` → `onWiperStateUpdated(WiperState?)`. ON 일 때만 true
 * - 리스너는 영역 정보를 넘겨주지 않으므로 영역마다 리스너 객체를 하나씩 만든다.
 *
 * AAOS `CarPropertyManager.registerCallback(callback, propId, rate)` (Pleos Vehicle SDK 에 해당 API 가 없는 신호)
 * - 차속: `VehiclePropertyIds.PERF_VEHICLE_SPEED` (m/s, 1Hz) → 절댓값 × 3.6 = km/h
 * - 외기온: `VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE` (Float, 섭씨, 1Hz 요청). 등록이 실패하거나 차가 지원하지 않으면
 *   로그만 남기고 `outsideTempC` 는 null 로 남는다. 다른 신호에는 영향이 없다
 * - 시동: `VehiclePropertyIds.IGNITION_STATE`. ON·START 에서 그 밖의 상태(LOCK·OFF·ACC)로 바뀔 때 `ignitionOff` 를 한 번 낸다.
 *   그 밖의 상태에서 ON·START 로 바뀔 때 `ignitionOn` 을 한 번 낸다(첫 값은 프로세스 시작이므로 내지 않는다)
 * - 기어 대체 경로: Pleos 기어 구독·조회가 실패하면 `VehiclePropertyIds.GEAR_SELECTION` 을 구독한다
 *
 * 권한
 * - 매니페스트: `pleos.car.permission.CAR_DRIVE`(기어), `CAR_DOORS`, `CAR_SEATS`, `CAR_WINDOWS`(와이퍼) — AAR 의 @RequiresPermission 으로 확인
 * - 매니페스트: `android.car.permission.CAR_POWERTRAIN`(IGNITION_STATE, GEAR_SELECTION)
 * - 매니페스트: `android.car.permission.CAR_EXTERIOR_ENVIRONMENT`(ENV_OUTSIDE_TEMPERATURE)
 * - 런타임(dangerous): `android.car.permission.CAR_SPEED`(PERF_VEHICLE_SPEED). 허용 전에는 구독이 SecurityException 으로 실패하고
 *   `speedKmh` 는 null 로 남는다. Activity 에서 허용받은 뒤 `stop()` → `start()` 를 다시 불러야 구독된다.
 *
 * 미확인
 * - 도어 위치 값의 뜻. `checkDoorPositionCapabilities` 가 돌려주는 minValue(받지 못하면 0)보다 크면 열림으로 판정했다.
 * - `Vehicle.initialize()` 는 연결 완료 콜백이 없다. 서비스 연결 전에 등록한 리스너가 연결 뒤 자동으로 다시 등록되는지 확인하지 못해서,
 *   start 3초 뒤에 현재 값 조회(getCurrent*)를 한 번 더 한다.
 * - Pleos Connect 가 AAOS 표준 권한(CAR_SPEED, CAR_POWERTRAIN)을 3rd party 앱에 허용하는지. 문서(vehicle-app-faq)는
 *   PERF_VEHICLE_SPEED 를 CarPropertyManager 로 읽으라고만 적고 권한 이름은 적지 않았다.
 * - 신호 값이 바뀔 때 구독 콜백으로 들어오는지. 2026-11-10 Pleos Connect Emulator v2.1.0 에서 확인한 것은
 *   초기화(`Vehicle initialized`)와 첫 값 수신(기어 GEAR_PARK, 도어 4개 0, 좌석 4개, 와이퍼 OFF, AAOS 차속 0.0 m/s)까지다.
 *   기어·도어 값의 변화 수신과 시동(IGNITION_STATE) 값 수신은 확인하지 못했다 (docs/verification-2026-11-10.md).
 */
class PleosVehicleSignals(context: Context) : VehicleSignals {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    private val _gear = MutableStateFlow(Gear.UNKNOWN)
    override val gear: StateFlow<Gear> = _gear.asStateFlow()
    private val _doorOpen = MutableStateFlow(false)
    override val doorOpen: StateFlow<Boolean> = _doorOpen.asStateFlow()
    private val _seatOccupancy = MutableStateFlow<Set<SeatId>>(emptySet())
    override val seatOccupancy: StateFlow<Set<SeatId>> = _seatOccupancy.asStateFlow()
    private val _wiperOn = MutableStateFlow(false)
    override val wiperOn: StateFlow<Boolean> = _wiperOn.asStateFlow()
    private val _speedKmh = MutableStateFlow<Double?>(null)
    override val speedKmh: StateFlow<Double?> = _speedKmh.asStateFlow()
    private val _outsideTempC = MutableStateFlow<Double?>(null)
    override val outsideTempC: StateFlow<Double?> = _outsideTempC.asStateFlow()
    private val _ignitionOff = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val ignitionOff: SharedFlow<Unit> = _ignitionOff.asSharedFlow()
    private val _ignitionOn = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val ignitionOn: SharedFlow<Unit> = _ignitionOn.asSharedFlow()

    private val lock = Any()
    private var started = false
    private var vehicle: Vehicle? = null
    private var car: Car? = null
    private var propertyManager: CarPropertyManager? = null
    private var aaosGearRegistered = false

    /** 열려 있는 도어 집합. 비어 있지 않으면 doorOpen = true */
    private val openDoors = MutableStateFlow<Set<DoorValue>>(emptySet())
    /** 도어별 닫힘 위치(capability 의 minValue). 받지 못한 도어는 0 으로 본다 */
    @Volatile private var doorClosedPos: Map<DoorValue, Int> = emptyMap()
    private val lastDoorPos = ConcurrentHashMap<DoorValue, Int>()
    @Volatile private var ignitionWasOn: Boolean? = null
    /** 신호별 첫 값 수신 로그를 한 번만 남기기 위한 표시 */
    private val firstLogged = ConcurrentHashMap.newKeySet<String>()

    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) = onPleosGear(gear)
        override fun onFailed(e: Exception) {
            Log.w(TAG, "gear listener failed", e)
            registerAaosGear()
        }
    }

    private val doorListeners: Map<DoorValue, DoorPositionListener> = DOORS.associateWith { door ->
        object : DoorPositionListener {
            override fun onPositionUpdated(pos: Int?) = onDoorPosition(door, pos)
            override fun onFailed(e: Exception) { Log.w(TAG, "door $door listener failed", e) }
        }
    }

    private val seatListeners: Map<SeatValue, SeatOccupancyListener> = SEATS.keys.associateWith { seat ->
        object : SeatOccupancyListener {
            override fun onSeatOccupancyUpdated(occupancy: Boolean?) = onSeat(seat, occupancy)
            override fun onFailed(e: Exception) { Log.w(TAG, "seat $seat listener failed", e) }
        }
    }

    private val wiperListener = object : WiperStateListener {
        override fun onWiperStateUpdated(state: WiperState?) = onWiper(state)
        override fun onFailed(e: Exception) { Log.w(TAG, "wiper listener failed", e) }
    }

    private val propertyCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>?) {
            value ?: return
            runCatching {
                when (value.propertyId) {
                    VehiclePropertyIds.PERF_VEHICLE_SPEED -> (value.value as? Float)?.let { onSpeed(it) }
                    VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE -> (value.value as? Float)?.let { onOutsideTemp(it) }
                    VehiclePropertyIds.IGNITION_STATE -> (value.value as? Int)?.let { onIgnition(it) }
                    VehiclePropertyIds.GEAR_SELECTION -> (value.value as? Int)?.let { onAaosGear(it) }
                }
            }.onFailure { Log.w(TAG, "car property event failed: ${value.propertyId}", it) }
        }
        override fun onErrorEvent(propId: Int, zone: Int) {
            Log.w(TAG, "car property error: propId=$propId zone=$zone")
        }
    }

    private val reReadCurrent = Runnable {
        val v = synchronized(lock) { if (started) vehicle else null } ?: return@Runnable
        readCurrentValues(v)
    }

    override fun start() {
        synchronized(lock) {
            if (started) return
            started = true
            startPleos()
            startAaos()
        }
        handler.postDelayed(reReadCurrent, REREAD_DELAY_MS)
    }

    override fun stop() {
        handler.removeCallbacks(reReadCurrent)
        synchronized(lock) {
            if (!started) return
            started = false
            stopPleos()
            stopAaos()
        }
    }

    // ---- Pleos Vehicle SDK ----

    private fun startPleos() {
        val v = runCatching { Vehicle(appContext).also { it.initialize() } }
            .onFailure { Log.w(TAG, "Vehicle initialize failed", it) }
            .getOrNull() ?: run { registerAaosGear(); return }
        vehicle = v
        Log.i(TAG, "Vehicle initialized")

        runCatching { v.getGear().registerGearSelection(gearListener) }
            .onFailure { Log.w(TAG, "registerGearSelection failed", it); registerAaosGear() }
        runCatching {
            v.getDoor().checkDoorPositionCapabilities(
                { bounds ->
                    val closed = HashMap<DoorValue, Int>()
                    bounds.forEach { (area, b) -> area.values.forEach { door -> b.minValue?.let { closed[door] = it } } }
                    doorClosedPos = closed
                    Log.i(TAG, "door closed positions: $closed")
                    // 닫힘 기준이 바뀌었으므로 이미 받은 위치를 다시 판정한다
                    lastDoorPos.toMap().forEach { (door, pos) -> onDoorPosition(door, pos) }
                },
                { e -> Log.w(TAG, "checkDoorPositionCapabilities failed", e) },
            )
        }.onFailure { Log.w(TAG, "checkDoorPositionCapabilities failed", it) }
        doorListeners.forEach { (door, listener) ->
            runCatching { v.getDoor().registerDoorPosition(DoorArea(door), listener) }
                .onFailure { Log.w(TAG, "registerDoorPosition $door failed", it) }
        }
        seatListeners.forEach { (seat, listener) ->
            runCatching { v.getSeat().registerSeatOccupancy(SeatArea(seat), listener) }
                .onFailure { Log.w(TAG, "registerSeatOccupancy $seat failed", it) }
        }
        runCatching { v.getWiper().registerWiperState(WIPER_AREA, wiperListener) }
            .onFailure { Log.w(TAG, "registerWiperState failed", it) }
        readCurrentValues(v)
    }

    /** 구독은 값이 바뀔 때만 올 수 있으므로 현재 값을 따로 조회한다 */
    private fun readCurrentValues(v: Vehicle) {
        runCatching {
            v.getGear().getCurrentGearSelectionState(
                { g -> onPleosGear(g) },
                { e -> Log.w(TAG, "getCurrentGearSelectionState failed", e); registerAaosGear() },
            )
        }.onFailure { Log.w(TAG, "getCurrentGearSelectionState failed", it) }
        DOORS.forEach { door ->
            runCatching {
                v.getDoor().getCurrentDoorPosition(
                    DoorArea(door),
                    { pos -> onDoorPosition(door, pos) },
                    { e -> Log.w(TAG, "getCurrentDoorPosition $door failed", e) },
                )
            }.onFailure { Log.w(TAG, "getCurrentDoorPosition $door failed", it) }
        }
        SEATS.keys.forEach { seat ->
            runCatching {
                v.getSeat().getCurrentSeatOccupancy(
                    SeatArea(seat),
                    { occupied -> onSeat(seat, occupied) },
                    { e -> Log.w(TAG, "getCurrentSeatOccupancy $seat failed", e) },
                )
            }.onFailure { Log.w(TAG, "getCurrentSeatOccupancy $seat failed", it) }
        }
        runCatching {
            v.getWiper().getCurrentWiperState(
                WIPER_AREA,
                { state -> onWiper(state) },
                { e -> Log.w(TAG, "getCurrentWiperState failed", e) },
            )
        }.onFailure { Log.w(TAG, "getCurrentWiperState failed", it) }
    }

    private fun stopPleos() {
        val v = vehicle ?: return
        vehicle = null
        runCatching { v.getGear().unregisterGearSelection(gearListener) }
            .onFailure { Log.w(TAG, "unregisterGearSelection failed", it) }
        doorListeners.forEach { (door, listener) ->
            runCatching { v.getDoor().unregisterDoorPosition(DoorArea(door), listener) }
                .onFailure { Log.w(TAG, "unregisterDoorPosition $door failed", it) }
        }
        seatListeners.forEach { (seat, listener) ->
            runCatching { v.getSeat().unregisterSeatOccupancy(SeatArea(seat), listener) }
                .onFailure { Log.w(TAG, "unregisterSeatOccupancy $seat failed", it) }
        }
        runCatching { v.getWiper().unregisterWiperState(WIPER_AREA, wiperListener) }
            .onFailure { Log.w(TAG, "unregisterWiperState failed", it) }
        runCatching { v.release() }.onFailure { Log.w(TAG, "Vehicle release failed", it) }
    }

    // ---- AAOS android.car ----

    private fun startAaos() {
        val c = runCatching { Car.createCar(appContext) }
            .onFailure { Log.w(TAG, "Car.createCar failed", it) }
            .getOrNull() ?: return
        car = c
        val pm = runCatching { c.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager }
            .onFailure { Log.w(TAG, "CarPropertyManager unavailable", it) }
            .getOrNull() ?: return
        propertyManager = pm
        Log.i(TAG, "CarPropertyManager connected")
        registerProperty(pm, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL, "PERF_VEHICLE_SPEED")
        registerProperty(pm, VehiclePropertyIds.IGNITION_STATE, CarPropertyManager.SENSOR_RATE_ONCHANGE, "IGNITION_STATE")
        registerProperty(pm, VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE, CarPropertyManager.SENSOR_RATE_NORMAL, "ENV_OUTSIDE_TEMPERATURE")
    }

    private fun registerProperty(pm: CarPropertyManager, propId: Int, rate: Float, name: String): Boolean =
        runCatching { pm.registerCallback(propertyCallback, propId, rate) }
            .onFailure { Log.w(TAG, "registerCallback $name failed", it) }
            .getOrDefault(false)
            .also { ok -> if (ok) Log.i(TAG, "registerCallback $name ok") else Log.w(TAG, "registerCallback $name not registered") }

    /** Pleos 기어 신호를 쓸 수 없을 때만 AAOS GEAR_SELECTION 을 구독한다 */
    private fun registerAaosGear() {
        synchronized(lock) {
            if (!started || aaosGearRegistered) return
            val pm = propertyManager ?: return
            aaosGearRegistered = registerProperty(pm, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE, "GEAR_SELECTION")
        }
    }

    private fun stopAaos() {
        propertyManager?.let { pm ->
            runCatching { pm.unregisterCallback(propertyCallback) }
                .onFailure { Log.w(TAG, "unregisterCallback failed", it) }
        }
        propertyManager = null
        aaosGearRegistered = false
        car?.let { c -> runCatching { c.disconnect() }.onFailure { Log.w(TAG, "Car.disconnect failed", it) } }
        car = null
    }

    // ---- 값 반영 ----

    private fun logFirst(key: String, value: Any?) {
        if (firstLogged.add(key)) Log.i(TAG, "first value: $key=$value")
    }

    private fun onPleosGear(g: VehicleGear?) {
        g ?: return
        logFirst("gear(pleos)", g)
        _gear.value = when (g) {
            VehicleGear.GEAR_PARK -> Gear.PARK
            VehicleGear.GEAR_REVERSE -> Gear.REVERSE
            VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
            VehicleGear.GEAR_UNKNOWN -> Gear.UNKNOWN
            // GEAR_DRIVE 와 수동 단수(GEAR_1~9)는 전진으로 본다
            else -> Gear.DRIVE
        }
    }

    private fun onAaosGear(g: Int) {
        logFirst("gear(aaos)", g)
        _gear.value = when (g) {
            AaosGear.GEAR_PARK -> Gear.PARK
            AaosGear.GEAR_REVERSE -> Gear.REVERSE
            AaosGear.GEAR_NEUTRAL -> Gear.NEUTRAL
            AaosGear.GEAR_UNKNOWN -> Gear.UNKNOWN
            else -> Gear.DRIVE
        }
    }

    private fun onDoorPosition(door: DoorValue, pos: Int?) {
        pos ?: return
        logFirst("door $door", pos)
        lastDoorPos[door] = pos
        val open = pos > (doorClosedPos[door] ?: 0)
        val next = openDoors.updateAndGetCompat { if (open) it + door else it - door }
        _doorOpen.value = next.isNotEmpty()
    }

    private fun onSeat(seat: SeatValue, occupied: Boolean?) {
        occupied ?: return
        val id = SEATS[seat] ?: return
        logFirst("seat $seat", occupied)
        _seatOccupancy.update { if (occupied) it + id else it - id }
    }

    private fun onWiper(state: WiperState?) {
        state ?: return
        logFirst("wiper", state)
        _wiperOn.value = state == WiperState.ON
    }

    private fun onSpeed(metersPerSecond: Float) {
        logFirst("speed(m/s)", metersPerSecond)
        _speedKmh.value = abs(metersPerSecond).toDouble() * 3.6
    }

    private fun onOutsideTemp(celsius: Float) {
        logFirst("outside temp(C)", celsius)
        _outsideTempC.value = celsius.toDouble()
    }

    private fun onIgnition(state: Int) {
        logFirst("ignition", state)
        if (state == VehicleIgnitionState.UNDEFINED) return
        val on = state == VehicleIgnitionState.ON || state == VehicleIgnitionState.START
        val wasOn = ignitionWasOn
        ignitionWasOn = on
        if (wasOn == true && !on) {
            Log.i(TAG, "ignition off (state=$state)")
            _ignitionOff.tryEmit(Unit)
        }
        if (wasOn == false && on) {
            Log.i(TAG, "ignition on (state=$state)")
            _ignitionOn.tryEmit(Unit)
        }
    }

    private inline fun <T> MutableStateFlow<T>.updateAndGetCompat(transform: (T) -> T): T {
        while (true) {
            val prev = value
            val next = transform(prev)
            if (compareAndSet(prev, next)) return next
        }
    }

    companion object {
        private const val TAG = "TripRecapPleos"
        private const val REREAD_DELAY_MS = 3_000L
        private val DOORS = listOf(DoorValue.ROW_1_LEFT, DoorValue.ROW_1_RIGHT, DoorValue.ROW_2_LEFT, DoorValue.ROW_2_RIGHT)
        private val SEATS = mapOf(
            SeatValue.ROW_1_LEFT to SeatId.DRIVER,
            SeatValue.ROW_1_RIGHT to SeatId.PASSENGER,
            SeatValue.ROW_2_LEFT to SeatId.REAR_LEFT,
            SeatValue.ROW_2_RIGHT to SeatId.REAR_RIGHT,
        )
        private val WIPER_AREA = WindowArea(WindowValue.FRONT_WINDSHIELD)
    }
}
