package com.dawnmoon.music_download.ui.viewmodel

import com.dawnmoon.music_download.domain.model.MusicQuality
import com.dawnmoon.music_download.domain.model.Quota
import com.dawnmoon.music_download.download.EngineSnapshot

/**
 * 任务列表的状态分组；声明顺序即列表中的排序优先级。
 *
 * [Active] 涵盖排队、解析、等待、下载与暂停：它们都还需要继续推进或等待用户继续。
 */
enum class TaskStatusGroup {
    Failed,
    Active,
    Cancelled,
    Completed,
}

/**
 * 工作台完整界面状态。
 *
 * [tasks] 是全部任务按 [TaskStatusGroup] 优先级排序后，经 [query] 过滤的结果。
 * 进行中保持加入顺序，下载中整段排在待解析之前；已完成排在这些任务之后。
 * 异常、已取消、已完成各组内仍按创建时间从新到旧。
 * [statusCounts] 统计过滤前的数量，供列表标题展示。
 */
data class WorkbenchUiState(
    val input: String,
    val selectedQuality: MusicQuality,
    val quota: Quota?,
    val tasks: List<TaskUiModel>,
    val statusCounts: Map<TaskStatusGroup, Int>,
    val query: String,
    val isEnqueuing: Boolean,
    val engine: EngineSnapshot?,
) {
    val totalCount: Int get() = statusCounts.values.sum()
}

/**
 * 单个任务的展示模型。
 *
 * [title] 等解析字段在任务尚未解析时为 null；[progress] 与 [speedBytesPerSecond]
 * 只在任务处于下载中时有值，其余状态不展示进度。
 */
data class TaskUiModel(
    val id: Long,
    val songId: Long?,
    val sourceUrl: String,
    val mediaStoreId: Long?,
    val title: String?,
    val artist: String?,
    val artworkUrl: String?,
    val qualityLabel: String,
    val statusLabel: String,
    val statusGroup: TaskStatusGroup,
    val progress: Float?,
    val isResolving: Boolean,
    val bytesDownloaded: Long?,
    val totalBytes: Long?,
    val speedBytesPerSecond: Long?,
    val failureKind: String?,
    val failureMessage: String?,
    val fileName: String?,
    val createdAt: Long,
    val completedAt: Long?,
    /**
     * 同一 [statusGroup] 内的状态优先级，数值小的排在前面。
     * 待解析（排队）低于下载中；其余状态与下载中同级。
     */
    val statusSortRank: Int = 0,
    val canPause: Boolean,
    val canResume: Boolean,
    val canCancel: Boolean,
    val canRetry: Boolean,
    val canDelete: Boolean,
) {
    val isCompleted: Boolean get() = statusGroup == TaskStatusGroup.Completed
}

/** 由 ViewModel 推送给界面的一次性事件。 */
sealed interface WorkbenchEvent {

    data class Snackbar(val message: String) : WorkbenchEvent

    data class OpenSettings(val focusKey: Boolean) : WorkbenchEvent

    data object RequestNotificationPermission : WorkbenchEvent
}
