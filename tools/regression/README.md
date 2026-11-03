# 真机标准流程

当前版本的统一入口、快速回归与上线回归说明见 [STANDARD_FLOW.md](STANDARD_FLOW.md)。

# Android 9 与 1.7.0 自动回归

基线是 `1b747a4003e933d56e11c028039011290134b8fb`，其源码标注版本 1.7.0。
`v1.7.0` 标签 `2645848` 实际标注 1.6.0，不能直接作为本次版本基线。
当前版从 HEAD 导出，并覆盖工作区已修改的跟踪文件；两版均安装为独立包，
不会清除现有 `xiao.bu.tv` 的数据。

环境：Windows、Python、ADB、Android SDK `D:/android/sdk`、Java 8。
优先使用仓库 `.codex-tmp/jdk8/runtime/jdk8u504-b01`；设备固定为
`127.0.0.1:21503`，脚本确认 API 28 后才运行。结果保存在
`.codex-tmp/android9-v17`，失败用例也保留在 `results.jsonl`。

首次准备：

```powershell
python tools/regression/android9_versions.py prepare baseline
python tools/regression/android9_versions.py prepare current
python tools/regression/android9_media.py generate
python tools/regression/android9_media.py serve --port 39981
```

服务器在独立终端运行，接着在另一终端依次执行：

```powershell
adb -s 127.0.0.1:21503 reverse tcp:29981 tcp:39981
python tools/regression/android9_versions.py build baseline --abi arm32
python tools/regression/android9_versions.py build current --abi arm32
python tools/regression/android9_versions.py batch baseline --plan tools/regression/android9_local_plan.json
python tools/regression/android9_versions.py batch current --plan tools/regression/android9_local_plan.json
python tools/regression/android9_versions.py batch baseline --plan tools/regression/android9_playback_plan.json
python tools/regression/android9_versions.py batch current --plan tools/regression/android9_playback_plan.json
```

构建命令必须结束后才启动测试。`--abi x86` 可验证模拟器原生 ABI；ARM32 则
验证定制 IJK 库，在本次模拟器上由 ARM 翻译层运行。
已有快照不会被 prepare 覆盖。需要更新共享测试适配时运行
`python tools/regression/android9_adapt_fixtures.py`，生产代码改动应重新导出快照。

单例运行：

```powershell
python tools/regression/android9_versions.py suite current --suite VersionBehavior
python tools/regression/android9_versions.py suite current --suite VersionBehavior --arguments '{"decodeMode":"software"}'
python tools/regression/android9_versions.py suite current --suite HdrColor
```

suite/batch 有失败时返回非零退出码；基线不存在的新增套件显示 NOT PRESENT。
每套测试启动前停止两个隔离包，不能与其他设备测试同时运行。
媒体服务器支持 Range，生成 20 秒 H.264/AAC MP4、AAC HLS 和 30 秒 MP3。
实际播放检查新频道准备、首帧、播放时钟、暂停、跳转、恢复和屏幕像素，
当前版还检查浮层的亮度、音量、进度及取消/直播保护。

## 全设备央视频 / 央视网两轮回归

`all_device_providers.py` 对 ADB 当前连接的设备运行独立的
`xiao.bu.tv.providerregression` 包，分别选 CCTV-1、2、5、8、13。
准备插件后才计时，实际发送遥控 OK，并确认关闭列表的绘制先于频道加载；
每台还有一次解密锁忙时的 UI 响应检查。运行期间会停止原播放器进程，
保留原播放器数据；安装独立包不会覆盖原播放器。

```powershell
python tools/regression/all_device_providers.py build --build-id candidate
python tools/regression/all_device_providers.py run --round 3 --build-id candidate --only 8ba8ceb8 ff0316c7 127.0.0.1:21503
python tools/regression/all_device_providers.py run --round 4 --build-id candidate --only 8ba8ceb8 ff0316c7 127.0.0.1:21503
python tools/regression/provider_live_report.py
```

奇数轮清隔离包 URL 缓存，偶数轮保留并反向选台，每例默认观察 30 秒。
Mi 6 使用 ARM64，其余设备 ARM32；Android 9 模拟器 ARM32 通过翻译层运行。
构建快照与已有轮次不会覆盖。失败、未完成、UI 预检或 instrumentation 结果
失败均返回非零，重试需要保留原记录。输出位于 `.codex-tmp/provider-live-rounds`。
最终报告核验档案第 3、4 轮的 3 台设备、60 次正式用例，另列诊断重播，
不能用重播覆盖正式失败。档案第 1、2 轮的修复前 80 次记录保留。
KT107 按用户要求停止适配，使用 --only 仅选择继续测试的三台设备；
它的已有数据保留并在报告中注明。重用工具时应按实际设备调整 --only。

首帧为 OK 至首帧事件；CDN 边缘延迟只有当前分片仍在源列表窗口时才估算，
不是广播至屏幕的端到端延迟。输出计数用于发现疑似冻结，单张非黑截图只能
证明有画面。时钟停顿断言使用相邻采样，HLS 窗口回跳后从新位置继续累计
前进量；重建播放器后重新建立基准，避免旧位置影响恢复后的判断。
旧档案采用历史最大位置，仍需结合原始采样判断，不能仅凭旧断言认定持续停播。
专用诊断可传 `allowRecovery=true` 观察有限重试后的持续输出，或传
`detachVideoAfterMs=3000` 注入视频输出丢失；这些参数只用于独立测试包。
正式两轮不允许恢复掩盖连续播放失败。`video-output-recovery.test.py`
验证普通直播的输出监测及暂停、缓冲、纯音频、点播、强制硬解保护条件。

## 解码异常调查与硬解重建

出现过正常画面的硬解播放，不因一次输出停顿直接回退 IJK 软解。
重建当前后端；恢复期间仍无首帧继续有限重试。解码恢复耗尽后保留频道，
停止播放并提示手动重试，避免把解码器异常当成频道失效而跳台。
只有 MEDIA_ERROR_SERVER_DIED 明确信号显示“系统解码器崩溃”；
单独停帧显示“系统解码器异常，正在恢复硬解”。

```powershell
python tools/regression/all_device_providers.py build --build-id hw-audit --trace-native
python tools/regression/decoder_recovery_audit.py --build-id hw-audit --name hw-audit-natural --channels 3 --observe 120
python tools/regression/decoder_recovery_audit.py --build-id hw-audit --name hw-audit-fault --channels 1 --observe 60 --detach 12000
python tools/regression/decoder_recovery_audit.py --build-id hw-audit --name hw-audit-policy --channels 1 --check-exhaustion
```

--trace-native 只影响诊断 DEBUG 包，记录 READ / DECODE / DISPLAY 时间戳。
DISPLAY 表示调用呈现路径，不代表提交成功，必须结合输出计数和视频截图。
IJK 解码 fps 是缓存的上次采样，不能用它证明停帧后解码线程仍在运行。
若设备 shell 原本有 root 权限，自然异常检查可用非终止的 debuggerd -b
抓取应用及媒体服务栈，不提升设备权限、不杀媒体服务。故障注入会断开
播放器输出，不做恢复前的截图断言，因为 Surface 可能没有可复制的数据。
--serial 可选 Mi 6（ARM64）或 Android 9 模拟器（ARM32），明确拒绝 KT107。
输出目录不覆盖旧记录；最新说明见
`.codex-tmp/provider-live-rounds/decoder-recovery-investigation.html`。
正式回归和诊断共用结果校验：用例数量、重复频道、UI 预检、ADB 退出码、
COMPLETE 汇总及 instrumentation 结果必须一起通过；诊断写入 validation.json。
`python tests/provider-regression.test.py` 覆盖漏跑、假成功、时钟回跳与恢复基准。

`RemoteMediaRouting` 引用两版都已删除的接收字段；旧 `AudioSwitch` 在当前版
引用已由唱片 UI 插件替代的内部 View。它们的首次失败记录保留，但不列入有效
功能断言。唱片 UI 插件改用 `RadioUiInstrumentation` 的 DEX 热替换与离线恢复测试。
