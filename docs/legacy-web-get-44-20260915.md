# Android 4.4 HTTPS GET 小范围验证

本轮只接管 WebSourceView.open 发起、能确认是 GET 的 HTTPS HTML 文档，复用已有 NetworkClient / OkHttp / TLS。Android 5.0 及以上、HTTP、任意子资源、POST、动态接口和视频流保留原路径。没有新增缓存，没有改网站脚本，也没有全局忽略 WebView 的证书回调。

Android 4.4 的旧拦截接口不提供方法和请求头，因此不把任意 URL 猜成 GET。Cookie 在主线程取快照、异步回写；避免在 Chromium 拦截线程同步调用 CookieManager 导致后续页面堵塞。响应以流交给 WebView，不将 HTML 整体装入内存。非 HTML、HTTP 错误和不支持的重定向回退原路径。

设备：三星 SM-G3608，Android 4.4.4，ADB 8ba8ceb8。使用真实 WebSourceView 对照原生请求与 GET 桥接；每页等待约 11 秒，记录 DOM 和截图。抽测频道表 6 个不同域名，并补充 B 站、网易云，共 8 站。不是全部频道验收，也不是隔离网络/缓存条件的性能基准。

频道表：[webview.txt](https://github.com/buhanzhe/webSourceM3U8/blob/main/webview.txt)。接口边界参考：[Android WebViewClient](https://developer.android.com/reference/android/webkit/WebViewClient)。

| 站点 | 本轮观察 |
| --- | --- |
| 央视频 | 前后均获取 HTML，正文为空，未观察到改善 |
| 央视网 | 前后均有 HTML；桥接截图显示导航和频道列表，未验证播放成功 |
| 广东台 | 原生采样仍在 about:blank；桥接得到标题与 3196 字符 HTML，但正文仍空白 |
| 河北台 | 前后 DOM 基本相同，可加载页面，未验证播放成功 |
| 芒果 TV | 站点转到 HTTP，保持原路径，未观察到明显变化 |
| 江苏台 | 前后均有 HTML、正文为空；站点脚本报 const 严格模式语法错误 |
| B 站 | 前后均跳转到“浏览器下载建议”页面 |
| 网易云 | 桥接截图可见首页内容；子脚本有语法错误，未验证登录/音乐播放 |

结论：HTTPS 文档获取有局部收益，无法解决旧 Chromium 的脚本/DOM/媒体能力差异。保留最小改动，不扩大到脚本转译或全站网络代理。

回归工具：LegacyWebGetInstrumentation；样本：androidTest/assets/legacy-web-get-sites.txt。本地证据位于 .codex-tmp/web-get-44-fixed.txt、web-get-console.txt、web-get-cctv.png、web-get-netease.png。
