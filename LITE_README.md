# Gramophone

Gramophone 是基于 [NeriPlayer](https://github.com/cwuom/NeriPlayer) 的**网易云音乐专属精简分支**。

目标：

- 只保留网易云、本地音乐、下载/离线、歌词、10 段 EQ/音效。
- 去掉 Bilibili、YouTube、Subsonic、一起听、GitHub/WebDAV 同步等非核心入口。
- 默认黑金深色主题，关闭液体玻璃、模糊、流体背景等重效果，优先流畅度。
- 保留上游播放、缓存、下载、歌词、音效能力，不重写底层。

## 当前状态

v0.1 可运行版本（Debug / Release APK 均已构建）：

- 首页：网易云推荐、私人雷达、雷达歌单
- 搜索：仅网易云
- 媒体库：本地 / 收藏 / 网易云
- 设置：第三方登录仅网易云；隐藏备份同步、一起听入口
- 主题：Clean + 深色 + 香槟金，近黑背景
- 性能：默认关闭高级模糊、液体玻璃、音频响应、动态背景、歌词模糊
- 音效：保留 10 段 EQ、音效与倍速
- 包名：`moe.ouom.nerilite`（Debug 为 `moe.ouom.nerilite.debug`），不覆盖官方 NeriPlayer
- 启动：无 4 秒阅读倒计时，点击「同意并继续」立即进入
- 品牌：Gramophone + 黑金黑胶唱片/唱臂桌面图标与启动图标
- 动画：120Hz 屏幕上波形、歌词高亮、歌词插值按 120fps 推进；前台保持高刷
- 播放修复：seek 不再越过歌曲时长，最后一行歌词跳转不会自动切下一首

## 构建

需要 JDK 17、Android SDK 37、NDK 27、CMake 3.22.1。

```powershell
$env:JAVA_HOME="C:\DevEnv\jdk17\jdk-17.0.21+9"
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME

# Debug
.\gradlew.bat :app:assembleDebug

# Release（arm64，签名文件在仓库外）
.\gradlew.bat :app:assembleRelease `
  -PKEYSTORE_FILE="D:/WorkSpace/muisc/keys/nerilite.jks" `
  -PKEYSTORE_PASSWORD="<见 keys/keystore-info.txt>" `
  -PKEY_ALIAS="nerilite" `
  -PKEY_PASSWORD="<见 keys/keystore-info.txt>"
```

产物：

- Debug：`app/build/outputs/apk/debug/app-debug.apk`
- Release：`app/build/outputs/apk/release/*.apk`
- 已复制真机包：`D:\WorkSpace\muisc\Gramophone-arm64-v1.1.apk`
  - 大小：14,784,108 bytes
  - SHA256：`8ABA293E5C1C4930BDE812B6BD09AD40785B7C02B47302A6256F67DE06CAB69B`
  - 版本：`749c1e2.11041301`（arm64-v8a）

> 旧版 `NeriLite-arm64-v1.0.apk` 仍保留，便于回滚；日常使用请安装 v1.1。

## 文档

**后续开发请先读 [`docs/LITE_DEV.md`](docs/LITE_DEV.md)**，里面有：

- 环境路径与代理配置
- 构建/安装/清数据/性能测量命令
- 代码地图（网易云协议、播放、音效、主题、设置）
- Lite 改造原则
- 已完成 / 进行中 / 后续
- 改动记录与踩坑

## 注意

- 本项目继续遵守 **GPL-3.0**，分发修改版需提供对应源码。
- 不绕过网易云会员、付费、DRM 或地区限制。
- 接口可能随平台变化；登录态和 Cookie 只保存在本机。
- 当前 UI 仍是 NeriPlayer 的 Clean 布局 + 黑金配色；播放页的 Walkman 化是下一阶段。
