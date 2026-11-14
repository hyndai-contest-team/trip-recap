package kr.triprecap.app.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "trip")
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val customTitle: String?,
    val closed: Boolean,
    /** 정렬용. 첫 드라이브의 시작 시각 */
    val startedAt: Long,
    /** version 2 에서 추가. 기존 여행은 본 것으로 둔다 (MIGRATION_1_2) */
    @ColumnInfo(defaultValue = "1") val seen: Boolean,
    /** version 3 에서 추가. TripCategory 이름(TRIP, DAILY, PENDING). 기존 여행은 TRIP (MIGRATION_2_3) */
    @ColumnInfo(defaultValue = "TRIP") val category: String,
    /** version 3 에서 추가. 집이 아닌 곳에 오래 서 있어서 닫혔는지. 기존 여행은 false */
    @ColumnInfo(defaultValue = "0") val endedAway: Boolean,
    /** version 3 에서 추가. 일상 이동 7일 삭제용. Trip.endedAt 과 같다 */
    @ColumnInfo(defaultValue = "0") val endedAt: Long,
)

@Entity(
    tableName = "drive",
    foreignKeys = [ForeignKey(entity = TripEntity::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tripId")],
)
data class DriveEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val position: Int,
    val startedAt: Long,
    val endedAt: Long,
    val distanceM: Double,
    val rainy: Boolean,
    /** SeatId.ordinal 비트 묶음 */
    val seatBits: Int,
    /** version 4 에서 추가. 도착 때 외기온(섭씨). 기존 드라이브와 지원하지 않는 차는 null */
    val arrivalTempC: Int? = null,
)

@Entity(
    tableName = "stop",
    primaryKeys = ["tripId", "stopId"],
    foreignKeys = [ForeignKey(entity = TripEntity::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE)],
)
data class StopEntity(
    val tripId: Long,
    val stopId: Int,
    val kind: String,
    val name: String?,
    val lat: Double?,
    val lon: Double?,
    val arrivedAt: Long,
    val departedAt: Long,
    val rainy: Boolean,
    val doorOpened: Boolean,
    val memo: String?,
    val region: String?,
    val placeResolved: Boolean,
    val excludedFromShare: Boolean,
    /** version 4 에서 추가. 외기온(섭씨). 기존 정차와 지원하지 않는 차는 null */
    val outsideTempC: Int? = null,
)

@Entity(
    tableName = "track_point",
    primaryKeys = ["driveId", "position"],
    foreignKeys = [ForeignKey(entity = DriveEntity::class, parentColumns = ["id"], childColumns = ["driveId"], onDelete = ForeignKey.CASCADE)],
)
data class TrackPointEntity(
    val driveId: Long,
    val position: Int,
    val t: Long,
    val lat: Double,
    val lon: Double,
)

/** 정차 요약 1건 (domain StopSummary). 여행과 관계없이 30일 보관한다. version 3 에서 추가 */
@Entity(tableName = "stop_summary")
data class StopSummaryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lat: Double,
    val lon: Double,
    val arrivedAt: Long,
    val departedAt: Long,
    val visit: Boolean,
)

@Dao
abstract class TripDao {
    @Query("SELECT * FROM trip ORDER BY startedAt DESC, id DESC") abstract fun observeTrips(): Flow<List<TripEntity>>
    @Query("SELECT * FROM trip ORDER BY startedAt DESC, id DESC") abstract suspend fun trips(): List<TripEntity>
    @Query("SELECT * FROM trip WHERE id = :id") abstract suspend fun trip(id: Long): TripEntity?
    @Query("SELECT * FROM trip WHERE closed = 0 ORDER BY startedAt DESC LIMIT 1") abstract suspend fun openTrip(): TripEntity?
    @Query("SELECT * FROM drive WHERE tripId = :tripId ORDER BY position") abstract suspend fun drives(tripId: Long): List<DriveEntity>
    @Query("SELECT * FROM stop WHERE tripId = :tripId ORDER BY arrivedAt, stopId") abstract suspend fun stops(tripId: Long): List<StopEntity>
    @Query("SELECT * FROM track_point WHERE driveId = :driveId ORDER BY position") abstract suspend fun points(driveId: Long): List<TrackPointEntity>

    @Insert abstract suspend fun insertTrip(e: TripEntity): Long
    @Update abstract suspend fun updateTrip(e: TripEntity)
    @Insert abstract suspend fun insertDrive(e: DriveEntity): Long
    @Insert abstract suspend fun insertStops(e: List<StopEntity>)
    @Insert abstract suspend fun insertPoints(e: List<TrackPointEntity>)
    @Query("DELETE FROM drive WHERE tripId = :tripId") abstract suspend fun deleteDrives(tripId: Long)
    @Query("DELETE FROM stop WHERE tripId = :tripId") abstract suspend fun deleteStops(tripId: Long)
    @Query("DELETE FROM trip WHERE id = :id") abstract suspend fun deleteTrip(id: Long)
    @Query("DELETE FROM trip") abstract suspend fun deleteAllTrips()
    @Query("SELECT id FROM trip WHERE category = 'DAILY' AND endedAt < :before ORDER BY id") abstract suspend fun dailyEndedBefore(before: Long): List<Long>
    @Query("DELETE FROM trip WHERE id IN (:ids)") abstract suspend fun deleteTrips(ids: List<Long>)

    @Insert abstract suspend fun insertSummaries(e: List<StopSummaryEntity>)
    @Query("SELECT * FROM stop_summary ORDER BY arrivedAt, departedAt, id") abstract suspend fun summaries(): List<StopSummaryEntity>
    @Query("DELETE FROM stop_summary WHERE departedAt < :before") abstract suspend fun deleteSummariesBefore(before: Long)
    @Query("DELETE FROM stop_summary") abstract suspend fun deleteAllSummaries()

    /** 끝난 시각이 [before] 보다 이른 일상 이동을 지우고 그 id 를 돌려준다 */
    @Transaction
    open suspend fun deleteDailyEndedBefore(before: Long): List<Long> {
        val ids = dailyEndedBefore(before)
        if (ids.isNotEmpty()) deleteTrips(ids)
        return ids
    }

    /** 여행 1건을 통째로 저장한다. 드라이브가 그대로면 경로 점은 다시 쓰지 않는다 */
    @Transaction
    open suspend fun replace(trip: TripEntity, drives: List<Pair<DriveEntity, List<TrackPointEntity>>>, stops: List<StopEntity>): Long {
        val id = if (trip.id == 0L || trip(trip.id) == null) insertTrip(trip) else trip.id.also { updateTrip(trip) }
        val existing = drives(id)
        val sameDrives = existing.size == drives.size && existing.zip(drives).all { (a, b) ->
            a.startedAt == b.first.startedAt && a.endedAt == b.first.endedAt && a.arrivalTempC == b.first.arrivalTempC
        }
        if (!sameDrives) {
            deleteDrives(id)
            for ((d, pts) in drives) {
                val driveId = insertDrive(d.copy(id = 0, tripId = id))
                insertPoints(pts.map { it.copy(driveId = driveId) })
            }
        }
        deleteStops(id)
        insertStops(stops.map { it.copy(tripId = id) })
        return id
    }
}

@Database(
    entities = [TripEntity::class, DriveEntity::class, StopEntity::class, TrackPointEntity::class, StopSummaryEntity::class],
    version = 4,
    exportSchema = false,
)
abstract class TripDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao

    companion object {
        /** 1 → 2: trip.seen 추가. 이미 저장된 여행은 사용자가 본 것으로 둔다(새 여행 표시를 띄우지 않는다) */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trip ADD COLUMN seen INTEGER NOT NULL DEFAULT 1")
            }
        }

        /**
         * 2 → 3: trip.category·endedAway·endedAt, stop_summary 표 추가.
         * 기존 여행은 여행(TRIP), endedAway = false 다. endedAt 은 드라이브 종료·정차 출발 중 가장 늦은 시각으로 채운다(Trip.endedAt 과 같은 값)
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trip ADD COLUMN category TEXT NOT NULL DEFAULT 'TRIP'")
                db.execSQL("ALTER TABLE trip ADD COLUMN endedAway INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE trip ADD COLUMN endedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "UPDATE trip SET endedAt = MAX(" +
                        "COALESCE((SELECT MAX(endedAt) FROM drive WHERE drive.tripId = trip.id), startedAt), " +
                        "COALESCE((SELECT MAX(departedAt) FROM stop WHERE stop.tripId = trip.id), 0))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `stop_summary` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `lat` REAL NOT NULL, `lon` REAL NOT NULL, " +
                        "`arrivedAt` INTEGER NOT NULL, `departedAt` INTEGER NOT NULL, `visit` INTEGER NOT NULL)",
                )
            }
        }

        /** 3 → 4: stop.outsideTempC, drive.arrivalTempC 추가(외기온). 기존 기록은 값이 없으므로 null 로 둔다 */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE stop ADD COLUMN outsideTempC INTEGER")
                db.execSQL("ALTER TABLE drive ADD COLUMN arrivalTempC INTEGER")
            }
        }

        fun create(context: Context, name: String = "trips.db"): TripDatabase =
            Room.databaseBuilder(context.applicationContext, TripDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
    }
}
