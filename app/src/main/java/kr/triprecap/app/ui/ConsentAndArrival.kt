package kr.triprecap.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kr.triprecap.app.domain.Arrival
import kr.triprecap.app.domain.ArrivalKind
import kr.triprecap.app.domain.ArrivalCard as ArrivalContent

/** 동의 화면 내용 폭 */
private val ConsentWidth = 1040.dp
/** 동의 화면 앱 이름 28/700 앰버. PleosType 에 28/700 이 없다 */
private val AppNameStyle = PleosType.Body28.copy(fontWeight = FontWeight.Bold)

private val ConsentRows = listOf(
    "기록하는 것" to "다닌 길, 멈춘 곳과 시각, 함께 탄 사람 수, 와이퍼 작동, 바깥 기온",
    "저장하는 곳" to "이 차 안에만 저장해요. 가입 없이 쓸 수 있어요.",
    "차 밖으로 보내는 것" to "장소 이름을 찾을 때 멈춘 곳의 위치,\nQR로 휴대폰에 보낼 때 여행 내용",
)

/** 첫 실행: 위치 기록 동의 화면. 내용은 위에서부터, [동의하고 시작하기] 는 화면 아래에 붙는다 */
@Composable
fun ConsentScreen(onAgree: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val narrow = isPortrait(maxWidth)
        Column(Modifier.fillMaxSize().padding(start = if (narrow) 32.dp else 72.dp, end = if (narrow) 32.dp else 72.dp, top = 40.dp, bottom = 56.dp)) {
            Column(Modifier.weight(1f).widthIn(max = ConsentWidth).verticalScroll(rememberScrollState())) {
                Text("왔다감", style = AppNameStyle, color = PleosColor.Accent)
                Spacer(Modifier.height(16.dp))
                Text(
                    "여행을 다녀오면 들른 곳과\n머문 시간이 차에 정리돼요",
                    style = PleosType.Title48,
                    color = PleosColor.Text,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(56.dp))
                Column(verticalArrangement = Arrangement.spacedBy(28.dp)) {
                    for ((label, value) in ConsentRows) TwoColumnInfoRow(label, value, labelWidth = 280.dp, stacked = narrow)
                }
                Spacer(Modifier.height(28.dp))
                Text(
                    "QR로 보낼 때 집·회사 근처는 빠져요.\n기록은 설정에서 언제든 끄고 지울 수 있어요.\n" +
                        "내비 즐겨찾기에 집과 회사를 등록하면 출퇴근을 더 정확히 가려내요.",
                    style = PleosType.Body26,
                    color = PleosColor.Text72,
                )
            }
            Spacer(Modifier.height(24.dp))
            PrimaryButton("동의하고 시작하기", onAgree, Modifier.widthIn(max = ConsentWidth).fillMaxWidth())
        }
    }
}

/**
 * 2열 행: 왼쪽 라벨(26 Text72, 폭 [labelWidth]) / 오른쪽 값(28 흰색). 두 글자의 첫 줄 기준선을 맞춘다.
 * [stacked] 면(좁은 창) 라벨을 값 위에 놓는다. 공유 화면도 쓴다.
 */
@Composable
internal fun TwoColumnInfoRow(label: String, value: String, labelWidth: Dp, stacked: Boolean) {
    if (stacked) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = PleosType.Body26, color = PleosColor.Text72)
            Text(value, style = PleosType.Body28, color = PleosColor.Text)
        }
    } else {
        Row {
            Text(label, style = PleosType.Body26, color = PleosColor.Text72, modifier = Modifier.width(labelWidth).alignByBaseline())
            Text(value, style = PleosType.Body28, color = PleosColor.Text, modifier = Modifier.weight(1f).alignByBaseline())
        }
    }
}

/**
 * 이동이 끝났을 때 띄우는 도착 카드 (docs/product-plan.md 2-3). 80% 덮개 위 가운데 카드.
 * 덮개를 누르면 닫힌다([onDismiss]). 카드 안쪽을 눌러도 닫히지 않는다.
 * - 저장한 여행(SAVED, SAVED_EARLIER): [닫기] / [여행 보기]([onOpen])
 * - 묻는 카드(ASK): 요약 아래 질문 줄 + [지우기]([onDiscard]) / [남기기]([onKeep]). [여행 보기] 는 없다.
 *   덮개를 눌러 닫으면 답하지 않은 것이다(일상 이동으로 보관)
 */
@Composable
fun ArrivalCard(arrival: Arrival, onOpen: () -> Unit, onDismiss: () -> Unit, onKeep: () -> Unit, onDiscard: () -> Unit) {
    val card = remember(arrival) { ArrivalContent.of(arrival.trip, arrival.kind) }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(PleosColor.Scrim)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClickLabel = "닫기", onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        val side = if (isPortrait(maxWidth)) 32.dp else 56.dp
        Column(
            Modifier
                .padding(horizontal = side, vertical = 40.dp)
                .widthIn(max = 1000.dp)
                .fillMaxWidth()
                .card(32.dp)
                // 카드 안 빈 곳을 누른 것이 덮개로 넘어가 카드가 닫히지 않게 여기서 받는다
                .pointerInput(Unit) { detectTapGestures { } }
                .verticalScroll(rememberScrollState())
                .padding(56.dp),
            verticalArrangement = Arrangement.spacedBy(40.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                card.topLine?.let { Text(it, style = PleosType.Body28Strong, color = PleosColor.Text72) }
                Text(card.headline, style = PleosType.Title48, color = PleosColor.Text, modifier = Modifier.semantics { heading() })
                card.subline?.let { Text(it, style = PleosType.Title32Medium, color = PleosColor.Text72) }
            }
            if (card.stats.isNotEmpty()) {
                StatRow {
                    for (s in card.stats) StatCell(s.value, s.label, valueColor = PleosColor.Accent)
                }
            }
            if (card.longestStayLine != null || card.rainLine != null) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    card.longestStayLine?.let { Text(it, style = PleosType.Body28, color = PleosColor.Text) }
                    card.rainLine?.let { Text(it, style = PleosType.Body28, color = PleosColor.Rain) }
                }
            }
            card.question?.let { Text(it, style = PleosType.Title32, color = PleosColor.Text) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (arrival.kind == ArrivalKind.ASK) {
                    SecondaryButton("지우기", onDiscard, Modifier.width(220.dp))
                    PrimaryButton("남기기", onKeep, Modifier.weight(1f))
                } else {
                    SecondaryButton("닫기", onDismiss, Modifier.width(220.dp))
                    PrimaryButton("여행 보기", onOpen, Modifier.weight(1f))
                }
            }
        }
    }
}
