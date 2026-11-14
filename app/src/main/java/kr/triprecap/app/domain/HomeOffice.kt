package kr.triprecap.app.domain

/** 집·회사 칸 */
enum class PlaceSlot(val title: String) {
    HOME("집으로 쓰는 위치"),
    OFFICE("회사로 쓰는 위치"),
}

/** 집·회사 위치를 어디서 얻었는지. 설정 화면에 [label] 로 보인다 */
enum class PlaceSource(val label: String) {
    /** 내비 즐겨찾기 HOME/OFFICE */
    NAVI("내비 등록"),
    /** 사용자가 설정에서 "지금 위치로 정하기"로 정함 */
    USER("직접 정함"),
    /** 정차 요약으로 추정 (PlaceEstimator) */
    ESTIMATE("추정"),
    NONE("없음"),
}

data class PlaceChoice(val point: GeoPoint?, val source: PlaceSource)

operator fun KnownPlaces.get(slot: PlaceSlot): GeoPoint? = when (slot) {
    PlaceSlot.HOME -> home
    PlaceSlot.OFFICE -> office
}

fun KnownPlaces.with(slot: PlaceSlot, point: GeoPoint?): KnownPlaces = when (slot) {
    PlaceSlot.HOME -> copy(home = point)
    PlaceSlot.OFFICE -> copy(office = point)
}

/**
 * 기록에 쓰는 집·회사 위치 (docs/product-plan.md 2-1 집·회사 위치, 2-9).
 * 칸마다 내비 즐겨찾기 > 사용자가 정한 위치 > 추정 순서로 고른다.
 */
data class PlaceSetup(val home: PlaceChoice, val office: PlaceChoice) {
    /** 기록(TripPipeline)과 공유 위치 가리기에 넘기는 값 */
    val known: KnownPlaces get() = KnownPlaces(home.point, office.point)

    operator fun get(slot: PlaceSlot): PlaceChoice = when (slot) {
        PlaceSlot.HOME -> home
        PlaceSlot.OFFICE -> office
    }

    companion object {
        const val NO_PLACE = "위치가 정해지지 않았어요"

        fun of(navi: KnownPlaces, user: KnownPlaces, estimated: KnownPlaces): PlaceSetup {
            fun pick(slot: PlaceSlot): PlaceChoice =
                navi[slot]?.let { PlaceChoice(it, PlaceSource.NAVI) }
                    ?: user[slot]?.let { PlaceChoice(it, PlaceSource.USER) }
                    ?: estimated[slot]?.let { PlaceChoice(it, PlaceSource.ESTIMATE) }
                    ?: PlaceChoice(null, PlaceSource.NONE)
            return PlaceSetup(pick(PlaceSlot.HOME), pick(PlaceSlot.OFFICE))
        }

        /**
         * 내비·사용자 값과 저장된 추정이 모두 없는 칸만 [PlaceEstimator] 로 채운 새 추정 값을 돌려준다.
         * 이미 있는 추정은 바꾸지 않는다(한 번 정한 추정은 사용자가 되돌리기 전까지 그대로 쓴다).
         * 회사 추정은 이번에 정해진 집(내비 > 사용자 > 추정) 300m 안을 빼고 고른다.
         */
        fun estimate(log: List<StopSummary>, now: Long, navi: KnownPlaces, user: KnownPlaces, estimated: KnownPlaces): KnownPlaces {
            fun open(slot: PlaceSlot) = navi[slot] == null && user[slot] == null && estimated[slot] == null
            var result = estimated
            if (open(PlaceSlot.HOME)) result = result.with(PlaceSlot.HOME, PlaceEstimator.estimateHome(log, now))
            if (open(PlaceSlot.OFFICE)) {
                val home = of(navi, user, result).home.point
                result = result.with(PlaceSlot.OFFICE, PlaceEstimator.estimateOffice(log, now, home))
            }
            return result
        }

        /**
         * 설정 행에 쓰는 지역 이름. 저장된 정차 중 [point] 300m 안에서 시·군·구가 채워진 가장 가까운 정차의 시·군·구다.
         * 집·회사 좌표를 장소 이름 조회로 보내지 않으려고 기기 안 기록만 쓴다. 없으면 null
         */
        fun regionNear(point: GeoPoint?, trips: List<Trip>): String? {
            if (point == null) return null
            return trips.asSequence().flatMap { it.stops }
                .mapNotNull { s -> val p = s.point; val r = s.region; if (p != null && r != null) r to p.distanceM(point) else null }
                .filter { it.second <= KnownPlaces.RADIUS_M }
                .minByOrNull { it.second }?.first
        }

        /**
         * 설정 행의 설명 줄. 위치가 없으면 "위치가 정해지지 않았어요".
         * 있으면 "출처 · 지역 이름", 지역 이름을 모르면 소수 3자리 좌표("추정 · 37.515, 127.106")
         */
        fun describe(choice: PlaceChoice, regionName: String?): String {
            val p = choice.point ?: return NO_PLACE
            val where = regionName ?: StopLog.round(p).let { "${it.lat}, ${it.lon}" }
            return "${choice.source.label} · $where"
        }
    }
}
