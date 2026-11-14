package kr.triprecap.app.ports

import kotlinx.coroutines.flow.Flow
import kr.triprecap.app.domain.StopSummary
import kr.triprecap.app.domain.Trip

/** 여행 저장소. 기기 안에만 저장한다 (docs/product-plan.md 2-9) */
interface TripStore {
    /** 저장된 모든 이동(닫힌 것과 열려 있는 것, 분류 무관). 시작 시각 최신순 */
    val trips: Flow<List<Trip>>

    /** id 가 0 이면 새로 넣고, 아니면 그 여행을 통째로 바꾼다. 저장된 id 를 돌려준다 */
    suspend fun save(trip: Trip): Long
    suspend fun get(id: Long): Trip?
    /** 아직 닫히지 않은 여행. 없으면 null */
    suspend fun openTrip(): Trip?
    suspend fun delete(id: Long)
    /** 모든 이동을 지운다. 정차 요약은 [deleteAllStopSummaries] 로 따로 지운다 */
    suspend fun deleteAll()

    /** 분류가 일상 이동(DAILY)이고 끝난 시각(Trip.endedAt)이 [before] 보다 이른 이동을 지운다. 지운 id 를 돌려준다 */
    suspend fun deleteDailyEndedBefore(before: Long): List<Long>

    /** 정차 요약 (docs/product-plan.md 2-1 정차 요약). 여행과 따로 보관한다 */
    suspend fun addStopSummaries(summaries: List<StopSummary>)
    /** 저장된 정차 요약 전체. 도착 시각 순 */
    suspend fun stopSummaries(): List<StopSummary>
    /** 출발 시각이 [before] 보다 이른 정차 요약을 지운다 (StopLog.prune 과 같은 기준) */
    suspend fun deleteStopSummariesBefore(before: Long)
    suspend fun deleteAllStopSummaries()
}
