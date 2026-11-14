package kr.triprecap.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.launch
import kr.triprecap.app.AppContainer
import kr.triprecap.app.BuildConfig
import kr.triprecap.app.domain.CoursePlanner
import kr.triprecap.app.domain.Texts
import kr.triprecap.app.domain.Toasts
import kr.triprecap.app.domain.Trip
import kr.triprecap.app.share.ShareCodec
import java.time.ZoneId

private val zone: ZoneId = ZoneId.of("Asia/Seoul")

/** 화면 좌우 여백: 넓은 창 56, 좁은 창(841) 32 */
private fun sidePadding(portrait: Boolean): Dp = if (portrait) 32.dp else 56.dp

// ─────────────────────────────────────────────────────────────────────────────
// 휴대폰으로 보내기
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 휴대폰으로 보내기 화면. QR 1개와 보내는 것·보내지 않는 것.
 * 여행 내용은 주소의 # 뒤에만 들어간다. 공유 페이지 주소가 설정되지 않았으면 QR 을 그리지 않는다(찍어도 열리지 않으므로).
 */
@Composable
fun ShareQrScreen(c: AppContainer, trip: Trip, portrait: Boolean, onBack: () -> Unit) {
    val places by c.session.places.collectAsState()
    val built = remember(trip, places) { runCatching { ShareCodec.build(trip, places) } }
    val payload = built.getOrNull()
    val url = payload?.urlOrNull(BuildConfig.SHARE_BASE_URL)
    val side = sidePadding(portrait)

    Column(Modifier.fillMaxSize().padding(start = side, end = side, top = 40.dp).verticalScroll(rememberScrollState())) {
        TopBar("휴대폰으로 보내기", onBack = onBack)
        Spacer(Modifier.height(48.dp))
        when {
            payload == null -> ShareMessage(
                "이 여행은 QR 하나에 담기지 않아요",
                "방문지 몇 곳을 공유에서 빼면 보낼 수 있어요.",
            )
            url == null -> ShareMessage("지금은 QR로 보낼 수 없어요", "공유 페이지 주소를 설정하면 QR이 나와요.") {
                if (BuildConfig.DEBUG) {
                    Text(
                        "개발용 안내: local.properties 에 share.baseUrl=https://… 을 넣고 다시 빌드하면 QR이 나와요.",
                        style = PleosType.Label24,
                        color = PleosColor.Text50,
                    )
                }
            }
            else -> {
                val info = @Composable { modifier: Modifier ->
                    ShareInfo(
                        title = trip.title,
                        date = Texts.date(trip.startedAt, zone, c.clock.now()),
                        rows = payload.preview.rows.map { it.label to it.value },
                        stacked = portrait,
                        modifier = modifier,
                    )
                }
                if (portrait) {
                    QrCode(url, Modifier.size(520.dp))
                    Spacer(Modifier.height(40.dp))
                    info(Modifier.fillMaxWidth())
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(64.dp), verticalAlignment = Alignment.Top) {
                        QrCode(url, Modifier.size(640.dp))
                        info(Modifier.weight(1f))
                    }
                }
            }
        }
        Spacer(Modifier.height(48.dp))
    }
}

/** QR 오른쪽(좁은 창은 아래) 정보. 위쪽 끝을 QR 위쪽 끝에 맞춘다 */
@Composable
private fun ShareInfo(title: String, date: String, rows: List<Pair<String, String>>, stacked: Boolean, modifier: Modifier) {
    Column(modifier) {
        Text("휴대폰 카메라로 QR을 찍어 주세요", style = PleosType.Title40, color = PleosColor.Text, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(12.dp))
        Text("$title · $date", style = PleosType.Body28, color = PleosColor.Text72, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(40.dp))
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            for ((label, value) in rows) TwoColumnInfoRow(label, value, labelWidth = 220.dp, stacked = stacked)
        }
        Spacer(Modifier.height(32.dp))
        Text(
            "QR에 담긴 여행 내용은 휴대폰에서만 열려요.\n사진은 휴대폰에서 붙여요. 직접 공유하기 전에는 어디에도 올라가지 않아요.",
            style = PleosType.Body26,
            color = PleosColor.Text72,
        )
    }
}

/** QR 을 보여 줄 수 없을 때: 제목 40/800 + 한 줄 28 Text72 */
@Composable
private fun ShareMessage(title: String, body: String, extra: @Composable () -> Unit = {}) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(title, style = PleosType.Title40, color = PleosColor.Text, modifier = Modifier.semantics { heading() })
        Text(body, style = PleosType.Body28, color = PleosColor.Text72)
        extra()
    }
}

@Composable
private fun QrCode(text: String, modifier: Modifier) {
    val matrix = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to 2))
    }
    Canvas(modifier.background(Color.White).semantics { contentDescription = "여행 QR" }) {
        val cell = size.width / matrix.width
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 코스로 다시 가기
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 코스로 다시 가기 화면. 왼쪽 경로 그림, 오른쪽 지난번 숫자 칸·순서 목록·휴게소 넣기, 아래 고정 줄.
 * 좁은 창은 경로 그림 없이 한 열이다. 안내 결과는 알림으로 알린다.
 */
@Composable
fun CourseStartScreen(c: AppContainer, trip: Trip, portrait: Boolean, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current
    var includeRest by remember(trip.id) { mutableStateOf(emptySet<Int>()) }
    val legs = remember(trip, includeRest) { CoursePlanner.plan(trip, includeRest) }
    val rows = remember(trip, includeRest) { CourseList.rows(trip, includeRest) }
    val rests = remember(trip) { CoursePlanner.restAreaCandidates(trip) }
    val lastTime = remember(trip) { CoursePlanner.lastTimeStats(trip) }
    val progress by c.course.progress.collectAsState()
    val demoRequest by c.player.lastRouteRequest.collectAsState()
    val scenarioMode by c.scenarioMode.collectAsState()
    var sending by remember { mutableStateOf(false) }
    val side = sidePadding(portrait)

    val start: () -> Unit = {
        if (!sending) {
            sending = true
            scope.launch {
                c.course.start(legs)
                val p = c.course.progress.value
                if (p != null && p.requestOk) {
                    toast.show(CourseList.sentToast(p.legNumber, p.legCount))
                } else {
                    toast.show(Toasts.NAVI_FAILED)
                    // 요청이 실패한 코스는 안내 중으로 두지 않는다. 버튼 줄이 [안내 시작] 으로 돌아간다
                    c.course.cancel()
                }
                sending = false
            }
        }
    }
    // 안내 중인 구간(0부터). 안내 전이면 null
    val activeLeg = progress?.let { it.legNumber - 1 }

    if (legs.isNotEmpty()) ToastBottomPadding(BottomBarDefaults.Height + 24.dp)

    Column(Modifier.fillMaxSize().padding(start = side, end = side, top = 40.dp)) {
        TopBar("코스로 다시 가기", onBack = onBack)
        Row(Modifier.weight(1f).fillMaxWidth().padding(top = 32.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            if (!portrait) {
                RouteCanvas(trip, Modifier.weight(1f).fillMaxHeight().padding(bottom = 28.dp).card(28.dp, PleosColor.BackgroundLow))
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = if (legs.isNotEmpty()) BottomBarDefaults.Height else 40.dp),
                    verticalArrangement = Arrangement.spacedBy(40.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("지난번", style = PleosType.Body28Strong, color = PleosColor.Text72, modifier = Modifier.semantics { heading() })
                        StatRow { for (s in lastTime) StatCell(s.value, s.label) }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        if (rows.isEmpty()) {
                            Text("위치가 기록된 방문지가 없어서 안내할 수 없어요", style = PleosType.Body28, color = PleosColor.Text72)
                        } else {
                            CourseOrder(rows, multiLeg = legs.size >= 2, activeLeg = activeLeg)
                        }
                        if (rests.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                for (r in rests) {
                                    val on = r.id in includeRest
                                    SwitchRow(
                                        title = "${trip.nameOf(r)} 들르기",
                                        detail = "지난번 ${Texts.stay(r)}",
                                        checked = on,
                                        onToggle = { includeRest = if (on) includeRest - r.id else includeRest + r.id },
                                        modifier = Modifier.card(24.dp),
                                        leading = { IdleDot(slot = 16.dp) },
                                    )
                                }
                            }
                        }
                        if (BuildConfig.DEBUG && scenarioMode && demoRequest != null) {
                            Text("시나리오 재생 중이라 내비 대신 요청 내용만 표시해요: $demoRequest", style = PleosType.Label24, color = PleosColor.Text50)
                        }
                    }
                }
                if (legs.isNotEmpty()) {
                    val m = Modifier.align(Alignment.BottomCenter)
                    if (progress == null) {
                        BottomBar("안내 시작", start, m, primaryEnabled = !sending)
                    } else {
                        BottomBar(
                            "처음부터 다시 안내", start, m,
                            secondaryText = "안내 끝내기",
                            onSecondary = { c.course.cancel() },
                            primaryEnabled = !sending,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 순서 목록: 점(방문지 번호·"숙"·잠깐 정차 회색 점) + 이름 32/700, 행 높이 96.
 * 구간이 2개 이상이면 구간 사이에 "여기서 다음 구간" 머리말을 넣고 아래에 구간 안내 문장을 붙인다.
 * 안내 중인 구간([activeLeg])의 번호 점은 선택됨 모양(흰 바탕)으로 그린다.
 */
@Composable
private fun CourseOrder(rows: List<CourseRow>, multiLeg: Boolean, activeLeg: Int?) {
    Column {
        rows.forEachIndexed { i, row ->
            if (multiLeg && i > 0 && row.leg != rows[i - 1].leg) {
                Text("여기서 다음 구간", style = PleosType.Label24, color = PleosColor.Text72, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
            } else if (i > 0) {
                Spacer(Modifier.height(8.dp))
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 96.dp), verticalAlignment = Alignment.CenterVertically) {
                // 선택된 번호 점(60)도 같은 칸(60) 안에 놓아 이름 시작선이 움직이지 않게 한다
                Box(Modifier.width(60.dp), contentAlignment = Alignment.Center) {
                    val dot = row.dot
                    if (dot == null) IdleDot() else NumberDot(dot, selected = row.leg == activeLeg)
                }
                // 이름 시작선 72: 점 48 + 간격 24
                Spacer(Modifier.width(12.dp))
                Text(row.name, style = PleosType.Title32, color = PleosColor.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (multiLeg) {
            Spacer(Modifier.height(16.dp))
            Text(
                "한 번에 3곳까지 안내해요. 구간 마지막 곳에서 다시 출발하면 다음 구간 안내가 이어져요.",
                style = PleosType.Body26,
                color = PleosColor.Text72,
            )
        }
    }
}
