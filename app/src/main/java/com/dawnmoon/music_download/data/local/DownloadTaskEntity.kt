package com.dawnmoon.music_download.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 持久化到 `download_task` 的下载任务。
 *
 * 状态与失败原因以枚举名保存，避免数据库层依赖领域枚举；各下载进度字段在后续阶段写入，
 * 当前任务阶段只保证结构完整，使仓库可以一次性映射领域对象。
 */
@Entity(
    tableName = "download_task",
    indices = [
        Index("songId"),
        Index("status"),
        Index("createdAt"),
        Index("completedAt"),
    ],
)
data class DownloadTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: Long?,
    val sourceUrl: String,
    val requestedQuality: String,
    val actualQuality: String?,
    val title: String?,
    val artist: String?,
    val album: String?,
    val artworkUrl: String?,
    val format: String?,
    val sizeBytes: Long?,
    val md5: String?,
    val streamUrl: String?,
    val etag: String?,
    val status: String,
    val bytesDownloaded: Long,
    val mediaStoreId: Long?,
    val fileName: String?,
    val failureKind: String?,
    val failureMessage: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long?,
)
