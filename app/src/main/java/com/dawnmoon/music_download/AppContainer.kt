package com.dawnmoon.music_download

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.dawnmoon.music_download.core.AppClock
import com.dawnmoon.music_download.core.SystemAppClock
import com.dawnmoon.music_download.data.local.DownloadDatabase
import com.dawnmoon.music_download.data.remote.ApiCaller
import com.dawnmoon.music_download.data.remote.ChkszApi
import com.dawnmoon.music_download.data.remote.DefaultChkszApi
import com.dawnmoon.music_download.data.remote.HttpShortLinkResolver
import com.dawnmoon.music_download.data.remote.LinkResolver
import com.dawnmoon.music_download.data.remote.RateLimiter
import com.dawnmoon.music_download.data.repository.AppSettings
import com.dawnmoon.music_download.data.repository.DataStoreSettings
import com.dawnmoon.music_download.data.repository.DefaultKeyValidator
import com.dawnmoon.music_download.data.repository.DefaultTaskRepository
import com.dawnmoon.music_download.data.repository.KeyValidator
import com.dawnmoon.music_download.data.repository.QuotaStore
import com.dawnmoon.music_download.data.repository.RoomQuotaStore
import com.dawnmoon.music_download.data.repository.TaskRepository
import com.dawnmoon.music_download.domain.DefaultQualityResolver
import com.dawnmoon.music_download.domain.TrackResolver
import com.dawnmoon.music_download.download.AndroidWakeLockController
import com.dawnmoon.music_download.download.DefaultDownloadWorker
import com.dawnmoon.music_download.download.DownloadController
import com.dawnmoon.music_download.download.DownloadEngine
import com.dawnmoon.music_download.download.DownloadNotifications
import com.dawnmoon.music_download.download.DownloadService
import com.dawnmoon.music_download.download.DownloadServiceStarter
import com.dawnmoon.music_download.download.FileNamer
import com.dawnmoon.music_download.download.MediaStoreTaskFileStore
import com.dawnmoon.music_download.download.ServiceStopper
import com.dawnmoon.music_download.download.TaskDownloader
import com.dawnmoon.music_download.download.TaskFileStore
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/**
 * 进程级手动依赖容器。
 *
 * 所有依赖懒加载并在容器内复用：OkHttp、Json、数据库、DAO 与限速器都不允许出现多个实例。
 * DataStore 通过顶层委托缓存，因此即使创建了多个 [AppContainer]（仪器测试直接构造容器），
 * 同一进程内也只会有一个活动的 DataStore，避免 DataStore 对同一文件的单实例约束被打破。
 */
class AppContainer(context: Context) : AppDependencies {

    private val appContext: Context = context.applicationContext

    private val clock: AppClock by lazy { SystemAppClock() }

    /**
     * 短链解析与 API 调用共享的连接池配置；不设整体超时，由调用场景自行限定。
     *
     * 无参 [OkHttpClient] 的默认连接/读/写均为 10s，慢网络下会早于设计预算失败。
     */
    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** 供 [api] 使用：在共享客户端上追加 60s 整体超时预算；不复用于下载等大文件场景。 */
    @VisibleForTesting
    internal val apiHttpClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val json: Json by lazy { Json { ignoreUnknownKeys = true } }

    private val database: DownloadDatabase by lazy {
        Room.databaseBuilder(appContext, DownloadDatabase::class.java, DATABASE_NAME).build()
    }

    private val dataStore: DataStore<Preferences> by lazy { appContext.containerDataStore }

    override val repository: TaskRepository by lazy {
        DefaultTaskRepository(database.downloadTaskDao(), settings, linkResolver, clock)
    }

    override val settings: AppSettings by lazy { DataStoreSettings(dataStore) }

    override val quotaStore: QuotaStore by lazy { RoomQuotaStore(database.quotaDao(), clock) }

    override val api: ChkszApi by lazy { DefaultChkszApi(apiHttpClient, json = json) }

    override val rateLimiter: RateLimiter by lazy {
        RateLimiter(clock = clock::elapsedRealtime)
    }

    override val apiCaller: ApiCaller by lazy { ApiCaller(rateLimiter, quotaStore) }

    override val keyValidator: KeyValidator by lazy { DefaultKeyValidator(api, apiCaller) }

    override val qualityResolver: TrackResolver by lazy {
        DefaultQualityResolver(api, apiCaller)
    }

    override val linkResolver: LinkResolver by lazy { HttpShortLinkResolver(okHttpClient) }

    /** 下载文件统一写入公共 Music 目录的 pending 条目。 */
    private val fileStore: TaskFileStore by lazy { MediaStoreTaskFileStore(appContext) }

    private val downloadNotifications: DownloadNotifications by lazy {
        DownloadNotifications(appContext)
    }

    private val downloadWakeLock: AndroidWakeLockController by lazy {
        AndroidWakeLockController(appContext)
    }

    private val downloadWorker: TaskDownloader by lazy {
        DefaultDownloadWorker(
            repo = repository,
            resolver = qualityResolver,
            settings = settings,
            fileStore = fileStore,
            namer = FileNamer(repository),
            client = okHttpClient,
            clock = clock,
        )
    }

    /**
     * 进程级唯一的下载引擎。
     *
     * 服务通过 [DownloadService.start] 启动自身，空闲时回调 [ServiceStopper] 停止；
     * 两个回调都经由 [DownloadService] 的静态入口，避免容器与 Android 组件循环依赖。
     */
    override val downloadController: DownloadController by lazy {
        DownloadEngine(
            repo = repository,
            resolver = qualityResolver,
            settings = settings,
            fileStore = fileStore,
            downloader = downloadWorker,
            notifications = downloadNotifications,
            wakeLock = downloadWakeLock,
            clock = clock,
            serviceStopper = ServiceStopper { DownloadService.stop(appContext) },
            serviceStarter = DownloadServiceStarter { context -> DownloadService.start(context) },
        )
    }

    /**
     * 把上一个进程遗留的可继续任务统一置为 Paused。
     *
     * 由 Application 在启动时调用；直接走 DAO，避免为恢复动作在仓库接口上开洞。
     */
    internal suspend fun markUnfinishedAsPaused() {
        database.downloadTaskDao().markUnfinishedAsPaused(clock.nowMillis())
    }

    private companion object {
        const val DATABASE_NAME = "music_download.db"
    }
}

/** DataStore 的进程级单例；委托自身按 Context 缓存，避免同一文件出现多个活动实例。 */
private val Context.containerDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
)
