package com.dawnmoon.music_download.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dawnmoon.music_download.core.AppClock
import com.dawnmoon.music_download.data.local.DownloadDatabase
import com.dawnmoon.music_download.data.local.QuotaDao
import com.dawnmoon.music_download.data.repository.RoomQuotaStore
import com.dawnmoon.music_download.domain.model.Quota
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomQuotaStoreTest {

    private lateinit var database: DownloadDatabase
    private lateinit var dao: QuotaDao
    private lateinit var store: RoomQuotaStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, DownloadDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.quotaDao()
        store = RoomQuotaStore(dao, FixedClock(1_000L))
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun quotaIsNullBeforeAnySnapshotIsRecorded() = runBlocking {
        assertNull(store.quota.first())
    }

    @Test
    fun recordPersistsEveryFieldOfTheFirstSnapshot() = runBlocking {
        store.record(Quota(freeRemaining = 3, paidRemaining = 0, limitPerMinute = 20))

        assertEquals(
            Quota(freeRemaining = 3, paidRemaining = 0, limitPerMinute = 20),
            store.quota.first(),
        )
    }

    @Test
    fun recordKeepsPreviousValuesForMissingFields() = runBlocking {
        store.record(Quota(freeRemaining = 3, paidRemaining = 0, limitPerMinute = 20))

        store.record(Quota(freeRemaining = null, paidRemaining = 5, limitPerMinute = null))

        assertEquals(
            Quota(freeRemaining = 3, paidRemaining = 5, limitPerMinute = 20),
            store.quota.first(),
        )
    }

    private class FixedClock(private val now: Long) : AppClock {
        override fun nowMillis(): Long = now

        override fun elapsedRealtime(): Long = now
    }
}
