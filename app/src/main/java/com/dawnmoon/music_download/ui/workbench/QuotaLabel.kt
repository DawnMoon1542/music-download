package com.dawnmoon.music_download.ui.workbench

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dawnmoon.music_download.R
import com.dawnmoon.music_download.domain.model.Quota

/** 免费额度低于该值时提醒用户，避免在解析前才发现额度耗尽。 */
private const val LOW_QUOTA_THRESHOLD = 10

/**
 * 输入卡片右上角的额度药丸。
 *
 * 无额度数据时整个标签不渲染；免费额度低于 [LOW_QUOTA_THRESHOLD] 时切换为错误色并追加警示，
 * 警示本身带 testTag，便于测试断言其存在而不依赖颜色。
 */
@Composable
fun QuotaLabel(
    quota: Quota?,
    modifier: Modifier = Modifier,
) {
    if (quota == null) return

    val unknown = stringResource(R.string.quota_unknown)
    val freeRemaining = quota.freeRemaining
    val low = freeRemaining != null && freeRemaining < LOW_QUOTA_THRESHOLD
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (low) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        contentColor = if (low) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(
                    R.string.quota_summary,
                    freeRemaining?.toString() ?: unknown,
                    quota.paidRemaining?.toString() ?: unknown,
                ),
                style = MaterialTheme.typography.labelMedium,
            )
            if (low) {
                Text(
                    text = stringResource(R.string.quota_low_warning),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("quota_warning"),
                )
            }
        }
    }
}
