# 固定真机测试流程

入口为 `standard_regression.py`，设备与官网用例在相邻的 `standard_devices.json`。
默认仅操作指定的 Android 4.0.4、4.4、7.1 三台真机，不枚举操作其他模拟器。
脚本不安装 APK、不清数据、不切 Wi-Fi、不清 logcat，也不替换频道的官方提供方。

需要 Python 3.11+、ADB、FFmpeg、Pillow、Node.js/npm。网页用例通过 Playwright CLI
自动操作真实浏览器，首次运行需要联网获取 CLI。主机测试另需仓库所需 JDK/NDK。
开始前手动安装待测 APK，打开应用，保持三台设备联网、前台播放，并避免同时操作。
电视必须能访问电脑的测试 HTTP 端口（默认 19891）；多网卡时显式传 `--fixture-host`。

上线回归还需要同签名的 WebView 测试 APK，用于操作设备真正内置的 WebView，
不能用电脑浏览器通过来代替。先构建并手动安装到参与测试的设备：

```powershell
.\gradlew.bat :app:assembleNtvBroReleaseAndroidTest '-PtestBuildType=release' '-PtestRunner=xiao.bu.tv.BrowserKernelInstrumentation'
adb -s 设备序列号 install -r app/build/outputs/apk/androidTest/ntvBro/release/app-ntvBro-release-androidTest.apk
```

这个独立测试包不替换主应用。探针临时使用软件播放器以避开旧版系统解码器的释放锁，
结束前恢复解码设置，并在真实 WebView 内打开内核选项、选中两种内核、确认保存。
测试结束会重启主应用。缺少测试 APK 或探针失败均记失败。

从仓库目录运行：

```powershell
# 查看固定用例，不访问设备。
python tools/regression/standard_regression.py --list

# 只读预检：设备型号/API、当前 APK SHA-256、管理接口、前台状态。
python tools/regression/standard_regression.py --profile check

# 快速回归：三台并行，图片、5频道 TXT、1000频道异步导入、内核设置、文件播放与控制。
python tools/regression/standard_regression.py --profile smoke

# 上线回归：先跑仓库主机测试，再跑三台完整用例，每台额外连续播放至少15分钟。
# --apk 校验设备实际安装的 APK，与文件逐字节一致；不会替你安装。
$env:JAVA_HOME='C:\Users\Administrator\.jdks\jbr-17.0.14'
python tools/regression/standard_regression.py --profile release --apk output/ntv_bro.apk --fixture-host 192.168.0.110

# 只复测单台单项；报告会注明这不是完整上线回归。
python tools/regression/standard_regression.py --device android-7.1 --case system-private-files

# 脚本自身测试，无需设备。
python tools/regression/test_standard_regression.py
```

完整用例额外检查：系统播放器连续两轮 WAV/MP4、系统/轻量网页、404/超时跳下一个
已配置源、源全部失败后停留原频道、央视频/央视网/广东/虎牙、24次切台、节目单
HTTP503 后下次冷启动使用同一网址重试、六套主题极简模式返回退出、三轮前后台切换
恢复、15分钟长播。前后台用例检查恢复后的时钟、画面；瞬时花屏仍需人工观察。
另检查分片404/损坏后的32秒内错误反馈、20轮直播拖动/倍速明确拒绝，以及4K HEVC
在有能力的设备上确认首帧并观察画面变化、无能力设备上明确报错。HEVC用例需
FFmpeg 的 libx265 编码器；缺少工具按准备失败处理，不伪装成播放不支持。
官网用例保留真实失败，旧系统能力限制也不会自动改成通过。未打包轻量内核的包，
轻量网页项会说明不适用。`--case`、单台、缩短长播只能作为诊断，不算完整上线回归。

播放判定要求准备完成、正在播放、同源时钟前进，以及解码图像信息；观察用例同时
检查前台和多次截图变化。不能仅因有画面、计时器在走或一次 API 返回成功就通过。
人工仍需听声音、操作手机网页确认滚动和交互；本脚本不会声称已经完成这些检查。

每轮写入新的 `output/standard-regression/<时间与随机编号>/`：

- `report.txt`、`report.json`：总结果、逐项结果、漏测项、是否完成整轮上线测试。
- 每台 `before.json`、`restored.json`：测试前快照、恢复后校验。
- `package.txt`、实际安装包 SHA-256、Git HEAD、工作区状态。
- `device.log`、原始播放采样、失败截图、前台状态、内存、测试服务器请求。

失败返回非零退出码。报告目录不覆盖；一项失败后仍保留结果并继续其他项。
设备缺失、APK不符、断连、切到其他应用、漏跑或恢复失败均不会成为通过。
普通 `PASS` 只表示本次所选范围通过；`completeReleaseRun` 仅在完整三台、完整用例、
主机测试、APK匹配和至少900秒长播全部满足时为真，并不代替人工验收。

新版管理接口增加 `configuredWebSourceEngine`，用于保存真正的用户默认内核。
旧接口的 `defaultWebSourceEngine` 是出厂默认值，不能安全用于恢复；缺少新字段时，
脚本只允许只读预检，拒绝修改设备。请先安装包含该字段的候选包再跑 smoke/release。

按一次 Ctrl+C 会请求停止并执行恢复；等待报告写完。若进程被强杀、设备掉线或
电脑关机，脚本无法保证当场恢复。重新连接后使用原轮次 `before.json`：

```powershell
python tools/regression/standard_regression.py --profile check --device android-4.4 --restore-snapshot output/standard-regression/某轮/android-4.4/before.json
```

每台有独立锁，防止本脚本的两轮测试同时操作同一设备。进程被强杀后会留下锁文件，
位于 `.codex-tmp/standard-regression-locks/`，内容包含 PID 和原报告目录；确认原进程
已停止后才删除该设备的锁，再执行恢复。不要为了开始新测试而覆盖旧快照。
