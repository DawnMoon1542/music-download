package com.dawnmoon.music_download.data

import com.dawnmoon.music_download.domain.model.ApiCredentials
import com.dawnmoon.music_download.core.AppClock
import com.dawnmoon.music_download.data.local.DownloadTaskDao
import com.dawnmoon.music_download.data.local.DownloadTaskEntity
import com.dawnmoon.music_download.data.remote.ApiFailure
import com.dawnmoon.music_download.data.remote.ApiResponse
import com.dawnmoon.music_download.data.remote.LinkResolver
import com.dawnmoon.music_download.data.repository.AppSettings
import com.dawnmoon.music_download.data.repository.DefaultTaskRepository
import com.dawnmoon.music_download.data.repository.EnqueueOutcome
import com.dawnmoon.music_download.domain.model.AudioFormat
import com.dawnmoon.music_download.domain.model.BaseFileName
import com.dawnmoon.music_download.domain.model.DownloadTask
import com.dawnmoon.music_download.domain.model.FailureKind
import com.dawnmoon.music_download.domain.model.MusicQuality
import com.dawnmoon.music_download.domain.model.ResolvedTrack
import com.dawnmoon.music_download.domain.model.SongId
import com.dawnmoon.music_download.domain.model.SongLink
import com.dawnmoon.music_download.domain.model.TaskStatus
import com.dawnmoon.music_download.domain.model.ThemeMode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val TEST_API_BASE_URL = "https://api.test"

private const val TEST_API_KEY = "chksz_test_key"
private const val RESOLVED_SONG_ID = 1867150097L
private const val SHARE_TEXT = "分享歌曲：https://163cn.tv/AbCd12 （来自网易云音乐）"
private const val SHORT_LINK_URL = "https://163cn.tv/AbCd12"

/**
 * [DefaultTaskRepository] 的行为测试。
 *
 * 使用内存 Fake DAO 复现 Room 查询的过滤/排序语义，只断言仓库对外可见的结果，
 * 不依赖 DAO 或短链解析器的调用次数等实现细节。
 */
class DefaultTaskRepositoryTest {

    private lateinit var dao: FakeDownloadTaskDao
    private lateinit var settings: FakeAppSettings
    private lateinit var resolver: FakeLinkResolver
    private lateinit var clock: FakeClock

    @Before
    fun setUp() {
        dao = FakeDownloadTaskDao()
        settings = FakeAppSettings(TEST_API_KEY)
        resolver = FakeLinkResolver()
        clock = FakeClock(now = 1_000L)
    }

    // region 入队

    @Test
    fun `enqueue resolves a short link from share text and creates a queued task`() = runTest {
        val outcome = repository().enqueue(SHARE_TEXT, MusicQuality.Exhigh)

        val enqueued = assertEnqueued(outcome)
        assertEquals(1, enqueued.created.size)
        assertEquals(0, enqueued.duplicates)
        assertEquals(0, enqueued.ignoredUrlCount)
        assertTrue(enqueued.failures.isEmpty())

        val task = present(dao.byId(enqueued.created.single()))
        assertEquals(RESOLVED_SONG_ID, task.songId)
        assertEquals(SHORT_LINK_URL, task.sourceUrl)
        assertEquals(TaskStatus.Queued.name, task.status)
        assertEquals(MusicQuality.Exhigh.name, task.requestedQuality)
    }

    @Test
    fun `enqueue reports missing api key without inserting tasks`() = runTest {
        settings.setKey(null)

        val outcome = repository().enqueue(SHARE_TEXT, MusicQuality.Exhigh)

        assertEquals(EnqueueOutcome.MissingApiKey(extractedCount = 1), outcome)
        assertEquals(0, dao.stored().size)
    }

    @Test
    fun `enqueue reports no links when text contains no song links`() = runTest {
        val outcome = repository().enqueue("这首歌没有链接", MusicQuality.Exhigh)

        assertEquals(EnqueueOutcome.NoLinks(ignoredUrlCount = 0), outcome)
    }

    @Test
    fun `enqueue counts an existing active task with the same song as duplicate`() = runTest {
        dao.insert(entity(status = TaskStatus.Queued, songId = RESOLVED_SONG_ID))

        val enqueued = assertEnqueued(repository().enqueue(SHARE_TEXT, MusicQuality.Exhigh))

        assertEquals(0, enqueued.created.size)
        assertEquals(1, enqueued.duplicates)
        assertEquals(1, dao.stored().size)
    }

    @Test
    fun `enqueue creates a new task when the same song only has a completed task`() = runTest {
        dao.insert(
            entity(
                status = TaskStatus.Completed,
                songId = RESOLVED_SONG_ID,
                completedAt = 50,
            ),
        )

        val enqueued = assertEnqueued(repository().enqueue(SHARE_TEXT, MusicQuality.Exhigh))

        assertEquals(1, enqueued.created.size)
        assertEquals(0, enqueued.duplicates)
        assertEquals(2, dao.stored().size)
    }

    @Test
    fun `enqueue reports a short link network failure without inserting a task`() = runTest {
        resolver.handler = {
            ApiResponse.Failure(
                ApiFailure.Network(exceptionType = "IOException", message = "connect timeout"),
                quota = null,
            )
        }

        val enqueued = assertEnqueued(repository().enqueue(SHARE_TEXT, MusicQuality.Exhigh))

        assertTrue(enqueued.created.isEmpty())
        assertEquals(1, enqueued.failures.size)
        val failure = enqueued.failures.single()
        assertEquals(SHORT_LINK_URL, failure.sourceUrl)
        assertTrue(failure.failure is ApiFailure.Network)
        assertEquals(0, dao.stored().size)
    }

    @Test
    fun `enqueueLinks inserts direct links as queued tasks`() = runTest {
        val direct = SongLink.Direct(
            sourceUrl = "https://music.163.com/song?id=42",
            songId = SongId(42),
        )

        val enqueued = assertEnqueued(
            repository().enqueueLinks(listOf(direct), MusicQuality.Lossless),
        )

        assertEquals(1, enqueued.created.size)
        val task = present(dao.byId(enqueued.created.single()))
        assertEquals(42L, task.songId)
        assertEquals(direct.sourceUrl, task.sourceUrl)
        assertEquals(TaskStatus.Queued.name, task.status)
        assertEquals(MusicQuality.Lossless.name, task.requestedQuality)
    }

    @Test
    fun `concurrent enqueue of the same song inserts a single task`() = runTest {
        val repository = repository()

        // FakeLinkResolver.resolve 内含 yield，六次入队会在解析阶段真实挂起并交错，
        // 而不是在单线程调度器上顺序跑完。
        val outcomes = coroutineScope {
            (1..6).map { async { repository.enqueue(SHARE_TEXT, MusicQuality.Exhigh) } }.awaitAll()
        }

        assertEquals(1, dao.stored().size)
        val enqueued = outcomes.filterIsInstance<EnqueueOutcome.Enqueued>()
        assertEquals(1, enqueued.sumOf { it.created.size })
        assertEquals(5, enqueued.sumOf { it.duplicates })
    }

    // endregion

    // region 状态写入

    @Test
    fun `markResolving moves a queued task to resolving`() = runTest {
        val id = dao.insert(entity(status = TaskStatus.Queued, updatedAt = 1))
        clock.now = 500

        repository().markResolving(id)

        val task = present(dao.byId(id))
        assertEquals(TaskStatus.Resolving.name, task.status)
        assertEquals(500L, task.updatedAt)
    }

    @Test
    fun `completeResolve stores resolved track fields in waiting state`() = runTest {
        val id = dao.insert(entity(status = TaskStatus.Resolving, updatedAt = 1))
        clock.now = 600
        // 夹具刻意使用与实体默认值不同的 songId/requestedQuality，否则断言无法证明由 track 写入。
        val track = resolvedTrack(
            songId = SongId(4_242L),
            requestedQuality = MusicQuality.Standard,
            etag = "etag-1",
        )

        repository().completeResolve(id, track)

        val task = present(dao.byId(id))
        assertEquals(TaskStatus.Waiting.name, task.status)
        assertEquals(4_242L, task.songId)
        assertEquals(MusicQuality.Standard.name, task.requestedQuality)
        assertEquals(track.title, task.title)
        assertEquals(track.artist, task.artist)
        assertEquals(track.album, task.album)
        assertEquals(track.artworkUrl, task.artworkUrl)
        assertEquals(track.actualQuality.name, task.actualQuality)
        assertEquals(track.format.name, task.format)
        assertEquals(track.sizeBytes, task.sizeBytes)
        assertEquals(track.md5, task.md5)
        assertEquals(track.streamUrl, task.streamUrl)
        assertEquals("etag-1", task.etag)
        assertEquals(600L, task.updatedAt)
    }

    @Test
    fun `startDownloading moves a waiting task to downloading`() = runTest {
        val id = dao.insert(entity(status = TaskStatus.Waiting, updatedAt = 1))
        clock.now = 700

        repository().startDownloading(id)

        val task = present(dao.byId(id))
        assertEquals(TaskStatus.Downloading.name, task.status)
        assertEquals(700L, task.updatedAt)
    }

    @Test
    fun `updateProgress stores downloaded bytes without changing status`() = runTest {
        val id = dao.insert(
            entity(
                status = TaskStatus.Downloading,
                bytesDownloaded = 10,
                fileName = "keep.mp3",
            ),
        )

        repository().updateProgress(id, bytesDownloaded = 4_096)

        val task = present(dao.byId(id))
        assertEquals(4_096L, task.bytesDownloaded)
        assertEquals(TaskStatus.Downloading.name, task.status)
        assertEquals("keep.mp3", task.fileName)
    }

    @Test
    fun `attachFile stores file name media store id and bytes`() = runTest {
        val id = dao.insert(entity(status = TaskStatus.Downloading))

        repository().attachFile(
            id,
            fileName = "file.mp3",
            mediaStoreId = 77,
            bytesDownloaded = 1_234,
        )

        val task = present(dao.byId(id))
        assertEquals("file.mp3", task.fileName)
        assertEquals(77L, task.mediaStoreId)
        assertEquals(1_234L, task.bytesDownloaded)
        assertEquals(TaskStatus.Downloading.name, task.status)
    }

    @Test
    fun `updateStream stores the new etag and keeps progress when content is unchanged`() = runTest {
        val id = dao.insert(
            entity(
                status = TaskStatus.Downloading,
                streamUrl = "https://cdn/old.flac",
                etag = "etag-old",
                sizeBytes = 12_345,
                md5 = "same-md5",
                bytesDownloaded = 500,
                mediaStoreId = 33,
                fileName = "kept.flac",
            ),
        )

        repository().updateStream(
            id,
            resolvedTrack(
                streamUrl = "https://cdn/new.flac",
                sizeBytes = 12_345,
                md5 = "same-md5",
                etag = "etag-new",
            ),
        )

        val task = present(dao.byId(id))
        assertEquals("https://cdn/new.flac", task.streamUrl)
        assertEquals(12_345L, task.sizeBytes)
        assertEquals("same-md5", task.md5)
        // 换链后必须写入新直链的 ETag，Stage 2 才能用它发 If-Range。
        assertEquals("etag-new", task.etag)
        assertEquals(500L, task.bytesDownloaded)
        assertEquals(33L, task.mediaStoreId)
        assertEquals("kept.flac", task.fileName)
        assertEquals(TaskStatus.Downloading.name, task.status)
    }

    @Test
    fun `updateStream keeps progress when the etag proves content is unchanged`() = runTest {
        val id = dao.insert(
            entity(
                status = TaskStatus.Downloading,
                streamUrl = "https://cdn/old.flac",
                etag = "same-etag",
                md5 = null,
                bytesDownloaded = 40,
            ),
        )

        repository().updateStream(
            id,
            resolvedTrack(
                streamUrl = "https://cdn/new.flac",
                md5 = null,
                etag = "same-etag",
            ),
        )

        val task = present(dao.byId(id))
        assertEquals("same-etag", task.etag)
        assertEquals(40L, task.bytesDownloaded)
    }

    @Test
    fun `updateStream resets progress when content identity cannot be proven`() = runTest {
        val id = dao.insert(
            entity(
                status = TaskStatus.Downloading,
                streamUrl = "https://cdn/old.flac",
                etag = "etag-old",
                sizeBytes = 100,
                md5 = null,
                bytesDownloaded = 40,
                mediaStoreId = 33,
                fileName = "stale.mp3",
            ),
        )

        repository().updateStream(
            id,
            resolvedTrack(
                streamUrl = "https://cdn/new.flac",
                sizeBytes = 100,
                md5 = null,
                etag = null,
            ),
        )

        val task = present(dao.byId(id))
        // 仅 sizeBytes 相同不足以证明内容一致，旧字节必须丢弃。
        assertEquals(0L, task.bytesDownloaded)
        assertNull(task.mediaStoreId)
        assertNull(task.fileName)
        assertNull(task.etag)
    }

    @Test
    fun `updateStream resets progress when the resolved content differs`() = runTest {
        val id = dao.insert(
            entity(
                status = TaskStatus.Downloading,
                streamUrl = "https://cdn/old.flac",
                etag = "etag-old",
                sizeBytes = 100,
                md5 = "old-md5",
                bytesDownloaded = 40,
                mediaStoreId = 33,
                fileName = "old.mp3",
            ),
        )

        repository().updateStream(
            id,
            resolvedTrack(
                streamUrl = "https://cdn/new.flac",
                sizeBytes = 100,
                md5 = "new-md5",
                etag = "etag-new",
            ),
        )

        val task = present(dao.byId(id))
        assertEquals(0L, task.bytesDownloaded)
        assertNull(task.mediaStoreId)
        assertNull(task.fileName)
        assertEquals("new-md5", task.md5)
        assertEquals("etag-new", task.etag)
    }

    @Test
    fun `updateProgress rejects a completed task without changing it`() = runTest {
        val id = dao.insert(
            entity(status = TaskStatus.Completed, bytesDownloaded = 100, completedAt = 5),
        )

        val error = runCatching {
            repository().updateProgress(id, bytesDownloaded = 999)
        }.exceptionOrNull()

        assertTrue("expected IllegalStateException but got $error", error is IllegalStateException)
        val task = present(dao.byId(id))
        assertEquals(100L, task.bytesDownloaded)
        assertEquals(TaskStatus.Completed.name, task.status)
    }

    @Test
    fun `attachFile rejects a cancelled task without changing it`() = runTest {
        val id = dao.insert(entity(status = TaskStatus.Cancelled, fileName = "old.mp3"))

        val error = runCatching {
            repository().attachFile(id, "new.mp3", mediaStoreId = 9, bytesDownloaded = 1)
        }.exceptionOrNull()

        assertTrue("expected IllegalStateException but got $error", error is IllegalStateException)
        val task = present(dao.byId(id))
        assertEquals("old.mp3", task.fileName)
        assertNull(task.mediaStoreId)
        assertEquals(TaskStatus.Cancelled.name, task.status)
    }

    @Test
    fun `updateStream rejects a queued task without changing it`() = runTest {
        val id = dao.insert(entity(status = TaskStatus.Queued, streamUrl = "https://cdn/old.flac"))

        val error = runCatching {
            repository().updateStream(id, resolvedTrack(streamUrl = "https://cdn/new.flac"))
        }.exceptionOrNull()

        assertTrue("expected IllegalStateException but got $error", error is IllegalStateException)
        assertEquals("https://cdn/old.flac", present(dao.byId(id)).streamUrl)
    }

    @Test
    fun `markCompleted stores completion fields`() = runTest {
        val id = dao.insert(entity(status = TaskStatus.Downloading, updatedAt = 1))
        clock.now = 1_000

        repository().markCompleted(
            id,
            fileName = "done.mp3",
            mediaStoreId = 88,
            completedAt = 9_000,
        )

        val task = present(dao.byId(id))
        assertEquals(TaskStatus.Completed.name, task.status)
        assertEquals("done.mp3", task.fileName)
        assertEquals(88L, task.mediaStoreId)
        assertEquals(9_000L, task.completedAt)
        assertEquals(1_000L, task.updatedAt)
    }

    @Test
    fun `markFailed stores failure kind and message`() = runTest {
        val id = dao.insert(entity(status = TaskStatus.Downloading))

        repository().markFailed(id, FailureKind.Network, "connect timeout")

        val task = present(dao.byId(id))
        assertEquals(TaskStatus.Failed.name, task.status)
        assertEquals(FailureKind.Network.name, task.failureKind)
        assertEquals("connect timeout", task.failureMessage)
    }

    @Test
    fun `markPaused moves an active task to paused`() = runTest {
        val id = dao.insert(entity(status = TaskStatus.Downloading))

        repository().markPaused(id)

        assertEquals(TaskStatus.Paused.name, present(dao.byId(id)).status)
    }

    @Test
    fun `markCancelled moves a waiting task to cancelled`() = runTest {
        val id = dao.insert(entity(status = TaskStatus.Waiting))

        repository().markCancelled(id)

        assertEquals(TaskStatus.Cancelled.name, present(dao.byId(id)).status)
    }

    @Test
    fun `an illegal status transition is rejected and leaves the task unchanged`() = runTest {
        val id = dao.insert(entity(status = TaskStatus.Queued))

        val error = runCatching {
            repository().markCompleted(id, "x.mp3", mediaStoreId = 1, completedAt = 2)
        }.exceptionOrNull()

        assertTrue("expected IllegalStateException but got $error", error is IllegalStateException)
        assertEquals(TaskStatus.Queued.name, present(dao.byId(id)).status)
    }

    // endregion

    // region 继续与重试

    @Test
    fun `resume returns an unresolved paused task to the queue`() = runTest {
        val id = dao.insert(entity(status = TaskStatus.Paused))

        repository().resume(id)

        assertEquals(TaskStatus.Queued.name, present(dao.byId(id)).status)
    }

    @Test
    fun `resume returns a resolved paused task to waiting`() = runTest {
        val id = dao.insert(
            entity(
                status = TaskStatus.Paused,
                actualQuality = MusicQuality.Lossless.name,
                format = AudioFormat.Flac.name,
                streamUrl = "https://cdn/stream.flac",
            ),
        )

        repository().resume(id)

        assertEquals(TaskStatus.Waiting.name, present(dao.byId(id)).status)
    }

    @Test
    fun `resetForRetry clears resolution progress file and failure fields`() = runTest {
        val id = dao.insert(
            entity(
                status = TaskStatus.Failed,
                songId = RESOLVED_SONG_ID,
                sourceUrl = "https://music.163.com/song?id=$RESOLVED_SONG_ID",
                requestedQuality = MusicQuality.Exhigh.name,
                actualQuality = MusicQuality.Lossless.name,
                title = "Title",
                artist = "Artist",
                album = "Album",
                artworkUrl = "https://img/cover.jpg",
                format = AudioFormat.Flac.name,
                sizeBytes = 12_345,
                md5 = "md5",
                streamUrl = "https://cdn/stream.flac",
                etag = "etag",
                bytesDownloaded = 10,
                mediaStoreId = 5,
                fileName = "file.flac",
                failureKind = FailureKind.Network.name,
                failureMessage = "timeout",
                createdAt = 100,
                updatedAt = 200,
            ),
        )

        repository().resetForRetry(id)

        val task = present(dao.byId(id))
        assertEquals(TaskStatus.Queued.name, task.status)
        assertEquals(RESOLVED_SONG_ID, task.songId)
        assertEquals("https://music.163.com/song?id=$RESOLVED_SONG_ID", task.sourceUrl)
        assertEquals(MusicQuality.Exhigh.name, task.requestedQuality)
        assertEquals(100L, task.createdAt)
        assertNull(task.actualQuality)
        assertNull(task.title)
        assertNull(task.artist)
        assertNull(task.album)
        assertNull(task.artworkUrl)
        assertNull(task.format)
        assertNull(task.sizeBytes)
        assertNull(task.md5)
        assertNull(task.streamUrl)
        assertNull(task.etag)
        assertEquals(0L, task.bytesDownloaded)
        assertNull(task.mediaStoreId)
        assertNull(task.fileName)
        assertNull(task.failureKind)
        assertNull(task.failureMessage)
    }

    // endregion

    // region 批量操作

    @Test
    fun `pauseAll pauses only active tasks`() = runTest {
        val queued = dao.insert(entity(status = TaskStatus.Queued, createdAt = 1))
        val waiting = dao.insert(entity(status = TaskStatus.Waiting, createdAt = 2))
        val failed = dao.insert(entity(status = TaskStatus.Failed, createdAt = 3))
        val completed = dao.insert(
            entity(status = TaskStatus.Completed, createdAt = 4, completedAt = 4),
        )

        repository().pauseAll()

        assertEquals(TaskStatus.Paused.name, present(dao.byId(queued)).status)
        assertEquals(TaskStatus.Paused.name, present(dao.byId(waiting)).status)
        assertEquals(TaskStatus.Failed.name, present(dao.byId(failed)).status)
        assertEquals(TaskStatus.Completed.name, present(dao.byId(completed)).status)
    }

    @Test
    fun `resumeAll resumes paused tasks according to resolution state`() = runTest {
        val unresolved = dao.insert(entity(status = TaskStatus.Paused, createdAt = 1))
        val resolved = dao.insert(
            entity(
                status = TaskStatus.Paused,
                createdAt = 2,
                actualQuality = MusicQuality.Lossless.name,
                streamUrl = "https://cdn/stream.flac",
            ),
        )
        val queued = dao.insert(entity(status = TaskStatus.Queued, createdAt = 3))
        val failed = dao.insert(entity(status = TaskStatus.Failed, createdAt = 4))

        repository().resumeAll()

        assertEquals(TaskStatus.Queued.name, present(dao.byId(unresolved)).status)
        assertEquals(TaskStatus.Waiting.name, present(dao.byId(resolved)).status)
        assertEquals(TaskStatus.Queued.name, present(dao.byId(queued)).status)
        assertEquals(TaskStatus.Failed.name, present(dao.byId(failed)).status)
    }

    @Test
    fun `cancelAll cancels all non terminal tasks`() = runTest {
        val queued = dao.insert(entity(status = TaskStatus.Queued, createdAt = 1))
        val paused = dao.insert(entity(status = TaskStatus.Paused, createdAt = 2))
        val failed = dao.insert(entity(status = TaskStatus.Failed, createdAt = 3))
        val completed = dao.insert(
            entity(status = TaskStatus.Completed, createdAt = 4, completedAt = 4),
        )

        repository().cancelAll()

        assertEquals(TaskStatus.Cancelled.name, present(dao.byId(queued)).status)
        assertEquals(TaskStatus.Cancelled.name, present(dao.byId(paused)).status)
        assertEquals(TaskStatus.Cancelled.name, present(dao.byId(failed)).status)
        assertEquals(TaskStatus.Completed.name, present(dao.byId(completed)).status)
    }

    @Test
    fun `retryAllFailed moves failed tasks back to the queue`() = runTest {
        val firstFailed = dao.insert(
            entity(
                status = TaskStatus.Failed,
                createdAt = 1,
                failureKind = FailureKind.Network.name,
                failureMessage = "timeout",
            ),
        )
        val secondFailed = dao.insert(entity(status = TaskStatus.Failed, createdAt = 2))
        val queued = dao.insert(entity(status = TaskStatus.Queued, createdAt = 3))

        repository().retryAllFailed()

        val retried = present(dao.byId(firstFailed))
        assertEquals(TaskStatus.Queued.name, retried.status)
        assertNull(retried.failureKind)
        assertNull(retried.failureMessage)
        assertEquals(TaskStatus.Queued.name, present(dao.byId(secondFailed)).status)
        assertEquals(TaskStatus.Queued.name, present(dao.byId(queued)).status)
    }

    @Test
    fun `pendingIds returns ids of tasks that are not completed or cancelled`() = runTest {
        val queued = dao.insert(entity(status = TaskStatus.Queued, createdAt = 1))
        val paused = dao.insert(entity(status = TaskStatus.Paused, createdAt = 2))
        val failed = dao.insert(entity(status = TaskStatus.Failed, createdAt = 3))
        dao.insert(entity(status = TaskStatus.Completed, createdAt = 4, completedAt = 4))
        dao.insert(entity(status = TaskStatus.Cancelled, createdAt = 5))

        val pending = repository().pendingIds()

        assertEquals(setOf(queued, paused, failed), pending.toSet())
    }

    // endregion

    // region 查询映射

    @Test
    fun `byId maps persisted fields to the domain task`() = runTest {
        val id = dao.insert(
            entity(
                songId = 42,
                requestedQuality = MusicQuality.Exhigh.name,
                actualQuality = MusicQuality.Lossless.name,
                title = "Title",
                artist = "Artist",
                album = "Album",
                artworkUrl = "https://img/cover.jpg",
                format = AudioFormat.Flac.name,
                sizeBytes = 1_234,
                md5 = "md5",
                streamUrl = "https://cdn/stream.flac",
                etag = "etag-1",
                status = TaskStatus.Failed,
                bytesDownloaded = 99,
                mediaStoreId = 77,
                fileName = "file.flac",
                failureKind = FailureKind.Network.name,
                failureMessage = "timeout",
                createdAt = 10,
                updatedAt = 20,
            ),
        )

        val task: DownloadTask = present(repository().byId(id))
        assertEquals(SongId(42), task.songId)
        assertEquals(MusicQuality.Exhigh, task.requestedQuality)
        assertEquals(MusicQuality.Lossless, task.actualQuality)
        assertEquals(AudioFormat.Flac, task.format)
        assertEquals(TaskStatus.Failed, task.status)
        assertEquals(FailureKind.Network, task.failureKind)
        assertEquals(77L, task.mediaStoreId)
        assertEquals("https://cdn/stream.flac", task.streamUrl)
        assertEquals("etag-1", task.etag)
        assertEquals(99L, task.bytesDownloaded)
        assertEquals("file.flac", task.fileName)
        assertEquals("timeout", task.failureMessage)
    }

    @Test
    fun `observeInProgress maps persisted stream and file fields`() = runTest {
        dao.insert(
            entity(
                status = TaskStatus.Downloading,
                mediaStoreId = 11,
                streamUrl = "https://cdn/a.mp3",
                etag = "etag-a",
                fileName = "a.mp3",
            ),
        )
        dao.insert(entity(status = TaskStatus.Completed, completedAt = 10))

        val tasks = repository().observeInProgress().first()

        assertEquals(1, tasks.size)
        val task = tasks.single()
        assertEquals(11L, task.mediaStoreId)
        assertEquals("https://cdn/a.mp3", task.streamUrl)
        assertEquals("etag-a", task.etag)
        assertEquals("a.mp3", task.fileName)
    }

    @Test
    fun `observeCompleted maps completed tasks`() = runTest {
        dao.insert(
            entity(
                status = TaskStatus.Completed,
                mediaStoreId = 22,
                streamUrl = "https://cdn/b.mp3",
                etag = "etag-b",
                fileName = "b.mp3",
                completedAt = 5,
            ),
        )
        dao.insert(entity(status = TaskStatus.Downloading))

        val tasks = repository().observeCompleted().first()

        assertEquals(1, tasks.size)
        val task = tasks.single()
        assertEquals(22L, task.mediaStoreId)
        assertEquals("https://cdn/b.mp3", task.streamUrl)
        assertEquals("etag-b", task.etag)
    }

    // endregion

    // region 删除记录

    @Test
    fun `deleteRecord removes only the requested task`() = runTest {
        val first = dao.insert(entity(songId = 1))
        val second = dao.insert(entity(songId = 2))

        repository().deleteRecord(first)

        assertNull(dao.byId(first))
        present(dao.byId(second))
    }

    @Test
    fun `deleteRecords removes all requested tasks`() = runTest {
        val first = dao.insert(entity(songId = 1))
        val second = dao.insert(entity(songId = 2))
        val third = dao.insert(entity(songId = 3))

        repository().deleteRecords(listOf(first, second))

        assertNull(dao.byId(first))
        assertNull(dao.byId(second))
        present(dao.byId(third))
    }

    @Test
    fun `clearCompleted removes only completed tasks`() = runTest {
        val completed = dao.insert(
            entity(status = TaskStatus.Completed, completedAt = 1),
        )
        val failed = dao.insert(entity(status = TaskStatus.Failed))
        val cancelled = dao.insert(entity(status = TaskStatus.Cancelled))

        repository().clearCompleted()

        assertNull(dao.byId(completed))
        present(dao.byId(failed))
        present(dao.byId(cancelled))
    }

    // endregion

    // region 文件名分配

    @Test
    fun `allocateFileName skips names used by existing tasks`() = runTest {
        val id = dao.insert(entity(status = TaskStatus.Downloading, fileName = "a.mp3"))

        val name = repository().allocateFileName(id, BaseFileName("a.mp3"))

        assertEquals("a (1).mp3", name)
        // 分配不得写入占位行或改变已有任务。
        assertEquals(1, dao.stored().size)
    }

    @Test
    fun `allocateFileName ignores names held by cancelled tasks`() = runTest {
        val id = dao.insert(entity(status = TaskStatus.Cancelled, fileName = "a.mp3"))

        val name = repository().allocateFileName(id, BaseFileName("a.mp3"))

        assertEquals("a.mp3", name)
    }

    @Test
    fun `concurrent allocateFileName calls return distinct names`() = runTest {
        val repository = repository()
        val ids = (1..3).map { index ->
            dao.insert(entity(status = TaskStatus.Downloading, createdAt = index.toLong()))
        }

        val names = coroutineScope {
            ids.map { id -> async { repository.allocateFileName(id, BaseFileName("a.mp3")) } }
                .awaitAll()
        }

        assertEquals(3, names.size)
        assertEquals(setOf("a.mp3", "a (1).mp3", "a (2).mp3"), names.toSet())
        // 预留发生在内存中，不写占位行。
        assertEquals(3, dao.stored().size)
    }

    @Test
    fun `allocateFileName releases a reservation once the file is attached`() = runTest {
        val repository = repository()
        val id = dao.insert(entity(status = TaskStatus.Downloading))
        val allocated = repository.allocateFileName(id, BaseFileName("a.mp3"))
        repository.attachFile(id, allocated, mediaStoreId = 1, bytesDownloaded = 0)
        repository.markCancelled(id)

        val reused = repository.allocateFileName(id, BaseFileName("a.mp3"))

        assertEquals("a.mp3", reused)
    }

    @Test
    fun `allocateFileName releases the reservation when a task is cancelled before attach`() = runTest {
        val repository = repository()
        val cancelled = dao.insert(entity(status = TaskStatus.Downloading))
        repository.allocateFileName(cancelled, BaseFileName("a.mp3"))

        repository.markCancelled(cancelled)

        val next = dao.insert(entity(status = TaskStatus.Downloading))
        assertEquals("a.mp3", repository.allocateFileName(next, BaseFileName("a.mp3")))
    }

    @Test
    fun `allocateFileName releases the reservation when a task fails before attach`() = runTest {
        val repository = repository()
        val failed = dao.insert(entity(status = TaskStatus.Downloading))
        repository.allocateFileName(failed, BaseFileName("a.mp3"))

        repository.markFailed(failed, FailureKind.Network, "timeout")

        val next = dao.insert(entity(status = TaskStatus.Downloading))
        assertEquals("a.mp3", repository.allocateFileName(next, BaseFileName("a.mp3")))
    }

    @Test
    fun `allocateFileName releases the reservation when a task record is deleted`() = runTest {
        val repository = repository()
        val deleted = dao.insert(entity(status = TaskStatus.Downloading))
        repository.allocateFileName(deleted, BaseFileName("a.mp3"))

        repository.deleteRecord(deleted)

        val next = dao.insert(entity(status = TaskStatus.Downloading))
        assertEquals("a.mp3", repository.allocateFileName(next, BaseFileName("a.mp3")))
    }

    @Test
    fun `resetForRetry releases the reservation for reallocation`() = runTest {
        val repository = repository()
        val failed = dao.insert(entity(status = TaskStatus.Downloading))
        repository.allocateFileName(failed, BaseFileName("a.mp3"))
        repository.markFailed(failed, FailureKind.Network, "timeout")
        // 失败已释放过一次；再次分配后重试，仍应释放。
        repository.allocateFileName(failed, BaseFileName("b.mp3"))

        repository.resetForRetry(failed)

        val next = dao.insert(entity(status = TaskStatus.Downloading))
        assertEquals("b.mp3", repository.allocateFileName(next, BaseFileName("b.mp3")))
    }

    @Test
    fun `markPaused releases the reservation so another task can take the same name`() = runTest {
        val repository = repository()
        val paused = dao.insert(entity(status = TaskStatus.Downloading))
        assertEquals("a.mp3", repository.allocateFileName(paused, BaseFileName("a.mp3")))

        repository.markPaused(paused)

        val next = dao.insert(entity(status = TaskStatus.Downloading))
        assertEquals("a.mp3", repository.allocateFileName(next, BaseFileName("a.mp3")))
    }

    @Test
    fun `a resumed task can reallocate its released name without an index suffix`() = runTest {
        val repository = repository()
        val id = dao.insert(entity(status = TaskStatus.Downloading))
        assertEquals("a.mp3", repository.allocateFileName(id, BaseFileName("a.mp3")))
        repository.markPaused(id)

        repository.resume(id)
        val reallocated = repository.allocateFileName(id, BaseFileName("a.mp3"))

        assertEquals("a.mp3", reallocated)
    }

    @Test
    fun `releaseFileName frees a reservation after file creation fails`() = runTest {
        val repository = repository()
        val id = dao.insert(entity(status = TaskStatus.Downloading))
        repository.allocateFileName(id, BaseFileName("a.mp3"))

        repository.releaseFileName(id)

        val next = dao.insert(entity(status = TaskStatus.Downloading))
        assertEquals("a.mp3", repository.allocateFileName(next, BaseFileName("a.mp3")))
    }

    @Test
    fun `attachFile failure releases the reservation`() = runTest {
        val repository = repository()
        val waiting = dao.insert(entity(status = TaskStatus.Waiting))
        repository.allocateFileName(waiting, BaseFileName("a.mp3"))

        val error = runCatching {
            repository.attachFile(waiting, "a.mp3", mediaStoreId = 1, bytesDownloaded = 0)
        }.exceptionOrNull()

        assertTrue("expected IllegalStateException but got $error", error is IllegalStateException)
        val next = dao.insert(entity(status = TaskStatus.Downloading))
        assertEquals("a.mp3", repository.allocateFileName(next, BaseFileName("a.mp3")))
    }

    @Test
    fun `deleteRecords releases reservations for every deleted task`() = runTest {
        val repository = repository()
        val first = dao.insert(entity(status = TaskStatus.Downloading))
        val second = dao.insert(entity(status = TaskStatus.Downloading))
        repository.allocateFileName(first, BaseFileName("a.mp3"))
        repository.allocateFileName(second, BaseFileName("b.mp3"))

        repository.deleteRecords(listOf(first, second))

        val next = dao.insert(entity(status = TaskStatus.Downloading))
        assertEquals("a.mp3", repository.allocateFileName(next, BaseFileName("a.mp3")))
        assertEquals("b.mp3", repository.allocateFileName(next, BaseFileName("b.mp3")))
    }

    @Test
    fun `clearCompleted releases reservations held by completed tasks`() = runTest {
        val repository = repository()
        val completed = dao.insert(entity(status = TaskStatus.Completed, completedAt = 1))
        repository.allocateFileName(completed, BaseFileName("a.mp3"))

        repository.clearCompleted()

        val next = dao.insert(entity(status = TaskStatus.Downloading))
        assertEquals("a.mp3", repository.allocateFileName(next, BaseFileName("a.mp3")))
    }

    // endregion

    private fun repository(): DefaultTaskRepository = DefaultTaskRepository(
        dao = dao,
        settings = settings,
        shortLinkResolver = resolver,
        clock = clock,
    )

    /** JUnit 的 assertNotNull 返回 Unit；这里返回非空值，便于在断言后继续读取字段。 */
    private fun <T> present(value: T?): T {
        assertNotNull(value)
        return value!!
    }

    private fun assertEnqueued(outcome: EnqueueOutcome): EnqueueOutcome.Enqueued {
        assertTrue("expected Enqueued but got $outcome", outcome is EnqueueOutcome.Enqueued)
        return outcome as EnqueueOutcome.Enqueued
    }

    private fun resolvedTrack(
        songId: SongId = SongId(RESOLVED_SONG_ID),
        title: String = "Title",
        artist: String = "Artist",
        album: String? = "Album",
        artworkUrl: String? = "https://img/cover.jpg",
        requestedQuality: MusicQuality = MusicQuality.Exhigh,
        actualQuality: MusicQuality = MusicQuality.Lossless,
        format: AudioFormat = AudioFormat.Flac,
        streamUrl: String = "https://cdn/stream.flac",
        sizeBytes: Long? = 12_345,
        md5: String? = "md5",
        etag: String? = null,
    ): ResolvedTrack = ResolvedTrack(
        songId = songId,
        title = title,
        artist = artist,
        album = album,
        artworkUrl = artworkUrl,
        requestedQuality = requestedQuality,
        actualQuality = actualQuality,
        format = format,
        streamUrl = streamUrl,
        sizeBytes = sizeBytes,
        md5 = md5,
        etag = etag,
    )

    private fun entity(
        id: Long = 0,
        songId: Long? = RESOLVED_SONG_ID,
        sourceUrl: String = "https://music.163.com/song?id=${songId ?: 0}",
        requestedQuality: String = MusicQuality.Exhigh.name,
        actualQuality: String? = null,
        title: String? = null,
        artist: String? = null,
        album: String? = null,
        artworkUrl: String? = null,
        format: String? = null,
        sizeBytes: Long? = null,
        md5: String? = null,
        streamUrl: String? = null,
        etag: String? = null,
        status: TaskStatus = TaskStatus.Queued,
        bytesDownloaded: Long = 0,
        mediaStoreId: Long? = null,
        fileName: String? = null,
        failureKind: String? = null,
        failureMessage: String? = null,
        createdAt: Long = 100,
        updatedAt: Long = 100,
        completedAt: Long? = null,
    ): DownloadTaskEntity = DownloadTaskEntity(
        id = id,
        songId = songId,
        sourceUrl = sourceUrl,
        requestedQuality = requestedQuality,
        actualQuality = actualQuality,
        title = title,
        artist = artist,
        album = album,
        artworkUrl = artworkUrl,
        format = format,
        sizeBytes = sizeBytes,
        md5 = md5,
        streamUrl = streamUrl,
        etag = etag,
        status = status.name,
        bytesDownloaded = bytesDownloaded,
        mediaStoreId = mediaStoreId,
        fileName = fileName,
        failureKind = failureKind,
        failureMessage = failureMessage,
        createdAt = createdAt,
        updatedAt = updatedAt,
        completedAt = completedAt,
    )

    /** 用 Mutex 复现 Room 单事务的可见性，避免 Fake 自身的竞态影响仓库断言。 */
    private class FakeDownloadTaskDao : DownloadTaskDao {

        private val mutex = Mutex()
        private val tasks = MutableStateFlow<List<DownloadTaskEntity>>(emptyList())
        private var nextId = 1L

        override fun observeInProgress(): Flow<List<DownloadTaskEntity>> =
            tasks.map { stored ->
                stored
                    .filter { it.status != TaskStatus.Completed.name }
                    .sortedByDescending { it.createdAt }
            }

        override fun observeCompleted(): Flow<List<DownloadTaskEntity>> =
            tasks.map { stored ->
                stored
                    .filter { it.status == TaskStatus.Completed.name }
                    .sortedByDescending { it.completedAt ?: Long.MIN_VALUE }
            }

        override suspend fun byId(id: Long): DownloadTaskEntity? =
            mutex.withLock { tasks.value.firstOrNull { it.id == id } }

        override suspend fun nextQueued(): DownloadTaskEntity? = mutex.withLock {
            tasks.value
                .filter { it.status == TaskStatus.Queued.name }
                .minByOrNull { it.createdAt }
        }

        override suspend fun activeBySongId(songId: Long): DownloadTaskEntity? = mutex.withLock {
            tasks.value
                .filter { it.songId == songId && it.status !in terminalStatuses }
                .maxByOrNull { it.createdAt }
        }

        override suspend fun allInProgress(): List<DownloadTaskEntity> = mutex.withLock {
            tasks.value.filter { it.status != TaskStatus.Completed.name }
        }

        override suspend fun insert(task: DownloadTaskEntity): Long = mutex.withLock {
            insertLocked(task)
        }

        override suspend fun update(task: DownloadTaskEntity) = mutex.withLock {
            tasks.value = tasks.value.map { if (it.id == task.id) task else it }
        }

        override suspend fun delete(id: Long) = mutex.withLock {
            tasks.value = tasks.value.filterNot { it.id == id }
        }

        override suspend fun clearCompleted() = mutex.withLock {
            tasks.value = tasks.value.filterNot { it.status == TaskStatus.Completed.name }
        }

        override suspend fun markUnfinishedAsPaused(now: Long) = mutex.withLock {
            tasks.value = tasks.value.map { task ->
                if (task.status in unfinishedStatuses) {
                    task.copy(status = TaskStatus.Paused.name, updatedAt = now)
                } else {
                    task
                }
            }
        }

        override suspend fun countCompleted(): Int = mutex.withLock {
            tasks.value.count { it.status == TaskStatus.Completed.name }
        }

        override suspend fun isFileNameTaken(name: String): Boolean = mutex.withLock {
            tasks.value.any { it.fileName == name && it.status != TaskStatus.Cancelled.name }
        }

        override suspend fun insertIfNoActive(task: DownloadTaskEntity): Long = mutex.withLock {
            val duplicate = task.songId != null && tasks.value.any {
                it.songId == task.songId && it.status !in terminalStatuses
            }
            if (duplicate) -1L else insertLocked(task)
        }

        /** 测试断言辅助：读取全部行。 */
        suspend fun stored(): List<DownloadTaskEntity> = mutex.withLock { tasks.value }

        private fun insertLocked(task: DownloadTaskEntity): Long {
            val id = if (task.id != 0L) task.id else nextId++
            if (task.id == 0L) {
                nextId = maxOf(nextId, id + 1)
            }
            tasks.value = tasks.value + task.copy(id = id)
            return id
        }

        private companion object {
            val terminalStatuses = setOf(
                TaskStatus.Completed.name,
                TaskStatus.Cancelled.name,
            )
            val unfinishedStatuses = setOf(
                TaskStatus.Queued.name,
                TaskStatus.Resolving.name,
                TaskStatus.Waiting.name,
                TaskStatus.Downloading.name,
            )
        }
    }

    private class FakeAppSettings(initialKey: String?) : AppSettings {

        private val keyFlow = MutableStateFlow(initialKey)
        private val qualityFlow = MutableStateFlow(MusicQuality.Exhigh)
        private val concurrencyFlow = MutableStateFlow(3)
        private val themeFlow = MutableStateFlow(ThemeMode.System)
        private val notificationAskedFlow = MutableStateFlow(false)

        override val apiCredentials: Flow<ApiCredentials?> =
            keyFlow.map { key -> key?.let { ApiCredentials(TEST_API_BASE_URL, it) } }
        override val defaultQuality: Flow<MusicQuality> = qualityFlow
        override val maxConcurrentDownloads: Flow<Int> = concurrencyFlow
        override val themeMode: Flow<ThemeMode> = themeFlow
        override val notificationAsked: Flow<Boolean> = notificationAskedFlow

        fun setKey(value: String?) {
            keyFlow.value = value
        }

        override suspend fun setApiCredentials(value: ApiCredentials) {
            keyFlow.value = value.apiKey
        }

        override suspend fun setDefaultQuality(value: MusicQuality) {
            qualityFlow.value = value
        }

        override suspend fun setMaxConcurrentDownloads(value: Int) {
            concurrencyFlow.value = value
        }

        override suspend fun setThemeMode(value: ThemeMode) {
            themeFlow.value = value
        }

        override suspend fun markNotificationAsked() {
            notificationAskedFlow.value = true
        }
    }

    private class FakeLinkResolver : LinkResolver {

        var handler: (SongLink.ShortLink) -> ApiResponse<SongId> = {
            ApiResponse.Success(SongId(RESOLVED_SONG_ID), quota = null)
        }

        override suspend fun resolve(link: SongLink.ShortLink): ApiResponse<SongId> {
            // 模拟真实网络调用的挂起点，使并发入队在 runTest 的单线程调度器上真实交错。
            yield()
            return handler(link)
        }
    }

    private class FakeClock(
        var now: Long = 0,
        var elapsed: Long = 0,
    ) : AppClock {
        override fun nowMillis(): Long = now
        override fun elapsedRealtime(): Long = elapsed
    }
}
