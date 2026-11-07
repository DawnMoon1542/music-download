package com.dawnmoon.music_download.ui

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dawnmoon.music_download.AppDependencies
import com.dawnmoon.music_download.MusicDownloadApp
import com.dawnmoon.music_download.data.repository.AppSettings
import com.dawnmoon.music_download.data.repository.EnqueueOutcome
import com.dawnmoon.music_download.data.repository.TaskRepository
import com.dawnmoon.music_download.domain.LinkExtractor
import com.dawnmoon.music_download.domain.model.DownloadTask
import com.dawnmoon.music_download.domain.model.MusicQuality
import com.dawnmoon.music_download.domain.model.ThemeMode
import com.dawnmoon.music_download.download.DownloadController
import com.dawnmoon.music_download.download.EngineSnapshot
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 系统分享入口的行为：从 `ACTION_SEND` 文本静默入队，并在处理完成后立即结束。
 *
 * 依赖通过 [MusicDownloadApp.dependencies] 替换为 Fake，避免真实网络、数据库写入与系统
 * 通知权限弹窗。`notificationAsked` 预置为 true，使分享入口走已询问过的分支。
 */
@RunWith(AndroidJUnit4::class)
class ShareActivityTest {

    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()

    private lateinit var context: Context
    private lateinit var app: MusicDownloadApp
    private lateinit var originalDependencies: AppDependencies
    private lateinit var repository: RecordingRepository
    private lateinit var engine: RecordingDownloadController

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        app = ApplicationProvider.getApplicationContext()
        originalDependencies = app.dependencies
        repository = RecordingRepository(originalDependencies.repository)
        engine = RecordingDownloadController()
        app.dependencies = ShareTestDependencies(
            delegate = originalDependencies,
            repository = repository,
            settings = FakeSettings(),
            downloadController = engine,
        )
    }

    @After
    fun tearDown() {
        app.dependencies = originalDependencies
    }

    @Test
    fun sharedTextIsEnqueuedWithDefaultQualityAndHandedToTheEngine() {
        val activity = launchShare(SAMPLE_SHARE_TEXT)

        assertTrue("分享入口应在 2 秒内结束", activity.awaitFinished())

        assertEquals("分享文本应交给仓库入队一次", 1, repository.enqueueCalls.size)
        assertEquals(SAMPLE_SHARE_TEXT, repository.enqueueCalls.single().text)
        assertEquals(
            "入队应使用设置中的默认音质",
            MusicQuality.Hires,
            repository.enqueueCalls.single().quality,
        )
        assertEquals("新建任务应交给下载引擎", listOf(1L), engine.requestedIds)
        assertEquals("分享入口应启动下载服务", 1, engine.startCount)
    }

    @Test
    fun textWithoutSongLinkFinishesWithoutEnqueueing() {
        val activity = launchShare("今晚一起听歌吗")

        assertTrue("无链接时分享入口仍应在 2 秒内结束", activity.awaitFinished())

        assertTrue("无链接文本不应调用仓库入队", repository.enqueueCalls.isEmpty())
        assertTrue("无链接文本不应启动下载引擎", engine.requestedIds.isEmpty())
    }

    @Test
    fun missingApiKeyOpensMainActivityFocusedOnTheKeyInput() {
        repository.missingApiKey = true
        val mainMonitor = Instrumentation.ActivityMonitor(IntentFilter(), null, false)
        instrumentation.addMonitor(mainMonitor)
        try {
            val shareActivity = launchShare(SAMPLE_SHARE_TEXT)

            assertTrue("未配置 Key 时分享入口应立即结束", shareActivity.awaitFinished())

            val mainActivity = mainMonitor.waitForActivityWithTimeout(3_000)
            assertNotNull("未配置 Key 时应打开主界面", mainActivity)
            assertTrue("应打开 MainActivity，而不是其他界面", mainActivity is MainActivity)
            assertTrue(
                "主界面应收到 focusKey=true 以聚焦 Key 输入框",
                mainActivity.intent.getBooleanExtra(MainActivity.EXTRA_FOCUS_KEY, false),
            )
            assertTrue("未配置 Key 时不应启动下载", engine.requestedIds.isEmpty())
        } finally {
            instrumentation.removeMonitor(mainMonitor)
            mainMonitor.lastActivity?.let(::finishOnMainThread)
        }
    }

    @Test
    fun twoSongLinksInSharedTextAreBothEnqueued() {
        val activity = launchShare(TWO_LINK_SHARE_TEXT)

        assertTrue("包含两条链接时分享入口应在 2 秒内结束", activity.awaitFinished())

        assertEquals("两条链接应生成两个下载任务", listOf(1L, 2L), engine.requestedIds)
    }

    /**
     * 以系统分享的 Intent 形态启动 [ShareActivity]，返回其 Activity 实例。
     *
     * 不使用 ActivityScenario：分享入口可能在 `onCreate` 中同步结束，而 ActivityScenario
     * 要求 Activity 到达 RESUMED，立即结束的 Activity 会被判为启动失败。
     */
    private fun launchShare(text: String): ShareActivity {
        val filter = IntentFilter(Intent.ACTION_SEND).apply { addDataType("text/plain") }
        val monitor = Instrumentation.ActivityMonitor(filter, null, false)
        instrumentation.addMonitor(monitor)
        try {
            val intent = Intent(context, ShareActivity::class.java).apply {
                action = Intent.ACTION_SEND
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            val activity = monitor.waitForActivityWithTimeout(LAUNCH_TIMEOUT_MILLIS) as? ShareActivity
            assertNotNull("ShareActivity 应被启动", activity)
            return activity!!
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    /** 轮询 Activity 是否已结束；分享入口的 `finish()` 可能发生在任意线程的入队之后。 */
    private fun Activity.awaitFinished(): Boolean {
        val deadline = System.currentTimeMillis() + FINISH_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (isFinishing || isDestroyed) return true
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        return isFinishing || isDestroyed
    }

    private fun finishOnMainThread(activity: Activity) {
        instrumentation.runOnMainSync { activity.finish() }
    }

    /** 日志或界面文案不参与断言；这里只记录调用参数，便于定位失败原因。 */
    private data class EnqueueCall(val text: String, val quality: MusicQuality)

    /**
     * 用真实 [LinkExtractor] 计算链接数量，使任务 ID 与提取结果一致。
     *
     * 这样测试验证的是“分享文本中的链接被交给引擎”这一跨组件行为，而不是单纯的调用次数。
     */
    private class RecordingRepository(
        private val delegate: TaskRepository,
    ) : TaskRepository by delegate {

        private val extractor = LinkExtractor()

        @Volatile
        var missingApiKey: Boolean = false

        val enqueueCalls = CopyOnWriteArrayList<EnqueueCall>()

        override suspend fun enqueue(text: String, quality: MusicQuality): EnqueueOutcome {
            enqueueCalls += EnqueueCall(text, quality)
            val extraction = extractor.extract(text)
            if (extraction.links.isEmpty()) {
                return EnqueueOutcome.NoLinks(ignoredUrlCount = extraction.ignoredUrlCount)
            }
            if (missingApiKey) {
                return EnqueueOutcome.MissingApiKey(extractedCount = extraction.links.size)
            }
            return EnqueueOutcome.Enqueued(
                created = extraction.links.indices.map { index -> (index + 1).toLong() },
                duplicates = extraction.duplicateCount,
                ignoredUrlCount = extraction.ignoredUrlCount,
                failures = emptyList(),
            )
        }

        override fun observeInProgress(): Flow<List<DownloadTask>> = emptyFlow()

        override fun observeCompleted(): Flow<List<DownloadTask>> = emptyFlow()
    }

    private class RecordingDownloadController : DownloadController {

        override val snapshot: StateFlow<EngineSnapshot> = MutableStateFlow(
            EngineSnapshot(
                activeCount = 0,
                completedInSession = 0,
                totalInSession = 0,
                bytesDone = 0,
                bytesTotal = null,
                speedBytesPerSecond = 0,
                isPaused = false,
            ),
        )

        val requestedIds = CopyOnWriteArrayList<Long>()

        @Volatile
        var startCount: Int = 0

        override suspend fun attach(scope: CoroutineScope) = Unit

        override fun detach() = Unit

        override fun start(context: Context) {
            startCount += 1
        }

        override fun requestDownload(ids: List<Long>) {
            requestedIds += ids
        }

        override fun pause(id: Long) = Unit

        override fun resume(id: Long) = Unit

        override fun cancel(id: Long) = Unit

        override fun retry(id: Long) = Unit

        override fun pauseAll() = Unit

        override fun resumeAll() = Unit

        override fun cancelAll() = Unit

        override fun retryAllFailed() = Unit
    }

    private class FakeSettings : AppSettings {

        private val apiKeyFlow = MutableStateFlow<String?>("chksz_test")
        private val defaultQualityFlow = MutableStateFlow(MusicQuality.Hires)
        private val maxConcurrentDownloadsFlow = MutableStateFlow(3)
        private val themeModeFlow = MutableStateFlow(ThemeMode.System)
        private val notificationAskedFlow = MutableStateFlow(true)

        override val apiKey: Flow<String?> = apiKeyFlow
        override val defaultQuality: Flow<MusicQuality> = defaultQualityFlow
        override val maxConcurrentDownloads: Flow<Int> = maxConcurrentDownloadsFlow
        override val themeMode: Flow<ThemeMode> = themeModeFlow
        override val notificationAsked: Flow<Boolean> = notificationAskedFlow

        override suspend fun setApiKey(value: String) {
            apiKeyFlow.value = value
        }

        override suspend fun setDefaultQuality(value: MusicQuality) {
            defaultQualityFlow.value = value
        }

        override suspend fun setMaxConcurrentDownloads(value: Int) {
            maxConcurrentDownloadsFlow.value = value
        }

        override suspend fun setThemeMode(value: ThemeMode) {
            themeModeFlow.value = value
        }

        override suspend fun markNotificationAsked() {
            notificationAskedFlow.value = true
        }
    }

    private class ShareTestDependencies(
        delegate: AppDependencies,
        override val repository: TaskRepository,
        override val settings: AppSettings,
        override val downloadController: DownloadController,
    ) : AppDependencies by delegate

    private companion object {
        const val FINISH_TIMEOUT_MILLIS = 2_000L
        const val LAUNCH_TIMEOUT_MILLIS = 5_000L
        const val POLL_INTERVAL_MILLIS = 25L

        /** 与手动验收命令使用同一条网易云分享文案。 */
        const val SAMPLE_SHARE_TEXT =
            "分享あたらよ的单曲《夏霞》: https://163cn.tv/bkp6m2as (来自@网易云音乐)"

        const val TWO_LINK_SHARE_TEXT =
            "分享两首歌 https://music.163.com/song?id=186016 " +
                "和 https://music.163.com/song?id=186017"
    }
}
