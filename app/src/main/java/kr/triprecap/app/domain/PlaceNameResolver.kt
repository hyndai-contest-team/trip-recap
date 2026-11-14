package kr.triprecap.app.domain

/** 좌표로 장소 이름을 찾는 외부 조회 (카카오 로컬 API). 통신 실패는 예외로 알린다 */
interface PlaceLookup {
    /** 좌표 주변 POI 이름. 주변에 없으면 null */
    suspend fun nearbyPoi(point: GeoPoint): String?
    /** 좌표의 행정구역. 찾지 못하면 null */
    suspend fun region(point: GeoPoint): RegionName?
}

data class RegionName(val sigungu: String, val dong: String?)

/** 내비 목적지 이름과 좌표 */
data class NamedPoint(val name: String, val point: GeoPoint)

data class ResolvedPlace(
    val name: String?,
    val region: String?,
    /** false 면 통신 실패로 끝내지 못했다. 온라인일 때 다시 조회한다 */
    val complete: Boolean,
)

/**
 * 정차 이름을 단계별로 정한다 (docs/product-plan.md 2-1 장소 이름)
 * ① 내비 목적지(300m 안)로 도착했으면 그 이름 ② 주변 POI ③ 행정동 ④ 통신 실패면 이름 없이 두고 나중에 다시 조회
 */
class PlaceNameResolver(private val lookup: PlaceLookup, private val destinationRadiusM: Double = 300.0) {

    suspend fun resolve(point: GeoPoint?, currentName: String?, destinations: List<NamedPoint>): ResolvedPlace {
        if (point == null) return ResolvedPlace(currentName, null, complete = true)
        var failed = false
        val region = try { lookup.region(point) } catch (e: Exception) { failed = true; null }
        var name = currentName
            ?: destinations.filter { it.point.distanceM(point) <= destinationRadiusM }.minByOrNull { it.point.distanceM(point) }?.name
        if (name == null) {
            name = try { lookup.nearbyPoi(point) } catch (e: Exception) { failed = true; null }
        }
        if (name == null) name = region?.dong
        return ResolvedPlace(name, region?.sigungu, complete = !failed)
    }

    /** 여행 안에서 아직 조회가 끝나지 않은 정차를 채운다. 바뀐 것이 없으면 같은 객체를 돌려준다 */
    suspend fun resolveStops(trip: Trip, destinations: List<NamedPoint>): Trip {
        if (trip.stops.all { it.placeResolved }) return trip
        return trip.copy(
            stops = trip.stops.map { s ->
                if (s.placeResolved) s
                else resolve(s.point, s.name, destinations).let { r -> s.copy(name = r.name, region = r.region ?: s.region, placeResolved = r.complete) }
            },
        )
    }
}
