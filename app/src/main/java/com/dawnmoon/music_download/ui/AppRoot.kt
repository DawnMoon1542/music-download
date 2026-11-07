package com.dawnmoon.music_download.ui

import android.Manifest
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dawnmoon.music_download.R
import com.dawnmoon.music_download.domain.model.ThemeMode
import com.dawnmoon.music_download.ui.settings.SettingsCallbacks
import com.dawnmoon.music_download.ui.settings.SettingsScreen
import com.dawnmoon.music_download.ui.theme.MusicDownloadTheme
import com.dawnmoon.music_download.ui.viewmodel.AppViewModelFactory
import com.dawnmoon.music_download.ui.viewmodel.SettingsEvent
import com.dawnmoon.music_download.ui.viewmodel.SettingsViewModel
import com.dawnmoon.music_download.ui.viewmodel.WorkbenchEvent
import com.dawnmoon.music_download.ui.viewmodel.WorkbenchViewModel
import com.dawnmoon.music_download.ui.workbench.WorkbenchCallbacks
import com.dawnmoon.music_download.ui.workbench.WorkbenchScreen

/** 单 Activity 内的页面标识；不使用 Navigation Compose。 */
enum class AppRoute { Workbench, Settings }

/**
 * 应用根组合：负责工作台与设置页的切换，并承载全局 Snackbar 与主题。
 *
 * @param viewModelFactory 页面 ViewModel 工厂；页面状态由其创建的 ViewModel 提供。
 * @param themeMode 当前主题模式；由调用方收集 `settings.themeMode` 后传入。
 * @param focusKey 启动入口是否要求直接进入设置页并聚焦 Key 输入框。
 * @param onFocusKeyConsumed 已处理 focusKey 后回调；调用方重置标记，后续同一 extra 仍能生效。
 */
@Composable
fun AppRoot(
    viewModelFactory: AppViewModelFactory,
    themeMode: ThemeMode = ThemeMode.System,
    modifier: Modifier = Modifier,
    focusKey: Boolean = false,
    onFocusKeyConsumed: () -> Unit = {},
) {
    var route by rememberSaveable { mutableStateOf(AppRoute.Workbench) }
    // 进入设置页时是否聚焦 Key 输入框；由启动 extra 或工作台的缺 Key 事件触发，返回后清除。
    var focusKeyOnOpen by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // 从通知或外部入口带 focusKey 启动时直接转到设置页。
    LaunchedEffect(focusKey) {
        if (focusKey) {
            focusKeyOnOpen = true
            route = AppRoute.Settings
            onFocusKeyConsumed()
        }
    }

    // 权限结果只影响通知是否可见；下载早已由 ViewModel 启动，因此这里仅记录结果。
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        Log.i(LOG_TAG, "POST_NOTIFICATIONS granted=$granted")
    }

    BackHandler(enabled = route == AppRoute.Settings) {
        focusKeyOnOpen = false
        route = AppRoute.Workbench
    }

    MusicDownloadTheme(themeMode = themeMode) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            modifier = modifier,
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                when (route) {
                    AppRoute.Workbench -> {
                        val workbenchViewModel: WorkbenchViewModel = viewModel(
                            factory = viewModelFactory,
                        )
                        val state by workbenchViewModel.state.collectAsStateWithLifecycle()
                        LaunchedEffect(workbenchViewModel) {
                            workbenchViewModel.events.collect { event ->
                                when (event) {
                                    is WorkbenchEvent.Snackbar ->
                                        snackbarHostState.showSnackbar(event.message)
                                    is WorkbenchEvent.OpenSettings -> {
                                        focusKeyOnOpen = event.focusKey
                                        route = AppRoute.Settings
                                    }
                                    WorkbenchEvent.RequestNotificationPermission ->
                                        notificationPermissionLauncher.launch(
                                            Manifest.permission.POST_NOTIFICATIONS,
                                        )
                                }
                            }
                        }
                        WorkbenchScreen(
                            state = state,
                            callbacks = WorkbenchCallbacks(
                                onInputChange = workbenchViewModel::onInputChange,
                                onQualityChange = workbenchViewModel::onQualityChange,
                                onTabChange = workbenchViewModel::onTabChange,
                                onEnqueue = workbenchViewModel::onEnqueue,
                                onPause = workbenchViewModel::onPause,
                                onResume = workbenchViewModel::onResume,
                                onCancel = workbenchViewModel::onCancel,
                                onRetry = workbenchViewModel::onRetry,
                                onPauseAll = workbenchViewModel::onPauseAll,
                                onResumeAll = workbenchViewModel::onResumeAll,
                                onRetryAllFailed = workbenchViewModel::onRetryAllFailed,
                                onCancelAll = workbenchViewModel::onCancelAll,
                                onOpenSettings = {
                                    focusKeyOnOpen = false
                                    route = AppRoute.Settings
                                },
                                onHistoryQueryChange = workbenchViewModel::onHistoryQueryChange,
                                onClearHistory = workbenchViewModel::onClearHistory,
                                onOpen = workbenchViewModel::onOpen,
                                onShare = workbenchViewModel::onShare,
                                onRedownload = workbenchViewModel::onRedownload,
                                onDelete = workbenchViewModel::onDelete,
                            ),
                        )
                    }
                    AppRoute.Settings -> {
                        val settingsViewModel: SettingsViewModel = viewModel(
                            factory = viewModelFactory,
                        )
                        val state by settingsViewModel.state.collectAsStateWithLifecycle()
                        val resources = LocalResources.current
                        LaunchedEffect(settingsViewModel) {
                            settingsViewModel.events.collect { event ->
                                val message = when (event) {
                                    SettingsEvent.HistoryCleared ->
                                        resources.getString(R.string.history_cleared)
                                    is SettingsEvent.HistoryClearFailed ->
                                        resources.getString(
                                            R.string.history_clear_failed_count,
                                            event.retainedCount,
                                        )
                                }
                                snackbarHostState.showSnackbar(message)
                            }
                        }
                        SettingsScreen(
                            state = state,
                            callbacks = SettingsCallbacks(
                                onKeyChange = settingsViewModel::onKeyChange,
                                onSaveKey = settingsViewModel::onSaveKey,
                                onDefaultQualityChange =
                                    settingsViewModel::onDefaultQualityChange,
                                onConcurrencyChange = settingsViewModel::onConcurrencyChange,
                                onThemeModeChange = settingsViewModel::onThemeModeChange,
                                onClearHistory = settingsViewModel::onClearHistory,
                            ),
                            onBack = {
                                focusKeyOnOpen = false
                                route = AppRoute.Workbench
                            },
                            focusKey = focusKeyOnOpen,
                        )
                    }
                }
            }
        }
    }
}

private const val LOG_TAG = "AppRoot"
