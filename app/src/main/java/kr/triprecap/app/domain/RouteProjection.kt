package kr.triprecap.app.domain

import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.tan

/**
 * 위경도 → 화면 좌표. 지도 타일 없이 경로 선만 그릴 때 쓴다 (docs/product-plan.md 5절).
 * Web Mercator 로 투영한 뒤 가로세로 비율을 지키며 상자 안에 가운데 맞춘다.
 * 휴대폰 페이지(web/js/projection.js)가 같은 계산을 한다. 두 구현은 web/test/fixtures/projection.json 으로 비교한다.
 */
object RouteProjection {
    data class Xy(val x: Double, val y: Double)

    /** 단위 정사각형 [0,1] 안의 Web Mercator 좌표. y 는 북쪽이 0 */
    fun mercator(p: GeoPoint): Xy {
        val x = (p.lon + 180.0) / 360.0
        val lat = Math.toRadians(p.lat)
        val y = (1.0 - ln(tan(lat) + 1.0 / kotlin.math.cos(lat)) / PI) / 2.0
        return Xy(x, y)
    }

    /**
     * [bounds] 의 점들이 [width]×[height] 상자의 [padding] 안쪽에 들어가도록 맞춘 변환으로 [points] 를 옮긴다.
     * [bounds] 가 비면 [points] 를 기준으로 삼는다. 점이 하나뿐이거나 모두 같으면 상자 가운데에 둔다.
     */
    fun fit(points: List<GeoPoint>, width: Double, height: Double, padding: Double, bounds: List<GeoPoint> = points): List<Xy> {
        val ref = (bounds.ifEmpty { points }).map(::mercator)
        if (ref.isEmpty()) return emptyList()
        val minX = ref.minOf { it.x }
        val maxX = ref.maxOf { it.x }
        val minY = ref.minOf { it.y }
        val maxY = ref.maxOf { it.y }
        val dx = maxX - minX
        val dy = maxY - minY
        val innerW = width - 2 * padding
        val innerH = height - 2 * padding
        val scale = when {
            dx <= 0.0 && dy <= 0.0 -> 0.0
            dx <= 0.0 -> innerH / dy
            dy <= 0.0 -> innerW / dx
            else -> min(innerW / dx, innerH / dy)
        }
        val offX = (width - dx * scale) / 2.0
        val offY = (height - dy * scale) / 2.0
        return points.map(::mercator).map { Xy(offX + (it.x - minX) * scale, offY + (it.y - minY) * scale) }
    }
}
