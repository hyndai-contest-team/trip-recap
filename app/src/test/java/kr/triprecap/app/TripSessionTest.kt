package kr.triprecap.app

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kr.triprecap.app.data.InMemoryTripStore
import kr.triprecap.app.domain.Arrival
import kr.triprecap.app.domain.ArrivalKind
import kr.triprecap.app.domain.FakeLookup
import kr.triprecap.app.domain.Gear
import kr.triprecap.app.domain.GeoPoint
import kr.triprecap.app.domain.KnownPlaces
import kr.triprecap.app.domain.LocationSample
import kr.triprecap.app.domain.PlaceChoice
import kr.triprecap.app.domain.PlaceSlot
import kr.triprecap.app.domain.PlaceSource
import kr.triprecap.app.domain.PlaceNameResolver
import kr.triprecap.app.domain.RegionName
import kr.triprecap.app.domain.StopLog
import kr.triprecap.app.domain.StopSummary
import kr.triprecap.app.domain.Trip
import kr.triprecap.app.domain.TripCategory
import kr.triprecap.app.domain.TripEditor
import kr.triprecap.app.domain.TripRecorder
import kr.triprecap.app.ports.Destination
import kr.triprecap.app.ports.InMemorySettings
import kr.triprecap.app.ports.VoiceIo
import kr.triprecap.app.scenario.GoldenScenarios
import kr.triprecap.app.scenario.Scenario
import kr.triprecap.app.scenario.ScenarioBuilder
import kr.triprecap.app.scenario.ScenarioEvent
import kr.triprecap.app.scenario.onScenarioEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class RecordingVoice : VoiceIo {
    val spoken = mutableListOf<String>()
    override suspend fun speak(text: String) { spoken += text }
    override suspend fun listen(timeoutMs: Long): String? = null
}

class TripSessionTest {
    private val store = InMemoryTripStore()
    private val settings = InMemorySettings()
    private val lookup = FakeLookup()
    private val voice = RecordingVoice()
    /** 실제 시각(도착 카드가 떠 있던 시간, 보관 기간). 재생할 때 이벤트 시각을 따라간다 */
    private var wall = 0L
    private fun newSession() = TripSession(store, settings, PlaceNameResolver(lookup), voice, wallClock = { wall })
    private var session = newSession()

    /**
     * 시나리오를 세션에 넣는다. [tickMs] 가 0 보다 크면 이벤트 사이에 그 간격으로 tick 을 넣는다(앱이 켜져 있는 경우).
     * 실제 시각(wall)은 이벤트·tick 시각을 따라간다
     */
    private suspend fun play(scenario: Scenario, untilMs: Long = Long.MAX_VALUE, fromMs: Long = Long.MIN_VALUE, tickMs: Long = 0) {
        var last: Long? = null
        for (s in scenario.steps) {
            if (s.atMs !in fromMs..untilMs) continue
            if (tickMs > 0 && last != null) {
                var t = last + tickMs
                while (t < s.atMs) { at(scenario.epochAtZero + t); session.tick(wall); t += tickMs }
            }
            last = s.atMs
            at(scenario.epochAtZero + s.atMs)
            session.onScenarioEvent(s.event, wall)
        }
    }

    private fun at(t: Long) { wall = t }

    private suspend fun tickAt(t: Long) { at(t); session.tick(t) }

    private suspend fun restart() {
        session = newSession()
        session.start()
    }

    private suspend fun trips(): List<Trip> = store.trips.first()

    private fun hm(h: Int, m: Int) = (h * 60L + m) * 60_000L

    @Test
    fun `a day trip is stored once, shown as the arrival card and announced`() = runBlocking {
        play(GoldenScenarios.gangneungDayTrip)
        val trip = store.trips.first().single()
        assertTrue(trip.closed)
        assertEquals(TripCategory.TRIP, trip.category)
        assertFalse(trip.endedAway)
        assertEquals("경포해변 → 강릉중앙시장", trip.title)
        assertEquals(Arrival(trip, ArrivalKind.SAVED), session.arrival.value)
        assertEquals(listOf("세 곳 다녀온 여행을 저장했어요."), voice.spoken)
        session.dismissArrival()
        assertNull(session.arrival.value)
    }

    @Test
    fun `arrival is not announced when the voice setting is off`() = runBlocking {
        settings.setArrivalVoiceEnabled(false)
        play(GoldenScenarios.gangneungDayTrip)
        assertTrue(voice.spoken.isEmpty())
        assertEquals(1, store.trips.first().size)
    }

    @Test
    fun `a week of commuting keeps ten daily moves and shows no card`() = runBlocking {
        play(GoldenScenarios.commuteWeek)
        val trips = trips()
        assertEquals(10, trips.size)
        assertTrue(trips.all { it.closed && it.category == TripCategory.DAILY })
        assertNull(session.arrival.value)
        assertTrue(voice.spoken.isEmpty())
    }

    @Test
    fun `nothing is recorded without consent or with recording turned off`() = runBlocking {
        settings.setConsentGiven(false)
        play(GoldenScenarios.gangneungDayTrip)
        assertTrue(store.trips.first().isEmpty())
        settings.setConsentGiven(true); settings.setRecordingEnabled(false)
        play(GoldenScenarios.gangneungDayTrip)
        assertTrue(store.trips.first().isEmpty())
    }

    @Test
    fun `gear is still followed while recording is off so the screen can be restricted`() = runBlocking {
        settings.setRecordingEnabled(false)
        session.onSignal(TripRecorder.Event.GearChanged(Gear.DRIVE), 1_000)
        assertTrue(session.live.value.moving)
        assertFalse(session.live.value.recording)
        session.onSignal(TripRecorder.Event.GearChanged(Gear.PARK), 2_000)
        assertFalse(session.live.value.moving)
    }

    @Test
    fun `live status follows the trip - moving, visits, distance and start time`() = runBlocking {
        val sc = GoldenScenarios.gangneungDayTrip
        play(sc, untilMs = hm(13, 0))      // 경포해변을 떠나 카페로 가는 중
        val live = session.live.value
        assertTrue(live.moving)
        assertTrue(live.recording)
        assertEquals(1, live.visitCount)
        assertEquals(sc.epochAtZero + hm(9, 10), live.tripStartedAt)
        assertTrue("distance ${live.distanceM}", live.distanceM in 150_000.0..260_000.0)
        play(sc, fromMs = hm(13, 0) + 1, untilMs = hm(13, 20))   // 카페 주차 15분째
        assertFalse(session.live.value.moving)
        session.tick(sc.epochAtZero + hm(13, 20))
        assertEquals(2, session.live.value.visitCount)
    }

    @Test
    fun `the trip survives a restart after ignition off at every stop`() = runBlocking {
        val sc = GoldenScenarios.gangneungDayTrip
        // 정차할 때마다 프로세스가 끝났다가 다음 출발 직전에 다시 시작된다
        val cuts = listOf(hm(11, 30), hm(13, 30), hm(15, 30), hm(17, 50), Long.MAX_VALUE)
        var from = Long.MIN_VALUE
        for (cut in cuts) {
            play(sc, untilMs = cut, fromMs = from)
            session.tick(sc.epochAtZero + minOf(cut, hm(19, 50)))
            from = cut + 1
            session = newSession()
            session.setNaviPlaces(KnownPlaces(GoldenScenarios.songpaHome, GoldenScenarios.officeCityHall))
            session.start()
            // 다시 켜지면 P 단이 먼저 들어온다
            session.onSignal(TripRecorder.Event.GearChanged(Gear.PARK), sc.epochAtZero + minOf(cut, hm(19, 50)))
        }
        val trip = store.trips.first().single()
        assertTrue(trip.closed)
        // 재시작으로 정차 기록이 사라져도 저장된 드라이브의 끝 지점과 시각으로 방문지가 만들어진다
        assertEquals(listOf(GoldenScenarios.gyeongpo, GoldenScenarios.anmokCafe, GoldenScenarios.jungangMarket), trip.visits.map { it.point })
        assertEquals(listOf(80L, 65L, 90L), trip.visits.map { it.stayMinutes })
        assertEquals(5, trip.drives.size)
    }

    private fun unnamedTrip(): Scenario = ScenarioBuilder("이름 없는 방문지", ZonedDateTime.of(2026, 11, 7, 0, 0, 0, 0, ZoneId.of("Asia/Seoul"))).run {
        home(GoldenScenarios.songpaHome)
        startParked(t(0, 9, 0), GoldenScenarios.songpaHome)
        drive(t(0, 9, 10), t(0, 11, 20), listOf(GoldenScenarios.songpaHome, GoldenScenarios.gyeongpo))
        door(t(0, 11, 21))
        drive(t(0, 12, 40), t(0, 15, 0), listOf(GoldenScenarios.gyeongpo, GoldenScenarios.songpaHome))
        add(t(0, 15, 2), ScenarioEvent.IgnitionOff)
        build()
    }

    @Test
    fun `a stop reached through the navi destination takes the destination name`() = runBlocking {
        lookup.poi = "경포 공영주차장"; lookup.region = RegionName("강릉시", "강문동")
        session.onScenarioEvent(ScenarioEvent.Navi(Destination("경포해변", GoldenScenarios.gyeongpo, 0)), 0)
        play(unnamedTrip())
        val stop = store.trips.first().single().stops.single()
        assertEquals("경포해변", stop.name)
        assertEquals("강릉시", stop.region)
    }

    @Test
    fun `without a navi destination the nearby poi names the stop`() = runBlocking {
        lookup.poi = "경포 공영주차장"; lookup.region = RegionName("강릉시", "강문동")
        play(unnamedTrip())
        assertEquals("경포 공영주차장", store.trips.first().single().stops.single().name)
    }

    @Test
    fun `offline the stop is saved as a numbered visit and named later`() = runBlocking {
        lookup.offline = true
        play(unnamedTrip())
        val offline = store.trips.first().single()
        assertEquals("방문지 1", offline.title)
        lookup.offline = false; lookup.poi = "경포해변"; lookup.region = RegionName("강릉시", "강문동")
        session.backfillNames()
        val named = store.trips.first().single()
        assertEquals("경포해변", named.title)
        assertEquals(offline.id, named.id)
    }

    @Test
    fun `editing and deleting go through the store`() = runBlocking {
        play(GoldenScenarios.gangneungDayTrip)
        val trip = store.trips.first().single()
        session.onTripEdited(TripEditor.renameTrip(trip, "가을 강릉"))
        assertEquals("가을 강릉", store.get(trip.id)!!.title)
        session.onTripDeleted(trip.id)
        assertTrue(store.trips.first().isEmpty())
        assertNull(session.arrival.value)
    }

    @Test
    fun `delete all also drops the trip in progress`() = runBlocking {
        val sc = GoldenScenarios.gangneungDayTrip
        play(sc, untilMs = hm(13, 30))
        assertEquals(1, store.trips.first().size)     // 열린 여행이 저장돼 있다
        session.onAllDeleted()
        play(sc, fromMs = hm(13, 30) + 1)
        // 지운 뒤의 구간만 새 여행으로 남는다 (카페 이후)
        val trip = store.trips.first().single()
        assertEquals(listOf("강릉중앙시장"), trip.visits.map { it.name })
    }

    @Test
    fun `a newly closed trip is new until the user opens it`() = runBlocking {
        play(GoldenScenarios.gangneungDayTrip)
        val trip = store.trips.first().single()
        assertFalse(trip.seen)
        assertTrue(trip.isNew)
        session.markSeen(trip.id)
        assertTrue(store.get(trip.id)!!.seen)
        assertFalse(store.get(trip.id)!!.isNew)
        assertTrue(session.arrival.value!!.trip.seen)
    }

    @Test
    fun `a trip being recorded is not new and closing it makes it new even if it was opened`() = runBlocking {
        val sc = GoldenScenarios.gangneungDayTrip
        play(sc, untilMs = hm(13, 30))
        val open = store.trips.first().single()
        assertFalse(open.closed)
        assertFalse(open.isNew)
        session.markSeen(open.id)
        assertTrue(store.get(open.id)!!.seen)
        play(sc, fromMs = hm(13, 30) + 1)
        val closed = store.get(open.id)!!
        assertTrue(closed.closed)
        assertTrue(closed.isNew)
    }

    @Test
    fun `editing a copy taken before it was opened keeps the trip seen`() = runBlocking {
        play(GoldenScenarios.gangneungDayTrip)
        val stale = store.trips.first().single()
        session.markSeen(stale.id)
        session.onTripEdited(TripEditor.renameTrip(stale, "가을 강릉"))
        val saved = store.get(stale.id)!!
        assertEquals("가을 강릉", saved.title)
        assertTrue(saved.seen)
    }

    @Test
    fun `marking an unknown trip does nothing`() = runBlocking {
        session.markSeen(42)
        assertTrue(store.trips.first().isEmpty())
    }

    @Test
    fun `a trip with nights is announced with them`() = runBlocking {
        val sc = GoldenScenarios.jejuThreeDays
        play(sc)
        assertEquals(listOf("여섯 곳 다녀온 2박 3일 여행을 저장했어요."), voice.spoken)
    }

    // ── 판정 3단계와 도착 카드 (docs/product-plan.md 2-1, 2-3) ──────────────────────────────

    private val zone = ZoneId.of("Asia/Seoul")
    private val hanamCafe = GeoPoint(37.5393, 127.2148)

    /** 11월 10일(화) 집에서 [dest] 로 가서 10:00 에 서고 10:05 에 시동을 끈다. 집으로 돌아오지 않는다 */
    private fun awayTrip(dest: GeoPoint, name: String): Scenario = ScenarioBuilder("집 밖에서 끝남", ZonedDateTime.of(2026, 11, 10, 0, 0, 0, 0, zone)).run {
        home(GoldenScenarios.songpaHome)
        office(GoldenScenarios.officeCityHall)
        startParked(t(0, 8, 50), GoldenScenarios.songpaHome, "집")
        drive(t(0, 9, 0), t(0, 10, 0), listOf(GoldenScenarios.songpaHome, dest), name)
        door(t(0, 10, 1))
        add(t(0, 10, 5), ScenarioEvent.IgnitionOff)
        build()
    }

    @Test
    fun `an unsure move is kept for an answer and asked about without voice`() = runBlocking {
        play(GoldenScenarios.coastalDrive)
        val trip = trips().single()
        assertEquals(TripCategory.PENDING, trip.category)
        assertEquals(Arrival(trip, ArrivalKind.ASK), session.arrival.value)
        assertTrue(voice.spoken.isEmpty())
    }

    @Test
    fun `keeping an unsure move makes it a new trip and closes the card`() = runBlocking {
        play(GoldenScenarios.coastalDrive)
        val id = trips().single().id
        session.keep(id)
        val trip = store.get(id)!!
        assertEquals(TripCategory.TRIP, trip.category)
        assertTrue(trip.isNew)
        assertNull(session.arrival.value)
    }

    @Test
    fun `discarding an unsure move deletes it for good`() = runBlocking {
        play(GoldenScenarios.coastalDrive)
        session.discard(trips().single().id)
        assertTrue(trips().isEmpty())
        assertNull(session.arrival.value)
    }

    @Test
    fun `closing the question without an answer keeps the move as a daily move`() = runBlocking {
        play(GoldenScenarios.coastalDrive)
        session.dismissArrival()
        assertEquals(TripCategory.DAILY, trips().single().category)
        assertNull(session.arrival.value)
    }

    @Test
    fun `the question goes away as a daily move after ten minutes`() = runBlocking {
        play(GoldenScenarios.coastalDrive)
        val shown = wall
        tickAt(shown + 9 * 60_000L)
        assertNotNull(session.arrival.value)
        assertEquals(TripCategory.PENDING, trips().single().category)
        tickAt(shown + 10 * 60_000L)
        assertNull(session.arrival.value)
        assertEquals(TripCategory.DAILY, trips().single().category)
    }

    @Test
    fun `driving off without an answer keeps the move as a daily move`() = runBlocking {
        play(GoldenScenarios.coastalDrive)
        at(wall + 2 * 60_000L)
        session.onSignal(TripRecorder.Event.GearChanged(Gear.DRIVE), wall)
        assertNull(session.arrival.value)
        assertEquals(TripCategory.DAILY, trips().single { it.closed }.category)
    }

    @Test
    fun `a commute past a regular shop is a daily move with no card`() = runBlocking {
        val sc = GoldenScenarios.regularMart
        // 이전 방문 기록은 저장소에 있고, 앱을 시작할 때 읽는다
        store.addStopSummaries(sc.priorLog)
        at(sc.epochAtZero)
        restart()
        play(sc)
        assertEquals(listOf(TripCategory.DAILY), trips().map { it.category })
        assertNull(session.arrival.value)
    }

    @Test
    fun `stop summaries are stored as they come`() = runBlocking {
        play(GoldenScenarios.gangneungDayTrip)
        val saved = store.stopSummaries()
        assertTrue(saved.size >= 3)
        assertTrue(saved.any { it.visit && it.point == StopLog.round(GoldenScenarios.gyeongpo) })
    }

    @Test
    fun `a trip that ends away from home closes after six hours without a card and shows at the next ignition`() = runBlocking {
        val sc = awayTrip(GoldenScenarios.yangpyeongCafe, "양평 카페")
        play(sc)
        tickAt(sc.epochAtZero + hm(15, 59))
        assertFalse(trips().single().closed)
        tickAt(sc.epochAtZero + hm(16, 1))
        val trip = trips().single()
        assertTrue(trip.closed)
        assertTrue(trip.endedAway)
        assertEquals(TripCategory.TRIP, trip.category)
        assertNull(session.arrival.value)
        assertTrue(voice.spoken.isEmpty())

        session.onIgnitionOn()
        assertEquals(Arrival(trip, ArrivalKind.SAVED_EARLIER), session.arrival.value)
        // 한 번 보여 준 카드는 다시 띄우지 않는다
        session.dismissArrival()
        session.onIgnitionOn()
        assertNull(session.arrival.value)
    }

    @Test
    fun `after a restart the first park signal is the ignition and shows the question`() = runBlocking {
        val sc = awayTrip(hanamCafe, "하남 카페")
        play(sc)
        // 시동을 끄면 프로세스가 끝난다. 7시간 뒤 다시 켜지면 P 단이 먼저 들어온다
        at(sc.epochAtZero + hm(17, 0))
        restart()
        session.setNaviPlaces(KnownPlaces(GoldenScenarios.songpaHome, GoldenScenarios.officeCityHall))
        session.onSignal(TripRecorder.Event.GearChanged(Gear.PARK), wall)
        val trip = trips().single()
        assertTrue(trip.endedAway)
        assertEquals(TripCategory.PENDING, trip.category)
        assertEquals(Arrival(trip, ArrivalKind.ASK), session.arrival.value)
    }

    @Test
    fun `a question waiting for the next ignition becomes a daily move when the car drives off`() = runBlocking {
        val sc = awayTrip(hanamCafe, "하남 카페")
        play(sc)
        tickAt(sc.epochAtZero + hm(16, 1))
        assertEquals(TripCategory.PENDING, trips().single().category)
        at(sc.epochAtZero + hm(17, 0))
        session.onSignal(TripRecorder.Event.GearChanged(Gear.DRIVE), wall)
        assertNull(session.arrival.value)
        assertEquals(TripCategory.DAILY, trips().single { it.closed }.category)
        session.onIgnitionOn()
        assertNull(session.arrival.value)
    }

    @Test
    fun `a trip reopened by a night away shows no card, then closes at home as one saved trip`() = runBlocking {
        val sc = GoldenScenarios.parentsOvernight
        play(sc, untilMs = hm(12, 15))
        tickAt(sc.epochAtZero + hm(18, 11))
        val closed = trips().single()
        assertTrue(closed.closed && closed.endedAway)
        assertNull(session.arrival.value)
        // 오전 3시가 지나면 숙박이 되어 다시 열린다
        tickAt(sc.epochAtZero + hm(27, 1))
        val reopened = trips().single()
        assertEquals(closed.id, reopened.id)
        assertFalse(reopened.closed)
        assertFalse(reopened.endedAway)
        session.onIgnitionOn()
        assertNull(session.arrival.value)

        play(sc, fromMs = hm(12, 15) + 1)
        val trip = trips().single()
        assertEquals(closed.id, trip.id)
        assertTrue(trip.closed)
        assertFalse(trip.endedAway)
        assertEquals(TripCategory.TRIP, trip.category)
        assertEquals(Arrival(trip, ArrivalKind.SAVED), session.arrival.value)
    }

    @Test
    fun `a trip closed away from home reopens after a restart and keeps its id and names`() = runBlocking {
        val sc = GoldenScenarios.parentsOvernight
        play(sc, untilMs = hm(12, 15))
        tickAt(sc.epochAtZero + hm(18, 11))
        val closed = trips().single()
        session.onTripEdited(TripEditor.renameTrip(closed, "세종 부모님 댁"))
        // 밤에 프로세스가 끝났다가 다음 날 아침 다시 켜진다
        at(sc.epochAtZero + hm(33, 50))
        restart()
        session.setNaviPlaces(KnownPlaces(GoldenScenarios.songpaHome, GoldenScenarios.officeCityHall))
        session.onSignal(TripRecorder.Event.GearChanged(Gear.PARK), wall)
        assertNull(session.arrival.value)
        play(sc, fromMs = hm(33, 50))
        val trip = trips().single()
        assertEquals(closed.id, trip.id)
        assertEquals("세종 부모님 댁", trip.title)
        assertTrue(trip.closed)
        assertEquals(TripCategory.TRIP, trip.category)
    }

    private fun closedTrip(endedAt: Long, category: TripCategory, endedAway: Boolean = false) = Trip(
        drives = listOf(kr.triprecap.app.domain.Fx.drive(endedAt - 3_600_000L, endedAt, GoldenScenarios.songpaHome, GoldenScenarios.officeCityHall)),
        closed = true, category = category, endedAway = endedAway,
    )

    @Test
    fun `at start an old unanswered question becomes a daily move, a recent one is asked again`() = runBlocking {
        val now = kr.triprecap.app.domain.Fx.at(13, 12)
        val old = store.save(closedTrip(now - 11 * 60_000L, TripCategory.PENDING))
        val waiting = store.save(closedTrip(now - 5 * 3_600_000L, TripCategory.PENDING, endedAway = true))
        val recent = store.save(closedTrip(now - 3 * 60_000L, TripCategory.PENDING))
        settings.deferredArrivalId = waiting
        at(now)
        restart()
        assertEquals(TripCategory.DAILY, store.get(old)!!.category)
        assertEquals(TripCategory.PENDING, store.get(waiting)!!.category)
        assertEquals(Arrival(store.get(recent)!!, ArrivalKind.ASK), session.arrival.value)
        // 다시 켜진 뒤 첫 P 단이 시동이다: 기다리던 질문이 뜨고, 앞의 질문은 답이 없었던 것으로 본다
        session.onSignal(TripRecorder.Event.GearChanged(Gear.PARK), now)
        assertEquals(TripCategory.DAILY, store.get(recent)!!.category)
        assertEquals(Arrival(store.get(waiting)!!, ArrivalKind.ASK), session.arrival.value)
    }

    @Test
    fun `a daily move can be kept as a trip`() = runBlocking {
        play(GoldenScenarios.commuteWeek)
        val id = trips().first().id
        session.keepAsTrip(id)
        assertEquals(TripCategory.TRIP, store.get(id)!!.category)
        assertEquals(9, trips().count { it.category == TripCategory.DAILY })
    }

    // ── 보관 기간 (docs/product-plan.md 2-1 일상 이동, 정차 요약, 2-9) ──────────────────────

    private val day = 86_400_000L

    @Test
    fun `at start daily moves older than seven days and stop summaries older than thirty days are deleted`() = runBlocking {
        val now = kr.triprecap.app.domain.Fx.at(13, 12)
        val oldDaily = store.save(closedTrip(now - 7 * day - 1, TripCategory.DAILY))
        val newDaily = store.save(closedTrip(now - 6 * day, TripCategory.DAILY))
        val oldTrip = store.save(closedTrip(now - 40 * day, TripCategory.TRIP))
        val p = StopLog.round(GoldenScenarios.jamsilMart)
        store.addStopSummaries(listOf(StopSummary(p, now - 31 * day, now - 31 * day + 1, true), StopSummary(p, now - 2 * day, now - 2 * day + 1, true)))
        at(now)
        restart()
        assertEquals(setOf(newDaily, oldTrip), trips().map { it.id }.toSet())
        assertNull(store.get(oldDaily))
        assertEquals(listOf(now - 2 * day), store.stopSummaries().map { it.arrivedAt })
    }

    @Test
    fun `retention also runs about once an hour while ticking`() = runBlocking {
        val now = kr.triprecap.app.domain.Fx.at(13, 12)
        at(now)
        restart()
        val daily = store.save(closedTrip(now - 7 * day + 30 * 60_000L, TripCategory.DAILY))
        tickAt(now + 40 * 60_000L)
        assertNotNull(store.get(daily))
        tickAt(now + 61 * 60_000L)
        assertNull(store.get(daily))
    }

    @Test
    fun `delete all also deletes the stop summaries and the estimated places`() = runBlocking {
        play(GoldenScenarios.gangneungDayTrip)
        settings.setEstimatedPlaces(KnownPlaces(home = GoldenScenarios.songpaHome))
        session.onAllDeleted()
        assertTrue(trips().isEmpty())
        assertTrue(store.stopSummaries().isEmpty())
        assertEquals(KnownPlaces(), settings.estimatedPlaces.value)
    }

    // ── 집·회사 위치 (docs/product-plan.md 2-1 집·회사 위치) ─────────────────────────────────

    private val home = GoldenScenarios.songpaHome
    private val office = GoldenScenarios.officeCityHall
    private val elsewhere = GeoPoint(37.60, 127.30)

    /** 11월 5~10일 밤마다 집에 서 있고, 11월 2~6일·9일(평일 6일) 9~18시 회사에 서 있던 기록 */
    private fun habitLog(): List<StopSummary> {
        val fx = kr.triprecap.app.domain.Fx
        return (5..10).map { StopSummary(StopLog.round(home), fx.at(it, 20), fx.at(it + 1, 8), false) } +
            listOf(2, 3, 4, 5, 6, 9).map { StopSummary(StopLog.round(office), fx.at(it, 8, 50), fx.at(it, 18, 10), false) }
    }

    @Test
    fun `without navi favourites home and office are estimated at start and stick`() = runBlocking {
        store.addStopSummaries(habitLog())
        at(kr.triprecap.app.domain.Fx.at(12, 12))
        restart()
        val setup = session.placeSetup.value
        assertEquals(PlaceChoice(StopLog.round(home), PlaceSource.ESTIMATE), setup.home)
        assertEquals(PlaceChoice(StopLog.round(office), PlaceSource.ESTIMATE), setup.office)
        assertEquals(KnownPlaces(StopLog.round(home), StopLog.round(office)), session.places.value)
        // 기록이 지워져도 한 번 정한 추정은 그대로 쓴다
        store.deleteAllStopSummaries()
        restart()
        assertEquals(KnownPlaces(StopLog.round(home), StopLog.round(office)), session.places.value)
    }

    @Test
    fun `navi favourites come before the user's choice, which comes before the estimate`() = runBlocking {
        settings.setEstimatedPlaces(KnownPlaces(home = home, office = office))
        at(kr.triprecap.app.domain.Fx.at(12, 12))
        restart()
        session.onSignal(TripRecorder.Event.LocationChanged(LocationSample(elsewhere)), wall)
        assertTrue(session.setPlaceToCurrent(PlaceSlot.OFFICE))
        assertEquals(PlaceChoice(elsewhere, PlaceSource.USER), session.placeSetup.value.office)
        assertEquals(KnownPlaces(home, elsewhere), session.places.value)
        session.setNaviPlaces(KnownPlaces(office = GoldenScenarios.jamsilMart))
        assertEquals(PlaceChoice(GoldenScenarios.jamsilMart, PlaceSource.NAVI), session.placeSetup.value.office)
        assertEquals(PlaceChoice(home, PlaceSource.ESTIMATE), session.placeSetup.value.home)
    }

    @Test
    fun `resetting to the estimate drops the user's choice and estimates again`() = runBlocking {
        store.addStopSummaries(habitLog())
        settings.setEstimatedPlaces(KnownPlaces(office = elsewhere))
        settings.setUserPlaces(KnownPlaces(office = GoldenScenarios.jamsilMart))
        at(kr.triprecap.app.domain.Fx.at(12, 12))
        restart()
        assertEquals(PlaceSource.USER, session.placeSetup.value.office.source)
        session.resetPlaceToEstimate(PlaceSlot.OFFICE)
        assertEquals(PlaceChoice(StopLog.round(office), PlaceSource.ESTIMATE), session.placeSetup.value.office)
        assertEquals(KnownPlaces(), settings.userPlaces.value)
    }

    @Test
    fun `the current location is needed to set a place`() = runBlocking {
        restart()
        assertFalse(session.setPlaceToCurrent(PlaceSlot.HOME))
        assertEquals(PlaceSource.NONE, session.placeSetup.value.home.source)
    }

    @Test
    fun `a demo scenario uses only its own places and does not store stop summaries`() = runBlocking {
        settings.setUserPlaces(KnownPlaces(home = elsewhere))
        restart()
        assertEquals(elsewhere, session.places.value.home)
        session.useScenario(emptyList())
        assertEquals(KnownPlaces(), session.places.value)
        play(GoldenScenarios.gangneungDayTrip)
        assertEquals(KnownPlaces(home, office), session.places.value)
        assertTrue(store.stopSummaries().isEmpty())
        assertEquals(1, trips().size)
    }
}
