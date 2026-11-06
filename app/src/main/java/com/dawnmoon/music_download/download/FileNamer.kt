package com.dawnmoon.music_download.download

import com.dawnmoon.music_download.data.repository.TaskRepository
import com.dawnmoon.music_download.domain.model.BaseFileName

/**
 * 下载文件名的分配接缝。
 *
 * 重名序号与并发预留由 [TaskRepository.allocateFileName] 在仓库互斥区内完成，
 * 本类只负责把调用转发给仓库，便于 Worker 测试替身替换命名策略。
 */
class FileNamer(private val repo: TaskRepository) {

    /** 返回 [taskId] 任务可用的文件名：无冲突时为 [base]，否则附加 ` (n)` 序号。 */
    suspend fun uniqueName(taskId: Long, base: BaseFileName): String =
        repo.allocateFileName(taskId, base)
}
