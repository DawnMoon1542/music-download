package com.dawnmoon.music_download.ui

import kotlinx.coroutines.flow.map
import com.dawnmoon.music_download.domain.model.ApiCredentials
import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.util.DisplayMetrics
import app.cash.turbine.test
import com.dawnmoon.music_download.R
import com.dawnmoon.music_download.core.AppClock
import com.dawnmoon.music_download.data.repository.AppSettings
import com.dawnmoon.music_download.data.repository.EnqueueOutcome
import com.dawnmoon.music_download.data.repository.QuotaStore
import com.dawnmoon.music_download.data.repository.TaskRepository
import com.dawnmoon.music_download.domain.model.BaseFileName
import com.dawnmoon.music_download.domain.model.DownloadTask
import com.dawnmoon.music_download.domain.model.FailureKind
import com.dawnmoon.music_download.domain.model.MusicQuality
import com.dawnmoon.music_download.domain.model.Quota
import com.dawnmoon.music_download.domain.model.ResolvedTrack
import com.dawnmoon.music_download.domain.model.SongLink
import com.dawnmoon.music_download.domain.model.ThemeMode
import com.dawnmoon.music_download.download.CreatedFile
import com.dawnmoon.music_download.download.DownloadController
import com.dawnmoon.music_download.download.EngineSnapshot
import com.dawnmoon.music_download.download.TaskFileStore
import com.dawnmoon.music_download.ui.viewmodel.WorkbenchEvent
import com.dawnmoon.music_download.ui.viewmodel.WorkbenchViewModel
import java.io.File
import java.io.OutputStream
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.w3c.dom.Element

private const val TEST_API_BASE_URL = "https://api.test"

/**
 * 下载前通知权限请求的行为：仅在 Android 13+ 且从未询问过时请求一次，
 * 请求不阻塞下载启动，且询问标记在发出事件时立即落库。
 *
 * 这些断言只观察 ViewModel 对外的事件流、引擎副作用与设置状态，不检查内部实现。
 */
class NotificationPermissionTest {

    private val dispatcher = StandardTestDispatcher()

    private lateinit var repo: FakeTaskRepository
    private lateinit var settings: FakeAppSettings
    private lateinit var engine: FakeDownloadController
    private lateinit var fileStore: FakeTaskFileStore
    private lateinit var quotaStore: FakeQuotaStore
    private lateinit var appContext: Context

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repo = FakeTaskRepository()
        settings = FakeAppSettings()
        engine = FakeDownloadController()
        fileStore = FakeTaskFileStore()
        quotaStore = FakeQuotaStore()
        appContext = NotificationTestContext()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `first enqueue starts the download and requests notification permission without waiting`() =
        runTest(dispatcher.scheduler) {
            settings.notificationAskedFlow.value = false
            repo.enqueueOutcome = enqueued(created = listOf(1L))
            val viewModel = createViewModel(sdkInt = 33)
            viewModel.onInputChange("https://music.163.com/song?id=1")
            dispatcher.scheduler.runCurrent()

            viewModel.events.test {
                viewModel.onEnqueue()
                dispatcher.scheduler.runCurrent()

                // 入队完成后同时产生下载提示与一次权限请求；权限请求与提示的先后不影响契约。
                val received = listOf(awaitItem(), awaitItem())
                assertTrue(received.contains(WorkbenchEvent.RequestNotificationPermission))
                assertTrue(received.contains(snackbar("已加入 1 个任务")))
                cancelAndIgnoreRemainingEvents()
            }

            // 没有提供任何权限结果，下载仍已启动：权限请求不阻塞下载。
            assertEquals(1, engine.startCount)
            assertEquals(listOf(1L), engine.requestedIds)
            // 标记在发事件时写入，而不是等待用户对权限弹窗的选择。
            assertTrue(settings.notificationAskedFlow.value)
        }

    @Test
    fun `enqueue does not ask again when notification permission was asked before`() =
        runTest(dispatcher.scheduler) {
            settings.notificationAskedFlow.value = true
            repo.enqueueOutcome = enqueued(created = listOf(1L))
            val viewModel = createViewModel(sdkInt = 33)
            viewModel.onInputChange("https://music.163.com/song?id=1")
            dispatcher.scheduler.runCurrent()

            viewModel.events.test {
                viewModel.onEnqueue()
                dispatcher.scheduler.runCurrent()

                assertEquals(snackbar("已加入 1 个任务"), awaitItem())
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `enqueue does not ask for notification permission below Android 13`() =
        runTest(dispatcher.scheduler) {
            settings.notificationAskedFlow.value = false
            repo.enqueueOutcome = enqueued(created = listOf(1L))
            val viewModel = createViewModel(sdkInt = 32)
            viewModel.onInputChange("https://music.163.com/song?id=1")
            dispatcher.scheduler.runCurrent()

            viewModel.events.test {
                viewModel.onEnqueue()
                dispatcher.scheduler.runCurrent()

                assertEquals(snackbar("已加入 1 个任务"), awaitItem())
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    private fun createViewModel(sdkInt: Int): WorkbenchViewModel =
        WorkbenchViewModel(
            repo = repo,
            settings = settings,
            engine = engine,
            fileStore = fileStore,
            quotaStore = quotaStore,
            appContext = appContext,
            clock = FakeClock(),
            sdkInt = sdkInt,
        )

    private fun snackbar(message: String): WorkbenchEvent = WorkbenchEvent.Snackbar(message)

    private fun enqueued(created: List<Long>): EnqueueOutcome =
        EnqueueOutcome.Enqueued(
            created = created,
            duplicates = 0,
            ignoredUrlCount = 0,
            failures = emptyList(),
        )

    private class FakeTaskRepository : TaskRepository {

        val inProgress = MutableStateFlow<List<DownloadTask>>(emptyList())
        val completed = MutableStateFlow<List<DownloadTask>>(emptyList())

        var enqueueOutcome: EnqueueOutcome = EnqueueOutcome.Enqueued(
            created = emptyList(),
            duplicates = 0,
            ignoredUrlCount = 0,
            failures = emptyList(),
        )

        override fun observeInProgress(): Flow<List<DownloadTask>> = inProgress

        override fun observeCompleted(): Flow<List<DownloadTask>> = completed

        override suspend fun byId(id: Long): DownloadTask? =
            (inProgress.value + completed.value).firstOrNull { it.id == id }

        override suspend fun enqueue(text: String, quality: MusicQuality): EnqueueOutcome =
            enqueueOutcome

        override suspend fun enqueueLinks(
            links: List<SongLink>,
            quality: MusicQuality,
        ): EnqueueOutcome = error("unused")

        override suspend fun markResolving(id: Long) = error("unused")

        override suspend fun completeResolve(id: Long, track: ResolvedTrack) = error("unused")

        override suspend fun startDownloading(id: Long) = error("unused")

        override suspend fun updateProgress(id: Long, bytesDownloaded: Long) = error("unused")

        override suspend fun updateStream(id: Long, track: ResolvedTrack) = error("unused")

        override suspend fun markCompleted(
            id: Long,
            fileName: String,
            mediaStoreId: Long,
            completedAt: Long,
        ) = error("unused")

        override suspend fun markFailed(
            id: Long,
            failureKind: FailureKind,
            message: String,
        ) = error("unused")

        override suspend fun markPaused(id: Long) = error("unused")

        override suspend fun markCancelled(id: Long) = error("unused")

        override suspend fun resetForRetry(id: Long) = error("unused")

        override suspend fun attachFile(
            id: Long,
            fileName: String,
            mediaStoreId: Long,
            bytesDownloaded: Long,
        ) = error("unused")

        override suspend fun pause(id: Long) = error("unused")

        override suspend fun resume(id: Long) = error("unused")

        override suspend fun retry(id: Long) = error("unused")

        override suspend fun deleteRecord(id: Long) = error("unused")

        override suspend fun deleteRecords(ids: List<Long>) = error("unused")

        override suspend fun clearCompleted() = error("unused")

        override suspend fun pauseAll() = error("unused")

        override suspend fun resumeAll() = error("unused")

        override suspend fun pendingIds(): List<Long> = error("unused")

        override suspend fun cancelAll() = error("unused")

        override suspend fun retryAllFailed() = error("unused")

        override suspend fun allocateFileName(id: Long, base: BaseFileName): String =
            error("unused")

        override suspend fun releaseFileName(id: Long) = error("unused")
    }

    private class FakeAppSettings : AppSettings {

        val apiKeyFlow = MutableStateFlow<String?>(null)
        val defaultQualityFlow = MutableStateFlow(MusicQuality.Exhigh)
        val maxConcurrentDownloadsFlow = MutableStateFlow(3)
        val themeModeFlow = MutableStateFlow(ThemeMode.System)
        val notificationAskedFlow = MutableStateFlow(false)

        override val apiCredentials: Flow<ApiCredentials?> =
            apiKeyFlow.map { key -> key?.let { ApiCredentials(TEST_API_BASE_URL, it) } }
        override val defaultQuality: Flow<MusicQuality> = defaultQualityFlow
        override val maxConcurrentDownloads: Flow<Int> = maxConcurrentDownloadsFlow
        override val themeMode: Flow<ThemeMode> = themeModeFlow
        override val notificationAsked: Flow<Boolean> = notificationAskedFlow

        override suspend fun setApiCredentials(value: ApiCredentials) = error("unused")

        override suspend fun setDefaultQuality(value: MusicQuality) {
            defaultQualityFlow.value = value
        }

        override suspend fun setMaxConcurrentDownloads(value: Int) = error("unused")

        override suspend fun setThemeMode(value: ThemeMode) = error("unused")

        override suspend fun markNotificationAsked() {
            notificationAskedFlow.value = true
        }
    }

    private class FakeDownloadController : DownloadController {

        override val snapshot: StateFlow<EngineSnapshot> =
            MutableStateFlow(
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

        var startCount: Int = 0
        val requestedIds = mutableListOf<Long>()

        override suspend fun attach(scope: kotlinx.coroutines.CoroutineScope) = Unit

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

    private class FakeTaskFileStore : TaskFileStore {

        override suspend fun createPending(displayName: String, mimeType: String): CreatedFile =
            error("unused")

        override suspend fun openAppend(file: CreatedFile): OutputStream = error("unused")

        override suspend fun openTruncate(file: CreatedFile): OutputStream = error("unused")

        override suspend fun complete(file: CreatedFile) = error("unused")

        override suspend fun delete(file: CreatedFile) = error("unused")

        override suspend fun sizeOf(file: CreatedFile): Long = error("unused")

        override suspend fun md5Of(file: CreatedFile): String = error("unused")

        override suspend fun deleteById(mediaStoreId: Long): Boolean = error("unused")
    }

    private class FakeQuotaStore : QuotaStore {

        override val quota = MutableStateFlow<Quota?>(null)

        override suspend fun record(quota: Quota) = error("unused")
    }

    private class FakeClock(
        var elapsedMillis: Long = 0,
        var nowMillis: Long = 0,
    ) : AppClock {

        override fun nowMillis(): Long = nowMillis

        override fun elapsedRealtime(): Long = elapsedMillis
    }
}

/**
 * JVM 单元测试运行在 mockable android.jar 上：`Context.getString` 是 final 且方法体未实现，
 * 调用会抛 "not mocked"。这里覆写 [getResources]，让 ViewModel 通过
 * `context.resources.getString(...)` 读取 strings.xml 的文案，避免为了测试硬编码中文。
 */
private class NotificationTestContext : ContextWrapper(null) {

    private val localizedResources = NotificationTestResources()

    override fun getApplicationContext(): Context = this

    override fun getResources(): Resources = localizedResources
}

/**
 * 只实现文案查询的 [Resources]。mockable android.jar 中 AssetManager 的构造器是包级私有，
 * 只能反射创建；文案值直接读取 strings.xml，因此后续新增文案键无需改动测试。
 */
@Suppress("DEPRECATION")
private class NotificationTestResources : Resources(
    newNotificationTestAssetManager(),
    DisplayMetrics(),
    Configuration(),
) {

    override fun getString(id: Int): String = valueOf(id)

    override fun getString(id: Int, vararg formatArgs: Any?): String =
        String.format(Locale.ROOT, valueOf(id), *formatArgs)

    override fun getText(id: Int): CharSequence = valueOf(id)

    private fun valueOf(id: Int): String {
        val name = NAME_BY_ID[id] ?: error("Unknown string resource id: $id")
        return STRINGS[name] ?: error("Missing string resource: $name")
    }

    private companion object {

        val NAME_BY_ID: Map<Int, String> = R.string::class.java.declaredFields
            .filter { it.type == Int::class.javaPrimitiveType }
            .associate { it.getInt(null) to it.name }

        val STRINGS: Map<String, String> = readNotificationTestStringsXml()
    }
}

private fun newNotificationTestAssetManager(): AssetManager =
    AssetManager::class.java.getDeclaredConstructor()
        .apply { isAccessible = true }
        .newInstance()

private fun readNotificationTestStringsXml(): Map<String, String> {
    val xml = findNotificationTestStringsXml()
    val nodes = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(xml)
        .getElementsByTagName("string")
    return (0 until nodes.length)
        .map { nodes.item(it) as Element }
        .associate { it.getAttribute("name") to it.textContent.trim() }
}

/** 兼容从仓库根目录或 app 模块目录启动测试两种工作目录。 */
private fun findNotificationTestStringsXml(): File {
    var dir: File? = File("").absoluteFile
    while (dir != null) {
        File(dir, "src/main/res/values/strings.xml").takeIf { it.isFile }?.let { return it }
        File(dir, "app/src/main/res/values/strings.xml").takeIf { it.isFile }?.let { return it }
        dir = dir.parentFile
    }
    error("strings.xml not found from ${File("").absolutePath}")
}
