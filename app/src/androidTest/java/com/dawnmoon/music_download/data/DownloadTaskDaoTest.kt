package com.dawnmoon.music_download.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dawnmoon.music_download.data.local.DownloadDatabase
import com.dawnmoon.music_download.data.local.DownloadTaskDao
import com.dawnmoon.music_download.data.local.DownloadTaskEntity
import com.dawnmoon.music_download.domain.model.MusicQuality
import com.dawnmoon.music_download.domain.model.TaskStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadTaskDaoTest {

    private lateinit var database: DownloadDatabase
    private lateinit var dao: DownloadTaskDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, DownloadDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.downloadTaskDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun observeInProgressExcludesCompletedAndOrdersNewestFirst() = runBlocking {
        val olderCancelled = dao.insert(task(status = TaskStatus.Cancelled, createdAt = 100))
        val newestQueued = dao.insert(task(status = TaskStatus.Queued, createdAt = 300))
        val completed = dao.insert(
            task(status = TaskStatus.Completed, createdAt = 200, completedAt = 250),
        )

        val inProgress = dao.observeInProgress().first()

        assertEquals(listOf(newestQueued, olderCancelled), inProgress.map { it.id })
        assertFalse(inProgress.any { it.id == completed })
    }

    @Test
    fun observeCompletedOrdersByCompletionTimeDescending() = runBlocking {
        // createdAt order is deliberately the reverse of completedAt order so that
        // sorting by createdAt or updatedAt cannot produce the expected result.
        val completedEarlier = dao.insert(
            task(status = TaskStatus.Completed, createdAt = 300, completedAt = 200),
        )
        val completedLater = dao.insert(
            task(status = TaskStatus.Completed, createdAt = 100, completedAt = 400),
        )
        dao.insert(task(status = TaskStatus.Downloading, createdAt = 500))

        val completed = dao.observeCompleted().first()

        assertEquals(listOf(completedLater, completedEarlier), completed.map { it.id })
    }

    @Test
    fun nextQueuedReturnsEarliestCreatedQueuedTask() = runBlocking {
        dao.insert(task(status = TaskStatus.Queued, createdAt = 200))
        val earliestQueued = dao.insert(task(status = TaskStatus.Queued, createdAt = 100))
        dao.insert(task(status = TaskStatus.Downloading, createdAt = 50))

        val next = dao.nextQueued()

        assertEquals(earliestQueued, next?.id)
    }

    @Test
    fun activeBySongIdReturnsDownloadingTaskWhenCompletedTaskSharesSong() = runBlocking {
        val downloading = dao.insert(
            task(songId = 42, status = TaskStatus.Downloading, createdAt = 100),
        )
        // The completed row is the newest one, so an unfiltered "latest by songId"
        // lookup would return it instead of the active task.
        dao.insert(
            task(songId = 42, status = TaskStatus.Completed, createdAt = 200, completedAt = 250),
        )

        val active = dao.activeBySongId(42)

        assertEquals(downloading, active?.id)
    }

    @Test
    fun activeBySongIdReturnsNullWhenOnlyCompletedTaskSharesSong() = runBlocking {
        dao.insert(
            task(songId = 43, status = TaskStatus.Completed, createdAt = 100, completedAt = 150),
        )

        assertNull(dao.activeBySongId(43))
    }

    @Test
    fun isFileNameTakenIgnoresCancelledTasks() = runBlocking {
        dao.insert(
            task(status = TaskStatus.Downloading, createdAt = 100, fileName = "held.mp3"),
        )
        dao.insert(task(status = TaskStatus.Cancelled, createdAt = 200, fileName = "free.mp3"))

        assertTrue(dao.isFileNameTaken("held.mp3"))
        assertFalse(dao.isFileNameTaken("free.mp3"))
    }

    @Test
    fun markUnfinishedAsPausedTransitionsResumableStatuses() = runBlocking {
        val now = 5_000L
        val resumableStatuses = listOf(
            TaskStatus.Queued,
            TaskStatus.Resolving,
            TaskStatus.Waiting,
            TaskStatus.Downloading,
        )
        val untouchedStatuses = listOf(
            TaskStatus.Failed,
            TaskStatus.Cancelled,
            TaskStatus.Completed,
        )
        val resumableIds = resumableStatuses.associateWith { status ->
            dao.insert(task(status = status, createdAt = 100, updatedAt = 1))
        }
        val untouchedIds = untouchedStatuses.associateWith { status ->
            dao.insert(task(status = status, createdAt = 100, updatedAt = 1))
        }

        dao.markUnfinishedAsPaused(now)

        resumableIds.forEach { (status, id) ->
            val stored = dao.byId(id)
            assertEquals("$status should be paused", TaskStatus.Paused.name, stored?.status)
            assertEquals("$status should record the pause time", now, stored?.updatedAt)
        }
        untouchedIds.forEach { (status, id) ->
            val stored = dao.byId(id)
            assertEquals("$status should keep its status", status.name, stored?.status)
            assertEquals("$status should keep its update time", 1L, stored?.updatedAt)
        }
    }

    @Test
    fun countCompletedCountsOnlyCompletedTasks() = runBlocking {
        dao.insert(task(status = TaskStatus.Completed, createdAt = 100, completedAt = 110))
        dao.insert(task(status = TaskStatus.Completed, createdAt = 200, completedAt = 210))
        dao.insert(task(status = TaskStatus.Downloading, createdAt = 300))
        dao.insert(task(status = TaskStatus.Cancelled, createdAt = 400))

        assertEquals(2, dao.countCompleted())
    }

    @Test
    fun allInProgressIncludesEveryStatusExceptCompleted() = runBlocking {
        val queued = dao.insert(task(status = TaskStatus.Queued, createdAt = 100))
        val paused = dao.insert(task(status = TaskStatus.Paused, createdAt = 200))
        val failed = dao.insert(task(status = TaskStatus.Failed, createdAt = 300))
        val cancelled = dao.insert(task(status = TaskStatus.Cancelled, createdAt = 400))
        val completed = dao.insert(
            task(status = TaskStatus.Completed, createdAt = 500, completedAt = 550),
        )

        val inProgressIds = dao.allInProgress().map { it.id }.toSet()

        assertEquals(setOf(queued, paused, failed, cancelled), inProgressIds)
        assertFalse(inProgressIds.contains(completed))
    }

    @Test
    fun clearCompletedRemovesOnlyCompletedTasks() = runBlocking {
        val completed = dao.insert(
            task(status = TaskStatus.Completed, createdAt = 100, completedAt = 150),
        )
        val downloading = dao.insert(task(status = TaskStatus.Downloading, createdAt = 200))

        dao.clearCompleted()

        assertNull(dao.byId(completed))
        assertNotNull(dao.byId(downloading))
    }

    @Test
    fun insertIfNoActiveRejectsDuplicateActiveSong() = runBlocking {
        val firstId = dao.insertIfNoActive(
            task(songId = 7, status = TaskStatus.Downloading, createdAt = 100),
        )
        val duplicateId = dao.insertIfNoActive(
            task(songId = 7, status = TaskStatus.Queued, createdAt = 200),
        )

        assertTrue(firstId > 0)
        assertEquals(-1L, duplicateId)
        assertEquals(1, dao.allInProgress().count { it.songId == 7L })
    }

    @Test
    fun insertIfNoActiveAllowsRequeueingSongWithFinishedTask() = runBlocking {
        val completed = dao.insert(
            task(songId = 8, status = TaskStatus.Completed, createdAt = 100, completedAt = 150),
        )
        val afterCompleted = dao.insertIfNoActive(
            task(songId = 8, status = TaskStatus.Queued, createdAt = 200),
        )

        val cancelled = dao.insert(
            task(songId = 9, status = TaskStatus.Cancelled, createdAt = 100),
        )
        val afterCancelled = dao.insertIfNoActive(
            task(songId = 9, status = TaskStatus.Queued, createdAt = 200),
        )

        assertTrue("completed task should not block requeueing the same song", afterCompleted > 0)
        assertTrue("cancelled task should not block requeueing the same song", afterCancelled > 0)
        assertNotNull(dao.byId(completed))
        assertNotNull(dao.byId(cancelled))
    }

    private fun task(
        songId: Long? = 1L,
        status: TaskStatus = TaskStatus.Queued,
        createdAt: Long = 0L,
        updatedAt: Long = createdAt,
        completedAt: Long? = null,
        fileName: String? = null,
    ): DownloadTaskEntity = DownloadTaskEntity(
        songId = songId,
        sourceUrl = "https://music.163.com/song?id=${songId ?: 0}",
        requestedQuality = MusicQuality.Exhigh.apiValue,
        actualQuality = null,
        title = null,
        artist = null,
        album = null,
        artworkUrl = null,
        format = null,
        sizeBytes = null,
        md5 = null,
        streamUrl = null,
        etag = null,
        status = status.name,
        bytesDownloaded = 0L,
        mediaStoreId = null,
        fileName = fileName,
        failureKind = null,
        failureMessage = null,
        createdAt = createdAt,
        updatedAt = updatedAt,
        completedAt = completedAt,
    )
}
