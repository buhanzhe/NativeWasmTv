# 局域网影视点播 / TVBox type 1 聚合

手机打开 nTv 已有的局域网管理页，进入“影视点播 / TVBox”。导入自己的 TVBox 单仓 JSON 订阅后，输入片名，可搜索所有支持的源，选择聚合结果中的来源和线路，将整条线路保存为电视的独立点播分组。无需下载/上传 TXT，不自动打断正在播放的节目。

## 范围与设计

- 仅支持普通 type 1 JSON 采集接口。支持搜索、分页、详情、M3U8/MP4直链线路；不执行订阅中的JAR、JS、Python或其他爬虫代码，不支持通用网页嗅探和DRM。
- 双工作线程搜索，逐源失败隔离、增量轮询和取消断连。默认每源1页，可选最多3页，每页200条，总候选2000条；明确展示未取完，不宣称全库扫描。
- 以来源接口的摘要和影片ID保留候选身份。按标题、年份、类型保守聚合；未知元数据单列，保留季数/版本字样。不自动切换或混拼不同源的剧集。
- 解析 `vod_play_from` / `vod_play_url`，保留多线路、完整query及URL中的`$`；相对地址按API基址解析。投递前核对剧集结构，允许签名地址轮换。
- 成功非空搜索页缓存2分钟，最多16页/256K字符。源检测绕过缓存。订阅和点播库持久保存；搜索任务与检测结果只保留在进程内。
- 独立 `vod_library_v1` 存储，读取时合并到频道快照，不覆盖直播来源；最多30条点播线路、每条1000集。相同源/影片/线路重复投递更新已有分组。
- 网络抽检仅检查选定线路首集的清单、相关AES-128密钥、初始化段和最多64KB媒体样本；不是全剧验证或播放器解码。媒体抽检与后台搜索/源检测互斥。

## 配套兼容性修复

点播在旧Android实际验证中依赖以下修复，因此随功能一起提交：

1. 在线CJS协议5按APK ABI和系统API选择 `armv7-base`、`armv7-perf` 或 `arm64`；校验profile/ABI/minSdk，使用独立缓存，保留SHA及ELF校验。
2. 将项目已有ARMv7原生TLS客户端适用范围扩至API14–20，处理旧系统TLS提供者与现代站点不兼容的问题。此变更继承现有证书信任策略，**不是新增严格证书验证**。
3. 分离直播滚动AES映射与有限点播映射，避免超过512段的点播丢失开头解密参数。有限映射上限16384，超限明确失败。

默认applicationId、应用名称、版本和播放器保持不变；可选 `-PapplicationLabelOverride=...` 配合既有包名覆盖参数构建共存测试包。

## 安全边界

仅使用有权访问的订阅和媒体。**局域网管理服务没有登录认证，不可开放到公网，也不可用于不可信局域网。** 新接口的JSON内容类型、同源Origin检查不是用户认证，也不是完整的DNS重绑定防护。

此功能有意允许用户配置HTTP(S)采集/媒体地址（包括自有局域网地址），不是通用SSRF防护网关。UI以textContent渲染源站文本；接口限制输入/响应规模，订阅令牌不内置于代码或公开状态；上游异常仅返回脱敏类别。媒体详情必须包含可投递地址，能访问管理端的客户端也能取得这些地址。

JSON请求采用15秒总预算触发断开，媒体抽检单次请求最多8秒、整体25秒预算；另有连接/读取超时。底层系统阻塞可能影响实际退出时间，不能等同于操作系统级强制终止。

## 验证与复现

纯Java测试不依赖在线媒体服务；使用JDK8和一个独立的`org.json`测试依赖（例如Maven `org.json:json:20240303`）。Android SDK的stub android.jar不能代替可运行的JSON实现。

```powershell
./tests/run-vod-unit-tests.ps1 -JavaHome <JDK目录> -JsonJar <json.jar路径>
```

71项断言覆盖采集/相对地址/查询参数、缓存、聚合年份类型季数、分页去重、双并发、失败隔离、取消、候选上限、请求总预算、媒体清单和密钥、错误脱敏、插件profile及715段AES映射。

管理页模拟接口回归需要Node.js和Playwright；依赖放在忽略目录中：

```text
npm install --prefix .codex-tmp/ui --no-save playwright
node tests/vod-ui.cjs
```

浏览器使用 `CHROME_PATH` 环境变量指定的路径、Windows常见Chrome路径或Playwright自带Chromium。使用自带Chromium前，运行 `.codex-tmp/ui/node_modules/.bin/playwright install chromium`（Windows使用对应`.cmd`）。此脚本只验证页面原有导入/单源搜索/分页/详情/投递/检测/删除回归；多源交互另外进行了下述Android实测，不把模拟接口测试称为设备端测试。

本次另在Windows官方ARM Android4.4/API19软件模拟器覆盖安装并验证：

- 受控上游7个唯一源形成预期4个聚合分组；慢源、错误源、重复页和取消不破坏已有结果；真实Chrome访问APK后端，完成刷新恢复、首集网络抽检和整剧投递。
- 已有真实订阅250源可恢复；单源搜索、37集详情和重复投递成功；直播分组未变。
- CCTV-1直播和点播首集媒体均出现新的视频解码首帧，1920×1080。未实听声音、未验证全片或所有剧集。
- 用户反馈其电视当前可用；未收集该电视的完整硬件/系统信息，不推断所有同类设备兼容。

未本轮验证API14–18、ARM64和全部源；不包含自动选最优源、跨源续播或自动下一集。用户的订阅、媒体账号和测试APK不随提交提供。

## 代码入口

- `TvBoxService`：采集、缓存、详情、任务入口。
- `VodSearch`：有界搜索、聚合及快照；`VodProbe` / `VodDeadline`：媒体抽检和请求预算。
- `MainActivity.handleVod` / `LocalControlServer`：现有 `/api/vod` JSON动作；`res/raw/vod.html`：ES5/XHR管理页。
- 新动作：`searchAll`、`searchState`、`searchCancel`、`probe`、`probeState`、`probeCancel`；源相关操作携带订阅revision，任务查询/取消携带task。

架构参考：[MoonTVPlus](https://github.com/mtvpls/MoonTVPlus) 的多源搜索、聚合和分层检测；Java/ES5独立实现，没有复制其CC BY-NC-SA React/Next.js代码。
