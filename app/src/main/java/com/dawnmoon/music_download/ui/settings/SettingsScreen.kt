package com.dawnmoon.music_download.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.dawnmoon.music_download.BuildConfig
import com.dawnmoon.music_download.R
import com.dawnmoon.music_download.domain.model.MusicQuality
import com.dawnmoon.music_download.domain.model.ThemeMode
import com.dawnmoon.music_download.ui.history.ClearHistoryDialog
import com.dawnmoon.music_download.ui.viewmodel.KeyStatus
import com.dawnmoon.music_download.ui.viewmodel.SettingsViewModel
import kotlin.math.roundToInt

/**
 * 设置页的界面回调。
 *
 * 每个回调都有空实现，测试与预览只需覆盖被观察的分支；生产装配由 `AppRoot` 完成。
 */
data class SettingsCallbacks(
    val onKeyChange: (String) -> Unit = {},
    val onSaveKey: () -> Unit = {},
    val onDefaultQualityChange: (MusicQuality) -> Unit = {},
    val onConcurrencyChange: (Int) -> Unit = {},
    val onThemeModeChange: (ThemeMode) -> Unit = {},
    val onClearHistory: (Boolean) -> Unit = {},
)

/**
 * 设置页：API Key、下载、外观、历史与关于五个分组。
 *
 * @param focusKey 为 true 时进入页面即聚焦 Key 输入框；工作台因缺少 Key 跳转时使用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsViewModel.State,
    callbacks: SettingsCallbacks,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    focusKey: Boolean = false,
) {
    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_title)) },
            navigationIcon = {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.testTag("settings_back"),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back_content_description),
                    )
                }
            },
            windowInsets = WindowInsets(0, 0, 0, 0),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            ApiKeyGroup(state = state, callbacks = callbacks, focusKey = focusKey)
            DownloadGroup(state = state, callbacks = callbacks)
            AppearanceGroup(state = state, callbacks = callbacks)
            HistoryGroup(callbacks = callbacks)
            AboutGroup()
        }
    }
}

@Composable
private fun ApiKeyGroup(
    state: SettingsViewModel.State,
    callbacks: SettingsCallbacks,
    focusKey: Boolean,
) {
    var visible by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    // 切换可见性会重建输入框以刷新 Password 语义，重建后把焦点还回 Key 输入框。
    var restoreFocus by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    // 只有从"缺少 Key"路径进入时才自动聚焦，普通进入设置页不抢占输入焦点。
    LaunchedEffect(focusKey) {
        if (focusKey) focusRequester.requestFocus()
    }

    LaunchedEffect(visible) {
        if (restoreFocus) focusRequester.requestFocus()
    }

    SettingsGroup(title = stringResource(R.string.settings_section_api_key)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Compose foundation 1.12.1 的 CoreTextFieldSemanticsModifier 在更新时未写回
            // isPassword，复用节点无法清除 Password 语义。按 visible 重建输入框可强制重新创建
            // 语义节点；value 由外部状态持有，重建不会丢失文本。
            key(visible) {
                OutlinedTextField(
                    value = state.apiKeyDraft,
                    onValueChange = callbacks.onKeyChange,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                    label = { Text(stringResource(R.string.settings_api_key_label)) },
                    singleLine = true,
                    // 校验期间禁用编辑，避免用户在等待结果时改写草稿导致结果错配。
                    enabled = state.keyStatus != KeyStatus.Validating,
                    visualTransformation = if (visible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                )
            }
            // 放在输入框外而非 trailingIcon：文本域的语义会合并子树，测试标签与点击
            // 会落到文本域本身，导致"显示切换"无法被独立操作。
            IconButton(
                onClick = {
                    restoreFocus = true
                    visible = !visible
                },
                modifier = Modifier.testTag("toggle_key_visibility"),
            ) {
                Icon(
                    imageVector = if (visible) {
                        Icons.Filled.VisibilityOff
                    } else {
                        Icons.Filled.Visibility
                    },
                    contentDescription = stringResource(
                        if (visible) R.string.settings_key_hide else R.string.settings_key_show,
                    ),
                )
            }
        }

        val masked = state.savedKeyMasked
        if (masked != null) {
            Text(
                text = stringResource(R.string.settings_key_saved, masked),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = callbacks.onSaveKey,
                enabled = state.keyStatus != KeyStatus.Validating,
                modifier = Modifier.testTag("save_key"),
            ) {
                Text(
                    if (state.keyStatus == KeyStatus.Validating) {
                        stringResource(R.string.settings_key_saving)
                    } else {
                        stringResource(R.string.settings_key_save_and_validate)
                    },
                )
            }
            Text(
                text = state.keyStatus.label(),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            state.quota?.let { quota ->
                val unknown = stringResource(R.string.quota_unknown)
                Text(
                    text = stringResource(
                        R.string.quota_summary,
                        quota.freeRemaining?.toString() ?: unknown,
                        quota.paidRemaining?.toString() ?: unknown,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
            }
            TextButton(
                onClick = { uriHandler.openUri(GET_KEY_URL) },
                modifier = Modifier.testTag("get_key_link"),
            ) {
                Text(stringResource(R.string.settings_get_key_link))
            }
        }
    }
}

@Composable
private fun DownloadGroup(
    state: SettingsViewModel.State,
    callbacks: SettingsCallbacks,
) {
    var qualityExpanded by remember { mutableStateOf(false) }

    SettingsGroup(title = stringResource(R.string.settings_section_download)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.settings_default_quality),
                style = MaterialTheme.typography.bodyLarge,
            )
            Box {
                OutlinedButton(
                    onClick = { qualityExpanded = true },
                    modifier = Modifier.testTag("default_quality"),
                ) {
                    Text(state.defaultQuality.label)
                }
                DropdownMenu(
                    expanded = qualityExpanded,
                    onDismissRequest = { qualityExpanded = false },
                ) {
                    MusicQuality.entries.forEach { quality ->
                        DropdownMenuItem(
                            text = { Text(quality.label) },
                            onClick = {
                                qualityExpanded = false
                                callbacks.onDefaultQualityChange(quality)
                            },
                        )
                    }
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.settings_concurrency),
                style = MaterialTheme.typography.bodyLarge,
            )
            Slider(
                value = state.concurrency.toFloat(),
                onValueChange = { callbacks.onConcurrencyChange(it.roundToInt()) },
                valueRange = MIN_CONCURRENCY..MAX_CONCURRENCY,
                steps = CONCURRENCY_STEPS,
                modifier = Modifier
                    .weight(1f)
                    .testTag("concurrency_slider"),
            )
            Text(state.concurrency.toString())
        }
    }
}

@Composable
private fun AppearanceGroup(
    state: SettingsViewModel.State,
    callbacks: SettingsCallbacks,
) {
    SettingsGroup(title = stringResource(R.string.settings_section_appearance)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { mode ->
                FilterChip(
                    selected = state.themeMode == mode,
                    onClick = { callbacks.onThemeModeChange(mode) },
                    label = { Text(mode.themeLabel()) },
                    modifier = Modifier.testTag("theme_${mode.name.lowercase()}"),
                )
            }
        }
    }
}

@Composable
private fun HistoryGroup(callbacks: SettingsCallbacks) {
    var showClearDialog by remember { mutableStateOf(false) }

    SettingsGroup(title = stringResource(R.string.settings_section_history)) {
        OutlinedButton(
            onClick = { showClearDialog = true },
            modifier = Modifier.testTag("clear_history"),
        ) {
            Text(stringResource(R.string.action_clear_history))
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
private fun AboutGroup() {
    SettingsGroup(title = stringResource(R.string.settings_section_about)) {
        Text(
            text = stringResource(R.string.settings_about_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(R.string.settings_about_api, API_BASE_URL),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun KeyStatus.label(): String = when (this) {
    KeyStatus.Idle -> stringResource(R.string.settings_key_status_idle)
    KeyStatus.Validating -> stringResource(R.string.settings_key_status_validating)
    KeyStatus.Valid -> stringResource(R.string.settings_key_status_valid)
    is KeyStatus.Invalid -> message?.takeIf { it.isNotBlank() }
        ?: messageRes?.let { stringResource(it) }.orEmpty()
    is KeyStatus.NetworkError -> stringResource(R.string.settings_key_status_warning, message)
}

@Composable
private fun ThemeMode.themeLabel(): String = when (this) {
    ThemeMode.System -> stringResource(R.string.settings_theme_system)
    ThemeMode.Light -> stringResource(R.string.settings_theme_light)
    ThemeMode.Dark -> stringResource(R.string.settings_theme_dark)
}

private const val GET_KEY_URL = "https://api.chksz.com/login"

private const val API_BASE_URL = "https://api.chksz.com"

private const val MIN_CONCURRENCY = 1f

private const val MAX_CONCURRENCY = 5f

private const val CONCURRENCY_STEPS = 3
