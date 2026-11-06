package com.dawnmoon.music_download.domain.model

/** 网易云音乐的数值歌曲标识，短链解析后从跳转地址的 id 参数取得。 */
@JvmInline
value class SongId(val value: Long)

/** 指向单首网易云歌曲的链接；短链需先解析，直链已包含歌曲 ID。 */
sealed interface SongLink {
    val sourceUrl: String

    data class ShortLink(override val sourceUrl: String, val code: String) : SongLink

    data class Direct(override val sourceUrl: String, val songId: SongId) : SongLink
}

/**
 * 任务状态。
 *
 * [isActive] 只覆盖调度器会主动推进的状态；暂停与失败需要用户操作才能恢复。
 * [isTerminal] 只覆盖不再变化的状态，其中只有完成的任务进入历史。
 */
enum class TaskStatus {
    Queued,
    Resolving,
    Waiting,
    Downloading,
    Paused,
    Completed,
    Failed,
    Cancelled;

    val isActive: Boolean
        get() = this == Queued || this == Resolving || this == Waiting || this == Downloading

    val isTerminal: Boolean
        get() = this == Completed || this == Cancelled
}

/** 响应头中的剩余额度；字段缺失时保留 null，由存储层沿用上一次的值。 */
data class Quota(
    val freeRemaining: Int?,
    val paidRemaining: Int?,
    val limitPerMinute: Int?,
)

/** 解析得到的可下载曲目；[actualQuality] 可能低于请求音质，因服务端会静默降级。 */
data class ResolvedTrack(
    val songId: SongId,
    val title: String,
    val artist: String,
    val album: String?,
    val artworkUrl: String?,
    val requestedQuality: MusicQuality,
    val actualQuality: MusicQuality,
    val format: AudioFormat,
    val streamUrl: String,
    val sizeBytes: Long?,
    val md5: String?,
)

/** 文件名主名，不含目录与重名序号；重名序号由下载层追加。 */
@JvmInline
value class BaseFileName(val value: String)

/** 任务失败原因；入队阶段的链接不支持不属于任务状态，因此不在此枚举中。 */
enum class FailureKind {
    Network,
    InvalidKey,
    QuotaExhausted,
    Forbidden,
    RateLimited,
    InvalidRequest,
    QualityUnavailable,
    NotFound,
    ServiceUnavailable,
    Malformed,
    StreamExpired,
    Integrity,
    Storage,
    Unknown,
}

/** 仓库对外暴露的任务领域对象，由数据库实体映射。 */
data class DownloadTask(
    val id: Long,
    val songId: SongId?,
    val sourceUrl: String,
    val requestedQuality: MusicQuality,
    val actualQuality: MusicQuality?,
    val title: String?,
    val artist: String?,
    val album: String?,
    val artworkUrl: String?,
    val format: AudioFormat?,
    val sizeBytes: Long?,
    val md5: String?,
    val streamUrl: String?,
    val etag: String?,
    val bytesDownloaded: Long,
    val status: TaskStatus,
    val mediaStoreId: Long?,
    val fileName: String?,
    val failureKind: FailureKind?,
    val failureMessage: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long?,
)
