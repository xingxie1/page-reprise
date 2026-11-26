# 循页 · PageReprise

给考研自习用的 Android 错题本。打开看今天该做哪一本，不发送通知。政治、英语二、数学二和 408 各有自己的普通本与永久重点本。

未清零的题按 **1 / 7 / 14 天**复习；正式复习中答错或猜中后，按 **1 / 3 / 14 天**重开。两次清零升入同科重点本。三连对结束排期，但不会删除题图或历史。

产品规则、设备和验收场景见[开发文档](循页-开发文档.md)。本仓库不存放个人错题、服务密钥或 Android 签名私钥。

## 在平板和手机上开始

1. 两端私下安装同一个已签名的 release APK。第一次启动即可本机试用；这时数据不会自动离开设备。
2. 在循页点「录入一道题」，或在系统截图、相册的分享菜单中选「循页」。选科后保存；长题可有多张题图和答案图，可裁切、排序、补图。Notein 只需能分享图片，不能直分享时用系统截图即可。
3. 次日北京时间 00:00 起，首页对应科目的本会显示待办。做题时答案折叠，猜对按没做对处理。仅打开、稍后、手动抽练都不改变排期。第二次清零须确认，题目只移到同科永久重点本。
4. 两端同步需要**你自己的已配置服务**，不是 GitHub 仓库提供云存储。按[云端启用指南](docs/cloud.md)配置，再在两端登录同一个受邀账号。从本机试用转入账号须手动确认，好友使用不同账号。设置内可以导出或预览恢复备份，夸克网盘只用于手动存放该文件。

## 构建和私下分发

需要 JDK 17、Android SDK Platform 35、Build Tools 34.0.0 / 35.0.0。设置 `JAVA_HOME` 和 `ANDROID_HOME`，或使用 Android Studio 的本地 SDK 位置。Gradle Wrapper 固定版本并校验发行包的 SHA-256。

| 任务 | Windows PowerShell 7 |
| --- | --- |
| 单元测试、构建 | `.\gradlew.bat :core:test :app:assembleDebug :app:assembleRelease` |
| 在自己选定的测试设备上验证 | `.\gradlew.bat :app:connectedDebugAndroidTest` |
| 用固定私钥签出可持续覆盖安装的 APK | `.\scripts\sign-local.ps1 -UnsignedApk app/build/outputs/apk/release/app-release-unsigned.apk` |

macOS / Linux 使用 `./gradlew`。Windows 签名工具把成品放在 `dist/PageReprise-0.1.0.apk`，用 `apksigner` 验签并报告摘要。首次签名在当前 Windows 用户的 `%LOCALAPPDATA%/PageReprise/signing` 创建专用密钥，口令用 DPAPI 保护。请离线备份签名密钥，并将口令另存在自己的密码管理器；仅复制 DPAPI 文件不能在另一台电脑解密。不要把签名材料放进 Git。后续升级使用同一私钥和递增的版本号。

## 验证与边界

- [Android 构建](../../actions/workflows/android.yml)：推送时运行排期、离线冲突和备份单元测试，并生成 debug APK、测试 APK 和**未签名** release APK。流水线的 debug 身份用于测试，不作为长期分发签名。
- [权限检查](../../actions/workflows/access.yml)：在一次性 PostgreSQL 中检查邀请码、只追加操作、不同账号及私有题图的隔离。
- [云接口联测](../../actions/workflows/cloud-smoke.yml)：按需在临时 Supabase 测试两个登录会话、实际上传下载、重复提交和好友隔离，不使用真实用户资料。[Android 设备验收](../../actions/workflows/device.yml)在一次性模拟器执行实际页面操作。
- 目标真机为小米 Pad 7 和小米 14 Pro，仍须验证 HyperOS 私下安装、Notein 分享菜单和手写题图清晰度。应用不申请通知或全盘读取权限。知识点复习、OCR、自动判分暂不开放。
