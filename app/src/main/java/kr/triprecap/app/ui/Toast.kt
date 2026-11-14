package kr.triprecap.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 보통 알림이 보이는 시간. 운전자가 1~2초 안에 읽는 한 줄 + 여유 1초 */
const val TOAST_DURATION_MS = 3_000L

/**
 * 알림(토스트) 한 개의 상태. 한 번에 한 메시지만 보인다.
 * - [show]: 보이고 [durationMs] 뒤 사라진다. 새 메시지가 오면 바로 바꾸고 시간을 다시 센다.
 * - [clear]: 보이는 메시지를 바로 지운다.
 * Compose 에 의존하지 않아 JVM 단위 테스트로 확인한다(ToastStateTest).
 */
class ToastState(
    private val scope: CoroutineScope,
    private val durationMs: Long = TOAST_DURATION_MS,
) {
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private var timer: Job? = null

    fun show(text: String) {
        timer?.cancel()
        _message.value = text
        timer = scope.launch {
            delay(durationMs)
            _message.value = null
        }
    }

    fun clear() {
        timer?.cancel()
        timer = null
        _message.value = null
    }
}

object ToastDefaults {
    /** 앱 창 아래에서 알림까지 거리 */
    val BottomPadding = 24.dp
    val Height = 88.dp
    val MaxWidth = 792.dp
}

/**
 * 화면에서 `LocalToast.current.show("…")` 로 알림을 띄운다. [AppRoot] 가 제공한다.
 * 주행 제한이 걸리면 AppRoot 가 알림을 지운다.
 */
val LocalToast = staticCompositionLocalOf<ToastState> { error("ToastState 가 제공되지 않았어요. AppRoot 안에서 쓰세요") }

/** [ToastBottomPadding] 이 값을 바꿀 때 쓰는 CompositionLocal. AppRoot 가 제공한다 */
internal val LocalToastBottomPadding = staticCompositionLocalOf<MutableState<Dp>?> { null }

/**
 * 이 composable 이 화면에 있는 동안 알림을 앱 창 아래에서 [padding] 만큼 띄운다. 화면이 사라지면 기본값(24)으로 돌아간다.
 * 아래 고정 줄이 있는 화면은 `ToastBottomPadding(BottomBarDefaults.Height + 24.dp)` 처럼 줄 위로 올린다.
 */
@Composable
fun ToastBottomPadding(padding: Dp) {
    val holder = LocalToastBottomPadding.current ?: return
    DisposableEffect(holder, padding) {
        holder.value = padding
        onDispose { holder.value = ToastDefaults.BottomPadding }
    }
}

/**
 * 알림을 그린다. 앱 창 전체를 덮는 자리에 두고(터치는 받지 않는다), 아래 가운데에 높이 88 pill 로 보인다.
 * 등장·퇴장 애니메이션은 넣지 않는다(화면 전환·주행 중 화면에 모션을 넣지 않는 규칙과 같게. docs/design-rules.md).
 */
@Composable
fun ToastHost(state: ToastState, modifier: Modifier = Modifier, bottomPadding: Dp = ToastDefaults.BottomPadding) {
    val message by state.message.collectAsState()
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        val text = message ?: return@Box
        val shape = RoundedCornerShape(50)
        Box(
            Modifier
                .padding(start = 16.dp, end = 16.dp, bottom = bottomPadding)
                .widthIn(max = ToastDefaults.MaxWidth)
                .height(ToastDefaults.Height)
                // 그림자: 아래로 8, 흐림 32, 검정 50%
                .shadow(16.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
                .background(PleosColor.SurfaceAccent, shape)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .padding(horizontal = 40.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, style = PleosType.Body28Strong, color = PleosColor.Text, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }
    }
}
