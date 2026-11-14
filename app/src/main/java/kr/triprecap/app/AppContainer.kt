package kr.triprecap.app

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kr.triprecap.app.data.PrefsSettings
import kr.triprecap.app.data.RoomTripStore
import kr.triprecap.app.data.TripDatabase
import kr.triprecap.app.domain.Gear
import kr.triprecap.app.domain.KnownPlaces
import kr.triprecap.app.domain.NamedPoint
import kr.triprecap.app.domain.PlaceNameResolver
import kr.triprecap.app.domain.TripRecorder.Event
import kr.triprecap.app.kakao.KakaoPlaceLookup
import kr.triprecap.app.pleos.CarUxRestrictions
import kr.triprecap.app.pleos.PleosLocation
import kr.triprecap.app.pleos.PleosNavi
import kr.triprecap.app.pleos.PleosVehicleSignals
import kr.triprecap.app.pleos.PleosVoiceIo
import kr.triprecap.app.ports.Clock
import kr.triprecap.app.ports.NaviSource
import kr.triprecap.app.ports.TripStore
import kr.triprecap.app.ports.VoiceIo
import kr.triprecap.app.scenario.GoldenScenarios
import kr.triprecap.app.scenario.ScenarioEvent
import kr.triprecap.app.scenario.ScenarioPlayer
import kr.triprecap.app.scenario.onScenarioEvent

/**
 * 앱 구성. 신호를 받는 곳은 두 가지다.
 * - vehicle: Pleos Vehicle·NaviHelper·FusedLocation 어댑터 (pleos/)
 * - scenario: 시나리오 재생기 (시연·개발)
 * 두 경우 모두 TripSession.onSignal 로 들어간다.
 */
class AppContainer(private val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = PrefsSettings(app)
    val store: TripStore = RoomTripStore(TripDatabase.create(app))
    val isPleosDevice: Boolean = android.os.Build.FINGERPRINT.startsWith("Pleos/")
    val voice: VoiceIo? = if (isPleosDevice) runCatching { PleosVoiceIo(app) }.getOrNull() else null
    val session = TripSession(store, settings, PlaceNameResolver(KakaoPlaceLookup(BuildConfig.KAKAO_REST_API_KEY)), voice)
    val player = ScenarioPlayer(scope)

    private val vehicle by lazy { PleosVehicleSignals(app) }
    private val location by lazy { PleosLocation(app) }
    private val pleosNavi by lazy { PleosNavi(app) }
    private val ux by lazy { CarUxRestrictions(app) }

    private val _scenarioMode = MutableStateFlow(settings.sourceMode == PrefsSettings.SOURCE_SCENARIO || !isPleosDevice)
    /** true 면 시나리오 재생, false 면 실제 차량 신호 */
    val scenarioMode: StateFlow<Boolean> = _scenarioMode.asStateFlow()

    val navi: NaviSource get() = if (_scenarioMode.value) player else pleosNavi
    val course = CourseController { navi }

    val clock = object : Clock {
        override fun now(): Long = if (_scenarioMode.value && player.scenario.value != null) player.now() else System.currentTimeMillis()
    }

    /** 시연용 제한 상태 토글. null 이면 자동(차량 상태를 따른다) */
    val restrictionOverride = MutableStateFlow<Boolean?>(null)
    private val uxRestricted = MutableStateFlow(false)

    /** 주행 중 화면 제한. 제한 중에는 주행 중 화면만 보여 주고 터치를 받지 않는다 */
    val restricted: StateFlow<Boolean> =
        combine(restrictionOverride, uxRestricted, session.live) { o, u, live -> o ?: (u || live.moving) }
            .stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * SDK 어댑터의 start/stop 은 이 스레드 하나에서 순서대로 한다.
     * Car.getCarManager 같은 binder 호출이 수 초 걸릴 때가 있어 메인 스레드에서 부르면 ANR 이 난다 (2026-11-10 에뮬레이터에서 확인).
     */
    private val sdkThread = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "pleos-sdk").apply { isDaemon = true } }
    private fun onSdkThread(what: String, block: () -> Unit) {
        sdkThread.execute { runCatching(block).onFailure { Log.w(TAG, "$what failed", it) } }
    }

    private var sourceJobs: List<Job> = emptyList()
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            session.start()
            startSources()
            session.backfillNames()
        }
    }

    fun setScenarioMode(enabled: Boolean) {
        if (enabled == _scenarioMode.value) return
        stopSources()
        _scenarioMode.value = enabled
        settings.sourceMode = if (enabled) PrefsSettings.SOURCE_SCENARIO else PrefsSettings.SOURCE_VEHICLE
        if (!enabled) scope.launch { session.leaveScenario() }
        if (started) startSources()
    }

    /**
     * 골든 시나리오를 올린다. 세션은 시나리오의 HOME·OFFICE 와 이전 기록만 쓴다(TripSession.useScenario).
     * scope 가 Main.immediate 라 세션 준비가 끝난 뒤 바로 같은 흐름에서 재생기를 올린다
     */
    fun loadScenario(index: Int) {
        val sc = GoldenScenarios.all.getOrNull(index) ?: return
        scope.launch {
            session.useScenario(sc.priorLog)
            player.load(sc)
        }
    }

    /**
     * 시동 ON 을 보낸다(시연 조작). 집이 아닌 곳에서 닫힌 이동의 도착 카드를 띄운다.
     * 시나리오 모드는 재생기의 시동 ON 흐름으로, 차량 모드는 세션에 바로 넣는다
     */
    fun simulateIgnitionOn() {
        if (_scenarioMode.value) player.simulateIgnitionOn()
        else scope.launch { session.onIgnitionOn() }
    }

    /** 위치 권한을 받은 뒤 위치 수신을 다시 시작한다 */
    fun restartLocation() {
        if (_scenarioMode.value || !started) return
        onSdkThread("location restart") { location.stop(); location.start() }
    }

    private fun startSources() {
        if (_scenarioMode.value) startScenario() else startVehicle()
    }

    private fun stopSources() {
        sourceJobs.forEach { it.cancel() }
        sourceJobs = emptyList()
        player.sink = null
        player.onTick = null
        player.stop()
        if (!_scenarioMode.value) {
            onSdkThread("vehicle stop") { vehicle.stop() }
            onSdkThread("location stop") { location.stop() }
            onSdkThread("navi stop") { pleosNavi.stop() }
            onSdkThread("ux restrictions stop") { ux.stop() }
            uxRestricted.value = false
        }
    }

    private fun startScenario() {
        player.sink = { e, at ->
            session.onScenarioEvent(e, at)
            if (e is ScenarioEvent.GearTo && (e.gear == Gear.DRIVE || e.gear == Gear.REVERSE)) course.onDeparted()
        }
        player.onTick = { session.tick(it) }
        sourceJobs = listOf(
            scope.launch { player.arrivals.collect { course.onDestinationArrived() } },
            scope.launch { player.ignitionOn.collect { session.onIgnitionOn() } },
        )
        player.start()
    }

    private fun startVehicle() {
        fun now() = System.currentTimeMillis()
        onSdkThread("vehicle start") { vehicle.start() }
        onSdkThread("location start") { location.start() }
        onSdkThread("navi start") { pleosNavi.start() }
        onSdkThread("ux restrictions start") { ux.start() }
        sourceJobs = listOf(
            scope.launch {
                vehicle.gear.collect {
                    session.onSignal(Event.GearChanged(it), now())
                    if (it == Gear.DRIVE || it == Gear.REVERSE) course.onDeparted()
                }
            },
            scope.launch { vehicle.doorOpen.collect { session.onSignal(Event.DoorChanged(it), now()) } },
            scope.launch { vehicle.seatOccupancy.collect { session.onSignal(Event.SeatsChanged(it), now()) } },
            scope.launch { vehicle.wiperOn.collect { session.onSignal(Event.WiperChanged(it), now()) } },
            scope.launch { vehicle.speedKmh.collect { v -> v?.let { session.onSignal(Event.SpeedChanged(it), now()) } } },
            scope.launch { vehicle.outsideTempC.collect { v -> v?.let { session.onSignal(Event.OutsideTempChanged(it), now()) } } },
            scope.launch { vehicle.ignitionOff.collect { session.onSignal(Event.IgnitionOff, now()) } },
            scope.launch { vehicle.ignitionOn.collect { session.onIgnitionOn() } },
            scope.launch { location.location.collect { s -> s?.let { session.onSignal(Event.LocationChanged(it), now()) } } },
            scope.launch { combine(pleosNavi.home, pleosNavi.office) { h, o -> KnownPlaces(h, o) }.collect { session.setNaviPlaces(it) } },
            scope.launch { pleosNavi.destination.collect { d -> session.onDestination(d?.let { x -> x.point?.let { NamedPoint(x.name, it) } }) } },
            scope.launch { pleosNavi.arrivals.collect { course.onDestinationArrived() } },
            scope.launch { ux.restricted.collect { uxRestricted.value = it } },
            scope.launch {
                while (isActive) {
                    delay(TICK_MS)
                    session.tick(now())
                }
            },
        )
    }

    companion object {
        private const val TAG = "TripRecapApp"
        private const val TICK_MS = 5_000L
    }
}
