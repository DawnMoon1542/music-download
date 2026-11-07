package com.dawnmoon.music_download.domain

import com.dawnmoon.music_download.data.remote.ApiCaller
import com.dawnmoon.music_download.data.remote.ApiFailure
import com.dawnmoon.music_download.data.remote.ApiResponse
import com.dawnmoon.music_download.data.remote.ChkszApi
import com.dawnmoon.music_download.domain.model.ApiCredentials
import com.dawnmoon.music_download.domain.model.MusicQuality
import com.dawnmoon.music_download.domain.model.ResolvedTrack
import com.dawnmoon.music_download.domain.model.SongId

/**
 * 逐级降级的音质解析器。
 *
 * 从请求音质起按 [MusicQuality.downgradePath] 向低音质尝试，只有 `QualityUnavailable`
 * 才继续；Key 无效、额度、限流等失败与音质无关，继续尝试只会浪费请求，因此立即返回。
 * 所有请求都经 [ApiCaller]，以获得全局限速、429 重试与额度写入。
 */
class DefaultQualityResolver(
    private val api: ChkszApi,
    private val apiCaller: ApiCaller,
) : TrackResolver {

    override suspend fun resolve(
        songId: SongId,
        requested: MusicQuality,
        credentials: ApiCredentials,
    ): ApiResponse<ResolvedTrack> {
        var lastFailure: ApiResponse.Failure? = null
        for (quality in requested.downgradePath()) {
            val response = apiCaller.call { api.fetchSong(songId, quality, credentials) }
            when (response) {
                is ApiResponse.Success -> return response
                is ApiResponse.Failure -> {
                    if (response.failure !is ApiFailure.QualityUnavailable) return response
                    lastFailure = response
                }
            }
        }
        // downgradePath 至少包含 requested 自身，循环必然执行过，lastFailure 不会为 null。
        return checkNotNull(lastFailure)
    }
}
