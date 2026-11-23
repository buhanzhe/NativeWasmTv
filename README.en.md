# nTv

A landscape live-TV player for Android TVs, boxes, tablets and phones, supporting Android 4.0 and later.

[简体中文](README.md) · [GitHub Releases](https://github.com/buhanzhe/NativeWasmTv/releases) · [Quark download](https://pan.quark.cn/s/a0395f56abae)

## Features

- IJK playback, multiple sources, quality selection and hardware/software decoding.
- Remote control, touch gestures, web management and phone air mouse.
- M3U/M3U8/TXT playlists, programme guides and IPTV catch-up.
- System WebView, media sniffing and user scripts; browser editions also include the lightweight engine.
- Cast reception, themes, auto start and online updates.

## Install

Download the APK for your device. An in-place upgrade preserves channels and settings.

Standard editions cover ARM32, ARM64 and x86. `ntv_bro.apk` is the ARM32 browser edition; `ntv_bro64.apk` is ARM64. `ntv_full.apk` includes all three supported architectures and the lightweight browser. All release editions exclude H5E. ARM64 requires Android 5.0 or later.

## Documentation

- [User guide](docs/user-guide.en.md): installation, remote controls, web management and air mouse.
- [Build guide](docs/building.en.md): dependencies, editions, signing, update manifests and site scripts.
- [完整中文指南](docs/user-guide.md) · [Receiver scope](docs/receiver-only.md) · [CJS plugins](docs/cjs-plugin.md).

Public standard editions build without `browser-core`; Windows users can run `build-release.bat`. Browser/full editions are skipped when the authorized private core or matching signing certificate is unavailable.

Plugin Management selects downloads for the receiver's Android version and CPU architecture, with local JAR uploads and a separate Python runtime. [nTv plugin releases](https://github.com/buhanzhe/ntv-plugin/releases/latest)

## License

Copyright (C) 2026 buhanzhe and contributors. Original code is licensed under [GPL-3.0-only](LICENSE). Redistribution requires applicable notices and complete corresponding source; see [copyright and use notes](COPYRIGHT.md). Only play content you are authorized to access.
