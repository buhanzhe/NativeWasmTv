# BL 原生 JAR 频道

BL 将用户提供的 BBLL 1.5.2 界面资源、布局层次与 newBV 的 Web API 合同移植到 nTv 的原生频道插件。唯一入口是 `BlPlugin.create()` 返回的根 View，页面、播放器、二维码和弹层均为其子 View；JAR 不注册 Activity、Fragment、Service 或 WebView。

## 导入

宿主需具备 BL 首版加入的 `ChannelPlugin.NativeHost.httpClient()`；已经安装配套宿主的设备可以直接更新 JAR。更早的宿主会提示更新 APK。正式包名、测试包名都可以使用相同的 JAR。

在线订阅：

https://raw.githubusercontent.com/buhanzhe/NativeWasmTv/master/plugins/bl/dist/example.m3u

`dist/example.m3u` 包含 HTTPS JAR 地址及 SHA-256。宿主下载、校验并缓存 DEX JAR。发布的订阅固定 JAR 所在的 Git 提交，避免 CDN 新旧缓存造成哈希不匹配。

## 兼容目标

| 项目 | 说明 |
| --- | --- |
| 最低目标 | Android 4.0 / API 14；UI 源码用 Google Android API 14 平台包校验，R8 `--min-api 14` 裁剪并生成 DEX |
| 已实测设备 | Android 4.4.4 / API 19、Android 7.1.1 / API 25 |
| 已知保留问题 | 4.4 强制系统 MediaPlayer 时，切分 P 仍偶发准备超时；IJK 完整回归通过。详见 [VALIDATION.md](VALIDATION.md) |
| Android 4.0 实测 | 设备未连接，尚未完成真机验证；编译通过不等于全部 ROM、解码器都已经验证 |
| WebView | 完全不依赖 WebView，没有最低 WebView 版本要求 |
| API 14–15 播放 | `BlSystemPlayer`，只引用 API 14 的平台 MediaPlayer；视频经共享 OkHttp 的本机代理输入 |
| API 16+ 播放 | Exo core 2.10.8 通过宿主 OkHttp 合并 DASH 音视频。DASH 使用桌面 Web 请求头，断流时从已读位置切到备用 CDN。点播媒体读取失败可刷新播放地址并恢复进度。Hi-Res FLAC 有软件解码路径，杜比音轨使用设备的解码或直通能力 |

BL 为当前 DASH 和支持字节范围的 MP4 点播保留已读取的片段。音视频共用 32 MiB 私有磁盘缓存，空间用满时按最近使用顺序淘汰片段。拖动进度、重建播放器或切换音轨时可复用相同媒体地址的缓存。退出播放清理缓存，剩余空间低于 128 MiB 或磁盘不可用时继续流式播放。直播走流式播放。DASH 音视频样本的内存缓冲目标为 8 MiB，IJK 队列目标为 2 MiB。写盘队列最多暂存 256 KiB，DASH 音视频各用 32 KiB 拼接连续读取的片段。
| ABI | JAR 不包含 `.so`；IJK 播放取决于宿主 APK 与设备 ABI 匹配。平台播放无需 JAR 携带原生库 |

没有直接打包 newBV 的 Compose/Media3 主应用，也没有把 BBLL 的 Activity 搬进 JAR。平台播放适配器特意避免 IJK `AndroidMediaPlayer` 中 API 16 `OnTimedTextListener` 的引用。

## 本版功能

- 推荐、热门、分区、番剧列表/剧集、直播列表及播放。
- 搜索视频/番剧/直播/UP，识别 BV 号与含 BV 号的链接；支持系统输入法和遥控器字母键盘。
- 详情、分 P、相关推荐、UP 投稿、评论阅读。
- 扫码登录、Cookie 本地保存、历史、收藏夹、稍后再看、关注列表、动态；账号接口需要有效登录。
- 点赞、收藏、投币确认、加入稍后再看。
- 视频播放、全屏控制、画质、分 P、进度、继续上次播放、连续分 P、VOD 弹幕和 JSON 字幕。
- BBLL 底部渐变控制栏、标题/分 P 信息、白色图标、紫色焦点/进度条、右上角时钟、右侧设置和居中选集弹层。
- DASH 画质和音轨按站点对当前账号返回的流显示，支持获取 8K、杜比全景声、Hi-Res 和杜比音频。播放依赖设备解码能力。点播支持 AVC/HEVC 选择、备用线路、倍速、缩放、字幕和调试信息。直播支持 v2 画质与线路以及旧接口回退。
- 正序/倒序、单集循环、随机、播完停止，以及全部播完退出播放器。系统 MediaPlayer 保持 1 倍速。
- VOD protobuf 分段弹幕，支持滚动/顶部/底部固定；字号、透明度、速度、占屏比、密度、服务端 weight 等级、关键词过滤及固定弹幕开关。接口不兼容时回退 XML。
- Android 5.0+ 直播 WebSocket 弹幕、认证、心跳、zlib 解包、限量批处理和退避重连；4.x 使用近期弹幕接口每 3 秒轮询兼容宿主旧 TLS 的串行读写。后台停止、前台恢复，所有连接仍复用宿主 OkHttp。
- 遥控器方向/确认、MENU、播放暂停/快进快退/上下集媒体键；返回先逐层关闭弹层，播放中双击返回详情，再按页面堆栈返回。频道根页面双击返回交给宿主退出。

v6 将推荐、热门、番剧、电影收进首页顶栏，并保留各分类的卡片选中位置。搜索支持数字键连按输入字母、全键盘、系统输入法和本地搜索记录，结果页可直接切换视频、番剧、直播和 UP 主。设置按通用、播放、弹幕、关于与诊断分栏。功能范围见 [PARITY.md](PARITY.md)。

v7 接入 BBLL 的分区、动态、直播、我的、播放量和弹幕量图标，并在视频卡片显示 UP 标识和独立时长。封面按实际列宽测量为 16∶9。

v8 在「设置 → 通用设置 → 主题」提供自动、黑色、白色、经典、粉色、蓝色、紫色和红色。主题、默认启动页面、封面质量与图片内存缓存上限会保存。播放设置可切换视频详情页及点击后直接播放。搜索显示热搜和本地搜索记录，「我的」分为历史、收藏、追番、追剧和稍后观看。历史保留本地记录，账号内容按登录状态展示。分区、影视和直播提供各自的分类入口。

v9 调整了设置、搜索、分区和「我的」的布局。设置独立打开，返回时保留原页面和搜索输入。分区与直播在顶部切换分类。「我的」头像菜单包含账号操作和本地历史清理。搜索键盘长按 `0` 或 `0/1` 可输入空格。

v10 移除了侧栏顶部字样。导航、设置、视频卡片和播放器图标取自 BBLL 1.5.2 APK，矢量图转换成兼容 Android 4.x 的 PNG。

v11 按 BBLL 播放页调整了标题信息、进度时间、设置顺序和相关视频。播放设置在右侧展开，选集标记当前分 P，相关视频在底部横排。直播使用独立设置入口。

v12 补齐侧栏、横向页签和播放器控制条的遥控器焦点移动。侧栏设置上方可直接打开账号，设置打开时聚焦返回键，左右键按位置进入邻近选项。新增三档默认 AAC 音轨，支持该接口的 nTv 宿主可在通用设置切换全屏。

真实接口可读取推荐/详情并播放；未登录搜索在本次测试网络可能返回空结果或 `v_voucher` 验证要求，界面明确提示登录后重试或稍后重试，不会显示成“没有搜索结果”。

## 错误详情（v3）

- 页面右上角「错误详情」，以及播放器 MENU →「错误详情」，均打开根 View 内的诊断面板。
  方向键滚动、切换「复制详情 / 返回」；返回键先退回上级菜单，再退出弹层。
- 显示错误编号、设备系统/API/ABI、插件版本、发生阶段、页面/BV/cid/分 P、请求画质，
  HTTP 方法及接口路径、状态码、站点业务码、服务追踪号、响应类型、异常原因链和处理建议。
- 播放错误保留 IJK/System 的原始 what/extra；DASH 保留 Exo 的原始异常原因链。
  记录引擎、格式、线路、解码策略、进度及实际媒体路径；MP4 CDN 错误可关联底层 HTTP 失败。
  直播换线、硬解转软解、DASH 回退 MP4、分段弹幕回退 XML 均留存原因。
- 播放接口失败显示持久错误提示，避免停在「正在加载」；解码器准备超过 30 秒也显示错误详情。
  系统播放器准备中若共享网络层已确认请求失败，立即记录并尝试直播备用线路或显示详情。
  普通页面上的错误不会在下一次时钟刷新时被覆盖。
- 每个频道会话最多保留 20 条记录、每条最多 8 KiB，退出后不保留面板历史。
  `BL-Diagnostics` 日志与复制内容均过滤 Cookie、Authorization、签名、二维码 key 和令牌；
  URL 不含查询参数与用户密码，不自动上传日志。不记录原始响应正文或请求头。

构建侧检查：`python plugins/bl/tests/diagnostics_unit.py`；启动本地服务后，
加 `--api-fixture http://127.0.0.1:39626` 可检查真实 OkHttp 的 HTTP 503、JSON 格式错误、
业务码 -352、媒体 403、失败恢复和资源关闭。诊断面板已在 Android 7.1 正式包上验收。

本轮还修复了宿主 R8 对 `IjkLibLoader` 的改名：它是 JAR 调用 IJK 的方法参数类型，
必须保留原名。如果旧宿主出现 `NoSuchMethodError: loadLibrariesOnce`，需覆盖安装修复后的 APK。
`python plugins/bl/tests/verify_host_abi.py 正式APK路径` 可校验混淆后的实际产物，
同时确认宿主未引入 Exo，也没有遗留临时测试入口。

## 运行与性能

- v4：播放引擎短暂报告未播放时，弹幕任务保留低频恢复检查；后台、关闭弹幕或 View 移除时取消任务。重复收到启动请求也不会不断撤销、重排当前帧。
- 显示/隐藏控制栏与字幕避让不再清空滚动弹幕、回放旧弹幕；底部固定弹幕随避让位置移动。仅移除过期或确实落入遮挡区的条目。
- 控制栏的时间、字幕文字/字号、图标和进度仅在内容变化时更新；时钟每分钟变化时更新，避免每半秒无效布局与重绘。
- 所有 API、图片、弹幕、字幕与 CDN 请求都使用宿主 `NetworkClient.sharedClient()` 的同一个实例。JAR 不创建其他 OkHttpClient、不携带重复 OkHttp/Okio/IJK 类或原生库。
- API 由 2 个有界工作线程处理，图片由 2 个线程处理；GridView 复用卡片，单列表最多 144 项，返回堆栈最多 12 层。
- 位图按显示尺寸采样，缓存为最大堆的 1/10 且不超过 6 MiB；历史最多 100 项、保存进度最多 256 项。
- MP4/FLV/HLS 通过带随机令牌的 `127.0.0.1` 流式代理接入，支持 Range/HEAD，以及 HLS 相对地址、Key/Map URI 重写；最大 4 条并发流、每条 32 KiB 传输缓冲、URL 表最多 1024 项。每次播放有独立会话，旧会话取消后才创建下一解码器，释放过程不阻塞 UI。
- DASH 视频与音频共用宿主 OkHttp，播放器目标缓冲 2–10 秒，分配器目标大小 8 MiB。数据读取遵守 CDN 返回的字节范围，主地址和备用地址沿用服务端签名。
- 画面 SurfaceView、字幕 TextView、弹幕 Canvas。VOD 最多缓存 3 段、每段最多 6000 条，XML 最多 12000 条；同屏 8–48 条，默认 24 条；活动播放约 30fps，暂停/后台停止绘制定时器。直播消息队列最多 200 条、历史最多 400 条，每 100ms 批量提交 UI。
- 弹幕轨道按实际文字宽度和移动速度避碰，底部弹幕及字幕避让控制栏。播放器设置仅改变 View 和偏好，不启动 Activity。
- 直播自动尝试接口返回的备用线路，v2 流不可播放时回退旧直播接口；菜单显示接口实际可用画质，直播不显示可拖动进度条。
- 关闭频道会取消 HTTP、关闭代理端口、停止线程池和定时器、释放播放器与位图缓存。HTTP/2 响应在后台关闭，避免退出时在主线程发送 RST 导致崩溃。

## 构建

Python 3.9+、JDK 8、Android SDK Build Tools 30.0.3：

```powershell
python plugins/bl/build.py --sdk D:/android/sdk --java-home C:/path/to/jdk8
```

也可设置 `ANDROID_HOME`、`JAVA_HOME`。脚本只在 `.codex-tmp/bl/build` 写中间产物。依赖版本、SDK 来源及 SHA-256 固定在 `dependencies.json`，优先使用 Gradle 缓存，否则从 Maven Central、Google SDK 和宿主使用的 JCenter 镜像获取。ZXing 3.3.0 只从源码编译 QRCodeWriter 及其依赖，不包含摄像头或扫码 Activity。Exo core 经 `shrink.pro` 裁剪，无下载 Service/Activity 子类；只在 API 16+ 创建 DASH 引擎。ZIP 条目时间固定，方便复核构建产物。

也支持新版 JDK：自动使用 `javac --release 8`，避免 Java 9 ByteBuffer 方法泄漏导致 Android 7.1 直播弹幕崩溃。
`verify_artifact.py` 会检查最终 DEX 中的方法描述符；本轮发布 JAR 使用 JDK 8 构建。

输出 `dist/bl.jar`、`dist/bl.sha256`、`dist/example.m3u`。宿主接口为 API 1 的可选扩展，既有 Folia BrowserHost/DirectoryHost 不变。插件可使用 M3U 查询参数 `apiOrigin`、`passportOrigin`、`liveOrigin` 指向本地测试接口；正式订阅不包含这些参数。

## 重复测试与诊断验证

测试服务可加 `--host 127.0.0.1`，配合设备 `adb reverse tcp:39626 tcp:39626` 与
`adb reverse tcp:39627 tcp:39627`。设备测试脚本对应使用 `--fixture-host 127.0.0.1 --cert 本地测试证书`；
证书仅在测试进程内额外受信，重新启动普通宿主后恢复正常信任，系统证书配置不变。
重复测试可用 `--skip-install --run-label round-2` 保存每轮证据，
`--errors` 验证错误详情，`--soak-seconds 20` 延长初次播放观察。
脚本只保存目标进程日志；真实站点模式不进行账号写操作。
`--interactions` 单独验证真实点击播放器、12 次控制栏切换、遥控器焦点/嵌套菜单、点击暂停与恢复，
并用 300ms 的引擎暂停模拟短暂未播放，检查恢复后的弹幕任务和实际绘制时间线。
报告主线程 16ms 心跳间隔的 P95/最大值；截图编码在采样结束后执行，不把它混入交互数据。
这些值是短测中的队列响应观察，不是显示帧率或长期性能基准。
默认轮次通过 `testPreferences=true` 使用独立的 `ntv-bl-instrumentation` 偏好，
保留正式 BL 登录、历史和设置；结束后恢复宿主上次播放频道的记录。

设备允许覆盖已有包、却拒绝安装单独测试 APK 时，可按用户要求使用原包名与原签名：

```powershell
./gradlew.bat :app:assembleArm64Release -I plugins/bl/tests/embedded-runner.gradle --rerun-tasks
```

这只生成临时测试 APK；设备脚本加 `--embedded-runner --package xiao.bu.tv`。
切换构建模式时加 `--rerun-tasks`，避免增量编译漏掉手动加入的测试源码。
测试完成必须不带 init script 重新打正式包、通过 `verify_host_abi.py` 并覆盖安装，移除测试入口。

## 来源

`reference/source.json` 记录 APK 哈希、提取布局和图标、newBV 提交。`reference/` 保存解码后的布局 XML 和图标 SVG，`assets/` 保存供旧 Android 使用的 PNG。见 `NOTICE`、`LICENSE-newBV`、`LICENSE-ZXing`、`LICENSE-ExoPlayer`；BBLL 图片/图标的原作者权利不因 newBV 的 MIT 许可而改变。

## 验证方式

`python plugins/bl/tests/verify_artifact.py` 检查 JAR 哈希、API 14 元数据、DEX 类归属，拒绝 Activity/WebView/重复宿主库。Android 实机测试入口是 `app/src/androidTest/java/xiao/bu/tv/BlPluginInstrumentation.java`。

使用独立测试包名构建（以下命令显式采用 Android 调试证书，不用于正式发布）：

```powershell
./gradlew.bat :app:assembleArm32Release :app:assembleArm32DebugAndroidTest -PapplicationIdOverride=xiao.bu.tv.bltest -PtestRunner=xiao.bu.tv.BlPluginInstrumentation -I plugins/bl/tests/test-signing.gradle
```

测试服务需要一段 40 秒 H.264 Baseline/AAC 的 MP4、开发 HTTPS 证书及私钥：

```powershell
python plugins/bl/tests/fixture.py --video path/to/video.mp4 --cert path/to/cert.pem --key path/to/key.pem
python plugins/bl/tests/run_device.py --serial DEVICE_SERIAL --package xiao.bu.tv.bltest --host-apk app/build/outputs/apk/arm32/release/app-arm32-release.apk --test-apk app/build/outputs/apk/androidTest/arm32/debug/app-arm32-debug-androidTest.apk
```

设备需可访问电脑局域网的 39626/39627 端口。加 `--real` 使用真实站点 API，`--dash` 测试 DASH，`--system` 强制走 API 14 系统播放器适配器。默认测试 IJK。DASH 测试需在视频旁准备 `video-only.mp4`（无音频）和 `audio-only.m4a`（无视频），例如用 ffmpeg 的 `-an -c:v copy` 和 `-vn -c:a copy` 分离，并加 `-movflags +faststart`。

测试服务的 `--dash-fallbacks` 让 DASH 视频/音频主地址返回 403；`--dash-reject-all` 让其所有地址返回 403、保留兼容媒体，用于验收回退。

测试服务与设备测试都加 `--premium-dash` 可验证高级流选择。把 `hevc.mp4`、`hi-res.m4a` 和 `dolby.m4a` 放在测试视频目录，这三份文件分别提供独立 HEVC、FLAC、E-AC-3 流。测试用 8K 档位标记 HEVC 样本来验收取流和切换。

测试服务与设备测试都加 `--dash-resume` 可验证桌面请求头、首个 Range、断流续读和音轨回退。设备测试加 `--real --dash --video <BV号>` 可单独验证一个公开点播片源。

结果及截图输出到 `output/bl/tests/`。测试覆盖共享客户端身份、触摸/遥控器、解码时间推进、媒体键、画质/连续分 P/备用线路/字幕、默认字幕和按钮显示设置、protobuf 弹幕实际绘制、zlib 直播弹幕、CDN 403 自动换线、后台暂停恢复、Range/HEAD、HLS URI 重写、二维码、历史、逐层返回及退出后的线程和端口关闭。`--smoke` 跳过解码器切换压力场景，只检查基础播放、面板、导航、直播弹幕和退出；不能代替完整回归。默认真实接口测试只做匿名读取。经用户授权的 `--account-audit --real --jar-url HTTPS_JAR` 使用已有正式登录，只允许 GET/HEAD，阻断账号写请求；打开并取消确认弹窗，末尾检查登录及写请求尝试数，并恢复本地设置。报告不会输出账号标识或凭据。`--local-jar PATH` 可通过 ADB 将开发 JAR 放入私有缓存，仍需通过 source 中 SHA-256 和正式加载器检查；不必向旧设备开放电脑端口。账号验收不使用 `testPreferences=true`，且不能与故障注入混用。发布验证记录见 [VALIDATION.md](VALIDATION.md)。

UI 提取可复核：先用 apktool 解码用户 APK（`apktool d -s`），再运行 `python plugins/bl/extract_ui.py DECODED_DIR --sharp SHARP_MODULE`；只提取资源，没有导入 APK 的 DEX。
