package com.dawnmoon.music_download.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.WarningAmber
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
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.dawnmoon.music_download.BuildConfig
import com.dawnmoon.music_download.R
import com.dawnmoon.music_download.data.remote.ApiBaseUrl
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
    val onUrlChange: (String) -> Unit = {},
    val onKeyChange: (String) -> Unit = {},
    val onSaveKey: () -> Unit = {},
    val onDefaultQualityChange: (MusicQuality) -> Unit = {},
    val onConcurrencyChange: (Int) -> Unit = {},
    val onThemeModeChange: (ThemeMode) -> Unit = {},
    val onClearHistory: (Boolean) -> Unit = {},
)

/**
 * 设置页：API 配置、下载、外观、历史与关于五个分组，每组是一张卡片。
 *
 * @param focusKey 为 true 时进入页面即聚焦凭据输入框；工作台因缺少凭据跳转时使用。
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
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            LargeTopAppBar(
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
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            ApiKeyGroup(state = state, callbacks = callbacks, focusKey = focusKey)
            DownloadGroup(state = state, callbacks = callbacks)
            AppearanceGroup(state = state, callbacks = callbacks)
            HistoryGroup(callbacks = callbacks)
            AboutGroup(state = state)
            Spacer(Modifier.size(16.dp))
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
    val keyFocusRequester = remember { FocusRequester() }
    val validating = state.keyStatus == KeyStatus.Validating

    // 已保存的地址异步回填到草稿，进入页面时草稿可能尚为空，因此固定聚焦 Key 输入框。
    LaunchedEffect(focusKey) {
        if (focusKey) keyFocusRequester.requestFocus()
    }

    LaunchedEffect(visible) {
        if (restoreFocus) keyFocusRequester.requestFocus()
    }

    SettingsGroup(title = stringResource(R.string.settings_section_api_key)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_api_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = state.apiUrlDraft,
                onValueChange = callbacks.onUrlChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("api_url_input"),
                label = { Text(stringResource(R.string.settings_api_url_label)) },
                placeholder = { Text(stringResource(R.string.settings_api_url_placeholder)) },
                leadingIcon = { Icon(Icons.Outlined.Link, contentDescription = null) },
                singleLine = true,
                enabled = !validating,
                shape = MaterialTheme.shapes.medium,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                ),
            )
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
                            .focusRequester(keyFocusRequester)
                            .testTag("api_key_input"),
                        label = { Text(stringResource(R.string.settings_api_key_label)) },
                        leadingIcon = { Icon(Icons.Outlined.Key, contentDescription = null) },
                        singleLine = true,
                        // 校验期间禁用编辑，避免用户在等待结果时改写草稿导致结果错配。
                        enabled = !validating,
                        shape = MaterialTheme.shapes.medium,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
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
                        imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = stringResource(
                            if (visible) R.string.settings_key_hide else R.string.settings_key_show,
                        ),
                    )
                }
            }

            val savedUrl = state.savedApiUrl
            val masked = state.savedKeyMasked
            if (savedUrl != null && masked != null) {
                Text(
                    text = stringResource(R.string.settings_key_saved, savedUrl, masked),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = callbacks.onSaveKey,
                    enabled = !validating,
                    modifier = Modifier.testTag("save_key"),
                ) {
                    AnimatedContent(
                        targetState = validating,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "save_key_content",
                    ) { busy ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (busy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.settings_key_saving))
                            } else {
                                Text(stringResource(R.string.settings_key_save_and_validate))
                            }
                        }
                    }
                }
                KeyStatusLabel(state.keyStatus)
            }

            val quota = state.quota
            // 网站入口来自用户填写的地址，应用内不再内置任何服务域名。
            val siteUrl = ApiBaseUrl.normalize(state.apiUrlDraft) ?: state.savedApiUrl
            AnimatedVisibility(visible = quota != null || siteUrl != null) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (quota != null) {
                            val unknown = stringResource(R.string.quota_unknown)
                            Text(
                                text = stringResource(
                                    R.string.quota_summary,
                                    quota.freeRemaining?.toString() ?: unknown,
                                    quota.paidRemaining?.toString() ?: unknown,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        if (siteUrl != null) {
                            TextButton(
                                onClick = { uriHandler.openUri(siteUrl) },
                                modifier = Modifier.testTag("get_key_link"),
                            ) {
                                Text(stringResource(R.string.settings_get_key_link))
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 校验结果：图标与颜色区分通过、失败与警告，状态切换时淡入淡出。 */
@Composable
private fun KeyStatusLabel(status: KeyStatus) {
    AnimatedContent(
        targetState = status,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "key_status",
    ) { current ->
        val colors = MaterialTheme.colorScheme
        val (icon, tint) = when (current) {
            KeyStatus.Valid -> Icons.Outlined.CheckCircle to colors.primary
            is KeyStatus.Invalid -> Icons.Outlined.ErrorOutline to colors.error
            is KeyStatus.NetworkError -> Icons.Outlined.WarningAmber to colors.tertiary
            KeyStatus.Idle, KeyStatus.Validating -> null to colors.onSurfaceVariant
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            }
            Text(
                text = current.label(),
                style = MaterialTheme.typography.bodyMedium,
                color = if (icon != null) tint else colors.onSurfaceVariant,
            )
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
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_default_quality)) },
            trailingContent = {
                Box {
                    OutlinedButton(
                        onClick = { qualityExpanded = true },
                        modifier = Modifier.testTag("default_quality"),
                    ) {
                        Text(state.defaultQuality.label)
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
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
            },
            colors = groupListItemColors(),
        )
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_concurrency)) },
            supportingContent = {
                Slider(
                    value = state.concurrency.toFloat(),
                    onValueChange = { callbacks.onConcurrencyChange(it.roundToInt()) },
                    valueRange = MIN_CONCURRENCY..MAX_CONCURRENCY,
                    steps = CONCURRENCY_STEPS,
                    modifier = Modifier.testTag("concurrency_slider"),
                )
            },
            trailingContent = {
                AnimatedContent(
                    targetState = state.concurrency,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "concurrency_value",
                ) { value ->
                    Text(
                        text = value.toString(),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            },
            colors = groupListItemColors(),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceGroup(
    state: SettingsViewModel.State,
    callbacks: SettingsCallbacks,
) {
    SettingsGroup(title = stringResource(R.string.settings_section_appearance)) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            ThemeMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = state.themeMode == mode,
                    onClick = { callbacks.onThemeModeChange(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size),
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
        ListItem(
            headlineContent = { Text(stringResource(R.string.action_clear_history)) },
            leadingContent = { Icon(Icons.Outlined.DeleteSweep, contentDescription = null) },
            colors = groupListItemColors(),
            modifier = Modifier
                .clickable { showClearDialog = true }
                .testTag("clear_history"),
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
private fun AboutGroup(state: SettingsViewModel.State) {
    SettingsGroup(title = stringResource(R.string.settings_section_about)) {
        AboutItem(
            icon = Icons.Outlined.Info,
            label = stringResource(R.string.settings_about_version_label),
            value = BuildConfig.VERSION_NAME,
        )
        AboutItem(
            icon = Icons.Outlined.Link,
            label = stringResource(R.string.settings_about_api_label),
            value = state.savedApiUrl ?: stringResource(R.string.settings_api_not_configured),
        )
    }
}

@Composable
private fun AboutItem(
    icon: ImageVector,
    label: String,
    value: String,
) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = { Text(value) },
        leadingContent = { Icon(icon, contentDescription = null) },
        colors = groupListItemColors(),
    )
}

/** 分组：主色小标题加一张容器色卡片，与 M3 设置页的分组样式一致。 */
@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
            content = content,
        )
    }
}

@Composable
private fun groupListItemColors() = ListItemDefaults.colors(containerColor = Color.Transparent)

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

private const val MIN_CONCURRENCY = 1f

private const val MAX_CONCURRENCY = 5f

private const val CONCURRENCY_STEPS = 3
