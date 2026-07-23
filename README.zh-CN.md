# nTv

简体中文 | [English](README.md)

`nTv` 是一款横屏 Android TV 播放器，支持 CCTV-1 至 CCTV-17 以及 CCTV-5+，
共 18 个 CCTV HLS 频道。最低支持 Android 4.1（`minSdk 16`），应用层使用纯
Java 编写，并通过 ijkplayer `0.8.8` 播放。应用 ID 为 `xiao.bu.tv`。

## 下载

- [夸克网盘](https://pan.quark.cn/s/e4db1433c7bb?pwd=KDmk)（提取码：`KDmk`）

## 设计

应用启动后会在 `127.0.0.1` 创建 HTTP 代理，重写上游 m3u8，并将重写后的地址
交给 ijkplayer。TS 分片由代理下载，在 ijkplayer 读取前先传入 JNI。频道入口地址
请求最高约 4 Mbps 的码率，代理会从主播放列表中选择带宽最高的变体，以便大屏
电视优先播放最清晰的可用视频流。

JNI 层会提取 H.264 PES 载荷，同时保留字节到 TS 的偏移映射。其调用顺序遵循
[xiaoxi-ij478/cctv-h5e-decrypt](https://github.com/xiaoxi-ij478/cctv-h5e-decrypt)：
初始化播放器状态、更新每个 NAL 的标签、调用选定的 VOD 解密器，最后执行
`CNTV_jsdecVOD8`。解密后的 NAL 字节会写回原 TS 位置，因此直播播放期间无需
重新封装。JNI 层还会为每个 TS 分片冻结工作线程所见的时钟，使性能较低的
Android TV 设备也能匹配上游 Worker 的快速执行行为。

wasm 载荷已通过 WABT `wasm2c` 转换为原生 C 代码，生成的代码位于
`app/src/main/jni/generated`。

## 遥控器操作

遥控器交互参考
[WebViewTvLive](https://github.com/hxh19950701/WebViewTvLive)：

- 播放时按 `DPAD_UP` 或 `DPAD_DOWN` 切换频道。
- 按 `DPAD_CENTER`、`ENTER` 或 `MENU` 打开频道列表。
- 按 `BACK` 或 `MENU` 关闭频道列表。
- 五秒无操作后，频道列表自动关闭。
- 按 `MEDIA_PLAY_PAUSE` 暂停或继续播放。
- 在触屏设备上，点击视频可打开频道列表。
- 频道列表关闭时，两秒内连续按两次 `BACK` 退出应用。

启动器图标复用了 WebViewTvLive 的图标。

## 构建

环境要求：

- 带有 `android-27` 平台的 Android SDK
- JDK 8
- Gradle `4.4`
- 带有 Linux `armeabi-v7a` 工具链的 Android NDK
- 在 Windows 下使用附带的 Linux NDK 时需要 WSL

根据 `local.properties.example` 创建 `local.properties`，然后构建原生库：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build-native.ps1 `
  -NdkRoot C:\path\to\android_ndk
```

构建调试版 APK：

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-8'
.\gradlew.bat assembleDebug
```

当 `local.properties` 指向 WSL 中的 Linux SDK 时，请在 WSL 内运行 Gradle：

```powershell
wsl -e bash -lc "cd /mnt/c/path/to/nTv && ./gradlew assembleDebug"
```

生成的 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

### 发布签名

当项目中存在以下本地文件时，发布构建会自动使用 IPTV 证书：

```text
.signing/iptv-release.jks
.signing/keystore-info.properties
```

属性文件格式如下（请在本地替换各占位符）：

```properties
keystore=iptv-release.jks
alias=<密钥别名>
storePassword=<密钥库密码>
keyPassword=<密钥密码>
```

完整的 `.signing/` 目录已被 Git 忽略，因为其中包含私钥和密码。使用以下命令
构建已签名的发布版 APK：

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-8'
.\gradlew.bat assembleRelease
```

已签名的 APK 位于 `app/build/outputs/apk/release/app-release.apk`。

## 自动更新

应用启动时会检查 `version.json`。清单请求和 APK 下载都会通过
`https://gh-proxy.com/` 发送。当 `versionCode` 高于已安装版本时，应用会请求
用户确认、下载 APK、在提供 SHA-256 时验证文件，随后打开 Android 软件包安装器。
应用未申请 Android 的 `REQUEST_INSTALL_PACKAGES` 权限。其目标 API 为 25，因此
旧版安装器 Intent 无需该清单权限即可使用。在 Android 8.0 及以上版本中，系统仍
可能要求用户允许此应用作为安装来源；该系统安全确认无法绕过。

更新地址为：

```text
https://gh-proxy.com/https://github.com/buhanzhe/NativeWasmTv/raw/refs/heads/master/version-iptv.json
```

将发布文件命名为 `nTv.apk`，上传到标签与 `v<versionName>` 一致的 GitHub Release
（例如 `v1.1.0`），然后根据该 APK 生成仓库清单：

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-8'
.\gradlew.bat generateVersionFile `
  '-PupdateApk=C:\path\to\nTv.apk' `
  '-PreleaseNotes=本次更新说明'
```

发布对应 APK 后，提交并推送生成的 `version.json`。仓库和 Release 文件必须公开，
因为 `gh-proxy.com` 无法对私有 GitHub 仓库进行身份验证。发布版本时应始终传入
`-PupdateApk`，以便客户端拒绝损坏的下载文件。

Android 4.4 支持 TLS 1.2，但并非始终默认启用。应用的 TLS 兼容层会为更新服务和
现有 HTTPS 播放请求启用 TLS 1.2 以及兼容的 ECDHE/AES 加密套件。

## 重新生成 wasm2c 输出

仓库中的原生 C 代码由上游提交
`c56afb59bc4cf176acb137f7182c400565e0c4fa` 生成。更新该仓库后，如需重新生成，
请下载 Windows 版 WABT `1.0.39` 并运行：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\extract-wasm.ps1 `
  -WorkerJs C:\path\to\cctv.worker.new.js `
  -WabtRoot C:\path\to\wabt-1.0.39
```

当前转换所用 wasm 的 SHA-256 为
`b645471c0b114f4a385c7432002d46f409f1766b0c424f770afb6c921abc66ce`。

## 说明

- 当前构建打包 `armeabi-v7a`，适用于支持该 ABI 的 Android 4.1 及以上电视和手机。
- Android 4.1 及以上版本已启用硬件解码。
- 当代理无法识别 H5E 流或无法原位替换 NAL 时，会回退到原始分片。
- wasm 调用发生异常时，该分片会被拒绝，而不会将加密字节传给 ijkplayer；同时会在
  下一次请求前重置原生 wasm 状态。
- 请仅播放您有权访问的视频流。
