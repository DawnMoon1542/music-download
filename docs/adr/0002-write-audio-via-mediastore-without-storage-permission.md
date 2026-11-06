# 音频文件通过 MediaStore 写入公共 Music 目录，不申请存储权限

目标目录是设备公共 `Music`，MIN_SDK 为 29。直接写公共目录需要 `WRITE_EXTERNAL_STORAGE`（Android 10 请求 legacy storage）或 `MANAGE_EXTERNAL_STORAGE`，两者都会带来不必要的权限和商店审核风险。改为通过 `MediaStore.Audio.Media` 在 `Music/` 下插入记录，先以 `IS_PENDING=1` 写入，校验大小与 md5 后置 0，全程不需要任何存储权限。代价是无法在不申请 `READ_MEDIA_AUDIO` 的前提下看到其他应用创建的同名文件，因此重名序号只保证相对于本应用已下载文件唯一；如果外部已存在同名文件，MediaStore 允许同名并存。

## Consequences

- 暂停的任务会留下 `IS_PENDING=1` 的隐藏条目，取消或校验失败时必须删除，否则会积累不可见残留。
- 系统会清理放置约 7 天的 pending 项，超时后只能从头下载；恢复下载前必须用文件实际长度校准进度，缺失时重建条目。
- 用户选择"仅删除记录"后，文件仍在 `Music/` 而数据库不再计数，同一首歌重新下载可能生成同名文件而不是带序号的文件名。这是已知且接受的限制。
