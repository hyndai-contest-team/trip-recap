package kr.triprecap.app.domain

import java.time.Instant
import java.time.ZoneId

/**
 * 정차(Stay)를 방문지 / 잠깐 정차 / 숙박으로 나눈다. (docs/product-plan.md 2-1, 골든 시나리오 2·4·5)
 *
 * - HOME·OFFICE 300m 안: 정차로 저장하지 않는다 (null)
 * - 숙박: 6시간 이상 48시간 이하 + 오전 3시를 포함
 * - 방문지: 도어 열림 + 10분 이상
 * - 잠깐 정차: 그 밖 (10분 미만이거나 도어가 열리지 않음)
 * - 직전 정차에서 150m 안에 다시 서면 하나로 합친다. 외기온은 먼저 도착한 정차의 값을 유지한다
 *
 * 48시간 상한은 docs/product-plan.md 2-1 이동을 닫는 시점 ③(정차 48시간 초과)과 같은 값이다. 공항 장기 주차가 숙박으로 이어지는 것을 막는다.
 */
class StopClassifier(private val zone: ZoneId = ZoneId.of("Asia/Seoul")) {

    fun classify(stay: Stay, places: KnownPlaces): StopKind? {
        if (places.isHomeOrOffice(stay.point)) return null
        val d = stay.durationMs
        if (d in LODGING_MIN_MS..LODGING_MAX_MS && includesNight(stay.arrivedAt, stay.departedAt)) return StopKind.LODGING
        if (stay.doorOpened && d >= VISIT_MIN_MS) return StopKind.VISIT
        return StopKind.BRIEF
    }

    /** 직전 정차 [previous] 에서 150m 안에 다시 선 경우 합친 정차를 돌려준다. 아니면 null */
    fun merge(previous: Stop, stay: Stay): Stay? {
        val a = previous.point ?: return null
        val b = stay.point ?: return null
        if (a.distanceM(b) > MERGE_RADIUS_M) return null
        return Stay(
            arrivedAt = previous.arrivedAt,
            departedAt = stay.departedAt,
            point = a,
            doorOpened = previous.doorOpened || stay.doorOpened,
            placeName = previous.name ?: stay.placeName,
            rainy = previous.rainy || stay.rainy,
            // 먼저 도착한 정차의 도착 때 값. 그 정차가 값을 받지 못했으면 합친 구간에서 처음 받은 값이 뒤 정차의 값이다
            outsideTempC = previous.outsideTempC ?: stay.outsideTempC,
        )
    }

    /** 구간 안에 현지 시각 오전 3시가 들어 있는지 */
    private fun includesNight(from: Long, to: Long): Boolean {
        var threeAm = Instant.ofEpochMilli(from).atZone(zone).toLocalDate().atTime(NIGHT_HOUR, 0).atZone(zone)
        if (threeAm.toInstant().toEpochMilli() < from) threeAm = threeAm.plusDays(1)
        return threeAm.toInstant().toEpochMilli() <= to
    }

    companion object {
        const val VISIT_MIN_MS = 10 * 60_000L
        const val LODGING_MIN_MS = 6 * 3_600_000L
        const val LODGING_MAX_MS = 48 * 3_600_000L
        const val MERGE_RADIUS_M = 150.0
        const val NIGHT_HOUR = 3
    }
}
