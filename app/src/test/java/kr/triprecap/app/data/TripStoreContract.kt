package kr.triprecap.app.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kr.triprecap.app.domain.Fx
import kr.triprecap.app.domain.Fx.at
import kr.triprecap.app.domain.GeoPoint
import kr.triprecap.app.domain.SeatId
import kr.triprecap.app.domain.Stop
import kr.triprecap.app.domain.StopKind
import kr.triprecap.app.domain.StopSummary
import kr.triprecap.app.domain.TrackPoint
import kr.triprecap.app.domain.Trip
import kr.triprecap.app.domain.TripCategory
import kr.triprecap.app.domain.TripEditor
import kr.triprecap.app.ports.TripStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TripStore 구현이 지켜야 하는 동작. InMemory 와 Room 이 같은 시험을 통과해야 한다 */
abstract class TripStoreContract {
    abstract fun newStore(): TripStore

    private fun trip(day: Int, closed: Boolean = true) = Trip(
        drives = listOf(
            Fx.drive(at(day, 9), at(day, 11), Fx.home, Fx.beach, rainy = true, seats = 3, distanceM = 230_440.5)
                .copy(track = listOf(TrackPoint(at(day, 9), Fx.home), TrackPoint(at(day, 10), GeoPoint(37.6, 128.0)), TrackPoint(at(day, 11), Fx.beach)), arrivalTempC = 12),
            Fx.drive(at(day, 13), at(day, 15), Fx.beach, null, seats = 1),
        ),
        stops = listOf(
            Stop(1, StopKind.VISIT, "경포해변", Fx.beach, at(day, 11), at(day, 12, 20), rainy = true, memo = "파도", region = "강릉시", placeResolved = true, outsideTempC = 12),
            Stop(2, StopKind.BRIEF, null, null, at(day, 12, 30), at(day, 12, 35), doorOpened = false),
            Stop(4, StopKind.LODGING, null, Fx.cafe, at(day, 20), at(day + 1, 9), excludedFromShare = true, outsideTempC = -3),
        ),
        customTitle = "가을 강릉",
        closed = closed,
    )

    @Test
    fun `a saved trip is read back with every field`() = runBlocking {
        val store = newStore()
        val t = trip(7)
        val id = store.save(t)
        assertTrue(id > 0)
        assertEquals(t.copy(id = id), store.get(id))
        assertEquals(setOf(SeatId.DRIVER, SeatId.PASSENGER, SeatId.REAR_LEFT), store.get(id)!!.drives[0].seats)
    }

    @Test
    fun `trips are listed newest first`() = runBlocking {
        val store = newStore()
        val a = store.save(trip(3))
        val b = store.save(trip(7))
        val c = store.save(trip(5))
        assertEquals(listOf(b, c, a), store.trips.first().map { it.id })
    }

    @Test
    fun `saving with an existing id replaces that trip`() = runBlocking {
        val store = newStore()
        val id = store.save(trip(7))
        val edited = TripEditor.deleteStop(TripEditor.renameTrip(store.get(id)!!, "새 제목"), 2)
        assertEquals(id, store.save(edited))
        assertEquals(edited, store.get(id))
        assertEquals(1, store.trips.first().size)
    }

    @Test
    fun `an open trip can grow by a drive and then be closed`() = runBlocking {
        val store = newStore()
        val id = store.save(trip(7, closed = false))
        assertEquals(id, store.openTrip()!!.id)
        val grown = store.get(id)!!.let { it.copy(drives = it.drives + Fx.drive(at(8, 10), at(8, 12), Fx.cafe, Fx.home), closed = true) }
        store.save(grown)
        assertEquals(grown, store.get(id))
        assertNull(store.openTrip())
    }

    @Test
    fun `deleting one trip leaves the others`() = runBlocking {
        val store = newStore()
        val a = store.save(trip(3))
        val b = store.save(trip(7))
        store.delete(a)
        assertNull(store.get(a))
        assertEquals(listOf(b), store.trips.first().map { it.id })
    }

    @Test
    fun `the seen flag is stored and read back`() = runBlocking {
        val store = newStore()
        val id = store.save(trip(7))
        assertFalse(store.get(id)!!.seen)
        store.save(store.get(id)!!.copy(seen = true))
        assertTrue(store.get(id)!!.seen)
        assertTrue(store.trips.first().single().seen)
    }

    @Test
    fun `delete all removes everything`() = runBlocking {
        val store = newStore()
        store.save(trip(3)); store.save(trip(7, closed = false))
        store.deleteAll()
        assertTrue(store.trips.first().isEmpty())
        assertNull(store.openTrip())
    }

    @Test
    fun `category and endedAway are stored and read back`() = runBlocking {
        val store = newStore()
        val id = store.save(trip(7).copy(category = TripCategory.PENDING, endedAway = true))
        assertEquals(TripCategory.PENDING, store.get(id)!!.category)
        assertTrue(store.get(id)!!.endedAway)
        store.save(store.get(id)!!.copy(category = TripCategory.DAILY, endedAway = false))
        assertEquals(TripCategory.DAILY, store.trips.first().single().category)
        assertFalse(store.trips.first().single().endedAway)
    }

    @Test
    fun `a closed trip saved again as open is the open trip`() = runBlocking {
        val store = newStore()
        val id = store.save(trip(7).copy(endedAway = true))
        assertNull(store.openTrip())
        store.save(store.get(id)!!.copy(closed = false, endedAway = false))
        assertEquals(id, store.openTrip()!!.id)
    }

    @Test
    fun `only daily moves that ended before the cutoff are deleted`() = runBlocking {
        val store = newStore()
        val oldDaily = store.save(trip(3).copy(category = TripCategory.DAILY))
        val newDaily = store.save(trip(7).copy(category = TripCategory.DAILY))
        val oldTrip = store.save(trip(1))
        val oldPending = store.save(trip(2).copy(category = TripCategory.PENDING))
        // trip(3) 은 3일 9시 시작, 마지막 숙박이 4일 9시에 끝난다
        val deleted = store.deleteDailyEndedBefore(at(4, 9) + 1)
        assertEquals(listOf(oldDaily), deleted)
        assertEquals(setOf(newDaily, oldTrip, oldPending), store.trips.first().map { it.id }.toSet())
        assertTrue(store.deleteDailyEndedBefore(at(4, 9)).isEmpty())
    }

    private fun summary(day: Int, visit: Boolean = true) = StopSummary(GeoPoint(37.515, 127.106), at(day, 9), at(day, 18), visit)

    @Test
    fun `stop summaries are added, read back in arrival order and pruned by departure`() = runBlocking {
        val store = newStore()
        store.addStopSummaries(listOf(summary(5), summary(3, visit = false)))
        store.addStopSummaries(listOf(summary(7)))
        assertEquals(listOf(summary(3, visit = false), summary(5), summary(7)), store.stopSummaries())
        store.deleteStopSummariesBefore(at(5, 18))
        assertEquals(listOf(summary(5), summary(7)), store.stopSummaries())
        store.deleteAllStopSummaries()
        assertTrue(store.stopSummaries().isEmpty())
    }

    @Test
    fun `deleting trips leaves the stop summaries`() = runBlocking {
        val store = newStore()
        store.save(trip(7))
        store.addStopSummaries(listOf(summary(7)))
        store.deleteAll()
        assertEquals(listOf(summary(7)), store.stopSummaries())
    }
}
