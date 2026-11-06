# 网页电视 Windows · 0.1

Windows 10/11 x64 的最小桌面版，采用简单的新界面。支持本地 M3U/TXT、
在线 HTTP(S) 播放列表、播放列表切换、频道切换、播放和停止。
视频使用独立的 FFplay 窗口；空格暂停，F 全屏，Q 关闭播放窗口。

## 使用

完整解压压缩包后，双击 `NativeWasmTv.exe` 或 `启动网页电视.cmd`。
包内自带 Java 运行时，无需安装 Android SDK、Java、WSL 或浏览器。

点击“打开列表”选择 UTF-8 编码的 M3U/TXT，或点击“添加地址”输入在线列表地址。
上方下拉框切换已加载列表，点击频道即播放，也可使用上一台/下一台和停止按钮。
网页地址使用 `webview://https://...`，保留原有 `?ujs=` 频道选择脚本；
直接媒体使用普通 HTTP(S) 地址。示例列表位于 `playlists/example.m3u`。

网页通过实际的 QuickJS、litehtml、wasm3 与原有 JS 兼容层解析。
网站原生 MSE 经同源 `LiteMseRelay` 转为本机媒体流；播放期间保留页面和 Cookie。
登录、验证码和需要手动网页交互的频道不在首版范围，失败会显示在状态栏。
首版不包含 Android 管理页面、主题、EPG、投屏或更新机制。

播放列表每份最多 8 MiB、10000 个频道；不支持的条目计数显示，不会替换成其他频道。
HTTP 及证书校验保留默认验证。用户配置位于用户目录 `.ntv-windows`。

## 从源码构建

应用仓库和已授权的 `liteWebCore` 仓库均使用 `win` 分支，内核放在
`browser-core`。所需工具：Python 3.10+、JDK 17、MinGW-w64 POSIX GCC/G++。

```powershell
git clone --branch win https://github.com/buhanzhe/NativeWasmTv.git
cd NativeWasmTv
git clone --branch win https://github.com/buhanzhe/liteWebCore.git browser-core
```

先构建实际 Windows 内核：

```powershell
python browser-core/litebrowser/windows/build.py --target windows --jdk $env:JAVA_HOME
```

从可信的 FFmpeg 分发获取 Windows x64 GPL 构建，核对发布方 SHA-256。
本项目使用 BtbN/FFmpeg-Builds；不要把其他平台的 `ffplay` 放入 Windows 包。

```powershell
python win/build.py --core-output browser-core/litebrowser/windows/out/windows `
  --jdk $env:JAVA_HOME --player C:/tools/ffmpeg/bin/ffplay.exe
```

输出 `win/out/windows/NativeWasmTv-win-x64-v0.1.0.zip`。
Linux 可通过 `--jdk` 指向 Windows JDK、`--cc/--cxx` 指向 MinGW 交叉编译器，
使用同版本宿主 JDK 的 `javac`/`jlink` 交叉打包；内核说明见私有工程对应目录。
应用打包脚本可用 `--launcher-cc x86_64-w64-mingw32-gcc` 指定启动器编译器。
`win/tests` 提供播放列表、进程切换及真实内核/MSE 测试。
本次已执行的检查、Windows/Wine 的环境差异及复验命令见 [VALIDATION.md](VALIDATION.md)。

运行时、播放器和原生库在发布 ZIP 内；构建缓存与发布产物不加入 Git。
原有 Android 工程及其签名文件保留独立。
