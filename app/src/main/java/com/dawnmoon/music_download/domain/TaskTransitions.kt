package com.dawnmoon.music_download.domain

import com.dawnmoon.music_download.domain.model.TaskStatus

/**
 * 任务状态迁移规则。
 *
 * 迁移表本身即规格：未列出的目标状态一律拒绝，因此终态（Completed、Cancelled）
 * 没有条目，同一状态到自身的迁移也不会命中任何集合。
 * Downloading 可回到 Resolving，用于换链后重新解析。
 */
object TaskTransitions {

    private val transitions: Map<TaskStatus, Set<TaskStatus>> = mapOf(
        TaskStatus.Queued to setOf(
            TaskStatus.Resolving,
            TaskStatus.Paused,
            TaskStatus.Failed,
            TaskStatus.Cancelled,
        ),
        TaskStatus.Resolving to setOf(
            TaskStatus.Waiting,
            TaskStatus.Downloading,
            TaskStatus.Paused,
            TaskStatus.Failed,
            TaskStatus.Cancelled,
        ),
        TaskStatus.Waiting to setOf(
            TaskStatus.Downloading,
            TaskStatus.Paused,
            TaskStatus.Failed,
            TaskStatus.Cancelled,
        ),
        TaskStatus.Downloading to setOf(
            TaskStatus.Completed,
            TaskStatus.Paused,
            TaskStatus.Failed,
            TaskStatus.Cancelled,
            TaskStatus.Resolving,
        ),
        TaskStatus.Paused to setOf(
            TaskStatus.Queued,
            TaskStatus.Waiting,
            TaskStatus.Cancelled,
        ),
        TaskStatus.Failed to setOf(
            TaskStatus.Queued,
            TaskStatus.Cancelled,
        ),
    )

    /** 判断从 [from] 到 [to] 的状态迁移是否合法。 */
    fun isAllowed(from: TaskStatus, to: TaskStatus): Boolean =
        to in transitions[from].orEmpty()
}
