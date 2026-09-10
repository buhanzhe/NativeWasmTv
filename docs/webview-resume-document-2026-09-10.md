# 网页退出接管后保留原文档

原实现虽然记住了最后网页 URL，但 releaseReceiverPlayback 调用 closeWebSource，后者让 WebView 导航到 about:blank；随后按 URL 新建/重载网页。返回同一个 URL 并不等于保留网页状态。

当前网页渲染仍位于手机的 View 树中，由投屏合成器采集。因此退出时不需要把 Chromium 进程搬到另一块屏幕，只需停止编码/RTSP，恢复本机视口并保留原 WebView。

## 改动

- 网页按钮退出、原生按钮退出、电视断线三条恢复路径，在当前网页仍可用且可见时保留原 WebView 和文档，不执行 closePage、loadUrl 或历史清空。
- 保留原播放请求标识，避免页面后续回调失配；取消遗留的换台、播放器创建和 Surface 重启任务，防止延迟回调覆盖刚恢复的网页。
- 释放推流和接管状态，恢复手机视口、绘制层和输入焦点；修复没有背景 Drawable 时跳过视口恢复的边界情况。
- 已进入原生视频的隐藏网页不自动抢回前台；页面已关闭/渲染器已消失时继续使用原有恢复逻辑。
- 后台超时清理与不要求恢复内容的退出仍关闭网页，避免保留不再使用的渲染器。

保留文档意味着 JS 内存、未提交表单、历史和当前页面播放状态不因应用重新加载而丢失。视口大小变化仍可能触发网站自身的响应式布局；系统暂停、网站自身脚本或媒体策略也可能影响播放，不保证任何网站都完全没有停顿。

## 验证

- 双架构 debug 与 AndroidTest 编译通过。
- CastResumeInstrumentation 已强化为真实 WebView/编码器生命周期测试，覆盖三条退出路径，检查 WebView 对象、JS 随机标记、未提交表单和非零滚动位置，确认编码器停止、投屏视口清除；另检查纯清理路径不保留文档。
- 小米 6 接受了应用覆盖安装，但测试 APK 被系统拒绝：INSTALL_FAILED_USER_RESTRICTED / Install canceled by user。未绕过限制，因此本次没有执行上述设备断言，不将编译通过当作实机验证通过。
- 已退出双方应用。

正式包目录：output/release-20260910-webview-resume。构建日志：.codex-tmp/webview-retain-release.log。
