# 音乐下载器 Stage 2 实现计划

> **执行者须知：** 使用 smart-exec-plan 连续执行全部 Stage。本 Stage 不是独立部署版本。

**目标：** 实现前台服务托管的解析队列与下载池，完成 Range 续传、换链续传、MediaStore 写入与校验、暂停/取消/重试、进度通知与锁屏保活。

**需求索引：** `docs/brainstorming/2026-11-07-music-downloader-app.md`

**设计文档：** `docs/grill/2026-11-07-music-downloader-app-stage-2.md`

**术语表：** `docs/CONTEXT.md`

**Stage：** 2 / 3

**依赖 Stage：** Stage 1

**完整验证命令：**
- 构建：`./gradlew :app:assembleDebug`
- 类型检查：项目未单独配置（Kotlin 编译由构建执行）
- lint：`./gradlew :app:lintDebug`
- 单元测试：`./gradlew :app:testDebugUnitTest`
- 集成测试：`./gradlew :app:connectedDebugAndroidTest`（需已启动模拟器）

---

## 进度清单

### Group 1：下载基础设施

- [x] Task 1-1 — TaskFileStore 接口与 MediaStore 实现
- [x] Task 1-2 — AdaptiveLimiter 可调并发门闸
- [x] Task 1-3 — FileNamer 重名分配

### Group 2：单任务下载

- [ ] Task 2-1 — 首下、进度节流与完整性校验
- [ ] Task 2-2 — 续传校准、换链与瞬时重试
- [ ] Task 2-3 — 暂停、取消与本地错误处理

### Group 3：引擎与服务

- [ ] Task 3-1 — 通知发布器、WakeLock 控制器
- [ ] Task 3-2 — 解析队列、下载池与 DownloadEngine
- [ ] Task 3-3 — 前台服务、容器装配与集成验证

---

### Task 1-1：TaskFileStore 接口与 MediaStore 实现

**依赖：** Stage 1

**文件：**
- 创建：`app/src/main/java/com/dawnmoon/music_download/download/TaskFileStore.kt`
- 创建：`app/src/main/java/com/dawnmoon/music_download/download/MediaStoreTaskFileStore.kt`
- 测试：`app/src/androidTest/java/com/dawnmoon/music_download/download/MediaStoreTaskFileStoreTest.kt`

**提交信息：**

`feat: 通过 MediaStore 写入 Music 目录`

#### Step 1：编写失败测试（仪器测试，try/finally 删除创建的条目）

- `createPending("测试_a.mp3","audio/mpeg")`：`DISPLAY_NAME`、`MIME_TYPE`、`RELATIVE_PATH`（含 `Music`）、`IS_PENDING=1`、`IS_MUSIC=1`。
- `openAppend` 1024 字节 → `sizeOf=1024`；再追加 512 → 1536。
- `openTruncate` 写 10 字节 → `sizeOf=10`。
- `md5Of` 与已知内容一致。
- `complete` 后 `IS_PENDING=0`。
- `delete` 后再查不到；`deleteById(不存在)` 返回 true。
- flac 条目 MIME 为 `audio/flac`。

运行 `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.dawnmoon.music_download.download.MediaStoreTaskFileStoreTest`，确认编译失败。

#### Step 2：实现最终行为

按设计实现接口与实现；`deleteById` 返回 `Boolean`（`true` = 已删除或不存在，`false` = 删除失败）；`sizeOf` 行不存在返回 -1；所有 ContentResolver 操作在 `Dispatchers.IO`。

#### Step 3：验证与整理

测试通过且无残留文件；确认未申请存储权限。

---

### Task 1-2：AdaptiveLimiter 可调并发门闸

**依赖：** Stage 1

**文件：**
- 创建：`app/src/main/java/com/dawnmoon/music_download/download/AdaptiveLimiter.kt`
- 测试：`app/src/test/java/com/dawnmoon/music_download/download/AdaptiveLimiterTest.kt`

**提交信息：**

`feat: 实现可动态调整的下载并发门闸`

#### Step 1：编写失败测试（虚拟时间）

- 初始 3：3 次 acquire 立即完成，第 4 次挂起；release 一次后完成。
- `setPermits(5)` 后挂起的 2 次立即完成；同时在途 ≤ 5。
- `setPermits(1)` 不打断已持有者；新 acquire 等所有持有者释放后获得。
- 多余 release 不使在途计数为负。
- 任务失败/取消路径必须 release（在引擎测试中覆盖）。

运行测试，确认编译失败。

#### Step 2：实现最终行为

`Mutex + inFlight 计数 + permits(1..5) + waiters`；`acquire` 在 `inFlight < permits` 时加一，否则挂起；`release` 减少在途并唤醒等待者；`setPermits` 只影响后续判断。

#### Step 3：验证与整理

测试通过。

---

### Task 1-3：FileNamer 重名分配

**依赖：** Stage 1 的 `allocateFileName`

**文件：**
- 创建：`app/src/main/java/com/dawnmoon/music_download/download/FileNamer.kt`
- 测试：`app/src/test/java/com/dawnmoon/music_download/download/FileNamerTest.kt`

**提交信息：**

`feat: 为下载文件分配唯一名称`

#### Step 1：编写失败测试

- Fake `TaskRepository.allocateFileName` 返回 `a.mp3` 时原样返回。
- 返回 `a (1).mp3` 时不二次追加。
- 仓库抛异常时异常向上传播（由 Worker 处理为失败）。

运行测试，确认编译失败。

#### Step 2：实现最终行为

`FileNamer(repo)` 仅委托 `repo.allocateFileName(base)`。

#### Step 3：验证与整理

测试通过。

---

### Task 2-1：首下、进度节流与完整性校验

**依赖：** Task 1-1、1-2、1-3、Stage 1

**文件：**
- 创建：`app/src/main/java/com/dawnmoon/music_download/download/DownloadWorker.kt`（`TaskDownloader` 接口、`WorkerResult`、`DefaultDownloadWorker`）
- 测试：`app/src/test/java/com/dawnmoon/music_download/download/FakeTaskFileStore.kt`
- 测试：`app/src/test/java/com/dawnmoon/music_download/download/DownloadWorkerTest.kt`

**提交信息：**

`feat: 实现单任务下载与完整性校验`

#### Step 1：编写失败测试（MockWebServer3 + 临时文件 Fake）

- 100 KiB 内容，`sizeBytes` 与真实 md5 已知：执行后 `Completed`、文件名分配值、媒体 id 非空、内容一致、`complete` 调用一次。
- `startDownloading` 先于首次写入；`updateProgress` 次数少于块数且最终值等于总字节。
- `md5=null` 时只校验大小。
- 大小少 1 字节 → `Failed(Integrity)`，删除文件。
- md5 错 → 同上。
- 请求 URL 与任务一致；不额外断言 User-Agent。

运行测试，确认编译失败。

#### Step 2：实现最终行为

`TaskDownloader` 接口 + `WorkerResult` 密封类；`DefaultDownloadWorker` 依赖 `repository/resolver/settings/fileStore/namer/client/clock`。`execute(taskId)`：读任务（Waiting/Paused）→ `startDownloading` → 无 mediaStoreId 时用 `FileNameBuilder`+`FileNamer`+`createPending`+`attachFile` → GET 流式写入（64 KiB）→ 500ms/1 MiB 节流写进度 → 校验大小与 md5 → `complete`+`markCompleted` 或删除+`markFailed(Integrity)`。Get API Key 通过 `settings.apiKey.first()`（首下不需要，换链需要）。

#### Step 3：验证与整理

首下用例通过。

---

### Task 2-2：续传校准、换链与瞬时重试

**依赖：** Task 2-1

**文件：**
- 修改：`app/src/main/java/com/dawnmoon/music_download/download/DownloadWorker.kt`
- 测试：`app/src/test/java/com/dawnmoon/music_download/download/DownloadWorkerResumeTest.kt`
- 测试：`app/src/test/java/com/dawnmoon/music_download/download/ProgressThrottleTest.kt`

**提交信息：**

`feat: 支持断点续传与直链过期换链`

#### Step 1：编写失败测试

- Worker 用任务行中的 `mediaStoreId`/`fileName` 重建 `CreatedFile`；已写 40 KiB → 请求头含 `Range: bytes=40960-` 与 `If-Range`；206 续传后完整。
- DB 字节数 50000 但文件 40960 → 以 40960 发 Range。
- 文件被删除（`sizeOf=-1`）→ 重建 pending 并从头下载。
- 200 忽略 Range → 截断重写。
- 206 的 `Content-Range` total 与 `sizeBytes` 不符 → 按过期链接换链，不追加。
- 403 → `resolver.resolve` 新链接（同 format/md5）→ 状态经历 `Downloading -> Resolving -> Downloading`，`updateStream` 更新所有元数据且保留 `bytesDownloaded`，续传成功。
- 403 后新 format/md5 不同 → 删旧建新、进度清零、最终扩展名正确、数据库 `format/sizeBytes/md5` 为最新值。
- 换链解析返回 401/402/403 → 任务 `Failed` 对应类型，不再尝试。
- 换链再次 403 → `Failed(StreamExpired)`。
- 416 → 截断重下成功。
- 5xx 三次（500,500,200）→ 成功且请求 3 次；持续 500 → `Failed(ServiceUnavailable)`，共 3 次。
- `ProgressThrottleTest`：用 8 MiB 流与可控 clock，断言 500ms 内最多一次写库，1 MiB 立即写，结束强制写最终值。

运行测试，确认编译失败。

#### Step 2：实现最终行为

续传前 `sizeOf` 校准；Range/If-Range；206 total 校验；403/404/410 走 `markResolving → resolve → updateStream 或重建 → startDownloading`；瞬时错误 2 次退避（注入零延迟 sleeper）；416 一次截断；进度节流由 `ProgressReporter` 内部类实现（500ms 或 1 MiB）。

#### Step 3：验证与整理

两组测试通过；换链后无旧文件残留。

---

### Task 2-3：暂停、取消与本地错误处理

**依赖：** Task 2-2

**文件：**
- 修改：`app/src/main/java/com/dawnmoon/music_download/download/DownloadWorker.kt`
- 测试：`app/src/test/java/com/dawnmoon/music_download/download/DownloadWorkerControlTest.kt`

**提交信息：**

`feat: 处理下载暂停、取消与存储错误`

#### Step 1：编写失败测试

- 慢速流（每块延迟）中途暂停：返回 `Paused`，`markPaused` 在 `NonCancellable` 中调用，文件保留，`delete` 未调用。
- 中途取消：`markCancelled` 且文件删除一次；`Cancelled` 状态正确。
- 写入抛 `IOException("ENOSPC")` → `Failed(Storage)`，文件删除，不自动重试。
- 网络 IOException 3 次 → `Failed(Network)`，共 3 次请求，文件保留。
- OkHttp Call 在协程取消后 1 秒内结束（通过 `invokeOnCompletion` 的 `call.cancel()`；测试断言读取线程退出）。

运行测试，确认失败。

#### Step 2：实现最终行为

`execute` 返回 `WorkerResult { Completed, Paused, Cancelled, Failed(kind) }`；`finally` 在 `withContext(NonCancellable)` 中按信号收尾；`CancellationSignal` 区分 pause/cancel；`currentCoroutineContext().job.invokeOnCompletion { call.cancel() }` 中断阻塞读；写入异常按 ENOSPC/IOException 分类。

#### Step 3：验证与整理

测试通过。

---

### Task 3-1：通知发布器、WakeLock 控制器

**依赖：** 无（纯 Android 基建；不依赖引擎）

**文件：**
- 创建：`app/src/main/java/com/dawnmoon/music_download/download/EngineSnapshot.kt`
- 创建：`app/src/main/java/com/dawnmoon/music_download/download/DownloadServiceActions.kt`（仅字符串常量）
- 创建：`app/src/main/java/com/dawnmoon/music_download/download/DownloadNotifier.kt`（接口）
- 创建：`app/src/main/java/com/dawnmoon/music_download/download/DownloadNotifications.kt`
- 创建：`app/src/main/java/com/dawnmoon/music_download/download/WakeLockController.kt`（接口）
- 创建：`app/src/main/java/com/dawnmoon/music_download/download/AndroidWakeLockController.kt`
- 修改：`app/src/main/AndroidManifest.xml`（新增 `WAKE_LOCK` 权限）
- 修改：`gradle/libs.versions.toml` 与 `app/build.gradle.kts`（新增 `androidx.test:rules` 到 `androidTestImplementation`）
- 测试：`app/src/androidTest/java/com/dawnmoon/music_download/download/DownloadNotificationsTest.kt`
- 测试：`app/src/androidTest/java/com/dawnmoon/music_download/download/WakeLockControllerTest.kt`

**提交信息：**

`feat: 添加下载通知与 WakeLock 控制器`

#### Step 1：编写失败测试

`DownloadNotificationsTest`（授予通知权限）：

- `build(snapshot(active=2, completed=5,total=12,bytes 已知), paused=false)` 的标题含"2"，正文含"5/12"，有 determinate 进度与"全部暂停" action。
- `paused=true` 时 action 为"全部继续"。
- 未知总字节时 indeterminate。
- channel 为 `downloads`，importance 为 LOW，无声。
- `clear()` 后通知被取消。

`WakeLockControllerTest`：

- acquire 两次 → 内部计数 2；release 一次仍持有；第二次 release 后 `isHeld=false`。
- `releaseAll` 直接释放。

运行仪器测试，确认编译失败（缺 rules 依赖）。

#### Step 2：实现最终行为

`DownloadNotifier` 接口；`EngineSnapshot` 数据类与 `DownloadServiceActions` 常量在本 Task 创建（供通知与后续服务共用）。`DownloadNotifications(context)` 实现渠道、`build/update/clear`、PendingIntent 打开 `MainActivity`、暂停/继续 action 使用 `DownloadServiceActions` 常量。`WakeLockController` 接口 + `AndroidWakeLockController(context)` 用引用计数持有 `PARTIAL_WAKE_LOCK`，`releaseAll` 供 `detach` 使用；Manifest 新增 `<uses-permission android:name="android.permission.WAKE_LOCK" />`。

#### Step 3：验证与整理

两组测试通过。

---

### Task 3-2：解析队列、下载池与 DownloadEngine

**依赖：** Task 2-1～2-3、Task 3-1、Stage 1 仓库

**文件：**
- 创建：`app/src/main/java/com/dawnmoon/music_download/download/DownloadController.kt`
- 创建：`app/src/main/java/com/dawnmoon/music_download/download/DownloadEngine.kt`
- 创建：`app/src/main/java/com/dawnmoon/music_download/download/ParseWorker.kt`
- 测试：`app/src/test/java/com/dawnmoon/music_download/download/DownloadEngineTest.kt`
- 测试：`app/src/test/java/com/dawnmoon/music_download/download/ConcurrencySettingTest.kt`

**提交信息：**

`feat: 实现解析队列、下载池与引擎调度`

#### Step 1：编写失败测试

使用 Fake `TaskRepository`、Fake `TrackResolver`、Fake `TaskFileStore`、Fake `TaskDownloader`、Fake `DownloadNotifier`、Fake `WakeLockController`、Fake `LinkResolver`：

- `requestDownload([1,2])`：解析串行，任务依次 `Queued→Waiting→Downloading→Completed`。
- 并发 1 时任务 2 在任务 1 完成后才开始；并发 2 时同时进行。
- 解析 `InvalidKey/QuotaExhausted/Forbidden`：后续 Queued 不再解析；`resumeAll` 后恢复。
- `pause` 取消 Job 并等待；`resume` 重新入队；`cancel` 删除文件；`retry` 先删 pending 文件再 `resetForRetry`。
- `pauseAll` 覆盖 Queued/Resolving/Waiting/Downloading；`resumeAll` 覆盖 Paused；`cancelAll`；`retryAllFailed`。
- 失败或取消后 limiter 许可被释放：并发 1 时连续两个失败任务后第三个仍能启动。
- 任务失败为 `Storage` 时设置池暂停标志，后续 Waiting 不再启动；`resumeAll` 清除。
- 通知：进度变化以 500ms 节流调用 `notifier.update`；活动数 0→1 调 `wakeLock.acquire()`，1→0 调 `release()`；`detach` 调 `releaseAll`。
- `snapshot` 的 `activeCount/completedInSession/totalInSession/bytesDone/bytesTotal/speedBytesPerSecond/isPaused` 在过程中正确，结束后归零。
- 空闲 2 秒后调用注入的 `ServiceStopper.stop()`（虚拟时间）。
- `ConcurrencySettingTest`：设置并发 2 → 最多 2；运行中改 1 不打断、新任务等待；改 5 后立即并行。

运行测试，确认编译失败。

#### Step 2：实现最终行为

`DownloadController` 接口 + `EngineSnapshot`；`ParseWorker` 按设计；`DownloadEngine : DownloadController` 用 `Channel<Long>(UNLIMITED)` 与 `AdaptiveLimiter`，`jobs` 映射、`snapshot` StateFlow、`attach/detach`、批量操作、`ServiceStopper`、`DownloadNotifier`、`WakeLockController`；`attach` 收集设置并发数并订阅 `repo.observeInProgress()`（500ms 节流刷新快照/通知/WakeLock）。

#### Step 3：验证与整理

两个测试类通过；补充无效任务 ID 不崩溃。

---

### Task 3-3：前台服务、容器装配与集成验证

**依赖：** Task 3-2、Stage 1 的容器

**文件：**
- 创建：`app/src/main/java/com/dawnmoon/music_download/download/DownloadService.kt`
- 修改：`app/src/main/AndroidManifest.xml`（服务声明）
- 修改：`app/src/main/java/com/dawnmoon/music_download/AppContainer.kt`（加入 fileStore/notifier/wakeLock/engine）
- 修改：`app/src/main/java/com/dawnmoon/music_download/AppDependencies.kt`（暴露 `downloadController`）
- 测试：`app/src/androidTest/java/com/dawnmoon/music_download/download/DownloadServiceTest.kt`
- 测试：`app/src/androidTest/java/com/dawnmoon/music_download/download/InterruptedTaskRecoveryTest.kt`

**提交信息：**

`feat: 添加下载前台服务并装配引擎`

#### Step 1：编写失败测试（仪器测试）

- 授予通知权限：`startForegroundService` 后服务进入前台、通知存在；`stopService` 后通知消失。
- 未授予通知权限：服务仍进入前台且不崩溃（通知不可见）。
- 服务 `onDestroy` 后引擎 `detach`，WakeLock 释放。
- 通过 `MusicDownloadApp.dependencies` 替换 Fake 容器后启动服务，断言 `attach` 被调用。
- `InterruptedTaskRecoveryTest`：写入 Downloading 任务，重新创建 `MusicDownloadApp`（或直接调用 Application 的恢复函数），状态为 Paused 且 `updatedAt` 更新。
- `start` 幂等：连续两次启动只有一个服务实例与一条通知。
- 无任务时 2 秒后服务自停（虚拟/真实等待 3 秒断言 `isForeground` 为 false）。

运行仪器测试，确认编译失败。

#### Step 2：实现最终行为

`DownloadService : LifecycleService()`：`onCreate` 创建渠道、`startForeground(..., FOREGROUND_SERVICE_TYPE_DATA_SYNC)`、`lifecycleScope.launch { engine.attach(this) }`；`onStartCommand` 处理暂停/继续 action，返回 `START_NOT_STICKY`；`onDestroy` 调用非 suspend 的 `engine.detach()`（内部取消 Job 并在 `NonCancellable` 中收尾、`releaseAll`）。`AppContainer` 增加 `MediaStoreTaskFileStore`、`DownloadNotifications`、`AndroidWakeLockController`、`DownloadEngine`；`DownloadController` 暴露给 UI。Manifest 声明 `exported=false`、`foregroundServiceType="dataSync"`。

#### Step 3：验证与整理

按计划头部运行构建、单元测试、仪器测试与 `lintDebug`，全部通过。
