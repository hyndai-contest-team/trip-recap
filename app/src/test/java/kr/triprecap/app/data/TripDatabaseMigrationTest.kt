package kr.triprecap.app.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kr.triprecap.app.domain.GeoPoint
import kr.triprecap.app.domain.StopSummary
import kr.triprecap.app.domain.TripCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 예전 version DB 파일을 열면 여행이 남아 있다.
 * - 1 → 2: 기존 여행은 본 것으로 표시된다
 * - 2 → 3: 기존 여행은 분류 여행(TRIP), endedAway = false 이고, 정차 요약 표를 쓸 수 있다
 * - 3 → 4: 기존 정차·드라이브의 외기온은 없음(null)이고, 새로 저장한 외기온은 그대로 읽힌다
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TripDatabaseMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "migration-test.db"

    /** version 1 스키마. Room 이 version 1 에서 만든 CREATE 문 그대로다 */
    private fun createVersion1() = create(
        version = 1,
        tripTable = "CREATE TABLE IF NOT EXISTS `trip` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `customTitle` TEXT, `closed` INTEGER NOT NULL, `startedAt` INTEGER NOT NULL)",
        hash = "83993c41bb4bd0288bd38da3a7647395",
        rows = listOf(
            "INSERT INTO trip (id, customTitle, closed, startedAt) VALUES (1, '가을 강릉', 1, 1000)",
            "INSERT INTO drive (id, tripId, position, startedAt, endedAt, distanceM, rainy, seatBits) VALUES (1, 1, 0, 1000, 2000, 1234.5, 0, 1)",
        ),
    )

    /** version 2 스키마: version 1 + trip.seen. 숙박 정차가 마지막 드라이브보다 늦게 끝나는 여행 1건 */
    private fun createVersion2() = create(
        version = 2,
        tripTable = "CREATE TABLE IF NOT EXISTS `trip` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `customTitle` TEXT, `closed` INTEGER NOT NULL, `startedAt` INTEGER NOT NULL, `seen` INTEGER NOT NULL DEFAULT 1)",
        hash = "version-2",
        rows = listOf(
            "INSERT INTO trip (id, customTitle, closed, startedAt, seen) VALUES (1, '가을 강릉', 1, 1000, 0)",
            "INSERT INTO drive (id, tripId, position, startedAt, endedAt, distanceM, rainy, seatBits) VALUES (1, 1, 0, 1000, 2000, 1234.5, 0, 1)",
            "INSERT INTO stop (tripId, stopId, kind, name, lat, lon, arrivedAt, departedAt, rainy, doorOpened, memo, region, placeResolved, excludedFromShare) " +
                "VALUES (1, 1, 'LODGING', '숙소', 37.5, 127.0, 2000, 9000, 0, 1, NULL, NULL, 1, 0)",
        ),
    )

    /** version 3 스키마: version 2 + trip.category·endedAway·endedAt + stop_summary 표 */
    private fun createVersion3() = create(
        version = 3,
        tripTable = "CREATE TABLE IF NOT EXISTS `trip` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `customTitle` TEXT, `closed` INTEGER NOT NULL, `startedAt` INTEGER NOT NULL, " +
            "`seen` INTEGER NOT NULL DEFAULT 1, `category` TEXT NOT NULL DEFAULT 'TRIP', `endedAway` INTEGER NOT NULL DEFAULT 0, `endedAt` INTEGER NOT NULL DEFAULT 0)",
        hash = "version-3",
        rows = listOf(
            "CREATE TABLE IF NOT EXISTS `stop_summary` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `lat` REAL NOT NULL, `lon` REAL NOT NULL, " +
                "`arrivedAt` INTEGER NOT NULL, `departedAt` INTEGER NOT NULL, `visit` INTEGER NOT NULL)",
            "INSERT INTO trip (id, customTitle, closed, startedAt, seen, category, endedAway, endedAt) VALUES (1, '가을 강릉', 1, 1000, 1, 'TRIP', 0, 9000)",
            "INSERT INTO drive (id, tripId, position, startedAt, endedAt, distanceM, rainy, seatBits) VALUES (1, 1, 0, 1000, 2000, 1234.5, 0, 1)",
            "INSERT INTO stop (tripId, stopId, kind, name, lat, lon, arrivedAt, departedAt, rainy, doorOpened, memo, region, placeResolved, excludedFromShare) " +
                "VALUES (1, 1, 'VISIT', '경포해변', 37.5, 127.0, 2000, 9000, 0, 1, NULL, '강릉시', 1, 0)",
        ),
    )

    private fun create(version: Int, tripTable: String, hash: String, rows: List<String>) {
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        listOf(
            tripTable,
            "CREATE TABLE IF NOT EXISTS `drive` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `tripId` INTEGER NOT NULL, `position` INTEGER NOT NULL, `startedAt` INTEGER NOT NULL, `endedAt` INTEGER NOT NULL, `distanceM` REAL NOT NULL, `rainy` INTEGER NOT NULL, `seatBits` INTEGER NOT NULL, FOREIGN KEY(`tripId`) REFERENCES `trip`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_drive_tripId` ON `drive` (`tripId`)",
            "CREATE TABLE IF NOT EXISTS `stop` (`tripId` INTEGER NOT NULL, `stopId` INTEGER NOT NULL, `kind` TEXT NOT NULL, `name` TEXT, `lat` REAL, `lon` REAL, `arrivedAt` INTEGER NOT NULL, `departedAt` INTEGER NOT NULL, `rainy` INTEGER NOT NULL, `doorOpened` INTEGER NOT NULL, `memo` TEXT, `region` TEXT, `placeResolved` INTEGER NOT NULL, `excludedFromShare` INTEGER NOT NULL, PRIMARY KEY(`tripId`, `stopId`), FOREIGN KEY(`tripId`) REFERENCES `trip`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE TABLE IF NOT EXISTS `track_point` (`driveId` INTEGER NOT NULL, `position` INTEGER NOT NULL, `t` INTEGER NOT NULL, `lat` REAL NOT NULL, `lon` REAL NOT NULL, PRIMARY KEY(`driveId`, `position`), FOREIGN KEY(`driveId`) REFERENCES `drive`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
            "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '$hash')",
        ).plus(rows).forEach(db::execSQL)
        db.version = version
        db.close()
    }

    @Test
    fun `version 1 trips survive the upgrade and count as seen`() = runBlocking {
        createVersion1()
        val db = TripDatabase.create(context, name)
        try {
            val store = RoomTripStore(db)
            val trip = store.get(1)!!
            assertEquals("가을 강릉", trip.title)
            assertEquals(1234.5, trip.distanceM, 0.0)
            assertTrue(trip.seen)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun `version 2 trips become trips that did not end away, and stop summaries can be stored`() = runBlocking {
        createVersion2()
        val db = TripDatabase.create(context, name)
        try {
            val store = RoomTripStore(db)
            val trip = store.get(1)!!
            assertEquals("가을 강릉", trip.title)
            assertFalse(trip.seen)
            assertEquals(TripCategory.TRIP, trip.category)
            assertFalse(trip.endedAway)
            // 끝난 시각은 드라이브·정차 중 가장 늦은 시각으로 채운다
            db.openHelper.readableDatabase.query("SELECT endedAt FROM trip WHERE id = 1").use { c ->
                c.moveToFirst()
                assertEquals(9000L, c.getLong(0))
            }
            val s = StopSummary(GeoPoint(37.515, 127.106), 1000, 2000, visit = true)
            store.addStopSummaries(listOf(s))
            assertEquals(listOf(s), store.stopSummaries())
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun `version 3 stops and drives have no outside temperature and new ones are stored`() = runBlocking {
        createVersion3()
        val db = TripDatabase.create(context, name)
        try {
            val store = RoomTripStore(db)
            val trip = store.get(1)!!
            assertEquals("경포해변", trip.stops.single().name)
            assertNull(trip.stops.single().outsideTempC)
            assertNull(trip.drives.single().arrivalTempC)
            val warmer = trip.copy(
                drives = trip.drives.map { it.copy(arrivalTempC = 12) },
                stops = trip.stops.map { it.copy(outsideTempC = -3) },
            )
            store.save(warmer)
            assertEquals(warmer, store.get(1))
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
