package com.dawnmoon.music_download.download

import com.dawnmoon.music_download.core.AppClock
import com.dawnmoon.music_download.data.remote.ApiFailure
import com.dawnmoon.music_download.data.remote.ApiResponse
import com.dawnmoon.music_download.data.repository.AppSettings
import com.dawnmoon.music_download.data.repository.EnqueueOutcome
import com.dawnmoon.music_download.data.repository.TaskRepository
import com.dawnmoon.music_download.domain.TaskTransitions
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
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
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
private const val FULL_SIZE = 100 * 1024
private const val RESUME_AT = 40 * 1024
private const val CHUNK_SIZE = 64 * 1024
private const val ETAG = "etag-1"
private const val RESPONSE_ETAG = "\"v1\""

/**
 * [DefaultDownloadWorker] 断点续传与直链过期换链的契约测试。
 *
 * 以真实临时文件保存已下载字节，用 MockWebServer 提供 206/416/500 等响应，
 * 断言请求头、落盘内容、仓库状态迁移与失败类型，而不是替身的调用细节。
 */
class DownloadWorkerResumeTest {

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
    fun `resumes from the persisted file with range and if-range`() = runTest {
        val content = sampleBytes(FULL_SIZE)
        val fileStore = FakeTaskFileStore(tempFolder.newFolder())
        val file = seedFile(fileStore, content, RESUME_AT)
        val repo = ResumableTaskRepository(
            task(
                sizeBytes = content.size.toLong(),
                md5 = md5Hex(content),
                streamUrl = streamUrl("/audio"),
                etag = ETAG,
                bytesDownloaded = RESUME_AT.toLong(),
                mediaStoreId = file.mediaStoreId,
                fileName = file.displayName,
            ),
        )
        enqueuePartial(content, RESUME_AT)

        val result = worker(repo, fileStore, QueueTrackResolver()).execute(TASK_ID)

        assertEquals(WorkerResult.Completed, result)
        val request = server.takeRequest()
        assertEquals("bytes=$RESUME_AT-", request.headers["Range"])
        assertEquals(ETAG, request.headers["If-Range"])
        assertArrayEquals(content, fileStore.contentOf(file.mediaStoreId))
    }

    @Test
    fun `calibrates the range to the actual file size when the database count is stale`() = runTest {
        val content = sampleBytes(FULL_SIZE)
        val fileStore = FakeTaskFileStore(tempFolder.newFolder())
        val file = seedFile(fileStore, content, RESUME_AT)
        val repo = ResumableTaskRepository(
            task(
                sizeBytes = content.size.toLong(),
                md5 = md5Hex(content),
                streamUrl = streamUrl("/audio"),
                etag = ETAG,
                bytesDownloaded = 50_000L,
                mediaStoreId = file.mediaStoreId,
                fileName = file.displayName,
            ),
        )
        enqueuePartial(content, RESUME_AT)

        val result = worker(repo, fileStore, QueueTrackResolver()).execute(TASK_ID)

        assertEquals(WorkerResult.Completed, result)
        val request = server.takeRequest()
        assertEquals(
            "the range must follow the bytes actually present, not the stale database value",
            "bytes=$RESUME_AT-",
            request.headers["Range"],
        )
        assertArrayEquals(content, fileStore.contentOf(file.mediaStoreId))
    }

    @Test
    fun `recreates the pending entry and starts over when the recorded file is gone`() = runTest {
        val content = sampleBytes(FULL_SIZE)
        val fileStore = FakeTaskFileStore(tempFolder.newFolder())
        val repo = ResumableTaskRepository(
            task(
                sizeBytes = content.size.toLong(),
                md5 = md5Hex(content),
                streamUrl = streamUrl("/audio"),
                etag = ETAG,
                bytesDownloaded = RESUME_AT.toLong(),
                mediaStoreId = 99L,
                fileName = "已删除.mp3",
            ),
        )
        enqueueAudio(content)

        val result = worker(repo, fileStore, QueueTrackResolver()).execute(TASK_ID)

        assertEquals(WorkerResult.Completed, result)
        val request = server.takeRequest()
        assertNull("a missing file must force a full download", request.headers["Range"])
        assertEquals("a rebuilt file must start from zero progress", 0L, repo.attachedBytes)
        val newId = requireNotNull(repo.task.mediaStoreId)
        assertTrue("a replacement pending entry must be created", newId != 99L)
        assertArrayEquals(content, fileStore.contentOf(newId))
    }

    @Test
    fun `rewrites from the start when the server ignores the range`() = runTest {
        val content = sampleBytes(FULL_SIZE)
        val fileStore = FakeTaskFileStore(tempFolder.newFolder())
        val file = seedFile(fileStore, content, RESUME_AT)
        val repo = ResumableTaskRepository(
            task(
                sizeBytes = content.size.toLong(),
                md5 = md5Hex(content),
                streamUrl = streamUrl("/audio"),
                etag = ETAG,
                bytesDownloaded = RESUME_AT.toLong(),
                mediaStoreId = file.mediaStoreId,
                fileName = file.displayName,
            ),
        )
        enqueueAudio(content)

        val result = worker(repo, fileStore, QueueTrackResolver()).execute(TASK_ID)

        assertEquals(WorkerResult.Completed, result)
        assertEquals("bytes=$RESUME_AT-", server.takeRequest().headers["Range"])
        assertArrayEquals(
            "a 200 response must replace the file instead of appending to it",
            content,
            fileStore.contentOf(file.mediaStoreId),
        )
    }

    @Test
    fun `captures the response etag to guard the range after an interrupted write`() = runTest {
        val content = sampleBytes(FULL_SIZE)
        val delegate = FakeTaskFileStore(tempFolder.newFolder())
        val file = seedFile(delegate, content, RESUME_AT)
        val fileStore = InterruptingFileStore(delegate)
        fileStore.failTruncateAfterBytes = 4 * 1024
        val repo = ResumableTaskRepository(
            task(
                sizeBytes = content.size.toLong(),
                md5 = md5Hex(content),
                streamUrl = streamUrl("/audio"),
                etag = null,
                bytesDownloaded = RESUME_AT.toLong(),
                mediaStoreId = file.mediaStoreId,
                fileName = file.displayName,
            ),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "audio/mpeg")
                .addHeader("ETag", RESPONSE_ETAG)
                .body(Buffer().write(content))
                .build(),
        )
        val rewritten = 4 * 1024
        enqueuePartial(content, rewritten)

        val result = worker(repo, fileStore, QueueTrackResolver()).execute(TASK_ID)

        assertEquals(WorkerResult.Completed, result)
        assertEquals("bytes=$RESUME_AT-", server.takeRequest().headers["Range"])
        val resumedRequest = server.takeRequest()
        assertEquals(
            "the etag learned from the successful response must guard the resumed range",
            RESPONSE_ETAG,
            resumedRequest.headers["If-Range"],
        )
        assertEquals("bytes=$rewritten-", resumedRequest.headers["Range"])
        assertEquals(
            "the etag from the response must be persisted on the task",
            RESPONSE_ETAG,
            repo.streamUpdates.lastOrNull()?.etag,
        )
        assertEquals(
            "recording the etag must keep the bytes already written",
            RESUME_AT.toLong(),
            repo.bytesAtStreamUpdate,
        )
        assertEquals(RESPONSE_ETAG, repo.task.etag)
        assertArrayEquals(content, delegate.contentOf(file.mediaStoreId))
    }

    @Test
    fun `captures the etag from a partial response to guard the next range`() = runTest {
        val content = sampleBytes(FULL_SIZE)
        val delegate = FakeTaskFileStore(tempFolder.newFolder())
        val file = seedFile(delegate, content, RESUME_AT)
        val fileStore = InterruptingFileStore(delegate)
        fileStore.failAppendAfterBytes = 4 * 1024
        val repo = ResumableTaskRepository(
            task(
                sizeBytes = content.size.toLong(),
                md5 = md5Hex(content),
                streamUrl = streamUrl("/audio"),
                etag = null,
                bytesDownloaded = RESUME_AT.toLong(),
                mediaStoreId = file.mediaStoreId,
                fileName = file.displayName,
            ),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(206)
                .addHeader("Content-Type", "audio/mpeg")
                .addHeader("Content-Range", "bytes $RESUME_AT-${content.size - 1}/${content.size}")
                .addHeader("ETag", RESPONSE_ETAG)
                .body(Buffer().write(content, RESUME_AT, content.size - RESUME_AT))
                .build(),
        )
        val resumedAt = RESUME_AT + 4 * 1024
        enqueuePartial(content, resumedAt)

        val result = worker(repo, fileStore, QueueTrackResolver()).execute(TASK_ID)

        assertEquals(WorkerResult.Completed, result)
        val firstRequest = server.takeRequest()
        assertEquals("bytes=$RESUME_AT-", firstRequest.headers["Range"])
        assertNull(
            "an unknown etag must not be sent before it is learned",
            firstRequest.headers["If-Range"],
        )
        val resumedRequest = server.takeRequest()
        assertEquals("bytes=$resumedAt-", resumedRequest.headers["Range"])
        assertEquals(
            "the etag learned from a partial response must guard the resumed range",
            RESPONSE_ETAG,
            resumedRequest.headers["If-Range"],
        )
        assertEquals(
            "the etag from a partial response must be persisted on the task",
            RESPONSE_ETAG,
            repo.streamUpdates.lastOrNull()?.etag,
        )
        assertEquals(
            "recording the etag must keep the bytes already written",
            RESUME_AT.toLong(),
            repo.bytesAtStreamUpdate,
        )
        assertEquals(RESPONSE_ETAG, repo.task.etag)
        assertArrayEquals(content, delegate.contentOf(file.mediaStoreId))
    }

    @Test
    fun `skips etag capture when the task has no md5 to prove the content`() = runTest {
        val content = sampleBytes(FULL_SIZE)
        val delegate = FakeTaskFileStore(tempFolder.newFolder())
        val file = seedFile(delegate, content, RESUME_AT)
        val fileStore = InterruptingFileStore(delegate)
        fileStore.failAppendAfterBytes = 4 * 1024
        val repo = ResumableTaskRepository(
            task(
                sizeBytes = content.size.toLong(),
                md5 = null,
                streamUrl = streamUrl("/audio"),
                etag = null,
                bytesDownloaded = RESUME_AT.toLong(),
                mediaStoreId = file.mediaStoreId,
                fileName = file.displayName,
            ),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(206)
                .addHeader("Content-Type", "audio/mpeg")
                .addHeader("Content-Range", "bytes $RESUME_AT-${content.size - 1}/${content.size}")
                .addHeader("ETag", RESPONSE_ETAG)
                .body(Buffer().write(content, RESUME_AT, content.size - RESUME_AT))
                .build(),
        )
        repeat(3) { server.enqueue(MockResponse.Builder().code(500).build()) }

        val result = worker(repo, fileStore, QueueTrackResolver()).execute(TASK_ID)

        assertEquals(WorkerResult.Failed(FailureKind.ServiceUnavailable), result)
        assertEquals(TaskStatus.Failed, repo.task.status)
        assertTrue(
            "an unverifiable etag must not detach the file from the task",
            repo.streamUpdates.isEmpty(),
        )
        assertEquals(
            "the pending entry must stay bound after an unverifiable etag",
            file.mediaStoreId,
            repo.task.mediaStoreId,
        )
        assertEquals(
            "the downloaded bytes must survive an unverifiable etag",
            RESUME_AT.toLong(),
            repo.task.bytesDownloaded,
        )
        assertNull("an unverifiable etag must not guard later ranges", repo.task.etag)
        assertEquals("bytes=$RESUME_AT-", server.takeRequest().headers["Range"])
        val resumedRequest = server.takeRequest()
        assertEquals("bytes=${RESUME_AT + 4 * 1024}-", resumedRequest.headers["Range"])
        assertNull(
            "a task without md5 must not send an etag it cannot verify",
            resumedRequest.headers["If-Range"],
        )
    }

    @Test
    fun `persists the etag received with a successful response when the task completes`() = runTest {
        val content = sampleBytes(FULL_SIZE)
        val fileStore = FakeTaskFileStore(tempFolder.newFolder())
        val repo = ResumableTaskRepository(
            task(
                sizeBytes = content.size.toLong(),
                md5 = md5Hex(content),
                streamUrl = streamUrl("/audio"),
                etag = null,
            ),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "audio/mpeg")
                .addHeader("ETag", RESPONSE_ETAG)
                .body(Buffer().write(content))
                .build(),
        )

        val result = worker(repo, fileStore, QueueTrackResolver()).execute(TASK_ID)

        assertEquals(WorkerResult.Completed, result)
        assertEquals(
            "a completed task must keep the etag learned from the response",
            RESPONSE_ETAG,
            repo.task.etag,
        )
        assertArrayEquals(content, fileStore.contentOf(requireNotNull(repo.task.mediaStoreId)))
    }

    @Test
    fun `refreshes the link when a partial response describes a different total`() = runTest {
        val content = sampleBytes(FULL_SIZE)
        val fileStore = FakeTaskFileStore(tempFolder.newFolder())
        val file = seedFile(fileStore, content, RESUME_AT)
        val repo = ResumableTaskRepository(
            task(
                sizeBytes = content.size.toLong(),
                md5 = md5Hex(content),
                streamUrl = streamUrl("/audio"),
                etag = ETAG,
                bytesDownloaded = RESUME_AT.toLong(),
                mediaStoreId = file.mediaStoreId,
                fileName = file.displayName,
            ),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(206)
                .addHeader("Content-Range", "bytes $RESUME_AT-${999_999 - 1}/999999")
                .body(Buffer().write(ByteArray(100) { 0x7F }))
                .build(),
        )
        val freshUrl = streamUrl("/fresh")
        val resolver = QueueTrackResolver(
            listOf(
                ApiResponse.Success(
                    resolved(
                        streamUrl = freshUrl,
                        format = AudioFormat.Mp3,
                        sizeBytes = content.size.toLong(),
                        md5 = md5Hex(content),
                        etag = "etag-2",
                    ),
                    quota = null,
                ),
            ),
        )
        enqueuePartial(content, RESUME_AT)

        val result = worker(repo, fileStore, resolver).execute(TASK_ID)

        assertEquals(WorkerResult.Completed, result)
        assertEquals("/audio", server.takeRequest().url.encodedPath)
        assertEquals(
            "a mismatched total must refresh the link instead of redownloading the same one",
            "/fresh",
            server.takeRequest().url.encodedPath,
        )
        assertArrayEquals(
            "bytes from a response describing another file must never be appended",
            content,
            fileStore.contentOf(file.mediaStoreId),
        )
    }

    @Test
    fun `refreshes an expired link and keeps the downloaded bytes`() = runTest {
        val content = sampleBytes(FULL_SIZE)
        val fileStore = FakeTaskFileStore(tempFolder.newFolder())
        val file = seedFile(fileStore, content, RESUME_AT)
        val repo = ResumableTaskRepository(
            task(
                sizeBytes = content.size.toLong(),
                md5 = md5Hex(content),
                streamUrl = streamUrl("/audio"),
                etag = ETAG,
                bytesDownloaded = RESUME_AT.toLong(),
                mediaStoreId = file.mediaStoreId,
                fileName = file.displayName,
            ),
        )
        server.enqueue(MockResponse.Builder().code(403).build())
        val freshUrl = streamUrl("/fresh")
        val freshTrack = resolved(
            streamUrl = freshUrl,
            format = AudioFormat.Mp3,
            sizeBytes = content.size.toLong(),
            md5 = md5Hex(content),
            etag = "etag-2",
        )
        val resolver = QueueTrackResolver(listOf(ApiResponse.Success(freshTrack, quota = null)))
        enqueuePartial(content, RESUME_AT)

        val result = worker(repo, fileStore, resolver).execute(TASK_ID)

        assertEquals(WorkerResult.Completed, result)
        assertEquals(
            "an expired link must pass through Resolving before downloading again",
            listOf(TaskStatus.Downloading, TaskStatus.Resolving, TaskStatus.Downloading),
            repo.statusHistory.take(3),
        )
        assertEquals("test-key", resolver.calls.first().apiKey)
        assertEquals(freshUrl, repo.task.streamUrl)
        assertEquals("etag-2", repo.task.etag)
        assertEquals(content.size.toLong(), repo.task.sizeBytes)
        assertEquals(md5Hex(content), repo.task.md5)
        assertEquals(
            "the refreshed link must keep the bytes already written",
            RESUME_AT.toLong(),
            repo.bytesAtStreamUpdate,
        )
        val expiredRequest = server.takeRequest()
        assertEquals("bytes=$RESUME_AT-", expiredRequest.headers["Range"])
        val freshRequest = server.takeRequest()
        assertEquals("bytes=$RESUME_AT-", freshRequest.headers["Range"])
        assertEquals("etag-2", freshRequest.headers["If-Range"])
        assertArrayEquals(content, fileStore.contentOf(file.mediaStoreId))
    }

    @Test
    fun `rebuilds the file when the refreshed link points to a different format`() = runTest {
        val oldContent = sampleBytes(FULL_SIZE)
        val newContent = sampleBytes(80 * 1024)
        val newMd5 = md5Hex(newContent)
        val fileStore = FakeTaskFileStore(tempFolder.newFolder())
        val file = seedFile(fileStore, oldContent, RESUME_AT)
        val repo = ResumableTaskRepository(
            task(
                sizeBytes = oldContent.size.toLong(),
                md5 = md5Hex(oldContent),
                streamUrl = streamUrl("/audio"),
                etag = ETAG,
                bytesDownloaded = RESUME_AT.toLong(),
                mediaStoreId = file.mediaStoreId,
                fileName = file.displayName,
            ),
        )
        server.enqueue(MockResponse.Builder().code(403).build())
        val freshUrl = streamUrl("/fresh.flac")
        val resolver = QueueTrackResolver(
            listOf(
                ApiResponse.Success(
                    resolved(
                        streamUrl = freshUrl,
                        format = AudioFormat.Flac,
                        sizeBytes = newContent.size.toLong(),
                        md5 = newMd5,
                        etag = "etag-flac",
                    ),
                    quota = null,
                ),
            ),
        )
        enqueueAudio(newContent)

        val result = worker(repo, fileStore, resolver).execute(TASK_ID)

        assertEquals(WorkerResult.Completed, result)
        assertFalse("the old pending entry must be removed", fileStore.exists(file.mediaStoreId))
        assertEquals(AudioFormat.Flac, repo.task.format)
        assertEquals(newContent.size.toLong(), repo.task.sizeBytes)
        assertEquals(newMd5, repo.task.md5)
        assertTrue(
            "the rebuilt file must use the refreshed format extension, got ${repo.task.fileName}",
            repo.task.fileName.orEmpty().endsWith(".flac"),
        )
        assertEquals("the new file starts from zero", 0L, repo.attachedBytes)
        val newId = requireNotNull(repo.task.mediaStoreId)
        assertArrayEquals(newContent, fileStore.contentOf(newId))
        assertEquals("bytes=$RESUME_AT-", server.takeRequest().headers["Range"])
        val freshRequest = server.takeRequest()
        assertEquals("/fresh.flac", freshRequest.url.encodedPath)
        assertNull(
            "a file belonging to another format must not be resumed",
            freshRequest.headers["Range"],
        )
    }

    @Test
    fun `fails with the resolver failure kind when the refreshed link cannot be resolved`() = runTest {
        val cases = listOf(
            ApiFailure.InvalidKey("invalid key") to FailureKind.InvalidKey,
            ApiFailure.QuotaExhausted("quota exhausted") to FailureKind.QuotaExhausted,
            ApiFailure.Forbidden("forbidden") to FailureKind.Forbidden,
        )
        for ((failure, expectedKind) in cases) {
            val content = sampleBytes(FULL_SIZE)
            val fileStore = FakeTaskFileStore(tempFolder.newFolder())
            val file = seedFile(fileStore, content, RESUME_AT)
            val repo = ResumableTaskRepository(
                task(
                    sizeBytes = content.size.toLong(),
                    md5 = md5Hex(content),
                    streamUrl = streamUrl("/audio"),
                    etag = ETAG,
                    bytesDownloaded = RESUME_AT.toLong(),
                    mediaStoreId = file.mediaStoreId,
                    fileName = file.displayName,
                ),
            )
            server.enqueue(MockResponse.Builder().code(403).build())
            val resolver = QueueTrackResolver(
                listOf(ApiResponse.Failure(failure, quota = null)),
            )
            val requestsBefore = server.requestCount

            val result = worker(repo, fileStore, resolver).execute(TASK_ID)

            assertEquals("resolver failure $failure", WorkerResult.Failed(expectedKind), result)
            assertEquals(expectedKind, repo.lastFailureKind)
            assertEquals(TaskStatus.Failed, repo.task.status)
            assertEquals(
                "a rejected refresh must not be retried",
                requestsBefore + 1,
                server.requestCount,
            )
        }
    }

    @Test
    fun `fails with stream expired when the refreshed link is also rejected`() = runTest {
        val content = sampleBytes(FULL_SIZE)
        val fileStore = FakeTaskFileStore(tempFolder.newFolder())
        val file = seedFile(fileStore, content, RESUME_AT)
        val repo = ResumableTaskRepository(
            task(
                sizeBytes = content.size.toLong(),
                md5 = md5Hex(content),
                streamUrl = streamUrl("/audio"),
                etag = ETAG,
                bytesDownloaded = RESUME_AT.toLong(),
                mediaStoreId = file.mediaStoreId,
                fileName = file.displayName,
            ),
        )
        server.enqueue(MockResponse.Builder().code(403).build())
        server.enqueue(MockResponse.Builder().code(403).build())
        val resolver = QueueTrackResolver(
            listOf(
                ApiResponse.Success(
                    resolved(
                        streamUrl = streamUrl("/fresh"),
                        format = AudioFormat.Mp3,
                        sizeBytes = content.size.toLong(),
                        md5 = md5Hex(content),
                        etag = "etag-2",
                    ),
                    quota = null,
                ),
            ),
        )

        val result = worker(repo, fileStore, resolver).execute(TASK_ID)

        assertEquals(WorkerResult.Failed(FailureKind.StreamExpired), result)
        assertEquals(FailureKind.StreamExpired, repo.lastFailureKind)
        assertEquals("the rejected refresh must not loop", 2, server.requestCount)
    }

    @Test
    fun `redownloads from the start after a range not satisfiable response`() = runTest {
        val content = sampleBytes(FULL_SIZE)
        val fileStore = FakeTaskFileStore(tempFolder.newFolder())
        val file = seedFile(fileStore, content, RESUME_AT)
        val repo = ResumableTaskRepository(
            task(
                sizeBytes = content.size.toLong(),
                md5 = md5Hex(content),
                streamUrl = streamUrl("/audio"),
                etag = ETAG,
                bytesDownloaded = RESUME_AT.toLong(),
                mediaStoreId = file.mediaStoreId,
                fileName = file.displayName,
            ),
        )
        server.enqueue(MockResponse.Builder().code(416).build())
        enqueueAudio(content)

        val result = worker(repo, fileStore, QueueTrackResolver()).execute(TASK_ID)

        assertEquals(WorkerResult.Completed, result)
        assertEquals("bytes=$RESUME_AT-", server.takeRequest().headers["Range"])
        assertNull(
            "after a 416 the file must be rewritten from zero",
            server.takeRequest().headers["Range"],
        )
        assertArrayEquals(content, fileStore.contentOf(file.mediaStoreId))
    }

    @Test
    fun `retries from zero when a transient failure follows a range not satisfiable response`() = runTest {
        val content = sampleBytes(FULL_SIZE)
        val fileStore = FakeTaskFileStore(tempFolder.newFolder())
        val file = seedFile(fileStore, content, RESUME_AT)
        val repo = ResumableTaskRepository(
            task(
                sizeBytes = content.size.toLong(),
                md5 = md5Hex(content),
                streamUrl = streamUrl("/audio"),
                etag = ETAG,
                bytesDownloaded = RESUME_AT.toLong(),
                mediaStoreId = file.mediaStoreId,
                fileName = file.displayName,
            ),
        )
        server.dispatcher = RangeAwareAudioDispatcher(content)

        val result = worker(
            repo = repo,
            fileStore = fileStore,
            resolver = QueueTrackResolver(),
            client = OkHttpClient.Builder().retryOnConnectionFailure(false).build(),
        ).execute(TASK_ID)

        assertEquals(WorkerResult.Completed, result)
        assertEquals("bytes=$RESUME_AT-", server.takeRequest().headers["Range"])
        assertNull(
            "the retry after a 416 must not resend the stale range",
            server.takeRequest().headers["Range"],
        )
        assertNull(
            "a transient failure after a 416 must not revive the stale range",
            server.takeRequest().headers["Range"],
        )
        assertArrayEquals(content, fileStore.contentOf(file.mediaStoreId))
    }

    @Test
    fun `rebuilds the file when the truncation after a range not satisfiable fails once`() = runTest {
        val content = sampleBytes(FULL_SIZE)
        val delegate = FakeTaskFileStore(tempFolder.newFolder())
        val file = seedFile(delegate, content, RESUME_AT)
        val fileStore = FailFirstTruncateFileStore(delegate)
        val repo = ResumableTaskRepository(
            task(
                sizeBytes = content.size.toLong(),
                md5 = md5Hex(content),
                streamUrl = streamUrl("/audio"),
                etag = ETAG,
                bytesDownloaded = RESUME_AT.toLong(),
                mediaStoreId = file.mediaStoreId,
                fileName = file.displayName,
            ),
        )
        server.enqueue(MockResponse.Builder().code(416).build())
        enqueueAudio(content)

        val result = worker(repo, fileStore, resolver = QueueTrackResolver()).execute(TASK_ID)

        assertEquals(WorkerResult.Completed, result)
        assertEquals("bytes=$RESUME_AT-", server.takeRequest().headers["Range"])
        assertNull(
            "an unopenable entry must not leave the stale range in play",
            server.takeRequest().headers["Range"],
        )
        val rebuiltId = requireNotNull(repo.task.mediaStoreId)
        assertTrue("the unopenable entry must be replaced", rebuiltId != file.mediaStoreId)
        assertEquals("a rebuilt entry starts from zero progress", 0L, repo.attachedBytes)
        assertArrayEquals(content, delegate.contentOf(rebuiltId))
    }

    @Test
    fun `retries server errors and completes on the third attempt`() = runTest {
        val content = sampleBytes(FULL_SIZE)
        val fileStore = FakeTaskFileStore(tempFolder.newFolder())
        val repo = ResumableTaskRepository(
            task(
                sizeBytes = content.size.toLong(),
                md5 = md5Hex(content),
                streamUrl = streamUrl("/audio"),
            ),
        )
        server.enqueue(MockResponse.Builder().code(500).build())
        server.enqueue(MockResponse.Builder().code(500).build())
        enqueueAudio(content)

        val result = worker(repo, fileStore, QueueTrackResolver()).execute(TASK_ID)

        assertEquals(WorkerResult.Completed, result)
        assertEquals(3, server.requestCount)
        val mediaStoreId = requireNotNull(repo.task.mediaStoreId)
        assertArrayEquals(content, fileStore.contentOf(mediaStoreId))
    }

    @Test
    fun `fails with service unavailable after three server errors`() = runTest {
        val content = sampleBytes(FULL_SIZE)
        val fileStore = FakeTaskFileStore(tempFolder.newFolder())
        val repo = ResumableTaskRepository(
            task(
                sizeBytes = content.size.toLong(),
                md5 = md5Hex(content),
                streamUrl = streamUrl("/audio"),
            ),
        )
        repeat(3) { server.enqueue(MockResponse.Builder().code(500).build()) }

        val result = worker(repo, fileStore, QueueTrackResolver()).execute(TASK_ID)

        assertEquals(WorkerResult.Failed(FailureKind.ServiceUnavailable), result)
        assertEquals(FailureKind.ServiceUnavailable, repo.lastFailureKind)
        assertEquals("only two retries are allowed", 3, server.requestCount)
    }

    @Test
    fun `resumes after a mid-write interruption from the actual file size even when the link refreshes`() = runTest {
        val content = sampleBytes(FULL_SIZE)
        val delegate = FakeTaskFileStore(tempFolder.newFolder())
        val file = seedFile(delegate, content, RESUME_AT)
        val fileStore = InterruptingFileStore(delegate)
        fileStore.failAppendAfterBytes = 4 * 1024
        val repo = ResumableTaskRepository(
            task(
                sizeBytes = content.size.toLong(),
                md5 = md5Hex(content),
                streamUrl = streamUrl("/audio"),
                etag = ETAG,
                bytesDownloaded = RESUME_AT.toLong(),
                mediaStoreId = file.mediaStoreId,
                fileName = file.displayName,
            ),
        )
        server.enqueue(partialResponse(content, RESUME_AT))
        server.enqueue(MockResponse.Builder().code(403).build())
        val resumedAt = RESUME_AT + 4 * 1024
        val freshUrl = streamUrl("/fresh")
        val resolver = QueueTrackResolver(
            listOf(
                ApiResponse.Success(
                    resolved(
                        streamUrl = freshUrl,
                        format = AudioFormat.Mp3,
                        sizeBytes = content.size.toLong(),
                        md5 = md5Hex(content),
                        etag = "etag-2",
                    ),
                    quota = null,
                ),
            ),
        )
        server.enqueue(partialResponse(content, resumedAt))

        val result = worker(repo, fileStore, resolver).execute(TASK_ID)

        assertEquals(WorkerResult.Completed, result)
        assertEquals("/audio", server.takeRequest().url.encodedPath)
        assertEquals(
            "the retry after an interrupted write must resume from the bytes actually written",
            "bytes=$resumedAt-",
            server.takeRequest().headers["Range"],
        )
        val freshRequest = server.takeRequest()
        assertEquals("/fresh", freshRequest.url.encodedPath)
        assertEquals(
            "the refreshed link must not fall back to the stale throttled database progress",
            "bytes=$resumedAt-",
            freshRequest.headers["Range"],
        )
        assertEquals("etag-2", freshRequest.headers["If-Range"])
        assertArrayEquals(content, delegate.contentOf(file.mediaStoreId))
    }

    @Test
    fun `rebuilds a same-format file without appending a duplicate name suffix`() = runTest {
        val oldContent = sampleBytes(FULL_SIZE)
        val newContent = sampleBytes(80 * 1024)
        val newMd5 = md5Hex(newContent)
        val delegate = FakeTaskFileStore(tempFolder.newFolder())
        val file = seedFile(delegate, oldContent, RESUME_AT)
        val repo = ResumableTaskRepository(
            task(
                sizeBytes = oldContent.size.toLong(),
                md5 = md5Hex(oldContent),
                streamUrl = streamUrl("/audio"),
                etag = ETAG,
                bytesDownloaded = RESUME_AT.toLong(),
                mediaStoreId = file.mediaStoreId,
                fileName = file.displayName,
            ),
        )
        server.enqueue(MockResponse.Builder().code(403).build())
        val freshUrl = streamUrl("/fresh")
        val resolver = QueueTrackResolver(
            listOf(
                ApiResponse.Success(
                    resolved(
                        streamUrl = freshUrl,
                        format = AudioFormat.Mp3,
                        sizeBytes = newContent.size.toLong(),
                        md5 = newMd5,
                        etag = "etag-2",
                    ),
                    quota = null,
                ),
            ),
        )
        enqueueAudio(newContent)

        val result = worker(repo, delegate, resolver).execute(TASK_ID)

        assertEquals(WorkerResult.Completed, result)
        assertEquals(
            "a rebuilt file of the same format must keep its original name",
            file.displayName,
            repo.task.fileName,
        )
        assertEquals(
            "the same-format rebuild must reuse the existing pending entry",
            file.mediaStoreId,
            repo.task.mediaStoreId,
        )
        assertTrue(
            "the worker must not ask for another name when it can reuse its own entry",
            repo.allocatedBases.isEmpty(),
        )
        assertNull("no new file should be attached", repo.attachedBytes)
        assertEquals(AudioFormat.Mp3, repo.task.format)
        assertEquals(newContent.size.toLong(), repo.task.sizeBytes)
        assertEquals(newMd5, repo.task.md5)
        assertArrayEquals(newContent, delegate.contentOf(file.mediaStoreId))
        assertTrue("the old file must be replaced, not appended to", delegate.exists(file.mediaStoreId))
        assertEquals("bytes=$RESUME_AT-", server.takeRequest().headers["Range"])
        assertNull(
            "a rebuilt file must be requested from the beginning",
            server.takeRequest().headers["Range"],
        )
    }

    @Test
    fun `recreates the pending entry when it disappears during a write`() = runTest {
        val content = sampleBytes(FULL_SIZE)
        val delegate = FakeTaskFileStore(tempFolder.newFolder())
        val file = seedFile(delegate, content, RESUME_AT)
        val fileStore = InterruptingFileStore(delegate)
        fileStore.vanishAfterBytes = 8 * 1024
        val repo = ResumableTaskRepository(
            task(
                sizeBytes = content.size.toLong(),
                md5 = md5Hex(content),
                streamUrl = streamUrl("/audio"),
                etag = ETAG,
                bytesDownloaded = RESUME_AT.toLong(),
                mediaStoreId = file.mediaStoreId,
                fileName = file.displayName,
            ),
        )
        server.enqueue(partialResponse(content, RESUME_AT))
        enqueueAudio(content)

        val result = worker(repo, fileStore, resolver = QueueTrackResolver()).execute(TASK_ID)

        assertEquals(WorkerResult.Completed, result)
        assertEquals("bytes=$RESUME_AT-", server.takeRequest().headers["Range"])
        assertNull(
            "a file that vanished mid-write must be rebuilt and requested from zero",
            server.takeRequest().headers["Range"],
        )
        val newId = requireNotNull(repo.task.mediaStoreId)
        assertTrue("the vanished entry must be replaced", newId != file.mediaStoreId)
        assertEquals("a rebuilt entry starts from zero progress", 0L, repo.attachedBytes)
        assertEquals(file.displayName, repo.task.fileName)
        assertArrayEquals(content, delegate.contentOf(newId))
    }

    private fun worker(
        repo: ResumableTaskRepository,
        fileStore: TaskFileStore,
        resolver: TrackResolver,
        client: OkHttpClient = this.client,
    ): DefaultDownloadWorker = DefaultDownloadWorker(
        repo = repo,
        resolver = resolver,
        settings = ResumeFakeSettings(),
        fileStore = fileStore,
        namer = FileNamer(repo),
        client = client,
        clock = ResumeFixedClock(),
        sleeper = { },
    )

    private fun task(
        sizeBytes: Long?,
        md5: String?,
        streamUrl: String,
        etag: String? = null,
        bytesDownloaded: Long = 0,
        mediaStoreId: Long? = null,
        fileName: String? = null,
    ): DownloadTask = DownloadTask(
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
        etag = etag,
        bytesDownloaded = bytesDownloaded,
        status = TaskStatus.Waiting,
        mediaStoreId = mediaStoreId,
        fileName = fileName,
        failureKind = null,
        failureMessage = null,
        createdAt = 1_000L,
        updatedAt = 1_000L,
        completedAt = null,
    )

    private fun resolved(
        streamUrl: String,
        format: AudioFormat,
        sizeBytes: Long?,
        md5: String?,
        etag: String?,
    ): ResolvedTrack = ResolvedTrack(
        songId = SongId(SONG_ID),
        title = "测试歌曲",
        artist = "测试歌手",
        album = "测试专辑",
        artworkUrl = null,
        requestedQuality = MusicQuality.Exhigh,
        actualQuality = MusicQuality.Exhigh,
        format = format,
        streamUrl = streamUrl,
        sizeBytes = sizeBytes,
        md5 = md5,
        etag = etag,
    )

    private fun streamUrl(path: String): String = server.url(path).toString()

    private suspend fun seedFile(
        fileStore: FakeTaskFileStore,
        content: ByteArray,
        length: Int,
    ): CreatedFile {
        val file = fileStore.createPending("测试歌曲_测试歌手.mp3", AudioFormat.Mp3.mimeType)
        fileStore.openAppend(file).use { output -> output.write(content, 0, length) }
        return file
    }

    private fun enqueueAudio(content: ByteArray) {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "audio/mpeg")
                .body(Buffer().write(content))
                .build(),
        )
    }

    private fun enqueuePartial(content: ByteArray, from: Int) {
        server.enqueue(partialResponse(content, from))
    }

    private fun partialResponse(content: ByteArray, from: Int): MockResponse =
        MockResponse.Builder()
            .code(206)
            .addHeader("Content-Type", "audio/mpeg")
            .addHeader("Content-Range", "bytes $from-${content.size - 1}/${content.size}")
            .body(Buffer().write(content, from, content.size - from))
            .build()

    private fun sampleBytes(size: Int): ByteArray = ByteArray(size) { index -> (index * 31 + 7).toByte() }

    private fun md5Hex(content: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(content)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
}

/**
 * 模拟不支持 Range 的直链，并在下一次完整请求开始时断开一次连接。
 *
 * 带 Range 的请求一律返回 416；第一个不带 Range 的请求在响应开始前关闭 socket，
 * 制造一次服务端无法响应的瞬时 IOException；之后的不带 Range 请求返回完整音频。
 * 这样可以在 416 与成功响应之间插入一次与文件写入无关的网络中断。
 */
private class RangeAwareAudioDispatcher(private val content: ByteArray) : Dispatcher() {

    private var fullRequests = 0

    override fun dispatch(request: RecordedRequest): MockResponse {
        if (request.headers["Range"] != null) {
            return MockResponse.Builder().code(416).build()
        }
        fullRequests += 1
        if (fullRequests == 1) {
            return MockResponse.Builder()
                .onResponseStart(SocketEffect.CloseSocket())
                .build()
        }
        return MockResponse.Builder()
            .code(200)
            .addHeader("Content-Type", "audio/mpeg")
            .body(Buffer().write(content))
            .build()
    }
}

/**
 * 在真实文件替身之上注入写入期故障的 [TaskFileStore]。
 *
 * - [failAppendAfterBytes]：首次追加写入该字节数后抛出 IOException，模拟读取响应体中途断流。
 * - [failTruncateAfterBytes]：首次截断重写写入该字节数后抛出 IOException，模拟 200 响应读流中断。
 * - [vanishAfterBytes]：首次追加写入该字节数后让条目从存储中消失，模拟写入过程中被系统清理。
 */
private class InterruptingFileStore(private val delegate: FakeTaskFileStore) : TaskFileStore {

    var failAppendAfterBytes: Int? = null
    var failTruncateAfterBytes: Int? = null
    var vanishAfterBytes: Int? = null

    /** 被标记为消失的条目：sizeOf 返回 -1，直到 delete 真正清掉它。 */
    private var vanishedId: Long? = null

    override suspend fun createPending(displayName: String, mimeType: String): CreatedFile =
        delegate.createPending(displayName, mimeType)

    override suspend fun openAppend(file: CreatedFile): OutputStream {
        val underlying = delegate.openAppend(file)
        val failAfter = failAppendAfterBytes
        val vanishAfter = vanishAfterBytes
        failAppendAfterBytes = null
        vanishAfterBytes = null
        return interruptible(underlying, failAfter, vanishAfter, file)
    }

    override suspend fun openTruncate(file: CreatedFile): OutputStream {
        val underlying = delegate.openTruncate(file)
        val failAfter = failTruncateAfterBytes
        failTruncateAfterBytes = null
        return interruptible(underlying, failAfter, vanishAfter = null, file)
    }

    /** 在 [failAfter] 字节后断流，或在 [vanishAfter] 字节后让条目消失；未配置时原样返回。 */
    private fun interruptible(
        underlying: OutputStream,
        failAfter: Int?,
        vanishAfter: Int?,
        file: CreatedFile,
    ): OutputStream {
        if (failAfter == null && vanishAfter == null) return underlying

        return object : OutputStream() {
            private var failRemaining = failAfter ?: Int.MAX_VALUE
            private var vanishRemaining = vanishAfter ?: Int.MAX_VALUE

            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

            override fun write(b: ByteArray, offset: Int, length: Int) {
                if (length <= 0) return
                val allowed = minOf(length, failRemaining, vanishRemaining)
                if (allowed > 0) {
                    underlying.write(b, offset, allowed)
                    failRemaining -= allowed
                    vanishRemaining -= allowed
                }
                if (vanishRemaining <= 0) {
                    vanishedId = file.mediaStoreId
                    throw IOException("模拟写入中途条目消失")
                }
                if (failRemaining <= 0) {
                    throw IOException("模拟写入中途断流")
                }
            }

            override fun flush() = underlying.flush()

            override fun close() = underlying.close()
        }
    }

    override suspend fun complete(file: CreatedFile) = delegate.complete(file)

    override suspend fun delete(file: CreatedFile) {
        if (file.mediaStoreId == vanishedId) vanishedId = null
        delegate.delete(file)
    }

    override suspend fun sizeOf(file: CreatedFile): Long =
        if (file.mediaStoreId == vanishedId) -1L else delegate.sizeOf(file)

    override suspend fun md5Of(file: CreatedFile): String = delegate.md5Of(file)

    override suspend fun deleteById(mediaStoreId: Long): Boolean = delegate.deleteById(mediaStoreId)
}

/**
 * 让第一次 [openTruncate] 以普通 [IOException] 失败，模拟条目在 416 之后暂时不可写。
 *
 * 失败原因刻意不包含 ENOSPC：这类瞬时错误必须按条目丢失重建，而不是判成 Storage 终止。
 */
private class FailFirstTruncateFileStore(
    private val delegate: FakeTaskFileStore,
) : TaskFileStore by delegate {

    private var remainingFailures = 1

    override suspend fun openTruncate(file: CreatedFile): OutputStream {
        if (remainingFailures > 0) {
            remainingFailures -= 1
            throw IOException("模拟条目暂时不可写")
        }
        return delegate.openTruncate(file)
    }
}

/**
 * 模拟真实仓库的状态机与换链语义。
 *
 * 换链是否保留进度取决于 MD5（双方可用时）或 ETag 能否证明内容未变；
 * [updateStream] 不承载音频格式，格式变化必须由调用方走其他合法迁移。
 */
private class ResumableTaskRepository(initial: DownloadTask) : TaskRepository {

    var task: DownloadTask = initial
        private set

    val statusHistory = mutableListOf<TaskStatus>()
    val progressUpdates = mutableListOf<Long>()
    val streamUpdates = mutableListOf<ResolvedTrack>()
    val allocatedBases = mutableListOf<BaseFileName>()

    var bytesAtStreamUpdate: Long? = null
        private set
    var attachedBytes: Long? = null
        private set
    var lastFailureKind: FailureKind? = null
        private set

    private fun transitionTo(next: TaskStatus) {
        check(TaskTransitions.isAllowed(task.status, next)) {
            "illegal transition ${task.status} -> $next"
        }
        task = task.copy(status = next)
        statusHistory += next
    }

    override fun observeInProgress(): Flow<List<DownloadTask>> =
        MutableStateFlow<List<DownloadTask>>(emptyList())

    override fun observeCompleted(): Flow<List<DownloadTask>> =
        MutableStateFlow<List<DownloadTask>>(emptyList())

    override suspend fun byId(id: Long): DownloadTask? = task.takeIf { it.id == id }

    override suspend fun markResolving(id: Long) {
        transitionTo(TaskStatus.Resolving)
    }

    override suspend fun completeResolve(id: Long, track: ResolvedTrack) {
        transitionTo(TaskStatus.Waiting)
        task = task.copy(
            songId = track.songId,
            requestedQuality = track.requestedQuality,
            title = track.title,
            artist = track.artist,
            album = track.album,
            artworkUrl = track.artworkUrl,
            actualQuality = track.actualQuality,
            format = track.format,
            sizeBytes = track.sizeBytes,
            md5 = track.md5,
            streamUrl = track.streamUrl,
            etag = track.etag,
        )
    }

    override suspend fun startDownloading(id: Long) {
        transitionTo(TaskStatus.Downloading)
    }

    override suspend fun updateProgress(id: Long, bytesDownloaded: Long) {
        check(task.status == TaskStatus.Downloading) {
            "progress must not be written outside Downloading"
        }
        progressUpdates += bytesDownloaded
        task = task.copy(bytesDownloaded = bytesDownloaded)
    }

    override suspend fun updateStream(id: Long, track: ResolvedTrack) {
        check(
            task.status == TaskStatus.Downloading || task.status == TaskStatus.Resolving,
        ) { "stream updates are only allowed while Downloading or Resolving" }
        val sameContent = when {
            task.md5 != null && track.md5 != null -> task.md5.equals(track.md5, ignoreCase = true)
            task.etag != null && track.etag != null -> task.etag == track.etag
            else -> false
        }
        bytesAtStreamUpdate = task.bytesDownloaded
        streamUpdates += track
        task = task.copy(
            streamUrl = track.streamUrl,
            etag = track.etag,
            sizeBytes = track.sizeBytes,
            md5 = track.md5,
            bytesDownloaded = if (sameContent) task.bytesDownloaded else 0,
            mediaStoreId = if (sameContent) task.mediaStoreId else null,
            fileName = if (sameContent) task.fileName else null,
        )
    }

    override suspend fun attachFile(
        id: Long,
        fileName: String,
        mediaStoreId: Long,
        bytesDownloaded: Long,
    ) {
        check(task.status == TaskStatus.Downloading) {
            "the created file must be attached while Downloading"
        }
        attachedBytes = bytesDownloaded
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
        transitionTo(TaskStatus.Completed)
        task = task.copy(
            fileName = fileName,
            mediaStoreId = mediaStoreId,
            completedAt = completedAt,
        )
    }

    override suspend fun markFailed(id: Long, failureKind: FailureKind, message: String) {
        transitionTo(TaskStatus.Failed)
        lastFailureKind = failureKind
        task = task.copy(failureKind = failureKind, failureMessage = message)
    }

    override suspend fun allocateFileName(id: Long, base: BaseFileName): String {
        allocatedBases += base
        val baseName = base.value
        // 模拟真实仓库：数据库里当前任务自己已持有的文件名同样算作占用，
        // 因此“删旧建新”若没先解除旧绑定，会得到多余的 " (1)" 序号。
        if (task.fileName != baseName) return baseName
        val dot = baseName.lastIndexOf('.')
        val stem = if (dot > 0) baseName.substring(0, dot) else baseName
        val extension = if (dot > 0) baseName.substring(dot) else ""
        var index = 1
        while (true) {
            val candidate = "$stem ($index)$extension"
            if (candidate != task.fileName) return candidate
            index++
        }
    }

    override suspend fun releaseFileName(id: Long) = Unit

    override suspend fun enqueue(text: String, quality: MusicQuality): EnqueueOutcome =
        error("not used by the download worker")

    override suspend fun enqueueLinks(links: List<SongLink>, quality: MusicQuality): EnqueueOutcome =
        error("not used by the download worker")

    override suspend fun markPaused(id: Long) = error("not used by the download worker")

    override suspend fun markCancelled(id: Long) = error("not used by the download worker")

    override suspend fun resetForRetry(id: Long) = error("not used by the download worker")

    override suspend fun pause(id: Long) = error("not used by the download worker")

    override suspend fun resume(id: Long) = error("not used by the download worker")

    override suspend fun retry(id: Long) = error("not used by the download worker")

    override suspend fun deleteRecord(id: Long) = error("not used by the download worker")

    override suspend fun deleteRecords(ids: List<Long>) = error("not used by the download worker")

    override suspend fun clearCompleted() = error("not used by the download worker")

    override suspend fun pauseAll() = error("not used by the download worker")

    override suspend fun resumeAll() = error("not used by the download worker")

    override suspend fun pendingIds(): List<Long> = error("not used by the download worker")

    override suspend fun cancelAll() = error("not used by the download worker")

    override suspend fun retryAllFailed() = error("not used by the download worker")
}

private data class ResolveCall(
    val songId: SongId,
    val requested: MusicQuality,
    val apiKey: String,
)

private class QueueTrackResolver(
    responses: List<ApiResponse<ResolvedTrack>> = emptyList(),
) : TrackResolver {

    private val pending = responses.toMutableList()
    val calls = mutableListOf<ResolveCall>()

    override suspend fun resolve(
        songId: SongId,
        requested: MusicQuality,
        apiKey: String,
    ): ApiResponse<ResolvedTrack> {
        calls += ResolveCall(songId = songId, requested = requested, apiKey = apiKey)
        return pending.removeFirstOrNull()
            ?: error("the worker requested an unexpected extra track resolution")
    }
}

private class ResumeFakeSettings : AppSettings {
    private val keyFlow = MutableStateFlow<String?>("test-key")

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

private class ResumeFixedClock : AppClock {
    override fun nowMillis(): Long = 10_000L
    override fun elapsedRealtime(): Long = 0L
}
