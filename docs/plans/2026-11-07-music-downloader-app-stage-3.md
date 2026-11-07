# 音乐下载器 Stage 3 实现计划

> **执行者须知：** 使用 smart-exec-plan 连续执行全部 Stage。本 Stage 不是独立部署版本。

**目标：** 完成单页工作台、历史操作、设置、分享入口、动态取色主题与通知权限流程，形成可直接使用的最终应用。

**需求索引：** `docs/brainstorming/2026-11-07-music-downloader-app.md`

**设计文档：** `docs/grill/2026-11-07-music-downloader-app-stage-3.md`

**术语表：** `docs/CONTEXT.md`

**Stage：** 3 / 3

**依赖 Stage：** Stage 2

**完整验证命令：**
- 构建：`./gradlew :app:assembleDebug`
- 类型检查：项目未单独配置（Kotlin 编译由构建执行）
- lint：`./gradlew :app:lintDebug`
- 单元测试：`./gradlew :app:testDebugUnitTest`
- 集成测试：`./gradlew :app:connectedDebugAndroidTest`（需已启动模拟器）

---

## 进度清单

### Group 1：主题与工作台

- [x] Task 1-1 — 动态主题、路由骨架、UI 测试依赖与页面外壳
- [x] Task 1-2 — 显示格式化与 WorkbenchViewModel
- [x] Task 1-3 — 工作台界面与任务卡片
- [x] Task 1-4 — 批量操作与通知权限流程

### Group 2：历史与设置

- [x] Task 2-1 — 历史搜索、打开、分享、重新下载、删除与清空
- [ ] Task 2-2 — 设置页面、Key 校验、主题与并发联动

### Group 3：分享与收尾

- [ ] Task 3-1 — 系统分享入口
- [ ] Task 3-2 — 端到端验收、文案、无障碍与最终验证

---

### Task 1-1：动态主题、路由骨架、UI 测试依赖与页面外壳

**依赖：** Stage 2

**文件：**
- 修改：`app/build.gradle.kts`（仅启用 `buildFeatures.buildConfig = true`；测试依赖已在 Stage 1 Task 1-1 加入，本 Stage 只验证可用）
- 修改：`app/src/main/java/com/dawnmoon/music_download/ui/theme/Theme.kt`（动态取色）
- 创建：`app/src/main/java/com/dawnmoon/music_download/ui/AppRoot.kt`
- 创建：`app/src/main/java/com/dawnmoon/music_download/ui/viewmodel/AppViewModelFactory.kt`
- 创建：`app/src/main/java/com/dawnmoon/music_download/ui/workbench/WorkbenchScreen.kt`（外壳）
- 创建：`app/src/main/java/com/dawnmoon/music_download/ui/settings/SettingsScreen.kt`（外壳）
- 修改：`app/src/main/java/com/dawnmoon/music_download/ui/MainActivity.kt`
- 修改：`app/src/main/res/values/strings.xml`
- 测试：`app/src/androidTest/java/com/dawnmoon/music_download/ui/AppRootTest.kt`

**提交信息：**

`feat: 添加动态取色主题与单 Activity 路由骨架`

#### Step 1：编写失败测试（Compose UI 仪器测试）

- 启动 `MainActivity`：显示"音乐下载"，输入框占位文案存在。
- 点击设置图标：显示"设置"标题；按返回回到工作台。
- 切换系统深色模式后 `assertIsDisplayed` 的标题仍可读（仅验证不崩溃与文案存在）。

先添加 UI 测试依赖，再运行测试，确认因页面不存在而失败（而非依赖缺失）。

#### Step 2：实现最终行为

- `buildFeatures { buildConfig = true }`；确认 Stage 1 加入的 Compose UI 测试与 `androidx.test:rules` 依赖可用，不重复添加。
- `Theme.kt`：Android 12+ `dynamicLight/darkColorScheme(LocalContext.current)`，否则内置配色（primary `0xFF5B5BD6`）。
- `AppRoot`：`AppRoute { Workbench, Settings }` + `rememberSaveable` + `BackHandler`；工作台与设置外壳分别显示标题与占位内容；`Scaffold` + `SnackbarHost`；设置图标。
- `WorkbenchScreen(state, callbacks)` 外壳包含 `TopAppBar`、空 `LazyColumn` 与输入卡片占位，参数签名在 Task 1-2/1-3 扩展。
- `SettingsScreen(state, callbacks)` 外壳包含标题与返回按钮。
- `MainActivity` 创建 `AppViewModelFactory`（容器来自 `MusicDownloadApp.dependencies`），`enableEdgeToEdge`，调用 `AppRoot`。
- `strings.xml` 建立本 Stage 全部文案键（后续 Task 只新增，不硬编码）。

#### Step 3：验证与整理

`AppRootTest` 通过；`./gradlew :app:assembleDebug` 成功。

---

### Task 1-2：显示格式化与 WorkbenchViewModel

**依赖：** Task 1-1

**文件：**
- 创建：`app/src/main/java/com/dawnmoon/music_download/ui/common/FileInfoFormatter.kt`
- 创建：`app/src/main/java/com/dawnmoon/music_download/ui/viewmodel/WorkbenchUiState.kt`
- 创建：`app/src/main/java/com/dawnmoon/music_download/ui/viewmodel/WorkbenchViewModel.kt`
- 修改：`app/src/main/java/com/dawnmoon/music_download/ui/viewmodel/AppViewModelFactory.kt`
- 测试：`app/src/test/java/com/dawnmoon/music_download/ui/FileInfoFormatterTest.kt`
- 测试：`app/src/test/java/com/dawnmoon/music_download/ui/WorkbenchViewModelTest.kt`

**提交信息：**

`feat: 实现工作台状态与显示格式化`

#### Step 1：编写失败测试

`FileInfoFormatterTest`：字节、速度、日期、未知值、跨年日期、`null` 进度。

`WorkbenchViewModelTest`（Fake `TaskRepository`/`DownloadController`/`TaskFileStore`/`QuotaStore`/`AppSettings`，Turbine）：

- 初始音质为设置默认值，Tab 为进行中。
- `onEnqueue` 总是调用 `repo.enqueue`：
  - `Enqueued(created=[1])` → `engine.start` + `requestDownload([1])`，提示"已加入 1 个任务"，清空输入。
  - `created=[]` 且 `duplicates=2` → 提示全部重复。
  - `created=[]` 且 `failures=1` → 提示链接无法识别。
  - 两者都有 → 同时提示。
  - `MissingApiKey` → `OpenSettings(focusKey=true)`。
  - `NoLinks` → 提示未找到链接。
  - 入队期间 `isEnqueuing=true`，按钮禁用。
- `onPause/onResume/onCancel/onRetry` 与批量方法调用引擎。
- 历史搜索：曲名、艺术家、文件名任一匹配（大小写不敏感）。
- 失败映射：`Network/InvalidKey/QuotaExhausted/Forbidden/RateLimited/QualityUnavailable/StreamExpired/Integrity/Storage/ServiceUnavailable/NotFound/InvalidRequest/Malformed/Unknown` 各自返回非空本地化文案；卡片保留 `failureMessage`。
- 速度：1 秒内字节增量 2 MiB → 2 MiB/s；暂停或完成后为 null。
- 取消的任务仍出现在进行中列表。

运行测试，确认编译失败。

#### Step 2：实现最终行为

`WorkbenchUiState`/`TaskUiModel` 按设计字段；`WorkbenchViewModel` 组合五个 Flow，速度用相邻进度差分（1s tick，清理已结束任务）；事件 `Channel(BUFFERED)`；`onEnqueue` 不做本地 Key 预判，完全依赖 `EnqueueOutcome`。

#### Step 3：验证与整理

两个测试类通过；`AppViewModelFactory` 可创建 `WorkbenchViewModel`。

---

### Task 1-3：工作台界面与任务卡片

**依赖：** Task 1-2

**文件：**
- 修改：`app/src/main/java/com/dawnmoon/music_download/ui/workbench/WorkbenchScreen.kt`
- 创建：`app/src/main/java/com/dawnmoon/music_download/ui/workbench/TaskCard.kt`
- 创建：`app/src/main/java/com/dawnmoon/music_download/ui/workbench/QualitySelector.kt`
- 创建：`app/src/main/java/com/dawnmoon/music_download/ui/workbench/QuotaLabel.kt`
- 创建：`app/src/main/java/com/dawnmoon/music_download/ui/workbench/EmptyState.kt`
- 测试：`app/src/androidTest/java/com/dawnmoon/music_download/ui/WorkbenchScreenTest.kt`

**提交信息：**

`feat: 实现工作台界面、音质选择与任务卡片`

#### Step 1：编写失败测试（直接传入 UiState 与回调，不依赖 ViewModel）

- 空列表显示"暂无下载任务"。
- 输入两行文本后按钮可点击，点击回调一次（不在此断言输入清空，清空由 ViewModel 测试覆盖）。
- 音质下拉 7 项，选择"无损"触发回调 `Lossless`。
- 进行中 Tab 显示数量；点击已完成 Tab 显示历史列表。
- 下载中卡片显示进度、百分比、大小、速度；暂停按钮触发 `onPause`。
- 失败卡片显示本地化文案与"重试"；点击展开显示原文。
- 已取消卡片显示"删除记录"。
- 额度低于 10 时显示警示文案（如"额度不足"）并带 `testTag`；不通过语义树断言颜色。

运行测试，确认失败。

#### Step 2：实现最终行为

按设计布局：`TopAppBar`（额度标签、溢出菜单、设置图标）、输入卡片（`OutlinedTextField` minLines 3、`ExposedDropdownMenuBox`、按钮 loading）、`TabRow` + 计数、`LazyColumn` 卡片、空状态、`SnackbarHost`。卡片含 Coil 封面、状态 chip、进度条、字节/速度、按状态的操作按钮；触控目标 ≥ 48dp，图标有 `contentDescription`。

#### Step 3：验证与整理

`WorkbenchScreenTest` 通过；深浅色截图核对。

---

### Task 1-4：批量操作与通知权限流程

**依赖：** Task 1-3

**文件：**
- 修改：`app/src/main/java/com/dawnmoon/music_download/ui/AppRoot.kt`
- 修改：`app/src/main/java/com/dawnmoon/music_download/ui/workbench/WorkbenchScreen.kt`（溢出菜单）
- 修改：`app/src/main/java/com/dawnmoon/music_download/ui/viewmodel/WorkbenchViewModel.kt`
- 测试：`app/src/test/java/com/dawnmoon/music_download/ui/NotificationPermissionTest.kt`
- 测试：`app/src/androidTest/java/com/dawnmoon/music_download/ui/BatchActionsTest.kt`

**提交信息：**

`feat: 添加批量操作与下载前通知权限请求`

#### Step 1：编写失败测试

单元测试（Fake `AppSettings`）：

- 首次入队且 `notificationAsked=false`：先调用 `engine.start/requestDownload`，再发 `RequestNotificationPermission`，并**在发事件时**写入 `markNotificationAsked`（不等待权限结果）。
- `notificationAsked=true`：不发权限事件。
- `WorkbenchViewModel` 构造器增加 `sdkInt: Int = Build.VERSION.SDK_INT`；注入 32 时不发事件，33 时发。
- 权限结果不参与下载启动（事件无回调）。

Compose 测试：

- 溢出菜单含全部暂停/继续/重试失败/取消。
- "全部取消"弹确认框，确认后触发 `onCancelAll`，取消不触发。

运行测试，确认失败。

#### Step 2：实现最终行为

`AppRoot` 用 `rememberLauncherForActivityResult(RequestPermission())`；收到事件后发起请求；下载已在 ViewModel 中启动，结果仅记录日志（`markNotificationAsked` 已在发事件时写入）。批量菜单接引擎方法，取消前确认。

#### Step 3：验证与整理

测试通过；手动拒绝权限确认下载继续。

---

### Task 2-1：历史搜索、打开、分享、重新下载、删除与清空

**依赖：** Task 1-3

**文件：**
- 修改：`app/src/main/java/com/dawnmoon/music_download/ui/workbench/WorkbenchScreen.kt`（搜索框、清空菜单、已完成卡片动作）
- 修改：`app/src/main/java/com/dawnmoon/music_download/ui/workbench/TaskCard.kt`
- 修改：`app/src/main/java/com/dawnmoon/music_download/ui/viewmodel/WorkbenchViewModel.kt`
- 创建：`app/src/main/java/com/dawnmoon/music_download/ui/history/HistoryActions.kt`
- 测试：`app/src/test/java/com/dawnmoon/music_download/ui/HistoryActionsTest.kt`
- 测试：`app/src/androidTest/java/com/dawnmoon/music_download/ui/HistoryScreenTest.kt`

**提交信息：**

`feat: 实现历史记录的打开、分享、重下与删除`

#### Step 1：编写失败测试

`HistoryActionsTest`（Fake `TaskFileStore` 返回 Boolean、Fake Repository）：

- `openIntent(42)`：`ACTION_VIEW`、`content://media/external/audio/media/42`、`FLAG_GRANT_READ_URI_PERMISSION` 与 `FLAG_ACTIVITY_NEW_TASK`。
- `shareIntent(42,"夏霞")`：`ACTION_SEND`、`audio/*`、`EXTRA_STREAM` 与 `clipData` 非空、读权限 flag 与 `FLAG_ACTIVITY_NEW_TASK`。
- `mediaStoreId=null` 时两者为 null，UI 提示"文件已不存在"。
- `delete(deleteFile=true)`：`deleteById` 返回 true 才删记录；返回 false 时保留并提示。
- `delete(deleteFile=false)`：不调用文件存储。
- `clearHistory(deleteFiles=true)`：只对返回 true 的 id 调 `deleteRecords`，返回失败数量。
- `clearHistory(deleteFiles=false)`：调 `clearCompleted`。
- `redownload(task, quality)`：构造 `SongLink.Direct(sourceUrl, SongId(songId))` 入队并启动引擎。
- 历史过滤匹配文件名。

Compose 测试：搜索框输入后列表过滤；清空历史弹窗两种选项；已完成卡片播放/分享/重下/删除菜单。

运行测试，确认失败。

#### Step 2：实现最终行为

`HistoryActions` 构造 Intent；ViewModel 实现流程并在主线程启动播放/分享；`WorkbenchScreen` 增加搜索框、清空历史菜单与空搜索状态；删除/清空使用 `deleteById` 的 Boolean 返回值；失败条目保留并 Snackbar 汇总。

#### Step 3：验证与整理

测试通过；`adb shell dumpsys package` 确认 intent 可解析。

---

### Task 2-2：设置页面、Key 校验、主题与并发联动

**依赖：** Task 1-1、1-4

**文件：**
- 创建：`app/src/main/java/com/dawnmoon/music_download/ui/viewmodel/SettingsViewModel.kt`
- 修改：`app/src/main/java/com/dawnmoon/music_download/ui/settings/SettingsScreen.kt`
- 修改：`app/src/main/java/com/dawnmoon/music_download/ui/viewmodel/AppViewModelFactory.kt`
- 修改：`app/src/main/java/com/dawnmoon/music_download/ui/AppRoot.kt`（主题收集、focusKey、设置页接线）
- 修改：`app/src/main/java/com/dawnmoon/music_download/ui/MainActivity.kt`（读取 `focusKey` extra）
- 测试：`app/src/test/java/com/dawnmoon/music_download/ui/SettingsViewModelTest.kt`
- 测试：`app/src/androidTest/java/com/dawnmoon/music_download/ui/SettingsScreenTest.kt`
- 测试：`app/src/androidTest/java/com/dawnmoon/music_download/ui/MainActivityFocusTest.kt`

**提交信息：**

`feat: 实现设置页面与 API Key 校验`

#### Step 1：编写失败测试

`SettingsViewModelTest`（Fake `AppSettings`、Fake `KeyValidator`、Fake `QuotaStore`、Fake `TaskRepository`、Fake `TaskFileStore`）：

- 本地格式非法 → `Invalid`，不调用校验器。
- `Valid` → 保存、清空草稿、显示额度。
- `InvalidKey` → 不保存，显示 msg。
- `Indeterminate` → 保存并显示警告。
- 并发数 0/9 钳制为 1/5；主题写入。
- 清空历史调用与 Task 2-1 相同的 `HistoryActions` 逻辑。
- 版本号来自 `BuildConfig.VERSION_NAME`。

`SettingsScreenTest`（注入 Fake `KeyValidator`，不依赖网络）：掩码切换、保存中禁用、下拉/滑块/主题三选一。`focusKey` 的行为由 `MainActivityFocusTest` 覆盖。

运行测试，确认失败。

#### Step 2：实现最终行为

`SettingsViewModel` 按设计；`SettingsScreen` 五个分组；`AppRoot` 收集 `settings.themeMode` 并传给主题，处理 `focusKey`，接线 `SettingsViewModel`；`MainActivity` 将 `intent.getBooleanExtra("focusKey", false)` 传入 `AppRoot`；`BuildConfig` 已在 Task 1-1 启用。

#### Step 3：验证与整理

测试通过；主题切换立即生效；真实 Key 校验成功（不写日志）。

---

### Task 3-1：系统分享入口

**依赖：** Task 2-2、Stage 2 引擎

**文件：**
- 创建：`app/src/main/java/com/dawnmoon/music_download/ui/ShareActivity.kt`
- 修改：`app/src/main/java/com/dawnmoon/music_download/MusicDownloadApp.kt`（`dependencies` 可替换）
- 修改：`app/src/main/AndroidManifest.xml`（透明 Activity + SEND filter）
- 修改：`app/src/main/res/values/themes.xml`、`values-night/themes.xml`（透明主题）
- 测试：`app/src/androidTest/java/com/dawnmoon/music_download/ui/ShareActivityTest.kt`

**提交信息：**

`feat: 支持从网易云分享链接静默入队`

#### Step 1：编写失败测试（仪器测试）

- 预置 `notificationAsked=true` 与 Fake 依赖（`MusicDownloadApp.dependencies` 替换）：发送示例分享文案 → 2 秒内 finish；仓库入队一次、音质为默认值；引擎 `start/requestDownload` 被调用。
- 文本无链接 → 不入队，Activity 结束。
- 未配置 Key（Fake 返回 `MissingApiKey`）→ 启动 `MainActivity` 并带 `focusKey=true`，Activity 结束。
- 文本包含两条链接 → 入队两条。

运行测试，确认编译失败。

#### Step 2：实现最终行为

`ShareActivity : ComponentActivity()` 无布局；读取 `EXTRA_TEXT`，`LinkExtractor` 预判后入队；按 `EnqueueOutcome` 显示 Toast；未配置 Key 时启动主界面并带 extra；首次下载且未请求通知权限时发起权限请求但不阻塞；`finish()`。Manifest 注册透明主题 Activity，`exported=true`，仅 `text/plain`。

#### Step 3：验证与整理

测试通过；`adb shell am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "分享あたらよ的单曲《夏霞》: https://163cn.tv/bkp6m2as (来自@网易云音乐)"` 实测静默入队。

---

### Task 3-2：端到端验收、文案、无障碍与最终验证

**依赖：** Task 3-1

**文件：**
- 修改：`app/src/main/res/values/strings.xml`（补齐全部文案）
- 修改：`app/src/main/java/com/dawnmoon/music_download/ui/**`（清理硬编码、补 `contentDescription`、`testTag`）
- 测试：`app/src/androidTest/java/com/dawnmoon/music_download/ui/AccessibilityTest.kt`
- 测试：`app/src/androidTest/java/com/dawnmoon/music_download/ui/ErrorStateScreenTest.kt`

**提交信息：**

`feat: 完成文案、无障碍与端到端验收`

#### Step 1：端到端验收（模拟器 + 真实 Key，从本地 `api_key.txt` 读取）

逐项记录证据（命令输出或截图路径）：

1. 示例分享文案下载：`adb shell ls -l /sdcard/Music` 存在 `夏霞_あたらよ.mp3`；大小等于 API `data.size`；`md5sum` 与 API 一致。
2. 两首同时入队：解析串行、下载并发 ≤ 设置值（`logcat` 时间戳）。
3. 暂停/继续：从断点字节继续；杀进程重启后任务为暂停且可继续。
4. 错误路径：无效 Key（401）、额度用尽（402）、封禁（403）、限流（429）、音质不可用（全部 404）、网络中断，各有明确文案。402/403/429 通过 Fake `TaskRepository` 注入 `FailureKind` 的仪器测试覆盖（`ErrorStateScreenTest`），不依赖真实 API 是否触发。
5. 分享入队静默完成。
6. 锁屏下载继续、通知更新（WakeLock 生效）。
7. 未申请存储权限；拒绝通知权限后下载仍可用。
8. 历史打开、分享、重新下载、删除记录、删除记录与文件、清空历史均可用。
9. 深浅色与动态取色切换，对比度与触控区域达标。
10. 构建、lint、单元测试、仪器测试全部通过。

任一失败回到对应 Task 修复并重跑其测试。

#### Step 2：文案与无障碍整理

运行 `AccessibilityTest`（Compose 语义检查关键按钮有描述、触控尺寸 ≥ 48dp）；扫描 `ui/**` 无硬编码中文（`MusicQuality.label` 等模型层标签保留在代码中）；`strings.xml` 无重复键。

#### Step 3：最终验证与安全检查

按顺序执行：

```
./gradlew :app:assembleDebug
./gradlew :app:lintDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest
git status --short
git check-ignore -v api_key.txt local.properties
```

确认无遗留 smoke 代码、无真实 Key 或日志、`api_key.txt` 与 `local.properties` 被忽略，最终成功标准逐项通过。
