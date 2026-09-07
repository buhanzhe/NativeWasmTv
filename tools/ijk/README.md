# nTv IJK / Dolby changes

## Playback policy

- H.264, HEVC and MPEG-2 prefer MediaCodec. The user's explicit software decoder setting remains authoritative. Failed ordinary hardware video decoding retains IJK's FFmpeg fallback.
- AAC keeps its existing PCM path. FFmpeg now includes AC3, EAC3, TrueHD and MLP decoders, AC3/EAC3/TrueHD/MLP demuxers and parsers, Matroska demuxing, and MPEG-2 decoding/parsing.
- AC3/EAC3: API 21+ may use compressed AudioTrack output when the connected HDMI output advertises the format/channel count. API 29+ also checks direct playback support. Headphone/Bluetooth routing, unsupported devices and unknown capabilities use FFmpeg PCM. API <21 never loads the new AudioTrack bridge.
- Atmos: EAC3/TrueHD bitstreams are not decoded or stripped on the passthrough path. TrueHD passthrough additionally requires API 23+, an advertised TrueHD output, and 16-frame batching. Software fallback plays the channel-based mix; it does **not** reproduce Atmos object rendering.
- A route change, failed/stalled write, unexpected playback-head reset, application mute/volume adjustment or non-1x speed returns the current audio stream to FFmpeg PCM. No restart of the video is required. Stop/seek discard the old stream's pending encoded bytes. Re-enabling passthrough is deferred to the next stream open.
- The HLS proxy still emits only one selected video/audio rendition group. It prefers EAC3/AC3 when direct output is available and AAC on speakers; Dolby-only playlists can use software decoding. It does not reintroduce probing of unused HLS groups.
- Dolby Vision is **not** identified by HEVC alone. MP4/fMP4 `dvcC`/`dvvC` and DV sample-entry tags, plus explicit HLS DV CODECS declarations, reach the native video pipeline. It requests `video/dolby-vision`, matching the advertised profile and level. This revision accepts single-layer profiles 5/8/9. Unsupported profiles, dual-layer content, missing metadata or unavailable DV decoders emit a clear unsupported-format error instead of falling through to ordinary HEVC with wrong colours. Matroska DV metadata and DRM/dual-layer DV are not implemented.

Audio capability APIs can describe a disconnected or inactive output on older Android releases; that is why a MIME name or `isDirectPlaybackSupported()` alone is not sufficient. See [Android TV audio capabilities](https://developer.android.com/training/tv/playback/audio-capabilities) and [AudioTrack](https://developer.android.com/reference/android/media/AudioTrack).

## Rebuilding native libraries

The app packages prebuilt libraries, so running Gradle alone does not compile these changes.

The source overlays target the existing `.codex-tmp/ijkplayer-0.8.8` checkout, its configured FFmpeg 3.4 `android/contrib/ffmpeg-arm64` and `ffmpeg-armv7a` trees, NDK r14b and the unchanged `libijksdl.so` in the app. `module-ntv.sh` is the feature profile for a fresh FFmpeg configuration. SoundTouch is the upstream `Bilibili/soundtouch` branch `ijk-r0.1.2-dev`, placed at `ijkmedia/ijksoundtouch`.

1. Run `node tools/ijk/apply-ntv-patches.cjs` from the repository root. Exact-match checks reject unexpected source versions, and running it twice is safe.
2. In Git Bash, run `bash tools/ijk/build-ffmpeg-dolby.sh arm64` and then `bash tools/ijk/build-ffmpeg-dolby.sh armv7a`. These scripts reuse the existing standalone cross-toolchain configuration. Set `NTV_SKIP_CONFIGURE=1` only for subsequent source-only rebuilds. Git Bash's `/usr/bin/awk` must precede the old Windows NDK awk.
3. Generate `ijkmedia/ijkplayer/ijkversion.h` with upstream `version.sh` if absent. Invoke `ndk-build.cmd` from the repo root with `NDK_PROJECT_PATH=. APP_BUILD_SCRIPT=tools/ijk/Android.mk NDK_APPLICATION_MK=tools/ijk/Application.mk NDK_OUT=.codex-tmp/dolby-native/obj NDK_LIBS_OUT=.codex-tmp/dolby-native/libs`, once for `APP_ABI=arm64-v8a APP_PLATFORM=android-21`, once for `APP_ABI=armeabi-v7a APP_PLATFORM=android-14`.
4. After successful linking, copy `libijkplayer.so`, `libijksdl.so` and (when FFmpeg changed) `libijkffmpeg.so` for each ABI from `.codex-tmp/dolby-native/libs` into `app/src/main/libs`. Original libraries from this change are backed up under `.codex-tmp/dolby-original-libs` locally.
5. Run the normal Gradle or release packaging script.

The IJK overlays are LGPL-2.1-or-later, like their host sources. Keep these source changes alongside the redistributed native libraries.

## Interactive cast latency

For nTv's realtime RTSP session, the player uses a three-frame decoded audio queue instead of IJK's default nine-frame queue. Its AudioTrack uses Android's reported minimum safe buffer instead of the upstream 2x-playback buffer, and the AudioTrack worker uses normal priority so it cannot starve video/UI on small CPUs. Video-only casting still renders the newest decoded picture immediately. When system audio is present, audio remains the master clock, while decoded video pictures more than 80 ms behind that clock are discarded until playback catches up. This bounds pointer lag without dropping compressed H.264/H.265 reference packets. Ordinary live streams retain IJK's default queue, priority and synchronization behavior.

When building ABIs in parallel, use different `NDK_LIBS_OUT` directories: NDK's install task may remove the other ABI's generated outputs. Copy each successful result into the corresponding app ABI directory and verify hashes.

## Track-switch stability update

The overlay also validates and deduplicates native track selection before closing a working decoder, restores the previous stream on an open failure, and supports explicit initial stream indices for recovery. Text subtitle decoders are included; `ntv_subtitle_text.h` safely converts both compact FFmpeg ASS events and legacy Dialogue events to bounded plain text. See [Android 9 regression report](../../reports/2026-09-02-track-switch-android9.md) for playback, pause/progress, failed-selection and overlay tests.

## Validation (2026-09-02)

Passed:

- ARM32 and ARM64 FFmpeg and IJK native compilation/linking.
- Both Android application variants and the dedicated instrumentation APKs compile.
- `TestDolbyFormats`: DV vs ordinary HEVC distinction, codec declarations, HDMI/speaker rendition priority, TrueHD batch/reset behaviour.
- Existing HLS metadata, subtitle timing/placement, URL-scoped track preference tests.
- `npm test --prefix scripts`: all web control/media/recording regression tests.

Not verified on hardware:

- PCM playback instrumentation on Android 7/4.4. The Android 7 app update succeeded, but installing the separate test APK returned `INSTALL_FAILED_USER_RESTRICTED` / installation cancelled. No test result is claimed from that attempt, and no installation restriction was bypassed.
- HDMI AC3/EAC3/TrueHD passthrough, Atmos receiver indication, hot-plug transitions, AV sync and Dolby Vision output require a compatible TV/receiver and representative media. The connected phones do not establish these capabilities.

### Offline speaker regression

`DolbyPlaybackInstrumentation` uses small offline Apple HLS audio segments, so this test isolates decoding from network speed. Download `a2/fileSequence0.ac3` and `a3/fileSequence0.ec3` under [Apple's TS example](https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_ts/master.m3u8) to `/sdcard/Download/ntv-dolby/sample.ac3` and `sample.ec3`. Both fixtures were copied to the Android 7 phone during preparation.

Build with `:app:assembleArm64DebugAndroidTest` (or `Arm32`), install the matching app/test APKs with device approval, then run:

```text
adb -s DEVICE shell am instrument -w xiao.bu.tv.test/xiao.bu.tv.DolbyPlaybackInstrumentation
```

It requires a no-HDMI speaker route, checks prepare/render callbacks, codec/module identity and advancing audio clock, and does not modify channel settings. Check `nTvDolby` and `nTvDolbyTest` logcat tags. An instrumentation failure is not a passed playback test.
