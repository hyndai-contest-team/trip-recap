package kr.triprecap.app.ui

import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

/** 덮개 진하기. 검정 80% = [PleosColor.Scrim] */
private const val SCRIM_ALPHA = 0.8f

/**
 * 대화상자 틀: 폭 760, 바탕 Surface, 반지름 28, 여백 40.
 * 덮개는 Dialog 창의 dim 을 80% 로 맞춰 그린다(창 밖을 누르면 닫힌다).
 * [alignTop] 이면 창을 화면 위쪽(위 여백 40)에 붙인다: 글 입력 대화상자가 화면 아래 키보드에 가리지 않게 하고,
 * 키보드가 올라와도 창을 옮기지 않는다(SOFT_INPUT_ADJUST_NOTHING).
 */
@Composable
private fun DialogFrame(onDismiss: () -> Unit, alignTop: Boolean, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val view = LocalView.current
        val topPx = with(LocalDensity.current) { 40.dp.roundToPx() }
        SideEffect {
            val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
            window.setDimAmount(SCRIM_ALPHA)
            if (alignTop) {
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
                window.attributes = window.attributes.apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = topPx
                }
            }
        }
        Column(Modifier.width(760.dp).card(28.dp).padding(40.dp), content = content)
    }
}

@Composable
private fun DialogTitle(text: String) {
    Text(text, style = PleosType.Title32, color = PleosColor.Text, modifier = Modifier.semantics { heading() })
}

/** 대화상자 아래 버튼 2개: 왼쪽 [닫기], 오른쪽 확인. 같은 폭, 높이 88, 간격 16, 위 여백 40 */
@Composable
private fun DialogButtons(onDismiss: () -> Unit, confirm: @Composable (Modifier) -> Unit) {
    Spacer(Modifier.height(40.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        SecondaryButton("닫기", onDismiss, Modifier.weight(1f), height = 88.dp)
        confirm(Modifier.weight(1f))
    }
}

/**
 * 확인 대화상자(여행 지우기 확인 등). 제목 32/700, 본문 28 Text72, 버튼 [닫기] / [confirm].
 * [destructive] = true 면 확인 버튼이 [DangerFillButton](지우기 확인)이다.
 * 확인을 누르면 [onConfirm] 다음 [onDismiss] 를 부른다.
 */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirm: String,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    onConfirm: () -> Unit,
) {
    DialogFrame(onDismiss, alignTop = false) {
        DialogTitle(title)
        Spacer(Modifier.height(12.dp))
        Text(body, style = PleosType.Body28, color = PleosColor.Text72)
        DialogButtons(onDismiss) { m ->
            val click = { onConfirm(); onDismiss() }
            if (destructive) DangerFillButton(confirm, click, m, height = 88.dp)
            else PrimaryButton(confirm, click, m, height = 88.dp)
        }
    }
}

/**
 * 내용을 화면 쪽에서 채우는 대화상자(검색 기간 달력). 틀·제목·아래 버튼 줄은 [ConfirmDialog] 와 같다.
 * 제목 줄은 높이 80 이고 [titleTrailing] 을 오른쪽 끝에 둔다(글자 버튼 높이 80 에 맞춤).
 * [confirm] 은 아래 오른쪽 주 행동 버튼을 받은 Modifier(같은 폭)로 그린다. 왼쪽은 [닫기].
 */
@Composable
fun ContentDialog(
    title: String,
    onDismiss: () -> Unit,
    titleTrailing: @Composable RowScope.() -> Unit = {},
    confirm: @Composable (Modifier) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    DialogFrame(onDismiss, alignTop = false) {
        Row(Modifier.fillMaxWidth().height(80.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { DialogTitle(title) }
            titleTrailing()
        }
        content()
        DialogButtons(onDismiss, confirm)
    }
}

/**
 * 글 한 줄 입력 대화상자(제목·장소 이름·메모). 화면 위쪽(위 여백 40)에 붙여 키보드에 가리지 않게 한다.
 * 열리면 입력칸에 초점을 주고 커서를 글 끝에 둔다. 키보드의 완료 키도 [confirmText] 와 같다.
 * [hint] 는 입력칸 아래 26/400 Text72 (카드 위라 Text50 을 쓰지 않는다).
 */
@Composable
fun TextEditDialog(
    title: String,
    initial: String,
    hint: String?,
    onDismiss: () -> Unit,
    confirmText: String = "저장",
    placeholder: String? = null,
    onSave: (String) -> Unit,
) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val focus = remember { FocusRequester() }
    val save = { onSave(value.text); onDismiss() }
    DialogFrame(onDismiss, alignTop = true) {
        DialogTitle(title)
        Spacer(Modifier.height(24.dp))
        InputField(value, { value = it }, Modifier.fillMaxWidth().focusRequester(focus), placeholder = placeholder, onDone = save)
        if (hint != null) {
            Spacer(Modifier.height(12.dp))
            Text(hint, style = PleosType.Body26, color = PleosColor.Text72)
        }
        DialogButtons(onDismiss) { m -> PrimaryButton(confirmText, save, m, height = 88.dp) }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

/**
 * 한 줄 입력칸(목록 검색칸): 높이 96, 테두리 2px 흰색 24%, 반지름 24, 좌우 32, 글자 28 흰색.
 * [leadingIcon] 은 32 Text72 (검색칸은 [AppIcon.Search]). 커서를 글 끝에 두려면 이 TextFieldValue 판을 쓴다.
 */
@Composable
fun InputField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    leadingIcon: AppIcon? = null,
    imeAction: ImeAction = ImeAction.Done,
    onDone: () -> Unit = {},
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = true,
        textStyle = PleosType.Body28.copy(color = PleosColor.Text),
        cursorBrush = SolidColor(PleosColor.Text),
        keyboardOptions = KeyboardOptions(imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone() }, onSearch = { onDone() }),
        decorationBox = { inner -> InputDecoration(value.text.isEmpty(), placeholder, leadingIcon, inner) },
    )
}

/** [InputField] 의 String 판. 커서 위치를 따로 다루지 않는 검색칸에 쓴다 */
@Composable
fun InputField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    leadingIcon: AppIcon? = null,
    imeAction: ImeAction = ImeAction.Done,
    onDone: () -> Unit = {},
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = true,
        textStyle = PleosType.Body28.copy(color = PleosColor.Text),
        cursorBrush = SolidColor(PleosColor.Text),
        keyboardOptions = KeyboardOptions(imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone() }, onSearch = { onDone() }),
        decorationBox = { inner -> InputDecoration(value.isEmpty(), placeholder, leadingIcon, inner) },
    )
}

@Composable
private fun InputDecoration(empty: Boolean, placeholder: String?, leadingIcon: AppIcon?, inner: @Composable () -> Unit) {
    Row(
        Modifier
            .height(96.dp)
            .border(2.dp, PleosColor.FieldBorder, RoundedCornerShape(24.dp))
            .padding(horizontal = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (leadingIcon != null) AppIconView(leadingIcon, color = PleosColor.Text72)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            // 카드 위·bg 위 어디서나 읽히도록 Text72. 입력한 글(흰색)과는 밝기로 구분된다
            if (empty && placeholder != null) Text(placeholder, style = PleosType.Body28, color = PleosColor.Text72, maxLines = 1)
            inner()
        }
    }
}
