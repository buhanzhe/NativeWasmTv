# WebView 原生渲染崩溃调查（未闭环）

## 已确认的事实

- Bugly issue #8，最后一次发生于 2026-09-09 21:36:44；Chrome 中读到两次上报、一个设备。
- 应用 `xiao.bu.tv` 1.6.0 的 `RenderThread(2397)` 收到 `SIGTRAP(TRAP_BRKPT)`。
- 用户提供的寄存器、`app_process32` 和 `/system/lib/` 映射确认该进程为 ARM 32 位。`WebViewGoogle64.apk` 的文件名不能用来判断宿主进程位数。
- 设备截图：Xiaomi 25019PNF3C，Android 17/API 37，WebView 149.0.7827.91，SM8750，设备总内存 14.8 GB。
- 堆栈主要位于 WebView 原生库及 `libhwui.so`，并非 Java 异常栈。
- 原始附件一是寄存器和可执行映射，不包含完整的进程内存统计。
- 原始附件二为 Crashpad 编码转储的尾部：首行是被截断的编码内容，末尾有 `END CRASHPAD MINIDUMP`，缺少开始标记和此前内容。不能把这些字符解释为普通错误日志，也不能可靠地还原完整 minidump。
- 附件中没有找到完整的 OOM、CHECK/FATAL 原因、GPU context lost 或 allocation failed 消息。最后的 AudioRecord/AudioTrack 日志不能单独证明它们导致了崩溃。

## 当前判断与限制

可以定位到应用进程内 WebView/HWUI 原生渲染链路，尚不能在内存分配失败、图形上下文丢失、驱动问题或其他 Chromium 断言之间确定根因。SIGTRAP 本身不区分这些原因。设备物理内存充足不排除 32 位虚拟地址空间或 GPU 资源分配失败；现有映射也不足以证明这两种问题实际发生。

已有 `WebViewRecovery.onRenderProcessGone` 用于 WebView 渲染子进程退出后的清理和恢复。这次是宿主进程 RenderThread 的原生致命信号，该回调和 Java try/catch 不能兜底。

检查到投屏路径在 API 23+ 使用额外硬件图层，并通过硬件 Canvas 捕获网页。这是进一步排查的候选路径，但报告没有证明崩溃当时的投屏状态或图层配置，因此本次没有据此切换软件绘制、删除硬件图层或修改投屏帧率。

## 本次代码改动：补充诊断，非根因修复

Bugly 用户数据新增：

- `web_environment`：实际进程位数、Android API、API 26+ 当前 WebView provider 及版本。
- `web_render`：网页虚拟尺寸、图层类型、硬件加速状态、是否投屏、投屏帧率；关闭、销毁及 rendererGone 时更新状态。
- `web_memory`：带时间戳的 Java 堆用量/上限、原生 malloc 堆、VmRSS、VmSize。首次打开网页和其后每 30 秒采样，关闭/销毁时停止。
- `memory_trim`：最近一次系统内存回收通知及时间。收到通知不等于 OOM。

不记录网页 URL、正文、Cookie 或截图；不扫描 smaps/PSS、不主动 GC、不在逐帧编码路径采样。浏览器未打开时不创建诊断实例或查询 WebView provider，保持原生频道的冷启动路径。

这些数据是崩溃前最近的采样，不是崩溃瞬间的精确内存峰值；原生堆统计不涵盖全部 GPU、映射和 WebView 子进程内存。仍需要完整的致命错误文本或匹配版本的 minidump/符号来确认具体断言。

## 验证

- arm32 / arm64 debug 构建通过，包名仍为 `xiao.bu.tv`。
- Android 9 本地设备执行已有 CastResumeInstrumentation，通过 A → B 网页切换及结束接管恢复回归。
- ADB 实际观察到 `viewport=1280x720 layer=0 hw=true cast=false fps=0` 以及带时间戳的 Java/native/VmRSS/VmSize 采样。
- 测试结束已 force-stop 应用，关闭临时 HTTP 服务并移除本次 ADB reverse。
- 没有在发生崩溃的 Android 17 / WebView 149 设备上复现，不能宣称该崩溃已消除。Android 9 样本不用于推断出问题设备的内存用量。

诊断包：

- `app/build/outputs/apk/arm32/debug/app-arm32-debug.apk`
- `app/build/outputs/apk/arm64/debug/app-arm64-debug.apk`

先保持 32 位架构复现能减少变量；64 位对比仅用于检验架构相关性，不能直接当作修复证明。

## 官方参考

- [Crashpad base94 编解码格式](https://chromium.googlesource.com/crashpad/crashpad/+/HEAD/tools/base94_encoder.md)
- [Android WebView 渲染进程退出处理](https://developer.android.com/develop/ui/views/layout/webapps/handle-termination)
- [Chromium 渲染上下文丢失导致致命错误的实例](https://issues.chromium.org/40250147)：不同版本、不同设备，只用于说明同类信号不等于 OOM，不认定为本次同一缺陷。
