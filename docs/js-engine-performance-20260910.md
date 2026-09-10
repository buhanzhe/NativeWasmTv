# QuickJS 与 WebView：三平台 JS 实测

2026-09-10。全部使用同一 ARM32 APK、相同 ES5 脚本；无网络、DOM 和视频解码。结果值逐项一致。每引擎每负载执行11次，首次单列，其余10次取均值。QuickJS使用应用真实NativeQuickJs入口，每次创建并释放上下文；WebView复用空白页，通过javascript URL执行、prompt回传。

负载：30万次整数运算；100项频道对象进行300次JSON解析/序列化；1万次URL正则提取与替换。它们是微基准，不是实际频道脚本或换台总耗时。

| 平台 | 负载 | QuickJS JS内部 ms | WebView JS内部 ms | QuickJS完整调用 ms | WebView完整调用 ms |
|---|---|---:|---:|---:|---:|
| 4.0.4 模拟器 | compute | 32.7 | 4.6 | 38.1 | 4.9 |
| 4.0.4 模拟器 | json | 86.5 | 26.2 | 89.1 | 26.6 |
| 4.0.4 模拟器 | regex | 26.6 | 2.6 | 28.3 | 2.7 |
| 4.4.4 真机 | compute | 195.8 | 17.9 | 197.4 | 19.3 |
| 4.4.4 真机 | json | 565.6 | 171.7 | 567.8 | 173.2 |
| 4.4.4 真机 | regex | 172.2 | 33.4 | 173.9 | 35.2 |
| 7.1.1 小米6 | compute | 44.8 | 10.9 | 45.0 | 14.4 |
| 7.1.1 小米6 | json | 135.8 | 55.8 | 136.5 | 67.9 |
| 7.1.1 小米6 | regex | 44.0 | 16.2 | 44.6 | 21.1 |

## 初始化与首次调用

以下初始化各测一次，非清空页缓存/重启系统后的冷启动均值。QuickJS仅统计类/库加载，WebView统计创建到空白页就绪，两者边界不同，不能直接算速度倍数。应用初始化可能提前加载浏览器组件。

### emulator-5564

- SDK 15 ABI armeabi-v7a
- quickjsLibraryLoadMs 3.5505
- webviewUA Mozilla/5.0 (Linux; U; Android 4.0.4; en-us; Android SDK built for x86 Build/MR1) AppleWebKit/534.30 (KHTML, like Gecko) Version/4.0 Safari/534.30
- webviewReadyMs 14.264

首次调用（完整调用毫秒，包含各自执行入口开销）：
- quickjs compute: 106.93
- quickjs json: 117.18
- quickjs regex: 35.5
- webview compute: 6.95
- webview json: 25.62
- webview regex: 3.43

### 8ba8ceb8

- SDK 19 ABI armeabi-v7a
- quickjsLibraryLoadMs 1.641302
- webviewUA Mozilla/5.0 (Linux; Android 4.4.4; SM-G3608 Build/KTU84P) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/33.0.0.0 Mobile Safari/537.36
- webviewReadyMs 466.27677

首次调用（完整调用毫秒，包含各自执行入口开销）：
- quickjs compute: 199.16
- quickjs json: 567.81
- quickjs regex: 173.93
- webview compute: 30.9
- webview json: 178.28
- webview regex: 48.44

### ff0316c7

- SDK 25 ABI armeabi-v7a
- quickjsLibraryLoadMs 6.76276
- webviewUA Mozilla/5.0 (Linux; Android 7.1.1; MI 6 Build/NMF26X; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/119.0.6045.194 Mobile Safari/537.36
- webviewReadyMs 731.401354

首次调用（完整调用毫秒，包含各自执行入口开销）：
- quickjs compute: 67.6
- quickjs json: 139.05
- quickjs regex: 46.17
- webview compute: 30.66
- webview json: 54.96
- webview regex: 21.95

## 结论与边界

这些可重复ES5负载中，已就绪的WebView执行更快。4.4的JS内部耗时优势约3.3–10.9倍。QuickJS仍可提供独立于系统WebView的现代语法兼容性；此结果不足以支持把全部插件改回WebView。应再测实际站点脚本端到端耗时后决定分流。

4.0.4是x86/Houdini环境：QuickJS ARM32经转译，而系统WebView执行路径不同；该行不能代表ARM 4.0真机的纯引擎差距。三设备硬件不同，不能将跨设备差异归因于Android版本。

JS内部使用Date.now（毫秒精度），完整调用用System.nanoTime并包含线程调度、JNI或WebView回调开销。按QuickJS后WebView的固定顺序执行，未控制CPU频率和热状态；本轮仅一组进程，不是统计意义上跨重启的严谨排名。

原始日志和summary.json位于`.codex-tmp/js-performance/`，测试代码：`app/src/androidTest/java/xiao/bu/tv/JsPerformanceInstrumentation.java`。测试后停止应用，不持续播放。
