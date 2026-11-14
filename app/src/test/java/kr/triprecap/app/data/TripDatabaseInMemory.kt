package kr.triprecap.app.data

import android.content.Context
import androidx.room.Room

/** 메모리 DB. 테스트에서 쓴다 */
fun TripDatabase.Companion.inMemory(context: Context): TripDatabase =
    Room.inMemoryDatabaseBuilder(context.applicationContext, TripDatabase::class.java).allowMainThreadQueries().build()
