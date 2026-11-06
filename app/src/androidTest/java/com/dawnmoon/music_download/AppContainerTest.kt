package com.dawnmoon.music_download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dawnmoon.music_download.data.remote.ApiCaller
import com.dawnmoon.music_download.data.remote.ChkszApi
import com.dawnmoon.music_download.data.remote.LinkResolver
import com.dawnmoon.music_download.data.remote.RateLimiter
import com.dawnmoon.music_download.data.repository.AppSettings
import com.dawnmoon.music_download.data.repository.EnqueueOutcome
import com.dawnmoon.music_download.data.repository.KeyValidator
import com.dawnmoon.music_download.data.repository.QuotaStore
import com.dawnmoon.music_download.data.repository.TaskRepository
import com.dawnmoon.music_download.domain.TrackResolver
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
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

private const val TEST_API_KEY = "chksz_test_key"
private const val RECOVERY_TIMEOUT_MILLIS = 5_000L
private const val RECOVERY_POLL_INTERVAL_MILLIS = 25L

@RunWith(AndroidJUnit4::class)
class AppContainerTest {

    @Test
    fun containerExposesSameDependencyInstancesAcrossRepeatedReads() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val container = AppContainer(context)

        // 数据库、DataStore、OkHttp 客户端与限速器都必须复用同一个实例：
        // 重复创建 DataStore 会让同一文件出现多个活动实例并直接抛错。
        assertSame(container.repository, container.repository)
        assertSame(container.settings, container.settings)
        assertSame(container.quotaStore, container.quotaStore)
        assertSame(container.apiCaller, container.apiCaller)
        assertSame(container.qualityResolver, container.qualityResolver)
        assertSame(container.apiHttpClient, container.apiHttpClient)
    }

    @Test
    fun apiClientUsesConfiguredSocketTimeoutsForSlowNetworks() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val container = AppContainer(context)

        val client = container.apiHttpClient

        assertEquals("连接超时应为 15s", 15_000, client.connectTimeoutMillis)
        assertEquals("读超时应为 30s", 30_000, client.readTimeoutMillis)
        assertEquals("写超时应为 30s", 30_000, client.writeTimeoutMillis)
    }

    @Test
    fun apiClientLimitsWholeCallToSixtySeconds() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val container = AppContainer(context)

        assertEquals(60_000, container.apiHttpClient.callTimeoutMillis)
    }

    @Test
    fun appDependenciesContractCanBeImplementedByTestDoubles() {
        // 编译期契约：Stage 2/3 的测试可以只用 Fake 实现全部依赖，不依赖 AppContainer。
        val dependencies: AppDependencies = FakeAppDependencies()

        assertNotNull(dependencies)
    }

    @Test
    fun applicationStartPausesTasksLeftDownloadingByPreviousProcess() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val runningApp = ApplicationProvider.getApplicationContext<MusicDownloadApp>()
        val taskId = seedDownloadingTask(runningApp)

        try {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val restartedApp = instrumentation.newApplication(
                MusicDownloadApp::class.java.classLoader,
                MusicDownloadApp::class.java.name,
                context,
            ) as MusicDownloadApp
            instrumentation.callApplicationOnCreate(restartedApp)

            val paused = withTimeoutOrNull(RECOVERY_TIMEOUT_MILLIS) {
                var task = restartedApp.dependencies.repository.byId(taskId)
                while (task?.status != TaskStatus.Paused) {
                    delay(RECOVERY_POLL_INTERVAL_MILLIS)
                    task = restartedApp.dependencies.repository.byId(taskId)
                }
                task
            }

            assertNotNull(
                "启动恢复没有在 ${RECOVERY_TIMEOUT_MILLIS}ms 内暂停任务 $taskId",
                paused,
            )
            assertEquals(TaskStatus.Paused, paused?.status)
        } finally {
            runningApp.dependencies.repository.deleteRecord(taskId)
        }
    }

    /**
     * 通过真实仓库把任务推进到 Downloading，模拟上一个进程遗留的未完成任务。
     *
     * 使用当前进程的 Application 容器写入同一个数据库文件；随后新建的 MusicDownloadApp
     * 会在 onCreate 中打开该数据库并执行启动恢复。
     */
    private suspend fun seedDownloadingTask(app: MusicDownloadApp): Long {
        val dependencies = app.dependencies
        dependencies.settings.setApiKey(TEST_API_KEY)

        val songId = SongId(System.currentTimeMillis())
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

/**
 * 只用于验证 [AppDependencies] 可由测试替身实现；成员在读取时立即失败，
 * 避免测试意外依赖真实装配。
 */
private class FakeAppDependencies : AppDependencies {
    override val repository: TaskRepository get() = unsupported()
    override val settings: AppSettings get() = unsupported()
    override val quotaStore: QuotaStore get() = unsupported()
    override val api: ChkszApi get() = unsupported()
    override val rateLimiter: RateLimiter get() = unsupported()
    override val apiCaller: ApiCaller get() = unsupported()
    override val keyValidator: KeyValidator get() = unsupported()
    override val qualityResolver: TrackResolver get() = unsupported()
    override val linkResolver: LinkResolver get() = unsupported()

    private fun unsupported(): Nothing = error("测试替身不提供真实依赖")
}
