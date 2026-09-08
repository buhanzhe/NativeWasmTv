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
- Gxtv's real script executed in QuickJS and made its API request, but received HTTP code 0.
  Playback did not start. Error detail logging was subsequently added; the underlying
  network failure is not yet diagnosed. EPG requests separately logged TLS failures.

Incomplete: fresh CCTV QuickJS API playback, Gxtv playback, Yangshipin playback and ARM64
device execution. Automatic approval rejected the subsequent app-start/channel-switch
test with only `blocked by policy`, so these are not reported as passed. The emulator
retains the debug test APK and the three-channel `CJS低版本测试` source for follow-up.

Both ARM32/API 14 and ARM64/API 21 engine builds are included. `native/quickjs/README.md`
documents limits, source provenance, build commands and the remaining browser-based paths.
