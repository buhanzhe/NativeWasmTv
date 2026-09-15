# M3U 外挂字幕

频道支持 HTTP/HTTPS 的 SRT、WebVTT 字幕，复用播放器现有字幕轨道、开关、字号、位置和阴影设置。字幕按播放器时间同步，暂停和快进、快退不需要重新打开视频。

```m3u
#EXTM3U
#EXTINF:-1 group-title="电影",示例影片
#EXTVLCOPT:sub-file=https://example.com/movie.zh.srt
#EXTVLCOPT:sub-file=https://example.com/movie.en.vtt
https://example.com/movie.mp4
```

同一频道可重复填写 `#EXTVLCOPT:sub-file=`，最多保留 16 个不同字幕地址。也接受 `#EXTINF` 上的 `subtitles="https://example.com/movie.srt"` 或 `subtitle="..."` 属性。

网络 M3U 的相对字幕路径按频道表网址解析；上传本地 M3U 时，字幕请填写完整 HTTP/HTTPS 地址。字幕文件使用 UTF-8，亦支持带 BOM 的 UTF-16；单个字幕文档不超过 2 MB。

在媒体控制器的“媒体设置 → 字幕轨道”中选择外挂字幕，继续使用原有“显示字幕”开关及样式设置。字幕信息会随频道合并、收藏、手机与电视同步和本地缓存保存；字幕下载失败不会中断媒体播放。
