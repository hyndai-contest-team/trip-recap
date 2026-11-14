package kr.triprecap.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kr.triprecap.app.domain.Drive
import kr.triprecap.app.domain.GeoPoint
import kr.triprecap.app.domain.SeatId
import kr.triprecap.app.domain.Stop
import kr.triprecap.app.domain.StopKind
import kr.triprecap.app.domain.StopSummary
import kr.triprecap.app.domain.TrackPoint
import kr.triprecap.app.domain.Trip
import kr.triprecap.app.domain.TripCategory
import kr.triprecap.app.ports.TripStore

/** Room DB 저장소 (Trip, Drive, Stop, TrackPoint, 정차 요약). 기기 안에만 저장한다 */
class RoomTripStore(private val db: TripDatabase) : TripStore {
    private val dao = db.tripDao()

    override val trips: Flow<List<Trip>> = dao.observeTrips().map { list -> list.map { load(it) } }

    override suspend fun save(trip: Trip): Long = dao.replace(
        trip = TripEntity(
            id = trip.id, customTitle = trip.customTitle, closed = trip.closed, startedAt = trip.startedAt, seen = trip.seen,
            category = trip.category.name, endedAway = trip.endedAway, endedAt = trip.endedAt,
        ),
        drives = trip.drives.mapIndexed { i, d ->
            DriveEntity(
                tripId = trip.id, position = i, startedAt = d.startedAt, endedAt = d.endedAt, distanceM = d.distanceM, rainy = d.rainy,
                seatBits = d.seats.fold(0) { acc, s -> acc or (1 shl s.ordinal) }, arrivalTempC = d.arrivalTempC,
            ) to d.track.mapIndexed { j, p -> TrackPointEntity(driveId = 0, position = j, t = p.t, lat = p.point.lat, lon = p.point.lon) }
        },
        stops = trip.stops.map { s ->
            StopEntity(
                tripId = trip.id, stopId = s.id, kind = s.kind.name, name = s.name, lat = s.point?.lat, lon = s.point?.lon,
                arrivedAt = s.arrivedAt, departedAt = s.departedAt, rainy = s.rainy, doorOpened = s.doorOpened, memo = s.memo,
                region = s.region, placeResolved = s.placeResolved, excludedFromShare = s.excludedFromShare, outsideTempC = s.outsideTempC,
            )
        },
    )

    override suspend fun get(id: Long): Trip? = dao.trip(id)?.let { load(it) }
    override suspend fun openTrip(): Trip? = dao.openTrip()?.let { load(it) }
    override suspend fun delete(id: Long) = dao.deleteTrip(id)
    override suspend fun deleteAll() = dao.deleteAllTrips()
    override suspend fun deleteDailyEndedBefore(before: Long): List<Long> = dao.deleteDailyEndedBefore(before)

    override suspend fun addStopSummaries(summaries: List<StopSummary>) {
        if (summaries.isEmpty()) return
        dao.insertSummaries(summaries.map { StopSummaryEntity(lat = it.point.lat, lon = it.point.lon, arrivedAt = it.arrivedAt, departedAt = it.departedAt, visit = it.visit) })
    }

    override suspend fun stopSummaries(): List<StopSummary> =
        dao.summaries().map { StopSummary(GeoPoint(it.lat, it.lon), it.arrivedAt, it.departedAt, it.visit) }

    override suspend fun deleteStopSummariesBefore(before: Long) = dao.deleteSummariesBefore(before)
    override suspend fun deleteAllStopSummaries() = dao.deleteAllSummaries()

    private suspend fun load(e: TripEntity): Trip = Trip(
        id = e.id,
        customTitle = e.customTitle,
        closed = e.closed,
        seen = e.seen,
        category = runCatching { TripCategory.valueOf(e.category) }.getOrDefault(TripCategory.TRIP),
        endedAway = e.endedAway,
        drives = dao.drives(e.id).map { d ->
            Drive(
                startedAt = d.startedAt, endedAt = d.endedAt, distanceM = d.distanceM, rainy = d.rainy,
                seats = SeatId.entries.filter { d.seatBits and (1 shl it.ordinal) != 0 }.toSet(),
                track = dao.points(d.id).map { TrackPoint(it.t, GeoPoint(it.lat, it.lon)) },
                arrivalTempC = d.arrivalTempC,
            )
        },
        stops = dao.stops(e.id).map { s ->
            Stop(
                id = s.stopId, kind = StopKind.valueOf(s.kind), name = s.name,
                point = if (s.lat != null && s.lon != null) GeoPoint(s.lat, s.lon) else null,
                arrivedAt = s.arrivedAt, departedAt = s.departedAt, rainy = s.rainy, doorOpened = s.doorOpened, memo = s.memo,
                region = s.region, placeResolved = s.placeResolved, excludedFromShare = s.excludedFromShare, outsideTempC = s.outsideTempC,
            )
        },
    )
}
