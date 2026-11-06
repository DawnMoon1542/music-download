# 下载引擎使用自建前台服务而非 WorkManager 或 DownloadManager

需求要求暂停、继续、断点续传、链接过期后换链续传、多任务并发控制，以及解析调用全局限速。系统 DownloadManager 无法暂停，也无法在直链过期后换新链接续传。WorkManager 没有原生暂停语义，跨 Worker 的全局解析限速与汇总进度通知需要额外协调，且前台通知按 Worker 分散。因此选择自建 `dataSync` 前台服务托管解析队列与下载池，用协程与 `Semaphore` 控制并发，任务状态存 Room。代价是必须自行处理进程被杀后的恢复和通知权限，这些在设计中显式覆盖。

## Considered Options

- WorkManager：进程重启后自动恢复，但暂停、全局限速和单条汇总通知都需要额外机制。
- 系统 DownloadManager：代码最少，但无法暂停、无法换链续传、状态与错误信息受限。
