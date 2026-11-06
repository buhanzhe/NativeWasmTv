# Windows 0.1 验证记录

本次在 Linux 构建机交叉编译 MinGW-w64 x64 目标，并用 Wine 10 运行实际
Windows JVM、Windows DLL、Windows FFplay 和 EXE。尚未在原生 Windows 电脑验收。

已通过：

- 实际 Windows x64 DLL 编译、链接和加载；仅依赖 Windows 系统 DLL。
- 播放列表 TXT/M3U、分组、相对地址、带逗号的签名地址、UJS 原样保留、请求头隔离。
- 快速切台替换、过期播放器退出事件隔离、停止与进程清理。
- Windows DLL 上的真实 QuickJS/DOM、WASM 导出调用、UJS 点击、hls.js MSE 写入，
  FFmpeg 解码选中频道的绿色视频帧；保留 `?token=a,b`。
- 包内 Windows JVM 与 FFplay 的真实窗口：列表加载、切换播放列表、打开原网页、
  显示选中频道画面、停止后实际解码器 PID 退出。
- EXE 启动器在带空格目录中启动包内 Java 运行时与应用窗口。
- ZIP 内文件哈希、x64 可执行文件、完整依赖和运行时；未包含项目签名文件。

Linux 同源 JNI 的真实网页/MSE/解码、Swing 窗口与进程清理也已通过，
它们与上述 Windows/Wine 结果分开记录。

Wine 的网卡枚举在此容器中触发 Windows JVM `GetAdaptersAddresses` 错误；
Wine 测试使用 `-Djava.net.preferIPv4Stack=true`。正式 Windows 启动器不强制关闭 IPv6。
Wine 控制台宿主 `conhost.exe` 不等同于解码器；停止检查核对的是 FFplay 的实际 PID。

没有将外部媒体候选、打开端口或单纯进程启动当成播放通过。本地测试实际核对解码图像。
没有进行公网上千频道兼容性、登录频道、长时间直播、4K 或原生 Windows 音频设备验收。

## 在 Windows 重复测试

先按 README 构建两个项目，并确保 JDK 17、Python 和 FFmpeg 位于 PATH。

```powershell
$app = 'win/out/windows/NativeWasmTv-win-x64/app'
javac --release 17 -encoding UTF-8 -cp "$app/*;$app/lib/*" `
  -d win/out/tests win/tests/java/xiao/bu/tv/windows/ContractChecks.java
java -cp "win/out/tests;$app/*;$app/lib/*" xiao.bu.tv.windows.ContractChecks
python win/tests/test_integration.py `
  --core browser-core/litebrowser/windows/out/windows --output win/out/test-core
python win/tests/test_ui.py --bundle win/out/windows/NativeWasmTv-win-x64 `
  --fixture win/out/test-core/fixture --output win/out/test-ui
```

测试视频由 FFmpeg 在本机生成，HTTP 服务只绑定 127.0.0.1，退出后关闭。
截图、解码画面、请求路径、测试日志留在指定输出目录，不写入源码目录。
