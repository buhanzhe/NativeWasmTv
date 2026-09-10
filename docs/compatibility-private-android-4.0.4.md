# 私有版 Android 4.0.4 兼容性复测

日期：2026-09-08。基线：私有仓库合并提交 `2bcd590`，另含本次发现的央视频 CJS 入口修复。
设备：`emulator-5564`，Android 4.0.4 / API 15，x86 + Houdini ARM 转译，双核。
使用启用混淆和资源压缩的 ARM32 release APK，独立包名 `xiao.bu.tv.compat404`。
原应用的 APK、频道配置和插件缓存未被覆盖；测试包首次安装时没有站点插件。

## 实际播放结果

| 类型 | 测试内容 | 结果 |
| --- | --- | --- |
| 央视网 CJS | CCTV-1，在线 cctv.cjs | 通过：新下载站点插件、执行 QuickJS、加载 cctv.so、显示动态画面 |
| 央视频 CJS | CCTV-1，在线 cmg.cjs | 修复误拦截后通过：加载 yangshipin.so，完成授权解析和实际播放 |
| 广西 CJS | 广西综艺旅游、都市、影视、新闻、国际、CETV-1、CETV-2、CETV-4 | 8/8 通过：在线插件、gxtv.so、HTTPS、动态画面 |
| Ku9 fenghuang.js | 凤凰中文 | 通过 |
| Ku9 jlntv.js | 吉林卫视、吉林都市、吉林综艺文化 | 3/3 通过 |
| Ku9 hnyx.js | 浙江卫视4K、CCTV4K 两组参数 | 2/2 通过：原始在线脚本在独立 QuickJS 中执行，直播列表持续刷新 |
| Ku9 cbg.js | 重庆测试地址 | 未播放：脚本正常执行，但返回的 getLiveUrl 地址响应 JSON，而非 HLS 或重定向；代理将 JSON 当作播放列表地址，返回 502 |

总计：修复后 CJS 10/10 可播放；Ku9 6/7 可播放。重庆源属于脚本返回地址与上游响应格式不匹配，不能记作播放通过；本次没有修改远端脚本。
所有播放通过的频道均有首帧日志和 AAC AudioCodec 日志，并比较了间隔 3 秒的两张截图，中部画面发生变化。
人工查看了广西与浙江卫视截图，未见花屏。音频仅确认解码链路，未人工试听。
“4K”为频道名称；本次浙江卫视截图中的实际码流是 1920×1080 H.265，不宣称验证了 4K 解码能力。

## 发现并修复

`cmg.cjs` 会映射到内部授权 WebView。原先“Android < 5.0 且双核设备禁止显示网页”的判断把这个 CJS 解析入口也拦截了，日志为 `Blocked WebView source`。
现在已识别的 CJS 源可以进入站点解析器；真正显示普通网页的 `openWebSource` 仍执行原有性能限制。
修复后央视频成功播放，并复查了广西、凤凰、hnyx，均正常。
修改位于 `MainActivity.resolveFallbackUrl`，尚未提交或推送。

## 混淆版运行时测试

- QuickJS：Unicode/NUL JNI、Promise/BigInt、语法错误、取消死循环、内存上限、50 次运行时创建与释放，全部通过。
- Ku9/CJS 公共协议：请求参数、查询参数、播放结果别名、直播列表、缓存隔离及 TTL、错误返回，全部通过。
- 私有版 Ku9：CommonJS/module.exports、require('crypto')、require('jsencrypt')、摘要、Base64、URI 和频道参数，全部通过。
- 旧版 TLS：3 次广西 HTTPS 请求、拒绝不可信证书、握手超时、并发关闭取消，全部通过。

## 保留插件缓存的进程冷启动

| 频道 | am start -W | 观测到首帧 | 结果 |
| --- | ---: | ---: | --- |
| 央视频 CCTV-1 | 324 ms | 5850 ms | 重新加载 yangshipin.so，正常播放 |
| 广西综艺旅游 | 296 ms | 3142 ms | 重新加载 gxtv.so，正常播放 |
| Ku9 浙江卫视 | 266 ms | 1823 ms | QuickJS 正常启动并播放 |

每次执行 force-stop 后重新启动，保留下载好的插件和应用缓存；不是重启系统。首帧按约 1 秒轮询记录，含启动、请求和播放器等待，不能当作精确解码延迟。
本环境使用 ARM 转译，这些时间不能用于判断真实 ARM 电视性能，也没有与旧版做性能对比。

## 本地复核材料

`.codex-tmp/compat404-retest/` 保存了测试 M3U、自动播放脚本、每频道原始日志、成对截图、运行时测试结果、修复前后结果及冷启动结果。
`playback-results.json` 是首轮结果，`retest-results.json` 包含修复后的复测，`cold-results.json` 是冷启动结果。
