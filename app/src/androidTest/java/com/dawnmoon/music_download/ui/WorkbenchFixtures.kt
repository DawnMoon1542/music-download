package com.dawnmoon.music_download.ui

import com.dawnmoon.music_download.domain.model.MusicQuality
import com.dawnmoon.music_download.domain.model.Quota
import com.dawnmoon.music_download.ui.viewmodel.TaskStatusGroup
import com.dawnmoon.music_download.ui.viewmodel.TaskUiModel
import com.dawnmoon.music_download.ui.viewmodel.WorkbenchUiState

/**
 * 工作台界面测试共用的状态构造。
 *
 * [statusCounts] 默认按 [tasks] 统计；需要模拟"有任务但搜索无结果"时显式传入。
 */
internal fun workbenchState(
    tasks: List<TaskUiModel> = emptyList(),
    input: String = "",
    selectedQuality: MusicQuality = MusicQuality.Exhigh,
    quota: Quota? = null,
    query: String = "",
    isEnqueuing: Boolean = false,
    statusCounts: Map<TaskStatusGroup, Int> = tasks.groupingBy { it.statusGroup }.eachCount(),
): WorkbenchUiState = WorkbenchUiState(
    input = input,
    selectedQuality = selectedQuality,
    quota = quota,
    tasks = tasks,
    statusCounts = statusCounts,
    query = query,
    isEnqueuing = isEnqueuing,
    engine = null,
)

/**
 * 测试任务；未显式给出 [statusGroup] 时按能力推断：有完成时间为已完成、有失败类型为异常、
 * 可删除记录为已取消，其余视为进行中。
 */
internal fun testTask(
    id: Long,
    title: String = "歌曲 $id",
    statusLabel: String = "下载中",
    artist: String? = null,
    songId: Long = id + 1L,
    mediaStoreId: Long? = null,
    qualityLabel: String = MusicQuality.Exhigh.label,
    progress: Float? = null,
    bytesDownloaded: Long? = null,
    totalBytes: Long? = null,
    speedBytesPerSecond: Long? = null,
    failureKind: String? = null,
    failureMessage: String? = null,
    fileName: String? = null,
    completedAt: Long? = null,
    canPause: Boolean = false,
    canResume: Boolean = false,
    canCancel: Boolean = false,
    canRetry: Boolean = false,
    canDelete: Boolean = false,
    statusGroup: TaskStatusGroup = when {
        completedAt != null -> TaskStatusGroup.Completed
        failureKind != null -> TaskStatusGroup.Failed
        canDelete -> TaskStatusGroup.Cancelled
        else -> TaskStatusGroup.Active
    },
): TaskUiModel = TaskUiModel(
    id = id,
    songId = songId,
    sourceUrl = "https://music.163.com/song?id=$songId",
    mediaStoreId = mediaStoreId,
    title = title,
    artist = artist,
    artworkUrl = null,
    qualityLabel = qualityLabel,
    statusLabel = statusLabel,
    statusGroup = statusGroup,
    progress = progress,
    isResolving = false,
    bytesDownloaded = bytesDownloaded,
    totalBytes = totalBytes,
    speedBytesPerSecond = speedBytesPerSecond,
    failureKind = failureKind,
    failureMessage = failureMessage,
    fileName = fileName,
    createdAt = id,
    completedAt = completedAt,
    canPause = canPause,
    canResume = canResume,
    canCancel = canCancel,
    canRetry = canRetry,
    canDelete = canDelete,
)
