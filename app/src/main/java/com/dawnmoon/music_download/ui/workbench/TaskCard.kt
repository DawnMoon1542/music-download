package com.dawnmoon.music_download.ui.workbench

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
 * 进行中任务的按钮由 [TaskUiModel] 的 can* 字段驱动，界面不推断任务状态；
 * 历史 Tab 通过 [isCompleted] 切换为播放、分享、重新下载与删除动作。
 * 失败卡片只有在存在服务端原文时才可点击展开，避免普通卡片出现无响应的点击区域。
 */
@Composable
fun TaskCard(
    task: TaskUiModel,
    callbacks: WorkbenchCallbacks,
    isCompleted: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var expanded by remember(task.id) { mutableStateOf(false) }
    val failureMessage = task.failureMessage

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(task)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (failureMessage != null) {
                                Modifier.clickable { expanded = !expanded }
                            } else {
                                Modifier
                            },
                        ),
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
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                StatusChip(task.statusLabel)
            }

            MetaRow(task)

            if (expanded && failureMessage != null) {
                Text(
                    text = stringResource(R.string.task_failure_detail, failureMessage),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            ActionsRow(task = task, callbacks = callbacks, isCompleted = isCompleted)
        }
    }
}

@Composable
private fun Artwork(task: TaskUiModel) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
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
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 音质标签；实际音质优先的取值已由 ViewModel 写入 [TaskUiModel.qualityLabel]。 */
@Composable
private fun QualityChip(label: String) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** 状态标签；失败时展示的是 ViewModel 已本地化的失败文案。 */
@Composable
private fun StatusChip(label: String) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun MetaRow(task: TaskUiModel) {
    val progress = task.progress
    if (progress != null) {
        val percent = "${(progress * 100).roundToInt()}%"
        val sizes = "${FileInfoFormatter.formatBytes(task.bytesDownloaded)} / " +
            FileInfoFormatter.formatBytes(task.totalBytes)
        val speed = task.speedBytesPerSecond
            ?.let { " · ${FileInfoFormatter.formatSpeed(it)}" }
            .orEmpty()
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QualityChip(task.qualityLabel)
                Text(
                    text = "$percent · $sizes$speed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("download_progress"),
            )
        }
        return
    }

    val details = buildList {
        if (task.completedAt != null) {
            add(FileInfoFormatter.formatDate(task.completedAt, System.currentTimeMillis()))
        }
        if (task.totalBytes != null) {
            add(FileInfoFormatter.formatBytes(task.totalBytes))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QualityChip(task.qualityLabel)
        if (details.isNotEmpty()) {
            Text(
                text = details.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ActionsRow(
    task: TaskUiModel,
    callbacks: WorkbenchCallbacks,
    isCompleted: Boolean,
) {
    if (isCompleted) {
        CompletedActionsRow(task = task, callbacks = callbacks)
        return
    }

    val hasActions = task.canPause || task.canResume || task.canRetry ||
        task.canCancel || task.canDelete
    if (!hasActions) return

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        if (task.canPause) {
            TaskAction(stringResource(R.string.action_pause)) { callbacks.onPause(task.id) }
        }
        if (task.canResume) {
            TaskAction(stringResource(R.string.action_resume)) { callbacks.onResume(task.id) }
        }
        if (task.canRetry) {
            TaskAction(stringResource(R.string.action_retry)) { callbacks.onRetry(task.id) }
        }
        if (task.canCancel) {
            TaskAction(stringResource(R.string.action_cancel)) { callbacks.onCancel(task.id) }
        }
        if (task.canDelete) {
            TaskAction(stringResource(R.string.action_delete_record)) {
                callbacks.onDelete(task.id, false)
            }
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
        horizontalArrangement = Arrangement.End,
    ) {
        TaskAction(stringResource(R.string.action_play)) { callbacks.onOpen(task.id) }
        TaskAction(stringResource(R.string.action_share)) { callbacks.onShare(task.id) }
        Box {
            IconButton(onClick = { menuExpanded = true }) {
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
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MusicQuality.entries.forEach { quality ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selected == quality,
                                role = Role.RadioButton,
                                onClick = { selected = quality },
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == quality, onClick = null)
                        Text(
                            text = quality.label,
                            modifier = Modifier.padding(start = 8.dp),
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

/** 文本动作按钮；TextButton 自带 48dp 最小触控目标。 */
@Composable
private fun TaskAction(
    label: String,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick) {
        Text(text = label)
    }
}
