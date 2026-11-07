package com.dawnmoon.music_download.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dawnmoon.music_download.R
import com.dawnmoon.music_download.ui.viewmodel.TaskUiModel
import com.dawnmoon.music_download.ui.workbench.EmptyState
import com.dawnmoon.music_download.ui.workbench.TaskCard
import com.dawnmoon.music_download.ui.workbench.WorkbenchCallbacks

/**
 * 已完成历史列表。
 *
 * 空列表按查询是否为空区分"暂无历史"和"没有匹配"，提醒用户当前看到的是过滤结果。
 * 卡片复用 [TaskCard] 的已完成动作，播放、分享、重下与删除仍通过 [WorkbenchCallbacks] 上抛。
 */
@Composable
fun HistoryList(
    tasks: List<TaskUiModel>,
    query: String,
    callbacks: WorkbenchCallbacks,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (tasks.isEmpty()) {
            item {
                val message = if (query.isBlank()) {
                    R.string.empty_history
                } else {
                    R.string.empty_history_search
                }
                EmptyState(message = stringResource(message))
            }
        } else {
            items(tasks, key = TaskUiModel::id) { task ->
                TaskCard(task = task, callbacks = callbacks, isCompleted = true)
            }
        }
    }
}
