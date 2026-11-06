package com.dawnmoon.music_download

import android.content.Context
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

    private val okHttpClient: OkHttpClient by lazy { OkHttpClient() }

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

    override val api: ChkszApi by lazy { DefaultChkszApi(okHttpClient, json = json) }

    override val rateLimiter: RateLimiter by lazy {
        RateLimiter(clock = clock::elapsedRealtime)
    }

    override val apiCaller: ApiCaller by lazy { ApiCaller(rateLimiter, quotaStore) }

    override val keyValidator: KeyValidator by lazy { DefaultKeyValidator(api, apiCaller) }

    override val qualityResolver: TrackResolver by lazy {
        DefaultQualityResolver(api, apiCaller)
    }

    override val linkResolver: LinkResolver by lazy { HttpShortLinkResolver(okHttpClient) }

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
