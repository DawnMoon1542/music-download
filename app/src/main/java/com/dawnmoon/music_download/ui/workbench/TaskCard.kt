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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.dawnmoon.music_download.R
import com.dawnmoon.music_download.ui.common.FileInfoFormatter
import com.dawnmoon.music_download.ui.viewmodel.TaskUiModel
import kotlin.math.roundToInt

/**
 * 单个任务卡片。
 *
 * 动作按钮完全由 [TaskUiModel] 的 can* 字段驱动，界面不推断任务状态；
 * 失败卡片只有在存在服务端原文时才可点击展开，避免普通卡片出现无响应的点击区域。
 */
@Composable
fun TaskCard(
    task: TaskUiModel,
    callbacks: WorkbenchCallbacks,
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

            ActionsRow(task = task, callbacks = callbacks)
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
) {
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
