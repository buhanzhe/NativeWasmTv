# One-shot legacy video frames

`libntvframe.so` reuses the app's existing IJK FFmpeg library. It decodes the single
AVC sync frame prepared by `AvcFrameMp4`, without networking, audio decoding,
another hardware decoder, or modifying the playing SurfaceView. Preview output
is scaled directly from YUV to a small RGBA bitmap. Original screenshots keep
their decoded dimensions and normal deblocking. All decoder and scale resources
are freed after each call. Java caches the JPEGs separately by playback session.

The checked-in public headers are from [Bilibili/FFmpeg 2902e33f6e59](https://github.com/Bilibili/FFmpeg/tree/2902e33f6e59),
matching the FFmpeg 3.4 library described in `tools/ijk/README.md`. The header
license is in `vendor/COPYING.LGPLv2.1`; the generated `avconfig.h` describes the
little-endian ARM builds. Runtime initialization checks the exact libavcodec
version before using public structures. Missing or incompatible native helpers
fall back to platform frame extraction.

Build both ARM libraries with `scripts/build-video-frame.ps1 -NdkRoot <NDK>`.
The verified compiler is NDK r17c, with API 14 for ARM32 and API 21 for ARM64.
Regular Gradle builds package the checked-in libraries without requiring an NDK.
The original IJK/FFmpeg libraries are not changed.

ARM NEON colour conversion writes to an aligned temporary buffer before copying
rows to Android's bitmap: Dalvik bitmap pixels can have insufficient alignment
for the existing FFmpeg converter. BT.709/BT.601 coefficients and source range
are preserved. The bridge has no per-frame work during ordinary playback.

On Amlogic devices through Android 9, PixelCopy may return success with an
almost uniform empty-YUV green image. The screenshot endpoint detects that
specific placeholder and uses the cached-segment path for both preview and
full-resolution images. The proxy retains one already delivered TS body, capped
at 16 MiB, on these devices as well as on pre-Android-7 devices. No extra stream
download or change to playback decoding is required. The fallback represents a
keyframe in that segment, not necessarily the exact currently displayed frame;
sources without a cached TS segment report an extraction error.
