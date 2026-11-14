package kr.triprecap.app.domain

/**
 * 차량 신호 → 드라이브·정차(TripRecorder) → 이동(TripGrouper). 실제 주행과 시나리오 재생이 같은 이 경로를 쓴다.
 * 저장은 하지 않는다. 호출할 때마다 [Update] 를 돌려주고, 호출하는 쪽이 그것을 저장한다.
 *
 * 닫는 시점 (TripGrouper 참고)
 * - HOME 300m 안 도착: 그 드라이브가 끝날 때
 * - 집이 아닌 곳에 6시간 정차: 6시간이 지난 뒤 첫 tick 또는 첫 이벤트에서 닫는다. 닫은 시각은 도착 + 6시간으로 적는다
 * - 숙박으로 열어 둔 정차 48시간 초과: 같은 방식으로 도착 + 48시간에 닫는다
 * - ②로 닫은 이동은 그 정차가 숙박이 되는 첫 tick(오전 3시 뒤) 또는 다음 드라이브에서 다시 연다
 *
 * 프로세스 재시작
 * 실제 차에서는 시동을 끄면 앱 프로세스가 끝나서 서 있는 동안 tick 이 돌지 않는다.
 * 다시 켜진 뒤에는 저장된 열린 이동(또는 마지막으로 닫힌 이동)의 마지막 드라이브 종료부터 지금까지를 정차로 보고,
 * 첫 tick·첫 이벤트에서 위 규칙을 그대로 적용한다. 닫는 시각이 정차 도착 시각으로 정해지므로
 * 앱이 계속 켜져 있던 경우와 결과(이동 구성, 정차 종류·시각, 분류)가 같다.
 * 이를 위해 호출하는 쪽은 재시작 때 [restore] 에 열린 이동과 마지막으로 닫힌 이동을 함께 넘긴다.
 */
class TripPipeline(
    val recorder: TripRecorder = TripRecorder(),
    private val grouper: TripGrouper = TripGrouper(),
) {
    /**
     * 한 번의 [onEvent]·[tick] 결과. 호출하는 쪽은 closed·reopened 를 저장하고 summaries 를 정차 요약 기록에 더한다.
     * 같은 이동이 closed 와 reopened 에 함께 들어 있는 일은 없다.
     */
    data class Update(
        /** 이번에 닫힌 이동(시각 순서). category·endedAway 가 채워져 있다. 다시 연 뒤 다시 닫힌 이동이면 id 가 이미 저장된 값이다 */
        val closed: List<Trip> = emptyList(),
        /** 6시간 정차로 닫혔다가 숙박이 돼 다시 열린 이동. id 는 닫힐 때와 같고 closed = false 다. 호출 뒤의 [open] 과 같다 */
        val reopened: Trip? = null,
        /**
         * 정차 요약. 정차 1개마다 1건이고, 그 정차 다음 드라이브가 끝날 때 낸다(재시작 뒤 정차도 같은 때).
         * 집·회사 근처처럼 이동의 정차가 되지 않은 정차도 방문지 아님으로 들어 있다. 방문지 여부는 합치기 전 그 정차만 보고 정한다
         */
        val summaries: List<StopSummary> = emptyList(),
    )

    var places: KnownPlaces = KnownPlaces()

    /**
     * 단골 판정에 쓰는 정차 요약 기록 (docs/product-plan.md 2-1 단골, 정차 요약).
     * 호출하는 쪽이 저장소에서 읽어 넣는다. 파이프라인이 새로 낸 요약([Update.summaries])은 여기에도 덧붙인다.
     * 저장소에서 다시 읽어 통째로 바꿔 넣어도 된다
     */
    var stopLog: List<StopSummary> = emptyList()

    /** 아직 닫히지 않은 이동 */
    var open: Trip? = null
        private set

    /** 마지막으로 닫힌 이동. endedAway 이면 다시 열 후보다. 다시 열면 null 이 된다 */
    var lastClosed: Trip? = null
        private set

    /** 끝난 정차. 다음 드라이브가 끝날 때 이동에 붙인다 */
    private var pendingStay: Stay? = null

    /** 열린 이동의 내용을 바꾼다 (이름 조회, 사용자 수정, 저장 뒤 id). 마지막으로 닫힌 이동은 그대로 둔다 */
    fun restore(openTrip: Trip?) {
        open = openTrip
    }

    /**
     * 앱을 다시 시작했을 때 저장해 둔 상태를 이어 받는다.
     * [lastClosedTrip] 은 분류와 관계없이 가장 최근에 닫힌 이동이다. 없으면 null.
     * 이 이동이 endedAway 이고 그 정차가 숙박이 되면 다시 연다. 열린 이동이 없을 때는 이 이동의 마지막 드라이브 종료부터를 정차로 본다
     * (집에서 밤을 보낸 정차도 요약으로 남는다).
     * 호출하는 쪽이 저장하면서 바꾼 닫힌 이동(id, 장소 이름)을 알려 줄 때도 쓴다.
     */
    fun restore(openTrip: Trip?, lastClosedTrip: Trip?) {
        open = openTrip
        lastClosed = lastClosedTrip
    }

    /** 열린 이동과 진행 중인 드라이브·정차를 모두 버린다 (전체 삭제) */
    fun clear() {
        open = null
        lastClosed = null
        pendingStay = null
        recorder.discardInProgress()
    }

    fun onEvent(event: TripRecorder.Event, now: Long): Update = run(now, recorder.onEvent(event, now))

    /** 이벤트가 없어도 주기적으로 부른다 (P 유지 3분, 6시간·48시간 정차로 닫기, 숙박이 되면 다시 열기) */
    fun tick(now: Long): Update = run(now, recorder.tick(now))

    /** 열린 이동에 지금까지의 정차를 붙인 모습. 열린 이동이 없으면 null */
    fun live(now: Long): Trip? {
        val base = open ?: return null
        return grouper.preview(base, pendingStay ?: recorder.currentStay(now), places)
    }

    private class Acc {
        val closed = mutableListOf<Trip>()
        /** 이번 호출에서 다시 연 이동의 시작 시각 */
        var reopenedStart: Long? = null
        val summaries = mutableListOf<StopSummary>()
    }

    private fun run(now: Long, outputs: List<TripRecorder.Output>): Update {
        val acc = Acc()
        for (o in outputs) when (o) {
            is TripRecorder.Output.StayEnded -> pendingStay = o.stay
            is TripRecorder.Output.DriveEnded -> {
                val stay = pendingStay ?: stayAcrossRestart(o.drive.startedAt)
                pendingStay = null
                stay?.let { summarize(it, acc) }
                apply(grouper.onDrive(open, stay, o.drive, places, stopLog, lastClosed), acc)
            }
        }
        val stay = pendingStay ?: recorder.currentStay(now) ?: stayAcrossRestart(recorder.driveStartedAt ?: now)
        if (stay != null) apply(grouper.settle(open, stay, places, stopLog, lastClosed), acc)
        // 다시 연 이동이 같은 호출에서 다시 닫혔으면 closed 에만 남긴다
        val reopened = open?.takeIf { it.startedAt == acc.reopenedStart }
        return Update(acc.closed.toList(), reopened, acc.summaries.toList())
    }

    private fun apply(r: TripGrouper.Result, acc: Acc) {
        open = r.open
        acc.closed += r.closed
        r.closed.lastOrNull()?.let { lastClosed = it }
        if (r.reopened != null) {
            acc.reopenedStart = r.reopened.startedAt
            lastClosed = null
        }
    }

    /** 끝난 정차의 요약을 낸다. 이동의 정차가 되지 않는 정차(집·회사 근처)도 방문지가 아닌 것으로 적는다 */
    private fun summarize(stay: Stay, acc: Acc) {
        val s = StopLog.summarize(stay, grouper.classifier.classify(stay, places)) ?: return
        acc.summaries += s
        stopLog = stopLog + s
    }

    /**
     * 시동을 끄면 앱 프로세스가 끝나서 정차 기록(도어, 위치)이 남지 않는다.
     * 다시 켜진 뒤 기록기에 정차가 없으면, 저장된 마지막 드라이브(열린 이동, 없으면 마지막으로 닫힌 이동) 종료부터 [until] 까지를 정차로 본다.
     * 시동을 껐다는 것은 사람이 내렸다는 뜻으로 보고 도어는 열린 것으로 둔다.
     * 외기온은 그 드라이브에 저장한 도착 때 값을 쓰고, 없으면 다시 켠 뒤 첫 출발 전에 처음 받은 값(서 있는 동안 처음 받은 값)을 쓴다.
     */
    private fun stayAcrossRestart(until: Long): Stay? {
        val last = (open ?: lastClosed)?.drives?.lastOrNull() ?: return null
        if (until <= last.endedAt) return null
        return Stay(
            arrivedAt = last.endedAt, departedAt = until, point = last.endPoint, doorOpened = true, rainy = last.rainy,
            outsideTempC = last.arrivalTempC ?: recorder.tempBeforeFirstDrive,
        )
    }
}
