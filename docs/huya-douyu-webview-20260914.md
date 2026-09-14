# 斗鱼、虎牙 WebView 不播放调查

2026-09-14，小米 6 / Android 7.1.1 / WebView 119.0.6045.194，使用应用真实 WebView，通过 ADB 与仅测试包开启的 CDP 采样。桌面 UA 保留实际 Chromium 119 版本。

## 自动嗅探接管与网页播放对照

- 虎牙 `https://www.huya.com/660579`：开启自动接管时，发现的 `adsmind.gdtimg.com/ads_svp_video__….mp4` 被交给 IJK。ADB 截图显示游戏广告占据播放器；原网页 visibility=hidden、媒体暂停保护开启、直播 video 暂停。广告被误当作主节目。
- 关闭自动接管并恢复网页后，同一虎牙直播间出现有效视频输出，800×450、无 media error，直播由 srcObject 切换到 blob/MSE 后 currentTime 与解码帧开始增长。此为短时恢复证据，未完成长时间稳定性验证。
- 斗鱼 `https://www.douyu.com/100`：关闭自动接管时网页内 1920×1080 视频播放正常，4 秒采样 currentTime 从 1465629.912477 到 1465633.913946，解码帧从 2831 到 3069，未出现 media error。
- 重新开启自动接管并加载斗鱼后，抓到 FLV 并隐藏、暂停原网页。IJK 能识别 HEVC/AAC 且解出首帧，随后重复出现 `av_read_frame error: unknown` 和多次重新打开同一流。因此不能简单判断为不支持 HEVC；读流失败的底层原因尚未进一步区分代理、服务端连接和 FLV 处理。

代码入口：MainActivity.onStreamDiscovered 只要开启 webViewAutoPlaySniffed 就立即调用 startSniffedResource；WebSourceView.normalizeMediaPlaylist 按文件扩展名识别，未区分广告与主视频。hideForStreamPlayback 暂停原网页是现有正常设计，但当接管对象错误或新播放器失败时，用户就看到网页不播放。

## 独立的 MIUI 内存策略问题

两次连续网站测试分别在 14:48、14:55 被系统结束。MIUI MemChecker 明确记录 WebView sandboxed_process0 PSS 为 259.54 MB、280.81 MB，超过其内存检测阈值，执行 Kill；随后 Chromium 因渲染进程结束而终止应用。不是凭内存大小推测，也不是已证实的整机 RAM 耗尽。

WebViewRecovery 仅在 API 26+ 安装 onRenderProcessGone 回调；本设备 API 25 无该公共恢复入口。不能直接把 API 26 回调移到 Android 7.1 使用，也未修改或绕过 MIUI 系统策略。

## 处理方向与范围

优先保留两站网页原播放器，不让发现的任意 URL 立即接管；资源列表仍可保留手动选择。接管失败应恢复原网页，广告识别需要单独处理。斗鱼 IJK FLV 读流问题与 MIUI 内存问题另行处理，不能用更换 UA 掩盖。

本轮为原因调查，未修改生产播放策略。测试结束恢复原来的自动接管开关及正式 APK。测试脚本、原始 CDP JSON 与 logcat 在本地忽略目录 `.codex-tmp/live-sites*`、`huya-*.json`、`douyu-*.json`，其中可能包含短期直播鉴权参数，不提交原始日志。

Android 9 模拟器确认内核为 Chromium 68，但未在该设备完成本轮两站对照；以上结果不可直接外推到所有 Android/WebView 版本。
