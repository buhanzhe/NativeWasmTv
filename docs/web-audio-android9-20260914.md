# 江苏、新疆网页直播声音兼容

2026-09-14，ADB `127.0.0.1:21503`，Android 9 NX809J 虚拟机，系统 WebView 为 Chrome 68.0.3440.70。

使用设备已有频道配置确认网址：

- 江苏卫视：`https://live.jstv.com/?channelId=670`
- 新疆卫视：`https://www.xjtvs.com.cn/column/tv/434?channelId=1`

自动嗅探后，原生 IJK 播放两台均存在已选中的 AAC 48000Hz 音轨。网页单独播放使用 WebAudioInstrumentation 保留现有配置、暂停原生播放器后打开相同网址，读取实际 DOM 播放及音频解码状态。

## 修复与验证

江苏官网播放器配置包含 `volume:0`。修复前 `muted=false`，音量 0，但已有音频解码；站点限定的首次播放处理恢复为播放器默认音量 0.6。修复后自动播放约 10 秒，音频解码计数 88144 字节。只修正未经用户操作的首次零音量播放；不覆盖主动静音、后续音量调整或多媒体投送静音。

新疆 DPlayer 优先选择 Android 原生 HLS。修复前音量 0.7、播放中，音频解码计数为 0；同一设备同一流改用页面自带 HLS.js 后音频计数增长。兼容规则仅在该站点电视页面且 HLS.js/MSE 可用时引导 DPlayer 使用 HLS.js，仍由 DPlayer 管理生命周期；不可用时保留原路径。修复后自动选择 blob/MSE 播放，音量 0.7，音频解码计数 86097 字节。

两站都验证了手动设置静音并暂停/恢复播放后，仍保持用户静音。Node 检查覆盖站点范围、重复注入、首播、手动输入、多媒体暂停以及 MSE 不支持时回退。ARM32 debug/release 构建通过。

这里验证的是实际设备 WebView 播放、音量和解码状态，未对扬声器输出做声学录音，也未逐一测试两省的所有频道。日志在 `.codex-tmp/web-audio-before.log`、`web-audio-probe.log`、`web-audio-after.log`。

完成后安装修复后的 release 包，恢复原选中频道；频道源和自动嗅探设置保持原值。

## 后续：通用零音量恢复

按用户要求，首次播放的零音量恢复取消江苏域名限制，适用于所有网页中的 video/audio 元素。默认恢复为 0.6，保留非零音量、主动静音、用户交互后的音量设置，以及多媒体投送暂停状态；同一媒体元素只检查一次。新疆 HLS.js 兼容仍限定在新疆电视页面。

通用脚本检查覆盖其他域名的视频与音频、注入时已经播放的媒体、重复注入，以及上述保留条件。


## 江苏频道自动嗅探路径补测

此前测试禁用了嗅探回调，只验证 WebView 自身音量和解码，遗漏了真实选台后的 IJK 切换。
此次保持正常回调和自动嗅探设置，在本地 Android 9 / Chrome 68 上逐个选择江苏卫视、城市、综艺、影视、新闻、教育、体育休闲、国际（channelId 670/669/663/664/668/666/665/671），每路观察 12 秒。

修复前 8/8：IJK prepared=true，但 mutedByAudioFocus=true；隐藏网页仍可能调用 play()，WebView AudioFocusDelegate 抢占声音焦点。单独修正 DOM volume 不能解决原生播放器静音。

修复：隐藏网页播放嗅探资源时复用媒体暂停保护，阻止延迟 play()；文档和首批脚本加载时补装保护；返回时恢复原有 play 方法、静音和播放意图。暂停期间不消耗首次零音量修正。用户主动返回网页后，本次频道会话不再自动播放新发现的资源，仍保留手动资源选择。

修复后 8/8：IJK prepared=true、mutedByAudioFocus=false、mutedByCallMode=false，隐藏网页 paused=true、muted=true。返回江苏卫视保持同一 WebView，6 秒后 volume=0.6、muted=false、paused=false、currentTime=5.827、音频解码 53522 bytes。

日志：`.codex-tmp/jiangsu-flow-before.log`、`jiangsu-flow-after.log`、`jiangsu-return-after.log`。这是软件播放链路验证，未通过麦克风录制扬声器输出。
回归：`node tests/media-pause.test.js`、`node tests/web-audio.test.js`、`node tests/control-pages.test.js`。
