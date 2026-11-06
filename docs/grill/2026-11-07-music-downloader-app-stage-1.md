# 音乐下载器 Stage 1 设计

> **重要变更与发布操作：**
> - 类别：本地数据库（Room 全新建库，version 1，无既有数据迁移）、外部依赖（ChKSz API）、构建环境（compileSdk 37.1 平台、Kotlin/KSP 版本约束）。
> - 影响：全新应用，无旧版本升级、无恢复限制、无不兼容变更、无专项公告。
> - 更新代码之外的操作：本机已安装 `platforms/android-37.1`；开发机需 JDK 21（Gradle toolchain 21，已配置）。
> - 执行时机：首次构建前。
> - 评估状态：已评估。

**需求索引：** `docs/brainstorming/2026-11-07-music-downloader-app.md`
**Stage：** 1 / 3
**依赖：** 无

## Stage 职责

建立最终系统的工程基础，并提供后续 Stage 直接使用的领域模型、数据层与网络层：

- Compose + Material 3 工程配置与版本约束。
- 领域纯逻辑：链接提取、短链解析、音质降级策略、文件名生成、任务状态机。
- ChKSz API 客户端：解析、搜索校验、错误映射、额度记录。
- 全局限速器与 429 单次重试包装。
- Room 数据库（任务与额度）与 DAO。
- DataStore 设置与 API Key 存储。
- 任务仓库：入队、去重、状态持久化与校验、查询；供 Stage 2 引擎调用的状态写入接口。
- 手动依赖容器。

本 Stage 不包含：前台服务、实际下载、MediaStore 写入、通知、面向用户的业务界面（仅保留可启动的空 Activity）。Stage 2、3 消费本 Stage 的接口。

## 最终架构位置

```
com.dawnmoon.music_download
├── MusicDownloadApp.kt          Application，持有 AppContainer
├── core/                        AppClock、DispatcherProvider、Result 辅助
├── domain/
│   ├── model/                   SongId、SongLink、MusicQuality、AudioFormat、TaskStatus、
│   │                             DownloadTask、Quota、BaseFileName、ThemeMode
│   ├── LinkExtractor.kt         文本 -> SongLink 列表
│   ├── FileNameBuilder.kt       -> BaseFileName
│   ├── QualityResolver.kt       降级策略，组合 ChkszApi 与 RateLimiter
│   └── TaskTransitions.kt       合法状态迁移表
├── data/
│   ├── remote/                  ChkszApi、DTO、ApiFailure、ShortLinkResolver、ApiCaller(429)
│   ├── local/                   Room 实体/DAO/数据库、DataStore 设置
│   └── repository/              TaskRepository、AppSettings、QuotaStore
└── ui/                          Stage 3 填充；本 Stage 仅保留可启动的空 Activity
```

依赖注入使用 `AppContainer`（Application 中懒加载单例，实现 `AppDependencies` 接口，供仪器测试替换），不引入 Hilt：项目规模小，且 AGP 9 内置 Kotlin 与 Hilt/KSP 组合存在额外兼容成本。

### 构建配置（冒烟测试已确认可编译、可运行）

- AGP 9.2.1，内置 Kotlin；Compose 与序列化编译器插件版本 2.4.21（实测会把实际编译版本提升到 2.4.21），KSP 2.3.13，Room 2.8.5（`androidx.room` 插件导出 schema 到 `app/schemas`）。
- `compileSdk = 37.1`（最新 AndroidX 要求 37）；`targetSdk = 36`，`minSdk = 29`。
- Compose BOM 2026.09.00，Material3 1.4.0，activity-compose 1.13.0，lifecycle 2.11.0，DataStore 1.3.0，OkHttp 5.5.1，kotlinx-serialization-json 1.12.0，Coil 3.6.3。
- 不强制 `kotlin-stdlib`（见 ADR 0003）。
- 单元测试：JUnit4、kotlinx-coroutines-test、Turbine、MockWebServer3。Robolectric 4.17 在当前 JDK 21 环境无法加载 native runtime，不采用。
- 仪器测试：androidx.test junit + espresso，用于 Room DAO 与 MediaStore（Stage 2）验证。

## 接口定义

### 领域模型

```kotlin
@JvmInline
value class SongId(val value: Long)

sealed interface SongLink {
    val sourceUrl: String
    data class ShortLink(override val sourceUrl: String, val code: String) : SongLink
    data class Direct(override val sourceUrl: String, val songId: SongId) : SongLink
}

enum class MusicQuality(val apiValue: String, val label: String) {
    Standard("standard", "标准"),
    Exhigh("exhigh", "极高"),
    Lossless("lossless", "无损"),
    Hires("hires", "Hi-Res"),
    Jyeffect("jyeffect", "沉浸环绕"),
    Sky("sky", "沉浸天空"),
    Jymaster("jymaster", "超清母带");

    fun downgradePath(): List<MusicQuality>   // 从自身到 standard

    companion object {
        val default: MusicQuality = Exhigh
        fun fromApiValue(value: String?): MusicQuality?
    }
}

enum class AudioFormat(val extension: String, val mimeType: String) {
    Mp3("mp3", "audio/mpeg"),
    Flac("flac", "audio/flac"),
    Unknown("bin", "application/octet-stream");

    companion object {
        /** 扩展名优先；无扩展名时按 fallbackQuality 推断（flac 级 -> flac，其余 -> mp3）。 */
        fun fromUrl(url: String, fallbackQuality: MusicQuality): AudioFormat
        fun fromExtension(ext: String?): AudioFormat
    }
}

enum class TaskStatus {
    Queued, Resolving, Waiting, Downloading, Paused, Completed, Failed, Cancelled;
    val isActive: Boolean          // Queued/Resolving/Waiting/Downloading
    val isTerminal: Boolean        // Completed/Cancelled
}

enum class ThemeMode { System, Light, Dark }

data class Quota(val freeRemaining: Int?, val paidRemaining: Int?, val limitPerMinute: Int?)

data class ResolvedTrack(
    val songId: SongId,
    val title: String,
    val artist: String,
    val album: String?,
    val artworkUrl: String?,
    val requestedQuality: MusicQuality,
    val actualQuality: MusicQuality,
    val format: AudioFormat,
    val streamUrl: String,
    val sizeBytes: Long?,
    val md5: String?,
)

@JvmInline
value class BaseFileName(val value: String)   // 不含目录，不含重名序号

/** 仓库对外暴露的领域对象，由实体映射。 */
data class DownloadTask(
    val id: Long,
    val songId: SongId?,
    val sourceUrl: String,
    val requestedQuality: MusicQuality,
    val actualQuality: MusicQuality?,
    val title: String?,
    val artist: String?,
    val album: String?,
    val artworkUrl: String?,
    val format: AudioFormat?,
    val sizeBytes: Long?,
    val md5: String?,
    val streamUrl: String?,          // 时效直链，仅下载层使用
    val etag: String?,
    val bytesDownloaded: Long,
    val status: TaskStatus,
    val mediaStoreId: Long?,
    val fileName: String?,
    val failureKind: FailureKind?,
    val failureMessage: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long?,
)
```

`AppClock` 提供 `nowMillis()` 与 `elapsedRealtime()`，测试中可注入 FixedClock。

### 链接与短链

```kotlin
class LinkExtractor {
    fun extract(text: String): ExtractionResult
}

data class ExtractionResult(
    val links: List<SongLink>,
    val ignoredUrlCount: Int,
    val duplicateCount: Int,
)

interface LinkResolver {
    /** 不跟随跳转，读取 302/301 的 Location 与 id 参数。 */
    suspend fun resolve(link: SongLink.ShortLink): ApiResponse<SongId>
}

class HttpShortLinkResolver(private val client: OkHttpClient) : LinkResolver { ... }
```

识别的形式（大小写不敏感，允许 http/https）：

- `163cn.tv/<code>`，code 为 `[A-Za-z0-9]+`。
- `music.163.com/song?id=<id>`、`music.163.com/#/song?id=<id>`、`music.163.com/m/song?id=<id>`，以及 `y.music.163.com` 前缀。
- 其余文字与 URL 忽略并计数。

短链解析不消耗 ChKSz 额度，在入队时立即执行，并发限制 2（不占限速令牌）。失败映射：

- `IOException` → `ApiFailure.Network`（可重试）。
- 非 301/302、无 `Location` 或无 `id` 参数 → `ApiFailure.LinkUnsupported`。
- 其他 HTTP 状态 → `ApiFailure.NotFound` 或按状态映射。

### API 客户端

```kotlin
interface ChkszApi {
    suspend fun fetchSong(songId: SongId, quality: MusicQuality, apiKey: String): ApiResponse<ResolvedTrack>
    /** key 校验：keyword 固定为 "music"，limit=1。 */
    suspend fun validateKey(apiKey: String): ApiResponse<Unit>
}

class DefaultChkszApi(
    private val client: OkHttpClient,
    private val baseUrl: HttpUrl = "https://api.chksz.com".toHttpUrl(),
    private val json: Json,
) : ChkszApi { ... }

sealed interface ApiResponse<out T> {
    data class Success<T>(val value: T, val quota: Quota?) : ApiResponse<T>
    data class Failure(val failure: ApiFailure, val quota: Quota?) : ApiResponse<Nothing>
}

sealed interface ApiFailure {
    val message: String
    data class InvalidKey(override val message: String) : ApiFailure              // 401
    data class QuotaExhausted(override val message: String) : ApiFailure         // 402
    data class Forbidden(override val message: String) : ApiFailure              // 403
    data class RateLimited(val retryAfterSeconds: Long?, override val message: String) : ApiFailure // 429
    data class InvalidRequest(override val message: String) : ApiFailure         // 400
    data class QualityUnavailable(override val message: String) : ApiFailure     // 163_music 的 404
    data class NotFound(override val message: String) : ApiFailure               // 其他 404
    data class ServiceUnavailable(override val message: String) : ApiFailure     // 503
    data class LinkUnsupported(override val message: String) : ApiFailure
    data class Network(val cause: IOException) : ApiFailure { override val message = cause.message ?: "network" }
    data class Malformed(override val message: String) : ApiFailure
}
```

约束：

- 所有请求带 `apikey` 查询参数；日志与异常信息必须把 `apikey` 值替换为 `***`。不启用 `HttpLoggingInterceptor`；自定义 redact 的调试日志仅在 debug 构建启用。
- `Json { ignoreUnknownKeys = true }`；判定以 HTTP 状态码为准，错误信息取 `msg` 原文。
- 额度从每次响应头读取（成功与失败都读），由调用方交给 `QuotaStore`。
- 超时：连接 15s、读 30s、写 30s、整体 60s。
- `163_music` 的 404 只有 `msg` 含 `Music URL not found` 才是 `QualityUnavailable`，否则 `NotFound`。
- `data.level` 映射不到已定义音质时按 `requestedQuality` 记录。
- `format` 取 `data.url` 路径扩展名；无扩展名时 flac 级 → flac，其余 → mp3。
- `ChkszApi` 实现本身不做任何重试；429 重试由 `ApiCaller` 统一负责。

### 429 重试包装

```kotlin
class ApiCaller(
    private val rateLimiter: RateLimiter,
    private val quotaStore: QuotaStore,
) {
    /** 在限速器内执行请求；429 时 penalize 后重试一次，仍失败则返回 RateLimited。 */
    suspend fun <T> call(request: suspend () -> ApiResponse<T>): ApiResponse<T>
}
```

- `DefaultQualityResolver` 与 `DefaultKeyValidator` 都只通过 `ApiCaller` 访问 API。
- 每次 `ApiResponse` 返回后（成功或失败），若带 quota 则写入 `QuotaStore`。

### 限速器

```kotlin
class RateLimiter(
    private val minIntervalMillis: Long = 3_200,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun <T> run(block: suspend () -> T): T
    suspend fun penalize(retryAfterMillis: Long)
}
```

- 调用起点间隔 ≥ 3.2s，任意 60 秒滑动窗口内最多 19 次，满足 ≤ 20 次/分钟。
- Mutex 保证全局串行；`penalize` 把下一次允许时间推后。

### 音质解析策略

```kotlin
interface TrackResolver {
    suspend fun resolve(songId: SongId, requested: MusicQuality, apiKey: String): ApiResponse<ResolvedTrack>
}

class DefaultQualityResolver(
    private val api: ChkszApi,
    private val apiCaller: ApiCaller,
) : TrackResolver { ... }
```

- 降级链：`jymaster, sky, jyeffect, hires, lossless, exhigh, standard` 中从 requested 起的后缀。
- 每一级调用都经 `ApiCaller`（限速 + 429 重试一次）。
- `QualityUnavailable` → 继续降级；其他失败立即返回（包括 429 重试一次后仍失败的 `RateLimited`，不再尝试更低音质）。
- 服务端静默降级（如请求 hires 返回 lossless）直接接受，`actualQuality` 记录返回值。

### 文件名

```kotlin
object FileNameBuilder {
    const val MAX_NAME_BYTES: Int = 120

    fun build(title: String, artist: String, format: AudioFormat, songIdForFallback: SongId): BaseFileName
}
```

1. 曲名、艺术家分别处理：非法字符 `\ / : * ? " < > |` 与控制字符替换为 `_`；制表/换行视为空白；连续空白合并；去掉首尾空格与结尾的点。
2. 艺术家为空时只保留曲名。
3. `/` 分隔的多艺术家自然变为 `_` 连接。
4. 拼成 `{曲名}_{艺术家}.{ext}`；总字节预算 120（UTF-8），先扣除 `.{ext}` 与分隔符 `_`，剩余预算先给艺术家（最多 40 字节），余下给曲名；仍超长时继续截断艺术家；截断不拆分多字节字符，结果不以空格或下划线结尾。
5. 重名序号由下载层追加，本函数不处理。
6. 处理后为空时使用 `163_<songId>` 兜底。

### 任务状态机

`TaskTransitions.isAllowed(from, to)` 纯函数，仓库在事务中校验：

| From | 允许的 To |
| --- | --- |
| Queued | Resolving, Paused, Failed, Cancelled |
| Resolving | Waiting, Downloading, Paused, Failed, Cancelled |
| Waiting | Downloading, Paused, Failed, Cancelled |
| Downloading | Completed, Paused, Failed, Cancelled, Resolving |
| Paused | Queued, Waiting, Cancelled |
| Failed | Queued, Cancelled |
| Completed | 终态 |
| Cancelled | 终态，可删除 |

- `Downloading -> Resolving`：直链过期换链，保留 `bytesDownloaded`、`etag`、`format`；成功后 `Resolving -> Downloading` 续传。
- `Paused -> Queued` 用于未解析的任务，`Paused -> Waiting` 用于已解析、只需续传的任务。

### 数据层

```kotlin
@Entity(tableName = "download_task", indices = [Index("songId"), Index("status"), Index("createdAt"), Index("completedAt")])
data class DownloadTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: Long?,
    val sourceUrl: String,
    val requestedQuality: String,
    val actualQuality: String?,
    val title: String?,
    val artist: String?,
    val album: String?,
    val artworkUrl: String?,
    val format: String?,
    val sizeBytes: Long?,
    val md5: String?,
    val streamUrl: String?,
    val etag: String?,
    val status: String,
    val bytesDownloaded: Long,
    val mediaStoreId: Long?,
    val fileName: String?,
    val failureKind: String?,
    val failureMessage: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long?,
)

@Entity(tableName = "quota_snapshot")
data class QuotaSnapshotEntity(
    @PrimaryKey val id: Int = 0,
    val freeRemaining: Int?,
    val paidRemaining: Int?,
    val limitPerMinute: Int?,
    val updatedAt: Long,
)
```

`DownloadTaskDao`：

```kotlin
@Dao interface DownloadTaskDao {
    fun observeInProgress(): Flow<List<DownloadTaskEntity>>
    fun observeCompleted(): Flow<List<DownloadTaskEntity>>
    suspend fun byId(id: Long): DownloadTaskEntity?
    suspend fun nextQueued(): DownloadTaskEntity?                       // ORDER BY createdAt LIMIT 1
    suspend fun activeBySongId(songId: Long): DownloadTaskEntity?       // 非 Completed/Cancelled
    suspend fun allInProgress(): List<DownloadTaskEntity>
    suspend fun insert(task: DownloadTaskEntity): Long
    suspend fun update(task: DownloadTaskEntity)
    suspend fun delete(id: Long)
    suspend fun clearCompleted()
    suspend fun markUnfinishedAsPaused(now: Long)                       // Queued/Resolving/Waiting/Downloading -> Paused
    suspend fun countCompleted(): Int
    suspend fun isFileNameTaken(name: String): Boolean                  // status != 'Cancelled'
}
```

- `DownloadDatabase` version 1，`exportSchema = true`，schema 输出到 `app/schemas`。无迁移、无 `fallbackToDestructiveMigration`。
- 队列唯一性（`songId` 在非 `Completed`/`Cancelled` 任务中唯一）由仓库的 `Mutex` + DAO 上的 `@Transaction insertIfNoActive` 保证；短链在插入前已解析，所以 `songId` 在插入时已知。并发入队有专门测试。
- 应用启动时调用 `markUnfinishedAsPaused`：所有未完成任务（`Queued`、`Resolving`、`Waiting`、`Downloading`）转为 `Paused`，`Failed`、`Cancelled` 保持。服务不会自动启动，用户可通过"全部继续"或新任务启动。这样与需求"重启后未完成任务为暂停状态"一致。

`QuotaStore`（Room）：

```kotlin
interface QuotaStore {
    val quota: Flow<Quota?>
    suspend fun record(quota: Quota)   // 空值字段保留上一次的值；成功与失败响应都会调用
}

class RoomQuotaStore(private val dao: QuotaDao, private val clock: AppClock) : QuotaStore { ... }
```

### 设置

```kotlin
interface AppSettings {
    val apiKey: Flow<String?>
    val defaultQuality: Flow<MusicQuality>        // 默认 Exhigh
    val maxConcurrentDownloads: Flow<Int>         // 默认 3，写入时钳制到 1..5
    val themeMode: Flow<ThemeMode>                // 默认 System
    val notificationAsked: Flow<Boolean>

    suspend fun setApiKey(value: String)
    suspend fun setDefaultQuality(value: MusicQuality)
    suspend fun setMaxConcurrentDownloads(value: Int)
    suspend fun setThemeMode(value: ThemeMode)
    suspend fun markNotificationAsked()
}

class DataStoreSettings(private val dataStore: DataStore<Preferences>) : AppSettings { ... }

interface KeyValidator {
    sealed interface Result {
        data class Valid(val quota: Quota?) : Result
        data class InvalidKey(val message: String) : Result
        data class Indeterminate(val message: String) : Result   // 网络/额度/限流等，Key 可能有效
    }
    suspend fun validate(apiKey: String): Result
}

class DefaultKeyValidator(
    private val api: ChkszApi,
    private val apiCaller: ApiCaller,
) : KeyValidator { ... }
```

- Key 以明文存入应用私有 DataStore；`android:allowBackup="false"` 已设置。
- 保存前本地格式检查：`chksz_` 前缀且只含 RFC 3986 非保留字符 `[A-Za-z0-9._~-]`（依据 `api.md` 第 2 节"API 密钥格式"），不做长度限制；最终以联网校验结果为准。
- 保存策略：`InvalidKey` 不保存；`Valid` 保存；`Indeterminate` 保存并提示（网络失败时用户仍可使用离线已有的 Key）。
- 默认值：音质 `Exhigh`、并发 3、主题 System。

### 仓库

```kotlin
interface TaskRepository {
    fun observeInProgress(): Flow<List<DownloadTask>>
    fun observeCompleted(): Flow<List<DownloadTask>>
    suspend fun byId(id: Long): DownloadTask?

    suspend fun enqueue(text: String, quality: MusicQuality): EnqueueOutcome
    suspend fun enqueueLinks(links: List<SongLink>, quality: MusicQuality): EnqueueOutcome

    // 引擎调用（Stage 2）：全部经过状态机校验并写 updatedAt
    suspend fun markResolving(id: Long)
    suspend fun completeResolve(id: Long, track: ResolvedTrack)
    suspend fun startDownloading(id: Long)
    suspend fun updateProgress(id: Long, bytesDownloaded: Long)
    suspend fun updateStream(id: Long, track: ResolvedTrack)      // 换链后更新直链/etag/size/md5，保留进度
    suspend fun markCompleted(id: Long, fileName: String, mediaStoreId: Long, completedAt: Long)
    suspend fun markFailed(id: Long, failureKind: FailureKind, message: String)
    suspend fun markPaused(id: Long)
    suspend fun markCancelled(id: Long)
    suspend fun resetForRetry(id: Long)                          // Failed -> Queued，清空解析/进度/文件字段
    suspend fun attachFile(id: Long, fileName: String, mediaStoreId: Long, bytesDownloaded: Long)

    // 用户操作（Stage 2 负责实际 Job 控制，Stage 1 只负责持久化）
    suspend fun pause(id: Long)
    suspend fun resume(id: Long)                                 // Paused -> Queued/Waiting
    suspend fun retry(id: Long)                                  // Failed -> Queued
    suspend fun deleteRecord(id: Long)
    suspend fun deleteRecords(ids: List<Long>)
    suspend fun clearCompleted()
    suspend fun pauseAll()
    suspend fun resumeAll()
    suspend fun pendingIds(): List<Long>
    suspend fun cancelAll()
    suspend fun retryAllFailed()
    /** 在互斥临界区内根据本应用未取消任务的 fileName 分配唯一名（baseName 或 baseName (n)）。 */
    suspend fun allocateFileName(base: BaseFileName): String
}

class DefaultTaskRepository(
    private val dao: DownloadTaskDao,
    private val settings: AppSettings,
    private val shortLinkResolver: LinkResolver,
    private val clock: AppClock,
) : TaskRepository { ... }

sealed interface EnqueueOutcome {
    data class MissingApiKey(val extractedCount: Int) : EnqueueOutcome
    data class NoLinks(val ignoredUrlCount: Int) : EnqueueOutcome
    data class Enqueued(
        val created: List<Long>,
        val duplicates: Int,
        val ignoredUrlCount: Int,
        val failures: List<EnqueueFailure>,   // 短链解析失败
    ) : EnqueueOutcome
}

data class EnqueueFailure(val sourceUrl: String, val failure: ApiFailure)
```

- 入队流程：提取 → 短链解析（并发 2）→ 无 Key 时仍可提取但返回 `MissingApiKey`（不入队）→ 按 songId 去重（非 Completed/Cancelled 范围内）→ 事务插入 `Queued`。
- 仓库不启动服务；调用方（Stage 3 或分享入口）在 `Enqueued.created` 非空时调用 `engine.start(context)` 与 `engine.requestDownload(created)`。
- `pause/resume/retry` 仅做持久化状态迁移，实际 Job 停止/启动由 Stage 2 的引擎调用这些方法并管理协程。
- 删除记录只删行，不删文件。Stage 3 删除"记录与文件"时先调用 `TaskFileStore.delete`，成功后再 `deleteRecord`；文件已不存在视为成功（幂等）。`clearHistory` 在 Stage 3 循环完成任务后调用 `dao.clearCompleted()`（通过仓库）实现两种语义。
- `FailureKind` 枚举：`Network/InvalidKey/QuotaExhausted/Forbidden/RateLimited/InvalidRequest/QualityUnavailable/NotFound/ServiceUnavailable/Malformed/StreamExpired/Integrity/Storage/Unknown`。`LinkUnsupported` 只出现在入队阶段的 `EnqueueFailure`，不会成为任务状态。
- `allocateFileName` 在单个 Mutex 内完成"查询同名 + 选择序号"，保证并发入队/下载时不重名。

## 数据流与数据安全

- 输入文本 → `LinkExtractor` → `SongLink` → 短链解析 → `SongId` → 去重 → `DownloadTaskEntity(status=Queued)`。
- 解析链：Stage 2 从 DAO 取 `Queued` → `TrackResolver`（限速器内）→ `Waiting` 并写入解析结果。
- 每次 API 调用后把额度写入 `quota_snapshot`，成功与失败都写。
- 数据库是唯一事实来源；界面只读仓库 Flow。
- Key 不进入数据库、日志、崩溃报告、通知；界面只显示掩码。

## 错误处理

| 失败 | 处理 | 用户可见（Stage 3） |
| --- | --- | --- |
| 网络 IOException | `Failed(Network)` | "网络错误，可重试" |
| 400 | `Failed(InvalidRequest)` | 显示 API msg |
| 401 | `Failed(InvalidKey)` | 显示 API msg，引导去设置 |
| 402 | `Failed(QuotaExhausted)` | 显示 API msg |
| 403 | `Failed(Forbidden)` | 显示 API msg，不自动重试 |
| 429 | `ApiCaller` 等待后重试一次；仍失败 `Failed(RateLimited)` | 显示 msg + 建议稍后 |
| 音质 404 | 降级 | 不提示，记录实际音质 |
| 全部音质 404 | `Failed(QualityUnavailable)` | "该歌曲所有音质不可用" |
| 其他 404 | `Failed(NotFound)` | "资源不存在" |
| 短链无法解析 | 入队阶段返回 `EnqueueFailure(LinkUnsupported)`，不创建任务 | "链接无法识别为网易云歌曲" |
| 短链网络错误 | 入队阶段返回 `EnqueueFailure(Network)`，不创建任务 | "网络错误，链接未能解析" |
| JSON 异常 | `Failed(Malformed)` | "接口返回异常" |
| 503 | `Failed(ServiceUnavailable)` | 显示 API msg |
| 未配置 Key | `EnqueueOutcome.MissingApiKey` | 引导去设置 |

API 的 `msg` 原文保留在 `failureMessage`，界面优先展示本地化说明，可展开原文。

## 与其他 Stage 的契约

- Stage 2 依赖：`TaskRepository` 的全部状态写入接口、`DownloadTaskEntity` 下载字段（`bytesDownloaded`、`streamUrl`、`etag`、`mediaStoreId`、`fileName`、`sizeBytes`、`md5`）、`ChkszApi`、`ApiCaller`、`RateLimiter`、`TrackResolver`、`AppSettings`、`QuotaStore`、`AppContainer`。
- Stage 2 的引擎负责：选择 `Queued` 任务、调用 `markResolving/completeResolve/startDownloading/updateProgress/updateStream/markCompleted/markFailed/markPaused/markCancelled`，并保证 `Downloading -> Resolving` 换链路径使用 `updateStream` 保留进度。
- Stage 3 依赖：仓库 Flow、`EnqueueOutcome`、`FailureKind`、设置项、额度 Flow、`KeyValidator`。
- Stage 1 不启动服务，也不提供下载占位实现。

## 测试策略

测试 seam：`TaskRepository`、`TrackResolver`、`KeyValidator` 均为接口，`Default*` 实现由 `AppContainer` 提供；Stage 2/3 的 JVM 测试可以直接注入 Fake。

JVM 单元测试（`app/src/test`）：

- `LinkExtractorTest`：分享文案、单条/多条、短链与直链混排、`#/song`、去重、无链接、无关 URL 计数。
- `FileNameBuilderTest`：非法字符、多艺术家、超长中日文截断、空艺术家、尾部点号、全非法兜底。
- `ShortLinkResolverTest`（MockWebServer）：302 + id、301 + id、无 id、非跳转、网络错误、HTTP 500。
- `ChkszApiTest`（MockWebServer）：成功解析、服务端降级、400/401/402/403/404/429/503、非 JSON、额度头、apikey 不出现在异常信息。
- `ApiCallerTest`：429 按 Retry-After 重试一次、第二次失败返回 RateLimited、额度写入成功与失败路径。
- `QualityResolverTest`：降级顺序、静默降级、401 短路、全部 404、429 重试失败后终止降级。
- `FileNameAllocationTest`：并发 allocateFileName 不重名、已取消任务释放名称。
- `RateLimiterTest`：虚拟时间 3.1s 间隔、并发串行化、penalize。
- `KeyValidatorTest`：有效、无效、网络错误返回 Indeterminate。
- `AppSettingsTest`：默认值、并发数钳制 1..5、主题持久化。
- `TaskTransitionsTest`：完整迁移矩阵，含 `Downloading -> Resolving`。
- `TaskRepositoryTest`（Fake DAO/内存实现）：去重、重复入队、MissingApiKey、状态迁移校验、重启转暂停。
- 可选联网测试 `LiveApiSmokeTest`：从 `user.dir` 向上查找含 `settings.gradle.kts` 的项目根目录，仅当根目录存在 `api_key.txt` 时运行真实解析（否则 `assumeTrue` 跳过）；该文件被 `.gitignore` 排除。

仪器测试（`app/src/androidTest`）：

- `DownloadTaskDaoTest`：插入、Flow 查询、按歌曲去重、`markUnfinishedAsPaused`、清空完成记录。
- Room schema 生成到 `app/schemas` 并纳入版本控制。

最终集成验证由 Stage 2/3 完成。

## 明确排除

- 不引入 Hilt、Dagger、Koin。
- 不引入 Robolectric。
- 不为 Stage 2 预留空服务、空下载器或假实现。
- 删除现有模板与冒烟测试代码：`smoke` 包（含 `SmokeService`、`SmokeStackTest`、`SmokePlatformTest`）、根包下的 `ExampleUnitTest`、`ExampleInstrumentedTest`、`app/schemas` 中的 `SmokeDatabase` schema、`AndroidManifest.xml` 的 `SEND` intent-filter、`SmokeService` 声明；模板主题替换为空 Compose Activity。
- 不支持 QQ 音乐、酷狗、歌单与歌词接口。
- 不实现数据库迁移或旧 Schema 兼容。
