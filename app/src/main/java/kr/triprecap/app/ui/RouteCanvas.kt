package kr.triprecap.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.triprecap.app.domain.GeoPoint
import kr.triprecap.app.domain.RouteFrame
import kr.triprecap.app.domain.RouteFraming
import kr.triprecap.app.domain.RouteProjection
import kr.triprecap.app.domain.Stop
import kr.triprecap.app.domain.StopKind
import kr.triprecap.app.domain.Trip

/** 번호 점 둘레 테두리 색. 그림 바탕(#131417)과 같게 해서 선과 점을 떼어 보이게 한다 */
private val RingColor = PleosColor.BackgroundLow
/** 옮겨진 번호 점과 원래 위치를 잇는 선. 흰색 50% */
private val LeaderColor = Color(0x80FFFFFF)

/**
 * 지도 타일 없이 경로 선과 방문지 번호 점만 그린다 (상세 화면의 경로 그림).
 * 바탕과 모서리는 부르는 쪽이 준다(`Modifier.clip(…).background(PleosColor.BackgroundLow)`).
 *
 * - 표시 범위는 [RouteFraming.frame] 이 정한다. 방문지가 한 지역에 몰려 확대했으면 그림 밖으로 나가는 선을 잘라 그리고
 *   전체 경로 미니맵(220×160)을 둔다. 모서리는 [RouteFraming.miniMapCorner] 가 점·선을 피해 고른다(기본 왼쪽 위).
 * - 방문지·숙박 점은 [RouteFraming.separate] 로 중심 간격 90 이상 떼어 놓고, 옮겨진 점은 원래 위치까지 가는 선으로 잇는다.
 * - 번호는 방문지만 센다. 숙박은 "숙", 잠깐 정차는 회색 작은 점. [selectedStopId] 의 점은 흰 바탕 큰 원.
 * - [compact] = true 면 목록 카드용(240×112): 선 4, 방문지·숙박 점 반지름 6, 번호·미니맵·잠깐 정차 없음, 확대하지 않고 전체 경로를 보인다.
 *
 * 경로 점이 없거나 1개뿐이어도 그린다(점 하나는 가운데에 놓인다).
 */
@Composable
fun RouteCanvas(trip: Trip, modifier: Modifier = Modifier, selectedStopId: Int? = null, compact: Boolean = false) {
    val measurer = rememberTextMeasurer()
    val model = remember(trip) { RouteModel.of(trip) }
    Canvas(modifier) {
        if (compact) drawCompact(model) else drawFull(model, selectedStopId, measurer)
    }
}

/** 한 여행의 그림 재료. 화면 크기와 무관한 것만 미리 계산한다 */
private class RouteModel(
    /** 드라이브마다 따로 그린다. 드라이브 사이(정차한 곳)를 직선으로 잇지 않는다 */
    val drives: List<List<GeoPoint>>,
    /** 좌표가 있는 정차. 여행 순서 */
    val stops: List<Stop>,
    /** 방문지 번호(1부터). 숙박·잠깐 정차는 없음 */
    val numbers: Map<Int, Int>,
    val frame: RouteFrame,
    /** 경로와 정차 전체를 담는 범위(미니맵·축소 모드) */
    val fullBounds: List<GeoPoint>,
) {
    companion object {
        fun of(trip: Trip): RouteModel {
            val drives = trip.drives.map { d -> d.track.map { it.point } }.filter { it.isNotEmpty() }
            val stops = trip.stops.filter { it.point != null }
            val numbers = trip.visits.mapIndexed { i, s -> s.id to i + 1 }.toMap()
            val all = drives.flatten() + stops.mapNotNull { it.point }
            return RouteModel(
                drives = drives,
                stops = stops,
                numbers = numbers,
                frame = RouteFraming.frame(trip),
                // places 를 비우면 frame 은 확대하지 않고 전체 범위 모서리를 돌려준다
                fullBounds = RouteFraming.frame(all, emptyList()).bounds,
            )
        }
    }
}

private fun DrawScope.routePath(points: List<GeoPoint>, w: Double, h: Double, pad: Double, bounds: List<GeoPoint>): Path? =
    pathOf(RouteProjection.fit(points, w, h, pad, bounds))

private fun pathOf(xy: List<RouteProjection.Xy>): Path? {
    if (xy.size < 2) return null
    return Path().apply {
        moveTo(xy[0].x.toFloat(), xy[0].y.toFloat())
        for (i in 1 until xy.size) lineTo(xy[i].x.toFloat(), xy[i].y.toFloat())
    }
}

private fun DrawScope.drawCompact(m: RouteModel) {
    if (m.fullBounds.isEmpty()) return
    val w = size.width.toDouble()
    val h = size.height.toDouble()
    // 목록 카드의 미니 그림: 240×112 안에서 선이 가장자리에서 20 안쪽에 있다
    val pad = 20.dp.toPx().toDouble()
    val stroke = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    for (d in m.drives) routePath(d, w, h, pad, m.fullBounds)?.let { drawPath(it, PleosColor.Accent, style = stroke) }
    val places = m.stops.filter { it.kind != StopKind.BRIEF }
    val xy = RouteProjection.fit(places.map { it.point!! }, w, h, pad, m.fullBounds)
    val r = 6.dp.toPx()
    for (p in xy) drawCircle(PleosColor.Accent, r, Offset(p.x.toFloat(), p.y.toFloat()))
}

private fun DrawScope.drawFull(m: RouteModel, selectedStopId: Int?, measurer: TextMeasurer) {
    if (m.frame.bounds.isEmpty()) return
    val w = size.width.toDouble()
    val h = size.height.toDouble()
    val pad = 72.dp.toPx().toDouble()
    val bounds = m.frame.bounds

    // 정차 점 위치. 잠깐 정차는 제자리, 방문지·숙박은 겹치지 않게 떼어 놓는다
    val all = RouteProjection.fit(m.stops.map { it.point!! }, w, h, pad, bounds)
    val placeIdx = m.stops.indices.filter { m.stops[it].kind != StopKind.BRIEF }
    val moved = RouteFraming.separate(placeIdx.map { all[it] }, RouteFraming.MIN_GAP_PX * density)
    val placed = all.toMutableList()
    placeIdx.forEachIndexed { k, i -> placed[i] = moved[k] }

    // 경로 선
    val stroke = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    val lines = m.drives.map { RouteProjection.fit(it, w, h, pad, bounds) }
    val paths = lines.mapNotNull(::pathOf)
    if (m.frame.zoomed) {
        clipRect { paths.forEach { drawPath(it, PleosColor.Accent, style = stroke) } }
        // 미니맵은 점과 선 위에 그리므로, 그려질 점(테두리 포함)과 선을 피하는 모서리에 둔다
        val marks = m.stops.mapIndexed { i, s ->
            val r = when {
                s.kind == StopKind.BRIEF -> 11
                s.id == selectedStopId -> 33
                else -> 27
            }
            RouteFraming.Mark(placed[i], r.dp.toPx().toDouble())
        }
        val mw = MiniMapWidth.toPx().toDouble()
        val mh = MiniMapHeight.toPx().toDouble()
        val margin = MiniMapMargin.toPx().toDouble()
        val corner = RouteFraming.miniMapCorner(w, h, mw, mh, margin, marks, lines)
        val o = corner.origin(w, h, mw, mh, margin)
        drawMiniMap(m, Offset(o.x.toFloat(), o.y.toFloat()))
    } else {
        paths.forEach { drawPath(it, PleosColor.Accent, style = stroke) }
    }

    // 옮겨진 점은 원래 위치까지 가는 선으로 잇는다
    val leaderWidth = 2.dp.toPx()
    for (i in placeIdx) {
        val from = all[i]
        val to = placed[i]
        if (from != to) drawLine(LeaderColor, Offset(from.x.toFloat(), from.y.toFloat()), Offset(to.x.toFloat(), to.y.toFloat()), leaderWidth, StrokeCap.Round)
    }

    // 잠깐 정차(지름 16) → 방문지·숙박 → 선택된 점 순서로 그려 선택된 점이 맨 위에 오게 한다
    m.stops.forEachIndexed { i, s ->
        if (s.kind != StopKind.BRIEF) return@forEachIndexed
        val c = Offset(placed[i].x.toFloat(), placed[i].y.toFloat())
        drawCircle(RingColor, 11.dp.toPx(), c)
        drawCircle(PleosColor.Idle, 8.dp.toPx(), c)
    }
    val normalStyle = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, color = PleosColor.OnAccent)
    val selectedStyle = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold, color = PleosColor.OnAccent)
    val order = placeIdx.sortedBy { m.stops[it].id == selectedStopId }
    for (i in order) {
        val s = m.stops[i]
        val selected = s.id == selectedStopId
        val c = Offset(placed[i].x.toFloat(), placed[i].y.toFloat())
        // 번호 점: 반지름 24(선택 30) 원 + 바탕색 테두리 6 (원 둘레 바깥으로 3)
        val r = (if (selected) 30 else 24).dp.toPx()
        drawCircle(RingColor, r + 3.dp.toPx(), c)
        drawCircle(if (selected) PleosColor.Text else PleosColor.Accent, r, c)
        val label = if (s.kind == StopKind.LODGING) "숙" else m.numbers[s.id]?.toString() ?: continue
        val text = measurer.measure(label, if (selected) selectedStyle else normalStyle)
        drawText(text, topLeft = Offset(c.x - text.size.width / 2f, c.y - text.size.height / 2f))
    }
}

private val MiniMapWidth = 220.dp
private val MiniMapHeight = 160.dp
private val MiniMapMargin = 24.dp

/** 미니맵(220×160, 그림 가장자리에서 24): 바탕 #1D1E21 반지름 16, 전체 경로 선 3 앰버, 확대 영역 사각형 2 Text72. [topLeft] 는 미니맵 왼쪽 위 */
private fun DrawScope.drawMiniMap(m: RouteModel, topLeft: Offset) {
    if (m.fullBounds.isEmpty()) return
    val left = topLeft.x
    val top = topLeft.y
    val mw = MiniMapWidth.toPx()
    val mh = MiniMapHeight.toPx()
    val radius = CornerRadius(16.dp.toPx())
    val box = RoundRect(left, top, left + mw, top + mh, radius)
    drawRoundRect(PleosColor.Background, Offset(left, top), Size(mw, mh), radius)
    clipPath(Path().apply { addRoundRect(box) }) {
        translate(left, top) {
            val pad = 16.dp.toPx().toDouble()
            val line = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            for (d in m.drives) routePath(d, mw.toDouble(), mh.toDouble(), pad, m.fullBounds)?.let { drawPath(it, PleosColor.Accent, style = line) }
            val corners = RouteProjection.fit(m.frame.bounds, mw.toDouble(), mh.toDouble(), pad, m.fullBounds)
            if (corners.size == 2) {
                // 남서·북동 모서리. 화면 y 는 북쪽이 작다
                val x0 = minOf(corners[0].x, corners[1].x).toFloat()
                val x1 = maxOf(corners[0].x, corners[1].x).toFloat()
                val y0 = minOf(corners[0].y, corners[1].y).toFloat()
                val y1 = maxOf(corners[0].y, corners[1].y).toFloat()
                drawRoundRect(
                    PleosColor.Text72,
                    Offset(x0, y0),
                    Size((x1 - x0).coerceAtLeast(4.dp.toPx()), (y1 - y0).coerceAtLeast(4.dp.toPx())),
                    CornerRadius(4.dp.toPx()),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }
    }
}
