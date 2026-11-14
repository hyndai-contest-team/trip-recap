package kr.triprecap.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.sp

/**
 * Pleos Connect 디자인 가이드 토큰 (다크). 근거: docs/design-rules.md
 * 차량 디스플레이는 160dpi 라 1dp = 1px, 1sp = 1px 이다. 숫자는 가이드의 px 값을 그대로 쓴다.
 */
object PleosColor {
    val Background = Color(0xFF1D1E21)      // background_primary (basic_50)
    val BackgroundLow = Color(0xFF131417)   // basic_00
    val Surface = Color(0xFF313236)         // surface_high (basic_100)
    val SurfaceAccent = Color(0xFF44464E)   // surface_accent (basic_200)
    val Text = Color(0xFFFFFFFF)
    /** 보조 글자. bg(#1D1E21)·카드(#313236) 위 모두 4.5:1 이상. 카드 위 보조 글자는 이 색을 쓴다 */
    val Text72 = Color(0xB8FFFFFF)
    /**
     * 흐린 글자. **bg(#1D1E21) 위에서만 쓴다.** 카드(Surface #313236) 위에서는 대비 4.45:1 로 4.5:1 기준에 못 미친다.
     * 카드 위 보조 글자는 [Text72] 를 쓴다.
     */
    val Text50 = Color(0x80FFFFFF)
    val Accent = Color(0xFFF5A623)          // 앰버 강조 (앱 고유)
    val OnAccent = Color(0xFF131417)
    val Rain = Color(0xFF6FB8FF)            // informative_positive 계열
    val RainSoft = Color(0x2E0082FF)
    val Idle = Color(0xFFA4A8AE)            // basic_400
    val ButtonBasic = Color(0x14FFFFFF)     // alpha 8%

    /** 켜진 선택 칩 바탕(basic_600, 가이드 button_filled_enabled). 글자는 [OnAccent] */
    val ChipOn = Color(0xFFE0E1E6)
    /** 지우기 글자 버튼 글자색. bg·카드 위 모두 4.5:1 이상 */
    val Danger = Color(0xFFFF7A5C)
    /** 지우기 확인 버튼 바탕(informative negative). 글자는 [OnAccent] */
    val DangerFill = Color(0xFFFF4C28)
    /** 덮개(대화상자 뒤). 검정 80% */
    val Scrim = Color(0xCC000000)
    /** 입력칸 테두리. 흰색 24% */
    val FieldBorder = Color(0x3DFFFFFF)

    // 눌림 바탕색. 가이드: 눌림 = 95% 축소 + 바탕색 변경. 채운 바탕은 12% 어둡게, 반투명 바탕은 불투명도를 올린다
    /** 앰버 버튼 눌림 (#F5A623 × 0.88) */
    val AccentPressed = Color(0xFFD8921F)
    /** basic 버튼·꺼진 칩 눌림 (흰색 16%). 바탕 없는 글자 버튼은 눌렀을 때 [ButtonBasic] 을 깐다 */
    val ButtonBasicPressed = Color(0x29FFFFFF)
    /** 카드 눌림 (Surface #313236 보다 한 단계 밝게) */
    val SurfacePressed = Color(0xFF3B3C41)
    /** 켜진 선택 칩 눌림 (basic_500, 가이드 button_filled_pressed) */
    val ChipOnPressed = Color(0xFFC7C9CE)
    /** 지우기 확인 버튼 눌림 (#FF4C28 × 0.88) */
    val DangerFillPressed = Color(0xFFE04323)
}

/**
 * Pleos 타이포 스케일. Headline 60/56/48, Title 40/36/32, Body 30/28/26, Label 24/20.
 * 줄 높이는 화면 디자인의 line-height 값을 옮겼다. 이 앱은 24 아래를 쓰지 않는다.
 *
 * 용도별 토큰:
 * - 주행 중 큰 숫자 [Stat96] / 숫자 칸 값 [Stat48] / 큰 제목(도착 카드, 주행 중 상태 줄, 동의 화면) [Title48]
 * - 화면 제목·공유 안내 제목·좁은 창 숫자 칸 값 [Title40] / 카드 제목·빈 목록 문장 [Title36]
 * - 주 행동 버튼·대화상자 제목·머문 시간·코스 순서 이름 [Title32] / 주행 중 숫자 라벨·도착 카드 여행 제목 [Title32Medium]
 * - 본문·메타 [Body28] / 보조 버튼·글자 버튼·선택 칩·머리말·알림 [Body28Strong] / 보조 문장·시각 [Body26]
 * - 번호 점 숫자 [Body26Strong] / 작은 칩·숫자 라벨 [Label24]
 */
object PleosType {
    val Title40 = TextStyle(fontSize = 40.sp, lineHeight = 56.sp, fontWeight = FontWeight.ExtraBold)
    val Title36 = TextStyle(fontSize = 36.sp, lineHeight = 52.sp, fontWeight = FontWeight.Bold)
    val Title32 = TextStyle(fontSize = 32.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold)
    val Title32Medium = TextStyle(fontSize = 32.sp, lineHeight = 44.sp, fontWeight = FontWeight.Medium)
    val Body28 = TextStyle(fontSize = 28.sp, lineHeight = 40.sp, fontWeight = FontWeight.Normal)
    val Body28Strong = TextStyle(fontSize = 28.sp, lineHeight = 40.sp, fontWeight = FontWeight.Medium)
    val Body26 = TextStyle(fontSize = 26.sp, lineHeight = 36.sp, fontWeight = FontWeight.Normal)
    val Body26Strong = TextStyle(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
    val Label24 = TextStyle(fontSize = 24.sp, lineHeight = 34.sp, fontWeight = FontWeight.Medium)

    /** 주행 중 큰 숫자 96/800. 가이드 표 밖 크기 */
    val Stat96 = TextStyle(fontSize = 96.sp, lineHeight = 120.sp, fontWeight = FontWeight.ExtraBold)
    /** 숫자 칸 값 48/800 */
    val Stat48 = TextStyle(fontSize = 48.sp, lineHeight = 64.sp, fontWeight = FontWeight.ExtraBold)
    /** 결과 제목 48/800 (도착 카드 "3곳을 다녀왔어요") */
    val Title48 = TextStyle(fontSize = 48.sp, lineHeight = 64.sp, fontWeight = FontWeight.ExtraBold, lineBreak = LineBreak.Heading)
}

private val Scheme = darkColorScheme(
    primary = PleosColor.Accent,
    onPrimary = PleosColor.OnAccent,
    secondary = PleosColor.Accent,
    background = PleosColor.Background,
    onBackground = PleosColor.Text,
    surface = PleosColor.Surface,
    onSurface = PleosColor.Text,
    surfaceVariant = PleosColor.SurfaceAccent,
    onSurfaceVariant = PleosColor.Text72,
    outline = PleosColor.SurfaceAccent,
    error = Color(0xFFFF4C28),
)

@Composable
fun TripRecapTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
