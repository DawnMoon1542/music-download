# Music Download

把网易云音乐链接解析成音频，并保存到手机的「音乐」目录。

相关链接：[ChKSz API](https://api.chksz.com/) [Linux.do](https://linux.do/t/topic/2740716/92)

## 能做什么

- 粘贴网易云歌曲链接或整段分享文案，一次可以放多条批量解析
- 从网易云的系统分享直接加入下载
- 选择音质；当前音质不可用时会向下降级
- 后台继续下载，通知里看总进度；可以暂停、继续、取消和重试
- 文件写入公共「音乐」目录，不申请存储权限
- 已完成的歌曲可以播放、分享、按音质重新下载，或删除记录和文件

## 构建

需要 JDK 17 和 Android SDK。`local.properties` 只放在本机，用来指向 SDK，不会提交。

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

最低系统版本 Android 10（`minSdk 29`）。

可选的联网冒烟测试会读取项目根目录的 `api_url.txt` 和 `api_key.txt`。这两个文件已被忽略，不要把 Key 放进仓库、截图或日志。

```bash
LIVE_API=1 ./gradlew :app:testDebugUnitTest --tests '*LiveApiSmokeTest'
```
