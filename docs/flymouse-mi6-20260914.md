# 小米 6 飞鼠绘制优化

## 连续绕圈卡顿的后续处理

此前修改只消除了反复 bringToFront 造成的布局开销，不能解释为已经解决所有卡顿。
本次进一步处理放大效果的绘制和调度：

- 箭头按最大 2 倍尺寸预绘制成小位图，移动与缩放复用纹理，避免每个缩放值重新栅格化矢量路径。
- 动画刷新在 API 16+ 对齐 VSYNC，API 14–15 使用单个 16ms 回调；不再每次绘制都撤销重建计时器。
- 空闲隐藏只记录最后操作时间，保留一个检查任务，避免每次移动扫描和修改 Handler 队列。
- 手机指针交由电视显示时，手机端不再提交指针区域重绘或执行放大动画。
- 坐标计算、原生鼠标事件、点击命中位置及快速移动放大效果保留。

小米 6（Android 7.1.1）配套 debug APK/测试 APK 验证通过：快慢移动、放大与缩回、点击、
连续移动期间位图对象复用，以及隐藏指针无动画回调。固定网页上 240 个绕圈输入，
模拟重复置顶产生 241 次布局，当前逻辑为 0 次。该数据仅证明没有布局回归，
不代表 Wi-Fi 全链路帧率或输入延迟提升的量化结果。

已目视检查放大截图，构建并安装 ARM32 release 到小米 6，随后卸载测试组件。

设备：ff0316c7，Android 7.1.1。保持频道配置和飞鼠设置。

## 修改

- 光标已在顶层时，不再调用 bringToFront；移除移动处理末尾的重复调用。
- 光标移动只使新旧光标区域失效，重复显示不再使整个覆盖层失效。
- 复用固定的箭头路径，以画布平移和缩放绘制，避免每次移动重新构造路径。
- 放大和点击动画仅保留一个待执行刷新，避免移动期间积累延迟刷新。
- 保留原有坐标、原生鼠标事件、快速移动放大及点击反馈。

## 设备验证

CursorFeedbackInstrumentation 在 debug 包运行（release 混淆后不适用直接方法调用）。
快速移动样本按设备 density 换算，修复原测试在小米 6 上速度不足的问题。

实际 MainActivity/WebView 使用固定本地 HTML，分别回放 240 次移动：

| 同一页面上的层级行为 | 全局布局次数 |
| --- | ---: |
| 模拟旧代码每次 bringToFront | 241 |
| 优化后，已置顶不再调整 | 0 |

慢速不放大、快速放大、点击不改变命中坐标、停止后恢复均通过。
JS 手势回归测试通过，未修改单指/双指协议和传输路由。

通过 adb forward 向设备 HTTP 接口回放 600 次移动（约 10 秒），优化版
请求响应中位数 3.8ms，P95 5.4ms；该数据不包含真实 Wi-Fi 延迟。
真实网站先后采样的 gfxinfo 卡顿率为 0.98%、7.49%、8.13%，页面重启和加载
状态不一致，不能据此证明整条链路变流畅；受控层级对比只证明移除了布局开销。
尚未复现用户所述持续严重卡顿，也未完成手机到电视的 Wi-Fi 全链路对比。

最终在小米 6 安装 ARM32 release 构建，并重新打开应用。

## 测试组件与正式包混用的崩溃修复

正式包 mapping 将 `FlyMouseCursorView.resetPosition()` 改名为 `d()`。
旧 debug 测试组件仍直接调用原方法名，因此在主线程产生 NoSuchMethodError。
原本 onStart 的 try/catch 无法捕获另一个线程中 runOnMainSync 执行体抛出的异常。

测试入口现检查目标应用的 DEBUGGABLE 标志及所调用的光标 API，拒绝混淆正式包；
UI 测试任务内部捕获异常，再转交测试线程报告失败，不让测试错误崩溃主线程。
生产 APK 的混淆规则和光标行为保持不变。

配套构建命令（安装时主包和测试包必须来自同一次构建）：

```powershell
./gradlew.bat --offline '-PtestRunner=xiao.bu.tv.CursorFeedbackInstrumentation' :app:assembleArm32Debug :app:assembleArm32DebugAndroidTest
adb -s ff0316c7 install -r app/build/outputs/apk/arm32/debug/app-arm32-debug.apk
adb -s ff0316c7 install -r app/build/outputs/apk/androidTest/arm32/debug/app-arm32-debug-androidTest.apk
adb -s ff0316c7 shell am instrument -w xiao.bu.tv.test/xiao.bu.tv.CursorFeedbackInstrumentation
```

2026-09-14 小米 6 验证：
- 正式包 + 新测试包：运行前返回明确的不兼容测试结果，没有 NoSuchMethodError 主线程崩溃。
- 配套 debug 主包 + 测试包：慢速/快速移动、点击反馈、命中坐标、空闲恢复全部通过；240 次移动的布局 A/B 为 `[240, 0]`。
- 测试结束恢复正式包，卸载 `xiao.bu.tv.test`，避免残留测试组件继续被误启动。
