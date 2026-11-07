package com.dawnmoon.music_download.domain

import com.dawnmoon.music_download.data.remote.ApiResponse
import com.dawnmoon.music_download.domain.model.ApiCredentials
import com.dawnmoon.music_download.domain.model.MusicQuality
import com.dawnmoon.music_download.domain.model.ResolvedTrack
import com.dawnmoon.music_download.domain.model.SongId

/** 按请求音质解析歌曲；实现负责在音质不可用时自动降级。 */
interface TrackResolver {

    /**
     * 解析 [songId] 的下载信息。
     *
     * 返回成功时 [ResolvedTrack.actualQuality] 可能低于 [requested]（服务端静默降级或客户端逐级降级）。
     */
    suspend fun resolve(
        songId: SongId,
        requested: MusicQuality,
        credentials: ApiCredentials,
    ): ApiResponse<ResolvedTrack>
}
