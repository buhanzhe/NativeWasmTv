# nTv IJK / Dolby changes

## App-owned log files

`apply-app-log-patches.cjs` mirrors SDL/IJK and FFmpeg `ALOG`/`VLOG` output into
`AppLog.appendNative` while preserving logcat output. Application startup binds
the JNI sink; messages enter the same bounded asynchronous queue as Java app
logs. Only INFO and above are mirrored; native INFO and warning/error traffic
have separate budgets of 100 records/second each, before JNI allocation. Dropped
records are counted in the web UI. Native-only threads attach once per lifetime.
Java producers enqueue at most 256 records / 64K characters without taking the
file lock. A low-priority worker formats timestamps (cached per second) and
writes buffered batches every 250 ms while active; it blocks without a timer
when idle. Decoder libraries load only on first IJK use, not for log recording.
UTF-8 bytes avoid JNI modified-UTF-8 assumptions. The app stores two rotating
files under its private `files/logs` directory; `/api/logs` reads these files
without invoking logcat or requiring log-reading/storage permissions.
Rebuild and copy **both libijksdl.so and libijkplayer.so for every ABI** after
applying this overlay. FFmpeg itself is unchanged; its existing callback routes
through the player log macros. `tests/app-log-reader.test.py` checks persistence,
rotation, filters and producer progress while the disk lock is held.
`AppLogInstrumentation` verifies actual JNI output, HTTP download, caller timing,
idle CPU and playback-clock progression under 100 log calls/second. Its local
12-second MP3 fixture must be pushed to `/data/local/tmp/ntv-app-log-perf.mp3`.

## Source frame rate counting

The source packet instrumentation is canonicalised by
`apply-stream-timing-patches.cjs`. Earlier independent source-FPS and latency
insertions broke each other's substring markers on repeated overlay runs, so
one video packet could increment the source counter six times (25 became 150).
The migration removes known duplicated instrumentation and rejects unexpected
code rather than deleting it. Recent media timestamps are also deduplicated in
`ntv_stream_timing.h`, including reordered pictures; real high-rate streams are
not clamped to common TV frame rates. Both ARM player libraries must be rebuilt.

`node tools/ijk/test-stream-timing-patches.cjs` checks fresh insertion, reruns,
sixfold migration and unknown-code rejection. `test-stream-timing.c` checks
duplicate/reordered timestamps, real 25/30/50/150fps, discontinuities and stalls.

Output samples need at least one second before reporting FPS. The minimum stale
sample/reset interval is also one second: a loaded decoder can submit only 2fps
from a 25fps source. The previous 250ms interval kept resetting that measurement
and displayed zero indefinitely on MT6580/API24. The counter still excludes
retained-picture repaints and expires when output stops; paused players return
zero immediately. Sparse source streams retain their longer interval. Both ARM
libraries include the change, verified on API19 and API24.

## Debug bitrate measurements

Debug bitrate measurements use optional long properties 22013/22014 for encoded
video/audio bytes. Upstream queue-byte properties include `MyAVPacketList` memory
and remain unchanged for buffering. `apply-payload-metrics-patches.cjs` subtracts
that overhead only for the new properties. Small AAC packets previously turned a
48 kbps MP4 track into roughly 87 kbps on ARM64; the media matrix now measures
about 48 kbps. Older native libraries return -1 and retain the metadata fallback.
`tools/regression/media_debug_matrix.py` creates ffprobe references and replays
HLS/MP4 through the actual HUD using an isolated application ID. Test output and
media stay under `.codex-tmp/hls-debug-matrix`; fixture HTTP supports byte ranges.

`tools/regression/all_device_media.py --round N` snapshots both test APK ABIs,
then runs independent HLS/MP4/HDR/script and RTP suites on all connected devices.
An incomplete instrumentation report is a driver failure, never a pass. API 26+
video evidence uses PixelCopy from the actual Surface: some Android 9 emulators
return black rotated display screenshots despite a valid video buffer.

`apply-egl-geometry-patches.cjs` fixes the initial height query and keeps software
GLES output at the SurfaceHolder's requested size. Low-RAM software playback uses
the viewport size, while decoding and debug source dimensions stay unchanged.
Low-RAM decoder threading is capped at two; resource probes use one thread,
keyframes only, and no redundant FPS sampling. Plain RTP/UDP gets a 1 MiB receive
buffer. MT6580 is treated as a low-resource device; its field-capable AVC sequences
use software decoding directly because its MediaCodec stalls on tested 1080i.
Other chipsets retain their decoder selection.

## Codec input validation (Bugly 2002)

`apply-codec-input-patches.cjs` rejects non-positive input writes in both IJK
MediaCodec feeding paths. Previously the JNI writer's `-1` failure passed an
`if (!copy_size)` check and was converted to an unsigned buffer length before
being submitted to Android. The JNI writer now also checks direct buffer address
and capacity before copying; queue arguments must fit Android's signed JNI ints.
Positive short writes now trigger a bounded decoder input-buffer enlargement,
instead of submitting arbitrary fragments as independent pictures. Both feeding
paths retain the complete key packet and its original timestamps for retry.
If reopening occurs on a dependent picture, input resumes at the next keyframe.
A vendor that ignores the requested size reports an error through the existing
player recovery path; it cannot repeatedly submit a truncated picture or grow
buffers without a bound (16 MiB compressed input). Ordinary successful writes
add no allocations, decoding, GPU readback or extra threads.

Packets explicitly marked corrupt by the demuxer (including TS continuity loss)
are discarded before codec input. The old reference pictures are flushed and
dependent packets are skipped until a clean keyframe. Unmarked source damage
and vendor-specific HDR output faults cannot be inferred from these flags.

`apply-parser-damage-patches.cjs` preserves demuxer corruption flags through
FFmpeg 3.4's picture parser, including the picture buffered until the next PES.
Without this patch, a TS continuity error can reach MediaCodec as a clean packet.
Parser close/seek clears the pending flag. This uses one private integer per
stream and no per-packet allocation. Rebuild **libijkffmpeg.so** as well as the
player. `test-parser-damage.c` reads the actual library's demuxed/parsed packets
to distinguish a clean TS from a TS with a deliberately missing video packet.

`apply-complete-input-patches.cjs` installs this policy in both codec feeders.
`test-codec-input.c` checks capacity failures, larger later pictures, keyframe
resynchronization, ignored requests and integer/size limits. Rebuild the player
for each ABI after applying the overlay; Gradle alone is insufficient.

The supplied CodecLooper stack ends at ACodec's input-buffer assertions, but
does not include the assertion message or exact system build. Older AOSP checks
input buffer ownership here as well as message fields. This confirms a system
codec abort, not which assertion failed. These changes repair a verified unsafe
submission path; they do not establish that every occurrence of issue 2002 is
caused by it. Hardware decoding remains enabled. ARM32/API14 and ARM64/API21
native compilation and release packaging passed; the original failing device
and its exact abort have not been reproduced. The x86 upstream IJK dependency
does not yet include this overlay.

Reference: [AOSP ACodec input processing](https://android.googlesource.com/platform/frameworks/av/+/jb-release/media/libstagefright/ACodec.cpp).

## HLS channel handoff

The Activity keeps the outgoing video visible and playing while a separate proxy
loads the target manifest and an actual media segment. The channel card stays at
`缓冲中` until target output advances; `onPrepared`, a manifest response and
`BUFFERING_END` alone are insufficient. A failed preload preserves the old
channel. Request generations cancel stale work during rapid channel changes.

Ordinary live MPEG-TS HLS can share one player and localhost playlist when its
single program, stream/PCR PIDs, AVC SPS/PPS or HEVC VPS/SPS/PPS, rendering SEI and
AAC configuration match exactly and its first picture is an IDR. The proxy
maintains a monotonic sequence/timestamp timeline and signals discontinuity at
the channel boundary. Already requested segments are immutable. Encoded video
and audio stay intact; there is no transcoding or new native dependency.

Changed parameters, alternate tracks, encryption, fMP4, VOD and unsupported
formats use normal decoder reopening. The page stays open. A queued handoff
estimated to exceed four seconds also reopens from the warmed fragment instead
of dropping compressed reference frames. Actual output still has a timeout
fallback. Stateful proprietary decryptors retain their existing ordered path.

Warmup files use a bounded disk cache (three completed fragments / 32 MiB
normally, one fragment limited to 64 MiB), file leases and cancellation cleanup.
Only small buffers and bounded TS headers enter memory. Playlist history has a
900-entry limit. `python tests/hls-hot-switch.test.py` generates its own fixtures
and checks compatibility, boundary clocks, queue preservation and pixel equality.
`LegacyChannelSwitchInstrumentation` with `checkHotSwitch=true` exercises live
handoff, withheld fragments, failure and cancellation on the actual Activity.

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

Live HLS buffering also checks a satisfied packet reserve immediately before
the next demux read (`apply-buffer-resume-patches.cjs`). Cached fragments can
arrive inside the 500 ms polling interval and then leave the read thread waiting
for a playlist update, even though both queues already satisfy the target.
The added check keeps the same watermarks, track/packet requirements and pause
behavior; its steady-playback path only tests the buffering flag. Finite-HLS
adaptive reserves keep their existing path. Rebuild `libijkplayer.so` for all
three ABIs after changing this overlay; SDL and FFmpeg do not need rebuilding.
`test-buffer-resume.py --device SERIAL --output DIR` exercises the production
buffering function and packet guard on Android, including the burst-before-read
case, insufficient audio, abort, VOD, audio/video-only and steady-playback calls.

HEVC source colour/depth is also read directly from bounded SPS headers in Annex B
and hvcC extradata. Valid Alternative Transfer SEI overrides the compatible VUI;
reserved/unspecified values cannot erase a valid SPS transfer. Both Annex B and
hvcC length-prefixed samples are supported, including four-byte start codes.
This supplies Main10 and HLG/PQ signalling to both MediaCodec
configuration and the source HUD when the container's parameters are incomplete.
Only headers are parsed; this does not add software pixel decoding to hardware playback.

HEVC scan type also uses the SPS profile-tier-level source flags and VUI
`field_seq_flag`. A positive interlaced flag reports interlaced source even when
field order is unavailable; conflicting or absent source flags remain unknown.
This supplements MediaCodec's missing frame metadata without inferring scan
type from the codec, resolution or frame rate. `test-hevc-color.c` covers the
progressive/interlaced flags, both ambiguous combinations and hvcC framing.

`apply-ffmpeg-buffer-patches.cjs` preserves unread AVIO data and reader offsets
when `ff_configure_buffers_for_index` enlarges the input buffer. FFmpeg 3.4's
original resize discarded that data, causing fMP4 HLS to miss its initial
samples when the stream could not seek back. Run this overlay before rebuilding
FFmpeg for both ABIs; `build-ffmpeg-dolby.sh` applies it automatically.

HTTP SDP descriptors are classified as native media. Once the native demuxer
identifies SDP, `apply-rtp-buffer-patches.cjs` disables the HTTP download reserve
gate: dynamic RTP packets may have no packet duration, so that gate could wait
indefinitely after the first frame. RTP reordering and presentation clocks
remain active. Bare dynamic RTP still requires an SDP encoding declaration.

`apply-annexb-csd-patches.cjs` also supplies existing Annex B H.264/HEVC
extradata as MediaCodec's `csd-0`. Upstream only supplied converted MP4 headers;
the raw-header branch was commented out. SDP sources may carry SPS/PPS only
out of band, so omitting them stalled hardware output while software decoding
continued normally. Both raw and converted headers now reach the codec.

AVC/H.264 also reads bounded SPS/VUI headers directly, from Annex B, avcC and
length-prefixed samples. This supplements FFmpeg 3.4's picture parser, which
does not export its SPS colour values to the private parser context. Metadata
is published during stream preparation on both API19 and API25; explicit SPS
values also reach MediaCodec configuration. An absent initial SPS can use the
same bounded packet queue as HEVC; consumed packets are replayed in order.
Later in-band AVC SPS updates are tracked per parser serial.

An ordinary 8-bit AVC/MPEG-2 stream with all three colour tags unspecified can
show a conservative SDR interpretation: BT.709 for HD, BT.601/SMPTE170M for SD.
The snapshot carries an independent `colorInferred` flag, and HUD/card labels
say “推定”. This is a display interpretation, not recovered source signalling;
it does not modify codec/container fields or select HDR processing. Partially
tagged, wide-gamut, HEVC and higher-depth streams do not use this inference.
The screenshot's 求索记录 SPS has `video_signal_type_present_flag=0`: its 8-bit
depth is real, while its missing colour tags cannot be recovered from that SPS.

For BT.2020 HEVC with compatible SDR/unknown VUI, a bounded startup probe reads
up to the first key packet (64 packets / 4 MiB) before codec configuration. Every
read packet is retained and replayed in order, including audio; seek/exit releases
unconsumed references. The validated SEI is retained independently because opening
FFmpeg's codec can re-export the SPS VUI into codecpar. This fixes HLG output as
well as its HUD label. It does not infer HDR from BT.2020 alone.

BT.2020 non-constant-luminance sources with SDR or unspecified transfer use a GPU
BT.2020-to-BT.709 gamut conversion in hardware and software playback. Unspecified
transfer uses the BT.1886 SDR display curve as an output fallback; the source HUD
continues to report unknown HDR/SDR and unknown transfer. BT.2020 alone does not
select PQ/HLG tone mapping. Explicit BT.709 override still reinterprets the YUV
matrix without HDR mapping. A decoder reporting BT.709 SDR output bypasses the
extra gamut conversion to avoid applying it twice.

`audit-playlist.py PLAYLIST OUTPUT` captures bounded source samples and
decodes them with host FFmpeg/ffprobe. `--retry-failed --reuse-captured` checks
failed entries again without recapturing successful entries. Source checks are
separate from Android playback: `PlaylistPlaybackInstrumentation` replays a JSON
manifest through the actual Activity and checks first frame, advancing video,
dimensions, audio decoder, colour signalling and visible video. Its optional
`expectedGpuTransfer` also checks the configured GPU path, rather than the HUD
alone. The `playlist` URL argument also validates the real importer against the
manifest's `sourceUrl` fields and checks EPG reuse/backup-source merge controls.
An EPG ID alone is not a channel identity: distinct names keep separate entries.
See `HDR_VALIDATION.md` for device results and scope.

The app packages prebuilt libraries, so running Gradle alone does not compile these changes.

The source overlays target the existing `.codex-tmp/ijkplayer-0.8.8` checkout, its configured FFmpeg 3.4 `android/contrib/ffmpeg-arm64` and `ffmpeg-armv7a` trees, NDK r14b and the unchanged `libijksdl.so` in the app. `module-ntv.sh` is the feature profile for a fresh FFmpeg configuration. SoundTouch is the upstream `Bilibili/soundtouch` branch `ijk-r0.1.2-dev`, placed at `ijkmedia/ijksoundtouch`.

1. Run `node tools/ijk/apply-ntv-patches.cjs` from the repository root. Exact-match checks reject unexpected source versions, and running it twice is safe.
2. In Git Bash, run `bash tools/ijk/build-ffmpeg-dolby.sh arm64` and then `bash tools/ijk/build-ffmpeg-dolby.sh armv7a`. These scripts reuse the existing standalone cross-toolchain configuration. Set `NTV_SKIP_CONFIGURE=1` only for subsequent source-only rebuilds. Git Bash's `/usr/bin/awk` must precede the old Windows NDK awk.
3. Generate `ijkmedia/ijkplayer/ijkversion.h` with upstream `version.sh` if absent. Invoke `ndk-build.cmd` from the repo root with `NDK_PROJECT_PATH=. APP_BUILD_SCRIPT=tools/ijk/Android.mk NDK_APPLICATION_MK=tools/ijk/Application.mk NDK_OUT=.codex-tmp/dolby-native/obj NDK_LIBS_OUT=.codex-tmp/dolby-native/libs`, once for `APP_ABI=arm64-v8a APP_PLATFORM=android-21`, once for `APP_ABI=armeabi-v7a APP_PLATFORM=android-14`.
4. After successful linking, copy `libijkplayer.so`, `libijksdl.so` and (when FFmpeg changed) `libijkffmpeg.so` for each ABI from `.codex-tmp/dolby-native/libs` into `app/src/main/libs`. Original libraries from this change are backed up under `.codex-tmp/dolby-original-libs` locally.
5. Run the normal Gradle or release packaging script.

The IJK overlays are LGPL-2.1-or-later, like their host sources. Keep these source changes alongside the redistributed native libraries.

## Hardware HDR lookup acceleration

The hardware SurfaceTexture bridge caches one 64³ RGBA8 colour table for the
current transfer/peak. Its LUT-only shader is separate from formula fallback.
On GPUs supporting 3D texture sampling, the CPU rearranges the generated 2D
atlas once and `NativeHdrOutput.uploadLut3d(ByteBuffer)` uploads that cube through
the optional JNI entry in `ntv_hdr_jni.h` / `ntv_hdr_lut.h`. Steady-state frames
use one hardware trilinear sample. The generation atlas is then released; the
retained colour texture is 1 MiB. There is no per-frame CPU conversion or reuse
of the previous picture. Unsupported extensions, missing older native methods,
upload/allocation errors or 3D shader rejection retain the 2D lookup; formula
fallback remains available if that lookup also fails.

This change requires rebuilding/copying `libijksdl.so` for each ABI using the
native steps above; Gradle does not rebuild the JNI upload helper. R8's existing
public `NativeHdrOutput` keep rule preserves its registered method name. It
does not alter the software decoding renderer.

For isolated mapping benchmarks, extract the actual Java shader constants with
`python tools/ijk/prepare-hdr-bench.py`. Build `HdrAlgorithmBench.mk` with NDK
r17c, `APP_ABI=armeabi-v7a APP_PLATFORM=android-19 APP_STL=system
NDK_TOOLCHAIN_VERSION=4.9 APP_OPTIM=release`, and task-specific `NDK_OUT` /
`NDK_LIBS_OUT` paths. Push `ntv-hdr-lut-bench` and the extracted shader directory
to `/data/local/tmp`, chmod the executable, then run:

```text
lut-bench SHADER_DIRECTORY 3840 2160 7 4 3d
lut-bench SHADER_DIRECTORY 1920 1080 7 8 3d
```

Run tests serially on each GPU. The optional `3d` argument compares the previous
2D-only kernel with native 3D lookup; without it, the benchmark compares the old
combined reference/LUT shader with the 2D-only shader. AB/BA paired batches use
`glFinish`, exclude table generation and readback, and report milliseconds per
draw rather than decoder or playback FPS. Colour probes compare the candidate
against both the old lookup and full formula; GPU filter rounding can differ
by up to two 8-bit codes in the validated samples. Host checks include
`tests/hdr-algorithm.test.py`, `tests/hdr-lut-volume.test.py` and
`tests/hdr-lut-program.test.py`. `HdrVolumeInstrumentation` additionally validates
the production release JNI upload and buffer bounds on an EGL pbuffer, without
launching an Activity or decoder.

## Interactive cast latency

Realtime RTSP PCM uses the first audio output's monotonic clock to correct slow
audio-device drift. The existing resampler makes bounded corrections of about
1%, after a smoothed 40 ms error, while video still follows audio. This prevents
growing receiver delay without changing playback speed or dropping encoded
reference frames. Ordinary live/VOD media retains its original sync policy.
TCP casting skips RTP reordering; UDP retains its bounded reorder window.
Starting a multimedia receiver also closes the outgoing channel's HLS proxy.

For nTv's realtime RTSP session, the player uses a three-frame decoded audio queue instead of IJK's default nine-frame queue. Its AudioTrack uses Android's reported minimum safe buffer instead of the upstream 2x-playback buffer, and the AudioTrack worker uses normal priority so it cannot starve video/UI on small CPUs. Video-only casting still renders the newest decoded picture immediately. When system audio is present, audio remains the master clock, while decoded video pictures more than 80 ms behind that clock are discarded until playback catches up. This bounds pointer lag without dropping compressed H.264/H.265 reference packets. Ordinary live streams retain the normal audio queue and priority; their hardware video synchronization policy is described below.

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

## High frame rate playback and stream statistics

The debug HUD shows rounded `output/source fps`: `25/25 fps` for normal 25 fps progressive playback, or `50/50 fps` for 50 decoded output pictures per second from an interlaced 25-frame/50-field source. The left number remains the measured output cadence, so dropped frames or frame-rate deinterlacing can show `25/50 fps`; an unmeasured output or unknown source shows `--`. Scan order is recovered from codec/container/parser/frame metadata and published separately as `currentSourceStats.scanType` and `sourceFieldRate`. Unknown scan order is not inferred from 1080-line resolution or from a 25 fps value. Progressive sources use source fps, without doubling it. Source access-unit statistics use completed media-timestamp bins with a one-second reorder allowance, so normal B-picture timestamp reordering in remuxed HLS/Matroska does not repeatedly reset the measurement. Output counters still count distinct successful frame submissions against wall time; source fields are never substituted for measured output.

`ntv-frame-sync` keeps audio as master and tolerates 80 ms of hardware scheduling jitter before discarding decoded pictures. Consecutive early drops are bounded; compressed reference packets are never discarded. An empty picture queue wakes on decoder production instead of waiting for another fixed refresh interval.

On API 23+, `IjkCodecPerformance` requests a supported operating rate for 45+ fps streams (60 for a supported 50 fps stream), and realtime codec priority. Capability checks bound the request to the selected decoder's size/rate range. A rejected configuration recreates the codec and retries without these optional hints. API 14–22 retain their existing codec configuration. The JNI helper class and entry point must be preserved in release builds.

Live HLS prefetch is separate from its first-frame water mark. Queues stay bounded to 48 MiB on ordinary devices (64 MiB in stable mode) and 15 MiB on low-memory devices; the native option ceiling is 128 MiB. Background packet targets are 360/480/720 for low/balanced/stable modes; low-memory devices use 360. Stable refill marks respect IJK's actual 5000 ms option limit. Infinite buffering stays disabled.

Finite HLS uses `ntv-vod-buffer-mode` (0 low / 1 balanced / 2 stable).
`apply-vod-buffer-patches.cjs` verifies the HLS demuxer and a positive finite
duration before enabling the reserve. Live streams, ordinary MP4 and casting
retain their existing policy. During startup and genuine rebuffering, the target
uses the actual remaining clip duration:
`remainingMediaMs * max(0, 1 - cachedMediaMs / elapsedDownloadMs) + safetyMs`.
Safety is 2000 ms balanced / 5000 ms stable, bounded by EOF and the native
60-second packet target ceiling; opening/probing time is included in the initial
estimate. Startup explicitly enters native buffering before preparation instead
of relying on a later queue underrun. Seeks use the ordinary short refill mark.
Compressed packets continue downloading during playback, with a 64 MiB balanced
or 96 MiB stable ceiling on ordinary devices. Existing small-device byte limits
are preserved. Queue saturation or EOF releases buffering rather than waiting
forever for an unreachable duration. These are estimated reserves, not a promise
against indefinite bandwidth deficits, outages or insufficient memory.

At a sustained 5 seconds of media per 6 seconds downloaded, a 20-second clip needs
about 8.3 seconds of stable reserve; a 150-second clip needs 30 seconds. Fast
downloads require only the safety margin. Targets shrink towards EOF.
Startup/probe overhead can require more. The finite-HLS recovery watchdog
allows up to 150 seconds for this intentional refill, instead of restarting at
30 seconds. Live and low-delay recovery remain unchanged.

The custom-source proxy also reserves plain finite HLS fragments on disk, beyond
the low-memory device's unchanged 15 MiB compressed-packet ceiling. Balanced
uses at most 128 MiB and stable 256 MiB, including partial downloads, with two
64 KiB streaming workers. Live, encrypted and byte-range playlists bypass this
cache. Files are committed only after complete download; consumed fragments and
closed/crashed sessions are removed. Storage limits fall back to ordinary HTTP.
Startup measures one fragment for clips up to 30 seconds and two for longer clips,
includes partial progress when the
HTTP length is known, and computes the same deficit from the actual EXTINF sum.
The UI displays `网速太慢，稳定播放预计还需 XX 秒` when measured delivery is slower
than playback, updating the estimate as downloads progress. Whole-fragment
rounding and disk quota can limit the reserve; a 0.25-second tolerance avoids
adding an entire fragment for measurement noise. Channel switches cancel waiting,
and download/storage failures stop prefetch. Startup follows the required reserve
rather than starting automatically after a fixed two-minute delay.
This is an estimate under the observed throughput, not a guarantee against later
network changes. There is no fixed two-minute playback horizon.

Requests start in playback order: the next request waits for its predecessor's
headers, then their bodies can overlap. Known-length downloads reserve disk space
before writing, so later fragments cannot steal the pending fragment's quota.
Properties 22040/22041 atomically communicate proxy warmup time and a satisfied
disk reserve to the native player. Initial decoder priming needs only 1 second
when the proxy reserve is sufficient, while genuine subsequent rebuffering
restores the remaining-duration policy. A satisfied reserve or near-full packet
queue is checked on packet arrival, before blocking for another fragment.

The proxy resumes an interrupted HTTP 200 full media fragment as well as an
existing HTTP 206 range. It requires a strong ETag and an exact matching HTTP 206
offset/end/total on resumption, with at most two body retries. A changed entity,
ignored range or missing validator closes the stream instead of corrupting media.
`tests/hls-stream-failure.test.py` fault-injects the production handler and checks
payload offsets, counts and all rejection cases. `test-vod-buffer.c` checks the
reserve calculation independently of Android playback.

FFmpeg 3.4 misidentifies `av3a` audio as MP3. Before opening decoders, the player marks it unsupported and asks best-stream selection to require a decoder, selecting the accompanying AAC track. Explicit supported track choices retain priority. This is an audio selection fix, not an AVS3 decoder; FFmpeg may still log a probe error before the stream's registration is inspected.

Float property `11001` measures video frame cadence from packet media timestamps. A header-only H.264 parser distinguishes separately coded PAFF fields from complete MBAFF/progressive pictures, so 1080i50 reports 25 frames/s. Output property `10002` counts successful submissions of distinct pictures over wall time, excludes retained repaints, and returns zero on pause/stall. Long property `22001` exposes the submission counter for independent interval checks. Statistics never replace zero actual output with nominal FPS.

`tools/ijk/test-stream-timing.c` runs on a host C99 compiler. `LocalStreamFrameInstrumentation` exercises production playback, records source/output FPS, output counts, audio choice, buffering events and AV difference, and restores settings. It rejects loss of foreground focus and intercepts touch events only during the fixture. ARM32/ARM64 player and SDL libraries were rebuilt against the existing, unchanged FFmpeg libraries; a Gradle build alone does not rebuild native code.

## HDR colour output

The debug overlay now shows the source HDR/SDR classification, component bit depth,
colour primaries/matrix and transfer function (including BT.2020, PQ and HLG), plus
the active decoder implementation name. The channel card includes HDR/SDR. Missing
metadata is labelled unknown; HEVC, 10-bit and the selected HDR output mode alone
are never used to classify HDR. Android MediaPlayer cannot expose its implementation
name through this API and reports that limitation explicitly.

`apply-video-info-patches.cjs` adds long property `22010`: one atomic 32-bit source
snapshot with depth [0..4], primaries [5..9], transfer [10..14], matrix [15..19], range
[20..21], Dolby Vision [22], available [24], and software-frame authoritative [25].
The existing compressed-packet probe refreshes stream/header metadata; software
frames update it before upload/conversion. Reading the HUD never decodes or copies
pixels and never borrows a live parser pointer from another thread. Decoder names
come from IJK's current native codec information, independently of the requested
hardware/software setting. `currentSourceStats` in `/api/media` exposes the same values.

`apply-hdr-patches.cjs` runs from the playback overlay script. MediaCodec receives the stream's colour standard, range, transfer, HEVC Main10 profile and available CTA-861.3 static metadata. HDR10/HLG are distinguished by PQ/HLG transfer metadata; HEVC or 10-bit alone does not imply HDR. Dolby Vision keeps its separate decoder policy.

Advanced settings expose four persisted `hdrMode` values (`hardware`, `mapping`, `hardware_sdr`, `bt709`) through `/api/settings` and `/api/state?view=advanced`. The former `passthrough` selection is accepted as a legacy alias and normalized to `hardware`, preserving its identical native mode 1 output policy. Changing modes restarts the current channel. Each player receives `ntv-hdr-mode` (0/1/2/3); policy is carried in that player's MediaFormat rather than a process-global colour switch.

* **转为 SDR / mapping** (native mode 0) explicitly converts HDR10/HLG to SDR even if the display advertises HDR. API 17–30 use a hardware decoder -> SurfaceTexture -> one GLES tone-mapping pass -> SurfaceView. The shared shader uses BT.2446 method-B reference viewing conditions (291-nit HLG, system gamma 1.03), a Mobius highlight shoulder and one common RGB multiplier to retain colour ratios. This is an adaptation inspired by method B, not its full ICTCP colour-volume implementation. PQ is matched at the 1000-nit HLG/PQ bridge. SDR BT.2020 gamut conversion preserves neutral code values using matching BT.1886 display decoding/encoding. Decoder output explicitly tagged BT.709 SDR bypasses the extra conversion. Codec static metadata supplies the PQ peak (1000-nit fallback); it does not rescale scene-relative HLG. API 31+ requests codec HDR -> SDR conversion and verifies acknowledgement before playback. If configure throws or the codec reports that the request is unsupported, the same hardware codec resets once and retries with GPU mapping. A bounded process cache remembers rejected codec/format profiles, avoiding repeated failed requests; it does not force software decoding.
* **GPU 转为 SDR / hardware_sdr** sends native mode 3: hardware decoder -> SurfaceTexture -> GPU HDR-to-SDR pass -> SurfaceView on API 17+, including API 31+. It bypasses decoder tone mapping for devices whose codec conversion produces incorrect colours. Explicit software decoding and hardware-decoder failure retain the software fallback; ordinary SDR keeps direct output without an extra GPU bridge.
* **自动 / hardware (default)** sends native mode 1. It keeps original HDR SurfaceView output only on API 26+ when the actual output display advertises the corresponding format: HDR10/HDR10+ for PQ, HLG for HLG. Otherwise it selects HDR-to-SDR mapping, requesting decoder mapping on API 31+ and using GPU mapping on older devices. Display capability checks are API-isolated for legacy compatibility; no manufacturer/model checks are used. A physically compatible decoder/display/HDMI chain is still required. Software decoding continues to map to SDR.

On API 29+, the GPU bridge tries GLES 3 and uses raw YUV sampling only when
`GL_EXT_YUV_target` is advertised and the shader links. The YUV -> RGB matrix
uses the validated source depth (8/10/12 bit), full/limited range and output
colour standard before PQ/HLG mapping. The sampler and calculations use high
precision, avoiding an intermediate driver RGB conversion. BT.709 override
applies its matrix once; RGB compatibility output retains its existing matrix
correction. Unsupported contexts/extensions/shaders retain the GLES 2 external
RGB path. Ordinary SDR remains direct and allocates no bridge. The older RGB
path cannot recover precision or highlights already lost by a vendor conversion;
an Android version alone does not guarantee correct HDR support. API 14–16
retain the existing software renderer rather than loading EGL14 classes.

No Media3 runtime dependency is included. This implementation follows the
[raw YUV input principle](https://github.com/androidx/media/blob/release/libraries/effect/src/main/java/androidx/media3/effect/DefaultShaderProgram.java)
without importing its player/effect graph. Android documents
[codec tone mapping](https://developer.android.com/reference/android/media/MediaFormat#KEY_COLOR_TRANSFER_REQUEST)
from API 31 and [GPU tone mapping capability requirements](https://developer.android.com/media/media3/transformer/supported-formats#hdr).

The GPU output thread uses display scheduling priority. IJK already releases
video against its A/V clock; the bridge requests the EGL config's minimum swap
interval, with interval 1 retained when capability queries or the request fail.
This avoids a second refresh-period wait where the driver permits interval 0;
it does not lower resolution or change tone mapping/LUT precision. See the
[EGL swap interval reference](https://www.khronos.org/files/egl-1-4-quick-reference-card.pdf).
`gpuTiming` includes configured input/output dimensions and swap interval, in
addition to latch, submission and swap timings, to distinguish scaling from
decoder/GPU/presentation limits.

Surface recreation reapplies the saved source buffer dimensions before decoder
attachment. Transient zero/partial dimensions during mode changes are ignored;
valid size/SAR updates still apply, with repeated metadata avoiding redundant
resizes. `tests/hdr-output-state.test.py` covers these transitions and rejected
EGL requests; original-code controls reproduce both geometry failures. These
checks do not establish sustained frame rate on a particular device/stream.

`python tests/hdr-codec-fallback.test.py` fault-injects accepted/rejected/throwing
codec transactions and checks API 14–37 policy plus 24 matrix/range/depth cases.
These host checks do not replace physical API 31+ codec validation.

When the GPU bridge receives a partial decoder output format, it retains the configured colour standard. An unknown transfer, or a reported SDR/linear transfer without a known colour standard on an HDR source, retains the validated source transfer. This prevents an incomplete tag from silently disabling HDR mapping. Complete known SDR output tags remain authoritative, avoiding double mapping when the decoder actually returns SDR. `decoderColor` logs the original decoder fields and identifies this fallback; it does not relabel the source bit depth.

The third debug line includes the actual colour output route (GPU to SDR, native
HDR, decoder to SDR, or software to SDR), separately from the source HDR label.
`currentSourceStats.colorOutput` and `decoderColor` expose the route and the GPU
bridge's reported decoder standard/transfer/range. A source HLG label alone does
not establish that the output display received HDR or that mapping ran.

The debug HUD also shows `displayHdrStatus`. On API 34+, the actual video View's
Display may expose a live HDR/SDR brightness ratio; `displayHdrSdrRatio` contains
that value, or JSON null when unavailable. Public API reflection preserves the
current SDK/toolchain, with at most one read per second and no legacy-version,
audio-only or pre-first-frame query. Debug-off playback adds no polling.
The label is "屏幕HDR余量 2.00×", never "HDR activated": a brightness ratio or
`Display.isHdr()` does not prove that this application's video activated a
physical panel/HDMI HDR mode. Unsupported APIs, invalid displays and vendor
errors show "屏幕HDR状态未知" rather than inventing a false inactive/active state.
See [Display.getHdrSdrRatio](https://developer.android.com/reference/android/view/Display#getHdrSdrRatio()).
* **强制 BT.709（色彩修正） / bt709** overrides MediaCodec colour standard/transfer to BT.709/SDR, omits HDR static metadata, and uses the BT.709 YUV matrix with tone mapping disabled for software frames. For hardware streams originally tagged HDR or a different colour standard, a GPU pass produces an SDR RGB Surface even if the vendor continues to attach HDR tags to its decoded buffers. It reinterprets the decoder's reported YUV matrix as BT.709 in the encoded domain, with no HDR luminance mapping. Correctly tagged BT.709 SDR keeps direct output. The mode preserves actual bit depth and range. This is a manual compatibility mode for incorrectly tagged SDR sources, not HDR-to-SDR conversion; true PQ/HLG sources can look washed out in this mode. Vendor behaviour still needs validation on each device. Dolby Vision retains its separate decoder policy.

Mapping/hardware/hardware_sdr leave normal SDR colour metadata unchanged. New installs and absent/invalid preferences default to `hardware`, which the app explicitly supplies as native mode 1. Saved `passthrough` preferences migrate to `hardware`; other saved selections persist. The separate decoder setting controls hardware/software selection. `test-hdr-display-policy.java` verifies automatic output across API levels and advertised HDR capabilities, SDR mapping paths, unknown display fallback, and legacy preference migration.

Software decoding uses a YUV GLES output from the first frame, including on API 14–16, and preserves per-frame matrix/range/transfer/static light levels unless BT.709 is explicitly selected. YUV420P10LE now retains the decoder's 10-bit planes and uploads both bytes directly to GLES; it avoids the scalar full-frame 10-bit -> 8-bit conversion and extra frame buffer. Chroma pitches and odd dimensions are handled separately, and limited/full-range offsets retain 10-bit values. YUV texture storage is reused until the plane dimensions change. Tone mapping still runs on the GPU. This does not make old CPUs capable of software 4K playback. Replacement IJK preparation waits asynchronously for the preceding player release (bounded to two seconds), and HDR bridge replacement also waits for its old EGL producer to disconnect. These handoffs prevent a new output connecting to a Surface still owned by the old codec. GPU output statistics count distinct texture timestamps after successful swaps, return zero after a stall, and do not count retained repaints.

`apply-software-render-patches.cjs` also makes the decoder's maximum-frame-rate gate prefer a valid average picture rate, falling back to `r_frame_rate` only when the average is missing. A 25-picture/50-field stream therefore no longer triggers two contradictory rate checks and unnecessary non-reference-frame skipping. True high-picture-rate streams retain the existing limit. Reopening a lower-rate track clears the previous high-rate decision.

`python tools/ijk/test-software-render.py --serial SERIAL --abi arm64-v8a --ndk NDK_PATH --libs app/src/main/libs --output .codex-tmp/software-render-test` links the actual SDL overlay/GLES code and runs it on the device. It checks 8/10-bit 420/444 pixels, limited/full range, zero-copy frame ownership, texture updates, odd/padded sizes, resize and the actual picture-rate gate. `--bench-only` permits a comparison against old SDL libraries. On MI 6 / Android 7.1.1, 4K conversion plus texture submission fell from about 24 ms to 9 ms per frame; that isolated benchmark is not playback FPS. A 35.9 Mbps 4K50 HEVC sample still exceeded the phone's software decoder capacity and remained around 1.6 output FPS. Hardware decoding or a less demanding source is still needed for that case.

The source `app/src/main/res/raw/hdr_tonemap.glsl` is shared with the native shaders; keep it with the LGPL native overlays when redistributing. Both `libijkplayer.so` and `libijksdl.so` must be rebuilt together because overlay colour fields extend their shared structure. The current ARM32/ARM64 IJK/SDL builds use NDK r17c against the existing FFmpeg binaries; Gradle alone cannot rebuild them. The format-prefix migration also removes previously duplicated performance/DV blocks and is idempotent.

The real test source is [Apple's advanced HLS example](https://developer.apple.com/streaming/examples/advanced-stream-dv-atmos.html). The pinned [1080p HDR + AAC playlist](fixtures/hdr-1080p.m3u8) selects PQ HEVC instead of letting the mixed master choose SDR or Dolby Vision. The current sample contains HDR10+ dynamic metadata; this revision uses the HDR10 base/static metadata and does not implement dynamic HDR10+ scene grading. The master also offers SDR companion renditions for comparison.


## Software deinterlacing

The bundled IJK 0.8.8 has no `deinterlace=auto/1` player option and was built
without libavfilter. `apply-deinterlace-patches.cjs` installs automatic BWDIF
into the software video thread. Frames marked interlaced by the actual decoder
produce one progressive output per field, preserving field order and half-frame
PTS spacing. Progressive frames bypass filtering. Stream/seek serial, dimensions
and pixel-format changes discard temporal references; EOF drains the last frame.
Original source scan order, bit depth, colour tags and HDR side data remain
independent of the progressive output frames. Unsupported interlaced pixel formats
surface a playback error rather than silently uploading combed frames.

Interlaced software frames bypass `get_video_frame`'s early-drop decision so
BWDIF always receives consecutive temporal references. Early dropping previously
removed two fields at a time before filtering, potentially introducing vertical
motion jumps and invalid temporal neighbours. Late progressive output can still
be dropped by the display scheduler to retain audio sync; this is not a promise
of full field rate on an overloaded CPU. Progressive early dropping is unchanged.
`test-interlaced-early-drop.py` executes the production decision with controlled
late timestamps on Android, checking that interlaced references survive while
progressive dropping still works.

The scalar kernels in `ntv_bwdif_kernel.h` derive from pinned FFmpeg 3.4
`vf_bwdif.c` (FFmpeg commit 2902e33f6e59), retaining LGPL and BBC attribution.
The wrapper uses the corrected row bounds from current FFmpeg for high-depth
pixels. It supports planar three-component YUV at 8–16 bits, uses up to four
workers for HD, and adds one source-frame lookahead. It does not add libavfilter,
extra shared libraries or fixture videos to the APK.

The worker pool now upgrades after a stream switches from SD to HD. Previously,
rendering one SD field permanently selected a single worker for that player,
including later 1080i frames. A partially created pool is still reused safely.

Forward timestamp gaps no longer stretch the two fields of the preceding source
picture across missing pictures. The last observed source period supplies field
spacing; discontinuities drain spatially and reset temporal references. For a
25-frame/s input with PTS `0,1,5,6` in time base `1/25`, the second field of the
picture at 40 ms now appears at 60 ms, rather than 120 ms. The actual gap before
the picture at 200 ms is retained. Packet durations that describe individual
fields are confirmed against PTS, avoiding accidental doubling of output cadence.

This path runs on software decoding, including hardware-decoder fallback.
It does not configure MediaCodec's vendor deinterlacer. Android has no common
quality/field-rate switch for that pipeline. Hardware interlaced playback is
shown as system handling, unverified; 25 decoded pictures versus 50 fields
must not be relabelled as 50 actual submissions. For BWDIF choose Advanced >
decoder > software (BWDIF). Sustained performance depends on CPU capacity;
native progressive 25p remains 25p.

Long property `22012` returns BWDIF active (source snapshot bit 30).
`/api/media.currentSourceStats.deinterlace` returns `bwdif_field`,
`system_unverified`, `not_needed` or `unknown`; the TV HUD and control page show
the interlaced route. Source scan order occupies snapshot bits 27–29.

`test-deinterlace.c` executes the production frame wrapper using real FFmpeg
AVFrames and test-only pthread adapters for SDL. `test-deinterlace.py` builds
that standalone executable, then compares Android output pixel-for-pixel against
host FFmpeg `bwdif=mode=send_field:parity=auto:deint=interlaced`. Assertions
cover field count, PTS/duration, seek/EOF, colour preservation and references.
Example on Windows, with the configured IJK source and NDK r17c:

```powershell
python tools/ijk/test-deinterlace.py --serial DEVICE --abi arm64-v8a --ndk D:/android/sdk/ndk/android-ndk-r17c --output .codex-tmp/deinterlace/pixels-arm64 --lifecycle --scroll
```

Use `--abi armeabi-v7a` for an ARM32 device. Both top/bottom first, 8–16-bit,
multithreaded and progressive controls are compared; differences fail the run.
Add `--scroll` for 1920x1080 vertically scrolling text in both field orders.
Add `--lifecycle` for mixed progressive/interlaced flags, fractional/unknown PTS,
SD-to-HD changes, timestamp gaps, field-coded durations, changing row strides,
unsupported formats, aborted output and 32 threaded create/drain/destroy cycles.
On the Android 17/API37 phone (25019PNF3C), all 13 pixel cases matched FFmpeg
exactly, including 20 progressive outputs from each 10-frame 1080i scroll case.
The controlled late-frame regression also passed. A same-package player test on
that phone subsequently maintained 50 measured output fps for both top-first
upward and bottom-first downward Chinese text. Baseline and patched players both
passed at ordinary load; this does not reproduce every reported jitter case.
The patched APK remains installed, with the original shared preferences restored.

`test-deinterlace-motion.py --output DIRECTORY` generates known 1080p50 sources,
interlaces it in both orders and checks BWDIF/YADIF field output against the
original. The default speeds are 2/4/8/16 source pixels per output field
(100/200/400/800 pixels/s); `--steps 1,4,16,32` overrides them. Both upward/downward
motion must preserve every source field and remain monotonic. Faster fixtures
have fewer frames to keep every glyph visible without wraps or clipping.
Deliberately reversed parity must produce backwards steps, so the test
also checks its ability to detect field-order jitter. PSNR covers only the text
region, excluding temporal startup/EOF. It is a synthetic quality check, not a
claim of lossless reconstruction or a general ranking of deinterlacers.

The Android 17 same-package comparison was also extended to all four speeds,
both directions and field orders (32 baseline/patched combinations). Stable
recorded intervals contained no reversed motion or steps exceeding 1.5 fields.
Baseline output measured 49.5–50.5 fps; patched output measured 50 fps. Recording
motion intervals varied around 17/25 ms on the phone's 120 Hz display in both
versions, so these results do not establish perfectly uniform presentation or
reproduce the original user report. Startup and speed-change intervals were
excluded; deliberate text wraparound was unwrapped by its known 800-pixel period.

### Extended Android 17 software validation (2026-10-26)

The same-package build was tested with AVC 1080i50 MP4/HLS + AC-3 5.1, AVC
1080i59.94 bottom-first, MPEG-2 1080i50, progressive AVC 25/60 fps and HEVC
30 fps Main10 SDR / 50 fps. All eight playback cases passed. Five pause/resume
and forward/backward seeks settled in about 1.6–2.2 seconds; 30 rapid channel
switches returned to advancing software output.

A complete five-minute high-motion 1080i50 run measured 49.5–50.5 output fps
after startup (mean 49.99), including 30 seconds of four additional bounded CPU workers.
There was no player-process restart or sustained clock stall. Sampled PSS ranged
235.4–239.0 MiB; this short run alone is not proof of absence of memory leaks.
An earlier run was interrupted when another app entered the foreground and was
excluded from the pass result. Original shared preferences were restored.

A single MPEG-TS stream also changed from 720x480 interlaced to 1920x1080:
worker threads increased from zero extra SD workers to three extra HD workers,
output remained approximately 50 fps and EOF drained normally. Original-wrapper
negative controls reproduce both the SD-worker assertion and the stretched-field
timestamp failure; the fixed wrapper passes both. The phone is ARM64-only, so
ARM32 is cross-compiled but not claimed as executed on that device.
Reference: https://ffmpeg.org/ffmpeg-filters.html#bwdif

Cadence and timestamp discontinuity detection share one calculation per picture.
The renderer reuses its output AVFrame object while queued pixels retain their
own buffer references. `test-deinterlace.py --lifecycle` also checks duplicate
and backward timestamps, and retained pixels across rendering and teardown.
All 13 pixel cases, including full-HD scrolling text, remain identical to FFmpeg.

RTSP debug latency uses property 22002: match packet PTS and stream serial to
the submitted video frame, then measure monotonic time from demux arrival to
successful display submission. This includes decoder and A/V scheduling waits,
unlike compressed queue duration. Samples use a bounded 256-entry ring, existing
queue mutex and 0.2 EWMA; no allocations or frame logs. Missing/stalled samples
expire after 2 seconds. It does not measure socket/demux buffering before packet
arrival or Surface/compositor/panel delay after submission. Sender telemetry
expires per field after 3 seconds. The total remains an estimate (sender encode,
queue, send, half control RTT, receiver interval); missing fields yield `--`.

### All-device playback regression

`python tools/regression/all_device_media.py --round N` runs isolated ARM32 tests
on legacy devices/the local Android 9 emulator, and ARM64 on physical ARM64
devices. Each device runs 74 media cases and three supported RTP/SDP cases. A
round snapshots its APKs and retains partial results if instrumentation crashes;
an incomplete run is an error. Fixture generation, the external manifest and
the user's `E:/Downloads/fhshow.js` must be prepared first. Own temporary test
packages/ports are removed; original nTv is stopped during testing so its
background decoder cannot compete with the test app. Channel data is retained.

`python tools/regression/all_device_report.py --rounds 6 7` builds an HTML/JSON
report under `.codex-tmp/all-device-rounds`. It separates successful playback,
probe failures, low output cadence and explicit device-capacity limits. PixelCopy
reads the actual video Surface on API 26+; older UI screenshots hide player
overlays and scan the whole video so a black introduction with a bottom caption
is not mistaken for a decoder failure.

The MT6580 device policy falls back from its unsupported field-capable AVC
MediaCodec to software. Actual output FPS remains measured; a 50-field/s source
does not imply the CPU can output 50 frames/s. Low-resource software playback
uses two decoder threads and viewport-sized RGB buffers. The Android EGL
initializer uses the window's height rather than its width for both dimensions.
Only devices marked low-RAM by Android with less than 1 GiB total RAM impose a
1080p software frame-allocation limit after hardware decoding has been tried.
Rejected decoders return a specific error, relinquish their context once, and
release the player before the next channel. Hardware capabilities are unchanged.

Resource discovery sets `ntv-metadata-only`: publish demuxed tracks without
opening playback decoders or audio/video outputs. `find_stream_info` uses one
thread, skips pixel decoding and performs no FPS sampling; missing TS dimensions
come from bounded, crop-aware AVC/HEVC SPS headers. Live bitrate estimation can reuse already fetched segment payload when
HEAD cannot provide a usable length. Ordinary RTP/UDP receivers and RTP probes
request 1 MiB socket buffers; interactive cast settings retain their policy.

HTTP/2 stream/header failures retry only the affected proxy host with HTTP/1.1.
Ordinary timeouts and carrier IPTV routes retain their transport. Instrumentation
checks wrapped failure detection, per-host connection/protocol selection and
carrier exclusions without making network requests; proof is retained in
`http2-fallback-checks.json`. A passing CDN run alone is not evidence that this
fallback branch was triggered.

The ARM32 APK on Android 9 remains the playback target. Standalone RTP senders
are built for the device's native ABI because Houdini can translate an APK yet
fail to launch an ARM command-line executable. Sending failures are fatal to
the harness rather than being reported as receiver decoder failures.

`python tools/regression/native_headers_regression.py` compares crop-aware AVC
and HEVC SPS dimensions, scan flags and color metadata against all generated
fixture references, including framing, truncation and deterministic fuzz checks.

FFmpeg 3.4 HEVC ignores the generic skip-frame probe option. Metadata-only
stream-info options therefore deny its decoder while retaining normal audio
probing and video parsers. Missing dimensions come from SPS; playback decoder
selection and non-HEVC probing are unchanged.

Manifest discovery lets its local proxy finish bounded upstream retries within
the remaining overall 10-second deadline instead of prematurely canceling at
two seconds. Every matrix case retains one video-only capture plus independent
HUD assertions, system memory samples and a pre-playback crash checkpoint.

Within one metadata probe, at most eight rewritten manifests (256 KiB each) are
reused for ten seconds. Only the default audio/video rendition is exposed to
the metadata reader; alternative languages, audio descriptions and subtitle
downloads would otherwise consume its deadline. Playback proxies keep every
referenced rendition and refresh manifests normally. Local instrumentation
verifies both behaviours in `probe-manifest-reuse-checks.json` without external
network access.


HTTP playback enables `ntv-adaptive-fps-probe` for direct media and the TLS
proxy's plaintext output. IJK marks its format discovery context; the FFmpeg
overlay accepts parsed H.264/HEVC timing only after at least four DTS intervals
agree within 1%. This bypasses only the initial FPS sampling gate. Missing PMTs,
codec headers or audio parameters still retain normal discovery, as do unknown
or inconsistent codec clocks and explicit `fpsprobesize` overrides. The probe
neither drops packets nor changes playback decoder settings. Apply
`apply-adaptive-probe-patches.cjs` when rebuilding each FFmpeg ABI (the build
script does this), then rebuild IJK for its new player option. The pure timing
checks are in `tests/adaptive-probe.test.c`; overlay rerun checks are in
`tests/adaptive-probe.test.cjs`. TLS streaming checks in
`tests/hls-stream-failure.test.py` verify first-body-byte delivery, short-read
flushing, exact ranges, bounded retry and encrypted-media path separation.

PQ LUT generation projects the six off-axis corners of each neutral cell onto
bounded RGB control sums `2*A+D` and `A+2*D`. Its grey diagonal then follows the
straight interpolation of the two reference endpoints rather than dipping in
highlights. Only 378 of the 262144 nodes can change, once per transfer/peak
change. The 2D/3D sampling shaders, texture dimensions and retained 1 MiB cache
are unchanged. Near-white chroma remains an interpolation approximation; this
does not establish full perceptual equivalence to the reference formula.

`tests/hdr-algorithm.test.py` covers 1024 grey levels at PQ mastering peaks
100/400/1000/4000/10000 nits, colour bounds and the 2D fallback. For actual
production playback, `tools/regression/hdr-playback-colour.py` supports
`NTV_COLOUR_OUTPUT`, `NTV_COLOUR_CASES=pq:mapping` and
`NTV_COLOUR_PATCH_SET=near-neutral`. The latter adds 48 near-white colour probes.
The isolated LUT benchmark accepts `fix` after rounds/frames and expects a
frozen `lookup-before.glsl` next to extracted current shaders. It compares
separate original/corrected textures with alternating timing order; decoding,
LUT generation and upload are excluded from these GPU kernel measurements.

`ntv-hdr-cache-bench SHADER_DIRECTORY 3840 2160 12 6` compares 64/96/128/192
cubed RGBA8 tables (1/3.375/8/27 MiB). It keeps one trilinear sample per pixel,
rotates variant order, and compares PQ/HLG output with the full shader formula.
It also checks 64 generated nodes, 24 grey levels and 48 near-neutral probes.
Generation-node errors are reported separately: the large 192-cubed atlas
showed legacy Adreno 306 generation errors and must not be treated as a valid
precision upgrade on that path. These experiments do not change production's
64-cubed cache or establish actual playback FPS for the larger tables.

The production 3D lookup uses `c*0.984375+0.0078125`; all three texture wrap
axes must remain `CLAMP_TO_EDGE`. Its half-texel coordinate clamp is equivalent
to clamping RGB before `(c*63+.5)/64`, including finite out-of-range RGB.
Coordinates and sampler stay highp. The separate 2D fallback still clamps RGB
explicitly because atlas tile boundaries are not texture edges.

For addressing experiments, add `address 64` (or `address 128`) to
`ntv-hdr-cache-bench`. It compares original, folded constants, hardware clamping
and three transposed cube layouts, with 65536 colours plus boundary probes.
`ntv-hdr-lut-bench ... address` compares the frozen original shader against the
current shader using identical corrected tables; put `lookup-before.glsl` in
its shader directory. It rejects output differences above one code value.

`ntv-hdr-cache-bench ... precision 64` compares sampler/result/coordinate
precision against the current highp kernel. It reports rejected experiments
instead of applying the normal two-code differential gate. No consistent
throughput benefit was established on Adreno 306/540 or Mali-G52.

`ntv-hdr-cache-bench ... center` evaluates a different sampling grid: interior
nodes are evaluated at hardware texel centres, endpoints stay anchored to
0/1, and the pixel shader samples directly at RGB without scale/bias. It
compares the production 64-cubed grid with centered 64/96/128-cubed candidates.
These are colour approximations, not algebraically identical shaders. The
centered 64-cubed variant increased worst-case HLG error and is not adopted.
Centered 128-cubed improved isolated Adreno 306 timing and aggregate accuracy,
but costs 8 MiB and slowed Adreno 540. It remains an experiment, without
production integration or actual video playback validation for that profile.
