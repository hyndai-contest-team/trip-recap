package kr.triprecap.app.scenario

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kr.triprecap.app.domain.Gear
import kr.triprecap.app.domain.GeoPoint
import kr.triprecap.app.domain.LocationSample
import kr.triprecap.app.domain.NamedPoint
import kr.triprecap.app.domain.SeatId
import kr.triprecap.app.ports.Clock
import kr.triprecap.app.ports.Destination
import kr.triprecap.app.ports.LocationSource
import kr.triprecap.app.ports.NaviSource
import kr.triprecap.app.ports.VehicleSignals

/**
 * 시나리오를 가속된 가상 시계로 재생한다. VehicleSignals·LocationSource·NaviSource·Clock 을 모두 구현한다.
 *
 * - speed: 가상 시간 / 실제 시간. 60 이면 실제 1초에 가상 1분이 지난다.
 * - 이벤트는 [sink] 로 하나씩, 그 이벤트의 시나리오 시각과 함께 넘긴다. 기록은 이 경로로만 한다.
 *   StateFlow 값은 화면 표시용이다 (한 번에 여러 이벤트를 적용하면 StateFlow 는 중간 값을 합치므로 기록에 쓰지 않는다).
 * - jumpToChapter(i): 그 챕터 시작 시각 전의 이벤트를 모두 순서대로 적용한 뒤 그 시각에서 재생을 잇는다.
 */
class ScenarioPlayer(
    private val scope: CoroutineScope,
    initialSpeed: Double = 60.0,
    private val realNow: () -> Long = System::currentTimeMillis,
) : VehicleSignals, LocationSource, NaviSource, Clock {

    /** 이벤트 1개와 그 시각(epoch ms) */
    var sink: (suspend (ScenarioEvent, Long) -> Unit)? = null
    /** 재생 tick 마다 현재 가상 시각(epoch ms) */
    var onTick: (suspend (Long) -> Unit)? = null

    private val _scenario = MutableStateFlow<Scenario?>(null)
    val scenario: StateFlow<Scenario?> = _scenario.asStateFlow()

    private val _gear = MutableStateFlow(Gear.UNKNOWN)
    override val gear: StateFlow<Gear> = _gear.asStateFlow()
    private val _door = MutableStateFlow(false)
    override val doorOpen: StateFlow<Boolean> = _door.asStateFlow()
    private val _seats = MutableStateFlow<Set<SeatId>>(emptySet())
    override val seatOccupancy: StateFlow<Set<SeatId>> = _seats.asStateFlow()
    private val _wiper = MutableStateFlow(false)
    override val wiperOn: StateFlow<Boolean> = _wiper.asStateFlow()
    /** 시나리오는 기어 신호를 주므로 차속은 쓰지 않는다 */
    override val speedKmh: StateFlow<Double?> = MutableStateFlow(null)
    private val _outsideTemp = MutableStateFlow<Double?>(null)
    override val outsideTempC: StateFlow<Double?> = _outsideTemp.asStateFlow()
    private val _ignitionOff = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    override val ignitionOff: SharedFlow<Unit> = _ignitionOff.asSharedFlow()
    /** 시나리오에는 시동 ON 이벤트가 없다. 시연 조작([simulateIgnitionOn])으로만 낸다 */
    private val _ignitionOn = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    override val ignitionOn: SharedFlow<Unit> = _ignitionOn.asSharedFlow()
    private val _location = MutableStateFlow<LocationSample?>(null)
    override val location: StateFlow<LocationSample?> = _location.asStateFlow()
    private val _destination = MutableStateFlow<Destination?>(null)
    override val destination: StateFlow<Destination?> = _destination.asStateFlow()
    private val _home = MutableStateFlow<GeoPoint?>(null)
    override val home: StateFlow<GeoPoint?> = _home.asStateFlow()
    private val _office = MutableStateFlow<GeoPoint?>(null)
    override val office: StateFlow<GeoPoint?> = _office.asStateFlow()
    private val _arrivals = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    override val arrivals: SharedFlow<Unit> = _arrivals.asSharedFlow()

    /** 시연용: 마지막으로 받은 경로 요청 (내비가 없으므로 화면에 글자로 보여 준다) */
    private val _lastRouteRequest = MutableStateFlow<String?>(null)
    val lastRouteRequest: StateFlow<String?> = _lastRouteRequest.asStateFlow()

    private val _speed = MutableStateFlow(initialSpeed)
    val speed: StateFlow<Double> = _speed.asStateFlow()
    private val _paused = MutableStateFlow(true)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()
    private val _chapterIndex = MutableStateFlow(0)
    val chapterIndex: StateFlow<Int> = _chapterIndex.asStateFlow()
    private val _finished = MutableStateFlow(false)
    val finished: StateFlow<Boolean> = _finished.asStateFlow()

    private var anchorElapsed = 0L
    private var anchorReal = realNow()
    private var nextStep = 0
    private var job: Job? = null
    private val mutex = Mutex()

    override fun now(): Long = (_scenario.value?.epochAtZero ?: realNow()) + elapsed()

    private fun elapsed(): Long =
        if (_paused.value) anchorElapsed
        else anchorElapsed + ((realNow() - anchorReal) * _speed.value).toLong()

    /** 시나리오를 처음 상태로 올린다. 재생은 resume 으로 시작한다 */
    fun load(scenario: Scenario) {
        _scenario.value = scenario
        resetState()
        nextStep = 0
        // HOME·OFFICE 등록(0시)은 건너뛰고 첫 차량 이벤트 시각에서 시작한다. 건너뛴 이벤트는 첫 step 에서 적용된다
        anchorElapsed = scenario.steps.firstOrNull { it.event !is ScenarioEvent.Home && it.event !is ScenarioEvent.Office }?.atMs
            ?: scenario.steps.firstOrNull()?.atMs ?: 0L
        anchorReal = realNow()
        _paused.value = true
        _finished.value = false
        _chapterIndex.value = 0
    }

    /** tick 반복을 시작한다 (재생 여부는 pause/resume 이 정한다) */
    override fun start() {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                step()
                delay(TICK_MS)
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    /** 현재 가상 시각까지 밀린 이벤트를 순서대로 모두 내보낸다 */
    suspend fun step() = mutex.withLock {
        val sc = _scenario.value ?: return@withLock
        val e = elapsed()
        while (nextStep < sc.steps.size && sc.steps[nextStep].atMs <= e) {
            val s = sc.steps[nextStep++]
            apply(s.event)
            sink?.invoke(s.event, sc.epochAtZero + s.atMs)
        }
        val idx = sc.chapters.indexOfLast { it.startMs <= e }.coerceAtLeast(0)
        if (idx != _chapterIndex.value) _chapterIndex.value = idx
        if (!_paused.value) onTick?.invoke(sc.epochAtZero + e)
        if (nextStep >= sc.steps.size && !_finished.value) {
            _finished.value = true
        }
    }

    private fun apply(event: ScenarioEvent) {
        when (event) {
            is ScenarioEvent.GearTo -> _gear.value = event.gear
            is ScenarioEvent.Door -> _door.value = event.open
            is ScenarioEvent.Seats -> _seats.value = event.seats
            is ScenarioEvent.Wiper -> _wiper.value = event.on
            is ScenarioEvent.OutsideTemp -> _outsideTemp.value = event.celsius
            is ScenarioEvent.Location -> _location.value = LocationSample(event.point, event.placeName)
            is ScenarioEvent.Navi -> _destination.value = event.destination
            is ScenarioEvent.Home -> _home.value = event.point
            is ScenarioEvent.Office -> _office.value = event.point
            ScenarioEvent.IgnitionOff -> _ignitionOff.tryEmit(Unit)
        }
    }

    fun setSpeed(speed: Double) {
        rebase()
        _speed.value = speed.coerceIn(1.0, 3600.0)
    }

    fun pause() {
        if (_paused.value) return
        rebase()
        _paused.value = true
    }

    fun resume() {
        if (!_paused.value) return
        anchorReal = realNow()
        _paused.value = false
    }

    /** 다음 챕터 시작으로 건너뛴다. 마지막 챕터면 시나리오 끝으로 간다 */
    fun nextChapter() {
        val sc = _scenario.value ?: return
        val next = sc.chapters.firstOrNull { it.startMs > elapsed() }
        jumpTo(next?.startMs ?: ((sc.steps.lastOrNull()?.atMs ?: 0L) + 1_000L))
    }

    fun jumpToChapter(index: Int) {
        val target = _scenario.value?.chapters?.getOrNull(index) ?: return
        jumpTo(target.startMs)
    }

    /** 앞으로만 건너뛴다. 이미 지난 시각이면 아무것도 하지 않는다 (지난 기록을 되돌릴 수 없다) */
    fun jumpTo(elapsedMs: Long) {
        if (elapsedMs <= elapsed()) return
        anchorElapsed = elapsedMs
        anchorReal = realNow()
    }

    private fun rebase() {
        anchorElapsed = elapsed()
        anchorReal = realNow()
    }

    private fun resetState() {
        _gear.value = Gear.UNKNOWN
        _door.value = false
        _seats.value = emptySet()
        _wiper.value = false
        _outsideTemp.value = null
        _location.value = null
        _destination.value = null
        _home.value = null
        _office.value = null
        _lastRouteRequest.value = null
    }

    override suspend fun requestRoute(destination: NamedPoint): Boolean {
        _lastRouteRequest.value = destination.name
        _destination.value = Destination(destination.name, destination.point, 0)
        return true
    }

    override suspend fun requestRouteWithWaypoints(waypoints: List<NamedPoint>, destination: NamedPoint): Boolean {
        _lastRouteRequest.value = (waypoints + destination).joinToString(" → ") { it.name }
        _destination.value = Destination(destination.name, destination.point, 0)
        return true
    }

    /** 시연용: 목적지 도착 콜백을 낸다 */
    fun simulateArrival() {
        _arrivals.tryEmit(Unit)
    }

    /** 시연용: 시동 ON 을 낸다. 집이 아닌 곳에서 닫힌 이동의 도착 카드("다음 시동 때")를 볼 때 쓴다 */
    fun simulateIgnitionOn() {
        _ignitionOn.tryEmit(Unit)
    }

    companion object {
        private const val TICK_MS = 200L
    }
}
