package com.dawnmoon.music_download.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dawnmoon.music_download.domain.model.ApiCredentials
import com.dawnmoon.music_download.MusicDownloadApp
import com.dawnmoon.music_download.data.repository.EnqueueOutcome
import com.dawnmoon.music_download.domain.model.AudioFormat
import com.dawnmoon.music_download.domain.model.MusicQuality
import com.dawnmoon.music_download.domain.model.ResolvedTrack
import com.dawnmoon.music_download.domain.model.SongId
import com.dawnmoon.music_download.domain.model.SongLink
import com.dawnmoon.music_download.domain.model.TaskStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

private const val TEST_API_KEY = "chksz_recovery_test_key"
private const val RECOVERY_TIMEOUT_MILLIS = 5_000L
private const val RECOVERY_POLL_INTERVAL_MILLIS = 25L
private const val CLOCK_TICK_GUARD_MILLIS = 20L

/**
 * 进程被杀后的任务恢复契约。
 *
 * 上一个进程遗留的 `Downloading` 任务必须在新进程启动时转为 `Paused`，并刷新 `updatedAt`，
 * 让 UI 能区分“本次启动暂停”与旧时间戳。测试通过重新创建 [MusicDownloadApp] 触发与真实
 * 启动完全相同的 `onCreate` 恢复路径。
 */
@RunWith(AndroidJUnit4::class)
class InterruptedTaskRecoveryTest {

    @Test
    fun applicationStartPausesLeftoverDownloadingTaskAndRefreshesUpdatedAt() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val runningApp = ApplicationProvider.getApplicationContext<MusicDownloadApp>()
        val taskId = seedDownloadingTask(runningApp)
        val beforeUpdatedAt = runningApp.dependencies.repository.byId(taskId)?.updatedAt

        try {
            assertNotNull("预置任务缺少 updatedAt", beforeUpdatedAt)
            // 恢复使用 SystemClock 的墙钟时间；等待一个时钟刻度，避免与预置写入落在同一毫秒。
            delay(CLOCK_TICK_GUARD_MILLIS)

            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val restartedApp = instrumentation.newApplication(
                MusicDownloadApp::class.java.classLoader,
                MusicDownloadApp::class.java.name,
                context,
            ) as MusicDownloadApp
            instrumentation.callApplicationOnCreate(restartedApp)

            val recovered = withTimeoutOrNull(RECOVERY_TIMEOUT_MILLIS) {
                var task = restartedApp.dependencies.repository.byId(taskId)
                while (task?.status != TaskStatus.Paused) {
                    delay(RECOVERY_POLL_INTERVAL_MILLIS)
                    task = restartedApp.dependencies.repository.byId(taskId)
                }
                task
            }

            assertNotNull(
                "启动恢复没有在 ${RECOVERY_TIMEOUT_MILLIS}ms 内暂停任务 $taskId",
                recovered,
            )
            assertEquals(TaskStatus.Paused, recovered?.status)
            val afterUpdatedAt = recovered?.updatedAt
            assertNotNull("恢复后的任务缺少 updatedAt", afterUpdatedAt)
            assertTrue(
                "恢复应刷新 updatedAt：before=$beforeUpdatedAt, after=$afterUpdatedAt",
                afterUpdatedAt!! > beforeUpdatedAt!!,
            )
        } finally {
            runningApp.dependencies.repository.deleteRecord(taskId)
        }
    }

    /**
     * 把任务推进到 `Downloading`，模拟上一个进程执行到一半被杀。
     *
     * 任务带有已解析的直链，与真实下载中的任务结构一致。
     */
    private suspend fun seedDownloadingTask(app: MusicDownloadApp): Long {
        val dependencies = app.dependencies
        dependencies.settings.setApiCredentials(ApiCredentials("https://api.test", TEST_API_KEY))

        val songId = SongId(System.nanoTime())
        val link = SongLink.Direct(
            sourceUrl = "https://music.163.com/song?id=${songId.value}",
            songId = songId,
        )
        val outcome = dependencies.repository.enqueueLinks(listOf(link), MusicQuality.Exhigh)
        val created = (outcome as? EnqueueOutcome.Enqueued)?.created?.singleOrNull()
        assertNotNull("预置任务失败：$outcome", created)
        val id = created!!

        dependencies.repository.markResolving(id)
        dependencies.repository.completeResolve(id, resolvedTrack(songId))
        dependencies.repository.startDownloading(id)
        assertEquals(TaskStatus.Downloading, dependencies.repository.byId(id)?.status)
        return id
    }

    private fun resolvedTrack(songId: SongId): ResolvedTrack = ResolvedTrack(
        songId = songId,
        title = "测试歌曲",
        artist = "测试艺术家",
        album = null,
        artworkUrl = null,
        requestedQuality = MusicQuality.Exhigh,
        actualQuality = MusicQuality.Exhigh,
        format = AudioFormat.Mp3,
        streamUrl = "https://example.com/audio/${songId.value}.mp3",
        sizeBytes = 1_024L,
        md5 = null,
        etag = "etag-${songId.value}",
    )
}
