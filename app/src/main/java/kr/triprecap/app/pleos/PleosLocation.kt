package kr.triprecap.app.pleos

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kr.triprecap.app.domain.GeoPoint
import kr.triprecap.app.domain.LocationSample
import kr.triprecap.app.ports.LocationSource

/**
 * 위치 어댑터. Pleos FusedLocation 1.1.3 을 먼저 쓰고, 쓸 수 없으면 NaviHelper 3.0.5 의 현재 위치를 10초마다 조회한다.
 *
 * FusedLocation (AAR javap + 문서 FusedLocation_initialization-and-release, FusedLocation_location-data-access 로 확인)
 * - `FusedLocation(context)`, `isSupported()`, `initialize()`, `release()`
 * - `registerFusedLocationCallback(FusedLocationListener)` / `unregisterFusedLocationCallback(listener)`
 * - `FusedLocationListener.onChanged(LocationData?)`, `onError(Exception)`; `LocationData.latitude`, `longitude`
 * - 구독 API 에 수신 주기 인자가 없다. 1초보다 자주 오는 값은 버려서 `location` 갱신을 1초에 1번 이하로 맞춘다.
 * - `release()` 한 인스턴스는 다시 쓰지 못하므로(문서) start 때마다 새 인스턴스를 만든다.
 *
 * 대체 경로 (AAR javap + 문서 NaviHelper_initialization-and-release 로 확인)
 * - `NaviHelper(context).initialize(onConnected, onFailed)`, `addListener(NaviHelperEventListener)`, `getCurrentLocationInfo()`
 *   → `onCurrentLocationInfo(CurrentLocationInfo)` 의 `latitude`, `longitude`
 * - 대체 경로로 바뀌는 조건: FUSED_LOCATION 런타임 권한 없음, `isSupported()` false, 초기화·등록 예외, `onError` 수신.
 * - 권한이 없어서 대체 경로로 간 경우에만, 10초 주기 조회 때마다 권한을 다시 확인해서 허용되면 FusedLocation 으로 돌아간다.
 *
 * 권한
 * - 런타임(dangerous): `pleos.car.permission.FUSED_LOCATION` (문서 FusedLocation_declaring-permissions). Activity 에서 요청한다.
 * - 매니페스트: `pleos.car.permission.NAVI_ROUTE` (getCurrentLocationInfo, AAR 의 @RequiresPermission 으로 확인)
 *
 * 미확인
 * - FusedLocation 콜백의 실제 수신 주기(문서에 수치가 없다)와 좌표계. WGS84 위경도로 보고 그대로 쓴다.
 * - 위치를 아직 계산하지 못했을 때 좌표가 (0, 0) 으로 오는지. (0, 0) 과 NaN 은 버린다.
 * - 위치가 바뀔 때 값이 이어서 들어오는지. 2026-11-10 Pleos Connect Emulator v2.1.0 에서 확인한 것은
 *   FusedLocation 초기화와 첫 좌표 수신(에뮬레이터 기본 좌표 39.237,-123.150)까지다.
 *   값 변화 수신과 NaviHelper 대체 경로는 확인하지 못했다 (docs/verification-2026-11-10.md).
 */
class PleosLocation(context: Context) : LocationSource {

    private val appContext = context.applicationContext

    private val _location = MutableStateFlow<LocationSample?>(null)
    override val location: StateFlow<LocationSample?> = _location.asStateFlow()

    private val lock = Any()
    private var started = false
    private var scope: CoroutineScope? = null
    private var fused: FusedLocation? = null
    private var navi: NaviHelper? = null
    private var pollJob: Job? = null
    /** FusedLocation 을 쓰지 못한 이유가 권한 없음뿐이면 true. 이때만 나중에 다시 시도한다 */
    private var retryFusedWhenGranted = false
    @Volatile private var naviConnected = false
    @Volatile private var lastEmitAt = 0L
    @Volatile private var firstFusedLogged = false
    @Volatile private var firstNaviLogged = false

    private val fusedListener = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            location ?: return
            val now = SystemClock.elapsedRealtime()
            if (now - lastEmitAt < MIN_INTERVAL_MS) return
            if (!emit(location.latitude, location.longitude)) return
            lastEmitAt = now
            if (!firstFusedLogged) {
                firstFusedLogged = true
                Log.i(TAG, "first value: fused location ${location.latitude},${location.longitude}")
            }
        }

        override fun onError(exception: Exception) {
            Log.w(TAG, "fused location error", exception)
            // 콜백 안에서 release 하지 않도록 다른 코루틴에서 대체 경로로 바꾼다
            synchronized(lock) { scope }?.launch { switchToFallback() }
        }
    }

    private val naviListener = object : NaviHelperEventListener {
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            if (!emit(currentLocationInfo.latitude, currentLocationInfo.longitude)) return
            if (!firstNaviLogged) {
                firstNaviLogged = true
                Log.i(TAG, "first value: navi location ${currentLocationInfo.latitude},${currentLocationInfo.longitude}")
            }
        }
    }

    override fun start() {
        synchronized(lock) {
            if (started) return
            started = true
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            if (!startFusedLocked()) startFallbackLocked()
        }
    }

    override fun stop() {
        synchronized(lock) {
            if (!started) return
            started = false
            stopFusedLocked()
            stopFallbackLocked()
            scope?.cancel()
            scope = null
        }
    }

    private fun emit(lat: Double, lon: Double): Boolean {
        if (lat.isNaN() || lon.isNaN() || (lat == 0.0 && lon == 0.0)) return false
        _location.value = LocationSample(GeoPoint(lat, lon), placeName = null)
        return true
    }

    private fun hasFusedPermission(): Boolean =
        runCatching { appContext.checkSelfPermission(FUSED_PERMISSION) == PackageManager.PERMISSION_GRANTED }
            .getOrDefault(false)

    /** FusedLocation 구독을 시작한다. 시작하지 못하면 false */
    private fun startFusedLocked(): Boolean {
        retryFusedWhenGranted = false
        if (!hasFusedPermission()) {
            Log.w(TAG, "FUSED_LOCATION permission not granted; using NaviHelper location")
            retryFusedWhenGranted = true
            return false
        }
        val f = runCatching { FusedLocation(appContext) }
            .onFailure { Log.w(TAG, "FusedLocation create failed", it) }
            .getOrNull() ?: return false
        val supported = runCatching { f.isSupported() }
            .onFailure { Log.w(TAG, "FusedLocation isSupported failed", it) }
            .getOrDefault(false)
        if (!supported) {
            Log.w(TAG, "FusedLocation not supported; using NaviHelper location")
            return false
        }
        val ok = runCatching {
            f.initialize()
            f.registerFusedLocationCallback(fusedListener)
        }.onFailure {
            Log.w(TAG, "FusedLocation initialize/register failed", it)
            runCatching { f.release() }
        }.isSuccess
        if (!ok) return false
        fused = f
        Log.i(TAG, "FusedLocation initialized")
        return true
    }

    private fun stopFusedLocked() {
        val f = fused ?: return
        fused = null
        runCatching { f.unregisterFusedLocationCallback(fusedListener) }
            .onFailure { Log.w(TAG, "unregisterFusedLocationCallback failed", it) }
        runCatching { f.release() }.onFailure { Log.w(TAG, "FusedLocation release failed", it) }
    }

    private fun switchToFallback() {
        synchronized(lock) {
            if (!started || fused == null) return
            stopFusedLocked()
            startFallbackLocked()
        }
    }

    private fun startFallbackLocked() {
        if (navi != null) return
        val n = runCatching { NaviHelper(appContext) }
            .onFailure { Log.w(TAG, "NaviHelper create failed (location)", it) }
            .getOrNull()
        navi = n
        naviConnected = false
        if (n != null) {
            runCatching {
                n.initialize(
                    {
                        Log.i(TAG, "NaviHelper connected (location fallback)")
                        runCatching { n.addListener(naviListener) }
                            .onFailure { Log.w(TAG, "NaviHelper addListener failed (location)", it) }
                        naviConnected = true
                        requestNaviLocation(n)
                    },
                    { t -> Log.w(TAG, "NaviHelper connect failed (location)", t) },
                )
            }.onFailure { Log.w(TAG, "NaviHelper initialize failed (location)", it) }
        }
        pollJob = scope?.launch {
            while (isActive) {
                delay(FALLBACK_POLL_MS)
                val switched = synchronized(lock) {
                    if (started && retryFusedWhenGranted && hasFusedPermission() && startFusedLocked()) {
                        stopFallbackLocked(cancelPoll = false)
                        true
                    } else {
                        false
                    }
                }
                if (switched) return@launch
                synchronized(lock) { navi }?.let { if (naviConnected) requestNaviLocation(it) }
            }
        }
    }

    private fun requestNaviLocation(n: NaviHelper) {
        runCatching { n.getCurrentLocationInfo() }
            .onFailure { Log.w(TAG, "getCurrentLocationInfo failed", it) }
    }

    private fun stopFallbackLocked(cancelPoll: Boolean = true) {
        if (cancelPoll) pollJob?.cancel()
        pollJob = null
        val n = navi ?: return
        navi = null
        naviConnected = false
        runCatching { n.removeListener(naviListener) }
            .onFailure { Log.w(TAG, "NaviHelper removeListener failed (location)", it) }
        runCatching { n.release() }.onFailure { Log.w(TAG, "NaviHelper release failed (location)", it) }
    }

    companion object {
        private const val TAG = "TripRecapPleos"
        private const val FUSED_PERMISSION = "pleos.car.permission.FUSED_LOCATION"
        /** 1초 간격 목표. 수신 시각의 오차를 감안해 900ms 로 둔다 */
        private const val MIN_INTERVAL_MS = 900L
        private const val FALLBACK_POLL_MS = 10_000L
    }
}
