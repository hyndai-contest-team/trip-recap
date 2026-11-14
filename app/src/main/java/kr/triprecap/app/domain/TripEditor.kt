package kr.triprecap.app.domain

/** 정차 중 사용자가 하는 수정 (docs/product-plan.md 2-4, 2-7, 2-8). 모두 새 Trip 을 돌려준다 */
object TripEditor {
    /** 빈 제목이면 자동 제목으로 돌아간다 */
    fun renameTrip(trip: Trip, title: String): Trip = trip.copy(customTitle = title.trim().ifEmpty { null })

    /** 빈 이름이면 바꾸지 않는다. 사용자가 정한 이름은 장소 조회로 덮어쓰지 않는다 */
    fun renameStop(trip: Trip, stopId: Int, name: String): Trip {
        val n = name.trim()
        if (n.isEmpty()) return trip
        return trip.mapStop(stopId) { it.copy(name = n, placeResolved = true) }
    }

    fun deleteStop(trip: Trip, stopId: Int): Trip = trip.copy(stops = trip.stops.filter { it.id != stopId })

    /** 빈 메모는 메모 삭제다 */
    fun setMemo(trip: Trip, stopId: Int, memo: String?): Trip =
        trip.mapStop(stopId) { it.copy(memo = memo?.trim()?.ifEmpty { null }) }

    fun setExcludedFromShare(trip: Trip, stopId: Int, excluded: Boolean): Trip =
        trip.mapStop(stopId) { it.copy(excludedFromShare = excluded) }

    private fun Trip.mapStop(stopId: Int, f: (Stop) -> Stop): Trip = copy(stops = stops.map { if (it.id == stopId) f(it) else it })
}
