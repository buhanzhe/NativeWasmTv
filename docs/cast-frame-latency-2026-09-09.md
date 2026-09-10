# Apple 帧号素材投屏实测（2026-09-09）

本次已下载三个链接中的 720p/60fps 视频轨，并在小米 6（Android 7.1.1，发送端）和 SM-G3608（Android 4.4.4，接收端）完成实测。主要瓶颈在发送及接收解复用之前的链路；本次没有观察到手机编码耗时达到数百毫秒。TCP 发送阻塞、积压以及超时重连造成了真实停帧。UDP 仍有延迟和损坏帧，不能通过默认改 UDP 解决。

## 素材、设备与测试范围

- 发送端：`ff0316c7`，小米 6，Android 7.1.1，192.168.0.114。
- 接收端：`8ba8ceb8`，SM-G3608，Android 4.4.4，192.168.0.113。它是本次模拟电视的设备，不能代表所有电视芯片。
- 两端使用普通 Wi-Fi 局域网；没有使用 Wi-Fi Direct。
- 三份素材均保留原视频编码，下载后本地播放，排除公网 HLS 下载波动。没有下载全部清晰度、音轨和字幕轨。
- TS / fMP4 为 H.264；第三份为 HEVC Main 10。手机硬解源视频后，全部统一重新编码为 H.264 投送。因此第三组验证的是 HEVC **输入解码**，不是 H.265 投送兼容性。
- 投送分辨率 1280×720；测试 30/60fps、4/8Mbps 配置上限、TCP/UDP。自适应码率保持开启：最终 TCP 组降至 2Mbps，最终 UDP 60fps 组约 3–3.3Mbps。因此不是固定码率的控制变量实验。
- 原生 MediaExtractor / MediaCodec 输出进入应用现有 GL 合成、编码和 RTSP 发送链路，电视使用现有 IJK 接收链路。本次不包含 WebView 网页渲染、触控输入或系统声音捕获。
- 测试入口主动指定 30/60fps，绕过旧电视正常接管时的帧率协商限制，以观察链路承载能力。

下载的可复用文件位于 `output/apple-latency-20260909/`：

| 文件 | 视频时长 / 帧数 | 字节数 |
| --- | --- | ---: |
| ts.mp4 | 120s / 7200 | 45,083,678 |
| fmp4-verified.mp4 | 120s / 7200 | 45,083,655 |
| hevc-verified.mp4 | 126.1s / 7566 | 38,165,542 |

原始地址：

- [H.264/MPEG-TS](https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_ts/master.m3u8)，选择 v6。
- [H.264/fMP4](https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8)，选择 v6。
- [HEVC/H.264](https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_adv_example_hevc/master.m3u8)，选择 v15。

早期使用旧版 FFmpeg 封装 fMP4/HEVC 时出现非单调 DTS 修正，导致素材自身节奏失真。这些组已经排除。正式数据使用 FFmpeg 7 从下载的 fragmented MP4 直接 `-c copy` 重封装，帧数、时长及时间戳复核通过，没有重编码视频。证据包不包含无效组。

## 如何对应同一帧

1. 手机解码后逐帧记录 `SOURCE_FRAME`，包含画面上的帧号、源 PTS、输入解码器时间、解码完成时间、向 Surface 释放时间，以及 Surface 时间戳。
2. GL 日志记录输入视频 Surface 时间戳 `video` 和输出编码 PTS；`ENCODE` 记录相同输出 PTS 的编码完成时间，`SEND` 记录发送开始、结束。
3. 以 PTS 精确连接上述日志，结果见各组 `frames.csv`。30fps 取样对应 60fps 素材中的隔帧画面；不会强行把帧号当作网络帧序号。
4. 接收端 IJK 记录 `READ / DECODE / DISPLAY`。READ 位于 `av_read_frame` 之后、入视频包队列之前；DISPLAY 是提交 native 显示，不是物理屏幕发光时间。
5. 根据发送端 90kHz RTP 时间戳和接收端 PTS 的固定偏移匹配同一帧。稳定三组分别匹配 598、579、2096 帧验证该偏移；只对匹配成功的帧统计。TCP 重连产生新的偏移，需要分段，不能把不同会话当作同一时间轴。
6. 两端通过 ADB 转发访问 debug-only 单调时钟接口，采集前后各 12 次校时，取最小往返时间样本。原始校时数据完整保留。
7. 电视每 2 秒抓一次原始 RGBA 屏幕，再在电脑转 PNG；用设备 `/proc/uptime` 包围截图调用。截图上的数字经过人工复核，损坏或不可辨认的数字排除，未按猜测填补。

每组约 40 秒，逐帧阶段统计取稳态约 8–32 秒；截图 12 次。截图帧龄以手机向 Surface 释放该源帧为起点，并给出上下界，通常约 ±60–75ms。截图不是高速摄像，无法测量面板扫描和像素响应时间；暂停期间同一旧帧的“帧龄”会继续增长。

解码输入至输出包含测试播放器的提前喂帧和定时播放等待，不能当作纯硬件解码耗时。编码统计是 Surface 提交到相同 PTS 压缩帧输出的完整耗时，也不等于编码芯片内部的独占执行时间。

## 最终四组复测

下表均为毫秒。中位值只表示分布，不能把不同帧的中位值相加充当端到端结果。

| 输入 / 投送设置 | 编码中位 / P95 | 发送调用最大 | 发送开始→电视 READ 中位 | READ→DECODE 中位 / P95 | DECODE→DISPLAY 中位 |
| --- | ---: | ---: | ---: | ---: | ---: |
| TS，30fps TCP | 21 / 25 | 1588 | 580¹ | 38 / 129 | 1.5 |
| fMP4，30fps TCP | 22 / 26 | 850 | 551 | 29 / 102 | 1.4 |
| HEVC 输入，30fps TCP | 22 / 26 | 833 | 451 | 22 / 75 | 1.1 |
| TS，60fps UDP | 24 / 29 | 8 | 565 | 125 / 146 | 1.2 |

¹ TS TCP 期间发生重连，匹配仅覆盖成功交付的帧；断流中没有交付的时间由截图补充观察，不包含在逐帧交付分布内。

三个 30fps 组实际编码输出约 **29.7fps**；UDP 60fps 组约 **58.7fps**。TCP 部分帧在后续队列被丢弃，不能把完成发送的约 16–18fps 错算成编码器只能达到该帧率。

| 最终组 | 可辨认截图数 | 截图帧龄中位 | 截图帧龄最大 |
| --- | ---: | ---: | ---: |
| TS 30fps TCP | 12/12 | 约 760ms | 约 5050ms |
| fMP4 30fps TCP | 12/12 | 约 440ms | 约 1420ms |
| HEVC 输入 30fps TCP | 12/12 | 约 460ms | 约 800ms |
| TS 60fps UDP | 7/12 | 约 790ms | 约 2300ms |

UDP 组另有 5 张截图的数字区域损坏，排除精确帧龄计算。排除坏帧会产生选择偏差，不能据剩余 7 张证明 UDP 更稳定。

较早但有效的原始截图 TS 对照：30fps TCP 4Mbps 上限、8Mbps 上限、30fps UDP 8Mbps 上限、60fps TCP 4Mbps 上限、60fps UDP 4Mbps 上限的截图帧龄中位约为 530、480、630、630、730ms。不同时间的结果波动较大；不能依据单轮宣称某个码率更优。完整结果在 `summary.json`。

## 可以直接核查的具体帧

最终 fMP4 TCP 组：

- **000947**：编码 23.0ms，发送调用 **636.4ms**，发送开始至电视 READ **679.4ms**。这是直接记录到的发送阻塞，不是推算的编码延迟。
- **001067**：编码 24.6ms，发送调用仅 0.878ms，但发送开始至电视 READ **629.9ms**，源帧释放到 DISPLAY **736.8ms**。发送函数返回不代表帧已经到达电视。
- **001393**：源帧释放到 DISPLAY 约 107ms，截图帧龄约 119ms。同一设置下也存在低延迟帧，因此不是所有帧都被固定缓存相同的 500ms。

最终 TS TCP 组：20 秒和 22 秒采样仍显示 **001065**，对应帧龄分别为 **2995–3137ms** 和 **4995–5107ms**；会话状态中的 `slowWriteDisconnects` 从 1 增至 2。这里同时存在发送阻塞后的断开、重连和旧帧保持，不能把 5 秒全部归于单帧解码。

无截图窗口仍记录到 TCP 发送调用最长约 **1.60s**，而 UDP 最终组无截图窗口最长约 **12.5ms**。截图会干扰低性能设备，但本次 TCP 发送阻塞并非仅在截图时出现。

## 定位结论与下一步

已经证实的链路问题：

1. TCP 发送调用存在长尾阻塞，造成后续帧无法及时交付；应用内队列看起来很短也不代表整个传输路径没有旧数据。
2. 发送调用快速返回的帧仍可能在电视 READ 前积压数百毫秒。这里包含发送端 socket、Wi-Fi 传输、接收 socket、RTP 拼包及 FFmpeg 解复用，**不能把该数值叫作纯网络传播延迟**。本次没有包级抓取，尚不能进一步判定 Wi-Fi 重传与接收侧读包等待各占多少。
3. 发送超时触发重连会扩大为数秒的可见停帧，单独看已成功输出的 decode/encode 均值会漏掉这个问题。
4. UDP 60fps 减少发送阻塞，但接收端 READ→DECODE 中位升至约 125ms，并出现明显损坏帧。保持 60fps 并不自动意味着更低延迟或更完整画面。

针对性优化应先做：包级到达时间和发送 socket 积压的补充观测；检查大帧发送节奏与拥塞后的新鲜帧恢复；减少重连期间等待新关键帧的时间。不能直接把接收缓冲全部设为 0，也不能丢弃任意压缩参考帧，否则可能换来花屏。

本次新增的是测量入口及逐帧关联日志，**没有依据这批数据直接修改正式版传输策略，也没有宣称卡顿已经修复**。结果限定于上述设备、网络和无声音视频链路，不能据此否定其他手机上确实存在的编码瓶颈。

## 代码、证据与复现

- `app/src/debug/java/xiao/bu/tv/CastLatencyProbeReceiver.java`：同包名、仅 debug 存在、要求 shell DUMP 权限的测试入口；本地时钟仅绑定回环地址。
- `app/src/main/java/xiao/bu/tv/CastGlCompositor.java`：增加源视频 Surface 时间戳日志，仅 DEBUG 与 CAST_LATENCY_TRACE 同时开启时输出。
- `output/apple-latency-20260909/evidence.zip`：九组有效原始截图测试的日志、PNG、校时及状态、逐帧 CSV、接收端匹配结果、人工帧号表和分析脚本。脚本中的设备地址是本次环境，复用前需调整。
- `output/apple-latency-20260909/contact-native.png`：最终四组的帧号区域总览。

构建测试包：

```powershell
.\gradlew.bat :app:assembleDebug -PcastLatencyTrace=true --console=plain
```

同包覆盖安装相应 ABI 的 debug APK，打开双方 MainActivity，把素材推入发送端应用外部文件目录，然后：

```powershell
adb -s ff0316c7 shell am broadcast -n xiao.bu.tv/.CastLatencyProbeReceiver --es mode decode --es path /sdcard/Android/data/xiao.bu.tv/files/ntv-apple-latency/ts.mp4 --ei fps 30 --ei bitrate 4000000 --es codec h264 --es transport tcp
adb -s 8ba8ceb8 shell am broadcast -n xiao.bu.tv/.CastLatencyProbeReceiver --es mode receive --es url rtsp://192.168.0.114:8554/cast --es transport tcp
adb -s ff0316c7 shell am broadcast -n xiao.bu.tv/.CastLatencyProbeReceiver --es mode stop
```

Android 4.4 的 logcat 不支持 `-T`，接收端必须抓完整过滤日志，再按校时区间排除历史记录。不要用旧工具重新封装 fMP4/HEVC 素材。

测试结束后，两台设备已原包名覆盖恢复正式版，确认未投屏、频道及投屏配置与测试前一致。未清除用户数据。
