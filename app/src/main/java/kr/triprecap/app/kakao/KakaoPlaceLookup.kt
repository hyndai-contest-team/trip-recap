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

/** 카카오 로컬 API 응답 해석과 장소 이름 고르기. 네트워크와 분리해 단위 테스트한다 */
object KakaoParsers {
    /** [category] 는 카카오 카테고리 그룹 코드(AT4 관광명소 등) */
    data class Place(val name: String, val distanceM: Int, val category: String = "")

    /** 카테고리 검색 응답의 장소 목록. 이름이 빈 문서는 뺀다 */
    fun places(json: String, category: String): List<Place> {
        val docs = JSONObject(json).optJSONArray("documents") ?: return emptyList()
        return (0 until docs.length())
            .map { docs.getJSONObject(it) }
            .mapNotNull { d ->
                val name = d.optString("place_name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Place(name, d.optString("distance").toIntOrNull() ?: Int.MAX_VALUE, category)
            }
    }

    /**
     * 정차 이름으로 쓸 장소를 고른다 (docs/product-plan.md 2-1 장소 이름).
     * 반경 안에 관광명소(AT4)가 있으면 거리와 관계없이 그중 가장 가까운 곳, 없으면 모든 분류에서 가장 가까운 곳.
     * 고른 이름은 [cleanName] 으로 정리한다. 후보가 없으면 null
     */
    fun pick(candidates: List<Place>): String? {
        val pool = candidates.filter { it.category == ATTRACTION }.ifEmpty { candidates }
        return pool.minByOrNull { it.distanceM }?.let { cleanName(it.name) }
    }

    /**
     * 장소 이름 끝의 시설 이름(주차장, 인증센터, 캠핑장, 입구 등)을 뗀다.
     * 떼고 남은 말이 장소 이름(해변, 해수욕장, 공원, 시장, 역 등)으로 끝날 때만 뗀다.
     * 예: "경포해변인증센터" → "경포해변", "협재해수욕장 캠핑장" → "협재해수욕장". "솔향캠핑장"은 그대로 둔다
     */
    fun cleanName(name: String): String {
        val original = name.trim()
        var rest = original
        while (true) {
            val tail = FACILITY_TAILS.firstOrNull { rest.endsWith(it) } ?: break
            rest = rest.dropLast(tail.length).trimEnd()
        }
        // "속초해수욕장 주차장 입구"처럼 시설 이름이 이어져 있으면 모두 뗀 뒤에 판단한다
        return if (rest != original && rest.length >= 2 && PLACE_ENDINGS.any { rest.endsWith(it) }) rest else original
    }

    private const val ATTRACTION = "AT4"

    /** 긴 것부터 맞춘다("공영주차장"이 "주차장"보다 먼저) */
    private val FACILITY_TAILS = listOf(
        "공영주차장", "주차장", "인증센터", "오토캠핑장", "캠핑장", "야영장", "관광안내소", "안내소", "안내센터",
        "방문자센터", "탐방지원센터", "관리사무소", "매표소", "화장실", "출입구", "입구", "정문", "후문",
    ).sortedByDescending { it.length }

    private val PLACE_ENDINGS = listOf(
        "해변", "해수욕장", "해안", "공원", "시장", "항", "포구", "호수", "저수지", "계곡", "폭포", "산", "섬", "역", "터미널",
        "휴게소", "전망대", "마을", "수목원", "식물원", "박물관", "미술관", "기념관", "궁", "성", "숲", "동굴", "온천", "스키장",
    )

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
 * - 주변 장소: GET /v2/local/search/category.json?category_group_code=..&x=&y=&radius=&sort=distance&size=5
 *   전체 카테고리 검색이 없어서 관광명소·문화시설·숙박·음식점·카페를 따로 조회하고, [KakaoParsers.pick] 으로 고른다
 *   (관광명소 우선, 시설 이름 정리).
 * 키가 없거나 통신에 실패하면 IOException 을 던진다. 호출하는 쪽이 "방문지 n"으로 저장하고 나중에 다시 조회한다.
 * 보내는 값은 좌표뿐이다.
 */
class KakaoPlaceLookup(
    private val restApiKey: String,
    private val radiusM: Int = 150,
    private val baseUrl: String = "https://dapi.kakao.com",
) : PlaceLookup {

    override suspend fun nearbyPoi(point: GeoPoint): String? = coroutineScope {
        val candidates = CATEGORIES.map { code ->
            async {
                KakaoParsers.places(
                    get("/v2/local/search/category.json?category_group_code=$code&x=${point.lon}&y=${point.lat}&radius=$radiusM&sort=distance&size=$PER_CATEGORY"),
                    code,
                )
            }
        }.awaitAll().flatten()
        KakaoParsers.pick(candidates)
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
        /** 분류마다 받는 후보 수. 호출 수(분류당 1번)는 그대로다 */
        private const val PER_CATEGORY = 5
        /** AT4 관광명소, CT1 문화시설, AD5 숙박, FD6 음식점, CE7 카페 */
        private val CATEGORIES = listOf("AT4", "CT1", "AD5", "FD6", "CE7")
    }
}
