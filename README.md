# Music Download

把网易云音乐链接解析成音频，并保存到手机的「音乐」目录。解析使用 [ChKSz API](https://api.chksz.com/)，需要你自己的 API Key。使用和接口讨论见 [Linux.do 主题帖](https://linux.do/t/topic/2740716/92)。

## 能做什么

- 粘贴网易云歌曲链接或整段分享文案，一次可以放多条
- 从网易云的系统分享直接加入下载
- 选择音质；当前音质不可用时会向下降级
- 后台继续下载，通知里看总进度；可以暂停、继续、取消和重试
- 文件写入公共「音乐」目录，不申请存储权限
- 已完成的歌曲可以播放、分享、按音质重新下载，或删除记录和文件

应用只处理网易云歌曲。QQ 音乐、酷狗、歌单、歌词和应用内播放都不在范围内。

## 开始使用

1. 打开 [ChKSz API](https://api.chksz.com/)，登录后复制以 `chksz_` 开头的 Key。
2. 在应用的设置里填写 API 地址 `https://api.chksz.com` 和 Key，然后保存。保存时会请求一次搜索接口来确认地址和 Key 可用，并消耗 1 次额度。
3. 回到首页，粘贴链接后点「解析并下载」。

额度、速率限制和错误含义以 [ChKSz API](https://api.chksz.com/) 当时的说明为准。接口字段摘要也写在仓库的 [api.md](api.md) 里。

## 构建

需要 JDK 17 和 Android SDK。`local.properties` 只放在本机，用来指向 SDK，不会提交。

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

最低系统版本是 Android 10（`minSdk 29`）。

可选的联网冒烟测试会读取项目根目录的 `api_url.txt` 和 `api_key.txt`。这两个文件已被忽略，不要把 Key 放进仓库、截图或日志。

```bash
LIVE_API=1 ./gradlew :app:testDebugUnitTest --tests '*LiveApiSmokeTest'
```

## 特别鸣谢

设置里的「特别鸣谢」列出了 [ChKSz API 贡献者](https://api.chksz.com/#contributors)。
