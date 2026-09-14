# Ku9 公共协议与双引擎

普通 Ku9 与 CJS 站点脚本共用 `Ku9JsContract`、`Ku9Host` 和 `Ku9ScriptEngine`。协议统一在 nTv 宿主实现；后续 CJS 站点脚本修复及逐频道验证见 [设备测试报告](ku9-device-matrix-20260914/README.md)。

## 引擎选择

- 默认 QuickJS，不再按 Android 版本固定选择引擎。
- 检测原始脚本中的常见浏览器依赖（如 document、Canvas 所需的 document、XMLHttpRequest、fetch、定时器等），选择 WebView。检测会跳过注释和普通字符串，也支持 `window['document']`。
- 脚本前 4096 字符内可以用单行注释显式指定，优先于自动检测：

```js
// @ku9-engine: webview
```

```js
// @ku9-engine: quickjs
```

静态检测是保守判断，并非完整的 JavaScript 依赖分析。计算属性名、动态加载代码、模板表达式里的浏览器依赖应显式声明；包含同名局部变量的纯脚本也可声明 QuickJS。引擎选定后不会因 403、网络超时或业务错误重复执行另一引擎。

Android 4.2 以下仍可运行纯 Ku9/QuickJS。需要 WebView 的脚本在这些设备上明确报错，避免使用 API 17 之前不受注解限制的 JavaScript Java 桥。WebView 路径可用的 DOM/Canvas/JS 特性仍取决于系统 WebView，选择 WebView 不会为旧系统补齐现代浏览器能力。

## 统一内容

- 同一份 JS 包装：main / module.exports / module.exports.main、Promise/thenable 返回、URL 查询参数注入、CryptoJS/RSA 模块及 Ku9 辅助方法。
- 同一份 Java 桥：HTTP、URI、散列、Base64、日期转换与回调处理。
- 同一份缓存读写与毫秒 TTL 实现。JS 缓存值统一按 JSON 序列化，保留字符串、数字、对象、数组和 null 的类型；读取仍兼容以前未编码的文本值。保留普通 Ku9 原有缓存键及 CJS 的站点隔离。
- 同一份输出别名与 M3U8 解析。CJS 清晰度选择、原生 transformer 和结果缓存继续由 CJS 层处理。
- 同一执行层负责超时、取消、WebView 回收及重复回调防护；每次执行独立 host，旧请求不能覆盖新结果。错误同时保留正文和堆栈。
- 普通 Ku9 的 URI 与 IPv6 检查不再随引擎改变。有限 M3U8 在两种引擎下都不重复刷新。
- 原始脚本及公共包装在后台准备，播放列表刷新复用已准备的程序。

## 验证

`Ku9UnifiedInstrumentation` 使用同一份程序，分别通过真实 QuickJS 和 WebView 运行：

- CommonJS、CryptoJS/RSA、URI/query、类型化缓存、POST 两种参数顺序、请求头、日期转换。
- 两种引擎返回完全相同结果；HTTP 403 不重试另一引擎；重复完成只回调一次。
- DOM/Canvas、浏览器异步 thenable、取消后不回调、取消后切换执行引擎。
- 现有 Ku9/CJS 契约用例：Promise、结果别名、生成 M3U8、站点缓存隔离与过期。

2026-09-14：Android 4.4 真机（8ba8ceb8）与 Android 9 模拟器（127.0.0.1:21503），Debug 和正式混淆 ARM32 APK 均通过上述测试。

后续已解除小米 6 的测试组件安装拦截，并连接 Android 4.0.4。四设备逐频道验证、补测结果和当前限制见 [设备测试报告](ku9-device-matrix-20260914/README.md)。上述契约测试是协议与生命周期回归，不是直播首帧速度测试，也不代表每个第三方脚本都可用。

构建测试：

```powershell
./gradlew.bat --offline :app:assembleArm32Release :app:assembleArm32ReleaseAndroidTest '-PtestBuildType=release' '-PtestRunner=xiao.bu.tv.Ku9UnifiedInstrumentation'
adb -s DEVICE shell am instrument -w xiao.bu.tv.test/xiao.bu.tv.Ku9UnifiedInstrumentation
```

测试 APK 必须与本次构建的宿主 APK 配套安装，不能混用旧版或不同混淆映射的 APK。
