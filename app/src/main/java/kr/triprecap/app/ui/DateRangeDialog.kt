package kr.triprecap.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kr.triprecap.app.domain.CalendarMonth
import kr.triprecap.app.domain.DateRangePick
import kr.triprecap.app.domain.DayMark
import kr.triprecap.app.domain.SearchPeriod
import java.time.LocalDate

/**
 * 날짜 칸 높이 80: 디자인 규칙의 터치 대상 최소 높이(80)와 같다. 폭은 대화상자 안쪽 680 / 7 = 약 97.
 * 6주 고정(42칸)이라 달마다 대화상자 높이가 바뀌지 않는다.
 */
private val CELL_HEIGHT = 80.dp

/** 고른 날짜 원(지름 64)과 범위 띠(높이 64). 위아래 8씩 남겨 이어지는 주의 띠가 붙어 보이지 않게 한다 */
private val MARK_SIZE = 64.dp

/** 시작일·종료일 사이 띠: 켜진 선택 칩 색(#E0E1E6) 24%. 카드(#313236) 위에서 흰 글자 대비 약 6.8:1 */
private val RangeBand = PleosColor.ChipOn.copy(alpha = 0.24f)

/**
 * 검색 기간 대화상자. 제목 "기간" + 안내 한 줄, 달 넘김 줄, 요일 줄, 날짜 칸 6×7, 아래 [닫기] / [찾기].
 * [from]·[to] 는 이미 직접 고른 기간(없으면 null, 올해·작년 칩의 한 해 범위도 null 로 준다).
 * 기간이 있으면 제목 줄 오른쪽에 [기간 지우기] 를 둔다. [onPick] 다음 [onDismiss] 를 부른다.
 *
 * 세로 배치(대화상자 위 끝 기준, 여백 40 포함): 제목 줄 40~120, 안내 128~164, 달 넘김 188~268,
 * 요일 284~324, 날짜 칸 332~812(줄마다 80), 버튼 852~940, 전체 높이 980.
 */
@Composable
fun DateRangeDialog(
    from: LocalDate?,
    to: LocalDate?,
    today: LocalDate,
    onDismiss: () -> Unit,
    onPick: (from: LocalDate?, to: LocalDate?) -> Unit,
) {
    var pick by remember { mutableStateOf(DateRangePick(from, to)) }
    var month by remember { mutableStateOf(CalendarMonth.initial(from, today)) }
    val apply = { range: Pair<LocalDate, LocalDate>? -> onPick(range?.first, range?.second); onDismiss() }

    ContentDialog(
        title = "기간",
        onDismiss = onDismiss,
        titleTrailing = { if (from != null) TextButton("기간 지우기", { apply(null) }) },
        confirm = { m -> PrimaryButton("찾기", { apply(pick.range) }, m, height = 88.dp, enabled = pick.range != null) },
    ) {
        Spacer(Modifier.height(8.dp))
        Text(
            SearchPeriod.hint(pick, today),
            style = PleosType.Body26,
            color = PleosColor.Text72,
            maxLines = 1,
            modifier = Modifier.height(36.dp),
        )
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth().height(80.dp), verticalAlignment = Alignment.CenterVertically) {
            CircleIconButton(AppIcon.Back, "이전 달", { month = month.minusMonths(1) })
            Text(
                CalendarMonth.title(month),
                style = PleosType.Title32,
                color = PleosColor.Text,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            CircleIconButton(AppIcon.ChevronRight, "다음 달", { month = month.plusMonths(1) }, enabled = CalendarMonth.canGoNext(month, today))
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            CalendarMonth.WEEKDAYS.forEach { w ->
                Text(w, style = PleosType.Body26, color = PleosColor.Text72, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(8.dp))
        Column {
            CalendarMonth.cells(month).chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth().height(CELL_HEIGHT)) {
                    week.forEachIndexed { col, day ->
                        val cell = Modifier.weight(1f).height(CELL_HEIGHT)
                        if (day == null) {
                            Spacer(cell)
                        } else {
                            DayCell(
                                day = day,
                                mark = pick.markOf(day),
                                capStart = col == 0 || day.dayOfMonth == 1,
                                capEnd = col == 6 || day.dayOfMonth == day.lengthOfMonth(),
                                enabled = !day.isAfter(today),
                                label = SearchPeriod.day(day, today),
                                onClick = { pick = pick.tap(day, today) },
                                modifier = cell,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 날짜 칸 하나. 칸 전체(약 97×80)가 터치 대상이다.
 * 시작일·종료일·하루는 지름 64 원(#E0E1E6, 글자 #131417), 그 사이는 띠([RangeBand]).
 * 띠는 주의 첫·끝 칸과 달의 첫·끝 날에서 둥글게 끝낸다([capStart], [capEnd]).
 * 눌림은 숫자 원에만 준다(95% 축소 + 바탕색): 띠까지 줄이면 이웃 칸과 띠가 끊겨 보인다.
 * 오늘 이후 날짜는 누를 수 없고 글자를 흐리게(Text50) 그린다. 누를 수 없는 버튼 글자와 같은 색이다.
 */
@Composable
private fun DayCell(
    day: LocalDate,
    mark: DayMark,
    capStart: Boolean,
    capEnd: Boolean,
    enabled: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val endpoint = mark == DayMark.SINGLE || mark == DayMark.START || mark == DayMark.END
    val circle = when {
        endpoint && pressed -> PleosColor.ChipOnPressed
        endpoint -> PleosColor.ChipOn
        pressed -> PleosColor.ButtonBasicPressed
        else -> null
    }
    val fg = when {
        endpoint -> PleosColor.OnAccent
        !enabled -> PleosColor.Text50
        else -> PleosColor.Text
    }
    Box(
        modifier
            .drawBehind {
                drawBand(
                    left = mark == DayMark.MIDDLE || mark == DayMark.END,
                    right = mark == DayMark.MIDDLE || mark == DayMark.START,
                    capLeft = capStart,
                    capRight = capEnd,
                )
            }
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = label
                selected = mark != DayMark.NONE
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .pressScale(source)
                .size(MARK_SIZE)
                .then(if (circle != null) Modifier.background(circle, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(day.dayOfMonth.toString(), style = PleosType.Body28Strong, color = fg, maxLines = 1, softWrap = false)
        }
    }
}

/**
 * 칸 가운데를 기준으로 왼쪽 반·오른쪽 반에 띠를 그린다. [capLeft]·[capRight] 면 그 쪽은 반원(지름 64)으로 끝낸다.
 * 반투명 색이라 두 도형이 겹치면 겹친 곳이 진해지므로, 한 쪽에는 사각형과 반원 중 하나만 그린다.
 */
private fun DrawScope.drawBand(left: Boolean, right: Boolean, capLeft: Boolean, capRight: Boolean) {
    val h = MARK_SIZE.toPx()
    val top = (size.height - h) / 2
    val cx = size.width / 2
    val arcTopLeft = Offset(cx - h / 2, top)
    if (left) {
        if (capLeft) drawArc(RangeBand, startAngle = 90f, sweepAngle = 180f, useCenter = true, topLeft = arcTopLeft, size = Size(h, h))
        else drawRect(RangeBand, Offset(0f, top), Size(cx, h))
    }
    if (right) {
        if (capRight) drawArc(RangeBand, startAngle = -90f, sweepAngle = 180f, useCenter = true, topLeft = arcTopLeft, size = Size(h, h))
        else drawRect(RangeBand, Offset(cx, top), Size(size.width - cx, h))
    }
}
