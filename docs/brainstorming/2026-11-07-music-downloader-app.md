# 音乐下载器 Android 应用 需求与方向

> **重要变更与发布操作：**
> - 类别：本地数据库（Room，全新建库，无既有数据迁移）；外部依赖（ChKSz API，用户自备 Key）；系统权限声明。
> - 影响：新应用首次发布，无旧版本升级路径，无恢复限制，无不兼容变更，无专项公告。
> - 权限：INTERNET、FOREGROUND_SERVICE、FOREGROUND_SERVICE_DATA_SYNC（安装时授予）；POST_NOTIFICATIONS（Android 13+ 运行时请求，可拒绝）。不申请任何存储权限。
> - 代码之外的操作：项目目录需 `git init`；`api_key.txt` 必须加入 `.gitignore`，测试 Key 只能通过本地文件读取，不进入仓库、日志和 APK。
> - 评估状态：已评估并经用户确认（2026-11-07）。

## 项目现状（已确认事实）

- 空 Android 模板：AGP 9.2.1，Gradle 9.4.1，compileSdk 36.1，minSdk 29，targetSdk 36，包名 `com.dawnmoon.music_download`。
- 当前仅有 View 体系依赖（appcompat、material 1.10），未接入 Compose，无业务代码。
- 目录尚未初始化 git。

## API 实测结论（已确认事实）

- 测试短链 `https://163cn.tv/bkp6m2as` 302 跳转至 `https://y.music.163.com/m/song?id=1867150097&...`，可从 `id` 参数取歌曲 ID。短链解析不经过 ChKSz，不消耗额度。
- `GET /api/163_music?id=&level=&apikey=` 返回 `code`、`msg`、`data{id,url,br,level,size,md5,name,artist,album,picUrl}`。
- 实际格式从 `data.url` 路径扩展名判断：`lossless` 为 flac，`standard` 为 mp3；CDN 对 flac 也返回 `Content-Type: audio/mpeg`，不可依赖。
- `data.url` 是带时间戳的时效链接，重试或续传时可能需要重新解析。
- 返回 `size` 与 `md5`，可用于完整性校验。
- CDN 支持 Range 请求（206 + ETag），可断点续传。
- 无效 Key：HTTP 401，`msg = "apikey 无效或已失效"`。
- 音质不可用：HTTP 404，`msg = "Music URL not found, song may be unavailable at this quality level"`。
- 服务端会静默降级：请求 `hires` 实测返回 `data.level = lossless`。实际音质以 `data.level` 为准，不以请求值为准。各级实测（歌曲 1867150097）：jymaster/sky/jyeffect/lossless 为 flac（163MB/74MB/93MB/30MB），exhigh 为 mp3（9MB），standard 为 mp3（3.6MB）。
- `163_search` 返回 `data.songs[].artists` 多艺术家以 `/` 分隔（如 `The Kid LAROI/Justin Bieber`）；`163_music` 的 `data.artist` 预计同格式，`/` 是文件名非法字符，必须替换。
- 额度响应头 `X-Quota-Free-Remaining`、`X-Quota-Paid-Remaining`、`X-RateLimit-Limit` 可读取；速率限制每分钟 20 次。

## 需求边界（已确认）

- 平台：仅网易云（`163cn.tv` 短链、`music.163.com` / `y.music.163.com` 歌曲链接）。
- 链接提取：多行输入与系统分享共用同一提取器，从任意文本中提取全部网易云歌曲链接，忽略其余文字，按歌曲 ID 去重。示例 `分享あたらよ的单曲《夏霞》: https://163cn.tv/bkp6m2as (来自@网易云音乐)` 应提取出该短链。
- 创建任务：输入链接 + 选择音质，自动解析曲名与艺术家。
- 文件名：`{曲名}_{艺术家}.{format}`，非法字符替换；统一保存到公共 `Music` 目录，通过 MediaStore 写入。
- 重名：自动加序号，如 `曲名_艺术家 (1).flac`。
- 音质：默认 exhigh；工作台可临时修改。404 时按 jymaster > sky > jyeffect > hires > lossless > exhigh > standard 向下降级，任务记录实际音质。
- 批量：多行粘贴，统一音质。
- 系统分享入口：接收单条分享文本，按默认音质静默入队，Toast 提示，不打开主界面。
- 并发：解析 API 全局串行并限速（不超过 20 次/分钟；429 按 Retry-After 等待后最多重试一次；401/402/403 暂停解析队列并转述 msg）；文件下载默认并发 3，设置中可调 1-5。
- 后台：下载在 dataSync 前台服务中继续，常驻通知显示总进度；Android 13+ 首次开始下载时请求通知权限，拒绝不影响下载。
- 状态管理：单个/全部暂停、继续、取消、重试；Range 续传；链接过期时重新解析后续传；完成后校验大小与 md5；进程被杀后重启，未完成任务恢复为暂停状态。
- 历史：用系统播放器打开、删除（仅记录 / 连文件一起删）、按音质重新下载、分享文件、搜索、清空。
- Key：设置页填写，保存时调用一次 `163_search(limit=1)` 校验（消耗 1 次额度）并读取额度；未配置 Key 时工作台引导前往设置。
- 额度：每次 API 调用后从响应头更新，工作台顶部小标签显示免费/付费剩余，低于 10 时变为警示色。
- 界面：单页工作台。顶部多行输入框 + 音质选择 + 「解析并下载」；下方 Tab 切换「进行中」与「已完成」；右上角进入设置。
- 主题：Material 3 动态取色（Android 12+），否则使用内置配色；深浅色跟随系统，设置中可切换。
- 元数据：不写标签和封面，不下载歌词，只保存原始音频。

## 最终成功标准（已确认）

1. 粘贴示例分享文案，能解析出《夏霞》/あたらよ，并在 `Music/` 下生成可播放的 `夏霞_あたらよ.mp3`（exhigh）或对应扩展名文件，大小与 md5 与 API 返回一致。
2. 一次粘贴多条链接，全部入队；解析调用间隔符合限速，同时下载数不超过设置值。
3. 暂停后继续从断点续传；链接过期能重新解析后继续；杀进程重启后任务为暂停状态且可继续。
4. 无效 Key、额度用尽、封禁、限流、音质不可用、网络中断都有明确的界面提示，不无限重试。
5. 从网易云 App 分享到本应用可直接入队下载。
6. 锁屏或切后台时下载继续，通知显示进度。
7. 全程不申请存储权限；拒绝通知权限不影响下载。
8. 历史记录的打开、分享、重新下载、两种删除均可用。
9. 单元测试覆盖链接提取、文件名生成、音质降级、错误映射、限速器、任务状态机；可选的联网集成测试从本地 `api_key.txt` 读取 Key。
10. `./gradlew assembleDebug` 与单元测试通过。

## 方向选型（已确认）

自建 dataSync 前台服务 + 协程队列：

- UI：Jetpack Compose + Material 3，ViewModel + StateFlow。
- 数据：Room 存任务与历史（唯一事实来源），DataStore 存 Key、默认音质、并发数、主题。
- 网络：OkHttp + kotlinx.serialization；Coil 加载封面。
- 引擎：`DownloadService` 托管解析队列（串行 + 限速器）与下载池（Semaphore），OkHttp Range 写入 MediaStore（IS_PENDING 1 -> 0）。
- 依赖注入：手动 AppContainer，规模小，避免 AGP 9 与 Hilt/KSP 的兼容风险。

未采用：WorkManager（无原生暂停，全局限速和汇总通知需额外协调）；系统 DownloadManager（不能暂停、过期链接无法换链续传、状态管理受限）。

## 明确排除

- QQ 音乐、酷狗及其他平台
- 搜索、歌单导入、歌词、标签与封面写入
- 应用内播放器
- 账号体系、云同步、多设备
- 自定义下载目录与文件名模板
- 应用内更新、统计埋点

## Stage 索引（已确认，严格串行：Stage 1 -> Stage 2 -> Stage 3）

### Stage 1：工程基础与数据/网络层

- 范围：Compose + M3 工程配置；链接提取与短链解析；ChKSz API 客户端（错误映射、额度头读取）；解析限速器；音质降级策略；文件名生成；Room 任务模型与状态机；DataStore 设置；Key 校验。
- 依赖：无
- 设计状态：已完成
- 计划状态：已完成
- 执行状态：已完成
- 设计文件：`docs/grill/2026-11-07-music-downloader-app-stage-1.md`（已确认）
- 计划文件：`docs/plans/2026-11-07-music-downloader-app-stage-1.md`（已确认）

### Stage 2：下载引擎

- 范围：dataSync 前台服务；解析队列与下载池；Range 续传与换链续传；MediaStore 写入与重名处理；大小与 md5 校验；暂停/继续/取消/重试；进程重启恢复；进度通知；系统分享入口的入队逻辑。
- 依赖：Stage 1
- 设计状态：已完成
- 计划状态：已完成
- 执行状态：已完成
- 设计文件：`docs/grill/2026-11-07-music-downloader-app-stage-2.md`（已确认）
- 计划文件：`docs/plans/2026-11-07-music-downloader-app-stage-2.md`（已确认）

### Stage 3：界面与交互

- 范围：单页工作台（输入、音质、额度标签、进行中/已完成 Tab、任务卡片操作）；历史操作（打开、分享、重新下载、删除、搜索、清空）；设置页（Key、默认音质、并发数、主题）；通知权限请求流程；动态取色主题；分享 Toast。
- 依赖：Stage 2
- 设计状态：已完成
- 计划状态：已完成
- 执行状态：未完成
- 设计文件：`docs/grill/2026-11-07-music-downloader-app-stage-3.md`（已确认）
- 计划文件：`docs/plans/2026-11-07-music-downloader-app-stage-3.md`（已确认）
