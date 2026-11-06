# 音乐下载器 Stage 2 设计

> **重要变更与发布操作：**
> - 类别：系统权限与服务声明（`FOREGROUND_SERVICE_DATA_SYNC`）、公共存储写入（MediaStore 的 `IS_PENDING` 条目）、进程生命周期（重启后未完成任务转暂停）。
> - 影响：应用首次发布，无旧版本升级；无数据库结构变化（沿用 Stage 1 的 version 1）；无专项公告、无恢复限制。
> - 更新代码之外的操作：无。通知权限由 Stage 3 在运行时请求；本 Stage 的测试在模拟器上无需该权限即可运行前台服务（通知不可见，但服务有效）。
> - 评估状态：已评估。

**需求索引：** `docs/brainstorming/2026-11-07-music-downloader-app.md`
**Stage：** 2 / 3
**依赖：** Stage 1

## Stage 职责

把 `Queued` 任务变成 `Music/` 下可播放的文件：

- `dataSync` 前台服务与引擎生命周期。
- 解析队列（串行 + 全局限速）与下载池（并发 1-5，默认 3）。
- Range 断点续传、直链过期换链续传、瞬时错误自动重试。
- MediaStore 写入：pending 条目、重名序号、大小与 md5 校验、取消清理。
- 单个/全部暂停、继续、取消、重试。
- 进度通知（单条汇总）。

不包含任何 Compose 界面（Stage 3 提供）。

## 最终架构位置

```
com.dawnmoon.music_download.download
├── DownloadService.kt         LifecycleService，dataSync 前台服务
├── DownloadEngine.kt          队列、下载池、任务作业调度（AppContainer 单例）
├── AdaptiveLimiter.kt         可调容量的并发门闸
├── DownloadWorker.kt          单任务下载实现
├── TaskFileStore.kt           MediaStore 抽象接口 + Android 实现
├── FileNamer.kt               重名序号决策
└── DownloadNotifications.kt   通知渠道与通知构造
```

调用关系：

```
UI / 分享 Activity
   └─ TaskRepository.enqueue(...)  -> EnqueueOutcome.Enqueued(created)
        └─ DownloadEngine.start(context)  -> startForegroundService(DownloadService)
        └─ DownloadEngine.requestDownload(created)
DownloadService.onCreate
   └─ engine.attach(scope)  -> 启动解析 worker 与调度循环
        ├─ ParseWorker: Queued -> Resolving -> Waiting
        └─ DownloadPool: Waiting -> Downloading -> Completed/Failed/Paused/Cancelled
```

## 接口定义

### 服务与引擎

```kotlin
class DownloadService : LifecycleService() {
    override fun onCreate()          // startForeground(通知), engine.attach(serviceScope)
    override fun onStartCommand(...) // START_NOT_STICKY；空服务自停
    companion object {
        fun start(context: Context)  // ContextCompat.startForegroundService
        fun stop(context: Context)
    }
}

interface DownloadController {
    val snapshot: StateFlow<EngineSnapshot>
    fun start(context: Context)
    fun requestDownload(ids: List<Long>)
    fun pause(id: Long); fun resume(id: Long); fun cancel(id: Long); fun retry(id: Long)
    fun pauseAll(); fun resumeAll(); fun cancelAll(); fun retryAllFailed()
}

data class EngineSnapshot(
    val activeCount: Int,
    val completedInSession: Int,
    val totalInSession: Int,
    val bytesDone: Long,
    val bytesTotal: Long?,
    val speedBytesPerSecond: Long,
    val isPaused: Boolean,
)

class DownloadEngine(
    private val repo: TaskRepository,
    private val resolver: TrackResolver,
    private val settings: AppSettings,
    private val fileStore: TaskFileStore,
    private val downloader: TaskDownloader,
    private val notifications: DownloadNotifier,
    private val clock: AppClock,
) : DownloadController {
    override val snapshot: StateFlow<EngineSnapshot>

    override fun start(context: Context)        // 幂等启动服务
    suspend fun attach(scope: CoroutineScope)   // 服务内调用；重复调用忽略
    fun detach()                                // 服务销毁：取消作业，运行中任务置 Paused（内部启动清理协程）

    override fun requestDownload(ids: List<Long>) // 唤醒调度（入队或继续）
    override fun pause(id: Long)
    override fun resume(id: Long)
    override fun cancel(id: Long)
    override fun retry(id: Long)
    override fun pauseAll()
    override fun resumeAll()
    override fun cancelAll()
    override fun retryAllFailed()
    suspend fun shutdownIfIdle()
}
```

- `DownloadEngine` 实现 `DownloadController` 接口，供 Stage 3 的 ViewModel 注入 Fake。
- 引擎实例由 `AppContainer` 持有，服务只是宿主。服务创建时 `attach`，销毁时 `detach`。
- `attach` 期间引擎收集 `repo.observeInProgress()`，以 500ms 节流更新 `snapshot`、通知与 WakeLock（活动下载 0→1 时 acquire，1→0 时 release）。
- 引擎在 `attach` 时收集 `settings.maxConcurrentDownloads`，变化时调用 `AdaptiveLimiter.setPermits`。
- 引擎不直接写 DAO，全部状态写入调用 `TaskRepository` 提供的接口；状态机校验在仓库内。
- 任务失败后引擎读取 `repo.byId(id).failureKind`；若为 `Storage`，设置下载池暂停标志，阻止新任务启动，直到用户「继续」。
- UI 与分享入口在入队成功后调用 `start` 与 `requestDownload`；「继续」「全部继续」「重试」同样通过引擎触发。

### 解析队列

```kotlin
class ParseWorker(
    private val repo: TaskRepository,
    private val resolver: TrackResolver,
    private val settings: AppSettings,
    private val notifications: DownloadNotifier,
) {
    suspend fun run(queue: ReceiveChannel<Long>)
}
```

流程：

1. 从 `Channel<Long>(UNLIMITED)` 取任务 ID，读取任务；状态不是 `Queued` 则丢弃。
2. 读取 Key；缺失 → `repo.markFailed(id, InvalidKey, ...)`，并置 `parsePaused = true`。
3. `repo.markResolving(id)`。
4. `resolver.resolve(songId, quality, key)`：内部经 `ApiCaller` 完成限速、429 单次重试与额度写入。
5. 成功 → `repo.completeResolve(id, track)`（`Waiting`），交给下载池。
6. `InvalidKey`/`QuotaExhausted`/`Forbidden` → `markFailed`，暂停整个解析队列。用户处理 Key/额度后任意 `resume`、`retry`、`requestDownload` 或 `resumeAll` 都会清除 `parsePaused` 并把 `Queued` 任务重新投递到解析队列。
7. 其他失败 → `markFailed`；队列继续。

短链已在 Stage 1 入队阶段解析完成，解析队列只处理有 `songId` 的任务。

### 下载池

```kotlin
class AdaptiveLimiter(initialPermits: Int) {
    fun setPermits(count: Int)   // 1..5；调小时不中断在途任务
    suspend fun acquire()
    fun release()
}
```

- 下载任务进入 `Channel<Long>(UNLIMITED)`，调度循环 `acquire()` 后启动独立 Job；结束 `release()`。
- 并发数变化即时生效：调大立即释放差额许可，调小只影响后续获取。
- 每个任务 Job 记录在 `jobs: MutableMap<Long, Job>`；暂停/取消通过取消 Job 实现。

### 单任务下载

```kotlin
interface TaskFileStore {
    suspend fun createPending(displayName: String, mimeType: String): CreatedFile
    suspend fun openAppend(file: CreatedFile): OutputStream
    suspend fun openTruncate(file: CreatedFile): OutputStream
    suspend fun complete(file: CreatedFile)
    suspend fun delete(file: CreatedFile)
    /** 行不存在返回 -1。 */
    suspend fun sizeOf(file: CreatedFile): Long
    suspend fun md5Of(file: CreatedFile): String
    /** true = 已删除或本就不存在；false = 删除失败（调用方必须保留记录）。 */
    suspend fun deleteById(mediaStoreId: Long): Boolean
}

/** 文件句柄只含持久化标识；URI 由 TaskFileStore 实现内部按 mediaStoreId 解析。 */
data class CreatedFile(val mediaStoreId: Long, val displayName: String)

interface TaskDownloader {
    suspend fun execute(taskId: Long): WorkerResult
}

sealed interface WorkerResult {
    data object Completed : WorkerResult
    data object Paused : WorkerResult
    data object Cancelled : WorkerResult
    data class Failed(val kind: FailureKind) : WorkerResult
}

class DefaultDownloadWorker(
    private val repo: TaskRepository,
    private val resolver: TrackResolver,
    private val settings: AppSettings,
    private val fileStore: TaskFileStore,
    private val namer: FileNamer,
    private val client: OkHttpClient,
    private val clock: AppClock,
) : TaskDownloader {
    override suspend fun execute(taskId: Long): WorkerResult
}
```

执行步骤：

1. 读取任务；`status` 必须为 `Waiting`（新任务）或 `Paused`（续传），置 `Downloading`。
2. 若 `mediaStoreId == null`：`FileNameBuilder` 得主名 → `repo.allocateFileName(base)` 得到唯一名 → `createPending`（`RELATIVE_PATH=Music/`、`IS_MUSIC=1`、Audio collection）→ `repo.attachFile(...)`。
3. 续传校准：只要 `bytesDownloaded > 0`，先 `fileStore.sizeOf(CreatedFile(mediaStoreId, fileName))`；文件不存在 → 重建 pending 条目并清零进度；实际大小与数据库不一致 → 以实际大小为 `bytesDownloaded`。
4. 构造请求：`GET streamUrl`；`bytesDownloaded > 0` 时加 `Range: bytes=<bytes>-`；有 `etag` 时加 `If-Range`。
5. 响应处理：
   - `206`：`Content-Range` total 与已知 `sizeBytes` 不一致时视为直链指向其他文件，按过期处理；否则追加写入。
   - `200`：服务端忽略 Range → `openTruncate` 从头写，`bytesDownloaded = 0`。
   - `403/404/410`：直链过期 → `repo.markResolving`（`Downloading -> Resolving`，保留进度与 etag）→ `resolver.resolve` 重新解析 → 若新结果的 `format`、`md5`（或 `md5` 缺失时的 `sizeBytes`）与当前文件不同 → 删除旧 pending 条目、清零进度、按新格式重建条目；否则 `repo.updateStream` 只更新直链/etag → `repo.startDownloading` → 继续；再失败 `Failed(StreamExpired)`。
   - `5xx`、`IOException`：进入瞬时重试。
   - `416`：`openTruncate` 清空，从头下载（最多 1 次）。
6. 循环读取 64 KiB 写入；每 500ms 或每 1 MiB 调 `repo.updateProgress` 并刷新通知；响应协程取消。
7. 完成校验：已知 `sizeBytes` 时实际大小必须相等；已知 `md5` 时流式读取完整文件计算并比对。不符 → `fileStore.delete` + `markFailed(Integrity)`。
8. `fileStore.complete`（`IS_PENDING=0`），`repo.markCompleted(id, fileName, mediaStoreId, now)`。
9. 瞬时重试：最多 2 次，间隔 2s、5s，沿用 Range；仅对网络与 5xx，401/402/403/404/416 不重试。

进度与速度只在内存计算 1 秒窗口，速度不落库。

### 暂停 / 继续 / 取消 / 重试

| 操作 | 行为 |
| --- | --- |
| 暂停 | 引擎标记 `pauseRequested` 并取消 Job，随后 `job.join()`；Job 的 `finally` 在 `withContext(NonCancellable)` 中关闭流并调用 `repo.markPaused`，保留 pending 文件与字节；OkHttp `Call` 通过 `invokeOnCompletion` 调用 `cancel()` 中断阻塞读 |
| 继续 | 已暂停：`repo.resume` 按是否已解析决定 `Queued` 或 `Waiting`，再 `requestDownload` |
| 取消 | 引擎取消 Job 并 `job.join()`；Job 在 `NonCancellable` 中删除 pending 文件并 `repo.markCancelled`（保留行）；引擎不重复删除 |
| 重试 | 仅 `Failed`：删除 pending 条目、`repo.resetForRetry`（清空解析/进度/文件字段并转 `Queued`）→ 重新入队 |
| 全部暂停 | 对 `Queued/Resolving/Waiting/Downloading` 暂停 |
| 全部继续 | 对 `Paused` 调 `repo.resume` 并入队 |
| 全部取消 | 取消全部未完成任务，删除 pending 文件，状态 `Cancelled` |
| 全部重试 | 对所有 `Failed` 调 `repo.resetForRetry` 并重新入队 |

- 取消与暂停通过 Job 的 `cancelReason` 区分，避免取消被误记为暂停。
- 引擎持有 `TaskFileStore`，用于取消时删除 pending 条目与 `cancelAll`。
- 已完成任务不提供引擎操作；文件删除由 Stage 3 直接调用 `TaskFileStore.deleteById`。

### 重名与文件名

```kotlin
class FileNamer(private val repo: TaskRepository) {
    /** 委托 repo.allocateFileName 的互斥临界区，返回 baseName 或 baseName (n)。 */
    suspend fun uniqueName(base: BaseFileName): String
}
```

- 依据数据库中相同 `fileName` 计数，从 ` (1)` 起尝试；`Cancelled` 任务的文件名不占用。
- 无法看到其他应用创建的同名文件（无 `READ_MEDIA_AUDIO`），可能同名并存；这是 ADR 0002 的已知限制。
- `Completed` 后文件名不可变。

### 通知

```kotlin
interface WakeLockController {
    fun acquire()
    fun release()
    fun releaseAll()
}

fun interface ServiceStopper { fun stop() }

interface DownloadNotifier {
    fun update(snapshot: EngineSnapshot, paused: Boolean)
    fun clear()
}

class DownloadNotifications(private val context: Context) : DownloadNotifier {
    fun ensureChannel()
    fun build(snapshot: EngineSnapshot, paused: Boolean): Notification
    fun update(snapshot: EngineSnapshot, paused: Boolean)
    fun clear()
}
```

- 标题：`正在下载 2 首`；正文：`已完成 5/12 · 3.2 MB/s`。
- 进度：已知总字节用 determinate，否则 indeterminate。
- Action：`全部暂停` 或 `全部继续`，通过 `PendingIntent` 发 service action。
- 点击打开 `MainActivity`（Stage 3）；Stage 2 阶段用 launcher intent，Stage 3 接入后不改。
- 全部结束：取消通知并 `stopSelf`；不额外发"全部完成"通知。
- Wake lock：引擎在活动下载数从 0 变为 1 时调用 `WakeLockController.acquire()`，回到 0 或 `detach` 时 `release()`；服务 `onDestroy` 调用 `detach`，保证不泄漏。

### 服务生命周期

- `attach` 后 2 秒内若无任务，`stopSelf`；`onStartCommand` 返回 `START_NOT_STICKY`。
- 每次任务结束、队列事件后检查：无运行中 Job 且两个队列为空 → 计划停止。
- `stopSelf` 前 `stopForeground(STOP_FOREGROUND_REMOVE)` 并 `clear()`。
- 进程被回收后重启：`AppContainer` 初始化调用 `repo.markUnfinishedAsPaused`；服务不自动启动，用户可「全部继续」或提交新任务。与需求"重启后未完成任务为暂停"一致。

## 数据流与数据安全

- Worker 只通过 `TaskRepository` 写状态。
- 文件只写自己创建的 pending 条目；完成前对其他应用不可见。
- 校验失败或取消删除自己创建的条目；绝不删除其他应用的文件。
- 历史删除文件仅通过 `mediaStoreId`，且需用户明确选择；文件已不存在视为成功。
- `streamUrl` 是时效链接，不进入日志；Key 不进入数据库。

## 错误处理

| 情况 | 任务结果 | 自动行为 |
| --- | --- | --- |
| 网络中断/超时 | 重试后 `Failed(Network)` | 2 次退避重试，续传 |
| 5xx | `Failed(ServiceUnavailable)` | 同上 |
| 403/404/410 直链过期 | 重新解析一次；仍失败 `Failed(StreamExpired)` | 消耗 1 次额度 |
| 416 | 从头下载一次 | 无 |
| 大小/md5 不符 | `Failed(Integrity)` | 删除文件，不重试 |
| 401/402/403 API | `Failed(InvalidKey/QuotaExhausted/Forbidden)` | 不重试；解析队列暂停 |
| 429 | `Failed(RateLimited)` | `ApiCaller` 已按 Retry-After 重试一次 |
| 磁盘空间不足 | `Failed(Storage)` | 删除 pending 条目，引擎暂停下载池（保留暂停标志直到用户继续） |
| 服务被杀 | 下次启动转 `Paused` | 无 |

## 与其他 Stage 的契约

- 对 Stage 3：`DownloadController` 接口（`snapshot`、`start`、`requestDownload`、`pause/resume/cancel/retry`、`pauseAll/resumeAll/cancelAll/retryAllFailed`）、`TaskFileStore.deleteById`。
- 对 Stage 1：只使用既有接口；新增类放在 `download` 包，不改动 Stage 1 合同。
- Stage 3 负责在开始下载前请求 `POST_NOTIFICATIONS`；本 Stage 不请求权限。

## 测试策略

JVM 单元测试（`FakeTaskFileStore` 基于临时文件，MockWebServer 提供音频流）：

- `DownloadWorkerTest`：完整下载成功并校验 md5；206 续传；200 忽略 Range 后重置；403 换链（状态经 `Downloading -> Resolving -> Downloading`）后续传；换链后格式/大小变化时重建文件；续传前按实际文件大小校准、文件缺失时重建；5xx 重试两次后成功；md5 不符失败并删除；暂停后 `Paused` 且保留字节；取消后 `Cancelled` 且文件删除；写入异常。
- `FileNamerTest`：无冲突、序号递增、已取消任务释放名称。
- `AdaptiveLimiterTest`：并发上限、动态增减、release 归还。
- `EngineQueueTest`（Fake 仓库/解析器）：Queued → Waiting → Downloading、401 暂停解析队列、空闲自停回调、暂停/继续/取消的 Job 语义。
- `ProgressThrottleTest`：500ms/1MiB 节流与最终值写入。

仪器测试：

- `TaskFileStoreTest`：创建 pending、追加写、完成、查询大小、md5、按 id 删除；`Music/` 路径与 MIME 正确。
- `DownloadServiceTest`：用 `GrantPermissionRule` 授予通知权限（API 33+），断言服务处于前台、通知存在、任务结束后自停；未授权场景单独断言服务仍运行。
- `InterruptedTaskRecoveryTest`：写入 `Downloading` 行后重启 Application，状态为 `Paused`。

端到端手测在 Stage 3 完成后执行（本 Stage 没有可输入链接的界面）：`分享あたらよ的单曲《夏霞》: https://163cn.tv/bkp6m2as (来自@网易云音乐)`，exhigh 下载，确认 `Music/夏霞_あたらよ.mp3` 存在且大小/md5 与 API 一致。

## 明确排除

- 不为 WorkManager 或 DownloadManager 保留并行实现。
- 不实现开机自启、定时任务、后台预取。
- 不实现多通知或通知内单任务控制。
- 不实现 pending 条目的额外清理策略（依赖系统 7 天清理；取消时手动删除）。
- 不请求 `READ_MEDIA_AUDIO`/`WRITE_EXTERNAL_STORAGE`。
- 不实现"全部完成"通知。
