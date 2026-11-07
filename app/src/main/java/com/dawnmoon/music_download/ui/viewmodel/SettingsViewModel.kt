package com.dawnmoon.music_download.ui.viewmodel

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dawnmoon.music_download.R
import com.dawnmoon.music_download.data.repository.AppSettings
import com.dawnmoon.music_download.data.repository.KeyValidator
import com.dawnmoon.music_download.data.repository.QuotaStore
import com.dawnmoon.music_download.data.repository.TaskRepository
import com.dawnmoon.music_download.domain.model.MusicQuality
import com.dawnmoon.music_download.domain.model.Quota
import com.dawnmoon.music_download.domain.model.ThemeMode
import com.dawnmoon.music_download.ui.history.HistoryActions
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** API Key 的本地与联网校验状态。 */
sealed interface KeyStatus {

    /** 尚无校验结果；用户尚未提交或已修改输入。 */
    data object Idle : KeyStatus

    /** 校验请求进行中；此期间禁止重复提交。 */
    data object Validating : KeyStatus

    /** 服务端确认 Key 有效。 */
    data object Valid : KeyStatus

    /**
     * Key 确定无效，不应保存。
     *
     * 本地格式错误没有服务端原文，用 [messageRes] 交给界面取资源；服务端返回的无效原因
     * 放在 [message] 中，两者互斥。
     */
    data class Invalid(
        val message: String? = null,
        @StringRes val messageRes: Int? = null,
    ) : KeyStatus

    /** 无法判定 Key 是否有效；Key 已保存，界面展示警告。 */
    data class NetworkError(val message: String) : KeyStatus
}

/** 由 [SettingsViewModel] 推送给界面的一次性事件。 */
sealed interface SettingsEvent {

    /** 历史已全部清空。 */
    data object HistoryCleared : SettingsEvent

    /** 部分记录因文件或记录删除失败而保留。 */
    data class HistoryClearFailed(val retainedCount: Int) : SettingsEvent
}

/**
 * 设置页的状态编排。
 *
 * 音质、并发数与主题只读取 [AppSettings] 的 Flow，写入后由持久化层回流，避免界面状态
 * 与 DataStore 出现两份互相竞争的事实。Key 校验先做本地格式检查，任何本地非法的输入
 * 都不会消耗一次联网额度。
 *
 * 清空历史通过注入的 [HistoryActions] 完成，与工作台历史 Tab 共用同一实现。
 */
class SettingsViewModel(
    private val settings: AppSettings,
    private val keyValidator: KeyValidator,
    private val quotaStore: QuotaStore,
    private val tasks: TaskRepository,
    private val historyActions: HistoryActions,
) : ViewModel() {

    /** 设置页的完整界面状态。 */
    data class State(
        val apiKeyDraft: String,
        val savedKeyMasked: String?,
        val keyStatus: KeyStatus,
        val defaultQuality: MusicQuality,
        val concurrency: Int,
        val themeMode: ThemeMode,
        val quota: Quota?,
    )

    private val apiKeyDraft = MutableStateFlow("")
    private val keyStatus = MutableStateFlow<KeyStatus>(KeyStatus.Idle)
    private val eventChannel = Channel<SettingsEvent>(Channel.BUFFERED)

    /** 一次性事件；界面应在进入组合时开始收集。 */
    val events: Flow<SettingsEvent> = eventChannel.receiveAsFlow()

    private val persisted = combine(
        settings.apiKey,
        settings.defaultQuality,
        settings.maxConcurrentDownloads,
        settings.themeMode,
    ) { apiKey, defaultQuality, concurrency, themeMode ->
        PersistedSettings(apiKey, defaultQuality, concurrency, themeMode)
    }

    val state: StateFlow<State> = combine(
        persisted,
        quotaStore.quota,
        apiKeyDraft,
        keyStatus,
    ) { persistedSettings, quota, draft, status ->
        State(
            apiKeyDraft = draft,
            // 已保存的 Key 只以掩码形式展示，明文永不回填输入框。
            savedKeyMasked = persistedSettings.apiKey?.let(::maskKey),
            keyStatus = status,
            defaultQuality = persistedSettings.defaultQuality,
            concurrency = persistedSettings.concurrency,
            themeMode = persistedSettings.themeMode,
            quota = quota,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = State(
            apiKeyDraft = "",
            savedKeyMasked = null,
            keyStatus = KeyStatus.Idle,
            defaultQuality = MusicQuality.default,
            concurrency = DEFAULT_CONCURRENCY,
            themeMode = ThemeMode.System,
            quota = null,
        ),
    )

    fun onKeyChange(value: String) {
        apiKeyDraft.value = value
        // 修改输入后旧的校验结论不再代表当前内容；校验中保持 Validating 以保证按钮禁用。
        if (keyStatus.value !is KeyStatus.Validating) {
            keyStatus.value = KeyStatus.Idle
        }
    }

    /**
     * 保存并校验当前草稿。
     *
     * 本地格式非法时直接拒绝，不发起联网校验（每次校验都会消耗一次 API 额度）。
     * `Indeterminate` 表示无法判断（网络、额度、限流），此时仍保存 Key，避免用户因临时
     * 故障丢失输入；界面据此展示警告而不是"无效"。
     */
    fun onSaveKey() {
        if (keyStatus.value is KeyStatus.Validating) return
        val draft = apiKeyDraft.value
        if (!API_KEY_PATTERN.matches(draft)) {
            keyStatus.value = KeyStatus.Invalid(messageRes = R.string.settings_key_invalid_format)
            return
        }
        keyStatus.value = KeyStatus.Validating
        viewModelScope.launch {
            val result = keyValidator.validate(draft)
            // 校验期间输入若已变化，旧结果不再对应当前草稿：既不保存旧 Key，也不清空新输入。
            if (apiKeyDraft.value != draft) {
                keyStatus.value = KeyStatus.Idle
                return@launch
            }
            when (result) {
                is KeyValidator.Result.Valid -> {
                    settings.setApiKey(draft)
                    apiKeyDraft.value = ""
                    keyStatus.value = KeyStatus.Valid
                }

                is KeyValidator.Result.InvalidKey -> {
                    keyStatus.value = KeyStatus.Invalid(result.message)
                }

                is KeyValidator.Result.Indeterminate -> {
                    settings.setApiKey(draft)
                    apiKeyDraft.value = ""
                    keyStatus.value = KeyStatus.NetworkError(result.message)
                }
            }
        }
    }

    fun onDefaultQualityChange(quality: MusicQuality) {
        viewModelScope.launch { settings.setDefaultQuality(quality) }
    }

    fun onConcurrencyChange(value: Int) {
        val clamped = value.coerceIn(MIN_CONCURRENCY, MAX_CONCURRENCY)
        viewModelScope.launch { settings.setMaxConcurrentDownloads(clamped) }
    }

    fun onThemeModeChange(mode: ThemeMode) {
        viewModelScope.launch { settings.setThemeMode(mode) }
    }

    /** 清空历史；保留的条目数量通过一次性事件告知界面。 */
    fun onClearHistory(deleteFiles: Boolean) {
        viewModelScope.launch {
            val completed = tasks.observeCompleted().first()
            val retainedCount = historyActions.clearHistory(completed, deleteFiles)
            eventChannel.trySend(
                if (retainedCount > 0) {
                    SettingsEvent.HistoryClearFailed(retainedCount)
                } else {
                    SettingsEvent.HistoryCleared
                },
            )
        }
    }

    private data class PersistedSettings(
        val apiKey: String?,
        val defaultQuality: MusicQuality,
        val concurrency: Int,
        val themeMode: ThemeMode,
    )

    private companion object {
        /**
         * 与 [com.dawnmoon.music_download.data.repository.DefaultKeyValidator] 的本地检查保持一致。
         *
         * 校验器仍会再检查一次；这里的预检是为了在明显非法时避免消耗一次联网额度。
         */
        val API_KEY_PATTERN = Regex("^chksz_[A-Za-z0-9._~-]+$")

        const val MIN_CONCURRENCY = 1
        const val MAX_CONCURRENCY = 5
        const val DEFAULT_CONCURRENCY = 3
    }
}

/** 只暴露 Key 的前缀与固定长度掩码，避免任何字符从掩码中还原。 */
private fun maskKey(key: String): String {
    val prefix = if (key.startsWith("chksz_")) "chksz_" else ""
    return prefix + "•".repeat(8)
}
