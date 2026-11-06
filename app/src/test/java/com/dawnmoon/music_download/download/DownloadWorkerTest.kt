package com.dawnmoon.music_download.download

import com.dawnmoon.music_download.core.AppClock
import com.dawnmoon.music_download.data.remote.ApiResponse
import com.dawnmoon.music_download.data.repository.AppSettings
import com.dawnmoon.music_download.data.repository.EnqueueOutcome
import com.dawnmoon.music_download.data.repository.TaskRepository
import com.dawnmoon.music_download.domain.TrackResolver
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
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val TASK_ID = 42L
private const val SONG_ID = 1_867_150_097L
private const val CONTENT_SIZE = 100 * 1024
private const val CHUNK_COUNT = 2
private const val EXPECTED_BASE_FILE_NAME = "测试歌曲_测试歌手.mp3"
private const val ALLOCATED_FILE_NAME = "测试歌曲_测试歌手 (1).mp3"

/**
 * [DefaultDownloadWorker] 首次下载行为的契约测试。
 *
 * 使用 MockWebServer 提供真实音频流、临时文件实现 [FakeTaskFileStore]，断言下载结果、
 * 落盘内容与完整性校验行为，而不是替身的调用细节。
 */
class DownloadWorkerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val client = OkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `completes a new download and exposes the verified file`() = runTest {
        val content = sampleBytes(CONTENT_SIZE)
        enqueueAudio(content)
        val repo = FakeTaskRepository(
            task(sizeBytes = content.size.toLong(), md5 = md5Hex(content), streamUrl = streamUrl()),
        )
        val fileStore = fileStore()

        val result = worker(repo, fileStore).execute(TASK_ID)

        assertEquals(WorkerResult.Completed, result)
        val mediaStoreId = requireNotNull(repo.task.mediaStoreId) {
            "worker must persist the created media id"
        }
        assertEquals(
            "the completed row must reference the persisted media entry",
            mediaStoreId,
            repo.completedMediaStoreId,
        )
        assertEquals(TaskStatus.Completed, repo.task.status)
        assertEquals(ALLOCATED_FILE_NAME, repo.task.fileName)
        assertEquals(
            "the created entry must use the allocated name, not the raw base name",
            ALLOCATED_FILE_NAME,
            fileStore.displayNameOf(mediaStoreId),
        )
        assertEquals(
            "the allocated name must be derived from the task metadata",
            listOf(BaseFileName(EXPECTED_BASE_FILE_NAME)),
            repo.allocatedBases,
        )
        assertArrayEquals(content, fileStore.contentOf(mediaStoreId))
        assertTrue("completed download must clear the pending flag", fileStore.isCompleted(mediaStoreId))
    }

    @Test
    fun `throttles progress writes and finishes with the total byte count`() = runTest {
        val content = sampleBytes(CONTENT_SIZE)
        enqueueAudio(content)
        val repo = FakeTaskRepository(
            task(sizeBytes = content.size.toLong(), md5 = md5Hex(content), streamUrl = streamUrl()),
        )

        val result = worker(repo, fileStore()).execute(TASK_ID)

        assertEquals(WorkerResult.Completed, result)
        assertTrue(
            "progress must be throttled below one write per chunk, got ${repo.progressUpdates}",
            repo.progressUpdates.size < CHUNK_COUNT,
        )
        assertEquals(
            "the last progress value must equal the downloaded byte count",
            content.size.toLong(),
            repo.progressUpdates.lastOrNull(),
        )
    }

    @Test
    fun `checks only size when the task has no md5`() = runTest {
        val content = sampleBytes(CONTENT_SIZE)
        enqueueAudio(content)
        val repo = FakeTaskRepository(
            task(sizeBytes = content.size.toLong(), md5 = null, streamUrl = streamUrl()),
        )
        val fileStore = fileStore()

        val result = worker(repo, fileStore).execute(TASK_ID)

        assertEquals(WorkerResult.Completed, result)
        val mediaStoreId = requireNotNull(repo.task.mediaStoreId)
        assertArrayEquals(content, fileStore.contentOf(mediaStoreId))
    }

    @Test
    fun `completes without size validation when the task has no known size`() = runTest {
        val content = sampleBytes(CONTENT_SIZE)
        enqueueAudio(content)
        val repo = FakeTaskRepository(
            task(sizeBytes = null, md5 = null, streamUrl = streamUrl()),
        )
        val fileStore = fileStore()

        val result = worker(repo, fileStore).execute(TASK_ID)

        assertEquals(WorkerResult.Completed, result)
        val mediaStoreId = requireNotNull(repo.task.mediaStoreId)
        assertArrayEquals(content, fileStore.contentOf(mediaStoreId))
        assertEquals(
            "a download without a known size must still be recorded as completed",
            mediaStoreId,
            repo.completedMediaStoreId,
        )
    }

    @Test
    fun `fails with integrity and deletes the file when the received size is short`() = runTest {
        val content = sampleBytes(CONTENT_SIZE)
        enqueueAudio(content)
        val repo = FakeTaskRepository(
            task(sizeBytes = content.size.toLong() - 1, md5 = null, streamUrl = streamUrl()),
        )
        val fileStore = fileStore()

        val result = worker(repo, fileStore).execute(TASK_ID)

        assertEquals(WorkerResult.Failed(FailureKind.Integrity), result)
        assertEquals(
            "the failed row must record the integrity failure",
            FailureKind.Integrity,
            repo.failureKind,
        )
        assertEquals(TaskStatus.Failed, repo.task.status)
        val mediaStoreId = requireNotNull(repo.attachedMediaStoreId) {
            "the pending file must have been created"
        }
        assertFalse("integrity failure must delete the pending file", fileStore.exists(mediaStoreId))
    }

    @Test
    fun `fails with integrity and deletes the file when the md5 does not match`() = runTest {
        val content = sampleBytes(CONTENT_SIZE)
        val wrongMd5 = md5Hex(ByteArray(CONTENT_SIZE) { 0x5A })
        enqueueAudio(content)
        val repo = FakeTaskRepository(
            task(sizeBytes = content.size.toLong(), md5 = wrongMd5, streamUrl = streamUrl()),
        )
        val fileStore = fileStore()

        val result = worker(repo, fileStore).execute(TASK_ID)

        assertEquals(WorkerResult.Failed(FailureKind.Integrity), result)
        assertEquals(
            "the failed row must record the integrity failure",
            FailureKind.Integrity,
            repo.failureKind,
        )
        assertEquals(TaskStatus.Failed, repo.task.status)
        val mediaStoreId = requireNotNull(repo.attachedMediaStoreId) {
            "the pending file must have been created"
        }
        assertFalse("integrity failure must delete the pending file", fileStore.exists(mediaStoreId))
    }

    @Test
    fun `fails with integrity when the written entry disappears before verification`() = runTest {
        val content = sampleBytes(CONTENT_SIZE)
        enqueueAudio(content)
        val repo = FakeTaskRepository(
            task(sizeBytes = content.size.toLong(), md5 = null, streamUrl = streamUrl()),
        )
        val fileStore = fileStore().apply { dropEntryOnWriteClose = true }

        val result = worker(repo, fileStore).execute(TASK_ID)

        assertEquals(WorkerResult.Failed(FailureKind.Integrity), result)
        assertEquals(
            "a vanished entry must be reported as a local integrity failure",
            FailureKind.Integrity,
            repo.failureKind,
        )
        assertEquals(TaskStatus.Failed, repo.task.status)
        val mediaStoreId = requireNotNull(repo.attachedMediaStoreId) {
            "the pending file must have been created"
        }
        assertFalse("the vanished entry must not be left behind", fileStore.exists(mediaStoreId))
        assertNull("a vanished entry must never be published as completed", repo.completedMediaStoreId)
    }

    @Test
    fun `requests the stream url stored on the task`() = runTest {
        val content = sampleBytes(CONTENT_SIZE)
        val targetUrl = streamUrl()
        enqueueAudio(content)
        val repo = FakeTaskRepository(
            task(sizeBytes = content.size.toLong(), md5 = md5Hex(content), streamUrl = targetUrl),
        )

        val result = worker(repo, fileStore()).execute(TASK_ID)

        assertEquals(WorkerResult.Completed, result)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals(targetUrl, request.url.toString())
    }

    private fun worker(repo: FakeTaskRepository, fileStore: FakeTaskFileStore): DefaultDownloadWorker =
        DefaultDownloadWorker(
            repo = repo,
            resolver = UnusedTrackResolver(),
            settings = FakeSettings(),
            fileStore = fileStore,
            namer = FileNamer(repo),
            client = client,
            clock = FixedClock(),
        )

    private fun task(sizeBytes: Long?, md5: String?, streamUrl: String): DownloadTask = DownloadTask(
        id = TASK_ID,
        songId = SongId(SONG_ID),
        sourceUrl = "https://163cn.tv/AbCd12",
        requestedQuality = MusicQuality.Exhigh,
        actualQuality = MusicQuality.Exhigh,
        title = "测试歌曲",
        artist = "测试歌手",
        album = "测试专辑",
        artworkUrl = null,
        format = AudioFormat.Mp3,
        sizeBytes = sizeBytes,
        md5 = md5,
        streamUrl = streamUrl,
        etag = null,
        bytesDownloaded = 0,
        status = TaskStatus.Waiting,
        mediaStoreId = null,
        fileName = null,
        failureKind = null,
        failureMessage = null,
        createdAt = 1_000L,
        updatedAt = 1_000L,
        completedAt = null,
    )

    private fun streamUrl(): String = server.url("/audio").toString()

    private fun fileStore(): FakeTaskFileStore = FakeTaskFileStore(tempFolder.newFolder())

    private fun enqueueAudio(content: ByteArray) {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "audio/mpeg")
                .body(Buffer().write(content))
                .build(),
        )
    }

    private fun sampleBytes(size: Int): ByteArray = ByteArray(size) { index -> (index * 31 + 7).toByte() }

    private fun md5Hex(content: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(content)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
}

/**
 * 首次下载不应触发重新解析；若被错误调用，测试会以明确的错误失败。
 */
private class UnusedTrackResolver : TrackResolver {
    override suspend fun resolve(
        songId: SongId,
        requested: MusicQuality,
        apiKey: String,
    ): ApiResponse<ResolvedTrack> = error("an initial download must not resolve the track")
}

private class FakeSettings : AppSettings {
    private val keyFlow = MutableStateFlow<String?>(null)

    override val apiKey: Flow<String?> = keyFlow
    override val defaultQuality: Flow<MusicQuality> = MutableStateFlow(MusicQuality.Exhigh)
    override val maxConcurrentDownloads: Flow<Int> = MutableStateFlow(3)
    override val themeMode: Flow<ThemeMode> = MutableStateFlow(ThemeMode.System)
    override val notificationAsked: Flow<Boolean> = MutableStateFlow(false)

    override suspend fun setApiKey(value: String) {
        keyFlow.value = value
    }

    override suspend fun setDefaultQuality(value: MusicQuality) = Unit

    override suspend fun setMaxConcurrentDownloads(value: Int) = Unit

    override suspend fun setThemeMode(value: ThemeMode) = Unit

    override suspend fun markNotificationAsked() = Unit
}

private class FixedClock : AppClock {
    override fun nowMillis(): Long = 10_000L
    override fun elapsedRealtime(): Long = 0L
}

/**
 * 记录下载过程中写入仓库的状态，并按真实仓库的约束只允许 `Downloading` 任务写文件与进度。
 */
private class FakeTaskRepository(
    initial: DownloadTask,
    private val allocatedName: String = ALLOCATED_FILE_NAME,
) : TaskRepository {

    var task: DownloadTask = initial
        private set

    val progressUpdates = mutableListOf<Long>()
    val allocatedBases = mutableListOf<BaseFileName>()

    var attachedMediaStoreId: Long? = null
        private set
    var completedMediaStoreId: Long? = null
        private set
    var failureKind: FailureKind? = null
        private set

    override fun observeInProgress(): Flow<List<DownloadTask>> =
        MutableStateFlow<List<DownloadTask>>(emptyList())

    override fun observeCompleted(): Flow<List<DownloadTask>> =
        MutableStateFlow<List<DownloadTask>>(emptyList())

    override suspend fun byId(id: Long): DownloadTask? = task.takeIf { it.id == id }

    override suspend fun startDownloading(id: Long) {
        task = task.copy(status = TaskStatus.Downloading)
    }

    override suspend fun updateProgress(id: Long, bytesDownloaded: Long) {
        check(task.status == TaskStatus.Downloading) {
            "progress must not be written before startDownloading"
        }
        progressUpdates += bytesDownloaded
        task = task.copy(bytesDownloaded = bytesDownloaded)
    }

    override suspend fun attachFile(
        id: Long,
        fileName: String,
        mediaStoreId: Long,
        bytesDownloaded: Long,
    ) {
        check(task.status == TaskStatus.Downloading) {
            "the created file must be attached after startDownloading"
        }
        attachedMediaStoreId = mediaStoreId
        task = task.copy(
            fileName = fileName,
            mediaStoreId = mediaStoreId,
            bytesDownloaded = bytesDownloaded,
        )
    }

    override suspend fun markCompleted(
        id: Long,
        fileName: String,
        mediaStoreId: Long,
        completedAt: Long,
    ) {
        completedMediaStoreId = mediaStoreId
        task = task.copy(
            status = TaskStatus.Completed,
            fileName = fileName,
            mediaStoreId = mediaStoreId,
            completedAt = completedAt,
        )
    }

    override suspend fun markFailed(id: Long, failureKind: FailureKind, message: String) {
        this.failureKind = failureKind
        task = task.copy(status = TaskStatus.Failed, failureKind = failureKind, failureMessage = message)
    }

    override suspend fun allocateFileName(id: Long, base: BaseFileName): String {
        allocatedBases += base
        return allocatedName
    }

    override suspend fun releaseFileName(id: Long) = Unit

    override suspend fun enqueue(text: String, quality: MusicQuality): EnqueueOutcome =
        error("not used by the download worker")

    override suspend fun enqueueLinks(links: List<SongLink>, quality: MusicQuality): EnqueueOutcome =
        error("not used by the download worker")

    override suspend fun markResolving(id: Long) = error("not used by an initial download")

    override suspend fun completeResolve(id: Long, track: ResolvedTrack) =
        error("not used by an initial download")

    override suspend fun updateStream(id: Long, track: ResolvedTrack) =
        error("not used by an initial download")

    override suspend fun markPaused(id: Long) = error("not used by an initial download")

    override suspend fun markCancelled(id: Long) = error("not used by an initial download")

    override suspend fun resetForRetry(id: Long) = error("not used by an initial download")

    override suspend fun pause(id: Long) = error("not used by an initial download")

    override suspend fun resume(id: Long) = error("not used by an initial download")

    override suspend fun retry(id: Long) = error("not used by an initial download")

    override suspend fun deleteRecord(id: Long) = error("not used by an initial download")

    override suspend fun deleteRecords(ids: List<Long>) = error("not used by an initial download")

    override suspend fun clearCompleted() = error("not used by an initial download")

    override suspend fun pauseAll() = error("not used by an initial download")

    override suspend fun resumeAll() = error("not used by an initial download")

    override suspend fun pendingIds(): List<Long> = error("not used by an initial download")

    override suspend fun cancelAll() = error("not used by an initial download")

    override suspend fun retryAllFailed() = error("not used by an initial download")
}
