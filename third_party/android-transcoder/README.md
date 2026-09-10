# android-transcoder 内置转码核心

来源：https://github.com/ypresto/android-transcoder

基线提交：`41068f51b26a28557bcaf55c3e3112f1d9f19d46`，Apache-2.0，许可证见 LICENSE。

本项目使用 Java MediaExtractor / MediaCodec / EGL 路径，不增加 FFmpeg 编码动态库。发送端仅在 Android 4.3（API 18）及以上启用；接收端无需使用本转码核心。

本地修改：

- QueuedMuxer 增加流式输出回调，直接交给有界 RTSP 发送队列。
- VideoTrackTranscoder 按目标帧率选择输入帧，支持把旋转元数据烘焙进画面。
- AudioChannel 改为逐步消费 PCM16 解码缓冲，按采样数计算时间戳，避免复制溢出缓冲及错误填充。
- AudioTrackTranscoder 严格保留有效 PCM 的 offset / size，释放空帧，正确投递 EOS。
- 移除未使用的生成 BuildConfig 引用。

未进行采样率转换或 HDR 到 SDR 色调映射；这些限制由调用层明确处理。原音频发送绕过 AudioTrackTranscoder。
