package com.dawnmoon.music_download.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 最近一次 API 响应中的额度快照。
 *
 * 固定使用单行 [id] = 0：额度是全局覆盖值而非历史序列，仓储层负责在空字段上沿用旧值。
 */
@Entity(tableName = "quota_snapshot")
data class QuotaSnapshotEntity(
    @PrimaryKey val id: Int = 0,
    val freeRemaining: Int?,
    val paidRemaining: Int?,
    val limitPerMinute: Int?,
    val updatedAt: Long,
)
