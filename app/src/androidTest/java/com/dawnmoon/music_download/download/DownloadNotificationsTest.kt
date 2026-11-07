package com.dawnmoon.music_download.download

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadNotificationsTest {

    @get:Rule
    val notificationPermission: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private lateinit var context: Context
    private lateinit var manager: NotificationManager
    private lateinit var notifications: DownloadNotifications

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = context.getSystemService(NotificationManager::class.java)
        manager.deleteNotificationChannel(CHANNEL_ID)
        manager.cancelAll()
        notifications = DownloadNotifications(context)
        notifications.ensureChannel()
    }

    @Test
    fun titleShowsActiveCountAndBodyShowsSessionProgress() {
        val notification: Notification = notifications.build(snapshot(activeCount = 2, completed = 5, total = 12), paused = false)

        val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

        assertNotNull("通知应设置标题", title)
        assertNotNull("通知应设置正文", text)
        assertTrue("标题应包含活动下载数，实际为 $title", title!!.contains("2"))
        assertTrue("正文应包含已完成数与总数，实际为 $text", text!!.contains("5/12"))
    }

    @Test
    fun knownTotalBytesProduceDeterminateProgress() {
        val notification: Notification = notifications.build(snapshot(bytesDone = 3_200_000L, bytesTotal = 6_400_000L), paused = false)
        val extras = notification.extras

        assertFalse(
            "已知总字节时进度不应为不确定态",
            extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false),
        )
        val max = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val progress = extras.getInt(Notification.EXTRA_PROGRESS, -1)
        assertTrue("已知总字节时应设置进度上限，实际为 $max", max > 0)
        assertTrue("进度应处于 0 到上限之间，实际为 $progress/$max", progress in 0..max)
    }

    @Test
    fun unknownTotalBytesProduceIndeterminateProgress() {
        val notification: Notification = notifications.build(snapshot(bytesTotal = null), paused = false)

        assertTrue(
            "未知总字节时进度应为不确定态",
            notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false),
        )
    }

    @Test
    fun pauseActionIsShownWhileDownloadsAreRunning() {
        val notification: Notification = notifications.build(snapshot(paused = false), paused = false)

        val pause = notification.actions.orEmpty().firstOrNull { it.title.toString() == "全部暂停" }
        assertNotNull("运行中应提供全部暂停 action，实际为 ${notification.actions.orEmpty().map { it.title }}", pause)
        assertNotNull("全部暂停 action 应携带 PendingIntent", pause!!.actionIntent)
    }

    @Test
    fun resumeActionIsShownWhileDownloadsArePaused() {
        val notification: Notification = notifications.build(snapshot(paused = true), paused = true)

        val resume = notification.actions.orEmpty().firstOrNull { it.title.toString() == "全部继续" }
        assertNotNull("暂停时应提供全部继续 action，实际为 ${notification.actions.orEmpty().map { it.title }}", resume)
        assertNotNull("全部继续 action 应携带 PendingIntent", resume!!.actionIntent)
    }

    @Test
    fun serviceActionsUseExplicitDownloadServiceComponent() {
        // 服务只声明 exported=false、没有 intent-filter，隐式 action Intent 无法解析；
        // 这里同时校验 PendingIntent 由 getService 创建、底层 Intent 带显式组件。
        listOf(
            notifications.build(snapshot(), paused = false),
            notifications.build(snapshot(), paused = true),
        ).forEach { notification ->
            val action = notification.actions.orEmpty().single()
            assertTrue(
                "action ${action.title} 应由 getService 创建",
                action.actionIntent.isService,
            )
        }

        listOf(
            DownloadServiceActions.ACTION_PAUSE_ALL,
            DownloadServiceActions.ACTION_RESUME_ALL,
        ).forEach { action ->
            val intent = notifications.serviceIntent(action)
            assertEquals(action, intent.action)
            assertEquals(
                "action $action 必须显式指向 DownloadService，避免依赖未声明的 intent-filter",
                "com.dawnmoon.music_download.download.DownloadService",
                intent.component?.className,
            )
            assertEquals(context.packageName, intent.component?.packageName)
        }
    }

    @Test
    fun channelIsLowImportanceAndSilent() {
        val channel = manager.getNotificationChannel(CHANNEL_ID)

        assertNotNull("应创建 downloads 通知渠道", channel)
        assertEquals("下载渠道应使用低重要性", NotificationManager.IMPORTANCE_LOW, channel!!.importance)
        assertNull("下载通知应无声", channel.sound)
    }

    @Test
    fun clearCancelsPostedNotification() {
        notifications.update(snapshot(), paused = false)
        // NotificationManagerService 在内部 handler 上异步处理 notify/cancel，
        // 紧接其后读取 activeNotifications 会与处理顺序竞争，因此轮询等待结果。
        assertTrue("update 应发布一条下载通知", waitUntil { postedDownloadNotifications().size == 1 })

        notifications.clear()

        assertTrue("clear 后不应保留下载通知", waitUntil { postedDownloadNotifications().isEmpty() })
    }

    private fun waitUntil(timeoutMillis: Long = 2_000L, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return true
            SystemClock.sleep(25)
        }
        return condition()
    }

    private fun postedDownloadNotifications(): List<android.service.notification.StatusBarNotification> {
        // 渠道 ID 只在包内唯一；Chrome 等系统应用也使用 `downloads` 渠道，
        // 若只按 channelId 过滤会把其他应用的活动通知算进来。
        return manager.activeNotifications.filter {
            it.packageName == context.packageName && it.notification.channelId == CHANNEL_ID
        }
    }

    private fun snapshot(
        activeCount: Int = 2,
        completed: Int = 5,
        total: Int = 12,
        bytesDone: Long = 3_200_000L,
        bytesTotal: Long? = 6_400_000L,
        paused: Boolean = false,
    ): EngineSnapshot = EngineSnapshot(
        activeCount = activeCount,
        completedInSession = completed,
        totalInSession = total,
        bytesDone = bytesDone,
        bytesTotal = bytesTotal,
        speedBytesPerSecond = 512_000L,
        isPaused = paused,
    )

    private companion object {
        const val CHANNEL_ID = "downloads"
    }
}
