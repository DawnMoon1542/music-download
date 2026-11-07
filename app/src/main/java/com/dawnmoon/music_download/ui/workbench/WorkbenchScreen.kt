package com.dawnmoon.music_download.ui.workbench

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dawnmoon.music_download.R
import com.dawnmoon.music_download.domain.model.MusicQuality
import com.dawnmoon.music_download.ui.history.ClearHistoryDialog
import com.dawnmoon.music_download.ui.history.HistoryList
import com.dawnmoon.music_download.ui.history.HistorySearch
import com.dawnmoon.music_download.ui.viewmodel.TaskUiModel
import com.dawnmoon.music_download.ui.viewmodel.WorkbenchTab
import com.dawnmoon.music_download.ui.viewmodel.WorkbenchUiState

/**
 * 工作台的界面回调。
 *
 * 每个回调都有空实现，测试与预览只需覆盖被观察的分支；生产装配由 `AppRoot` 完成。
 */
data class WorkbenchCallbacks(
    val onInputChange: (String) -> Unit = {},
    val onQualityChange: (MusicQuality) -> Unit = {},
    val onTabChange: (WorkbenchTab) -> Unit = {},
    val onEnqueue: () -> Unit = {},
    val onPause: (Long) -> Unit = {},
    val onResume: (Long) -> Unit = {},
    val onCancel: (Long) -> Unit = {},
    val onRetry: (Long) -> Unit = {},
    val onDelete: (Long, Boolean) -> Unit = { _, _ -> },
    val onPauseAll: () -> Unit = {},
    val onResumeAll: () -> Unit = {},
    val onRetryAllFailed: () -> Unit = {},
    val onCancelAll: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onHistoryQueryChange: (String) -> Unit = {},
    val onClearHistory: (Boolean) -> Unit = {},
    val onOpen: (Long) -> Unit = {},
    val onShare: (Long) -> Unit = {},
    val onRedownload: (Long, MusicQuality) -> Unit = { _, _ -> },
)

/**
 * 工作台页面。
 *
 * 纯展示组合：状态由 [WorkbenchUiState] 注入，所有交互只通过 [WorkbenchCallbacks]
 * 上抛，不直接持有 ViewModel、仓库或引擎。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkbenchScreen(
    state: WorkbenchUiState,
    callbacks: WorkbenchCallbacks,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.workbench_title)) },
                actions = {
                    QuotaLabel(quota = state.quota)
                    // 批量动作只作用于未完成任务；历史 Tab 的溢出菜单留给"清空历史"。
                    if (state.selectedTab == WorkbenchTab.Completed) {
                        HistoryMenu(callbacks = callbacks)
                    } else {
                        BatchActionsMenu(callbacks = callbacks)
                    }
                    IconButton(onClick = callbacks.onOpenSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.settings_content_description),
                        )
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            LinkInputCard(state = state, callbacks = callbacks)
            TaskTabs(state = state, callbacks = callbacks)
            if (state.selectedTab == WorkbenchTab.Completed) {
                HistorySearch(
                    query = state.historyQuery,
                    onQueryChange = callbacks.onHistoryQueryChange,
                )
            }
            TaskList(state = state, callbacks = callbacks)
        }
    }
}

@Composable
private fun BatchActionsMenu(callbacks: WorkbenchCallbacks) {
    var expanded by remember { mutableStateOf(false) }
    var showCancelAllConfirmation by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.more_actions_content_description),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            BatchActionItem(text = R.string.action_pause_all) {
                expanded = false
                callbacks.onPauseAll()
            }
            BatchActionItem(text = R.string.action_resume_all) {
                expanded = false
                callbacks.onResumeAll()
            }
            BatchActionItem(text = R.string.action_retry_all_failed) {
                expanded = false
                callbacks.onRetryAllFailed()
            }
            BatchActionItem(text = R.string.action_cancel_all) {
                expanded = false
                showCancelAllConfirmation = true
            }
        }
    }

    // 取消全部不可撤销，先确认再交给引擎；打开确认框本身不触发取消。
    if (showCancelAllConfirmation) {
        AlertDialog(
            onDismissRequest = { showCancelAllConfirmation = false },
            title = { Text(stringResource(R.string.action_cancel_all)) },
            text = { Text(stringResource(R.string.cancel_all_confirmation)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCancelAllConfirmation = false
                        callbacks.onCancelAll()
                    },
                ) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelAllConfirmation = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun BatchActionItem(
    @StringRes text: Int,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(stringResource(text)) },
        onClick = onClick,
    )
}

/** 已完成 Tab 的历史操作菜单；清空历史需要用户在弹窗中明确选择是否删除文件。 */
@Composable
private fun HistoryMenu(callbacks: WorkbenchCallbacks) {
    var expanded by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.history_action_content_description),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            BatchActionItem(text = R.string.action_clear_history) {
                expanded = false
                showClearDialog = true
            }
        }
    }

    if (showClearDialog) {
        ClearHistoryDialog(
            onDismiss = { showClearDialog = false },
            onConfirm = { deleteFiles ->
                showClearDialog = false
                callbacks.onClearHistory(deleteFiles)
            },
        )
    }
}

@Composable
private fun LinkInputCard(
    state: WorkbenchUiState,
    callbacks: WorkbenchCallbacks,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = state.input,
                onValueChange = callbacks.onInputChange,
                placeholder = { Text(stringResource(R.string.link_input_placeholder)) },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                QualitySelector(
                    selected = state.selectedQuality,
                    onQualityChange = callbacks.onQualityChange,
                    enabled = !state.isEnqueuing,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = callbacks.onEnqueue,
                    enabled = state.input.isNotBlank() && !state.isEnqueuing,
                ) {
                    if (state.isEnqueuing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(stringResource(R.string.enqueue_action))
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskTabs(
    state: WorkbenchUiState,
    callbacks: WorkbenchCallbacks,
) {
    val selectedIndex = when (state.selectedTab) {
        WorkbenchTab.InProgress -> 0
        WorkbenchTab.Completed -> 1
    }
    PrimaryTabRow(selectedTabIndex = selectedIndex) {
        Tab(
            selected = selectedIndex == 0,
            onClick = { callbacks.onTabChange(WorkbenchTab.InProgress) },
            text = {
                Text("${stringResource(R.string.tab_in_progress)} (${state.inProgress.size})")
            },
        )
        Tab(
            selected = selectedIndex == 1,
            onClick = { callbacks.onTabChange(WorkbenchTab.Completed) },
            text = {
                Text("${stringResource(R.string.tab_completed)} (${state.completed.size})")
            },
        )
    }
}

@Composable
private fun TaskList(
    state: WorkbenchUiState,
    callbacks: WorkbenchCallbacks,
) {
    if (state.selectedTab == WorkbenchTab.Completed) {
        HistoryList(
            tasks = state.completed,
            query = state.historyQuery,
            callbacks = callbacks,
        )
        return
    }

    val tasks = state.inProgress
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (tasks.isEmpty()) {
            item {
                EmptyState(message = stringResource(R.string.empty_tasks))
            }
        } else {
            items(tasks, key = TaskUiModel::id) { task ->
                TaskCard(task = task, callbacks = callbacks)
            }
        }
    }
}
