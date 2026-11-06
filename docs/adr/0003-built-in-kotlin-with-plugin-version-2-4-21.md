# 采用 AGP 9 内置 Kotlin，插件版本 2.4.21，KSP 2.3.13

AGP 9.2.1 默认启用内置 Kotlin，其自带编译器为 2.2.10，且与 `org.jetbrains.kotlin.android` 插件以及 AGP 9 新 DSL 互斥；开启内置 Kotlin 是官方推荐路径。但当前 AndroidX、Compose 1.12 与 kotlinx-serialization 1.12 的 class 元数据已达到 2.4，2.2.10 编译器无法读取。实测把 `org.jetbrains.kotlin.plugin.compose` 与 `org.jetbrains.kotlin.plugin.serialization` 固定为 2.4.21 后，这两者带入的 Kotlin Gradle 插件会把实际编译版本提升到 2.4.21，构建、单元测试与真机运行均通过，无需再强制 `kotlin-stdlib`。KSP 2.2.x 使用 `kotlin.sourceSets` 注入生成源码，与内置 Kotlin 冲突，因此 KSP 固定为解耦版本号的 2.3.13。

## Consequences

- 实际编译器版本由这两个 Compose/序列化插件的版本决定，而不是 AGP 的 POM 声明；升级 AGP 或插件时必须重新验证组合并运行 `assembleDebug` 与单元测试。
- 不再需要 `android.builtInKotlin=false` 或 `android.newDsl=false`；两条 opt-out 在 AGP 10 移除。
- 如果将来 AndroidX 元数据继续升级，需要同步提升这两个插件版本。
