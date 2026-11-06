package com.dawnmoon.music_download.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 额度快照的数据访问对象。
 *
 * 只负责单行读写；空字段沿用上一次值的合并规则由仓储层实现。
 */
@Dao
interface QuotaDao {

    @Query("SELECT * FROM quota_snapshot WHERE id = 0")
    fun observe(): Flow<QuotaSnapshotEntity?>

    @Query("SELECT * FROM quota_snapshot WHERE id = 0")
    suspend fun current(): QuotaSnapshotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(snapshot: QuotaSnapshotEntity)
}
