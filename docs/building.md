# nTv 构建与内核说明

[返回首页](../README.md) · [使用指南](user-guide.md) · [English](building.en.md)

## 手动打正式包（Windows）

双击项目根目录的 `build-release.bat`，调用 `scripts/build-release.ps1`。普通 ARM32、ARM64、x86 包可从公开源码独立构建，不需要 `browser-core`。

| 文件 | 架构 | 内核 |
| --- | --- | --- |
| `nTv.apk` | ARM32，Android 4.0+ | 系统 WebView、IJK、QuickJS/TLS |
| `nTv64.apk` | ARM64，Android 5.0+ | 同上 |
| `nTvX86.apk` | x86，Android 4.0+ | 同上 |
| `ntv_bro.apk` | ARM32，Android 4.0+ | 普通包功能及轻量浏览器 |
| `ntv_bro64.apk` | ARM64，Android 5.0+ | 普通包功能及轻量浏览器 |
| `ntv_full.apk` | ARM32、ARM64、x86，Android 4.0+ | 轻量浏览器、系统 WebView、IJK、QuickJS/TLS |

全量包覆盖本项目已有的全部三种原生架构；没有 x86_64 原生库。系统 WebView 由设备提供，TVBus 等按需下载的插件仍由插件管理安装。全量包依据当前进程选择插件架构，支持64位设备运行32位进程。所有发布包均不带 H5E 流式库，央视网保留整段解密路径。

bro32、bro64、全量包需要完整的本地授权 `browser-core/`、与内核匹配的正式证书及授权包名 `xiao.bu.tv`。缺少私有内核、证书不一致或包名不一致时，自动跳过这三个包，继续打普通三包；更新清单也不包含跳过的包。不会修改内核签名校验。正式打包仍需要自己的 `.signing` 签名文件和 Android SDK（`local.properties`）。

输出默认在 `output/`，包含英文附件、对应中文副本、SHA256、按哈希保存的 R8 mapping、`mapping.zip`、`build-summary.json` 和三个更新清单。上传 GitHub Release 时保留英文名大小写。构建报告列出本次实际生成的包和跳过原因；旧输出文件不会成为本次清单的附件。版本文件只写入输出目录，不覆盖工作区已有清单。

默认采用增量构建，避免 Windows 文件占用导致 `:app:clean` 失败；需要完整清理时可执行 `build-release.bat -Clean`。命令行也可继续传递 `-SkipClean` 等原脚本参数。

构建使用 JDK 17、Gradle 8.11.1 和 Android Gradle Plugin 8.9.1，需要安装 Android SDK Platform 34、Build Tools 35.0.0；应用最低系统版本与 targetSdk 保持不变。正式包启用 R8 代码优化、混淆和资源裁剪，旧系统的电视横幅只生成一张 PNG 兼容图。打包时在 APK 旁导出一个 `output/mapping.zip`，内含 `arm32/mapping.txt`、`arm64/mapping.txt`、`x86/mapping.txt` 及记录 APK、映射 SHA256 的 `manifest.json`；启用的 bro32、bro64、全量包也分别包含对应映射。每个 APK 的映射仍按哈希归档为 `output/mapping/<APK 的 SHA256>/mapping.txt`，保留历史版本。映射缺失或为空时打包会报错；发布时请保留对应 ZIP，用于还原该版本的崩溃堆栈。

每次打包在输出目录生成 `version-lite.json`、`version.json`、`version-iptv.json`。轻量清单使用实际版本号，两个启动清单的版本阈值保持6。每种包使用独立附件和哈希，bro64和全量包升级后保留对应内核。`-ImportantUpdate` 参数保留兼容。

## 私有浏览器内核

浏览器 Java/JS、litehtml、WASM、浏览器专用 QuickJS/TLS 代码保存在单独授权的私有工程中，不随本仓库提交。本仓库仅在 `prebuilt/litebrowser/<ABI>/libntvLitebro.so` 保存轻量内核的二进制快照，不包含内核源码或子模块入口。

普通包直接构建；浏览器包使用可选 `:litecore` 和 `:litebrowser`，将授权的完整工程放在本地 `browser-core/`，并配置匹配的正式签名后执行：

```powershell
./gradlew.bat :app:assembleNtvBro64Release :app:assembleNtvFullRelease --console=plain
```

`browser-core/` 已被 Git 忽略。公开的 `.so` 不包含构建所需的 Java 接口和 JS 资源，不能单独替代该本地依赖；公开检出可独立构建普通三包，`app/src/browserUnavailable` 提供通用网络/配置代码及不可用内核接口，不含浏览器执行实现。可用 `-PwithoutBrowserCore` 验证不依赖私有目录的构建。应用业务代码和通用 QuickJS/TLS 库仍在本仓库维护。分发包含内核的 bro 安装包时，完整对应源码的提供义务见 [版权说明](../COPYRIGHT.md)，仅提交 `.so` 不代表已满足该义务。

## 站点脚本与通用轻量内核

站点点击、频道选择、播放器私有结构观察由应用内的油猴脚本管理：
`app/src/main/assets/userscripts/ntv-site-selection.user.js` 和
`app/src/main/assets/userscripts/ntv-player-compat.user.js`；播放器对象响应化、电视门户组件精简和免费直播续流也分别保存在同一目录的独立脚本中。首次升级会加入现有脚本列表，保留已有脚本及明确关闭的总开关；随后不会覆盖用户编辑或重新添加已删除的脚本。在管理网页的脚本管理中可编辑、停用或删除，按 `@match`、`@exclude-match`、`@run-at` 执行。脚本设置变化会使五分钟预解析结果失效。

站点需要特定 UA 时，在油猴脚本头部设置 `@user-agent`，并用 `@match`、`@exclude-match` 或 `@exclude` 限定适用页面。停用脚本后，其 UA 设置也停止生效。

轻量内核仅提供通用的文档开始脚本入口、DOM/网络/媒体能力和播放器 SDK 观察接口，不携带业务域名或站点点击表。网页脚本可调用 `ntvBrowser.offerMedia(url)` 提交候选媒体，由既有检测链路验证；需要用户登录或交互时可调用 `ntvBrowser.requestInteraction()`。这两个接口只在轻量页面中提供，系统 WebView 使用其正常页面能力。

必须由宿主处理的站点鉴权、加密播放链路、校时等策略集中在应用的 `WebSiteCompatibility`，通过可选的 `LiteHostPolicy` 接入；独立内核默认不加载这些策略。网站 DOM 或按钮变化优先更新脚本，涉及服务协议的变化仍需更新对应应用策略。

## Windows 最小版本

`win` 分支的 Windows x64 网页电视位于 [`win/`](https://github.com/buhanzhe/NativeWasmTv/tree/win/win)：简单播放列表、
频道切换和播放，使用独立新界面及实际轻量内核。构建与验证见该目录说明。
