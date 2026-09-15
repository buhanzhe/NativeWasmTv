# QuickJS 执行网页脚本、WebView 操作 DOM 实验

实验前的当前代码已提交为 `dd1ca10`。实验仅包含 `androidTest` 文件，不进入普通 APK、不改变正式版开关、网页执行引擎或 Ku9 行为。

## 实现

`QuickJsDomInstrumentation` 下载 HTTPS HTML，在 WebView 关闭 JavaScript 的状态下装载文档，再将初始脚本设为惰性类型以避免重复执行。保留脚本节点、提供 currentScript，并去掉事件属性和 noscript 内容。下载到的初始经典脚本只获取一次，分别交给 WebView 和 QuickJS 执行。

QuickJS 复用现有 `NativeQuickJs` 和原生库。测试线程通过现有 Host 接口传递 JSON 操作，主线程用 evaluateJavascript 操作真实 DOM，结果通过回调返回测试线程。WebView 端桥接使用 ES5；QuickJS 端使用 Proxy 缓存 DOM 对象引用，支持属性读写、方法调用、构造函数、事件回传及有限定时器。没有将网页 DOM 转成假数据。

这是初始脚本接管原型，不是完整浏览器实现。模块脚本、动态脚本装载、同步返回的事件回调、跨引擎原型关系、任意循环对象、iframe 环境及标准浏览器事件循环还不完整；Promise 作业由现有 NativeQuickJs 在顶层脚本结束后处理。生命周期事件由测试工具重放，不等同于浏览器解析时序。真实站点结果只能用于发现边界，不能视为正常浏览器端到端加载测速。

边界：仅测试组件开启；每个脚本下载上限 2 MiB，初始脚本数量上限 20，累计字符预算约 4 MiB，DOM RPC 上限 2000 次、单次回调等待 3 秒、执行预算 15 秒；沿用原生 QuickJS 16 MiB 内存/20 秒中断保护。没有重新编译或增加原生库。

## Android 4.4.4 实测

设备：Samsung SM-G3608，ADB `8ba8ceb8`。2026-09-15，ARM32 debug App 和测试组件。

同一份 ES5 控制脚本验证：创建按钮、读写属性、DOM 引用相等、点击事件回传、定时器均通过。额外现代语法脚本使用箭头函数和默认参数：原生 WebView 报 `Unexpected token >`，QuickJS 成功写出 `42`。

最终三站对比那轮的控制脚本，100 次设置宽度并读取 offsetWidth：

| 执行路径 | 循环耗时 |
| --- | ---: |
| WebView 原生 | 63 ms |
| QuickJS → Java → WebView DOM | 2329 ms |

该轮整段 QuickJS 控制测试含事件泵共 364 次 RPC，累计 RPC 时间 2951 ms。早期相同控制脚本也得到 64/2413 ms 和 86/2062 ms，均显示同步桥接开销明显。不是 QuickJS 纯计算性能比较；不能据此认定其解密/解析比 WebView 慢。

| 网站 | 原生初始脚本 | QuickJS 接管结果 |
| --- | --- | --- |
| 江苏 `live.jstv.com/?channelId=676` | app 脚本在严格模式 const 处语法错误 | 越过语法解析，但出现 `TypeError: not a function`；动态脚本不支持，页面仍为空白 |
| 网易云 `music.163.com` | 装载方式和环境也影响初始化 | 出现 `trim`、`cv6e` 等对象/方法错误，只呈现页头及播放器框架，未验证可用播放 |
| B 站 `www.bilibili.com` | 多段现代语法报错，缺少 Map/System | 部分原先报语法错的脚本执行成功，但仍遇到循环对象 JSON 传输和方法错误、缺少 System；静态内容存在，布局和交互未恢复 |

网易云另以开启 DOM storage 的最终配置补测，QuickJS 仍在相同对象/方法位置失败。因此并非简单补上 localStorage 开关即可解决。普通 WebView 是否可正常访问，应以正常 App 路径的测试为准。

观察结论：能让部分现代 JS 操作旧 WebView 的真实 DOM，但这版原型没有使三个站点完整可用，逐次同步 DOM 桥接还显著增加延迟。保留实验工具，不并入普通网页执行路径。

## 复现

PowerShell，在项目目录执行（需配置可用 JAVA_HOME）：

```powershell
./gradlew.bat --offline :app:assembleArm32Debug :app:assembleArm32DebugAndroidTest '-PtestRunner=xiao.bu.tv.QuickJsDomInstrumentation'
adb -s 8ba8ceb8 install -r app/build/outputs/apk/arm32/debug/app-arm32-debug.apk
adb -s 8ba8ceb8 install -r app/build/outputs/apk/androidTest/arm32/debug/app-arm32-debug-androidTest.apk
adb -s 8ba8ceb8 shell am instrument -w xiao.bu.tv.test/xiao.bu.tv.QuickJsDomInstrumentation
```

仅运行基础验证：增加 `-e sites proof`。指定一个站点：增加 `-e sites https://music.163.com/`。默认运行基础验证及江苏、网易云、B 站。API 19+，使用匹配的 debug App 和测试包；不要混用混淆后的 release 包。

设备报告：`/sdcard/Android/data/xiao.bu.tv/files/quickjs-dom-report.txt`；截图同目录 `quickjs-dom-<域名>-0.png` 为 WebView、`-1.png` 为 QuickJS。

本机详细记录未入库：`.codex-tmp/quickjs-dom-44-final.txt`、`.codex-tmp/quickjs-dom-44-storage.txt`、`.codex-tmp/quickjs-dom-{jstv,bili,music}.png`。测试结束已移除测试组件并恢复正常 App。
