package com.dawnmoon.music_download.ui.workbench

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dawnmoon.music_download.R
import com.dawnmoon.music_download.domain.model.MusicQuality
import com.dawnmoon.music_download.ui.history.ClearHistoryDialog
import com.dawnmoon.music_download.ui.viewmodel.TaskStatusGroup
import com.dawnmoon.music_download.ui.viewmodel.TaskUiModel
import com.dawnmoon.music_download.ui.viewmodel.WorkbenchUiState

/**
 * 工作台的界面回调。
 *
 * 每个回调都有空实现，测试与预览只需覆盖被观察的分支；生产装配由 `AppRoot` 完成。
 */
data class WorkbenchCallbacks(
    val onInputChange: (String) -> Unit = {},
    val onQualityChange: (MusicQuality) -> Unit = {},
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
    val onQueryChange: (String) -> Unit = {},
    val onClearHistory: (Boolean) -> Unit = {},
    val onOpen: (Long) -> Unit = {},
    val onShare: (Long) -> Unit = {},
    val onRedownload: (Long, MusicQuality) -> Unit = { _, _ -> },
)

/**
 * 工作台页面。
 *
 * 纯展示组合：状态由 [WorkbenchUiState] 注入，所有交互只通过 [WorkbenchCallbacks]
 * 上抛，不直接持有 ViewModel、仓库或引擎。输入卡片、搜索框与任务处于同一个
 * 滚动容器中，大标题栏随列表滚动折叠。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkbenchScreen(
    state: WorkbenchUiState,
    callbacks: WorkbenchCallbacks,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.workbench_title)) },
                actions = {
                    TaskActionsMenu(callbacks = callbacks)
                    IconButton(
                        onClick = callbacks.onOpenSettings,
                        modifier = Modifier.testTag("settings_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = stringResource(R.string.settings_content_description),
                        )
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0),
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        TaskList(
            state = state,
            callbacks = callbacks,
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding() + 4.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp,
            ),
        )
    }
}

/**
 * 顶栏溢出菜单：批量控制作用于未完成任务，清空只作用于已完成记录。
 *
 * 取消全部与清空记录都不可撤销，因此先弹确认框；打开确认框本身不触发动作。
 */
@Composable
private fun TaskActionsMenu(callbacks: WorkbenchCallbacks) {
    var expanded by remember { mutableStateOf(false) }
    var showCancelAllConfirmation by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }

    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.testTag("batch_actions_menu"),
        ) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.more_actions_content_description),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            MenuItem(text = R.string.action_pause_all) {
                expanded = false
                callbacks.onPauseAll()
            }
            MenuItem(text = R.string.action_resume_all) {
                expanded = false
                callbacks.onResumeAll()
            }
            MenuItem(text = R.string.action_retry_all_failed) {
                expanded = false
                callbacks.onRetryAllFailed()
            }
            MenuItem(text = R.string.action_cancel_all) {
                expanded = false
                showCancelAllConfirmation = true
            }
            HorizontalDivider()
            MenuItem(text = R.string.action_clear_history) {
                expanded = false
                showClearDialog = true
            }
        }
    }

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
private fun MenuItem(
    @StringRes text: Int,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(stringResource(text)) },
        onClick = onClick,
    )
}

@Composable
private fun TaskList(
    state: WorkbenchUiState,
    callbacks: WorkbenchCallbacks,
    contentPadding: PaddingValues,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("task_list"),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "input", contentType = "input") {
            LinkInputCard(state = state, callbacks = callbacks)
        }
        item(key = "header", contentType = "header") {
            TaskListHeader(state = state, callbacks = callbacks)
        }
        if (state.tasks.isEmpty()) {
            item(key = "empty", contentType = "empty") {
                if (state.totalCount == 0) {
                    EmptyState(
                        message = stringResource(R.string.empty_tasks),
                        hint = stringResource(R.string.empty_tasks_hint),
                        modifier = Modifier.animateItem(),
                    )
                } else {
                    EmptyState(
                        message = stringResource(R.string.empty_task_search),
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        } else {
            items(state.tasks, key = TaskUiModel::id, contentType = { "task" }) { task ->
                TaskCard(
                    task = task,
                    callbacks = callbacks,
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

@Composable
private fun LinkInputCard(
    state: WorkbenchUiState,
    callbacks: WorkbenchCallbacks,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.add_download_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                QuotaLabel(quota = state.quota)
            }
            OutlinedTextField(
                value = state.input,
                onValueChange = callbacks.onInputChange,
                placeholder = { Text(stringResource(R.string.link_input_placeholder)) },
                minLines = 3,
                maxLines = 6,
                shape = MaterialTheme.shapes.medium,
                trailingIcon = {
                    AnimatedVisibility(
                        visible = state.input.isNotEmpty(),
                        enter = fadeIn() + scaleIn(),
                        exit = fadeOut() + scaleOut(),
                    ) {
                        IconButton(onClick = { callbacks.onInputChange("") }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.clear_input_content_description),
                            )
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("link_input"),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                QualitySelector(
                    selected = state.selectedQuality,
                    onQualityChange = callbacks.onQualityChange,
                    enabled = !state.isEnqueuing,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("quality_selector"),
                )
                Button(
                    onClick = callbacks.onEnqueue,
                    enabled = state.input.isNotBlank() && !state.isEnqueuing,
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                    modifier = Modifier.testTag("enqueue_button"),
                ) {
                    AnimatedContent(
                        targetState = state.isEnqueuing,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "enqueue_button_content",
                    ) { enqueuing ->
                        if (enqueuing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.enqueue_action))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 列表标题、各状态数量与搜索框；没有任何任务时只保留标题，避免空列表出现无用的搜索框。 */
@Composable
private fun TaskListHeader(
    state: WorkbenchUiState,
    callbacks: WorkbenchCallbacks,
) {
    Column(
        modifier = Modifier.padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.tasks_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        if (state.totalCount > 0) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 4.dp),
            ) {
                TaskStatusGroup.entries.forEach { group ->
                    val count = state.statusCounts[group] ?: 0
                    if (count > 0) StatusCountBadge(group = group, count = count)
                }
            }
            TaskSearchField(query = state.query, onQueryChange = callbacks.onQueryChange)
        }
    }
}

@Composable
private fun TaskSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(stringResource(R.string.task_search_placeholder)) },
        singleLine = true,
        shape = CircleShape,
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            AnimatedVisibility(
                visible = query.isNotEmpty(),
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
            ) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.clear_input_content_description),
                    )
                }
            }
        },
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            unfocusedBorderColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("task_search"),
    )
}
