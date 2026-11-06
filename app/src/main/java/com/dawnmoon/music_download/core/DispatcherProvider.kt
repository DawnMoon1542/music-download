package com.dawnmoon.music_download.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** 协程调度器的集中来源；测试中可替换为可控调度器。 */
interface DispatcherProvider {
    val main: CoroutineDispatcher
    val default: CoroutineDispatcher
    val io: CoroutineDispatcher
}

/** 生产环境实现的 [DispatcherProvider]。 */
class DefaultDispatcherProvider : DispatcherProvider {
    override val main: CoroutineDispatcher get() = Dispatchers.Main

    override val default: CoroutineDispatcher get() = Dispatchers.Default

    override val io: CoroutineDispatcher get() = Dispatchers.IO
}
