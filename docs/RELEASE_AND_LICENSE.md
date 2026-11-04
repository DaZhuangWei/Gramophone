# Gramophone 发布与 GPL 说明

## 构建

```powershell
$env:JAVA_HOME="C:\DevEnv\jdk17\jdk-17.0.21+9"
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME

.\gradlew.bat :app:assembleRelease `
  -PKEYSTORE_FILE="D:/WorkSpace/muisc/keys/nerilite.jks" `
  -PKEYSTORE_PASSWORD="见 keys/keystore-info.txt" `
  -PKEY_ALIAS="nerilite" `
  -PKEY_PASSWORD="见 keys/keystore-info.txt"
```

产物在 `app/build/outputs/apk/release/`，复制到：

`D:\WorkSpace\muisc\Gramophone-arm64-v<版本>.apk`

同时记录版本名、文件大小和 SHA256。

## 发布到个人网站

1. 打开 `https://whhhlllll.space/admin`，必须使用 HTTPS。
2. 登录后点「软件」。
3. 填写标题、版本号、标签、摘要和更新说明。
4. 上传 arm64 APK 和封面（可选）。
5. 外部链接填 GitHub Release 或源码仓库地址。
6. 先保存为草稿，确认后再「立即发布」。

后台数据保存在服务器端 `CONTENT_DATA_DIR`，不要用本地 `data/` 覆盖线上内容。

## 发布到 GitHub

后台地址：`https://whhhlllll.space/admin/github`

服务器环境变量（不要提交到 Git）：

```bash
GITHUB_REPO=owner/repo
GITHUB_TOKEN=github_pat_xxx
```

Token 建议使用 Fine-grained token，只授权目标仓库：

- Contents: Read and write（创建 Release 和上传资产）
- Metadata: Read-only

发布流程：

1. 填写仓库、Tag（如 `v1.7.0`）、标题和 Markdown 更新说明。
2. 选择 APK。
3. 建议先勾选「先保存为 GitHub Draft」。
4. 发布后检查 Release 和 APK，再在 GitHub 上公开。

Tag 已存在时 GitHub 会返回 422；换新 Tag 或先删除旧 Release/Tag。

## GPL-3.0 合规

Gramophone 是 NeriPlayer 的修改版，仓库根目录保留 GPL-3.0 `LICENSE`。公开发布 APK 时必须同时满足：

- 保留 GPL-3.0 许可证和上游版权声明。
- 明确标注这是修改版，并说明主要修改。
- 向接收二进制的人提供**完整对应源码**（同一版本的完整仓库，含构建脚本）。
- 源码继续使用 GPL-3.0，不额外限制复制、修改或再分发。
- GitHub Release 可同时上传 APK，并链接到同一 Tag 的源码；GitHub 自动生成的 Source code 压缩包通常可作为源码提供方式。
- 如果源码仓库是私有的，只发布 APK 不符合 GPL。

个人网站和 GitHub Releases 都可以分发，只要源码可得且许可证/版权声明完整。不要移除上游的 GPL 声明，也不要用 EULA 限制 GPL 授予的权利。

## 发布检查清单

- [ ] Debug/Release 构建通过
- [ ] APK 版本名、大小、SHA256 已记录
- [ ] 网站软件页已填写版本和更新说明
- [ ] GitHub Tag 与 APK 版本一致
- [ ] GitHub Release 附带 APK 和源码链接
- [ ] 网站外部链接指向 GitHub Release/源码
- [ ] GPL 许可证与修改说明可见
- [ ] 未提交 keystore、密码、Token
