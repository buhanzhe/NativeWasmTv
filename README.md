# nTv

面向 Android 电视、电视盒子、平板和手机的横屏直播播放器，支持 Android 4.0 及以上系统。

[English](README.en.md) · [GitHub 下载](https://github.com/buhanzhe/NativeWasmTv/releases) · [夸克网盘](https://pan.quark.cn/s/a0395f56abae)

## 功能

- IJK 播放、多线路切换、清晰度选择与硬件/软件解码。
- 遥控器、触摸操作、网页管理和手机飞鼠。
- M3U、M3U8、TXT 频道源，节目单与 IPTV 回看。
- 系统 WebView、网页嗅探和用户脚本；bro 与全量包另含轻量内核。
- 接收投屏、主题切换、开机启动与在线更新。

## 安装

从下载页选择对应架构的 APK，覆盖安装可保留频道和设置。

普通包提供 ARM32、ARM64、x86 版本；轻量内核版提供 `ntv_bro.apk`（ARM32）和 `ntv_bro64.apk`（ARM64）。`ntv_full.apk` 包含上述三种架构及轻量内核。所有发布包均不含 H5E 库。ARM64 包需要 Android 5.0 及以上系统。

## 文档

- [使用指南](docs/user-guide.md)：遥控器、触摸、网页管理、频道密码、回看与常见问题。
- [构建与内核说明](docs/building.md)：源码构建、安装包区别、签名、升级清单与站点脚本。
- [投屏接收端说明](docs/receiver-only.md) · [CJS 插件开发](docs/cjs-plugin.md)。

普通三包可独立构建，不需要私有 `browser-core`；Windows 可运行 `build-release.bat`。bro 与全量包缺少授权内核或签名不匹配时自动跳过，详见构建文档。

## 交流与许可

[内测QQ群：1121401707](https://qm.qq.com/q/abYSrBCq52) · [Linux.do](https://linux.do/u/buhanzhe) · [恩山论坛](https://www.right.com.cn/forum/thread-8484303-1-1.html)

Copyright (C) 2026 buhanzhe and contributors. 原创代码采用 [GPLv3](LICENSE)（`GPL-3.0-only`）；再分发的版权、许可和完整对应源码义务见 [版权与使用说明](COPYRIGHT.md)。请仅播放有权访问的内容。
