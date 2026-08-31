# Third-party notices

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
