package kr.triprecap.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kr.triprecap.app.AppContainer
import kr.triprecap.app.domain.CoursePlace
import kr.triprecap.app.domain.Stop
import kr.triprecap.app.domain.StopKind
import kr.triprecap.app.domain.Texts
import kr.triprecap.app.domain.Toasts
import kr.triprecap.app.domain.Trip
import kr.triprecap.app.domain.TripEditor
import java.time.ZoneId

private val zone: ZoneId = ZoneId.of("Asia/Seoul")

/** 메모를 듣는 최대 시간 */
private const val LISTEN_TIMEOUT_MS = 8_000L

private sealed interface Edit {
    data object Title : Edit
    data class StopName(val stop: Stop) : Edit
    data class Memo(val stop: Stop) : Edit
    data object DeleteTrip : Edit
    data class DeleteStop(val stop: Stop) : Edit
}

/**
 * 여행 상세 화면(넓은 창·좁은 창, 방문지 카드 펼침, 지우기 확인).
 * 넓은 창: 왼쪽 경로 그림, 오른쪽 방문지 타임라인 + 열 아래 고정 줄. 좁은 창(841): 타임라인만 + 화면 아래 고정 줄.
 * 열려 있는(기록 중) 여행도 같은 화면으로 보인다. 결과는 모두 알림으로 띄운다.
 *
 * 저장·지우기는 [AppContainer.scope] 에서 한다: 저장 직후 뒤로 가도 이 화면의 scope 와 함께 취소되지 않게.
 * 여행 지우기는 [onDelete] 로 AppRoot 에 넘긴다(목록으로 돌아간 뒤 알림을 띄운다).
 */
@Composable
fun TripDetailScreen(
    c: AppContainer,
    trip: Trip,
    portrait: Boolean,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onCourse: () -> Unit,
    onDelete: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current
    var selected by remember { mutableStateOf<Int?>(null) }
    var edit by remember { mutableStateOf<Edit?>(null) }
    var listeningId by remember { mutableStateOf<Int?>(null) }
    val latest by rememberUpdatedState(trip)
    fun save(t: Trip, message: String) {
        c.scope.launch { c.session.onTripEdited(t) }
        toast.show(message)
    }

    // 알림이 아래 고정 줄 위에 뜨게 한다
    ToastBottomPadding(BottomBarDefaults.Height + 24.dp)

    val side = if (portrait) 32.dp else 56.dp
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(start = side, end = side, top = 40.dp)) {
            TopBar(
                trip.title,
                onBack = onBack,
                subtitle = Texts.date(trip.startedAt, zone, c.clock.now()),
                onTitleClick = { edit = Edit.Title },
            )
            StatRow(Modifier.padding(top = 28.dp)) {
                // 좁은 창은 값 40/800
                for (s in Texts.detailStats(trip)) StatCell(s.value, s.label, valueStyle = if (portrait) PleosType.Title40 else PleosType.Stat48)
            }
            Row(Modifier.fillMaxWidth().weight(1f).padding(top = 32.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                if (!portrait) {
                    // 넓은 창: 1570 중 경로 그림 780, 타임라인 758. 그림 아래 28 은 고정 줄의 아래 여백과 맞춘다
                    RouteCanvas(
                        trip,
                        Modifier.weight(780f).fillMaxHeight().padding(bottom = 28.dp)
                            .clip(RoundedCornerShape(28.dp)).background(PleosColor.BackgroundLow),
                        selectedStopId = selected,
                    )
                }
                Box(Modifier.weight(758f).fillMaxHeight()) {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(bottom = BottomBarDefaults.Height),
                    ) {
                        if (trip.stops.isEmpty()) item(key = "empty") {
                            Text("들른 곳이 없는 여행이에요", style = PleosType.Body28, color = PleosColor.Text72)
                        }
                        items(trip.stops, key = { it.id }) { stop ->
                            StopCard(
                                trip, stop,
                                expanded = selected == stop.id,
                                listening = listeningId == stop.id,
                                hasVoice = c.voice != null,
                                onClick = { selected = if (selected == stop.id) null else stop.id },
                                onGuide = {
                                    val p = stop.point ?: return@StopCard
                                    val name = trip.nameOf(stop)
                                    scope.launch {
                                        val ok = orNull { c.course.goTo(CoursePlace(name, p)) } ?: false
                                        toast.show(if (ok) Toasts.naviSent(name) else Toasts.NAVI_FAILED)
                                    }
                                },
                                onVoiceMemo = {
                                    val voice = c.voice ?: return@StopCard
                                    if (listeningId != null) return@StopCard
                                    listeningId = stop.id
                                    scope.launch {
                                        val heard = try {
                                            orNull { voice.listen(LISTEN_TIMEOUT_MS) }
                                        } finally {
                                            listeningId = null
                                        }
                                        // 듣는 동안 여행이 바뀌었을 수 있어(기록 중인 여행) 최신 여행에 메모를 넣는다
                                        if (heard.isNullOrBlank()) toast.show(Toasts.MEMO_NOT_HEARD)
                                        else save(TripEditor.setMemo(latest, stop.id, heard), Toasts.MEMO_SAVED)
                                    }
                                },
                                onMemo = { edit = Edit.Memo(stop) },
                                onRename = { edit = Edit.StopName(stop) },
                                onToggleShare = {
                                    val exclude = !stop.excludedFromShare
                                    save(TripEditor.setExcludedFromShare(trip, stop.id, exclude), if (exclude) Toasts.SHARE_EXCLUDED else Toasts.SHARE_INCLUDED)
                                },
                                onDelete = { edit = Edit.DeleteStop(stop) },
                            )
                        }
                        item(key = "delete-trip") { DangerTextButton("이 여행 지우기", { edit = Edit.DeleteTrip }) }
                    }
                    if (!portrait) DetailBottomBar(portrait = false, onShare, onCourse, Modifier.align(Alignment.BottomCenter))
                }
            }
        }
        if (portrait) DetailBottomBar(portrait = true, onShare, onCourse, Modifier.align(Alignment.BottomCenter).padding(horizontal = side))
    }

    when (val e = edit) {
        Edit.Title -> TextEditDialog("여행 제목", trip.title, "비워 두면 방문지 이름으로 정해져요", { edit = null }) { text ->
            // 자동 제목을 그대로 저장하면 이후 방문지 이름이 바뀌어도 제목이 따라가지 않으므로, 글이 같으면 저장하지 않는다
            val t = text.trim()
            val renamed = TripEditor.renameTrip(trip, t)
            if (t != trip.title && renamed != trip) save(renamed, Toasts.TITLE_CHANGED)
        }
        is Edit.StopName -> TextEditDialog("장소 이름", trip.nameOf(e.stop), null, { edit = null }) { text ->
            val renamed = TripEditor.renameStop(trip, e.stop.id, text)
            if (renamed != trip && text.trim() != trip.nameOf(e.stop)) save(renamed, Toasts.NAME_CHANGED)
        }
        is Edit.Memo -> TextEditDialog("메모", e.stop.memo ?: "", "비워 두면 메모를 지워요", { edit = null }) { text ->
            val memo = text.trim().ifEmpty { null }
            if (memo != e.stop.memo) save(TripEditor.setMemo(trip, e.stop.id, memo), if (memo == null) Toasts.MEMO_DELETED else Toasts.MEMO_SAVED)
        }
        Edit.DeleteTrip -> ConfirmDialog("이 여행을 지울까요?", "지우면 되돌릴 수 없어요.", "지우기", { edit = null }, destructive = true) {
            onDelete()
        }
        is Edit.DeleteStop -> {
            val name = trip.nameOf(e.stop)
            ConfirmDialog(Texts.deleteStopQuestion(name), "이 여행에서 빠지고, 되돌릴 수 없어요.", "지우기", { edit = null }, destructive = true) {
                if (selected == e.stop.id) selected = null
                save(TripEditor.deleteStop(trip, e.stop.id), Toasts.stopDeleted(name))
            }
        }
        null -> Unit
    }
}

/** 실패하면 null. 화면을 떠나 취소된 경우는 그대로 취소한다(떠난 뒤 실패 알림이 뜨지 않게) */
private suspend fun <T> orNull(block: suspend () -> T): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

/** 아래 고정 줄: [코스로 다시 가기](좁은 창 "다시 가기") / [휴대폰으로 보내기] */
@Composable
private fun DetailBottomBar(portrait: Boolean, onShare: () -> Unit, onCourse: () -> Unit, modifier: Modifier) {
    BottomBar(
        primaryText = "휴대폰으로 보내기",
        onPrimary = onShare,
        modifier = modifier,
        secondaryText = if (portrait) "다시 가기" else "코스로 다시 가기",
        onSecondary = onCourse,
    )
}

/**
 * 방문지 카드. 첫 줄 번호 점 / 이름 + 칩 / 머문 시간(32/700), 둘째 줄 시각·지역, 메모가 있으면 한 줄.
 * 잠깐 정차는 낮은 카드(#131417): 회색 점, 이름 28, 오른쪽 시간 28.
 * 펼치면 선택 테두리와 버튼 2줄: [여기로 안내] [메모 말하기] [메모 쓰기] / 이름 바꾸기 · 공유에서 빼기 · 지우기.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StopCard(
    trip: Trip,
    stop: Stop,
    expanded: Boolean,
    listening: Boolean,
    hasVoice: Boolean,
    onClick: () -> Unit,
    onGuide: () -> Unit,
    onVoiceMemo: () -> Unit,
    onMemo: () -> Unit,
    onRename: () -> Unit,
    onToggleShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val brief = stop.kind == StopKind.BRIEF
    val name = trip.nameOf(stop)
    val cardModifier = if (brief) {
        Modifier.pressableCard(onClick, radius = 24.dp, color = PleosColor.BackgroundLow, pressedColor = PleosColor.Background, selected = expanded, contentPadding = CardPadding.Brief)
    } else {
        Modifier.stopCard(onClick, selected = expanded)
    }
    Column(Modifier.fillMaxWidth().then(cardModifier)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            when (stop.kind) {
                StopKind.VISIT -> NumberDot("${trip.visits.indexOfFirst { it.id == stop.id } + 1}")
                StopKind.LODGING -> NumberDot("숙")
                StopKind.BRIEF -> IdleDot()
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (brief) 0.dp else 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        name,
                        style = if (brief) PleosType.Body28 else PleosType.Title36,
                        color = if (brief) PleosColor.Text72 else PleosColor.Text,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (stop.rainy) RainChip()
                    if (stop.excludedFromShare) MutedChip("공유 제외")
                }
                Text(Texts.stopClock(stop, zone), style = PleosType.Body26, color = PleosColor.Text72, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                Texts.stay(stop),
                style = if (brief) PleosType.Body28 else PleosType.Title32,
                color = if (brief) PleosColor.Text72 else PleosColor.Text,
                maxLines = 1,
                softWrap = false,
            )
        }
        stop.memo?.let {
            Text(
                "“$it”",
                style = PleosType.Body28,
                color = PleosColor.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 20.dp),
            )
        }
        if (expanded) {
            FlowRow(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (stop.point != null) PrimaryButton("여기로 안내", onGuide, height = 80.dp)
                if (hasVoice) SecondaryButton("메모 말하기", onVoiceMemo, height = 80.dp, busyText = if (listening) "듣고 있어요" else null)
                SecondaryButton("메모 쓰기", onMemo, height = 80.dp)
            }
            // 글자 버튼의 좌우 여백 24 만큼 왼쪽으로 당겨 글자 시작선을 위 버튼 줄과 맞춘다(왼쪽 여백 -24)
            FlowRow(Modifier.padding(top = 8.dp).offset(x = (-24).dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton("이름 바꾸기", onRename)
                TextButton(if (stop.excludedFromShare) "공유에 넣기" else "공유에서 빼기", onToggleShare)
                DangerTextButton("지우기", onDelete)
            }
        }
    }
}
