package kr.triprecap.app.pleos

import ai.pleos.playground.navi.constants.BookmarkType
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NaviMode
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
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
import kotlinx.coroutines.withTimeoutOrNull
import kr.triprecap.app.domain.GeoPoint
import kr.triprecap.app.domain.NamedPoint
import kr.triprecap.app.ports.Destination
import kr.triprecap.app.ports.NaviSource

/**
 * 내비 어댑터. Pleos NaviHelper 3.0.5. 조회와 경로 요청만 한다(경로 취소·옵션 변경은 호출하지 않는다).
 *
 * 사용 API (AAR javap + 문서 NaviHelper_initialization-and-release, _destinations, _route-with-waypoints, _event-listeners 로 확인)
 * - `NaviHelper(context).initialize(onConnected, onFailed)`, `addListener` / `removeListener`, `release()`.
 *   `release()` 뒤에는 새 객체가 필요하므로(문서) start 때마다 새로 만든다.
 * - 조회: `getRouteStateInfo()` → `onRouteStateInfo(RouteStateInfo)`, `getDestinationInfo()` → `onDestinationInfo(DestinationInfo)`,
 *   `getBookmarkInfo()` → `onBookmarkInfo(List<BookmarkInfo>)`. 값은 반환되지 않고 리스너로 온다.
 *   `Destination(name, point, remainTimeS)` = `DestinationInfo.name`(비면 address), `latitude`·`longitude`, `remainTime`.
 *   HOME·OFFICE = `BookmarkInfo.category` 가 `BookmarkType.HOME`·`OFFICE` 인 항목의 `latitude`·`longitude`.
 * - 다시 읽는 시점: 연결 직후, `onRouteStarted`, `onRoutingCompleted`, `onRouteEnded`, `onRouteCancelled`, `onDestinationChanged`,
 *   `onBookMarkChanged`, `onDestinationArrived`, `onNavigationStatus`, 그리고 30초 주기(이벤트가 오지 않는 환경 대비, 남은 시간 갱신).
 * - 도착: `onDestinationArrived(DestinationArrivedInfo)` → `arrivals.tryEmit(Unit)`, destination = null.
 * - 경로 요청: `requestRoute(RouteInfo)`, `requestRouteWithWaypoints(RouteWithWaypointsRequest(destination, waypoints))`.
 *   경유지는 앞에서 2개까지만 쓰고 `WaypointIndex.FIRST`, `SECOND` 를 붙인다.
 *   SDK 호출은 반환값이 없다. 호출 뒤 3초 안에 `onError` 가 오면 false, `onRouteStarted`·`onRoutingCompleted` 가 오면 true,
 *   아무 이벤트도 없으면 호출이 예외 없이 끝난 것을 근거로 true 를 돌려준다.
 *
 * 권한 (AAR 의 @RequiresPermission + 문서 NaviHelper_declaring-permissions)
 * - 매니페스트: `pleos.car.permission.NAVI_ROUTE` (get*Info), `pleos.car.permission.NAVI_ROUTE_SEARCH` (requestRoute, requestRouteWithWaypoints)
 * - 런타임 권한 요청이 필요한지는 문서에 없다.
 *
 * 미확인
 * - 안내 중이 아닐 때 `getDestinationInfo()` 가 무엇을 돌려주는지. `RouteStateInfo.naviMode` 가 ROUTE_GUIDING·SIMULATION_GUIDING 이거나
 *   `isRouting` 이 true 일 때만 안내 중으로 보고, 아니면 destination = null 로 둔다. 이름과 좌표가 모두 빈 DestinationInfo 도 null 로 처리한다.
 * - `RouteStateInfo.isRouting` 이 "안내 중"인지 "경로 탐색 중"인지.
 * - `DestinationInfo.remainTime` 의 단위. 초로 보고 그대로 `remainTimeS` 에 넣는다.
 * - `RouteInfo.poiId`, `poiSubId`, `address` 를 빈 문자열로 보내도 경로 탐색이 되는지(문서 예시는 모두 실제 POI 값을 넣는다).
 * - 에뮬레이터는 `onRouteEnded` 가 경로 시작 때 온다고 알려져 있어서, `onRouteEnded` 에서는 destination 을 지우지 않고 다시 읽기만 한다.
 * - 2026-11-10 Pleos Connect Emulator v2.1.0 에서 확인한 것은 연결, 즐겨찾기 2개(HOME, OFFICE) 조회, `FREE_DRIVE` 상태 수신까지다.
 *   안내 중 목적지 조회, 도착 콜백, `requestRoute`·`requestRouteWithWaypoints` 실제 호출은 확인하지 못했다 (docs/verification-2026-11-10.md).
 */
class PleosNavi(context: Context) : NaviSource {

    private val appContext = context.applicationContext

    private val _destination = MutableStateFlow<Destination?>(null)
    override val destination: StateFlow<Destination?> = _destination.asStateFlow()
    private val _home = MutableStateFlow<GeoPoint?>(null)
    override val home: StateFlow<GeoPoint?> = _home.asStateFlow()
    private val _office = MutableStateFlow<GeoPoint?>(null)
    override val office: StateFlow<GeoPoint?> = _office.asStateFlow()
    private val _arrivals = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val arrivals: SharedFlow<Unit> = _arrivals.asSharedFlow()

    private val lock = Any()
    private var started = false
    private var scope: CoroutineScope? = null
    private var navi: NaviHelper? = null
    @Volatile private var connected = false
    /** 마지막 RouteStateInfo 로 판정한 안내 여부. 아직 받지 못했으면 null */
    @Volatile private var guiding: Boolean? = null
    @Volatile private var pendingRoute: CompletableDeferred<Boolean>? = null
    private val routeMutex = Mutex()
    @Volatile private var firstDestinationLogged = false
    @Volatile private var firstBookmarkLogged = false

    private val listener = object : NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            Log.i(TAG, "navi onRouteStarted name=${routeStartInfo.name}")
            pendingRoute?.complete(true)
            refresh()
        }
        override fun onRoutingCompleted() {
            Log.i(TAG, "navi onRoutingCompleted")
            pendingRoute?.complete(true)
            refresh()
        }
        override fun onRouteEnded() { Log.i(TAG, "navi onRouteEnded"); refresh() }
        override fun onRouteCancelled() {
            Log.i(TAG, "navi onRouteCancelled")
            guiding = false
            _destination.value = null
            refresh()
        }
        override fun onDestinationChanged() { Log.i(TAG, "navi onDestinationChanged"); refresh() }
        override fun onBookMarkChanged() { Log.i(TAG, "navi onBookMarkChanged"); refresh() }
        override fun onNavigationStatus(navigationStatus: ai.pleos.playground.navi.constants.NavigationStatus) {
            Log.i(TAG, "navi onNavigationStatus $navigationStatus")
            refresh()
        }
        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
            Log.i(TAG, "navi onDestinationArrived name=${destinationArrivedInfo.name}")
            _arrivals.tryEmit(Unit)
            guiding = false
            _destination.value = null
            refresh()
        }
        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) = onRouteState(routeStateInfo)
        override fun onDestinationInfo(destinationInfo: DestinationInfo) = onDestination(destinationInfo)
        override fun onBookmarkInfo(bookmarkInfoList: List<BookmarkInfo>) = onBookmarks(bookmarkInfoList)
        override fun onError(errorCode: NaviErrorCode) {
            Log.w(TAG, "navi onError $errorCode")
            pendingRoute?.complete(false)
        }
    }

    override fun start() {
        synchronized(lock) {
            if (started) return
            started = true
            val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            scope = s
            guiding = null
            connected = false
            val n = runCatching { NaviHelper(appContext) }
                .onFailure { Log.w(TAG, "NaviHelper create failed", it) }
                .getOrNull()
            navi = n
            if (n != null) {
                runCatching {
                    n.initialize(
                        {
                            Log.i(TAG, "NaviHelper connected")
                            runCatching { n.addListener(listener) }
                                .onFailure { Log.w(TAG, "NaviHelper addListener failed", it) }
                            connected = true
                            refresh()
                        },
                        { t -> Log.w(TAG, "NaviHelper connect failed", t) },
                    )
                }.onFailure { Log.w(TAG, "NaviHelper initialize failed", it) }
            }
            s.launch {
                while (isActive) {
                    delay(POLL_MS)
                    refresh()
                }
            }
        }
    }

    override fun stop() {
        synchronized(lock) {
            if (!started) return
            started = false
            scope?.cancel()
            scope = null
            connected = false
            pendingRoute?.complete(false)
            val n = navi ?: return
            navi = null
            runCatching { n.removeListener(listener) }.onFailure { Log.w(TAG, "NaviHelper removeListener failed", it) }
            runCatching { n.release() }.onFailure { Log.w(TAG, "NaviHelper release failed", it) }
        }
    }

    /** 안내 상태·즐겨찾기를 다시 조회한다. 목적지는 onRouteStateInfo 에서 안내 중일 때 이어서 조회한다 */
    private fun refresh() {
        val n = connectedNavi() ?: return
        runCatching { n.getRouteStateInfo() }.onFailure { Log.w(TAG, "getRouteStateInfo failed", it) }
        runCatching { n.getBookmarkInfo() }.onFailure { Log.w(TAG, "getBookmarkInfo failed", it) }
        // 안내 상태를 아직 한 번도 받지 못했으면 목적지를 직접 조회한다
        if (guiding == null) requestDestination(n)
    }

    private fun connectedNavi(): NaviHelper? = synchronized(lock) { if (started && connected) navi else null }

    private fun requestDestination(n: NaviHelper) {
        runCatching { n.getDestinationInfo() }.onFailure { Log.w(TAG, "getDestinationInfo failed", it) }
    }

    private fun onRouteState(state: RouteStateInfo) {
        val mode = runCatching { state.naviMode }.getOrNull()
        val nowGuiding = mode == NaviMode.ROUTE_GUIDING || mode == NaviMode.SIMULATION_GUIDING || state.isRouting
        if (guiding != nowGuiding) Log.i(TAG, "navi route state: naviMode=$mode isRouting=${state.isRouting} guiding=$nowGuiding")
        guiding = nowGuiding
        if (nowGuiding) {
            connectedNavi()?.let { requestDestination(it) }
        } else {
            _destination.value = null
        }
    }

    private fun onDestination(info: DestinationInfo) {
        if (guiding == false) return
        val name = info.name.takeIf { it.isNotBlank() } ?: info.address
        val point = pointOrNull(info.latitude, info.longitude)
        if (name.isBlank() && point == null) {
            _destination.value = null
            return
        }
        if (!firstDestinationLogged) {
            firstDestinationLogged = true
            Log.i(TAG, "first value: destination name=$name point=$point remainTime=${info.remainTime}")
        }
        _destination.value = Destination(name = name, point = point, remainTimeS = info.remainTime)
    }

    private fun onBookmarks(list: List<BookmarkInfo>) {
        if (!firstBookmarkLogged) {
            firstBookmarkLogged = true
            Log.i(TAG, "first value: bookmarks count=${list.size} categories=${list.map { it.category }}")
        }
        _home.value = list.firstOrNull { it.category == BookmarkType.HOME }?.let { pointOrNull(it.latitude, it.longitude) }
        _office.value = list.firstOrNull { it.category == BookmarkType.OFFICE }?.let { pointOrNull(it.latitude, it.longitude) }
    }

    private fun pointOrNull(lat: Double, lon: Double): GeoPoint? =
        if (lat.isNaN() || lon.isNaN() || (lat == 0.0 && lon == 0.0)) null else GeoPoint(lat, lon)

    override suspend fun requestRoute(destination: NamedPoint): Boolean =
        sendRoute("requestRoute ${destination.name}") { n -> n.requestRoute(routeInfo(destination)) }

    override suspend fun requestRouteWithWaypoints(waypoints: List<NamedPoint>, destination: NamedPoint): Boolean {
        if (waypoints.isEmpty()) return requestRoute(destination)
        if (waypoints.size > MAX_WAYPOINTS) Log.w(TAG, "waypoints ${waypoints.size} > $MAX_WAYPOINTS; using first $MAX_WAYPOINTS")
        val indexes = listOf(WaypointIndex.FIRST, WaypointIndex.SECOND)
        val requestWaypoints = waypoints.take(MAX_WAYPOINTS).mapIndexed { i, w ->
            RequestWaypointInfo(
                latitude = w.point.lat,
                longitude = w.point.lon,
                poiName = w.name,
                waypointIndex = indexes[i],
                poiId = "",
                address = "",
                poiSubId = "",
            )
        }
        return sendRoute("requestRouteWithWaypoints ${waypoints.take(MAX_WAYPOINTS).map { it.name }} -> ${destination.name}") { n ->
            n.requestRouteWithWaypoints(RouteWithWaypointsRequest(destination = routeInfo(destination), waypoints = requestWaypoints))
        }
    }

    private fun routeInfo(p: NamedPoint) = RouteInfo(
        latitude = p.point.lat,
        longitude = p.point.lon,
        displayLatitude = null,
        displayLongitude = null,
        poiName = p.name,
        poiId = "",
        address = "",
        poiSubId = "",
    )

    /** 경로 요청을 보내고 결과 이벤트를 [ROUTE_RESULT_TIMEOUT_MS] 동안 기다린다 */
    private suspend fun sendRoute(what: String, call: (NaviHelper) -> Unit): Boolean = routeMutex.withLock {
        val n = connectedNavi() ?: run {
            Log.w(TAG, "$what: NaviHelper not connected")
            return@withLock false
        }
        val result = CompletableDeferred<Boolean>()
        pendingRoute = result
        try {
            val sent = runCatching { call(n) }.onFailure { Log.w(TAG, "$what failed", it) }.isSuccess
            if (!sent) return@withLock false
            Log.i(TAG, "$what sent")
            val accepted = withTimeoutOrNull(ROUTE_RESULT_TIMEOUT_MS) { result.await() }
            if (accepted == null) Log.w(TAG, "$what: no result event in ${ROUTE_RESULT_TIMEOUT_MS}ms; treated as accepted")
            accepted ?: true
        } finally {
            pendingRoute = null
        }
    }

    companion object {
        private const val TAG = "TripRecapPleos"
        private const val POLL_MS = 30_000L
        private const val ROUTE_RESULT_TIMEOUT_MS = 3_000L
        private const val MAX_WAYPOINTS = 2
    }
}
