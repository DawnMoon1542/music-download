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

/**
 * 应用依赖的只读契约。
 *
 * 生产实现为 [AppContainer]；Stage 2/3 的测试与界面层只依赖本接口，
 * 因此可以用 Fake 替换全部真实实现。本 Stage 尚无 fileStore，后续 Stage 再扩展。
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
}
