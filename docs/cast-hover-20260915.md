# 投屏网页的飞鼠悬停修复

## 原因与修改

Android 17 投屏给 Android 7.1.1 小米 6 时，光标坐标和 WebView 缩放映射正确，但保留的 WebView 收到原生 HOVER_MOVE 后，网页没有收到 mousemove，CSS :hover 也没有更新。重新挂载、请求焦点及补 HOVER_ENTER 均未解决。

Chromium 的 WebContentsViewAndroid::OnMouseEvent 会将 HOVER_ENTER / HOVER_EXIT / HOVER_MOVE 先交给无障碍命中处理。设备启用了无障碍服务、未开启触摸探索时也复现。实验中在同一个 WebView 上改为按钮未按下的原生鼠标 ACTION_MOVE，立即恢复网页悬停。

参考源码：[WebContentsViewAndroid::OnMouseEvent](https://chromium.googlesource.com/chromium/src/%2B/a130fecfcf274e3ac59c7ded9760433b3d918f9b/content/browser/web_contents/web_contents_view_android.cc)。

- MainActivity 在飞鼠悬停分发期间设置一个同步作用域标记，仍通过原来的 ViewGroup 命中与坐标转换。
- CastWebView 仅在该作用域内，把鼠标 HOVER_MOVE 转换成原生 ACTION_MOVE；HOVER_EXIT 转成视图外的移动，清除旧的悬停状态。
- API 19 以下继续使用原来的 WebKit 路径。系统无障碍事件、真实外设事件以及点击、拖动、滚轮路径保持原行为。
- 无需重新加载网页，也不模拟 DOM 事件。浏览器继续负责 iframe 命中和网页交互。

## 实机验证

发送端 fc14c2c0（Android 17），接收端 ff0316c7（Android 7.1.1），接收地址 10.217.137.28:9966；通过正常 App 的局域网接管流程，关闭实验性 Wi-Fi Direct。

| 场景 | 结果 |
| --- | --- |
| 修复前，保留 WebView 投屏 | 原生事件到达，DOM mousemove 为 0，CSS :hover 为空 |
| 修复后，Android 17 本地 B 站 | 12 次移动全部收到，移出清空、移入恢复 |
| 修复后，B 站投屏 | 12 次移动全部收到，WebView 实例及 URL 保持不变 |
| 修复后，央视频直播页面投屏 | 12 次移动全部收到，移出清空、移入恢复 |
| ADB 在 Android 17 飞鼠触控板上实际滑动 | 两站均收到新增 mousemove；小米 6 截图确认播放栏出现，视频持续播放 |
| 小米 6 本机 B 站回归 | 悬停命中与鼠标位置一致，移出清除、移入恢复均通过 |

小米 6 忙碌页面会合并连续移动事件（本次 12 次输入产生 6 次 DOM mousemove）；回归断言验证事件确实到达、最终命中一致及移出/移入行为，不要求浏览器逐个保留所有采样。

测试入口为 CastHoverInstrumentation，使用 `-e receiver http://<接收端>:9966` 测试接管，`-e local true` 仅测试本机网页。测试临时频道和设置结束后恢复，不保存测试频道表。

本地证据：`.codex-tmp/hover-touch-pair.txt`、`hover-touch-Bili-tv.png`、`hover-touch-YSP-tv.png`、`hover-fixed-mi6.txt`。
