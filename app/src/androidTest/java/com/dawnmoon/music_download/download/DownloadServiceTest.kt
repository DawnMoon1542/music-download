package com.dawnmoon.music_download.download

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dawnmoon.music_download.domain.model.ApiCredentials
import com.dawnmoon.music_download.AppDependencies
import com.dawnmoon.music_download.MusicDownloadApp
import com.dawnmoon.music_download.core.SystemAppClock
import com.dawnmoon.music_download.data.repository.EnqueueOutcome
import com.dawnmoon.music_download.domain.model.AudioFormat
import com.dawnmoon.music_download.domain.model.MusicQuality
import com.dawnmoon.music_download.domain.model.ResolvedTrack
import com.dawnmoon.music_download.domain.model.SongId
import com.dawnmoon.music_download.domain.model.SongLink
import com.dawnmoon.music_download.domain.model.TaskStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private const val TEST_API_KEY = "chksz_service_test_key"
private const val DEFAULT_TIMEOUT_MILLIS = 5_000L
private const val IDLE_STOP_TIMEOUT_MILLIS = 8_000L
private const val POLL_INTERVAL_MILLIS = 50L
private const val DOWNLOAD_CHANNEL_ID = "downloads"

/**
 * 前台服务与引擎、通知、WakeLock 的生命周期契约。
 *
 * 断言只针对外部可见结果（服务是否在前台、通知是否存在、WakeLock 是否被持有、
 * 任务状态是否推进），不依赖服务内部的调用顺序。需要断言通知存在的用例在自身开头
 * 显式授予通知权限，避免类级规则污染未授权用例。
 */
@RunWith(AndroidJUnit4::class)
class DownloadServiceTest {

    private lateinit var context: Context
    private lateinit var app: MusicDownloadApp
    private lateinit var originalDependencies: AppDependencies
    private lateinit var activityManager: ActivityManager
    private lateinit var notificationManager: NotificationManager

    private val serviceComponent: ComponentName by lazy {
        ComponentName(context, DownloadService::class.java)
    }

    private val seededTaskIds = mutableListOf<Long>()
    private val openedWakeLocks = mutableListOf<AndroidWakeLockController>()
    private var dependenciesReplaced = false

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        app = ApplicationProvider.getApplicationContext()
        originalDependencies = app.dependencies
        activityManager = context.getSystemService(ActivityManager::class.java)
        notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.cancelAll()
        runBlocking {
            originalDependencies.repository.pendingIds().forEach { id ->
                originalDependencies.repository.deleteRecord(id)
            }
        }
    }

    @After
    fun tearDown() {
        stopService()
        waitUntil(3_000) { runningServiceInfo() == null }
        if (dependenciesReplaced) {
            app.dependencies = originalDependencies
            dependenciesReplaced = false
        }
        openedWakeLocks.forEach { it.releaseAll() }
        openedWakeLocks.clear()
        runBlocking {
            seededTaskIds.forEach { originalDependencies.repository.deleteRecord(it) }
        }
        seededTaskIds.clear()
        notificationManager.cancelAll()
    }

    @Test
    fun startingServiceTwiceKeepsForegroundAndExactlyOneNotification() {
        grantNotificationPermission()
        runBlocking { seedDownloadingTask() }

        startService()

        assertTrue("服务应在启动后进入前台", waitUntil { isForeground() })
        assertTrue(
            "前台服务应发布 downloads 通知",
            waitUntil { downloadNotifications().isNotEmpty() },
        )
        // 给引擎 attach 后的通知刷新留出时间：若服务用独立 ID 启动前台通知、
        // 引擎再用自己的 ID 更新进度，这里会观察到两条通知。
        SystemClock.sleep(500)
        assertEquals(
            "启动后应只有一条汇总通知",
            1,
            downloadNotifications().size,
        )

        // 第二次启动走同一条 onStartCommand 路径，不应再产生服务实例或通知。
        startService()
        assertTrue("重复启动后服务应仍在前台", waitUntil { isForeground() })
        SystemClock.sleep(500)
        assertEquals(
            "重复启动后应仍只有一条汇总通知",
            1,
            downloadNotifications().size,
        )

        stopService()

        assertTrue("停止服务后应退出前台", waitUntil { !isForeground() })
        assertTrue(
            "停止服务后应移除下载通知",
            waitUntil { downloadNotifications().isEmpty() },
        )
    }

    @Test
    fun startingServiceAttachesEngineSoActiveTasksAreTracked() {
        val harness = installEngineBackedDependencies()
        runBlocking { seedDownloadingTask() }

        startService()
        try {
            assertTrue(
                "服务启动后引擎应 attach 并聚合活动任务",
                waitUntil { harness.engine.snapshot.value.activeCount == 1 },
            )
        } finally {
            stopService()
        }
    }

    @Test
    fun stoppingServiceDetachesEngineAndReleasesWakeLock() {
        val harness = installEngineBackedDependencies()
        runBlocking { seedDownloadingTask() }

        startService()

        assertTrue(
            "引擎 attach 后活动任务应持有 WakeLock",
            waitUntil { harness.wakeLock.isHeld },
        )

        stopService()

        assertTrue(
            "服务销毁后引擎 detach 应释放 WakeLock",
            waitUntil { !harness.wakeLock.isHeld },
        )
    }

    @Test
    fun pauseAndResumeActionsReachTheEngine() {
        val harness = installEngineBackedDependencies()
        val taskId = runBlocking { seedDownloadingTask() }

        startService()
        try {
            assertTrue(
                "引擎 attach 后活动任务应被聚合",
                waitUntil { harness.engine.snapshot.value.activeCount == 1 },
            )

            sendServiceAction(DownloadServiceActions.ACTION_PAUSE_ALL)

            assertTrue(
                "暂停 action 应把活动任务置为暂停",
                waitUntil {
                    runBlocking { originalDependencies.repository.byId(taskId)?.status } == TaskStatus.Paused
                },
            )

            sendServiceAction(DownloadServiceActions.ACTION_RESUME_ALL)

            assertTrue(
                "继续 action 应把已解析的暂停任务重新排队",
                waitUntil {
                    runBlocking { originalDependencies.repository.byId(taskId)?.status } == TaskStatus.Waiting
                },
            )
        } finally {
            stopService()
        }
    }

    @Test
    fun controlOperationOnDetachedEngineStartsForegroundServiceAndAttachesEngine() {
        val harness = installEngineBackedDependencies()
        runBlocking { seedDownloadingTask() }

        // 服务当前未运行，引擎处于 detach 状态；控制操作必须自行拉起前台服务。
        harness.engine.detach()
        harness.engine.resumeAll()

        assertTrue(
            "引擎未 attach 时，控制操作应拉起前台服务",
            waitUntil { isForeground() },
        )
        assertTrue(
            "前台服务启动后应重新 attach 引擎并聚合活动任务",
            waitUntil { harness.engine.snapshot.value.activeCount == 1 },
        )
    }

    @Test
    fun serviceStopsItselfWhenNoWorkRemains() {
        startService()

        assertTrue("服务应进入前台", waitUntil { isForeground() })
        assertTrue(
            "没有任务时服务应在空闲超时后自行停止",
            waitUntil(IDLE_STOP_TIMEOUT_MILLIS) { !isForeground() },
        )
    }

    @Test
    fun serviceAttachesAnyDownloadControllerImplementation() {
        // 服务只能依赖 DownloadController 契约，不能向下强转具体引擎；
        // 这里用纯 Fake 替换容器，若服务仍要求 DownloadEngine，onCreate 会直接失败。
        val controller = RecordingDownloadController()
        app.dependencies = ReplacedDependencies(originalDependencies, controller)
        dependenciesReplaced = true

        startService()

        assertTrue(
            "服务应通过 DownloadController.attach 启动引擎",
            waitUntil { controller.attached },
        )

        stopService()

        assertTrue(
            "服务销毁应通过 DownloadController.detach 收尾",
            waitUntil { controller.detached },
        )
    }

    /**
     * 用测试持有的引擎替换容器中的控制器。
     *
     * 服务通过 [AppDependencies.downloadController] 获取引擎；这里提供真实
     * [DownloadEngine]，其仓库、设置与解析器仍来自真实容器，下载器替换为立即返回
     * Paused 的实现，避免测试触发网络请求。
     */
    private fun installEngineBackedDependencies(): ServiceEngineHarness {
        val wakeLock = AndroidWakeLockController(context)
        openedWakeLocks += wakeLock
        val engine = DownloadEngine(
            repo = originalDependencies.repository,
            resolver = originalDependencies.qualityResolver,
            settings = originalDependencies.settings,
            fileStore = MediaStoreTaskFileStore(context),
            downloader = PausedTaskDownloader,
            notifications = DownloadNotifications(context),
            wakeLock = wakeLock,
            clock = SystemAppClock(),
            serviceStopper = ServiceStopper { },
            // 控制操作通过该启动器拉起真实前台服务，与生产装配一致。
            serviceStarter = DownloadServiceStarter { DownloadService.start(context) },
        )
        app.dependencies = ReplacedDependencies(originalDependencies, engine)
        dependenciesReplaced = true
        return ServiceEngineHarness(engine, wakeLock)
    }

    private fun startService() {
        ContextCompat.startForegroundService(
            context,
            Intent(context, DownloadService::class.java),
        )
    }

    /**
     * 仅为需要断言通知存在的用例授予权限。
     *
     * 类级规则会先于所有方法执行并污染未授权用例，因此改为按用例显式授予。
     */
    private fun grantNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    }

    private fun stopService() {
        context.stopService(Intent(context, DownloadService::class.java))
    }

    private fun sendServiceAction(action: String) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, DownloadService::class.java).setAction(action),
        )
    }

    private fun isForeground(): Boolean = runningServiceInfo()?.foreground == true

    @Suppress("DEPRECATION")
    private fun runningServiceInfo(): ActivityManager.RunningServiceInfo? =
        activityManager.getRunningServices(Int.MAX_VALUE)
            .firstOrNull { it.service == serviceComponent }

    private fun downloadNotifications() = notificationManager.activeNotifications
        .filter {
            it.packageName == context.packageName &&
                it.notification.channelId == DOWNLOAD_CHANNEL_ID
        }

    private fun waitUntil(
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return true
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        }
        return condition()
    }

    /**
     * 通过真实仓库把任务推进到 Downloading，模拟引擎启动时正在进行的任务。
     *
     * 任务带有已解析的直链，因此暂停后继续会回到 Waiting 而不是 Queued。
     */
    private suspend fun seedDownloadingTask(): Long {
        val songId = SongId(System.nanoTime())
        val link = SongLink.Direct(
            sourceUrl = "https://music.163.com/song?id=${songId.value}",
            songId = songId,
        )
        originalDependencies.settings.setApiCredentials(ApiCredentials("https://api.test", TEST_API_KEY))
        val outcome = originalDependencies.repository.enqueueLinks(
            listOf(link),
            MusicQuality.Exhigh,
        )
        val created = (outcome as? EnqueueOutcome.Enqueued)?.created?.singleOrNull()
        assertNotNull("预置任务失败：$outcome", created)
        val id = created!!

        originalDependencies.repository.markResolving(id)
        originalDependencies.repository.completeResolve(id, resolvedTrack(songId))
        originalDependencies.repository.startDownloading(id)
        assertEquals(
            TaskStatus.Downloading,
            originalDependencies.repository.byId(id)?.status,
        )
        seededTaskIds += id
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

/** 测试装配出的引擎及其 WakeLock，供生命周期断言读取。 */
private class ServiceEngineHarness(
    val engine: DownloadEngine,
    val wakeLock: AndroidWakeLockController,
)

/** 把除控制器外的依赖委托给真实容器，只替换服务将要 attach 的引擎。 */
private class ReplacedDependencies(
    private val delegate: AppDependencies,
    override val downloadController: DownloadController,
) : AppDependencies by delegate

/** 立即返回 Paused，避免服务集成测试触发真实网络下载。 */
private object PausedTaskDownloader : TaskDownloader {
    override suspend fun execute(taskId: Long): WorkerResult = WorkerResult.Paused
}

/**
 * 只记录生命周期调用的控制器，证明服务不依赖 [DownloadEngine] 的具体实现。
 *
 * 控制方法保持空实现：本用例只关心服务是否通过接口调用 `attach` / `detach`。
 */
private class RecordingDownloadController : DownloadController {

    @Volatile
    var attached = false

    @Volatile
    var detached = false

    override val snapshot = MutableStateFlow(
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

    override suspend fun attach(scope: CoroutineScope) {
        attached = true
    }

    override fun detach() {
        detached = true
    }

    override fun start(context: Context) = Unit

    override fun requestDownload(ids: List<Long>) = Unit

    override fun pause(id: Long) = Unit

    override fun resume(id: Long) = Unit

    override fun cancel(id: Long) = Unit

    override fun retry(id: Long) = Unit

    override fun pauseAll() = Unit

    override fun resumeAll() = Unit

    override fun cancelAll() = Unit

    override fun retryAllFailed() = Unit
}

/**
 * 未授予通知权限时的服务行为：仍进入前台、不崩溃，且通知不可见。
 *
 * 类名在扫描顺序上排在 [DownloadNotificationsTest] 与 [DownloadServiceTest] 之前。后两者会
 * 授予 `POST_NOTIFICATIONS`，而运行时权限一旦授予就无法在进程内撤销（`pm revoke` 会杀死
 * 承载仪器测试的进程），因此只有让本类先于所有授权用例执行，才能在全新安装的设备上观察到
 * 真实的未授权状态。设备已授予权限时用 `assumeTrue` 跳过并提示独立运行，避免误报。
 */
@RunWith(AndroidJUnit4::class)
class DownloadDeniedPermissionTest {

    private lateinit var context: Context
    private lateinit var activityManager: ActivityManager
    private lateinit var notificationManager: NotificationManager

    private val serviceComponent: ComponentName by lazy {
        ComponentName(context, DownloadService::class.java)
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        activityManager = context.getSystemService(ActivityManager::class.java)
        notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.cancelAll()
    }

    @After
    fun tearDown() {
        context.stopService(Intent(context, DownloadService::class.java))
        waitUntil(3_000) { runningServiceInfo() == null }
        // 前台通知的移除是异步的；若残留到下一个测试类，会与同一 ID/channel 的用例互相污染。
        notificationManager.cancelAll()
        waitUntil(3_000) { downloadNotifications().isEmpty() }
    }

    @Test
    fun serviceStaysForegroundAndPostsNoNotificationWithoutPermission() {
        // 全新安装时 POST_NOTIFICATIONS 默认未授予；若设备此前已授予则无法在进程内撤销。
        assumeTrue(
            "通知权限已授予且无法在进程内撤销；请在全新安装的设备上运行",
            !hasNotificationPermission(),
        )

        ContextCompat.startForegroundService(
            context,
            Intent(context, DownloadService::class.java),
        )

        assertTrue(
            "未授予通知权限时服务仍应进入前台且不崩溃",
            waitUntil { isForeground() },
        )
        SystemClock.sleep(500)
        assertTrue("未授予通知权限不应导致服务退出", isForeground())
        assertTrue(
            "未授予通知权限时通知不可见",
            downloadNotifications().isEmpty(),
        )
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun isForeground(): Boolean = runningServiceInfo()?.foreground == true

    @Suppress("DEPRECATION")
    private fun runningServiceInfo(): ActivityManager.RunningServiceInfo? =
        activityManager.getRunningServices(Int.MAX_VALUE)
            .firstOrNull { it.service == serviceComponent }

    private fun downloadNotifications() = notificationManager.activeNotifications
        .filter {
            it.packageName == context.packageName &&
                it.notification.channelId == DOWNLOAD_CHANNEL_ID
        }

    private fun waitUntil(
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return true
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        }
        return condition()
    }
}
