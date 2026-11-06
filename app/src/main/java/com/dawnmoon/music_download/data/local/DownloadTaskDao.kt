package com.dawnmoon.music_download.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * 下载任务的数据访问对象。
 *
 * 状态列保存 [com.dawnmoon.music_download.domain.model.TaskStatus] 的枚举名，因此 SQL 中的
 * 字面量必须与枚举名保持一致。查询只做投影与排序，状态转换规则仍由领域层校验。
 */
@Dao
interface DownloadTaskDao {

    /** 所有未完成任务，最新创建的在前；Cancelled 仍保留在列表中，Completed 只进入历史。 */
    @Query("SELECT * FROM download_task WHERE status != 'Completed' ORDER BY createdAt DESC")
    fun observeInProgress(): Flow<List<DownloadTaskEntity>>

    /** 已完成任务，按完成时间倒序。 */
    @Query("SELECT * FROM download_task WHERE status = 'Completed' ORDER BY completedAt DESC")
    fun observeCompleted(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_task WHERE id = :id")
    suspend fun byId(id: Long): DownloadTaskEntity?

    /** 队列中最早创建的任务，作为调度器的下一个候选。 */
    @Query("SELECT * FROM download_task WHERE status = 'Queued' ORDER BY createdAt ASC LIMIT 1")
    suspend fun nextQueued(): DownloadTaskEntity?

    /**
     * 同歌曲的活动任务。Completed/Cancelled 不占用歌曲，允许重新入队；
     * Failed 与 Paused 仍未结束，因此仍视为活动任务。
     */
    @Query(
        "SELECT * FROM download_task WHERE songId = :songId " +
            "AND status NOT IN ('Completed', 'Cancelled') ORDER BY createdAt DESC LIMIT 1",
    )
    suspend fun activeBySongId(songId: Long): DownloadTaskEntity?

    @Query("SELECT * FROM download_task WHERE status != 'Completed'")
    suspend fun allInProgress(): List<DownloadTaskEntity>

    @Insert
    suspend fun insert(task: DownloadTaskEntity): Long

    @Update
    suspend fun update(task: DownloadTaskEntity)

    @Query("DELETE FROM download_task WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM download_task WHERE status = 'Completed'")
    suspend fun clearCompleted()

    /** 应用启动时把可继续的任务统一置为暂停，并记录暂停时间。 */
    @Query(
        "UPDATE download_task SET status = 'Paused', updatedAt = :now " +
            "WHERE status IN ('Queued', 'Resolving', 'Waiting', 'Downloading')",
    )
    suspend fun markUnfinishedAsPaused(now: Long)

    @Query("SELECT COUNT(*) FROM download_task WHERE status = 'Completed'")
    suspend fun countCompleted(): Int

    /** 同名文件是否已被占用；Cancelled 任务不会产出文件，因此不占用文件名。 */
    @Query(
        "SELECT COUNT(*) > 0 FROM download_task WHERE fileName = :name AND status != 'Cancelled'",
    )
    suspend fun isFileNameTaken(name: String): Boolean

    /**
     * 仅当歌曲没有活动任务时插入，返回新行 id；已存在活动任务时返回 -1。
     *
     * 查询与插入在同一事务中完成，避免并发入队时两个调用都通过检查。
     */
    @Transaction
    suspend fun insertIfNoActive(task: DownloadTaskEntity): Long {
        val songId = task.songId
        if (songId != null && activeBySongId(songId) != null) return -1L
        return insert(task)
    }
}
