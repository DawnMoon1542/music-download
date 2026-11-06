# 音乐下载器 Stage 1 实现计划

> **执行者须知：** 使用 smart-exec-plan 连续执行全部 Stage。本 Stage 不是独立部署版本。

**目标：** 建立 Compose + Material 3 工程基础，实现领域纯逻辑、API 客户端、限速与 429 重试、Room 数据层、DataStore 设置、任务仓库与手动依赖容器。

**需求索引：** `docs/brainstorming/2026-11-07-music-downloader-app.md`

**设计文档：** `docs/grill/2026-11-07-music-downloader-app-stage-1.md`

**术语表：** `docs/CONTEXT.md`

**Stage：** 1 / 3

**依赖 Stage：** 无

**完整验证命令：**
- 构建：`./gradlew :app:assembleDebug`
- 类型检查：项目未单独配置（Kotlin 编译由构建执行）
- lint：`./gradlew :app:lintDebug`
- 单元测试：`./gradlew :app:testDebugUnitTest`
- 集成测试：`./gradlew :app:connectedDebugAndroidTest`（需已启动模拟器）

---

## 进度清单

### Group 1：工程骨架

- [x] Task 1-1 — 清理模板与冒烟代码，建立空 Compose Activity、主题与 Application
- [x] Task 1-2 — 领域基础类型与音质/格式规则
- [x] Task 1-3 — 任务状态机

### Group 2：纯领域逻辑

- [x] Task 2-1 — 链接提取
- [x] Task 2-2 — 文件名生成
- [x] Task 2-3 — 全局限速器

### Group 3：数据层

- [x] Task 3-1 — Room 实体、DAO、数据库与 schema 导出
- [x] Task 3-2 — RoomQuotaStore 与 DataStoreSettings

### Group 4：网络层

- [x] Task 4-1 — ChkszApi、DTO、错误映射与额度头
- [x] Task 4-2 — 短链解析
- [x] Task 4-3 — ApiCaller 429 重试与 KeyValidator
- [ ] Task 4-4 — DefaultQualityResolver 降级策略

### Group 5：仓库与装配

- [ ] Task 5-1 — DefaultTaskRepository 入队、去重、状态写入与文件名分配
- [ ] Task 5-2 — AppContainer、AppDependencies、Application 与启动恢复

---

### Task 1-1：清理模板与冒烟代码，建立空 Compose Activity、主题与 Application

**依赖：** 无

**文件：**
- 修改：`gradle/libs.versions.toml`（新增 `compose-ui-test-junit4`、`compose-ui-test-manifest`、`androidx-test-rules`）
- 修改：`app/build.gradle.kts`（`androidTestImplementation(platform(libs.androidx.compose.bom))`、两个 Compose UI 测试库、`androidx.test:rules`；`debugImplementation(libs.androidx.compose.ui.test.manifest)`）
- 创建：`app/src/main/java/com/dawnmoon/music_download/MusicDownloadApp.kt`
- 创建：`app/src/main/java/com/dawnmoon/music_download/ui/MainActivity.kt`
- 创建：`app/src/main/java/com/dawnmoon/music_download/ui/theme/Theme.kt`
- 创建：`app/src/main/java/com/dawnmoon/music_download/domain/model/ThemeMode.kt`
- 修改：`app/src/main/AndroidManifest.xml`
- 删除：`app/src/main/java/com/dawnmoon/music_download/smoke/`（含 `Smoke.kt`、`MainActivity.kt`）
- 删除：`app/src/test/java/com/dawnmoon/music_download/smoke/SmokeStackTest.kt`
- 删除：`app/src/androidTest/java/com/dawnmoon/music_download/smoke/SmokePlatformTest.kt`
- 删除：`app/src/test/java/com/dawnmoon/music_download/ExampleUnitTest.kt`
- 删除：`app/src/androidTest/java/com/dawnmoon/music_download/ExampleInstrumentedTest.kt`
- 删除：`app/schemas/com.dawnmoon.music_download.smoke.SmokeDatabase/`
- 测试：`app/src/androidTest/java/com/dawnmoon/music_download/ui/LauncherTest.kt`

**提交信息：**

`chore: 清理模板与冒烟代码并搭建 Compose 工程骨架`

移除空模板的 View 残留与冒烟代码，建立 Application、空 Compose Activity、最终形态的主题入口与 `ThemeMode`，作为后续 Stage 的代码基座。

#### Step 1：编写失败测试

`LauncherTest`（Compose UI，`createAndroidComposeRule<MainActivity>()`）断言：Activity 启动后显示文本"音乐下载"，且不存在崩溃。测试先编写；此时依赖与 Activity 均未就绪，编译失败即为预期红灯（同时验证构建校验）。

#### Step 2：删除模板代码并建立骨架

- 删除上述 smoke 包、两个示例测试、SmokeDatabase schema。
- 版本目录与 `app/build.gradle.kts` 一次性加入 Compose UI 测试与 `androidx.test:rules` 依赖（Stage 3 不重复添加）。
- Manifest 删除 `SEND` intent-filter 与 `SmokeService` 声明；保留 INTERNET、ACCESS_NETWORK_STATE、FOREGROUND_SERVICE、FOREGROUND_SERVICE_DATA_SYNC、POST_NOTIFICATIONS，新增 WAKE_LOCK；`<application android:name=".MusicDownloadApp">`，Activity 指向 `.ui.MainActivity`。
- `ThemeMode.kt` 定义 `enum class ThemeMode { System, Light, Dark }`（最终位置，后续不再移动）。
- `Theme.kt`：

```kotlin
@Composable
fun MusicDownloadTheme(themeMode: ThemeMode, content: @Composable () -> Unit)
```

根据 `themeMode` 与 `isSystemInDarkTheme()` 选择 `lightColorScheme`/`darkColorScheme`（动态取色留给 Stage 3）。

- `MainActivity`：`enableEdgeToEdge()`；`setContent { MusicDownloadTheme(ThemeMode.System) { Surface(Modifier.fillMaxSize()) { Text("音乐下载") } } }`。
- `MusicDownloadApp : Application()` 空实现，仅作为 `android:name` 目标。

#### Step 3：验证与整理

运行 `./gradlew :app:assembleDebug` 与 `:app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.dawnmoon.music_download.ui.LauncherTest`，确认通过；安装 APK 启动确认。

---

### Task 1-2：领域基础类型与音质/格式规则

**依赖：** Task 1-1

**文件：**
- 创建：`app/src/main/java/com/dawnmoon/music_download/domain/model/Models.kt`
- 创建：`app/src/main/java/com/dawnmoon/music_download/domain/model/MusicQuality.kt`
- 创建：`app/src/main/java/com/dawnmoon/music_download/domain/model/AudioFormat.kt`
- 创建：`app/src/main/java/com/dawnmoon/music_download/core/AppClock.kt`
- 创建：`app/src/main/java/com/dawnmoon/music_download/core/DispatcherProvider.kt`
- 测试：`app/src/test/java/com/dawnmoon/music_download/domain/MusicQualityTest.kt`
- 测试：`app/src/test/java/com/dawnmoon/music_download/domain/AudioFormatTest.kt`

**提交信息：**

`feat: 添加歌曲、音质、格式与任务领域模型`

定义后续所有 Stage 共用的领域类型，固化音质降级顺序、格式识别与任务状态枚举。

#### Step 1：编写失败测试

`MusicQualityTest`：

- `Jymaster.downgradePath()` 返回 `Jymaster, Sky, Jyeffect, Hires, Lossless, Exhigh, Standard`。
- `Lossless.downgradePath()` 返回 `Lossless, Exhigh, Standard`。
- `fromApiValue("hires") == Hires`；`fromApiValue("unknown") == null`；`fromApiValue(null) == null`；`default == Exhigh`。
- 7 个枚举的 `apiValue` 与 `api.md` 一致。

`AudioFormatTest`：

- `fromUrl("https://x/y.flac?t=1", Hires) == Flac`；`.mp3` → `Mp3`；`.FLAC` → `Flac`。
- 无扩展名：fallback `Jymaster/Sky/Jyeffect/Hires/Lossless` → `Flac`，`Exhigh/Standard` → `Mp3`。
- `fromExtension("flac") == Flac`、`fromExtension("mp3") == Mp3`、`fromExtension(null) == Unknown`。
- `Mp3.mimeType == "audio/mpeg"`、`Flac.mimeType == "audio/flac"`。

运行 `./gradlew :app:testDebugUnitTest --tests '*MusicQualityTest' --tests '*AudioFormatTest'`，确认编译失败。

#### Step 2：实现最终行为

`Models.kt` 定义 `SongId`、`SongLink`、`TaskStatus`（含 `isActive`/`isTerminal`）、`Quota`、`ResolvedTrack`、`BaseFileName`、`DownloadTask`（含 `streamUrl/etag/mediaStoreId/updatedAt/mediaStoreId/failureKind/failureMessage`）、`FailureKind`（不含 `LinkUnsupported`）。`MusicQuality`、`AudioFormat` 按设计实现；`fromUrl` 忽略 query/fragment，扩展名小写，未知/缺失时按 fallback 音质推断。`AppClock`/`SystemAppClock`、`DispatcherProvider`/`DefaultDispatcherProvider` 按设计实现。

#### Step 3：验证与整理

两个测试类通过；确认 `ThemeMode` 仍只在 Task 1-1 的文件中定义。

---

### Task 1-3：任务状态机

**依赖：** Task 1-2

**文件：**
- 创建：`app/src/main/java/com/dawnmoon/music_download/domain/TaskTransitions.kt`
- 测试：`app/src/test/java/com/dawnmoon/music_download/domain/TaskTransitionsTest.kt`

**提交信息：**

`feat: 定义任务状态迁移规则`

以纯函数固化任务状态机，支持换链时 `Downloading -> Resolving`。

#### Step 1：编写失败测试

`TaskTransitions.isAllowed(from, to)` 覆盖：

- 允许：`Queued->Resolving/Paused/Failed/Cancelled`；`Resolving->Waiting/Downloading/Paused/Failed/Cancelled`；`Waiting->Downloading/Paused/Failed/Cancelled`；`Downloading->Completed/Paused/Failed/Cancelled/Resolving`；`Paused->Queued/Waiting/Cancelled`；`Failed->Queued/Cancelled`。
- 拒绝：`Completed->任意`、`Cancelled->任意`、`Queued->Completed`、`Queued->Waiting`、`Queued->Downloading`、`Resolving->Completed`、`Waiting->Completed`、`Paused->Completed`、`Failed->Completed`、同一状态到自身。

运行测试，确认编译失败。

#### Step 2：实现最终行为

`object TaskTransitions { fun isAllowed(from: TaskStatus, to: TaskStatus): Boolean }`，用 `Map<TaskStatus, Set<TaskStatus>>` 表达迁移表。

#### Step 3：验证与整理

`TaskTransitionsTest` 全部通过。

---

### Task 2-1：链接提取

**依赖：** Task 1-2

**文件：**
- 创建：`app/src/main/java/com/dawnmoon/music_download/domain/LinkExtractor.kt`
- 测试：`app/src/test/java/com/dawnmoon/music_download/domain/LinkExtractorTest.kt`

**提交信息：**

`feat: 从分享文本中提取网易云歌曲链接`

#### Step 1：编写失败测试

- 示例分享文案 → 1 条 `ShortLink(code="bkp6m2as")`。
- `https://y.music.163.com/m/song?id=1867150097&uct2=abc` → `Direct(1867150097)`。
- `https://music.163.com/#/song?id=123`、`http://music.163.com/song?id=456`、`https://music.163.com/m/song?foo=1&id=789`、大写 host `HTTPS://MUSIC.163.COM/song?id=9` 均识别。
- 3 条不同链接保持出现顺序；同一短链重复只保留首条且 `duplicateCount == 1`。
- 只含 `https://example.com/x` → links 为空、`ignoredUrlCount == 1`。
- 无 URL → 空结果、计数为 0。
- `id=abc` → 不识别并计入忽略。

运行测试，确认编译失败。

#### Step 2：实现最终行为

按设计用正则提取 URL，判定 `163cn.tv` 与 `music.163.com/y.music.163.com`，解析查询参数或 `#/song?id=` 中的数字 id；输出顺序与去重规则按设计。

#### Step 3：验证与整理

`LinkExtractorTest` 通过。

---

### Task 2-2：文件名生成

**依赖：** Task 1-2

**文件：**
- 创建：`app/src/main/java/com/dawnmoon/music_download/domain/FileNameBuilder.kt`
- 测试：`app/src/test/java/com/dawnmoon/music_download/domain/FileNameBuilderTest.kt`

**提交信息：**

`feat: 生成安全的下载文件名主名`

#### Step 1：编写失败测试

- `build("夏霞", "あたらよ", Flac, SongId(1)).value == "夏霞_あたらよ.flac"`。
- 非法字符全部替换为 `_`；`/` 多艺术家变为 `_` 连接。
- 空艺术家 → `曲名.ext`；制表/连续空格合并；尾部点与首尾空格去除。
- 80 个中文字符曲名 + 50 个字符艺术家 → UTF-8 总字节 ≤ 120，扩展名保留，标题与艺术家均被截断且不以空格/下划线结尾。
- 40 字节艺术家上限：长艺术家先行截断，剩余预算给曲名。
- 全非法字符 → `163_42.flac`；`Mp3` → `.mp3`；`Unknown` → `.bin`。

运行测试，确认编译失败。

#### Step 2：实现最终行为

预算算法：`budget = 120 - ".ext".utf8 - (artist 非空 ? 1 : 0)`；艺术家最多占 `min(40, budget / 3)` 字节，其余给曲名；任一截断按 UTF-8 字符边界（`codePointCount`/逐字符追加）进行；空结果兜底 `163_<songId>`。

#### Step 3：验证与整理

测试通过。

---

### Task 2-3：全局限速器

**依赖：** Task 1-2

**文件：**
- 创建：`app/src/main/java/com/dawnmoon/music_download/data/remote/RateLimiter.kt`
- 测试：`app/src/test/java/com/dawnmoon/music_download/data/RateLimiterTest.kt`

**提交信息：**

`feat: 添加串行全局限速器`

#### Step 1：编写失败测试（虚拟时间）

- 首次调用无延迟；两次调用间隔 ≥ 3200ms。
- 25 次连续调用中，任意 60 秒滑动窗口内的调用次数 ≤ 19。
- 并发 3 个协程执行区间不重叠。
- `penalize(5000)` 后下一次调用至少再等待 5000ms。

运行测试，确认编译失败。

#### Step 2：实现最终行为

`RateLimiter(minIntervalMillis = 3200, clock, sleeper)`：Mutex 内维护 `nextAllowedAt`；执行前等待至允许时间；执行后 `nextAllowedAt = max(now, nextAllowedAt) + interval`；`penalize` 在锁内推后 `nextAllowedAt`。

#### Step 3：验证与整理

测试通过且无真实等待。

---

### Task 3-1：Room 实体、DAO、数据库与 schema 导出

**依赖：** Task 1-2

**文件：**
- 创建：`app/src/main/java/com/dawnmoon/music_download/data/local/DownloadTaskEntity.kt`
- 创建：`app/src/main/java/com/dawnmoon/music_download/data/local/QuotaSnapshotEntity.kt`
- 创建：`app/src/main/java/com/dawnmoon/music_download/data/local/DownloadTaskDao.kt`
- 创建：`app/src/main/java/com/dawnmoon/music_download/data/local/QuotaDao.kt`
- 创建：`app/src/main/java/com/dawnmoon/music_download/data/local/DownloadDatabase.kt`
- 测试：`app/src/androidTest/java/com/dawnmoon/music_download/data/DownloadTaskDaoTest.kt`

**提交信息：**

`feat: 建立 Room 任务与额度数据库`

#### Step 1：编写失败测试

- 插入 3 条任务，`observeInProgress` 返回非 Completed 的 2 条并按 `createdAt DESC`；`observeCompleted` 按 `completedAt DESC`。
- `nextQueued` 返回最早创建的 Queued。
- 同 songId 一条 Completed、一条 Downloading，`activeBySongId` 返回 Downloading。
- `isFileNameTaken` 对 Downloading 为 true，对仅 Cancelled 同名任务为 false。
- `markUnfinishedAsPaused(now)` 转换 Queued/Resolving/Waiting/Downloading，并写入 `updatedAt=now`；Failed/Cancelled/Completed 不变。
- `countCompleted` 与 `allInProgress` 返回正确集合。
- `clearCompleted` 只删 Completed。
- 代码覆盖插入两条同 songId 活动任务：`insertIfNoActive` 第二次返回 -1。

运行 `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.dawnmoon.music_download.data.DownloadTaskDaoTest`，确认编译失败。

#### Step 2：实现最终行为

实体与索引按设计；DAO 增加 `@Transaction suspend fun insertIfNoActive(task): Long`（内部查询活动同 songId，存在返回 -1）；其余方法按设计与测试实现。

#### Step 3：验证与整理

DAO 测试通过，`app/schemas/com.dawnmoon.music_download.data.local.DownloadDatabase/1.json` 生成并纳入版本控制。

---

### Task 3-2：RoomQuotaStore 与 DataStoreSettings

**依赖：** Task 3-1

**文件：**
- 创建：`app/src/main/java/com/dawnmoon/music_download/data/repository/QuotaStore.kt`
- 创建：`app/src/main/java/com/dawnmoon/music_download/data/repository/AppSettings.kt`
- 创建：`app/src/main/java/com/dawnmoon/music_download/data/repository/DataStoreSettings.kt`
- 测试：`app/src/androidTest/java/com/dawnmoon/music_download/data/RoomQuotaStoreTest.kt`
- 测试：`app/src/androidTest/java/com/dawnmoon/music_download/data/DataStoreSettingsTest.kt`

**提交信息：**

`feat: 添加额度存储与 DataStore 设置`

#### Step 1：编写失败测试

`RoomQuotaStoreTest`：

- 无数据时 `quota.first() == null`；`record(Quota(3,0,20))` 后正确。
- 再次 `record(Quota(null,5,null))` 保留 3 与 20。

`DataStoreSettingsTest`（`PreferenceDataStoreFactory.create` 指向临时文件）：

- 默认值：Exhigh、3、System、null、false。
- 并发数 0→1、9→5、3→3。
- 所有设置写入后 Flow 反映新值。

运行仪器测试，确认编译失败。

#### Step 2：实现最终行为

`interface QuotaStore` + `RoomQuotaStore`；`interface AppSettings` + `DataStoreSettings`；并发数 `coerceIn(1,5)`；音质非法值回退默认。

#### Step 3：验证与整理

两个测试通过。

---

### Task 4-1：ChkszApi、DTO、错误映射与额度头

**依赖：** Task 1-2、Task 2-2

**文件：**
- 创建：`app/src/main/java/com/dawnmoon/music_download/data/remote/ApiResponse.kt`
- 创建：`app/src/main/java/com/dawnmoon/music_download/data/remote/Dtos.kt`
- 创建：`app/src/main/java/com/dawnmoon/music_download/data/remote/ChkszApi.kt`（接口与 `DefaultChkszApi`）
- 创建：`app/src/main/java/com/dawnmoon/music_download/core/Redaction.kt`
- 测试：`app/src/test/java/com/dawnmoon/music_download/data/ChkszApiTest.kt`
- 测试：`app/src/test/java/com/dawnmoon/music_download/core/RedactionTest.kt`

**提交信息：**

`feat: 实现 ChKSz 解析与搜索接口客户端`

#### Step 1：编写失败测试

- 成功响应字段映射（含 `data.level` 无法映射时回退请求音质、`artist`/`title` 为空时的回退文案）。
- 请求路径与查询参数正确（`id`、`level`、`apikey`）。
- 401/402/403/400/404/429/503 映射与 `msg` 原文；404 含 `Music URL not found` → `QualityUnavailable`。
- 429 响应 `Retry-After: 7` 解析为 `RateLimited(7, msg)`。
- 非 JSON → `Malformed`。
- 额度头解析；失败响应同样解析。
- `validateKey` 请求 `/api/163_search?keyword=music&limit=1`。
- `Redaction.redactUrl` 把 `apikey` 值替换为 `***`；redact 后的异常消息不含原始 Key。

运行测试，确认编译失败。

#### Step 2：实现最终行为

按设计实现 `interface ChkszApi` 与 `class DefaultChkszApi`；所有异常消息经 `Redaction`；`Retry-After` 缺失为 null；`data.level` 映射失败记录请求音质；`format` 用 `AudioFormat.fromUrl(url, actualQuality)`。

#### Step 3：验证与整理

测试通过；源码无 Key/URL 日志。

---

### Task 4-2：短链解析

**依赖：** Task 4-1、Task 2-1

**文件：**
- 创建：`app/src/main/java/com/dawnmoon/music_download/data/remote/ShortLinkResolver.kt`
- 测试：`app/src/test/java/com/dawnmoon/music_download/data/ShortLinkResolverTest.kt`

**提交信息：**

`feat: 解析 163cn.tv 短链得到歌曲 ID`

#### Step 1：编写失败测试（MockWebServer3）

- 302 + Location 含 id → 成功；301 同。
- 302 无 `Location` → `LinkUnsupported`。
- Location 无 id / 200 无跳转 → `LinkUnsupported`。
- 500 → `NotFound`；连接失败 → `Network`。
- 服务端只收到一次请求（未跟随跳转）。

运行测试，确认编译失败。

#### Step 2：实现最终行为

`LinkResolver` 接口 + `HttpShortLinkResolver(client)`：用 `followRedirects(false)` 的独立调用；GET `https://163cn.tv/<code>`；Location 用 `LinkExtractor` 的直链规则提取 id。

#### Step 3：验证与整理

测试通过。

---

### Task 4-3：ApiCaller 429 重试与 KeyValidator

**依赖：** Task 4-1、Task 2-3、Task 3-2

**文件：**
- 创建：`app/src/main/java/com/dawnmoon/music_download/data/remote/ApiCaller.kt`
- 创建：`app/src/main/java/com/dawnmoon/music_download/data/repository/KeyValidator.kt`
- 测试：`app/src/test/java/com/dawnmoon/music_download/data/ApiCallerTest.kt`
- 测试：`app/src/test/java/com/dawnmoon/music_download/data/KeyValidatorTest.kt`

**提交信息：**

`feat: 统一 API 限速、429 重试与 Key 校验`

#### Step 1：编写失败测试

`ApiCallerTest`（真实 `RateLimiter` 注入虚拟 clock/sleeper；Fake `QuotaStore` 接口）：

- 成功与失败响应都调用 `record`（带 quota 时）。
- 首次 429（Retry-After 7）→ `penalize(7000)`；第二次调用起点与第一次间隔 ≥ 7000ms（虚拟时间断言）；返回第二次成功结果。
- 两次 429 → `RateLimited`，总调用 2 次。
- 无 Retry-After → penalize 60000。

`KeyValidatorTest`（Fake `ChkszApi` 接口实现，断言请求次数）：

- 200 → `Valid(quota)`；401 → `InvalidKey`；网络错误/402/403 → `Indeterminate`。
- 非法格式（无 `chksz_`、含空格）→ `InvalidKey` 且不发起请求。
- 合法字符集：`chksz_A-z.0_9~-` 通过本地校验。

运行测试，确认编译失败。

#### Step 2：实现最终行为

`ApiCaller`：第一次 `rateLimiter.run { request() }`；`RateLimited` 时 `penalize`，再 `rateLimiter.run { request() }`（重新进入限速，使等待生效）；第二次结果返回。额度写入每个响应。

`KeyValidator` 接口 + `DefaultKeyValidator`；本地正则 `^chksz_[A-Za-z0-9._~-]+$`。

#### Step 3：验证与整理

两个测试通过。

---

### Task 4-4：DefaultQualityResolver 降级策略

**依赖：** Task 4-3

**文件：**
- 创建：`app/src/main/java/com/dawnmoon/music_download/domain/QualityResolver.kt`
- 创建：`app/src/main/java/com/dawnmoon/music_download/domain/DefaultQualityResolver.kt`
- 测试：`app/src/test/java/com/dawnmoon/music_download/domain/QualityResolverTest.kt`
- 测试：`app/src/test/java/com/dawnmoon/music_download/data/LiveApiSmokeTest.kt`

**提交信息：**

`feat: 实现音质自动降级解析`

#### Step 1：编写失败测试

`QualityResolverTest`（真实 `ApiCaller` + 虚拟时间 `RateLimiter` + Fake `QuotaStore`，Fake `ChkszApi` 返回脚本化响应）：

- 请求 `Jymaster`，依次 404 → 成功于 `Lossless`；调用顺序为完整降级链，`actualQuality=Lossless`。
- 服务端静默降级（请求 Hires 返回 Lossless）→ 直接成功，只调用一次。
- 401 首次 → 立即返回，不继续降级。
- 429（ApiCaller 已重试仍失败）→ 立即返回，不继续降级。
- 全部 404 → 返回最后一次 `QualityUnavailable`。
- 成功响应写入额度（通过 Fake `QuotaStore` 断言）。

`LiveApiSmokeTest`：从 `user.dir` 向上查找含 `settings.gradle.kts` 的根目录；存在 `api_key.txt` 时请求歌曲 1867150097 的 `exhigh`，断言 `title/artist/format` 非空；否则 `assumeTrue(false)` 跳过。

运行测试，确认编译失败。

#### Step 2：实现最终行为

`interface TrackResolver`；`DefaultQualityResolver(api, apiCaller)`：从 requested 起遍历降级链，每级 `apiCaller.call { api.fetchSong(...) }`；`QualityUnavailable` 继续，其他失败立即返回。

#### Step 3：验证与整理

`QualityResolverTest` 通过；联网测试在无 Key 时跳过。

---

### Task 5-1：DefaultTaskRepository

**依赖：** Task 1-3、Task 3-1、Task 3-2、Task 4-2、Task 4-4

**文件：**
- 创建：`app/src/main/java/com/dawnmoon/music_download/data/repository/TaskRepository.kt`
- 创建：`app/src/main/java/com/dawnmoon/music_download/data/repository/DefaultTaskRepository.kt`
- 测试：`app/src/test/java/com/dawnmoon/music_download/data/DefaultTaskRepositoryTest.kt`

**提交信息：**

`feat: 实现任务仓库的入队、去重与状态持久化`

#### Step 1：编写失败测试

使用手写 `FakeDownloadTaskDao`（实现 DAO 接口）与 Fake `AppSettings`、Fake `LinkResolver`（接口 `LinkResolver` 的测试实现）：

- 示例分享文案 → `Enqueued(created.size=1)`，songId 已解析，状态 Queued。
- 无 Key → `MissingApiKey(extractedCount=1)`，无插入。
- 无链接 → `NoLinks`。
- 活动同 songId → duplicates=1；Completed 同 songId → 新建成功。
- 短链 Network 失败 → `failures=1`，类型 Network。
- `enqueueLinks` 直接入队直链。
- 状态写入：`markResolving/completeResolve/startDownloading/updateProgress/attachFile/updateStream/markCompleted/markFailed/markPaused/markCancelled` 全字段正确；`updateStream` 保留 `bytesDownloaded`。
- 非法迁移抛 `IllegalStateException`。
- `resume` 未解析→Queued，已解析→Waiting；`resetForRetry` 清空解析/进度/文件/失败字段并转 Queued。
- 批量：`pauseAll/resumeAll/cancelAll/retryAllFailed/pendingIds`。
- `observeInProgress/observeCompleted/byId` 映射 `mediaStoreId/streamUrl/etag/failureKind`。
- `deleteRecord(s)/clearCompleted` 只动数据库。
- 并发 6 次同 songId 入队 → 只插入 1 条；并发 3 次 `allocateFileName` 得到互不重复的 `a.mp3`、`a (1).mp3`、`a (2).mp3`；Cancelled 占用名不参与。

运行测试，确认编译失败。

#### Step 2：实现最终行为

`TaskRepository` 接口 + `DefaultTaskRepository`：`enqueue` 在仓库级 `Mutex` 内执行提取、短链并发 2、Key 检查、`dao.insertIfNoActive`；所有状态写入先读实体、`TaskTransitions.isAllowed` 校验、`dao.update(copy(updatedAt = now))`；`allocateFileName` 在同一 Mutex 内循环 `isFileNameTaken`；`resetForRetry` 清空 `failureKind/failureMessage`。

`LinkResolver` 接口 + `HttpShortLinkResolver` 实现（包装 Task 4-2 的实现），便于测试。

#### Step 3：验证与整理

`DefaultTaskRepositoryTest` 通过；仓库外无直接 `dao.update`。

---

### Task 5-2：AppContainer、AppDependencies、Application 与启动恢复

**依赖：** Task 5-1、Task 4-4

**文件：**
- 创建：`app/src/main/java/com/dawnmoon/music_download/AppContainer.kt`
- 创建：`app/src/main/java/com/dawnmoon/music_download/AppDependencies.kt`
- 修改：`app/src/main/java/com/dawnmoon/music_download/MusicDownloadApp.kt`
- 测试：`app/src/androidTest/java/com/dawnmoon/music_download/AppContainerTest.kt`

**提交信息：**

`feat: 装配应用依赖容器与启动恢复`

#### Step 1：编写失败测试（仪器测试）

- `AppContainer(context)` 连续读取 `repository/settings/quotaStore/apiCaller/qualityResolver` 返回同一实例。
- `AppDependencies` 接口可被 Fake 实现（编译期断言）。
- 启动 `MusicDownloadApp` 后，预置的 `Downloading` 任务在数据库中被标记为 Paused。

运行仪器测试，确认编译失败。

#### Step 2：实现最终行为

`AppDependencies` 暴露 `repository/settings/quotaStore/api/rateLimiter/apiCaller/keyValidator/qualityResolver/linkResolver/fileStore?`（本 Stage 不含 fileStore）。`AppContainer(context) : AppDependencies` 懒加载 OkHttp、Json、数据库、DAO、`RoomQuotaStore`、`DataStoreSettings`、`HttpShortLinkResolver`、`DefaultChkszApi`、`RateLimiter`、`ApiCaller`、`DefaultQualityResolver`、`DefaultKeyValidator`、`DefaultTaskRepository`。`MusicDownloadApp` 暴露 `@VisibleForTesting internal var dependencies: AppDependencies`，`onCreate` 创建容器并在 `applicationScope` 调 `markUnfinishedAsPaused`。

#### Step 3：验证与整理

运行计划头部的构建、单元测试、仪器测试与 `lintDebug`，确认通过；安装启动无崩溃。
