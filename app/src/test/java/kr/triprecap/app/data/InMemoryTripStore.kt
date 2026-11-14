package kr.triprecap.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kr.triprecap.app.domain.StopSummary
import kr.triprecap.app.domain.Trip
import kr.triprecap.app.domain.TripCategory
import kr.triprecap.app.ports.TripStore

/** 메모리 저장소. 테스트에서 쓴다 */
class InMemoryTripStore : TripStore {
    private val state = MutableStateFlow<Map<Long, Trip>>(emptyMap())
    private var nextId = 1L
    private var summaries = emptyList<StopSummary>()

    override val trips: Flow<List<Trip>> = state.map { m -> m.values.sortedByDescending { it.startedAt } }

    override suspend fun save(trip: Trip): Long {
        val id = if (trip.id == 0L) nextId++ else trip.id
        state.value = state.value + (id to trip.copy(id = id))
        return id
    }

    override suspend fun get(id: Long): Trip? = state.value[id]
    override suspend fun openTrip(): Trip? = state.value.values.filter { !it.closed }.maxByOrNull { it.startedAt }
    override suspend fun delete(id: Long) { state.value = state.value - id }
    override suspend fun deleteAll() { state.value = emptyMap() }

    override suspend fun deleteDailyEndedBefore(before: Long): List<Long> {
        val ids = state.value.values.filter { it.category == TripCategory.DAILY && it.endedAt < before }.map { it.id }.sorted()
        state.value = state.value - ids.toSet()
        return ids
    }

    override suspend fun addStopSummaries(summaries: List<StopSummary>) { this.summaries = this.summaries + summaries }
    override suspend fun stopSummaries(): List<StopSummary> = summaries.sortedWith(compareBy({ it.arrivedAt }, { it.departedAt }))
    override suspend fun deleteStopSummariesBefore(before: Long) { summaries = summaries.filter { it.departedAt >= before } }
    override suspend fun deleteAllStopSummaries() { summaries = emptyList() }
}
