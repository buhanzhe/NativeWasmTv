# 投屏 TCP / UDP 选择

“投屏与互联”增加独立的传输协议设置。缺省为 TCP，UDP 由用户主动选择；与 H.264 / H.265 编码选择独立。设置保存后于下次开始投屏或新建频道投屏会话生效。当前实际传输协议显示在投屏状态文字中。

## 协议与兼容

- 手机持久化键 `web_cast_transport`，管理接口字段 `settings.webCastTransport`，仅接受 `tcp` / `udp`。
- `CastConfig.transport` 随编码器回退、帧率回退和自适应分辨率变化保留，不在降档时重置。
- 手机 `/api/playback` 的 `remotePlayback` 在 `sourceMode=cast` 时增加 `castTransport`。电视解析到本次播放结果，再传给 IJK 的 `rtsp_transport`。
- 新版电视遇到没有字段的旧手机时使用 TCP。旧版电视可能继续执行原有的强制 UDP 逻辑，因此手机和电视均需更新。
- 移除 Android 7.1 及以下/低性能接收端强制 UDP 的覆盖规则。普通 RTSP 频道继续采用原有高级设置，不受投屏选择影响。
- H.265 原先并没有单独强制 UDP；旧接收端版本和性能判定是覆盖来源。

TCP 可通过重传避免网络丢包直接破坏码流，但网络拥塞时可能增加等待。UDP 仍作为用户可选项。编码器、解码器或像素格式问题并不会因切换 TCP 自动消失，本次未宣称解决所有 HEVC 花屏。

## 验证

- 同包名 xiao.bu.tv，MI 6 / Android 7.1.1 为发送端，SM-G3608 / Android 4.4.4 为接收端。
- 默认 TCP：接收端日志 `Cast RTSP transport=tcp`，发送端状态 `requestedTransport=tcp`、`rtspTransport=tcp`，电视截图可见测试画面。
- 主动选择 UDP 后重建会话：接收端日志 `Cast RTSP transport=udp`，发送端请求和实际协议均为 udp，30 秒内画面编码持续增长。
- 13 个网页控制回归通过，含 H.265 不隐式选择 UDP、保存 UDP 后状态回显。
- JVM 配置/自适应回归通过，含 TCP 默认值及 H.265→H.264 回退和降分辨率保留 UDP。
- 签名 ARM32/ARM64 release 构建通过。输出目录 `output/release-20260909-transport`。
- HEVC 接收画面未在本地 4.4 设备实测，该设备正常回退 H.264。小米 6 Canvas 长测仍存在原生渲染崩溃，详见编码自适应报告，尚未解决。
