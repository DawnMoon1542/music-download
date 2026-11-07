package com.dawnmoon.music_download.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.dawnmoon.music_download.AppDependencies

/**
 * 页面 ViewModel 的统一工厂。
 *
 * 各页面 ViewModel 由后续 Task 接入；工厂持有进程级依赖容器，
 * 避免每个页面各自构造数据层实例。
 */
class AppViewModelFactory(
    private val dependencies: AppDependencies,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
