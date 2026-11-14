package kr.triprecap.app.domain

/** 판정 결과. DAILY: 확실한 일상, TRIP: 확실한 여행, ASK: 애매한 이동(도착 카드에서 묻는다) */
enum class Verdict { DAILY, TRIP, ASK }

/**
 * 닫힌 이동을 확실한 일상 / 확실한 여행 / 애매한 이동으로 나눈다. (docs/product-plan.md 2-1 판정 3단계)
 *
 * 일상 → 여행 → 애매 순서로 본다. 잠깐 정차([StopKind.BRIEF])는 어디에서도 세지 않는다.
 * - 일상: 한쪽 끝이 집, 다른 끝이 회사(각 300m 안, 방향 무관)이고 방문지·숙박 0개.
 *   또는 방문지가 1곳, 숙박 0개이고 그 방문지가 단골
 * - 여행: 방문지 2곳 이상, 또는 숙박 1회 이상, 또는 경로 중 한 점이 모든 기준점에서 30km 이상.
 *   기준점은 집·회사 중 알려진 것이고, 둘 다 모르면 이동의 출발점이다
 * - 애매: 그 밖
 */
object TripJudge {
    const val FAR_M = 30_000.0

    fun judge(trip: Trip, places: KnownPlaces, regulars: Regulars): Verdict {
        val visits = trip.stops.filter { it.kind == StopKind.VISIT }
        val lodgings = trip.stops.count { it.kind == StopKind.LODGING }
        if (visits.isEmpty() && lodgings == 0 && isCommute(trip, places)) return Verdict.DAILY
        if (visits.size == 1 && lodgings == 0 && regulars.isRegular(visits.single().point)) return Verdict.DAILY
        if (visits.size >= 2 || lodgings >= 1 || goesFar(trip, places)) return Verdict.TRIP
        return Verdict.ASK
    }

    /** 한쪽 끝이 집 300m 안, 다른 끝이 회사 300m 안 */
    private fun isCommute(trip: Trip, places: KnownPlaces): Boolean {
        val start = trip.startPoint ?: return false
        val end = trip.endPoint ?: return false
        return (near(start, places.home) && near(end, places.office)) ||
            (near(start, places.office) && near(end, places.home))
    }

    private fun near(p: GeoPoint, ref: GeoPoint?): Boolean = ref != null && p.distanceM(ref) <= KnownPlaces.RADIUS_M

    /** 경로 점 중 하나라도 모든 기준점에서 30km 이상 떨어져 있으면 true */
    private fun goesFar(trip: Trip, places: KnownPlaces): Boolean {
        val refs = listOfNotNull(places.home, places.office).ifEmpty { listOfNotNull(trip.startPoint) }
        if (refs.isEmpty()) return false
        return trip.drives.any { d -> d.track.any { tp -> refs.all { tp.point.distanceM(it) >= FAR_M } } }
    }
}
