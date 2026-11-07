package com.dawnmoon.music_download.ui.workbench

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dawnmoon.music_download.R
import com.dawnmoon.music_download.domain.model.Quota

/** 免费额度低于该值时提醒用户，避免在解析前才发现额度耗尽。 */
private const val LOW_QUOTA_THRESHOLD = 10

/**
 * 顶栏额度标签。
 *
 * 无额度数据时整个标签不渲染；免费额度低于 [LOW_QUOTA_THRESHOLD] 时追加警示，
 * 警示本身带 testTag，便于测试断言其存在而不依赖颜色。
 */
@Composable
fun QuotaLabel(
    quota: Quota?,
    modifier: Modifier = Modifier,
) {
    if (quota == null) return

    val unknown = stringResource(R.string.quota_unknown)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(
                R.string.quota_summary,
                quota.freeRemaining?.toString() ?: unknown,
                quota.paidRemaining?.toString() ?: unknown,
            ),
            style = MaterialTheme.typography.labelMedium,
        )
        val freeRemaining = quota.freeRemaining
        if (freeRemaining != null && freeRemaining < LOW_QUOTA_THRESHOLD) {
            Text(
                text = stringResource(R.string.quota_low_warning),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.testTag("quota_warning"),
            )
        }
    }
}
