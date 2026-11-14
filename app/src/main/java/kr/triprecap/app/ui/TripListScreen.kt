package kr.triprecap.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.triprecap.app.AppContainer
import kr.triprecap.app.domain.SearchPeriod
import kr.triprecap.app.domain.Texts
import kr.triprecap.app.domain.Toasts
import kr.triprecap.app.domain.Trip
import kr.triprecap.app.domain.TripLists
import kr.triprecap.app.domain.TripSearch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private val zone: ZoneId = ZoneId.of("Asia/Seoul")

/**
 * 여행 목록 화면과 검색 화면.
 * - 검색을 켜지 않았을 때: "1년 전 이맘때" 카드(있을 때만) → 계절 묶음 머리말 → 카드
 * - 검색을 켰을 때: 입력칸 + 조건 칩 5개(2명 이상, 비 온 날, 올해, 작년, 기간) → "여행 N번" → 카드(최신순, 묶지 않음)
 * - 보이는 여행: 분류가 여행인 것 중 닫힌 여행과, 열려 있어도 방문지가 생긴 여행(출근길 같은 진행 중 드라이브는 감춘다).
 *   일상 이동과 묻는 중인 이동은 보이지 않는다 (TripLists.listed)
 * - 일상 이동이 있으면 맨 아래에 "일상 이동 n건 · 7일 뒤 자동으로 지워요" 줄. 누르면 일상 이동 화면([onDailyMoves])
 */
@Composable
fun TripListScreen(
    c: AppContainer,
    trips: List<Trip>,
    portrait: Boolean,
    onOpen: (Long) -> Unit,
    onSettings: () -> Unit,
    onDailyMoves: () -> Unit,
) {
    val search = remember { TripSearch(zone) }
    var query by remember { mutableStateOf(TripSearch.Query()) }
    var searching by remember { mutableStateOf(false) }
    val recording by c.settings.recordingEnabled.collectAsStateWithLifecycle()
    val now = remember(trips) { c.clock.now() }
    val today = remember(now) { Instant.ofEpochMilli(now).atZone(zone).toLocalDate() }

    val visible = remember(trips) { TripLists.listed(trips) }
    val dailyCount = remember(trips) { TripLists.daily(trips).size }
    val results = remember(visible, query) { search.search(visible, query) }
    val groups = remember(visible) { search.seasonGroups(visible) }
    val yearAgo = remember(visible, today) { search.oneYearAgo(visible.filter { it.closed }, today) }

    val listState = rememberLazyListState()
    val itemKeys = if (searching) results.map { it.id } else listOfNotNull(yearAgo?.id) + groups.flatMap { g -> g.trips.map { it.id } }
    KeepTopOnChange(listState, itemKeys, filter = searching to query)

    Column(Modifier.fillMaxSize().padding(start = side(portrait), end = side(portrait), top = 40.dp)) {
        TopBar("왔다감", onBack = null) {
            SelectChip("검색", searching, onClick = {
                searching = !searching
                if (!searching) query = TripSearch.Query()
            }, showMark = false)
            CircleIconButton(AppIcon.Settings, "설정", onSettings)
        }
        if (searching) SearchPanel(query, today, Modifier.padding(top = 32.dp)) { query = it }

        if (visible.isEmpty()) {
            EmptyState(recording, onTurnOn = { c.settings.setRecordingEnabled(true) }, modifier = Modifier.weight(1f))
            if (dailyCount > 0) DailyMovesRow(dailyCount, onDailyMoves, Modifier.padding(bottom = 48.dp))
            return@Column
        }

        val open = { t: Trip -> onOpen(t.id) }
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(top = 32.dp, bottom = 48.dp),
        ) {
            if (searching) {
                searchResults(results, now, portrait, open, onClear = { query = TripSearch.Query() })
            } else {
                if (yearAgo != null) item(key = "year-ago") { TripCard(yearAgo, now, portrait, yearAgo = true, onClick = { open(yearAgo) }) }
                groups.forEachIndexed { i, g ->
                    item(key = "h-${g.label}") {
                        SectionHeader(seasonTitle(g.label), "여행 ${g.trips.size}번", Modifier.padding(top = if (i == 0 && yearAgo == null) 0.dp else 16.dp))
                    }
                    items(g.trips, key = { "t-${it.id}" }) { t -> TripCard(t, now, portrait, onClick = { open(t) }) }
                }
                if (dailyCount > 0) item(key = "daily") { DailyMovesRow(dailyCount, onDailyMoves, Modifier.padding(top = 16.dp)) }
            }
        }
    }
}

/** 직전에 그린 목록의 항목 구성과 검색 조건. 바뀌었는지 비교할 때만 쓰므로 Compose 상태로 두지 않는다 */
private class ShownList(var keys: List<Long>, var filter: Any)

/**
 * 목록 항목 구성이 바뀔 때 스크롤 위치를 정한다.
 * - 검색을 켜고 끄거나 조건을 바꾸면 맨 위에서 시작한다.
 * - 여행이 늘거나 줄었을 때, 바뀌기 전에 맨 위였으면 맨 위를 유지한다. 아래로 스크롤해 둔 상태는 건드리지 않는다.
 *
 * LazyColumn 은 key 가 있는 항목이 앞에 끼어들면 첫 항목의 key 를 따라가므로, 맨 위에 있던 항목이 한 칸 아래로 밀린 위치를 유지한다.
 * 그래서 "1년 전 이맘때" 카드처럼 key 가 있는 항목이 맨 앞에 끼어들면 그 항목은 화면 위쪽 밖에 놓인다. requestScrollToItem 은 다음 측정 한 번에 한해 key 를 따라가지 않고 지정한 위치를 쓴다.
 * SideEffect 는 구성이 반영된 뒤, 새 항목으로 측정하기 전에 실행되므로 이때 읽는 스크롤 위치는 바뀌기 전 목록의 위치다.
 */
@Composable
private fun KeepTopOnChange(state: LazyListState, keys: List<Long>, filter: Any) {
    val shown = remember { ShownList(keys, filter) }
    SideEffect {
        if (shown.keys == keys && shown.filter == filter) return@SideEffect
        val atTop = state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0
        if (shown.filter != filter || atTop) state.requestScrollToItem(0)
        shown.keys = keys
        shown.filter = filter
    }
}

/** 좌우 여백: 넓은 창 56, 좁은 창(841) 32 */
private fun side(portrait: Boolean) = if (portrait) 32.dp else 56.dp

/** TripSearch 의 "2026 가을" → 계절 묶음 머리말 "2026년 가을" */
private fun seasonTitle(label: String): String = label.replaceFirst(Regex("^(\\d+) "), "$1년 ")

private fun LazyListScope.searchResults(results: List<Trip>, now: Long, portrait: Boolean, onOpen: (Trip) -> Unit, onClear: () -> Unit) {
    if (results.isEmpty()) {
        item(key = "no-result") {
            Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("조건에 맞는 여행이 없어요", style = PleosType.Title32, color = PleosColor.Text)
                Spacer(Modifier.height(32.dp))
                SecondaryButton("조건 지우기", onClear, height = 80.dp)
            }
        }
        return
    }
    item(key = "count") { SectionHeader("여행 ${results.size}번", null) }
    items(results, key = { "r-${it.id}" }) { t -> TripCard(t, now, portrait, onClick = { onOpen(t) }) }
}

/** 머리말: 28/500 Text72 + (보조) 28/400 Text50, 간격 16. 아래 카드까지 20 (목록 간격 16 + 4) */
@Composable
private fun SectionHeader(title: String, count: String?, modifier: Modifier = Modifier) {
    Row(modifier.padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(title, style = PleosType.Body28Strong, color = PleosColor.Text72, modifier = Modifier.alignByBaseline().semantics { heading() })
        if (count != null) Text(count, style = PleosType.Body28, color = PleosColor.Text50, modifier = Modifier.alignByBaseline())
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchPanel(query: TripSearch.Query, today: LocalDate, modifier: Modifier = Modifier, onChange: (TripSearch.Query) -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        InputField(
            query.text,
            { onChange(query.copy(text = it)) },
            Modifier.fillMaxWidth(),
            placeholder = "장소나 지역 이름",
            leadingIcon = AppIcon.Search,
            imeAction = ImeAction.Search,
        )
        // 좁은 창(841)에서 칩 5개가 한 줄에 들어가지 않으면 다음 줄로 넘긴다
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SelectChip("2명 이상", query.withCompanions, { onChange(query.copy(withCompanions = !query.withCompanions)) })
            SelectChip("비 온 날", query.rainy, { onChange(query.copy(rainy = !query.rainy)) })
            YearChip("올해", today.year, query, onChange)
            YearChip("작년", today.year - 1, query, onChange)
            PeriodChip(query, today, onChange)
        }
    }
}

/**
 * 한 해 범위 칩(1월 1일~12월 31일). 다른 해나 직접 고른 기간이 있으면 그 해로 바꾼다(올해·작년·기간은 함께 켜지지 않는다).
 * 직접 고른 기간이 1월 1일에 시작해도 켜지지 않도록 시작일·종료일을 모두 본다.
 */
@Composable
private fun YearChip(text: String, year: Int, query: TripSearch.Query, onChange: (TripSearch.Query) -> Unit) {
    val on = SearchPeriod.fullYear(query.from, query.to) == year
    SelectChip(text, on, {
        onChange(if (on) query.copy(from = null, to = null) else query.copy(from = LocalDate.of(year, 1, 1), to = LocalDate.of(year, 12, 31)))
    })
}

/**
 * 기간 칩. 직접 고른 기간이 없으면 "기간"(꺼짐), 있으면 그 범위 글자(켜짐). 켜져 있어도 누르면 대화상자를 연다:
 * 기간을 바꾸거나 지우는 일은 대화상자 안에서 한다.
 */
@Composable
private fun PeriodChip(query: TripSearch.Query, today: LocalDate, onChange: (TripSearch.Query) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val custom = SearchPeriod.custom(query.from, query.to)
    SelectChip(SearchPeriod.chipLabel(query.from, query.to, today), custom != null, { open = true })
    if (open) {
        DateRangeDialog(custom?.first, custom?.second, today, onDismiss = { open = false }) { from, to ->
            onChange(query.copy(from = from, to = to))
        }
    }
}

/**
 * 목록 맨 아래 일상 이동 줄(높이 96, 누를 수 있는 카드): "일상 이동 n건" 28/500 + " · 7일 뒤 자동으로 지워요" 28 Text72 + 꺾쇠.
 * 설정의 시연 도구 펼침 행과 같은 모양이다
 */
@Composable
private fun DailyMovesRow(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = TripLists.dailyFooter(count)
    val head = label.substringBefore(" · ")
    Row(
        modifier
            .fillMaxWidth()
            .height(96.dp)
            .pressableCard(onClick, radius = 28.dp, contentPadding = PaddingValues(horizontal = 40.dp), onClickLabel = "일상 이동 보기"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(head, style = PleosType.Body28Strong, color = PleosColor.Text, maxLines = 1, softWrap = false)
        Text(
            label.removePrefix(head),
            style = PleosType.Body28,
            color = PleosColor.Text72,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        AppIconView(AppIcon.ChevronRight, color = PleosColor.Text72)
    }
}

@Composable
private fun EmptyState(recording: Boolean, onTurnOn: () -> Unit, modifier: Modifier = Modifier) {
    val toast = LocalToast.current
    Column(
        modifier.fillMaxWidth().padding(bottom = 120.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!recording) {
            Text("여행 기록이 꺼져 있어요", style = PleosType.Title36, color = PleosColor.Text, textAlign = TextAlign.Center)
            Spacer(Modifier.height(40.dp))
            PrimaryButton("기록 켜기", { onTurnOn(); toast.show(Toasts.RECORDING_ON) })
        } else {
            Text("여행을 다녀오면 여기에 정리돼요", style = PleosType.Title36, color = PleosColor.Text, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Text("차가 출발하면 자동으로 기록해요. 따로 누를 건 없어요.", style = PleosType.Body28, color = PleosColor.Text72, textAlign = TextAlign.Center)
        }
    }
}

/**
 * 목록 카드(높이 168): 첫 줄 제목 + 칩, 둘째 줄 날짜·방문·인원·거리, 오른쪽 미니 경로 그림 240×112(좁은 창에서는 숨긴다).
 * [yearAgo] 면 앰버 테두리 + 제목 앞 "1년 전 이맘때" 칩. 이 화면에서 테두리 카드는 이것 하나다.
 */
@Composable
private fun TripCard(trip: Trip, now: Long, portrait: Boolean, yearAgo: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 168.dp).listCard(onClick, selected = yearAgo),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(40.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (yearAgo) AmberChip("1년 전 이맘때")
                Text(
                    trip.title,
                    style = PleosType.Title36,
                    color = PleosColor.Text,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (trip.rainy) RainChip()
                if (trip.isNew && !yearAgo) AmberChip("새 여행")
                if (!trip.closed) RecordingMark()
            }
            Text(
                Texts.listMeta(trip, zone, now),
                style = PleosType.Body28,
                color = PleosColor.Text72,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!portrait) {
            RouteCanvas(
                trip,
                Modifier.size(width = 240.dp, height = 112.dp).clip(RoundedCornerShape(16.dp)).background(PleosColor.BackgroundLow),
                compact = true,
            )
        }
    }
}

/** 기록 중인 열린 여행: 앰버 점(지름 12) + "기록 중" 24/500 Text72. 테두리는 쓰지 않는다 */
@Composable
private fun RecordingMark() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Dot(12.dp, PleosColor.Accent)
        Text("기록 중", style = PleosType.Label24, color = PleosColor.Text72, maxLines = 1, softWrap = false)
    }
}
