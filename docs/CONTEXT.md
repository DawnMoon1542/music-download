# 音乐下载器

本 context 描述用户用 ChKSz API 解析网易云音乐链接、把音频保存到设备公共 Music 目录的 Android 应用。

## Language

**歌曲链接 (SongLink)**：
  指向单首网易云歌曲的链接，分为 `163cn.tv` 短链和包含歌曲 ID 的 `music.163.com` 链接。
  _Avoid_: URL, 音频地址, 分享链接

**短链 (ShortLink)**：
  `163cn.tv` 的跳转链接，必须先解析出跳转目标才能得到歌曲 ID。
  _Avoid_: 分享码

**歌曲 ID (SongId)**：
  网易云音乐的数值歌曲标识，短链解析后从跳转地址的 `id` 参数取得。
  _Avoid_: 歌曲编号, trackId

**解析 (Resolve)**：
  调用 ChKSz `163_music` 接口，用歌曲 ID 和音质换取音频直链、曲名、艺术家、大小和 md5 的过程。每次解析消耗一次额度。
  _Avoid_: 请求, 下载

**音质 (MusicQuality)**：
  ChKSz 的 `level` 取值，从高到低为 `jymaster`、`sky`、`jyeffect`、`hires`、`lossless`、`exhigh`、`standard`。
  _Avoid_: 清晰度, 码率

**实际音质 (ActualQuality)**：
  解析响应中 `data.level` 报告的最终音质，可能低于请求音质，因为服务端会静默降级。
  _Avoid_: 请求音质

**降级 (Downgrade)**：
  请求音质返回 404 时，按音质顺序逐级向下重试，直到成功或到 `standard`。
  _Avoid_: 回退, fallback

**任务 (DownloadTask)**：
  用户提交的一条下载记录，持有链接、音质、解析结果、进度和状态。
  _Avoid_: 下载项, item, 作业

**任务状态 (TaskStatus)**：
  `QUEUED`、`RESOLVING`、`WAITING`、`DOWNLOADING`、`PAUSED`、`COMPLETED`、`FAILED`、`CANCELLED` 之一。
  _Avoid_: 阶段, phase

**解析队列 (ParseQueue)**：
  串行处理 `QUEUED` 任务的队列，全局限制调用频率不超过 20 次/分钟。
  _Avoid_: 请求池

**下载池 (DownloadPool)**：
  同时执行 `DOWNLOADING` 任务的并发池，容量等于设置的并发数。
  _Avoid_: 线程池

**进行中**：
  状态不是 `COMPLETED` 的任务，界面包含排队、下载、暂停、失败和已取消。
  _Avoid_: 未完成

**历史**：
  `COMPLETED` 状态的任务，以及它们的文件与完成信息。
  _Avoid_: 记录, 下载记录

**额度 (Quota)**：
  ChKSz 按账户计数的调用次数，响应头 `X-Quota-Free-Remaining` 与 `X-Quota-Paid-Remaining` 分别表示免费与付费剩余额度。
  _Avoid_: 余额, credits

**文件名主名 (BaseFileName)**：
  `{曲名}_{艺术家}.{format}`，非法字符替换为 `_`，不含重名序号。
  _Avoid_: 显示名, title, DownloadFileName

**重名序号**：
  文件名主名在"本应用未取消任务"范围内重复时追加的 ` (n)` 后缀，由下载层决定。
  _Avoid_: 自动编号

## Relationships

- 一条 **歌曲链接** 至多对应一个 **歌曲 ID**；一条 **短链** 必须解析一次才能得到 **歌曲 ID**。
- 一个 **歌曲 ID** 对应一个 **任务**（同一 ID 在队列中只允许一条任务）；同一 ID 可以出现在 **历史** 的多个任务中。
- 一个 **任务** 经过一次或多次 **解析**（**降级** 与换链续传会产生额外解析）。
- 一个 **歌曲 ID** 在非完成、非取消的 **任务** 中最多出现一次；**历史** 中可以有多次。
- 一个 **任务** 消耗 1 次以上 **额度**。
- 一个完成的 **任务** 生成一个 **文件名**；文件保存在公共 `Music` 目录。

## Example dialogue

> **Dev:** "用户粘贴了一条短链，需要先放进 **解析队列** 吗？"
> **Domain expert:** "短链跳转不经过 ChKSz，不入队；得到 **歌曲 ID** 后才开始 **解析**。"
>
> **Dev:** "**实际音质** 比请求的低，要不要继续 **降级**？"
> **Domain expert:** "不用。服务端已经给了一个可用音质，把它记录为 **实际音质** 即可。"

## Flagged ambiguities

- "下载" 曾同时指 **解析** 和真正写入文件——已澄清：**解析** 只换取直链，写文件属于下载。
- "完成" 曾被用于表示解析成功——已澄清：**历史** 只收 `COMPLETED`，解析成功但未写完文件的任务仍属 **进行中**。
