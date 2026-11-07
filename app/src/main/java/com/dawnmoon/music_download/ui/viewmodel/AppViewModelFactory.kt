package com.dawnmoon.music_download.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.CreationExtras
import com.dawnmoon.music_download.AppDependencies
import com.dawnmoon.music_download.core.AppClock
import com.dawnmoon.music_download.core.SystemAppClock

/**
 * 页面 ViewModel 的统一工厂。
 *
 * 工厂持有进程级依赖容器；[appContext] 供需要读取资源的 ViewModel 使用。通过
 * [ViewModelProvider] 创建时也可从 [CreationExtras] 的 [APPLICATION_KEY] 取得 Application，
 * 这样调用方不必额外传入上下文。
 */
class AppViewModelFactory(
    private val dependencies: AppDependencies,
    private val appContext: Context? = null,
    private val clock: AppClock = SystemAppClock(),
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        create(modelClass, appContext)

    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
        create(modelClass, appContext ?: extras[APPLICATION_KEY])

    private fun <T : ViewModel> create(modelClass: Class<T>, context: Context?): T {
        if (modelClass.isAssignableFrom(WorkbenchViewModel::class.java)) {
            val requiredContext = requireNotNull(context) {
                "WorkbenchViewModel requires a Context; pass it to AppViewModelFactory " +
                    "or provide APPLICATION_KEY in CreationExtras"
            }
            @Suppress("UNCHECKED_CAST")
            return WorkbenchViewModel(
                repo = dependencies.repository,
                settings = dependencies.settings,
                engine = dependencies.downloadController,
                fileStore = dependencies.fileStore,
                quotaStore = dependencies.quotaStore,
                appContext = requiredContext,
                clock = clock,
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
