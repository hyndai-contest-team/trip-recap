package kr.triprecap.app.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 실제 SQLite(Robolectric) 위에서 Room 저장소가 같은 계약을 지키는지 본다 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RoomTripStoreTest : TripStoreContract() {
    private val dbs = mutableListOf<TripDatabase>()

    override fun newStore() = RoomTripStore(TripDatabase.inMemory(ApplicationProvider.getApplicationContext()).also { dbs += it })

    @After
    fun close() = dbs.forEach { it.close() }
}
