# 接管页面与断线恢复验证

## 修改

- 接管成功后复用手机当前的 ManagementActivity，进入飞鼠并清空旧 WebView 历史；没有管理页时才新建。普通浏览器用 `location.replace` 跳转。成功回包和权限授权后的异步完成都覆盖。
- 网页或系统返回不再等同于结束接管。电视接受接管时收起二维码，并关闭本机管理 Activity。
- 手机用单调时钟和最后一次有效会话 ACK 维持 3 秒租约。检测独立于 Socket 重连线程，连接、握手或写入阻塞时也能关闭 Socket 并退出。
- 失联恢复手机接管前的频道、线路或网页，关闭飞鼠和管理页，释放投屏、声音捕获与 Direct 资源。
- 电视重启不恢复过期租约，只恢复自己的播放记录。手机在网络入口和 UI 执行入口都检查接收端身份与当前接管状态，拒绝断线后的迟到播放请求。
- 管理 WebView 先暂停并从父布局移除，再销毁，避免窗口继续绘制已销毁的 WebView。

## 实机

直接覆盖原包名 `xiao.bu.tv`，没有使用改包名的主应用。

| 角色 | 设备 | 系统 | 地址 |
| --- | --- | --- | --- |
| 手机 | MI 6，ff0316c7 | Android 7.1.1 / API 25，ARM64 | 192.168.0.114 |
| 电视 | SM-G3608，8ba8ceb8 | Android 4.4.4 / API 19，ARM32 | 192.168.0.113 |

通过应用原有 HTTP 接口与 ADB 检查；独立 instrumentation APK 被 MIUI 拒绝安装，未使用它作为验证依据。

1. 手机网页 A 实际编码为 1280×720、25fps，电视截图确认收到带动态色块和时间戳的画面。未把更高帧率作为本次修改或验证目标。
2. 手机成功进入飞鼠，Activity 栈仅保留 MainActivity 和一个 ManagementActivity。点击飞鼠网页返回后显示管理首页，接管继续。
3. 电视先显示二维码，接管后二维码消失；另一次先打开电视 ManagementActivity，接管后其 Activity 栈只剩 MainActivity。
4. 手机先播放网页 A，接管期间切换网页 B；通过 ADB 强制停止电视应用模拟突然断线。最终复测日志 `silentMs=3000`，约 120ms 后恢复 A 的选择。PC 从发送停止命令开始轮询，3354ms 时观察到接管地址清空、编码停止、A 的 WebView 已打开。后续截图确认 A 正常动态显示，飞鼠窗口消失。
5. 同样测试原生广西频道，日志 `silentMs=3001`；恢复频道选择约 59ms，重新拉流后的首帧约 1.7 秒。PC 轮询为 3648ms，包含 ADB 停止命令执行、最后一次在途心跳与轮询开销，不能将其当作纯心跳超时。
6. 电视重新启动后显示自己的吉林都市，手机保持本地播放。模拟迟到的 receiver play 请求返回“接管会话已结束，请重新接管电视”，没有重新进入接管。

本次模拟的是电视进程突然终止，未实际给设备断电，也未完成 Wi-Fi 黑洞测试。3 秒是失联判定时限，不包含网页加载或频道重新缓冲所需时间。

测试早期记录到 MI 6 的 `Chrome_InProcGp / libgsl.so gsl_syncobj_destroy` 原生崩溃。修正管理页复用和 WebView 销毁顺序后，完成了网页恢复、后续原生频道投屏与恢复复测，没有新增该崩溃；这不是对所有系统 WebView/GPU 组合的稳定性保证。

## 检查与证据

- `node tests/control-pages.test.js`：12 项通过，含成功替换页面、权限延迟完成、失败/退出不误跳转、状态刷新恢复。
- ARM32、ARM64 debug 与签名混淆 release 构建。
- 本地证据：`.codex-tmp/cast-study/lifecycle-disconnect.json`、`lifecycle-native-disconnect.json`、`phone-final-flymouse-window.xml`、`phone-back-window.xml`、`lifecycle-tv-casting.png`、`lifecycle-phone-restored-stable.png`。
- 测试结束恢复原频道源列表、网页嗅探设置和电视飞鼠设置；临时频道没有进入交付配置。
