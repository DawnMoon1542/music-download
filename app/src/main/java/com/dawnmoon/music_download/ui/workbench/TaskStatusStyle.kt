package com.dawnmoon.music_download.ui.workbench

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Downloading
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dawnmoon.music_download.R
import com.dawnmoon.music_download.ui.viewmodel.TaskStatusGroup

/** 状态标签的配色与图标；颜色全部取自主题角色，动态取色下同样成立。 */
internal data class StatusStyle(
    val container: Color,
    val content: Color,
    val icon: ImageVector,
)

/**
 * 按状态分组取样式：异常用 error、进行中用 primary、暂停用 secondary、
 * 已取消退为中性色、已完成用 tertiary，保证列表中异常最醒目、已完成最安静。
 */
@Composable
internal fun statusStyle(group: TaskStatusGroup, paused: Boolean = false): StatusStyle {
    val colors = MaterialTheme.colorScheme
    return when (group) {
        TaskStatusGroup.Failed -> StatusStyle(
            colors.errorContainer,
            colors.onErrorContainer,
            Icons.Outlined.ErrorOutline,
        )
        TaskStatusGroup.Active -> if (paused) {
            StatusStyle(colors.secondaryContainer, colors.onSecondaryContainer, Icons.Outlined.PauseCircle)
        } else {
            StatusStyle(colors.primaryContainer, colors.onPrimaryContainer, Icons.Outlined.Downloading)
        }
        TaskStatusGroup.Cancelled -> StatusStyle(
            colors.surfaceContainerHighest,
            colors.onSurfaceVariant,
            Icons.Outlined.Block,
        )
        TaskStatusGroup.Completed -> StatusStyle(
            colors.tertiaryContainer,
            colors.onTertiaryContainer,
            Icons.Outlined.CheckCircle,
        )
    }
}

/** 带图标的状态药丸；状态切换时颜色以动画过渡，而不是瞬间跳变。 */
@Composable
internal fun StatusPill(
    label: String,
    style: StatusStyle,
    modifier: Modifier = Modifier,
) {
    val container by animateColorAsState(style.container, label = "status_container")
    val content by animateColorAsState(style.content, label = "status_content")
    Surface(
        shape = MaterialTheme.shapes.small,
        color = container,
        contentColor = content,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(start = 6.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = style.icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 列表标题下的状态计数徽标。 */
@Composable
internal fun StatusCountBadge(
    group: TaskStatusGroup,
    count: Int,
) {
    StatusPill(
        label = stringResource(group.countLabel, count),
        style = statusStyle(group),
    )
}

@get:StringRes
private val TaskStatusGroup.countLabel: Int
    get() = when (this) {
        TaskStatusGroup.Failed -> R.string.status_group_failed
        TaskStatusGroup.Active -> R.string.status_group_active
        TaskStatusGroup.Cancelled -> R.string.status_group_cancelled
        TaskStatusGroup.Completed -> R.string.status_group_completed
    }
