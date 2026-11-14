package kr.triprecap.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ─────────────────────────────────────────────────────────────────────────────
// 눌림 효과
// ─────────────────────────────────────────────────────────────────────────────

/** 눌림 축소 비율 */
object PressScale {
    /** 버튼·칩·아이콘 버튼. Pleos 가이드 shrink effect 95% */
    const val BUTTON = 0.95f
    /**
     * 폭이 큰 카드(목록 카드, 방문지 카드). 폭 1570 카드를 95% 로 줄이면 좌우 끝이 39px 씩 안쪽으로 움직여
     * 카드 전체가 흔들려 보인다. 98% 면 16px 이다.
     */
    const val CARD = 0.98f
}

/**
 * 누르는 동안 [scale] 로 줄인다(100ms tween). Pleos 가이드의 shrink effect.
 * ripple 은 쓰지 않는다: 이 modifier 와 함께 쓰는 clickable 은 `indication = null` 로 둔다.
 * 바탕색 변경은 각 컴포넌트가 [interactionSource] 의 눌림 상태로 따로 한다.
 */
fun Modifier.pressScale(interactionSource: InteractionSource, scale: Float = PressScale.BUTTON): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) scale else 1f, tween(durationMillis = 100), label = "pressScale")
    graphicsLayer { scaleX = s; scaleY = s }
}

// ─────────────────────────────────────────────────────────────────────────────
// 버튼
// ─────────────────────────────────────────────────────────────────────────────

private class PillColors(
    val container: Color,
    val pressed: Color,
    val content: Color,
    val disabledContainer: Color,
    val disabledContent: Color,
)

private val PrimaryColors = PillColors(PleosColor.Accent, PleosColor.AccentPressed, PleosColor.OnAccent, PleosColor.SurfaceAccent, PleosColor.Text50)
private val SecondaryColors = PillColors(PleosColor.ButtonBasic, PleosColor.ButtonBasicPressed, PleosColor.Text, PleosColor.ButtonBasic, PleosColor.Text50)
private val TextColors = PillColors(Color.Transparent, PleosColor.ButtonBasic, PleosColor.Text72, Color.Transparent, PleosColor.Text50)
private val DangerTextColors = PillColors(Color.Transparent, PleosColor.ButtonBasic, PleosColor.Danger, Color.Transparent, PleosColor.Text50)
private val DangerFillColors = PillColors(PleosColor.DangerFill, PleosColor.DangerFillPressed, PleosColor.OnAccent, PleosColor.SurfaceAccent, PleosColor.Text50)

/** 버튼 높이별 좌우 여백: 높이 112 → 48, 88 → 40, 80 → 32 (docs/design-rules.md "버튼") */
private fun pillPadding(height: Dp): Dp = when {
    height >= 112.dp -> 48.dp
    height >= 88.dp -> 40.dp
    else -> 32.dp
}

/**
 * pill 버튼 공통. 글자는 한 줄로만 쓴다(줄바꿈하지 않고 넘치면 말줄임).
 * [busyText] 가 있으면 그 글자를 보이고 누를 수 없다. 색은 그대로 둬서 "진행 중" 으로 읽히게 한다.
 */
@Composable
private fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    height: Dp,
    enabled: Boolean,
    busyText: String?,
    colors: PillColors,
    style: TextStyle,
    horizontalPadding: Dp = pillPadding(height),
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val busy = busyText != null
    val clickable = enabled && !busy
    val bg = when {
        !enabled -> colors.disabledContainer
        pressed -> colors.pressed
        else -> colors.container
    }
    val fg = if (enabled) colors.content else colors.disabledContent
    Box(
        modifier
            .pressScale(source)
            .height(height)
            .defaultMinSize(minWidth = height)
            .background(bg, RoundedCornerShape(50))
            .clickable(interactionSource = source, indication = null, enabled = clickable, role = Role.Button, onClick = onClick)
            .padding(horizontal = horizontalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(busyText ?: text, style = style, color = fg, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * 주 행동 버튼(앰버, 화면당 1개). 글자 32/700 #131417.
 * 높이: 112 아래 고정 줄, 88 대화상자, 80 카드 안.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 112.dp,
    enabled: Boolean = true,
    busyText: String? = null,
) = PillButton(text, onClick, modifier, height, enabled, busyText, PrimaryColors, PleosType.Title32)

/** 보조 버튼(basic 바탕 흰색 8%, 글자 28/500 흰색). 높이는 같은 줄의 [PrimaryButton] 과 맞춘다 */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 112.dp,
    enabled: Boolean = true,
    busyText: String? = null,
) = PillButton(text, onClick, modifier, height, enabled, busyText, SecondaryColors, PleosType.Body28Strong)

/** 글자 버튼(바탕 없음, 28/500 Text72, 높이 80, 좌우 24). 누르는 동안만 basic 바탕을 깐다 */
@Composable
fun TextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busyText: String? = null,
) = PillButton(text, onClick, modifier, 80.dp, enabled, busyText, TextColors, PleosType.Body28Strong, horizontalPadding = 24.dp)

/** 지우기 글자 버튼(#FF7A5C). 누르면 [ConfirmDialog] (destructive = true) 로 한 번 더 묻는다 */
@Composable
fun DangerTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) = PillButton(text, onClick, modifier, 80.dp, enabled, null, DangerTextColors, PleosType.Body28Strong, horizontalPadding = 24.dp)

/** 지우기 확인 버튼(바탕 #FF4C28, 글자 #131417 32/700). 대화상자 안에서만 쓴다 */
@Composable
fun DangerFillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 88.dp,
    enabled: Boolean = true,
) = PillButton(text, onClick, modifier, height, enabled, null, DangerFillColors, PleosType.Title32)

/**
 * 원형 아이콘 버튼(기본 80×80, basic 바탕). [contentDescription] 은 화면 읽기 프로그램이 읽는 이름("뒤로", "설정").
 * 바탕 없는 형태(제목 옆 연필)는 `background = Color.Transparent, tint = PleosColor.Text72` 로 쓴다.
 */
@Composable
fun CircleIconButton(
    icon: AppIcon,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    background: Color = PleosColor.ButtonBasic,
    tint: Color = PleosColor.Text,
    size: Dp = 80.dp,
    iconSize: Dp = icon.viewBox.dp,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val pressedBg = if (background == Color.Transparent) PleosColor.ButtonBasic else PleosColor.ButtonBasicPressed
    Box(
        modifier
            .pressScale(source)
            .size(size)
            .background(if (pressed) pressedBg else background, CircleShape)
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        AppIconView(icon, size = iconSize, color = if (enabled) tint else PleosColor.Text50)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// TopBar
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 화면 위 줄(높이 80). 바깥 여백(위 40, 좌우 56/32)은 화면이 준다.
 * - [onBack] 이 있으면: 뒤로 아이콘 버튼 + 간격 24 + 제목.
 * - [onBack] 이 null 이면 목록용: 제목("왔다감")만 왼쪽에, [trailing] 은 오른쪽 끝(간격 16).
 * - [onTitleClick] 이 있으면 제목 옆(간격 12)에 연필 버튼을 두고 제목을 눌러도 같은 일을 한다.
 * - [subtitle] 은 제목 아래 26/400 Text72 한 줄. 뒤로 버튼이 있으면 제목 시작선(왼쪽 104)에 맞춘다.
 */
@Composable
fun TopBar(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onTitleClick: (() -> Unit)? = null,
    titleClickLabel: String = "제목 바꾸기",
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(80.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                CircleIconButton(AppIcon.Back, "뒤로", onBack)
                Spacer(Modifier.width(24.dp))
            }
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                val titleSource = remember { MutableInteractionSource() }
                Text(
                    title,
                    style = PleosType.Title40,
                    color = PleosColor.Text,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .semantics { heading() }
                        .then(
                            if (onTitleClick != null) Modifier.clickable(titleSource, indication = null, onClickLabel = titleClickLabel, onClick = onTitleClick)
                            else Modifier,
                        ),
                )
                if (onTitleClick != null) {
                    Spacer(Modifier.width(12.dp))
                    CircleIconButton(AppIcon.Pencil, titleClickLabel, onTitleClick, background = Color.Transparent, tint = PleosColor.Text72)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically, content = trailing)
        }
        if (subtitle != null) {
            Text(
                subtitle,
                style = PleosType.Body26,
                color = PleosColor.Text72,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = if (onBack != null) 104.dp else 0.dp, top = 4.dp),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 선택 칩, 스위치
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 선택 칩(높이 80, pill, 28/500, 좌우 32).
 * 켜짐 = 바탕 #E0E1E6 + 글자 #131417 + 체크, 꺼짐 = basic + 흰 글자 + 빈 원.
 * 켜짐·꺼짐 모두 28 아이콘 + 간격 10 + 같은 글자 스타일이라 폭이 바뀌지 않는다.
 * [showMark] = false 면 아이콘 없이 바탕색만 바뀐다(목록 TopBar 의 [검색] 켜짐 표시).
 */
@Composable
fun SelectChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showMark: Boolean = true,
    enabled: Boolean = true,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val bg = when {
        selected && pressed -> PleosColor.ChipOnPressed
        selected -> PleosColor.ChipOn
        pressed -> PleosColor.ButtonBasicPressed
        else -> PleosColor.ButtonBasic
    }
    val fg = if (selected) PleosColor.OnAccent else PleosColor.Text
    Row(
        modifier
            .pressScale(source)
            .height(80.dp)
            .background(bg, RoundedCornerShape(50))
            .toggleable(value = selected, interactionSource = source, indication = null, enabled = enabled, role = Role.Checkbox, onValueChange = { onClick() })
            .padding(horizontal = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (showMark) {
            if (selected) AppIconView(AppIcon.Check, color = PleosColor.OnAccent)
            else AppIconView(AppIcon.Circle, color = PleosColor.Text50)
        }
        Text(text, style = PleosType.Body28Strong, color = fg, maxLines = 1, softWrap = false)
    }
}

/**
 * 스위치 그림(트랙 104×56 pill, 원 지름 44).
 * 켜짐 = 트랙 앰버 + 원 #131417 오른쪽, 꺼짐 = 트랙 #44464E + 원 #A4A8AE 왼쪽.
 * [onCheckedChange] 가 null 이면 그림만 그린다([SwitchRow] 안). 혼자 쓸 때는 터치 대상을 104×80 으로 넓힌다.
 * [interactionSource] 를 주면 그 눌림에 맞춰 트랙이 95% 로 줄어든다.
 */
@Composable
fun WatSwitch(
    checked: Boolean,
    modifier: Modifier = Modifier,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    contentDescription: String? = null,
    interactionSource: InteractionSource? = null,
) {
    val ownSource = remember { MutableInteractionSource() }
    val source = interactionSource ?: ownSource
    val track = @Composable {
        Box(
            Modifier
                .pressScale(source)
                .size(width = 104.dp, height = 56.dp)
                .background(if (checked) PleosColor.Accent else PleosColor.SurfaceAccent, RoundedCornerShape(50)),
        ) {
            Box(
                Modifier
                    .offset(x = if (checked) 54.dp else 6.dp, y = 6.dp)
                    .size(44.dp)
                    .background(if (checked) PleosColor.OnAccent else PleosColor.Idle, CircleShape),
            )
        }
    }
    if (onCheckedChange == null) {
        Box(modifier) { track() }
    } else {
        Box(
            modifier
                .size(width = 104.dp, height = 80.dp)
                .toggleable(value = checked, interactionSource = ownSource, indication = null, role = Role.Switch, onValueChange = onCheckedChange)
                .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier),
            contentAlignment = Alignment.Center,
        ) { track() }
    }
}

/**
 * 스위치 행(최소 높이 112, 좌우 40 위아래 16). 행 전체가 눌린다. 접근성 role = Switch, 켜짐/꺼짐 상태를 읽는다.
 * 바탕은 없다: 설정처럼 카드(`Modifier.card(28.dp)`) 안에 [ListDivider] 로 나눠 넣는다.
 * 코스 화면처럼 행 하나가 카드이면 `modifier = Modifier.card(24.dp)` 와 [leading](잠깐 정차 점)을 준다.
 */
@Composable
fun SwitchRow(
    title: String,
    detail: String?,
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) {
    val source = remember { MutableInteractionSource() }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 112.dp)
            .toggleable(value = checked, interactionSource = source, indication = null, enabled = enabled, role = Role.Switch, onValueChange = { onToggle() })
            .padding(horizontal = 40.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(20.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = PleosType.Body28Strong, color = PleosColor.Text)
            if (detail != null) Text(detail, style = PleosType.Body26, color = PleosColor.Text72)
        }
        Spacer(Modifier.width(32.dp))
        WatSwitch(checked, interactionSource = source)
    }
}

/** 카드 안 행 사이 구분선(1px basic, 좌우 40 들여씀) */
@Composable
fun ListDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(horizontal = 40.dp).height(1.dp).background(PleosColor.ButtonBasic))
}

// ─────────────────────────────────────────────────────────────────────────────
// 숫자 칸
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 숫자 칸: 값(기본 48/800 흰색) 아래 라벨(24/500 Text72), 사이 4. 단위는 값에 붙여 쓴다("413km").
 * 도착 카드는 `valueColor = PleosColor.Accent`, 주행 중은 `valueStyle = PleosType.Stat96, labelStyle = PleosType.Title32Medium,
 * valueColor = PleosColor.Accent, horizontalAlignment = Alignment.CenterHorizontally` 로 쓴다.
 * 화면 읽기 프로그램은 값과 라벨을 한 번에 읽는다.
 */
@Composable
fun StatCell(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    valueColor: Color = PleosColor.Text,
    valueStyle: TextStyle = PleosType.Stat48,
    labelStyle: TextStyle = PleosType.Label24,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
) {
    Column(modifier.semantics(mergeDescendants = true) {}, horizontalAlignment = horizontalAlignment, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(value, style = valueStyle, color = valueColor, maxLines = 1, softWrap = false)
        Text(label, style = labelStyle, color = PleosColor.Text72, maxLines = 1, softWrap = false)
    }
}

/** 숫자 칸 여러 개를 간격 [spacing](64)으로 놓는다. 폭이 모자라면 다음 줄로 넘긴다(줄 간격 16) */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatRow(modifier: Modifier = Modifier, spacing: Dp = 64.dp, content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(spacing), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
}

// ─────────────────────────────────────────────────────────────────────────────
// 번호 점, 칩
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 방문지 번호 점. 기본 = 지름 48 앰버 + #131417 26/700. 숙박은 label "숙".
 * [selected] = 지름 60 흰 바탕 + 32/700 (코스 화면에서 안내 중인 구간).
 * 번호는 방문지만 센다. 잠깐 정차는 [IdleDot]. 경로 그림 위의 번호 점은 [RouteCanvas] 가 따로 그린다.
 */
@Composable
fun NumberDot(label: String, modifier: Modifier = Modifier, selected: Boolean = false) {
    val size = if (selected) 60.dp else 48.dp
    Box(
        modifier.size(size).background(if (selected) PleosColor.Text else PleosColor.Accent, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = if (selected) PleosType.Title32 else PleosType.Body26Strong, color = PleosColor.OnAccent, maxLines = 1, softWrap = false)
    }
}

/**
 * 잠깐 정차 점(지름 16, #A4A8AE, 번호 없음). [slot] 크기 상자 가운데에 그린다:
 * 기본 48 은 [NumberDot] 과 같은 폭이라 목록에서 이름 시작선이 맞는다. 점만 쓰려면 `slot = 16.dp`.
 */
@Composable
fun IdleDot(modifier: Modifier = Modifier, slot: Dp = 48.dp) {
    Box(modifier.size(slot), contentAlignment = Alignment.Center) {
        Box(Modifier.size(16.dp).background(PleosColor.Idle, CircleShape))
    }
}

/** 작은 라벨 칩(높이 48, pill, 24/500, 좌우 20). 아래 [RainChip]·[MutedChip]·[AmberChip] 을 먼저 쓴다 */
@Composable
fun TagChip(text: String, fg: Color, bg: Color, modifier: Modifier = Modifier, height: Dp = 48.dp, style: TextStyle = PleosType.Label24) {
    Box(
        modifier
            .height(height)
            .background(bg, RoundedCornerShape(height / 2))
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = style, color = fg, maxLines = 1, softWrap = false)
    }
}

/** 비 칩: 글자 #6FB8FF, 바탕 rgba(0,130,255,0.18) */
@Composable
fun RainChip(modifier: Modifier = Modifier, text: String = "비") = TagChip(text, PleosColor.Rain, PleosColor.RainSoft, modifier)

/** 흐린 칩: 글자 Text72, 바탕 basic. 방문지 카드의 "공유 제외" */
@Composable
fun MutedChip(text: String, modifier: Modifier = Modifier) = TagChip(text, PleosColor.Text72, PleosColor.ButtonBasic, modifier)

/** 앰버 칩: 바탕 앰버, 글자 #131417. "1년 전 이맘때", "새 여행" */
@Composable
fun AmberChip(text: String, modifier: Modifier = Modifier) = TagChip(text, PleosColor.OnAccent, PleosColor.Accent, modifier)

@Composable
fun Dot(size: Dp, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(size).background(color, CircleShape))
}

// ─────────────────────────────────────────────────────────────────────────────
// 카드
// ─────────────────────────────────────────────────────────────────────────────

/** 카드 안쪽 여백 */
object CardPadding {
    /** 목록 행 카드: 좌우 40, 위아래 28 */
    val List = PaddingValues(horizontal = 40.dp, vertical = 28.dp)
    /** 방문지 카드: 좌우 40, 위아래 24 */
    val Stop = PaddingValues(horizontal = 40.dp, vertical = 24.dp)
    /** 잠깐 정차 카드: 좌우 40, 위아래 20 */
    val Brief = PaddingValues(horizontal = 40.dp, vertical = 20.dp)
}

/**
 * 누를 수 있는 카드 바탕. 순서: 눌림 축소(98%) → 바탕(눌리면 [pressedColor]) → 선택 테두리(3px 앰버) → 클릭 → 안쪽 여백.
 * 테두리는 안쪽 여백 위에 겹쳐 그리므로 선택돼도 카드 크기와 내용 위치가 바뀌지 않는다.
 * [onClick] 이 null 이면 바탕·테두리·여백만 준다(누를 수 없는 카드).
 */
fun Modifier.pressableCard(
    onClick: (() -> Unit)?,
    radius: Dp = 28.dp,
    color: Color = PleosColor.Surface,
    pressedColor: Color = PleosColor.SurfacePressed,
    selected: Boolean = false,
    contentPadding: PaddingValues = CardPadding.List,
    onClickLabel: String? = null,
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val shape = RoundedCornerShape(radius)
    this
        .then(if (onClick != null) Modifier.pressScale(source, PressScale.CARD) else Modifier)
        .background(if (pressed) pressedColor else color, shape)
        .then(if (selected) Modifier.border(BorderStroke(3.dp, PleosColor.Accent), shape) else Modifier)
        .then(
            if (onClick != null) Modifier.clickable(interactionSource = source, indication = null, onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
            else Modifier,
        )
        .padding(contentPadding)
}

/** 목록 행 카드(Surface, 반지름 28, 여백 40/28). "1년 전 이맘때" 카드는 `selected = true` 로 앰버 테두리 */
fun Modifier.listCard(onClick: (() -> Unit)?, selected: Boolean = false, onClickLabel: String? = null): Modifier =
    pressableCard(onClick, radius = 28.dp, selected = selected, contentPadding = CardPadding.List, onClickLabel = onClickLabel)

/** 방문지 카드(Surface, 반지름 24, 여백 40/24). 펼친 카드는 `selected = true`. 잠깐 정차는 `color = BackgroundLow, contentPadding = CardPadding.Brief` */
fun Modifier.stopCard(
    onClick: (() -> Unit)?,
    selected: Boolean = false,
    color: Color = PleosColor.Surface,
    contentPadding: PaddingValues = CardPadding.Stop,
    onClickLabel: String? = null,
): Modifier = pressableCard(onClick, radius = 24.dp, color = color, selected = selected, contentPadding = contentPadding, onClickLabel = onClickLabel)

/** 카드 바탕 (누를 수 없는 카드). 누를 수 있는 카드는 [pressableCard] */
fun Modifier.card(radius: Dp = 24.dp, color: Color = PleosColor.Surface): Modifier =
    this.background(color, RoundedCornerShape(radius))

// ─────────────────────────────────────────────────────────────────────────────
// 아래 고정 줄
// ─────────────────────────────────────────────────────────────────────────────

object BottomBarDefaults {
    /** 위 gradient 24 + 위아래 여백 28·28 + 버튼 112. 목록의 아래 contentPadding 으로 쓴다 */
    val Height = 192.dp
    val ButtonHeight = 112.dp
}

/**
 * 화면(또는 열) 아래에 붙는 버튼 줄. 위 24px 은 바탕색으로 흐려지는 gradient, 그 아래 위아래 여백 28, 버튼 간격 16.
 * 화면이 `Box` 안에서 `Modifier.align(Alignment.BottomCenter)` 로 놓고, 스크롤 목록은 아래 여백 [BottomBarDefaults.Height] 를 준다.
 * 버튼은 `Modifier.weight(1f)` 로 같은 폭을 준다. 왼쪽 보조 / 오른쪽 주 행동.
 */
@Composable
fun BottomBar(modifier: Modifier = Modifier, background: Color = PleosColor.Background, content: @Composable RowScope.() -> Unit) {
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(24.dp).background(Brush.verticalGradient(listOf(background.copy(alpha = 0f), background))))
        Row(
            Modifier.fillMaxWidth().background(background).padding(vertical = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/** 버튼 1개(주 행동, 폭 가득) 또는 2개(왼쪽 [secondaryText], 오른쪽 [primaryText], 같은 폭) 아래 고정 줄 */
@Composable
fun BottomBar(
    primaryText: String,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    secondaryText: String? = null,
    onSecondary: () -> Unit = {},
    primaryEnabled: Boolean = true,
    primaryBusyText: String? = null,
    background: Color = PleosColor.Background,
) {
    BottomBar(modifier, background) {
        if (secondaryText != null) SecondaryButton(secondaryText, onSecondary, Modifier.weight(1f), BottomBarDefaults.ButtonHeight)
        PrimaryButton(primaryText, onPrimary, Modifier.weight(1f), BottomBarDefaults.ButtonHeight, primaryEnabled, primaryBusyText)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 그 밖
// ─────────────────────────────────────────────────────────────────────────────

/** 화면 폭이 좁으면(앱 2개 분할) true */
fun isPortrait(maxWidth: Dp): Boolean = maxWidth < 1200.dp
