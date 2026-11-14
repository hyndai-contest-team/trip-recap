package kr.triprecap.app.kakao

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kr.triprecap.app.domain.GeoPoint
import kr.triprecap.app.domain.PlaceLookup
import kr.triprecap.app.domain.RegionName
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 카카오 로컬 API 응답 해석. 네트워크와 분리해 단위 테스트한다 */
object KakaoParsers {
    data class Place(val name: String, val distanceM: Int)

    /** 카테고리 검색 응답에서 가장 가까운 장소 */
    fun nearestPlace(json: String): Place? {
        val docs = JSONObject(json).optJSONArray("documents") ?: return null
        return (0 until docs.length())
            .map { docs.getJSONObject(it) }
            .mapNotNull { d ->
                val name = d.optString("place_name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Place(name, d.optString("distance").toIntOrNull() ?: Int.MAX_VALUE)
            }
            .minByOrNull { it.distanceM }
    }

    /** 좌표→행정구역 응답. 행정동(H)을 먼저 쓰고 없으면 법정동(B) */
    fun region(json: String): RegionName? {
        val docs = JSONObject(json).optJSONArray("documents") ?: return null
        val list = (0 until docs.length()).map { docs.getJSONObject(it) }
        val d = list.firstOrNull { it.optString("region_type") == "H" } ?: list.firstOrNull() ?: return null
        val sigungu = d.optString("region_2depth_name").ifBlank { d.optString("region_1depth_name") }
        if (sigungu.isBlank()) return null
        return RegionName(sigungu, d.optString("region_3depth_name").takeIf { it.isNotBlank() })
    }
}

/**
 * 카카오 로컬 REST API 로 좌표 주변 장소 이름과 행정구역을 찾는다 (docs/product-plan.md 5절 결정).
 * - 좌표 → 행정구역: GET /v2/local/geo/coord2regioncode.json?x={경도}&y={위도}
 * - 주변 장소: GET /v2/local/search/category.json?category_group_code=..&x=&y=&radius=&sort=distance
 *   전체 카테고리 검색이 없어서 관광명소·문화시설·숙박·음식점·카페를 따로 조회해 가장 가까운 것을 쓴다.
 * 키가 없거나 통신에 실패하면 IOException 을 던진다. 호출하는 쪽이 "방문지 n"으로 저장하고 나중에 다시 조회한다.
 * 보내는 값은 좌표뿐이다.
 */
class KakaoPlaceLookup(
    private val restApiKey: String,
    private val radiusM: Int = 150,
    private val baseUrl: String = "https://dapi.kakao.com",
) : PlaceLookup {

    override suspend fun nearbyPoi(point: GeoPoint): String? = coroutineScope {
        CATEGORIES.map { code ->
            async {
                KakaoParsers.nearestPlace(
                    get("/v2/local/search/category.json?category_group_code=$code&x=${point.lon}&y=${point.lat}&radius=$radiusM&sort=distance&size=1"),
                )
            }
        }.awaitAll().filterNotNull().minByOrNull { it.distanceM }?.name
    }

    override suspend fun region(point: GeoPoint): RegionName? =
        KakaoParsers.region(get("/v2/local/geo/coord2regioncode.json?x=${point.lon}&y=${point.lat}"))

    private suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        if (restApiKey.isBlank()) throw IOException("카카오 REST API 키가 없다 (local.properties 의 kakao.restApiKey)")
        val conn = URL(baseUrl + path).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("Authorization", "KakaoAK $restApiKey")
            val code = conn.responseCode
            if (code != 200) throw IOException("카카오 로컬 API 응답 $code")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TIMEOUT_MS = 3_000
        /** AT4 관광명소, CT1 문화시설, AD5 숙박, FD6 음식점, CE7 카페 */
        private val CATEGORIES = listOf("AT4", "CT1", "AD5", "FD6", "CE7")
    }
}
