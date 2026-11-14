package kr.triprecap.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kr.triprecap.app.domain.Arrival
import kr.triprecap.app.domain.ArrivalKind
import kr.triprecap.app.domain.Gear
import kr.triprecap.app.domain.KnownPlaces
import kr.triprecap.app.domain.NamedPoint
import kr.triprecap.app.domain.PlaceNameResolver
import kr.triprecap.app.domain.PlaceSetup
import kr.triprecap.app.domain.PlaceSlot
import kr.triprecap.app.domain.StopLog
import kr.triprecap.app.domain.StopSummary
import kr.triprecap.app.domain.Trip
import kr.triprecap.app.domain.TripCategory
import kr.triprecap.app.domain.TripLists
import kr.triprecap.app.domain.TripPipeline
import kr.triprecap.app.domain.TripRecorder
import kr.triprecap.app.domain.TripSummarizer
import kr.triprecap.app.domain.with
import kr.triprecap.app.ports.Settings
import kr.triprecap.app.ports.TripStore
import kr.triprecap.app.ports.VoiceIo

/** 주행 중 화면에 쓰는 현재 상태 */
data class LiveStatus(
    /** 차가 움직일 수 있는 상태(P 단이 아님). 화면 제한의 시연 기준으로 쓴다 */
    val moving: Boolean = false,
    /** 기록 중인 여행 또는 드라이브가 있는지 */
    val recording: Boolean = false,
    val tripStartedAt: Long? = null,
    val visitCount: Int = 0,
    val distanceM: Double = 0.0,
)

/**
 * 차량 신호 → 기록(TripPipeline) → 저장(TripStore) → 도착 카드. 앱 프로세스에 하나만 둔다.
 * 실제 어댑터와 시나리오 재생기가 모두 onSignal 로 신호를 넣는다. 신호는 들어온 순서대로 하나씩 처리한다.
 *
 * 저장 (docs/product-plan.md 2-1)
 * - 닫힌 이동은 분류(여행 / 일상 이동 / 묻는 중)와 함께 모두 저장한다. 목록에는 여행만 보인다(TripLists.listed)
 * - 정차 요약은 나오는 대로 저장하고, 앱 시작 때 저장소에서 읽어 파이프라인에 넣는다
 * - 보관 기간: 일상 이동 7일(끝난 시각 기준), 정차 요약 30일(출발 시각 기준). 앱 시작 때와 tick 중 1시간마다 지운다.
 *   기준 시각은 실제 시각([wallClock])이다
 *
 * 도착 카드 (docs/product-plan.md 2-3)
 * - 확실한 여행이 집 도착으로 닫힘: 바로 "여행을 저장했어요" 카드 + 음성 한 문장(설정에서 끔)
 * - 애매한 이동이 집 도착으로 닫힘: 바로 묻는 카드, 음성 없음. [keep] → 여행, [discard] → 바로 지움.
 *   답 없이 닫거나([dismissArrival]), 보여 준 지 10분이 지나거나(tick, 실제 시각), 드라이브가 시작되면 일상 이동이 된다
 * - 확실한 일상: 카드 없음
 * - 집이 아닌 곳에 오래 서 있어서 닫힌 이동(endedAway): 닫힐 때는 카드를 띄우지 않고 id 를 Settings.deferredArrivalId 에 적어 둔다.
 *   다음 시동 때 정차 상태에서 띄운다(여행이면 "지난 여행을 저장했어요", 애매하면 묻는 카드). 그 전에 다시 열리면 띄우지 않는다
 *
 * 다음 시동을 아는 방법
 * - 프로세스가 새로 시작된 뒤 처음 들어온 P 단 신호(GearChanged(PARK)). 실제 차에서는 시동을 끄면 프로세스가 끝나고,
 *   켜면 BootReceiver → TripRecordingService → AppContainer.start → [start] 순서로 다시 시작한다
 * - 프로세스가 살아 있는 동안에는 [onIgnitionOn]. 실제 차량 모드는 IGNITION_STATE 가 꺼짐에서 ON·START 로 바뀔 때,
 *   시연은 debug 명령 `ignition` 과 설정 > 시연 도구 "시동 켜기 보내기"로 부른다
 * 그 전에 드라이브가 시작되면(P → D/R) 기다리던 카드는 버린다(묻는 중이면 일상 이동)
 *
 * 집·회사 위치 (docs/product-plan.md 2-1 집·회사 위치)
 * 칸마다 내비 즐겨찾기 > 사용자가 정한 위치 > 추정 순서로 고른다([PlaceSetup]). 추정은 내비·사용자 값과 저장된 추정이 모두 없는 칸만
 * 앱 시작, 정차 요약 저장, 내비 값 변경, 되돌리기 때 한 번 하고, 정해지면 Settings 에 저장해 계속 쓴다.
 *
 * 시연 시나리오([useScenario]): 골든 시나리오 결과가 저장된 사용자 값에 따라 달라지지 않게 시나리오의 HOME·OFFICE 만 쓰고,
 * 정차 요약은 저장하지 않으며(시나리오의 이전 기록과 함께 메모리에만 둔다) 추정과 tick 중 보관 기간 정리를 하지 않는다.
 */
class TripSession(
    private val store: TripStore,
    private val settings: Settings,
    private val resolver: PlaceNameResolver,
    private val voice: VoiceIo? = null,
    private val pipeline: TripPipeline = TripPipeline(),
    /** 실제 시각. 도착 카드가 떠 있던 시간과 보관 기간에 쓴다. 시나리오의 가상 시계와 따로 둔다 */
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    private val _live = MutableStateFlow(LiveStatus())
    val live: StateFlow<LiveStatus> = _live.asStateFlow()

    /** 화면에 띄울 도착 카드. 닫거나 답하면 null */
    private val _arrival = MutableStateFlow<Arrival?>(null)
    val arrival: StateFlow<Arrival?> = _arrival.asStateFlow()
    /** 지금 보이는 카드를 띄운 실제 시각 */
    private var arrivalShownAt = 0L

    /** 내비 즐겨찾기 HOME·OFFICE (시나리오에서는 시나리오가 준 값) */
    private val _naviPlaces = MutableStateFlow(KnownPlaces())
    val naviPlaces: StateFlow<KnownPlaces> = _naviPlaces.asStateFlow()

    /** 칸마다 고른 집·회사 위치와 출처. 설정 화면이 쓴다 */
    private val _placeSetup = MutableStateFlow(PlaceSetup.of(KnownPlaces(), KnownPlaces(), KnownPlaces()))
    val placeSetup: StateFlow<PlaceSetup> = _placeSetup.asStateFlow()

    /** 기록·공유에 쓰는 집·회사 위치 ([placeSetup] 의 좌표) */
    private val _places = MutableStateFlow(KnownPlaces())
    val places: StateFlow<KnownPlaces> = _places.asStateFlow()

    /** 최근 내비 목적지. 정차 이름을 정할 때 쓴다 */
    private val destinations = ArrayDeque<NamedPoint>()

    /** 프로세스가 시작된 뒤 아직 시동(첫 P 단)을 보지 못했는지 */
    private var awaitingIgnition = false
    /** 시연 시나리오를 재생 중인지 ([useScenario]) */
    private var scenarioMode = false
    /** 마지막으로 보관 기간 정리를 한 실제 시각. 아직 안 했으면 null */
    private var lastRetentionAt: Long? = null

    private val recordingAllowed: Boolean get() = settings.consentGiven.value && settings.recordingEnabled.value

    /**
     * 앱 시작 때: 보관 기간이 지난 것을 지우고, 정차 요약·열린 이동·마지막으로 닫힌 이동을 이어 받는다.
     * 묻는 중인 이동 중 다음 시동 카드를 기다리지 않고 끝난 지 10분이 지난 것은 일상 이동으로 바꾼다. 10분 안의 것은 다시 묻는다.
     * 집·회사 추정도 여기서 한 번 한다.
     */
    suspend fun start() = mutex.withLock {
        val now = wallClock()
        runRetention(now)
        pipeline.stopLog = store.stopSummaries()
        val all = store.trips.first()
        pipeline.restore(store.openTrip(), all.filter { it.closed }.maxByOrNull { it.endedAt })

        settings.deferredArrivalId?.let { id ->
            if (all.none { it.id == id && it.closed && it.category != TripCategory.DAILY }) settings.deferredArrivalId = null
        }
        val waiting = settings.deferredArrivalId
        val pending = all.filter { it.closed && it.category == TripCategory.PENDING && it.id != waiting }
        val recent = pending.filter { now - it.endedAt <= ARRIVAL_CARD_WINDOW_MS }.maxByOrNull { it.endedAt }
        for (t in pending) if (t.id != recent?.id) setCategory(t, TripCategory.DAILY)
        recent?.let { show(Arrival(it, ArrivalKind.ASK)) }

        awaitingIgnition = true
        estimatePlaces()
    }

    /** 내비 즐겨찾기 HOME·OFFICE 가 바뀌었을 때 (시나리오에서는 시나리오의 HOME·OFFICE 이벤트) */
    suspend fun setNaviPlaces(places: KnownPlaces) = mutex.withLock {
        _naviPlaces.value = places
        estimatePlaces()
    }

    /**
     * 시연 시나리오를 올릴 때. 시나리오의 HOME·OFFICE 만 쓰고(내비 값은 비운다), 단골 판정용 이전 기록 [priorLog] 를 넣는다.
     * 시연 중에는 정차 요약을 저장하지 않고 추정·보관 기간 정리를 하지 않는다. [leaveScenario] 로 끝낸다
     */
    suspend fun useScenario(priorLog: List<StopSummary>) = mutex.withLock {
        scenarioMode = true
        awaitingIgnition = false
        _naviPlaces.value = KnownPlaces()
        pipeline.stopLog = priorLog
        applyPlaces()
    }

    /** 시연 시나리오를 끝내고 실제 차량 신호로 돌아갈 때. 저장된 정차 요약과 사용자·추정 위치를 다시 쓴다 */
    suspend fun leaveScenario() = mutex.withLock {
        if (!scenarioMode) return@withLock
        scenarioMode = false
        _naviPlaces.value = KnownPlaces()
        pipeline.stopLog = store.stopSummaries()
        estimatePlaces()
    }

    fun onDestination(destination: NamedPoint?) {
        if (destination == null || destinations.lastOrNull() == destination) return
        destinations.addLast(destination)
        while (destinations.size > MAX_DESTINATIONS) destinations.removeFirst()
    }

    suspend fun onSignal(event: TripRecorder.Event, at: Long) = mutex.withLock {
        if (!recordingAllowed) {
            // 기록은 하지 않지만 주행 중 화면 제한을 위해 기어 상태는 본다
            if (event is TripRecorder.Event.GearChanged) _live.value = LiveStatus(moving = event.gear.isMoving())
            return@withLock
        }
        val before = pipeline.open
        val wasDriving = pipeline.recorder.isDriving
        val update = pipeline.onEvent(event, at)
        afterPipeline(before, wasDriving, update, at)
        if (awaitingIgnition && event is TripRecorder.Event.GearChanged && event.gear == Gear.PARK) {
            awaitingIgnition = false
            showDeferred()
        }
    }

    /** 이벤트가 없어도 주기적으로 부른다 (P 유지 3분 판정, 6시간 정차로 닫기, 묻는 카드 10분, 보관 기간, 화면 갱신) */
    suspend fun tick(now: Long) = mutex.withLock {
        val wall = wallClock()
        val shown = _arrival.value
        if (shown?.kind == ArrivalKind.ASK && wall - arrivalShownAt >= ARRIVAL_CARD_WINDOW_MS) unanswered(shown.trip.id)
        val last = lastRetentionAt
        if (!scenarioMode && (last == null || wall - last >= RETENTION_INTERVAL_MS || wall < last)) runRetention(wall)
        if (!recordingAllowed) return@withLock
        val before = pipeline.open
        val wasDriving = pipeline.recorder.isDriving
        val update = pipeline.tick(now)
        afterPipeline(before, wasDriving, update, now)
    }

    /**
     * 시동이 켜졌을 때(프로세스가 살아 있는 동안). 차가 서 있으면 다음 시동 때 띄우기로 한 카드를 띄운다.
     * 프로세스가 새로 시작된 경우는 [onSignal] 의 첫 P 단이 같은 일을 한다
     */
    suspend fun onIgnitionOn() = mutex.withLock {
        awaitingIgnition = false
        if (pipeline.recorder.isDriving && pipeline.recorder.stoppedAt == null) return@withLock
        showDeferred()
    }

    /** 카드를 닫는다. 묻는 카드면 답하지 않은 것으로 보고 일상 이동으로 바꾼다 */
    suspend fun dismissArrival() = mutex.withLock {
        val shown = _arrival.value ?: return@withLock
        if (shown.kind == ArrivalKind.ASK) unanswered(shown.trip.id) else clearArrival()
    }

    /** 묻는 카드의 [남기기]: 여행으로 저장한다. 아직 열어 보지 않은 새 여행이다 */
    suspend fun keep(id: Long) = keepAsTrip(id)

    /** 묻는 카드의 [지우기]: 바로 영구 삭제한다(일상 이동으로 보관하지 않는다) */
    suspend fun discard(id: Long) = onTripDeleted(id)

    /** 일상 이동 화면의 [여행으로 남기기]와 묻는 카드의 [남기기]: 분류를 여행으로 바꾼다 */
    suspend fun keepAsTrip(id: Long) = mutex.withLock {
        val trip = store.get(id) ?: return@withLock
        setCategory(trip, TripCategory.TRIP)
        if (_arrival.value?.trip?.id == id) clearArrival()
        if (settings.deferredArrivalId == id) settings.deferredArrivalId = null
    }

    /** 이름 조회를 끝내지 못한 정차를 다시 조회한다 (온라인이 됐을 때, 앱을 열었을 때) */
    suspend fun backfillNames() = mutex.withLock {
        for (trip in store.trips.first()) {
            val resolved = resolver.resolveStops(trip, emptyList())
            if (resolved !== trip) {
                store.save(resolved)
                syncPipeline(resolved)
            }
        }
    }

    /** 사용자가 여행을 고친 뒤: 열린 여행이나 마지막으로 닫힌 이동이면 기록기에도 반영한다 */
    suspend fun onTripEdited(edited: Trip) = mutex.withLock {
        // 화면이 markSeen 전에 받아 둔 여행을 고쳐 보내도 본 표시가 되돌아가지 않게 한다
        val trip = if (edited.seen || store.get(edited.id)?.seen != true) edited else edited.copy(seen = true)
        store.save(trip)
        syncPipeline(trip)
    }

    suspend fun onTripDeleted(id: Long) = mutex.withLock { deleteTrip(id) }

    /**
     * 사용자가 여행을 열어 봤을 때(도착 카드의 "여행 보기", 목록에서 열기) 부른다. 새 여행 표시를 끈다.
     * 없는 id 면 아무것도 하지 않는다
     */
    suspend fun markSeen(id: Long) = mutex.withLock {
        val trip = store.get(id) ?: return@withLock
        if (trip.seen) return@withLock
        val seen = trip.copy(seen = true)
        store.save(seen)
        syncPipeline(seen)
        _arrival.value?.let { if (it.trip.id == id) _arrival.value = it.copy(trip = seen) }
    }

    /** 모든 여행 지우기: 이동, 정차 요약, 추정한 집·회사, 기록 중인 이동을 모두 지운다. 사용자가 정한 위치는 설정이므로 남긴다 */
    suspend fun onAllDeleted() = mutex.withLock {
        store.deleteAll()
        store.deleteAllStopSummaries()
        pipeline.clear()
        pipeline.stopLog = emptyList()
        settings.setEstimatedPlaces(KnownPlaces())
        settings.deferredArrivalId = null
        clearArrival()
        applyPlaces()
    }

    /** 설정 "지금 위치로 정하기": 차의 마지막 위치를 그 칸의 위치로 정한다. 위치를 아직 모르면 false */
    suspend fun setPlaceToCurrent(slot: PlaceSlot): Boolean = mutex.withLock {
        val point = pipeline.recorder.lastLocation?.point ?: return@withLock false
        settings.setUserPlaces(settings.userPlaces.value.with(slot, point))
        applyPlaces()
        true
    }

    /** 설정 "추정으로 되돌리기": 사용자가 정한 위치와 저장된 추정을 지우고 다시 추정한다 */
    suspend fun resetPlaceToEstimate(slot: PlaceSlot) = mutex.withLock {
        settings.setUserPlaces(settings.userPlaces.value.with(slot, null))
        settings.setEstimatedPlaces(settings.estimatedPlaces.value.with(slot, null))
        estimatePlaces()
    }

    private suspend fun afterPipeline(before: Trip?, wasDriving: Boolean, update: TripPipeline.Update, now: Long) {
        if (update.summaries.isNotEmpty() && !scenarioMode) {
            store.addStopSummaries(update.summaries)
            estimatePlaces()
        }
        for (trip in update.closed) {
            // 기록 중에 열어 봤더라도 닫힌 결과는 아직 보지 않은 새 여행이다
            val saved = save(trip.copy(seen = false))
            // 다시 열 때 저장된 id·이름을 이어 쓰게 한다
            if (saved.startedAt == pipeline.lastClosed?.startedAt) pipeline.restore(pipeline.open, saved)
            onClosed(saved, now)
        }
        update.reopened?.let { r ->
            // 다시 열린 이동은 다음 시동 때 카드를 띄우지 않는다
            if (settings.deferredArrivalId == r.id) settings.deferredArrivalId = null
            if (_arrival.value?.trip?.id == r.id) clearArrival()
        }
        // 다시 열린 이동도 여기서 열린 이동으로 저장된다(같은 id, closed = false)
        val open = pipeline.open
        if (open != null && open !== before) pipeline.restore(save(open))
        if (!wasDriving && pipeline.recorder.isDriving) onDriveStarted()
        publishLive(now)
    }

    private suspend fun onClosed(saved: Trip, now: Long) {
        when {
            saved.category == TripCategory.DAILY -> Unit
            saved.endedAway -> defer(saved)
            // 방금(10분 안) 끝난 이동만 바로 카드로 보여 준다. 늦게 닫힌 여행은 목록에만 넣고, 묻는 중이면 일상 이동으로 둔다
            now - saved.endedAt > ARRIVAL_CARD_WINDOW_MS -> if (saved.category == TripCategory.PENDING) setCategory(saved, TripCategory.DAILY)
            saved.category == TripCategory.TRIP -> {
                show(Arrival(saved, ArrivalKind.SAVED))
                if (settings.arrivalVoiceEnabled.value) runCatching { voice?.speak(TripSummarizer.arrivalSentence(saved)) }
            }
            else -> show(Arrival(saved, ArrivalKind.ASK))
        }
    }

    /** 다음 시동 때 카드를 띄우도록 적어 둔다. 앞서 기다리던 묻는 이동은 답이 없었던 것으로 본다 */
    private suspend fun defer(trip: Trip) {
        settings.deferredArrivalId?.takeIf { it != trip.id }?.let { old -> store.get(old)?.let { dropUnanswered(it) } }
        settings.deferredArrivalId = trip.id
    }

    /** 다음 시동 때 띄우기로 한 카드를 띄운다. 그 사이 다시 열렸거나 지워졌으면 띄우지 않는다 */
    private suspend fun showDeferred() {
        val id = settings.deferredArrivalId ?: return
        settings.deferredArrivalId = null
        val trip = store.get(id)?.takeIf { it.closed } ?: return
        when (trip.category) {
            TripCategory.TRIP -> show(Arrival(trip, ArrivalKind.SAVED_EARLIER))
            TripCategory.PENDING -> show(Arrival(trip, ArrivalKind.ASK))
            TripCategory.DAILY -> Unit
        }
    }

    /** 드라이브가 시작되면 묻는 카드와 다음 시동 카드는 답이 없었던 것으로 본다 */
    private suspend fun onDriveStarted() {
        awaitingIgnition = false
        _arrival.value?.takeIf { it.kind == ArrivalKind.ASK }?.let { unanswered(it.trip.id) }
        settings.deferredArrivalId?.let { id ->
            settings.deferredArrivalId = null
            store.get(id)?.let { dropUnanswered(it) }
        }
    }

    /** 카드를 띄운다. 떠 있던 묻는 카드는 답이 없었던 것으로 본다 */
    private suspend fun show(arrival: Arrival) {
        _arrival.value?.takeIf { it.kind == ArrivalKind.ASK && it.trip.id != arrival.trip.id }?.let { unanswered(it.trip.id) }
        _arrival.value = arrival
        arrivalShownAt = wallClock()
    }

    /** 묻는 카드에 답하지 않았다: 일상 이동으로 바꾸고 카드를 닫는다 */
    private suspend fun unanswered(id: Long) {
        store.get(id)?.let { dropUnanswered(it) }
        if (_arrival.value?.trip?.id == id) clearArrival()
    }

    private suspend fun dropUnanswered(trip: Trip) {
        if (trip.category == TripCategory.PENDING) setCategory(trip, TripCategory.DAILY)
    }

    private fun clearArrival() {
        _arrival.value = null
    }

    private suspend fun setCategory(trip: Trip, category: TripCategory) {
        if (trip.category == category) return
        val updated = trip.copy(category = category)
        store.save(updated)
        syncPipeline(updated)
    }

    private suspend fun deleteTrip(id: Long) {
        store.delete(id)
        forget(id)
    }

    /** 지워진 이동을 기록기·카드에서도 뺀다 */
    private fun forget(id: Long) {
        if (pipeline.open?.id == id) pipeline.restore(null)
        if (pipeline.lastClosed?.id == id) pipeline.restore(pipeline.open, null)
        if (_arrival.value?.trip?.id == id) clearArrival()
        if (settings.deferredArrivalId == id) settings.deferredArrivalId = null
    }

    /** 저장소에서 바뀐 이동이 열린 이동이거나 마지막으로 닫힌 이동이면 기록기에도 반영한다 */
    private fun syncPipeline(trip: Trip) {
        if (pipeline.open?.id == trip.id) pipeline.restore(trip)
        else if (pipeline.lastClosed?.id == trip.id) pipeline.restore(pipeline.open, trip)
    }

    /** 일상 이동 7일, 정차 요약 30일이 지난 것을 지운다 */
    private suspend fun runRetention(now: Long) {
        lastRetentionAt = now
        for (id in store.deleteDailyEndedBefore(now - TripLists.DAILY_KEEP_MS)) forget(id)
        store.deleteStopSummariesBefore(now - StopLog.RETENTION_MS)
        if (!scenarioMode) pipeline.stopLog = StopLog.prune(pipeline.stopLog, now)
    }

    /** 비어 있는 칸을 추정해 저장하고, 고른 위치를 기록기에 넣는다 */
    private fun estimatePlaces() {
        if (!scenarioMode) {
            val estimated = settings.estimatedPlaces.value
            val next = PlaceSetup.estimate(pipeline.stopLog, wallClock(), _naviPlaces.value, settings.userPlaces.value, estimated)
            if (next != estimated) settings.setEstimatedPlaces(next)
        }
        applyPlaces()
    }

    private fun applyPlaces() {
        val setup = if (scenarioMode) PlaceSetup.of(_naviPlaces.value, KnownPlaces(), KnownPlaces())
        else PlaceSetup.of(_naviPlaces.value, settings.userPlaces.value, settings.estimatedPlaces.value)
        _placeSetup.value = setup
        _places.value = setup.known
        pipeline.places = setup.known
    }

    private suspend fun save(trip: Trip): Trip {
        val named = resolver.resolveStops(trip, destinations.toList())
        return named.copy(id = store.save(named))
    }

    private fun publishLive(now: Long) {
        val r = pipeline.recorder
        val open = pipeline.open
        _live.value = LiveStatus(
            moving = if (r.gear == Gear.UNKNOWN) r.isDriving && r.stoppedAt == null else r.gear.isMoving(),
            recording = open != null || r.isDriving,
            tripStartedAt = open?.startedAt ?: r.driveStartedAt,
            visitCount = pipeline.live(now)?.visits?.size ?: 0,
            distanceM = (open?.distanceM ?: 0.0) + r.currentDriveDistanceM,
        )
    }

    private fun Gear.isMoving() = this != Gear.PARK && this != Gear.UNKNOWN

    companion object {
        /** 도착 카드: 끝난 지 이 시간 안의 이동만 바로 띄운다. 묻는 카드는 보여 준 뒤 이 시간이 지나면 일상 이동이 된다 */
        const val ARRIVAL_CARD_WINDOW_MS = 10 * 60_000L
        /** tick 중 보관 기간 정리 간격 */
        const val RETENTION_INTERVAL_MS = 3_600_000L
        private const val MAX_DESTINATIONS = 10
    }
}
