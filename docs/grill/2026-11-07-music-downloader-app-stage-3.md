# 音乐下载器 Stage 3 设计

> **重要变更与发布操作：**
> - 类别：系统权限（`POST_NOTIFICATIONS` 运行时请求）、入口（系统分享）。
> - 影响：首次发布。通知权限被拒绝时下载照常运行，只是没有通知；不涉及数据迁移、服务中断或公告。
> - 更新代码之外的操作：无。
> - 评估状态：已评估。

**需求索引：** `docs/brainstorming/2026-11-07-music-downloader-app.md`
**Stage：** 3 / 3
**依赖：** Stage 2

## Stage 职责

提供全部面向用户的界面与交互：

- 单页工作台：多行链接输入、音质选择、额度标签、进行中/已完成两个 Tab、任务卡片操作。
- 历史搜索、播放、分享、重新下载、删除（记录 / 记录与文件）、清空历史。
- 设置：API Key（保存时校验）、默认音质、并发数、主题模式、清空历史、关于。
- Material 3 动态取色与深浅色主题。
- 通知权限请求流程。
- 系统分享入口的静默入队。

## 最终架构位置

```
com.dawnmoon.music_download
├── ui/
│   ├── theme/            Color、Type、Shape、MusicDownloadTheme(themeMode)
│   ├── MainActivity.kt   enableEdgeToEdge + setContent
│   ├── ShareActivity.kt  透明主题，接收 ACTION_SEND，入队后 finish
│   ├── AppRoot.kt        路由（工作台 / 设置）+ BackHandler
│   ├── workbench/        WorkbenchScreen、TaskCard、EmptyState、QuotaLabel、QualitySelector
│   ├── history/          HistoryList、HistorySearch、ClearHistoryDialog
│   ├── settings/         SettingsScreen、ApiKeySection、QualitySection、 AppearanceSection
│   ├── common/           FileInfoFormatter、SnackbarHost、ConfirmDialog
│   └── viewmodel/        WorkbenchViewModel、SettingsViewModel、AppViewModelFactory
└── MusicDownloadApp.kt   持有 AppContainer，创建通知渠道
```

路由使用单一 `AppRoute` 枚举（`Workbench`、`Settings`），由 `rememberSaveable` 保存，`BackHandler` 在设置页返回工作台；不引入 Navigation Compose。

## 接口定义

### ViewModel

```kotlin
data class WorkbenchUiState(
    val input: String,
    val selectedQuality: MusicQuality,
    val quota: Quota?,
    val inProgress: List<TaskUiModel>,
    val completed: List<TaskUiModel>,
    val selectedTab: WorkbenchTab,          // InProgress / Completed
    val historyQuery: String,
    val isEnqueuing: Boolean,
    val engine: EngineSnapshot?,
)

data class TaskUiModel(
    val id: Long,
    val songId: Long?,
    val sourceUrl: String,
    val mediaStoreId: Long?,
    val title: String?,            // null 表示尚未解析
    val artist: String?,
    val artworkUrl: String?,
    val qualityLabel: String,      // 实际音质优先，未解析时显示请求音质
    val statusLabel: String,
    val progress: Float?,          // null = 未知或非下载中
    val bytesDownloaded: Long?,
    val totalBytes: Long?,
    val speedBytesPerSecond: Long?,
    val failureKind: String?,
    val failureMessage: String?,
    val fileName: String?,
    val completedAt: Long?,
    val canPause: Boolean, val canResume: Boolean, val canCancel: Boolean, val canRetry: Boolean,
)

sealed interface WorkbenchEvent {
    data class Snackbar(val message: String) : WorkbenchEvent
    data class OpenSettings(val focusKey: Boolean) : WorkbenchEvent
    data object RequestNotificationPermission : WorkbenchEvent   // 不阻塞下载启动
}

class WorkbenchViewModel(
    private val repo: TaskRepository,
    private val settings: AppSettings,
    private val engine: DownloadController,
    private val fileStore: TaskFileStore,
    private val quotaStore: QuotaStore,
    private val appContext: Context,
    private val clock: AppClock,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : ViewModel() {
    val state: StateFlow<WorkbenchUiState>
    fun onInputChange(value: String)
    fun onQualityChange(quality: MusicQuality)
    fun onTabChange(tab: WorkbenchTab)
    fun onHistoryQueryChange(query: String)
    fun onEnqueue()
    fun onPause(id: Long); fun onResume(id: Long); fun onCancel(id: Long); fun onRetry(id: Long)
    fun onPauseAll(); fun onResumeAll(); fun onCancelAll(); fun onRetryAllFailed()
    fun onOpen(id: Long); fun onShare(id: Long)
    fun onRedownload(id: Long, quality: MusicQuality)
    fun onDelete(id: Long, deleteFile: Boolean)
    fun onClearHistory(deleteFiles: Boolean)
    val events: Flow<WorkbenchEvent>
}

class SettingsViewModel(
    private val settings: AppSettings,
    private val keyValidator: KeyValidator,
    private val quotaStore: QuotaStore,
    private val tasks: TaskRepository,
    private val fileStore: TaskFileStore,
) : ViewModel() {
    data class State(
        val apiKeyDraft: String,
        val savedKeyMasked: String?,
        val keyStatus: KeyStatus,       // Idle / Validating / Valid / Invalid(message) / NetworkError
        val defaultQuality: MusicQuality,
        val concurrency: Int,
        val themeMode: ThemeMode,
        val quota: Quota?,
    )
    val state: StateFlow<State>
    fun onKeyChange(value: String)
    fun onSaveKey()
    fun onDefaultQualityChange(q: MusicQuality)
    fun onConcurrencyChange(value: Int)
    fun onThemeModeChange(mode: ThemeMode)
}
```

- `onEnqueue`：总是调用 `repo.enqueue(input, quality)`（由仓库判断 Key 是否缺失），按 `EnqueueOutcome` 处理：
  - `Enqueued` 且 `created` 非空 → `engine.start` + `engine.requestDownload(created)`，提示"已加入 n 个任务"；`duplicates > 0` 时附加"跳过 m 个已在队列中的歌曲"；`failures` 非空时附加"k 个链接无法识别"；清空输入框并切到"进行中"。
  - `Enqueued` 且 `created` 为空：按 `duplicates` 与 `failures` 分支提示（全部重复 / 全部失败 / 两者都有），不统一说成"已在队列中"。
  - `MissingApiKey` → 发 `OpenSettings(focusKey=true)`。
  - `NoLinks` → "未找到网易云歌曲链接"。
  - 入队期间按钮显示进度并禁用，防止重复提交。
- 下载前检查通知权限（Android 13+）：未请求过 → 发 `RequestNotificationPermission`，但**不等待**权限结果，`engine.start/requestDownload` 立即执行；权限结果只影响通知可见性。请求后写 `notificationAsked=true`，不重复请求。
- 打开/分享：用 `mediaStoreId` 构造 `content://media/external/audio/media/<id>`；`ACTION_VIEW` 与 `ACTION_SEND` 都带 `FLAG_GRANT_READ_URI_PERMISSION` 与 `FLAG_ACTIVITY_NEW_TASK`（从 `appContext` 启动），`ACTION_SEND` 同时设置 `clipData` 与 `EXTRA_STREAM`（`audio/*`）。文件不存在（`mediaStoreId` 为空或该行已删）时提示"文件已不存在"并引导"仅删记录"。
- 删除记录与文件：先调用 `TaskFileStore.deleteById(mediaStoreId)`；返回 `true`（已删除或不存在）才调用 `repo.deleteRecord`，返回 `false` 时保留记录并提示。
- 清空历史：两种语义都由 Stage 3 编排——"仅清空记录"直接 `repo.clearCompleted()`；"同时删除文件"逐条调用 `TaskFileStore.deleteById`，只对返回 true 的 id 调用 `repo.deleteRecords(ids)`，失败的记录保留并提示数量。设置页与工作台历史 Tab 共用同一 `HistoryActions` 实现。

### 屏幕结构

**工作台**

```
TopAppBar: 音乐下载          [额度标签] [⋮] [设置]
⋮ 菜单: 全部暂停 / 全部继续 / 全部重试失败 / 全部取消（确认弹窗）
────────────────────────────────────────
链接卡片
  多行输入（3-6 行）: 粘贴网易云链接或分享文案
  [音质: 极高 ▾]                 [解析并下载]
────────────────────────────────────────
TabRow: 进行中 (3)     已完成 (12)
────────────────────────────────────────
任务卡片（进行中）
  [封面 56dp]  曲名 / 艺术家
               [极高] 下载中 42%  ·  12.3/21.8 MB  ·  2.1 MB/s
               ▓▓▓▓▓▓▓▓▓░░░░░░░
                                        [暂停] [取消]
任务卡片（排队/失败/暂停/取消）按状态显示不同操作
────────────────────────────────────────
空状态: 图标 + "暂无下载任务，粘贴链接开始"
```

- 额度标签：`免费 37 · 付费 120`；低于 10 时用 `MaterialTheme.colorScheme.error`；无数据时不显示。
- 音质选择：`ExposedDropdownMenuBox`，列出 7 个音质，初始值为设置中的默认音质；本次选择不回写默认值。
- 任务卡片动作：下载中/解析中 → 暂停、取消；排队/等待 → 取消；暂停 → 继续、取消；失败 → 重试、取消；已取消 → 删除记录。
- 失败卡片显示本地化错误文案，点击展开显示 `failureMessage` 原文。
- 取消后任务留在"进行中"Tab（用户选择保留），带"已取消"标记。

**已完成 Tab**

顶部搜索框（按曲名/艺术家/文件名过滤），右上角溢出菜单"清空历史"。每项：

```
[封面] 曲名 / 艺术家
       [极高]  9.1 MB · 11月7日 12:30
       [播放] [分享] [⋮]
⋮ 菜单: 按音质重新下载 ▸ / 删除记录 / 删除记录与文件
```

"按音质重新下载"弹出音质单选后新建任务（即使历史中已有该歌曲）。

**设置**

```
TopAppBar: 设置 [返回]
API Key
  [chksz_••••••••••]  [眼睛]
  [保存并验证]   状态：验证通过 / 无效 Key: xxx
  免费额度 37 · 付费额度 120    获取 Key ↗ (https://api.chksz.com/login)
下载
  默认音质 [极高 ▾]
  同时下载数 [3]  ———●————  (1-5，联动当前引擎)
外观
  主题 [跟随系统 / 浅色 / 深色]
历史
  [清空历史]
关于
  版本 1.0 · 接口 https://api.chksz.com
```

- Key 输入使用 `PasswordVisualTransformation` + 显示切换；已保存的 Key 只显示掩码，不把明文回填输入框；保存成功后清空草稿。
- 保存并验证：先本地格式校验（`chksz_` 前缀 + URL 安全字符），再 `KeyValidator.validate`（经 `ApiCaller` 限速，消耗 1 次额度）。`Valid` → 保存并显示额度；`InvalidKey` → 不保存，显示 msg；`Indeterminate`（网络/额度/限流）→ 仍保存，显示警告，避免用户因临时网络问题丢失输入。
- 主题切换即时生效：`MainActivity`/`AppRoot` 收集 `settings.themeMode` 传给 `MusicDownloadTheme`；复用 Stage 1 的 `ThemeMode` 枚举，不重复定义。
- 清空历史弹窗：单选"仅清空记录" / "同时删除文件"；选后者时逐条删除 MediaStore 文件，失败的条目保留并提示数量。

### 主题

```kotlin
enum class ThemeMode { System, Light, Dark }

@Composable fun MusicDownloadTheme(themeMode: ThemeMode, content: @Composable () -> Unit)
```

- Android 12+ 使用 `dynamicLightColorScheme` / `dynamicDarkColorScheme`；低于 12 使用内置浅/深配色，种子色 `#5B5BD6`。
- `enableEdgeToEdge()`；状态栏与导航栏图标颜色随主题（`SystemBarStyle.auto`）。
- 形状：卡片 `RoundedCornerShape(16.dp)`，封面 `12.dp`；页面水平内边距 16dp，卡片间距 8dp。
- 类型：M3 默认字体；曲名 `titleMedium`，次要信息 `bodySmall` 且 `onSurfaceVariant`。
- 动效：进度条 300ms 平滑，Tab 切换默认动画；不使用夸张动画。

### 分享入口

```kotlin
class ShareActivity : ComponentActivity() {
    // android:theme 使用透明、无动画主题；不 setContent
}
```

- 接收 `ACTION_SEND` + `text/plain` 的 `EXTRA_TEXT`。
- 首次下载且 Android 13+ 未请求过通知权限时，ShareActivity 发起 `POST_NOTIFICATIONS` 请求但不等待结果，入队与引擎启动立即执行。
- 已配置 Key：`repo.enqueue(text, settings.defaultQuality.first())` → `engine.start/requestDownload` → `Toast("已加入下载队列：n 个")`（重复或无法识别时给出相应提示）→ `finish()`。
- 未配置 Key：打开 `MainActivity` 并在设置页提示"请先填写 API Key"。
- 分享入口不承载批量（系统分享只能带一条文本），但仍使用同一个提取器，若文本中恰好包含多条链接则全部入队。

## 数据流与数据安全

- UI 只通过 ViewModel 调用仓库与引擎；任务列表来自 DAO Flow，进度节流后的数值由 DAO 更新驱动。
- 输入文本与 Key 不写入日志；Key 输入框默认掩码。
- 删除文件必须用户明确选择；默认只删记录。
- 错误信息直接展示 API 返回的 `msg`，不做静默吞错。

## 错误处理（界面文案）

| failureKind | 文案 |
| --- | --- |
| Network | 网络错误，请检查网络后重试 |
| InvalidKey | API Key 无效，请到设置中检查 |
| QuotaExhausted | 今日额度已用尽，可等待次日重置或兑换额度 |
| Forbidden | 访问被拒绝：{msg} |
| RateLimited | 请求过于频繁，请稍后再试 |
| QualityUnavailable | 该歌曲在所选及更低音质下均不可用 |
| StreamExpired | 下载链接已失效，重试将重新解析 |
| Integrity | 文件校验失败，已删除不完整文件 |
| Storage | 存储空间不足 |
| InvalidRequest | 请求参数异常：{msg} |
| NotFound | 资源不存在：{msg} |
| ServiceUnavailable | 服务暂时不可用，请稍后重试 |
| Unknown | 下载失败：{msg} |
| Malformed | 接口返回异常，请稍后重试 |

Snackbar 只显示一条汇总消息；单任务错误在卡片上展示。入队阶段的短链失败（`EnqueueFailure.LinkUnsupported/Network`）不是任务状态，只在 Snackbar 汇总提示。

## 与其他 Stage 的契约

- 使用 Stage 1：`TaskRepository`（含 `byId`）、`AppSettings`、`KeyValidator`、`ApiFailure`/`FailureKind`、`QuotaStore`。
- `AppContainer` 实现 `AppDependencies` 接口（暴露 `repository`、`settings`、`engine`、`fileStore`、`quotaStore`、`keyValidator`）；`MusicDownloadApp` 暴露 `@VisibleForTesting internal var dependencies: AppDependencies`，仪器测试可替换为 Fake。
- `MainActivity` 在 `onCreate` 与 `onNewIntent` 都读取 `focusKey=true` extra 并转到设置页、聚焦 Key 输入框（Activity 为 `singleTop`）；工作台的 `OpenSettings` 事件走同一路径。
- 单任务速度：`WorkbenchViewModel` 对同一 `id` 的 `bytesDownloaded` 相邻两次 Flow 值按 1 秒窗口差分计算，不依赖引擎额外接口；暂停/重连时归零。
- 使用 Stage 2：`DownloadController`（`start/requestDownload/snapshot/pauseAll/resumeAll/cancelAll/retryAllFailed`）、`TaskFileStore.deleteById`、通知权限被拒绝时仍可下载的行为。
- Stage 3 不直接操作 MediaStore，除删除文件与打开/分享外；打开与分享使用任务保存的 `mediaStoreId` 构造 URI。

## 测试策略

JVM 单元测试：

- `WorkbenchViewModelTest`（Fake 仓库/引擎/Flow）：入队成功/重复/无法识别三种提示；未配置 Key 跳转设置；权限请求时机；暂停/取消/重试调用引擎；历史过滤；删除记录与删除文件分支。
- `SettingsViewModelTest`：Key 格式本地校验、保存成功清空草稿、验证失败展示 msg、并发数范围钳制、主题切换持久化。
- `FileInfoFormatterTest`：字节、速度、日期格式化（含 0、未知、跨年）。

仪器测试（模拟器）：

- `WorkbenchScreenTest`（Compose UI test，测试依赖在 Stage 1 加入）：空状态、输入后按钮可用、Tab 切换、任务卡片动作；`SettingsScreenTest` 使用 Fake `KeyValidator`，不依赖网络。
- `ShareActivityTest`：ACTION_SEND 文本入队后 Activity finish。
- 手动视觉验收：真机/模拟器截图核对深色与浅色、动态取色、空列表、下载中、失败、已完成五种状态。

端到端验收（手动）：见 Stage 2 的端到端步骤，加界面断言（进度、历史、分享入口）。

## 明确排除

- 不引入 Navigation Compose、Hilt、分页库。
- 不做应用内播放器、歌词页、封面下载。
- 不做多语言与深色自定义配色（仅跟随系统或用户选择深浅）。
- 不为旧 View 界面保留入口。
- 不做引导页、登录页或配额购买流程。
