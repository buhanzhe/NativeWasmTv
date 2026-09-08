# Android 4.0.4 / QuickJS compatibility check — 2026-09-08

Device: `emulator-5564`, API 15, x86 with Houdini ARM translation. ARM32 APK installed
successfully. This verifies compatibility under translation, not real ARM performance.
The original APK and preferences were backed up before testing.

Confirmed:

- Compact plain plugin catalog was downloaded and persisted; site runtime/SO installation
  succeeded for CCTV and Gxtv using their ARM32 artifacts.
- The previous CJS WebView implementation crashed on unguarded
  `WebSettings.setMediaPlaybackRequiresUserGesture` (API 17), and also used
  `evaluateJavascript` (API 19). Guarded fallback calls first fixed the crash and CCTV
  rendered a video frame. The final CJS implementation removes that WebView entirely.
- QuickJS loaded successfully through Houdini. Instrumentation passed Unicode/NUL JNI
  roundtrips, native callbacks, Promise/BigInt, syntax errors, loop cancellation, memory
  exhaustion handling and 50 independent runtime lifecycles (73 ms total in this run).
- The QuickJS build played CCTV from its cached resolved URL (first video/audio decode
  and rendered video frame logged); this does not verify a fresh CCTV QuickJS API request.
- Gxtv's initial HTTP code 0 was traced to a server TLS `protocol version` alert.
  Android 4.0's TLS provider could not negotiate the API's required protocol. The
  API's HTTP endpoint redirects to HTTPS, so changing the script to HTTP would not fix it.
- The Mbed TLS 3.6.7 client now handles legacy HTTPS. Gxtv's existing online CJS
  script and per-site ARM32 `gxtv.so` work without modifications. Both the API and
  video CDN remain HTTPS; no network relay or server-side plugin conversion is used.

## Guangxi verification after TLS integration

All eight channels from the CJS repository's root `gxtv.m3u` rendered a first frame:
广西综艺旅游、广西都市、广西影视、广西新闻、广西国际、CETV-1、CETV-2、CETV-4.
Each also logged `AudioCodec: avcodec, aac`. First-frame detection was polled every
second, taking 1.20–1.28 seconds per channel in this warm-process run. These are
polling bounds, not sub-frame latency measurements. The Guangxi variety channel
was visually checked by an ADB screenshot; no scrambled picture was present.
Audio decoding was verified in logs, not by a human listening to the emulator.

TLS instrumentation passed three real Gxtv API requests, a rejecting Java trust
manager, a stalled handshake timeout and concurrent close cancellation. The three
API requests shared one TLS handshake, confirming HTTP connection reuse.

The signed release APK was installed and force-stopped/relaunched three times with
the plugin already downloaded. Each run resolved the Guangxi API afresh and played:

| Run | Activity launch (`am start -W`) | CJS script start to first video frame | API TLS handshake | CDN TLS handshake |
| --- | ---: | ---: | ---: | ---: |
| 1 | 273 ms | 1,600 ms | 187 ms | 81 ms |
| 2 | 228 ms | 1,640 ms | 192 ms | 93 ms |
| 3 | 273 ms | 1,470 ms | 175 ms | 83 ms |

These are process cold starts with filesystem/plugin caches retained, not reboots.
No old/new cold-start performance ratio is claimed: the old TLS build could not
play this source. API 16+ retains its previous TLS path and does not load this SO.

ARM32 native TLS size: 144,856 bytes, compressed to 92,304 bytes inside the APK.
The complete ARM32 APK grows by about 105 KiB over the preceding QuickJS build,
including Java adapter and license. ARM64 contains no TLS SO and grows about 14 KiB
from Java/license files. Both release variants build and pass APK signature checks.

Not revalidated in this pass: Yangshipin, fresh CCTV playback and ARM64 execution.
The emulator retains the release test APK and original three-channel `CJS低版本测试`
source, with 广西CJS selected. The temporary eight-channel test source was removed
from its active configuration after testing.

Both ARM32/API 14 and ARM64/API 21 engine builds are included. `native/quickjs/README.md`
documents limits, source provenance, build commands and the remaining browser-based paths.
