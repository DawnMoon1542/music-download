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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dawnmoon.music_download.ui.settings.SettingsScreen
import com.dawnmoon.music_download.ui.viewmodel.AppViewModelFactory
import com.dawnmoon.music_download.ui.viewmodel.WorkbenchEvent
import com.dawnmoon.music_download.ui.viewmodel.WorkbenchViewModel
import com.dawnmoon.music_download.ui.workbench.WorkbenchCallbacks
import com.dawnmoon.music_download.ui.workbench.WorkbenchScreen

/** 单 Activity 内的页面标识；不使用 Navigation Compose。 */
enum class AppRoute { Workbench, Settings }

/**
 * 应用根组合：负责工作台与设置页的切换，并承载全局 Snackbar。
 *
 * @param viewModelFactory 页面 ViewModel 工厂；工作台状态由其创建的 ViewModel 提供。
 */
@Composable
fun AppRoot(
    viewModelFactory: AppViewModelFactory,
    modifier: Modifier = Modifier,
) {
    var route by rememberSaveable { mutableStateOf(AppRoute.Workbench) }
    val snackbarHostState = remember { SnackbarHostState() }

    // 权限结果只影响通知是否可见；下载早已由 ViewModel 启动，因此这里仅记录结果。
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        Log.i(LOG_TAG, "POST_NOTIFICATIONS granted=$granted")
    }

    BackHandler(enabled = route == AppRoute.Settings) {
        route = AppRoute.Workbench
    }

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
                                is WorkbenchEvent.OpenSettings -> route = AppRoute.Settings
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
                            onOpenSettings = { route = AppRoute.Settings },
                        ),
                    )
                }
                AppRoute.Settings -> SettingsScreen(
                    onBack = { route = AppRoute.Workbench },
                )
            }
        }
    }
}

private const val LOG_TAG = "AppRoot"
