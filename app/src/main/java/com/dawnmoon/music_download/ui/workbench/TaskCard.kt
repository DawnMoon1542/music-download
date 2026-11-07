package com.dawnmoon.music_download.ui.workbench

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.dawnmoon.music_download.R
import com.dawnmoon.music_download.domain.model.MusicQuality
import com.dawnmoon.music_download.ui.common.FileInfoFormatter
import com.dawnmoon.music_download.ui.viewmodel.TaskUiModel
import kotlin.math.roundToInt

/**
 * 单个任务卡片。
 *
 * 未完成任务的按钮由 [TaskUiModel] 的 can* 字段驱动，界面不推断任务状态；
 * 已完成任务切换为播放、分享、重新下载与删除动作。
 * 失败详情只有在存在服务端原文时才提供展开入口，避免普通卡片出现无响应的点击区域。
 */
@Composable
fun TaskCard(
    task: TaskUiModel,
    callbacks: WorkbenchCallbacks,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier
                .padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 8.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.padding(end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(task)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = task.title ?: task.sourceUrl,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    task.artist?.let { artist ->
                        Text(
                            text = artist,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.padding(end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MetaRow(task)
                ProgressSection(task)
                FailureDetails(task)
            }

            ActionsRow(task = task, callbacks = callbacks)
        }
    }
}

@Composable
private fun Artwork(task: TaskUiModel) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (task.artworkUrl != null) {
            AsyncImage(
                model = task.artworkUrl,
                contentDescription = stringResource(R.string.task_cover_content_description),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Outlined.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

/** 音质标签；实际音质优先的取值已由 ViewModel 写入 [TaskUiModel.qualityLabel]。 */
@Composable
private fun QualityChip(label: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/** 状态、音质与文件信息；失败文案可能较长，允许换行而不是截断。 */
@Composable
private fun MetaRow(task: TaskUiModel) {
    val sameYearFormat = stringResource(R.string.date_format_current_year)
    val otherYearFormat = stringResource(R.string.date_format_other_year)
    val details = if (task.progress != null) {
        val sizes = "${FileInfoFormatter.formatBytes(task.bytesDownloaded)} / " +
            FileInfoFormatter.formatBytes(task.totalBytes)
        val speed = task.speedBytesPerSecond?.let { " · ${FileInfoFormatter.formatSpeed(it)}" }.orEmpty()
        "${(task.progress * 100).roundToInt()}% · $sizes$speed"
    } else {
        buildList {
            task.completedAt?.let {
                add(FileInfoFormatter.formatDate(it, System.currentTimeMillis(), sameYearFormat, otherYearFormat))
            }
            task.totalBytes?.let { add(FileInfoFormatter.formatBytes(it)) }
        }.joinToString(" · ")
    }

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        StatusPill(
            label = task.statusLabel,
            style = statusStyle(task.statusGroup, paused = task.canResume),
            modifier = Modifier.testTag("task_status"),
        )
        QualityChip(task.qualityLabel)
        if (details.isNotEmpty()) {
            Text(
                text = details,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 下载中展示平滑过渡的确定进度，解析中展示不确定进度；其余状态不占位。 */
@Composable
private fun ProgressSection(task: TaskUiModel) {
    val progress = task.progress
    if (progress != null) {
        val animated by animateFloatAsState(progress, label = "download_progress")
        LinearProgressIndicator(
            progress = { animated },
            strokeCap = StrokeCap.Round,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("download_progress"),
        )
    } else if (task.isResolving) {
        LinearProgressIndicator(
            strokeCap = StrokeCap.Round,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun FailureDetails(task: TaskUiModel) {
    val failureMessage = task.failureMessage ?: return
    var expanded by remember(task.id) { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "failure_chevron")
    // 展开区域没有独立按钮，屏幕阅读器依赖 OnClick 的动作标签说明点击结果。
    val expansionActionLabel = stringResource(
        if (expanded) R.string.task_failure_collapse_action else R.string.task_failure_expand_action,
    )

    Column {
        Row(
            modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .heightIn(min = 48.dp)
                .testTag("task_failure_toggle")
                .clickable(onClickLabel = expansionActionLabel) { expanded = !expanded }
                .padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.task_failure_details_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.error,
            )
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.rotate(rotation),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.task_failure_detail, failureMessage),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}

@Composable
private fun ActionsRow(
    task: TaskUiModel,
    callbacks: WorkbenchCallbacks,
) {
    if (task.isCompleted) {
        CompletedActionsRow(task = task, callbacks = callbacks)
        return
    }

    val hasActions = task.canPause || task.canResume || task.canRetry ||
        task.canCancel || task.canDelete
    if (!hasActions) return

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (task.canCancel) {
            TaskAction(
                label = stringResource(R.string.action_cancel),
                icon = Icons.Outlined.Close,
                testTag = "task_action_cancel",
            ) { callbacks.onCancel(task.id) }
        }
        if (task.canDelete) {
            TaskAction(
                label = stringResource(R.string.action_delete_record),
                icon = Icons.Outlined.Delete,
                testTag = "task_action_delete",
            ) { callbacks.onDelete(task.id, false) }
        }
        if (task.canPause) {
            TaskAction(
                label = stringResource(R.string.action_pause),
                icon = Icons.Default.Pause,
                testTag = "task_action_pause",
            ) { callbacks.onPause(task.id) }
        }
        if (task.canResume) {
            TaskAction(
                label = stringResource(R.string.action_resume),
                icon = Icons.Default.PlayArrow,
                testTag = "task_action_resume",
                emphasized = true,
            ) { callbacks.onResume(task.id) }
        }
        if (task.canRetry) {
            TaskAction(
                label = stringResource(R.string.action_retry),
                icon = Icons.Default.Refresh,
                testTag = "task_action_retry",
                emphasized = true,
            ) { callbacks.onRetry(task.id) }
        }
    }
}

/** 已完成卡片：播放、分享以及"重新下载/删除"溢出菜单。 */
@Composable
private fun CompletedActionsRow(
    task: TaskUiModel,
    callbacks: WorkbenchCallbacks,
) {
    var menuExpanded by remember(task.id) { mutableStateOf(false) }
    var showQualityDialog by remember(task.id) { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TaskAction(
            label = stringResource(R.string.action_share),
            icon = Icons.Outlined.Share,
            testTag = "task_action_share",
        ) { callbacks.onShare(task.id) }
        TaskAction(
            label = stringResource(R.string.action_play),
            icon = Icons.Default.PlayArrow,
            testTag = "task_action_play",
            emphasized = true,
        ) { callbacks.onOpen(task.id) }
        Box {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.testTag("task_more_actions"),
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.more_actions_content_description),
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_redownload)) },
                    onClick = {
                        menuExpanded = false
                        showQualityDialog = true
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_delete_record)) },
                    onClick = {
                        menuExpanded = false
                        callbacks.onDelete(task.id, false)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_delete_record_and_file)) },
                    onClick = {
                        menuExpanded = false
                        callbacks.onDelete(task.id, true)
                    },
                )
            }
        }
    }

    if (showQualityDialog) {
        RedownloadQualityDialog(
            onDismiss = { showQualityDialog = false },
            onConfirm = { quality ->
                showQualityDialog = false
                callbacks.onRedownload(task.id, quality)
            },
        )
    }
}

/** 重新下载前的音质单选；确认后才创建新任务。 */
@Composable
private fun RedownloadQualityDialog(
    onDismiss: () -> Unit,
    onConfirm: (MusicQuality) -> Unit,
) {
    var selected by remember { mutableStateOf(MusicQuality.default) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.quality_label)) },
        text = {
            Column {
                MusicQuality.entries.forEach { quality ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .selectable(
                                selected = selected == quality,
                                role = Role.RadioButton,
                                onClick = { selected = quality },
                            )
                            .heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == quality, onClick = null)
                        Text(
                            text = quality.label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/**
 * 卡片动作按钮：主动作（继续、重试、播放）用填充色调按钮强调，其余用文本按钮。
 * 两种按钮都自带 48dp 最小触控目标。
 */
@Composable
private fun TaskAction(
    label: String,
    icon: ImageVector,
    testTag: String,
    emphasized: Boolean = false,
    onClick: () -> Unit,
) {
    val content: @Composable () -> Unit = {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text = label)
    }
    if (emphasized) {
        FilledTonalButton(onClick = onClick, modifier = Modifier.testTag(testTag)) { content() }
    } else {
        TextButton(onClick = onClick, modifier = Modifier.testTag(testTag)) { content() }
    }
}
