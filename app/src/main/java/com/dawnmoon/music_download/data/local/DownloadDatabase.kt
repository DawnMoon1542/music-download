package com.dawnmoon.music_download.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/** 应用本地数据库；version 1 为全新建库，无迁移路径。 */
@Database(
    entities = [DownloadTaskEntity::class, QuotaSnapshotEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class DownloadDatabase : RoomDatabase() {
    abstract fun downloadTaskDao(): DownloadTaskDao

    abstract fun quotaDao(): QuotaDao
}
