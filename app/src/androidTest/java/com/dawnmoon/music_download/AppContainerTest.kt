package com.dawnmoon.music_download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dawnmoon.music_download.data.remote.ApiCaller
import com.dawnmoon.music_download.data.remote.ChkszApi
import com.dawnmoon.music_download.data.remote.LinkResolver
import com.dawnmoon.music_download.data.remote.RateLimiter
import com.dawnmoon.music_download.data.repository.AppSettings
import com.dawnmoon.music_download.data.repository.KeyValidator
import com.dawnmoon.music_download.data.repository.QuotaStore
import com.dawnmoon.music_download.data.repository.TaskRepository
import com.dawnmoon.music_download.domain.TrackResolver
import com.dawnmoon.music_download.download.DownloadController
import com.dawnmoon.music_download.download.TaskFileStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

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
        // UI 与前台服务都经由 AppDependencies.downloadController 读取引擎；
        // 若每次访问都新建 DownloadEngine，界面操作与服务执行会落在不同引擎上。
        assertSame(container.downloadController, container.downloadController)

        // Stage 3 通过 AppDependencies 读取文件存储来执行 deleteById；
        // 它必须与 worker/引擎使用同一个实例，否则界面删除会作用在另一份存储状态上。
        assertSame(container.fileStore, container.fileStore)
        val dependencies: AppDependencies = container
        assertSame(container.fileStore, dependencies.fileStore)
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
    override val fileStore: TaskFileStore get() = unsupported()
    override val downloadController: DownloadController get() = unsupported()

    private fun unsupported(): Nothing = error("测试替身不提供真实依赖")
}
