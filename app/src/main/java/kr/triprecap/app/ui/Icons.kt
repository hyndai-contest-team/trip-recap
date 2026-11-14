package kr.triprecap.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 선 아이콘. 화면 디자인의 svg 에 있던 viewBox 좌표와 선 굵기를 그대로 옮겼다(material-icons 의존성을 두지 않는다).
 * [viewBox] 는 아이콘의 기본 크기(px)이기도 하다.
 */
enum class AppIcon(internal val viewBox: Float, internal val stroke: Float) {
    /** 뒤로 꺾쇠 (TopBar) */
    Back(32f, 4f),
    /** 설정 톱니 (목록 TopBar) */
    Settings(40f, 3.5f),
    /** 연필 (제목 바꾸기). 색 Text72 */
    Pencil(40f, 3f),
    /** 체크 (켜진 선택 칩) */
    Check(28f, 3.5f),
    /** 빈 원 (꺼진 선택 칩). 색 Text50 */
    Circle(28f, 2.5f),
    /** 오른쪽 꺾쇠 (펼치기 행) */
    ChevronRight(32f, 4f),
    /** 돋보기 (검색 입력칸) */
    Search(32f, 3.5f),
}

/** 아이콘 그림만 그린다(장식). 누를 수 있게 하려면 [CircleIconButton] 을 쓴다 */
@Composable
fun AppIconView(icon: AppIcon, modifier: Modifier = Modifier, size: Dp = icon.viewBox.dp, color: Color = PleosColor.Text) {
    Canvas(modifier.size(size)) { drawAppIcon(icon, color) }
}

private fun DrawScope.drawAppIcon(icon: AppIcon, color: Color) {
    val k = size.minDimension / icon.viewBox
    val w = icon.stroke * k
    val stroke = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun o(x: Float, y: Float) = Offset(x * k, y * k)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, o(x1, y1), o(x2, y2), strokeWidth = w, cap = StrokeCap.Round)
    fun poly(closed: Boolean, vararg xy: Float) {
        val path = Path().apply {
            moveTo(xy[0] * k, xy[1] * k)
            var i = 2
            while (i < xy.size) { lineTo(xy[i] * k, xy[i + 1] * k); i += 2 }
            if (closed) close()
        }
        drawPath(path, color, style = stroke)
    }
    fun circle(cx: Float, cy: Float, r: Float) = drawCircle(color, r * k, o(cx, cy), style = stroke)

    when (icon) {
        AppIcon.Back -> poly(false, 20f, 6f, 10f, 16f, 20f, 26f)
        AppIcon.ChevronRight -> poly(false, 13f, 7f, 22f, 16f, 13f, 25f)
        AppIcon.Check -> poly(false, 5f, 14.5f, 11f, 20.5f, 23f, 7.5f)
        AppIcon.Circle -> circle(14f, 14f, 10.5f)
        AppIcon.Search -> { circle(14f, 14f, 9f); line(21f, 21f, 28f, 28f) }
        AppIcon.Pencil -> { poly(true, 8f, 32f, 9.5f, 24.5f, 26f, 8f, 32f, 14f, 15.5f, 30.5f); line(22f, 12f, 28f, 18f) }
        AppIcon.Settings -> {
            circle(20f, 20f, 5f)
            circle(20f, 20f, 11.5f)
            line(20f, 2.5f, 20f, 6.5f); line(20f, 33.5f, 20f, 37.5f)
            line(2.5f, 20f, 6.5f, 20f); line(33.5f, 20f, 37.5f, 20f)
            line(29.9f, 10.1f, 32.4f, 7.6f); line(10.1f, 10.1f, 7.6f, 7.6f)
            line(29.9f, 29.9f, 32.4f, 32.4f); line(10.1f, 29.9f, 7.6f, 32.4f)
        }
    }
}
