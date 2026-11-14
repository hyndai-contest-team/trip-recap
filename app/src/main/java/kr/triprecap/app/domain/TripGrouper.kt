package kr.triprecap.app.domain

/**
 * 드라이브를 이동으로 묶고, 닫힌 이동을 판정한다. (docs/product-plan.md 2-1 이동 묶기, 판정 3단계)
 *
 * 묶기
 * - 드라이브 사이 간격이 6시간 미만이면 같은 이동이다
 * - 닫는 시점 ①: HOME 300m 안에 도착하면 닫는다
 * - 닫는 시점 ②: 한 정차에 6시간 서 있으면 그 시점(도착 + 6시간)에 닫는다. 그때 그 정차가 숙박이고 HOME 이 등록돼 있으면 닫지 않는다
 * - 닫는 시점 ③: 숙박으로 열어 둔 정차가 48시간을 넘으면 도착 + 48시간 + 1ms 에 닫는다
 * - ②로 닫은 뒤 같은 정차가 숙박(6~48시간 + 오전 3시 포함)이 되고 HOME 이 등록돼 있으면 닫았던 이동을 다시 연다.
 *   숙박 상한이 48시간이므로 48시간 안에 다시 출발한 경우만 이어진다
 * - ②③으로 닫은 이동의 마지막 정차는 닫은 시각까지로 적는다. 다시 열면 실제 출발 시각까지 늘린다
 * - ②③으로 닫은 이동은 endedAway = true 다. 열린 이동이 HOME 300m 안에 서 있는 경우(도착 뒤 HOME 이 등록됨)만 false 로 둔다
 *
 * 판정: 닫을 때 TripJudge 결과로 category 를 정한다 (DAILY → 일상 이동, TRIP → 여행, ASK → 묻는 중).
 * 단골은 [StopSummary] 기록에서 이 이동의 시작 전 정차만 센다. 판정 시각은 이동이 끝난 시각(endedAt)이다.
 *
 * 상태를 갖지 않는다. 열린 이동과 마지막으로 닫힌 이동은 호출하는 쪽이 들고 있다가 다시 넘긴다.
 */
class TripGrouper(val classifier: StopClassifier = StopClassifier()) {

    /**
     * open: 아직 열려 있는 이동. closed: 이번 호출로 닫힌 이동(시각 순서).
     * reopened: 이번 호출로 다시 열려 지금도 열려 있는 이동(= open). 다시 연 뒤 같은 호출에서 다시 닫혔으면 closed 에만 들어 있다
     */
    data class Result(val open: Trip?, val closed: List<Trip> = emptyList(), val reopened: Trip? = null)

    /**
     * 드라이브 1개가 끝났을 때. [stayBefore] 는 이 드라이브 직전의 정차다.
     * [lastClosed] 는 마지막으로 닫힌 이동이다. ②로 닫혔고 [stayBefore] 가 그 뒤 정차이면 다시 열 수 있다.
     */
    fun onDrive(
        open: Trip?,
        stayBefore: Stay?,
        drive: Drive,
        places: KnownPlaces,
        log: List<StopSummary> = emptyList(),
        lastClosed: Trip? = null,
    ): Result {
        val settled = settle(open, stayBefore, places, log, lastClosed)
        val closed = settled.closed.toMutableList()
        var trip = settled.open
        if (trip != null && stayBefore != null) trip = absorb(trip, stayBefore, places)
        trip = trip?.copy(drives = trip.drives + drive) ?: Trip(drives = listOf(drive))
        if (places.isHome(drive.endPoint)) {
            closed += close(trip, places, log, away = false)
            trip = null
        }
        val reopened = trip?.takeIf { settled.reopened != null }
        return Result(trip, closed, reopened)
    }

    /**
     * 서 있는 동안 이동을 닫거나 다시 연다. [stay] 는 열린 이동(없으면 [lastClosed])의 마지막 드라이브 뒤 정차이고,
     * 도착부터 지금(또는 출발)까지의 구간이다. 언제 부르든 닫는 시각은 정차 도착 시각으로 정해지므로 결과가 같다.
     */
    fun settle(
        open: Trip?,
        stay: Stay?,
        places: KnownPlaces,
        log: List<StopSummary> = emptyList(),
        lastClosed: Trip? = null,
    ): Result {
        if (stay == null) return Result(open)
        if (open != null) {
            val at = closeAt(open, stay, places) ?: return Result(open)
            val cut = stay.copy(departedAt = at)
            return Result(null, listOf(close(absorb(open, cut, places), places, log, away = !places.isHome(stay.point))))
        }
        val candidate = lastClosed?.takeIf { it.endedAway && it.drives.last().endedAt == stay.arrivedAt } ?: return Result(null)
        val base = candidate.copy(closed = false, endedAway = false, category = TripCategory.TRIP)
        if (!lodges(stay, absorb(base, stay, places), places)) return Result(null)
        return Result(base, reopened = base)
    }

    /** 열린 이동에 지금까지의 정차를 붙여 본 모습 (화면 미리보기용). 이동을 닫지 않는다 */
    fun preview(open: Trip, currentStay: Stay?, places: KnownPlaces): Trip =
        if (currentStay == null) open else absorb(open, currentStay, places)

    /** 열린 이동을 [stay] 중에 닫아야 하면 닫는 시각. 아니면 null */
    private fun closeAt(open: Trip, stay: Stay, places: KnownPlaces): Long? {
        if (stay.durationMs < SPLIT_GAP_MS) return null
        if (lodges(stay, absorb(open, stay, places), places)) return null
        // 48시간째까지 숙박으로 열려 있었으면 ③, 아니면 ②
        val atMax = stay.copy(departedAt = stay.arrivedAt + StopClassifier.LODGING_MAX_MS)
        val wasLodging = stay.durationMs > StopClassifier.LODGING_MAX_MS && lodges(atMax, absorb(open, atMax, places), places)
        return stay.arrivedAt + if (wasLodging) StopClassifier.LODGING_MAX_MS + 1 else SPLIT_GAP_MS
    }

    /** [stay] 를 붙인 [withStay] 의 마지막 정차가 숙박이고 HOME 이 등록돼 있으면 true. 이때는 이동을 이어 간다 */
    private fun lodges(stay: Stay, withStay: Trip, places: KnownPlaces): Boolean {
        val last = withStay.stops.lastOrNull()?.takeIf { it.departedAt == stay.departedAt }
        return last?.kind == StopKind.LODGING && places.home != null
    }

    /**
     * 정차를 이동의 정차 목록에 넣는다.
     * - 마지막 정차가 마지막 드라이브 뒤에 있으면(②로 닫았다가 다시 연 이동) 그 정차를 [stay] 의 출발 시각까지 늘린다
     * - 직전 정차와 150m 안이면 합친다
     */
    private fun absorb(trip: Trip, stay: Stay, places: KnownPlaces): Trip {
        val last = trip.stops.lastOrNull()
        if (last != null && last.departedAt > trip.drives.last().endedAt) return extend(trip, last, stay, places)
        val adjacent = last != null && last.departedAt == trip.drives.last().startedAt
        val merged = if (adjacent) classifier.merge(last!!, stay) else null
        val effective = merged ?: stay
        val kept = if (merged != null) trip.stops.dropLast(1) else trip.stops
        val kind = classifier.classify(effective, places) ?: return trip.copy(stops = kept)
        val stop = Stop(
            id = if (merged != null) last!!.id else (trip.stops.maxOfOrNull { it.id } ?: 0) + 1,
            kind = kind,
            name = effective.placeName,
            point = effective.point,
            arrivedAt = effective.arrivedAt,
            departedAt = effective.departedAt,
            rainy = effective.rainy,
            doorOpened = effective.doorOpened,
            outsideTempC = effective.outsideTempC,
            memo = if (merged != null) last!!.memo else null,
        )
        return trip.copy(stops = kept + stop)
    }

    /** 닫을 때 붙인 정차 [last] 를 [stay] 의 출발 시각까지 늘린다. 이름·메모 등 사용자가 본 값은 그대로 둔다 */
    private fun extend(trip: Trip, last: Stop, stay: Stay, places: KnownPlaces): Trip {
        val longer = Stay(
            arrivedAt = last.arrivedAt,
            departedAt = stay.departedAt,
            point = last.point ?: stay.point,
            doorOpened = last.doorOpened || stay.doorOpened,
            placeName = last.name ?: stay.placeName,
            rainy = last.rainy || stay.rainy,
            outsideTempC = last.outsideTempC ?: stay.outsideTempC,
        )
        val kept = trip.stops.dropLast(1)
        val kind = classifier.classify(longer, places) ?: return trip.copy(stops = kept)
        val stop = last.copy(
            kind = kind,
            name = longer.placeName,
            point = longer.point,
            departedAt = longer.departedAt,
            doorOpened = longer.doorOpened,
            rainy = longer.rainy,
            outsideTempC = longer.outsideTempC,
        )
        return trip.copy(stops = kept + stop)
    }

    private fun close(trip: Trip, places: KnownPlaces, log: List<StopSummary>, away: Boolean): Trip {
        val regulars = Regulars.from(log, now = trip.endedAt, excludeFrom = trip.startedAt)
        val category = when (TripJudge.judge(trip, places, regulars)) {
            Verdict.DAILY -> TripCategory.DAILY
            Verdict.TRIP -> TripCategory.TRIP
            Verdict.ASK -> TripCategory.PENDING
        }
        return trip.copy(closed = true, category = category, endedAway = away)
    }

    companion object {
        const val SPLIT_GAP_MS = 6 * 3_600_000L
    }
}
