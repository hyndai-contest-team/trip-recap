package kr.triprecap.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kr.triprecap.app.AppContainer
import kr.triprecap.app.LiveStatus
import kr.triprecap.app.domain.DrivingView
import kr.triprecap.app.domain.Toasts

sealed interface Screen {
    data object List : Screen
    data class Detail(val tripId: Long) : Screen
    data class Share(val tripId: Long) : Screen
    data class Course(val tripId: Long) : Screen
    data object Settings : Screen
    /** 일상 이동 (목록 맨 아래 줄에서 연다) */
    data object DailyMoves : Screen
}

/** 주행 중 화면의 경과 시간을 다시 계산하는 간격. 화면은 분 단위로만 바뀐다 */
private const val DRIVING_TICK_MS = 1_000L

/**
 * 화면 전환.
 * - 동의 전: 동의 화면
 * - 주행 제한 중: 주행 중 화면만 (터치 없음). 보던 화면은 정차하면 다시 나온다
 * - 정차 중: 목록 · 상세 · 공유 · 다시 가기 · 설정 · 일상 이동. 도착 카드가 있으면 위에 띄운다
 *   (저장한 여행: [닫기] / [여행 보기], 묻는 이동: [지우기] / [남기기])
 *
 * 여행을 열면(목록 카드, 도착 카드 "여행 보기") 본 여행으로 표시해 "새 여행" 칩을 없앤다.
 * 저장소를 바꾸는 일(본 여행 표시, 여행 지우기, 도착 카드 답)은 [AppContainer.scope] 에서 한다: 화면이 바뀌어도 취소되지 않게.
 *
 * 알림(토스트): 화면은 `LocalToast.current.show("…")` 로 띄운다. 앱 창 아래 가운데에 그린다.
 * 주행 제한이 걸리면 그 프레임부터 그리지 않고 상태도 지운다(정차 뒤 옛 알림이 다시 보이지 않게).
 */
@Composable
fun AppRoot(c: AppContainer, onConsent: () -> Unit) {
    TripRecapTheme {
        val consent by c.settings.consentGiven.collectAsStateWithLifecycle()
        val recording by c.settings.recordingEnabled.collectAsStateWithLifecycle()
        val restricted by c.restricted.collectAsStateWithLifecycle()
        val live by c.session.live.collectAsStateWithLifecycle()
        val arrival by c.session.arrival.collectAsStateWithLifecycle()
        val trips by c.store.trips.collectAsStateWithLifecycle(emptyList())
        var screen by remember { mutableStateOf<Screen>(Screen.List) }
        val scope = rememberCoroutineScope()
        val toast = remember { ToastState(scope) }
        val toastBottom = remember { mutableStateOf<Dp>(ToastDefaults.BottomPadding) }
        LaunchedEffect(restricted) { if (restricted) toast.clear() }

        fun openTrip(id: Long) {
            screen = Screen.Detail(id)
            c.scope.launch { c.session.markSeen(id) }
        }

        CompositionLocalProvider(LocalToast provides toast, LocalToastBottomPadding provides toastBottom) {
            BoxWithConstraints(Modifier.fillMaxSize().background(PleosColor.Background).safeDrawingPadding()) {
                val portrait = isPortrait(maxWidth)
                when {
                    !consent -> ConsentScreen(onAgree = onConsent)
                    restricted -> DrivingHost(c, recordingEnabled = consent && recording, live = live, hasPreviousScreen = screen != Screen.List)
                    else -> {
                        BackHandler(enabled = screen != Screen.List) {
                            screen = when (val s = screen) {
                                is Screen.Share -> Screen.Detail(s.tripId)
                                is Screen.Course -> Screen.Detail(s.tripId)
                                else -> Screen.List
                            }
                        }
                        when (val s = screen) {
                            Screen.List -> TripListScreen(
                                c, trips, portrait,
                                onOpen = ::openTrip,
                                onSettings = { screen = Screen.Settings },
                                onDailyMoves = { screen = Screen.DailyMoves },
                            )
                            is Screen.Detail -> {
                                val trip = trips.firstOrNull { it.id == s.tripId }
                                // 다른 곳에서 지워졌으면 목록으로 돌아간다
                                if (trip == null) LaunchedEffect(s.tripId) { screen = Screen.List }
                                else TripDetailScreen(
                                    c, trip, portrait,
                                    onBack = { screen = Screen.List },
                                    onShare = { screen = Screen.Share(trip.id) },
                                    onCourse = { screen = Screen.Course(trip.id) },
                                    onDelete = {
                                        screen = Screen.List
                                        c.scope.launch { c.session.onTripDeleted(trip.id) }
                                        toast.show(Toasts.TRIP_DELETED)
                                    },
                                )
                            }
                            is Screen.Share -> trips.firstOrNull { it.id == s.tripId }?.let { ShareQrScreen(c, it, portrait, onBack = { screen = Screen.Detail(s.tripId) }) }
                            is Screen.Course -> trips.firstOrNull { it.id == s.tripId }?.let { CourseStartScreen(c, it, portrait, onBack = { screen = Screen.Detail(s.tripId) }) }
                            Screen.Settings -> SettingsScreen(c, portrait, onBack = { screen = Screen.List })
                            Screen.DailyMoves -> DailyMovesScreen(c, trips, portrait, onBack = { screen = Screen.List })
                        }
                        arrival?.let { a ->
                            val id = a.trip.id
                            ArrivalCard(
                                a,
                                onOpen = { c.scope.launch { c.session.dismissArrival() }; openTrip(id) },
                                onDismiss = { c.scope.launch { c.session.dismissArrival() } },
                                onKeep = { c.scope.launch { c.session.keep(id) }; toast.show(Toasts.KEPT_AS_TRIP) },
                                onDiscard = { c.scope.launch { c.session.discard(id) }; toast.show(Toasts.MOVE_DELETED) },
                            )
                        }
                    }
                }
                if (consent && !restricted) ToastHost(toast, bottomPadding = toastBottom.value)
            }
        }
    }
}

/**
 * 주행 중 화면. 경과 시간을 [DRIVING_TICK_MS] 마다 다시 계산하지만, [DrivingView] 는 분 단위 글자만 담으므로
 * 값이 같으면 상태가 바뀌지 않아(mutableStateOf 의 구조 비교) 주행 중 화면을 다시 그리지 않는다.
 */
@Composable
private fun DrivingHost(c: AppContainer, recordingEnabled: Boolean, live: LiveStatus, hasPreviousScreen: Boolean) {
    var view by remember { mutableStateOf(DrivingView.of(recordingEnabled, live, c.clock.now(), hasPreviousScreen)) }
    LaunchedEffect(recordingEnabled, live, hasPreviousScreen) {
        while (true) {
            view = DrivingView.of(recordingEnabled, live, c.clock.now(), hasPreviousScreen)
            delay(DRIVING_TICK_MS)
        }
    }
    DrivingScreen(view)
}
