package kr.triprecap.app.scenario

import kr.triprecap.app.domain.Gear
import kr.triprecap.app.domain.GeoPoint
import kr.triprecap.app.domain.SeatId
import kr.triprecap.app.domain.StopLog
import kr.triprecap.app.domain.StopSummary
import java.time.ZonedDateTime

/**
 * 시나리오를 시각 순서대로 쌓는 도우미.
 * 경로는 주요 지점 좌표를 직선으로 이은 근사 경로다. 실제 도로 형상이 아니다.
 */
class ScenarioBuilder(private val name: String, private val zero: ZonedDateTime) {
    private val steps = mutableListOf<Step>()
    private val chapters = mutableListOf<Chapter>()
    private val priorLog = mutableListOf<StopSummary>()

    /** 시작일 0시 기준 day 일째 h:m 의 경과 ms (시작 시각 기준) */
    fun t(day: Int, h: Int, m: Int = 0, s: Int = 0): Long {
        val target = zero.toLocalDate().plusDays(day.toLong()).atTime(h, m, s).atZone(zero.zone)
        return target.toInstant().toEpochMilli() - zero.toInstant().toEpochMilli()
    }

    fun add(at: Long, event: ScenarioEvent) = apply { steps += Step(at, event) }
    fun chapter(title: String, at: Long) = apply { chapters += Chapter(title, at) }
    fun home(p: GeoPoint) = add(0, ScenarioEvent.Home(p))
    fun office(p: GeoPoint) = add(0, ScenarioEvent.Office(p))
    fun seats(at: Long, vararg seats: SeatId) = add(at, ScenarioEvent.Seats(seats.toSet()))
    /** 시나리오 시작 전의 방문 기록 1건(정차 요약). [from]·[to] 는 t() 값이고 음수 day 도 된다 */
    fun priorVisit(from: Long, to: Long, p: GeoPoint) = apply {
        val zeroMs = zero.toInstant().toEpochMilli()
        priorLog += StopSummary(StopLog.round(p), zeroMs + from, zeroMs + to, visit = true)
    }

    /** [at] 에 외기온 [celsius] 를 받는다 */
    fun temp(at: Long, celsius: Double) = add(at, ScenarioEvent.OutsideTemp(celsius))

    fun wiper(from: Long, to: Long) = add(from, ScenarioEvent.Wiper(true)).add(to, ScenarioEvent.Wiper(false))

    /** 처음 위치와 P 단 */
    fun startParked(at: Long, p: GeoPoint, placeName: String? = null) =
        add(at, ScenarioEvent.Location(p, placeName)).add(at, ScenarioEvent.GearTo(Gear.PARK))

    /** 도어를 열었다가 1분 뒤 닫는다 */
    fun door(at: Long) = add(at, ScenarioEvent.Door(true)).add(at + 60_000L, ScenarioEvent.Door(false))

    /**
     * [from] 에 D 단으로 출발해 [path] 를 일정한 속도로 달리고 [to] 에 P 단으로 선다.
     * 위치 표본은 [sampleS] 초마다 낸다. [blackoutM] 구간(출발점부터의 거리 m)에서는 표본을 내지 않는다(터널).
     */
    fun drive(
        from: Long,
        to: Long,
        path: List<GeoPoint>,
        arriveName: String? = null,
        sampleS: Int = 20,
        blackoutM: ClosedFloatingPointRange<Double>? = null,
    ) = apply {
        require(path.size >= 2 && to > from)
        val cum = DoubleArray(path.size)
        for (i in 1 until path.size) cum[i] = cum[i - 1] + path[i - 1].distanceM(path[i])
        val total = cum.last()
        add(from, ScenarioEvent.GearTo(Gear.DRIVE))
        var at = from + sampleS * 1_000L
        while (at < to - 1_000L) {
            val d = total * (at - from) / (to - from)
            if (blackoutM == null || d !in blackoutM) add(at, ScenarioEvent.Location(pointAt(path, cum, d)))
            at += sampleS * 1_000L
        }
        add(to - 1_000L, ScenarioEvent.Location(path.last(), arriveName))
        add(to, ScenarioEvent.GearTo(Gear.PARK))
    }

    private fun pointAt(path: List<GeoPoint>, cum: DoubleArray, d: Double): GeoPoint {
        var i = 1
        while (i < path.size - 1 && cum[i] < d) i++
        val seg = cum[i] - cum[i - 1]
        val f = if (seg <= 0.0) 0.0 else ((d - cum[i - 1]) / seg).coerceIn(0.0, 1.0)
        val a = path[i - 1]
        val b = path[i]
        return GeoPoint(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)
    }

    fun build(): Scenario = Scenario(
        name = name,
        epochAtZero = zero.toInstant().toEpochMilli(),
        // 같은 시각의 이벤트는 넣은 순서를 지킨다 (sortedBy 는 안정 정렬)
        steps = steps.sortedBy { it.atMs },
        chapters = chapters.sortedBy { it.startMs },
        priorLog = priorLog.sortedBy { it.arrivedAt },
    )
}
