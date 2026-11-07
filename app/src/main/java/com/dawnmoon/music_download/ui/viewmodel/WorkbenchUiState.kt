package com.dawnmoon.music_download.ui.viewmodel

import com.dawnmoon.music_download.domain.model.MusicQuality
import com.dawnmoon.music_download.domain.model.Quota
import com.dawnmoon.music_download.download.EngineSnapshot

/** 工作台的两个任务列表。 */
enum class WorkbenchTab {
    InProgress,
    Completed,
}

/**
 * 工作台完整界面状态。
 *
 * [inProgress] 包含除已完成外的全部任务（含失败与取消），[completed] 已按 [historyQuery] 过滤。
 */
data class WorkbenchUiState(
    val input: String,
    val selectedQuality: MusicQuality,
    val quota: Quota?,
    val inProgress: List<TaskUiModel>,
    val completed: List<TaskUiModel>,
    val selectedTab: WorkbenchTab,
    val historyQuery: String,
    val isEnqueuing: Boolean,
    val engine: EngineSnapshot?,
)

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
    val progress: Float?,
    val bytesDownloaded: Long?,
    val totalBytes: Long?,
    val speedBytesPerSecond: Long?,
    val failureKind: String?,
    val failureMessage: String?,
    val fileName: String?,
    val completedAt: Long?,
    val canPause: Boolean,
    val canResume: Boolean,
    val canCancel: Boolean,
    val canRetry: Boolean,
    val canDelete: Boolean,
)

/** 由 ViewModel 推送给界面的一次性事件。 */
sealed interface WorkbenchEvent {

    data class Snackbar(val message: String) : WorkbenchEvent

    data class OpenSettings(val focusKey: Boolean) : WorkbenchEvent

    data object RequestNotificationPermission : WorkbenchEvent
}
