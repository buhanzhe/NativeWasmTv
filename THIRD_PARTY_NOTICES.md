# Third-party notices

Original nTv project code: Copyright (C) 2026 buhanzhe and contributors;
GNU GPL version 3 only (`GPL-3.0-only`). See `LICENSE` and `COPYRIGHT.md`.
Third-party works below retain their own copyright and license terms.

## Tencent Bugly SDK

The app bundles `com.tencent.bugly:crashreport:4.1.9.3`, including its native crash
reporting library. Its published Maven metadata identifies **The Bugly Software
License, Version 1.0**, at http://bugly.qq.com/licenses/LICENSE-1.0.txt.
Source and SDK information: https://github.com/BuglyDevTeam/Bugly-Android

This is a separately licensed SDK; the project's GPL notice does not relicense it.
Distributors must verify the applicable SDK terms and compatibility of the actual
combined distribution before claiming that an APK satisfies GPL requirements.

## AndroidX WebKit and Core

The app uses AndroidX WebKit `1.9.0`. For its Core dependency, the build keeps
the original `Pair` and `ObjectsCompat` class files from AndroidX Core `1.1.0`;
unrelated Core components, resources and transitive dependencies are not bundled.
No upstream class bytecode is modified.

License: Apache License 2.0. See `third_party/material-design-icons.LICENSE`
for the license text. Source: https://android.googlesource.com/platform/frameworks/support/

## cctv-h5e-decrypt

The generated `app/src/main/jni/generated/cctv_h5e_wasm.c` and
`cctv_h5e_wasm.h` files are derived from the embedded wasm in
[xiaoxi-ij478/cctv-h5e-decrypt](https://github.com/xiaoxi-ij478/cctv-h5e-decrypt)
at commit `f25831e372970a15617af36e83af004b0d1a630d`.

License: MIT. See `third_party/cctv-h5e-decrypt.LICENSE`.

## WABT wasm2c runtime

Files under `app/src/main/jni/wasm-rt` come from
[WebAssembly/wabt](https://github.com/WebAssembly/wabt) release `1.0.39`.

License: Apache License 2.0. See `third_party/WABT.LICENSE`.

## ijkplayer and FFmpeg

The Android app uses [bilibili/ijkplayer](https://github.com/bilibili/ijkplayer)
`0.8.8`. Its Android native libraries are rebuilt from the corresponding
upstream sources with the small feature profile in `tools/ijk/module-ntv.sh`.
The profile adds RTSP over TCP/UDP and the MP2 audio decoder to the upstream
lite configuration. The Java API continues to use the published 0.8.8 AAR.

License: LGPL-2.1. See the upstream project for the complete notice.

## Google Material Icons

The media controller embeds rounded SVG paths for previous, play, pause, next,
favorite, settings, and download icons from
[google/material-design-icons](https://github.com/google/material-design-icons).

License: Apache License 2.0. See `third_party/material-design-icons.LICENSE`.

## CryptoJS

The Ku9 script compatibility runtime embeds [CryptoJS](https://github.com/brix/crypto-js)
`3.1.9-1` for the standard `require("crypto")` module. This ES5 release remains
compatible with the JavaScript engine shipped on Android 5.0.

License: MIT. See `third_party/crypto-js.LICENSE`.

## JSEncrypt

The Ku9 script compatibility runtime embeds [JSEncrypt](https://github.com/travist/jsencrypt)
`2.3.1` for the standard `require("jsencrypt")` module. This release keeps
compatibility with the JavaScript engine shipped on Android 5.0.

License: MIT. See `third_party/jsencrypt.LICENSE`.

## Vinyl record artwork

`app/src/main/res/drawable-nodpi/vinyl_record.png` was redrawn with OpenAI
image generation (2026-09-16): a transparent 653 × 653 black vinyl record
with antialiased edges and a red center label. The visual reference was
**45 rpm record** by Paul Sherman / WP ClipArt, published in the public domain.
Source: https://commons.wikimedia.org/wiki/File:45_rpm_record.png
Original: https://upload.wikimedia.org/wikipedia/commons/8/88/45_rpm_record.png
The MP3 album cover is drawn over the center at runtime.

## anti-AD

When web ad blocking is enabled, the app downloads the AdGuard-compatible
domain rules from the anti-AD official service at https://anti-ad.net/adguard.txt.
The rules are stored in the app data directory and are not bundled in the APK.

License: MIT. Source: https://github.com/privacy-protection-tools/anti-AD
