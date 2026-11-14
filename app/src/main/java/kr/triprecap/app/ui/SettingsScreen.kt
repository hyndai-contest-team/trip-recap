package kr.triprecap.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kr.triprecap.app.AppContainer
import kr.triprecap.app.BuildConfig
import kr.triprecap.app.domain.PlaceChoice
import kr.triprecap.app.domain.PlaceSetup
import kr.triprecap.app.domain.PlaceSlot
import kr.triprecap.app.domain.PlaceSource
import kr.triprecap.app.domain.Toasts
import kr.triprecap.app.domain.TripLists
import kr.triprecap.app.scenario.DemoSeeder
import kr.triprecap.app.scenario.GoldenScenarios
import kr.triprecap.app.share.ShareCodec

private const val RECORDING_DETAIL = "다닌 길과 멈춘 곳을 기록해요"
private const val RECORDING_OFF_NOTE = "꺼 두면 새 여행을 기록하지 않아요. 저장된 여행은 그대로 있어요."

private val PrivacyLines = listOf(
    "여행 기록은 이 차 안에만 저장해요. 서버에 올리거나 분석하지 않아요. 차 밖으로 나가는 것은 아래 두 가지뿐이에요.",
    "장소 이름을 찾을 때 멈춘 곳의 위치를 카카오에 보내요.",
    "QR로 휴대폰에 보낼 때 여행 내용을 보내요. 집·회사 500m 안의 길과 방문지는 빠져요.",
    "일상 이동은 7일, 집·회사를 추정할 때 쓰는 정차 기록은 30일 뒤 지워요.",
)

private const val PLACES_NOTE = "출퇴근 같은 일상 이동을 가려낼 때 써요. 내비 즐겨찾기에 등록한 곳을 먼저 쓰고, 없으면 자주 서 있던 곳으로 추정해요."

/** 시연 배속. 1배속은 실제 시간, 600배속이면 10시간 여행이 1분에 끝난다 */
private val DemoSpeeds = listOf(1.0, 10.0, 60.0, 600.0)

/**
 * 설정 화면. 기록·집과 회사·개인정보 카드와, debug 빌드에서만 보이는 접힌 시연 도구.
 * 이 화면의 앰버는 켜진 스위치 트랙뿐이다. 시연 도구의 켜짐 표시는 모두 [SelectChip] 이다.
 */
@Composable
fun SettingsScreen(c: AppContainer, portrait: Boolean, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current
    val recording by c.settings.recordingEnabled.collectAsState()
    val arrivalVoice by c.settings.arrivalVoiceEnabled.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    var demoOpen by remember { mutableStateOf(false) }
    val side = if (portrait) 32.dp else 56.dp

    Column(Modifier.fillMaxSize().padding(start = side, end = side, top = 40.dp)) {
        TopBar("설정", onBack = onBack)
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(top = 40.dp, bottom = 48.dp),
        ) {
            Column(Modifier.widthIn(max = 1100.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(40.dp)) {
                SettingsSection("기록") {
                    SwitchRow(
                        "여행 기록",
                        if (recording) RECORDING_DETAIL else "$RECORDING_DETAIL\n$RECORDING_OFF_NOTE",
                        recording,
                        onToggle = {
                            val next = !recording
                            c.settings.setRecordingEnabled(next)
                            toast.show(if (next) Toasts.RECORDING_ON else Toasts.RECORDING_OFF)
                        },
                    )
                    ListDivider()
                    SwitchRow("도착 음성 안내", "여행이 끝나면 한 문장으로 알려 줘요", arrivalVoice, onToggle = { c.settings.setArrivalVoiceEnabled(!arrivalVoice) })
                }

                SettingsSection("집과 회사") {
                    Text(PLACES_NOTE, style = PleosType.Body26, color = PleosColor.Text72, modifier = Modifier.padding(start = 40.dp, end = 40.dp, top = 28.dp, bottom = 4.dp))
                    PlaceRow(c, PlaceSlot.HOME)
                    ListDivider()
                    PlaceRow(c, PlaceSlot.OFFICE)
                }

                SettingsSection("개인정보") {
                    Column(Modifier.padding(start = 40.dp, end = 40.dp, top = 32.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (line in PrivacyLines) Text(line, style = PleosType.Body28, color = PleosColor.Text72)
                    }
                    ListDivider()
                    Row(Modifier.padding(16.dp)) {
                        DangerTextButton("모든 여행 지우기", { confirmDelete = true })
                    }
                }

                // 시연 도구는 주행 중 화면 제한을 끌 수 있으므로 debug 빌드에만 둔다
                if (BuildConfig.DEBUG) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        DemoHeader(open = demoOpen, onClick = { demoOpen = !demoOpen })
                        if (demoOpen) DemoTools(c)
                    }
                }

                Text(versionLine(full = BuildConfig.DEBUG && demoOpen), style = PleosType.Label24, color = PleosColor.Text50)
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "모든 여행을 지울까요?",
            body = "저장된 여행과 기록 중인 여행, 일상 이동, 집·회사 추정에 쓰는 정차 기록이 모두 지워져요. 되돌릴 수 없어요.",
            confirm = "모두 지우기",
            onDismiss = { confirmDelete = false },
            destructive = true,
        ) {
            scope.launch {
                c.session.onAllDeleted()
                toast.show(Toasts.ALL_TRIPS_DELETED)
            }
        }
    }
}

/** 버전. debug 빌드에서 시연 도구를 펼치면 키·주소 설정 여부까지 보인다(시연 전에 확인하는 값) */
private fun versionLine(full: Boolean): String {
    val version = "버전 ${BuildConfig.VERSION_NAME}"
    if (!full) return version
    val kakao = if (BuildConfig.KAKAO_REST_API_KEY.isBlank()) "없음" else "있음"
    val share = if (BuildConfig.SHARE_BASE_URL.isBlank()) "없음" else "있음"
    return "$version · 카카오 키 $kakao · 공유 주소 $share"
}

/**
 * 집·회사 행: 제목(28/500) + 설명 "추정 · 송파구 잠실동"(26 Text72), 아래 보조 버튼(높이 80).
 * [지금 위치로 정하기] 는 내비 등록이 아닐 때만, [추정으로 되돌리기] 는 직접 정한 위치일 때만 보인다.
 * 내비 즐겨찾기가 있으면 그 위치를 먼저 쓰므로 여기서 바꿀 수 없다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaceRow(c: AppContainer, slot: PlaceSlot) {
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current
    val setup by c.session.placeSetup.collectAsState()
    val trips by c.store.trips.collectAsState(emptyList())
    val choice: PlaceChoice = setup[slot]
    val region = remember(choice.point, trips) { PlaceSetup.regionNear(choice.point, trips) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(slot.title, style = PleosType.Body28Strong, color = PleosColor.Text)
            Text(PlaceSetup.describe(choice, region), style = PleosType.Body26, color = PleosColor.Text72)
        }
        if (choice.source != PlaceSource.NAVI) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton("지금 위치로 정하기", {
                    scope.launch { toast.show(if (c.session.setPlaceToCurrent(slot)) Toasts.placeSet(slot) else Toasts.PLACE_UNKNOWN) }
                }, height = 80.dp)
                if (choice.source == PlaceSource.USER) {
                    SecondaryButton("추정으로 되돌리기", {
                        scope.launch {
                            c.session.resetPlaceToEstimate(slot)
                            toast.show(Toasts.PLACE_RESET)
                        }
                    }, height = 80.dp)
                }
            }
        }
    }
}

/** 구역: 카드 바깥 머리말(28/500 Text72) + 카드(Surface, 반지름 28) */
@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(title, style = PleosType.Body28Strong, color = PleosColor.Text72, modifier = Modifier.semantics { heading() })
        Column(Modifier.fillMaxWidth().card(28.dp)) { content() }
    }
}

/** 시연 도구 펼침 행(높이 96, 카드). 펼치면 꺾쇠가 아래를 향한다 */
@Composable
private fun DemoHeader(open: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(96.dp)
            .pressableCard(onClick, radius = 28.dp, contentPadding = PaddingValues(horizontal = 40.dp))
            .semantics { stateDescription = if (open) "펼침" else "접힘" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("시연 도구", style = PleosType.Body28Strong, color = PleosColor.Text, modifier = Modifier.weight(1f))
        AppIconView(AppIcon.ChevronRight, Modifier.rotate(if (open) 90f else 0f), color = PleosColor.Text72)
    }
}

/** 시연 조작: 신호 고르기, 주행 중 화면 제한, 시나리오 재생. 좁은 창에서 넘치지 않게 모두 FlowRow 로 놓는다 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DemoTools(c: AppContainer) {
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current
    val scenarioMode by c.scenarioMode.collectAsState()
    val override by c.restrictionOverride.collectAsState()
    val scenario by c.player.scenario.collectAsState()
    val paused by c.player.paused.collectAsState()
    val speed by c.player.speed.collectAsState()
    val chapter by c.player.chapterIndex.collectAsState()
    val finished by c.player.finished.collectAsState()
    val trips by c.store.trips.collectAsState(emptyList())
    val places by c.session.places.collectAsState()
    // 공유 화면에서 뺀 QR 크기를 여기서 본다. 가장 최근 여행 기준
    val latestQrBytes = remember(trips, places) {
        TripLists.listed(trips).maxByOrNull { it.startedAt }?.let { t -> runCatching { ShareCodec.build(t, places).bytes.size }.getOrNull() }
    }

    Column(Modifier.fillMaxWidth().card(28.dp).padding(40.dp), verticalArrangement = Arrangement.spacedBy(32.dp)) {
        DemoGroup("신호") {
            SelectChip("실제 차량", !scenarioMode, { c.setScenarioMode(false) })
            SelectChip("시나리오", scenarioMode, { c.setScenarioMode(true) })
        }
        DemoGroup("주행 중 화면 제한") {
            SelectChip("자동", override == null, { c.restrictionOverride.value = null })
            SelectChip("제한 켜기", override == true, { c.restrictionOverride.value = true })
            SelectChip("제한 끄기", override == false, { c.restrictionOverride.value = false })
        }
        if (scenarioMode) {
            val now = scenario?.let { s ->
                listOfNotNull(s.name, s.chapters.getOrNull(chapter)?.title, if (finished) "끝" else null).joinToString(" · ")
            }
            DemoGroup("시나리오", detail = now) {
                GoldenScenarios.all.forEachIndexed { i, s -> SelectChip(s.name, scenario?.name == s.name, { c.loadScenario(i) }) }
            }
            DemoGroup("재생") {
                SecondaryButton(if (paused) "재생" else "일시정지", { if (paused) c.player.resume() else c.player.pause() }, height = 80.dp)
                for (x in DemoSpeeds) SelectChip("${x.toInt()}배속", speed == x, { c.player.setSpeed(x) })
                SecondaryButton("다음 장면", { c.player.nextChapter() }, height = 80.dp)
            }
            DemoGroup("시연 데이터") {
                SecondaryButton("시연 여행 넣기", {
                    scope.launch {
                        DemoSeeder.seed(c.store, System.currentTimeMillis())
                        toast.show(Toasts.DEMO_SEEDED)
                    }
                }, height = 80.dp)
                SecondaryButton("목적지 도착 보내기", { c.player.simulateArrival() }, height = 80.dp)
                // 집이 아닌 곳에서 닫힌 이동의 도착 카드는 다음 시동 때 뜬다
                SecondaryButton("시동 켜기 보내기", { c.simulateIgnitionOn(); toast.show(Toasts.IGNITION_SENT) }, height = 80.dp)
            }
        }
        latestQrBytes?.let {
            Text("최근 여행 QR 크기 ${it}B (최대 ${ShareCodec.MAX_BYTES}B)", style = PleosType.Body26, color = PleosColor.Text72)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DemoGroup(title: String, detail: String? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = PleosType.Body28Strong, color = PleosColor.Text)
            if (detail != null) Text(detail, style = PleosType.Body26, color = PleosColor.Text72)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}
