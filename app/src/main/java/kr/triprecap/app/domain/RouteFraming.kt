package kr.triprecap.app.domain

import kr.triprecap.app.domain.RouteProjection.Xy
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * 경로 그림의 표시 범위.
 * @property bounds [남서, 북동] 모서리 두 점. 점이 없으면 빈 목록이다. RouteProjection.fit 의 bounds 인자로 그대로 넘긴다
 * @property zoomed 방문지 범위로 확대했는지. true 면 화면이 전체 경로 미니맵을 함께 그린다
 */
data class RouteFrame(val bounds: List<GeoPoint>, val zoomed: Boolean)

/** 확대한 경로 그림에서 미니맵을 놓는 모서리. 선언 순서가 고를 때의 우선순위다 */
enum class MiniMapCorner {
    TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT;

    /** [width]×[height] 그림 안, 가장자리에서 [margin] 떨어진 [mapWidth]×[mapHeight] 미니맵의 왼쪽 위 좌표 */
    fun origin(width: Double, height: Double, mapWidth: Double, mapHeight: Double, margin: Double): Xy = Xy(
        if (this == TOP_LEFT || this == BOTTOM_LEFT) margin else width - margin - mapWidth,
        if (this == TOP_LEFT || this == TOP_RIGHT) margin else height - margin - mapHeight,
    )
}

/** 상세 화면의 경로 그림: 표시 범위 정하기와 번호 점 겹침 풀기 */
object RouteFraming {
    /** 방문지 범위 대각선이 전체 범위 대각선의 이 비율보다 작으면 확대한다 */
    const val ZOOM_RATIO = 0.3
    /** 확대했을 때 범위의 긴 변 최소 길이. 1곳에 몰린 점들을 골목 수준까지 확대하지 않게 한다 */
    const val MIN_SPAN_M = 1_500.0
    /** 번호 점 중심 사이 최소 간격(px) */
    const val MIN_GAP_PX = 90.0

    private const val M_PER_DEG_LAT = 111_195.0

    /** 경로는 모든 드라이브의 점, 기준 점은 방문지·숙박(잠깐 정차 제외) */
    fun frame(trip: Trip): RouteFrame = frame(
        route = trip.drives.flatMap { d -> d.track.map { it.point } },
        places = trip.stops.filter { it.kind != StopKind.BRIEF }.mapNotNull { it.point },
    )

    fun frame(route: List<GeoPoint>, places: List<GeoPoint>): RouteFrame {
        val all = route + places
        if (all.isEmpty()) return RouteFrame(emptyList(), false)
        val full = corners(all)
        if (places.size < 2) return RouteFrame(full, false)
        val zoom = corners(places)
        if (diagonalM(zoom) >= ZOOM_RATIO * diagonalM(full)) return RouteFrame(full, false)
        return RouteFrame(widen(zoom), true)
    }

    private fun corners(points: List<GeoPoint>) = listOf(
        GeoPoint(points.minOf { it.lat }, points.minOf { it.lon }),
        GeoPoint(points.maxOf { it.lat }, points.maxOf { it.lon }),
    )

    private fun diagonalM(c: List<GeoPoint>) = c[0].distanceM(c[1])

    /** 긴 변이 MIN_SPAN_M 보다 짧으면 중심 기준 MIN_SPAN_M 정사각형으로 넓힌다 */
    private fun widen(c: List<GeoPoint>): List<GeoPoint> {
        val (sw, ne) = c
        val midLat = (sw.lat + ne.lat) / 2
        val midLon = (sw.lon + ne.lon) / 2
        val mPerDegLon = M_PER_DEG_LAT * cos(Math.toRadians(midLat))
        val longSide = max((ne.lat - sw.lat) * M_PER_DEG_LAT, (ne.lon - sw.lon) * mPerDegLon)
        if (longSide >= MIN_SPAN_M) return c
        val halfLat = MIN_SPAN_M / 2 / M_PER_DEG_LAT
        val halfLon = MIN_SPAN_M / 2 / mPerDegLon
        return listOf(GeoPoint(midLat - halfLat, midLon - halfLon), GeoPoint(midLat + halfLat, midLon + halfLon))
    }

    /** 각도 후보: 밀어내는 방향에서 시작해 30도씩 양쪽으로 번갈아 돈다 (0, +30, -30, +60 … +180) */
    private val turnsDeg = listOf(0) + (30..150 step 30).flatMap { listOf(it, -it) } + 180
    private const val MAX_RINGS = 10
    private const val EPS = 1e-6

    /**
     * 화면 좌표의 점들을 순서대로 놓으며, 앞서 놓은 점과 중심 거리가 [minGap] 보다 가까우면 나중 점을 옮긴다.
     * 옮길 자리: 가장 가까운 앞 점(거리가 같으면 앞 순서)에서 [minGap] 떨어진 원 위를, 그 점에서 멀어지는 방향부터 30도씩 돌며 찾는다.
     * 빈자리가 없으면 반지름을 [minGap] 씩 늘린다. 두 점이 완전히 겹치면 오른쪽(+x)부터 찾는다.
     * 난수를 쓰지 않으므로 같은 입력에는 같은 결과가 나온다.
     */
    fun separate(points: List<Xy>, minGap: Double = MIN_GAP_PX): List<Xy> {
        val placed = ArrayList<Xy>(points.size)
        for (p in points) placed += place(p, placed, minGap)
        return placed
    }

    private fun place(p: Xy, placed: List<Xy>, minGap: Double): Xy {
        fun clear(q: Xy) = placed.all { hypot(q.x - it.x, q.y - it.y) >= minGap - EPS }
        if (clear(p)) return p
        val anchor = placed.minBy { hypot(p.x - it.x, p.y - it.y) }
        val dx = p.x - anchor.x
        val dy = p.y - anchor.y
        val base = if (hypot(dx, dy) < EPS) 0.0 else atan2(dy, dx)
        var last = p
        for (ring in 1..MAX_RINGS) {
            for (turn in turnsDeg) {
                val a = base + Math.toRadians(turn.toDouble())
                val q = Xy(anchor.x + cos(a) * minGap * ring, anchor.y + sin(a) * minGap * ring)
                if (clear(q)) return q
                last = q
            }
        }
        return last
    }

    /** 화면 좌표의 점 하나. [radius] 는 그려지는 원 바깥 테두리까지의 반지름 */
    data class Mark(val at: Xy, val radius: Double)

    /**
     * 미니맵을 놓을 모서리. [marks] 와 겹치는 수가 가장 적은 모서리 가운데, [lines] 가 미니맵 칸 안을 지나는 길이가 가장 짧은 곳.
     * 둘 다 같으면 [MiniMapCorner] 선언 순서(왼쪽 위 → 오른쪽 위 → 왼쪽 아래 → 오른쪽 아래)에서 앞의 것.
     * [lines] 는 그림 밖으로 나간 점을 포함해도 된다. 칸 안에 든 부분만 센다.
     */
    fun miniMapCorner(
        width: Double,
        height: Double,
        mapWidth: Double,
        mapHeight: Double,
        margin: Double,
        marks: List<Mark>,
        lines: List<List<Xy>>,
    ): MiniMapCorner {
        data class Score(val corner: MiniMapCorner, val dots: Int, val line: Double)
        val scores = MiniMapCorner.entries.map { c ->
            val o = c.origin(width, height, mapWidth, mapHeight, margin)
            val box = Box(o.x, o.y, o.x + mapWidth, o.y + mapHeight)
            Score(
                c,
                marks.count { box.touches(it) },
                lines.sumOf { pts -> pts.zipWithNext().sumOf { (a, b) -> box.clippedLength(a, b) } },
            )
        }
        // minWith 은 같은 값이면 앞의 것을 남긴다
        return scores.minWith(compareBy<Score> { it.dots }.thenBy { it.line }).corner
    }

    private class Box(val left: Double, val top: Double, val right: Double, val bottom: Double) {
        /** 원과 사각형이 겹치는지: 사각형에서 원 중심까지 가장 가까운 거리가 반지름보다 짧다 */
        fun touches(m: Mark): Boolean {
            val dx = m.at.x - m.at.x.coerceIn(left, right)
            val dy = m.at.y - m.at.y.coerceIn(top, bottom)
            return hypot(dx, dy) < m.radius
        }

        /** 선분 a–b 가운데 사각형 안에 든 부분의 길이 (Liang–Barsky 자르기) */
        fun clippedLength(a: Xy, b: Xy): Double {
            val dx = b.x - a.x
            val dy = b.y - a.y
            var t0 = 0.0
            var t1 = 1.0
            val edges = listOf(-dx to a.x - left, dx to right - a.x, -dy to a.y - top, dy to bottom - a.y)
            for ((p, q) in edges) {
                if (p == 0.0) {
                    if (q < 0) return 0.0
                    continue
                }
                val t = q / p
                if (p < 0) t0 = max(t0, t) else t1 = minOf(t1, t)
                if (t0 > t1) return 0.0
            }
            return (t1 - t0) * hypot(dx, dy)
        }
    }
}
