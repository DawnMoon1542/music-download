package com.dawnmoon.music_download

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

/**
 * 应用依赖的只读契约。
 *
 * 生产实现为 [AppContainer]；Stage 2/3 的测试与界面层只依赖本接口，
 * 因此可以用 Fake 替换全部真实实现。
 */
interface AppDependencies {
    val repository: TaskRepository
    val settings: AppSettings
    val quotaStore: QuotaStore
    val api: ChkszApi
    val rateLimiter: RateLimiter
    val apiCaller: ApiCaller
    val keyValidator: KeyValidator
    val qualityResolver: TrackResolver
    val linkResolver: LinkResolver

    /**
     * 文件存储入口；Stage 3 通过它执行 [TaskFileStore.deleteById]。
     *
     * 必须与 worker/引擎使用同一实例，否则界面侧的删除会作用在另一份存储状态上。
     */
    val fileStore: TaskFileStore

    /** 下载引擎；UI 与前台服务共享同一实例，避免操作落在不同引擎上。 */
    val downloadController: DownloadController
}
