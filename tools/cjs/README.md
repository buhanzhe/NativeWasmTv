# Native crypto trials

These tools work with the separately downloaded TvWasm/cjs native plugins.
The release script now bundles H5E streaming by default for ARMv7, ARM64 and x86.
Other site plugins remain separately downloaded. Direct Gradle builds enable the
bundled implementation with `-Ph5eStreaming`; `-Ph5eStreamingChecks` remains an
isolated trial using temporary libraries.

## Release packaging

`scripts/build-release.ps1` passes `-Ph5eStreaming`, checks all three libraries
under `app/src/h5eStreaming/jniLibs`, then verifies each signed APK contains its
matching library. Missing libraries fail the build instead of silently shipping
whole-TS playback. The libraries do not depend on `.codex-tmp` at release time.
Use `-DisableH5eStreaming` on the release script to produce the previous
downloaded-plugin implementation for compatibility comparisons.

Rebuild the bundled libraries from a CJS source checkout:

```powershell
python tools/cjs/build-h5e-streaming.py PATH_TO_CJS_CHECKOUT --ndk PATH_TO_NDK_R17C --bundle
```

ARMv7/x86 target API14; ARM64 targets API21. The bridge loads the bundled
implementation only for CCTV H5E, and falls back to the site plugin if the
library cannot load. Existing CMG/Yangshipin, clear IPTV, WebView and decoder
selection paths are unchanged. This changes encrypted media delivery timing;
it does not promise to eliminate all buffering on every device. Verify runtime
logs contain `H5E streaming supported=true` and `H5E progressive segment=`.

## H5E progressive TS trial

The optional `supportsStreamingTransportStream` and `decryptTransportStreamPart`
JNI calls accept complete video PES units in original TS packet order. The
first chunk resets the module and captures the segment clock; subsequent chunks
retain the same module, clock and cancellation generation. Only a complete,
validated download enters the replay cache. Partial HTTP responses close on
failure, and retries are allowed only before any body bytes entered the pipe.
Old plugins without these JNI symbols retain the whole-segment path.

Build from a local CJS checkout, leaving that checkout and remote manifests intact:

```powershell
python tools/cjs/build-h5e-streaming.py PATH_TO_CJS_CHECKOUT --ndk PATH_TO_NDK_R17C
.\gradlew.bat :app:assembleArm32Release :app:assembleArm64Release -Ph5eStreamingChecks
```

The flag loads `libh5estream.so` from `.codex-tmp/h5e-streaming/apk-libs` instead
of the installed H5E plugin. Build and install the correct ABI; trial libraries
are ARMv7/API14, ARM64/API21 and x86/API14. Gradle builds without either H5E
property retain the downloaded plugin path.

Run the proxy failure tests and byte-for-byte native checks on encrypted fixtures:

```powershell
python tests/h5e-progressive.test.py
python tools/cjs/run-h5e-stream-check.py --device SERIAL --sdk PATH_TO_ANDROID_SDK --library PATH_TO_LIBH5ESTREAM_SO ENCRYPTED_TS_FILES
```

The native check uses an isolated `dalvikvm` process, handles fragmented reads,
and checks cancellation, new-segment restart and whole/stream API interoperation.
It does not replace installed plugin files. Device results, fixture hashes and
experimental APKs from 2026-10-29 are under `.codex-tmp/h5e-streaming/`.

## CCTV scratch reuse

`python tools/cjs/apply-cctv-workspace.py PATH_TO_CJS_CHECKOUT`

The H5E wrapper originally allocated and freed a NAL workspace, including its
required 2 MiB scratch tail, for every NAL. The trial retains that allocation
inside the current segment. Each native worker has its own thread-local
workspace. Module reset discards its address, so an address cannot survive a
new module/key state. Failure paths invalidate the allocation. Original wasm
bounds checks, decrypt steps, output copies, and the scratch tail remain intact.

Build with the cjs checkout's `tools/build-native.ps1`; its profiles include
ARMv7/API14, ARMv7/API19, and ARM64/API21. Do not publish a library by replacing
the remote file alone: plugin manifests and integrity hashes must also match.

The isolated `CctvCryptoTrial` instrumentation compares captured encrypted TS
fixtures using 1, 2, and 3 workers. Workers decrypt independent whole segments;
it does **not** split one stateful segment across threads. Every output is
hashed. The controlled benchmark freezes the wrapper clock equally for all
variants, and adds per-stage native timing. Neither modification belongs in
the production plugin.

Measurements and native builds from 2026-10-11 are under
`.codex-tmp/crypto-speed/`. Eight captured segments were tested on Android 4.4:
three CCTV-1 segments and one each from CCTV-1/3/5/8/13. Baseline and pooled
output hashes match. This is a native decrypt benchmark, not an end-to-end
playback or channel-switch benchmark. Android 7.1 installation was blocked by
the device; API14 was compile-checked, not device-tested.

## CMG bounded JNI copy

`python tools/cjs/apply-cmg-range-copy.py PATH_TO_CJS_CHECKOUT`

This replaces whole-array JNI access with Get/SetByteArrayRegion for the
existing NAL range. `CryptoTrial` checks deterministic output, unchanged bytes
outside the range, and invalid-range rejection using synthetic inputs. These
fixtures do not validate live CMG stream keys. The measured copy improvement
on Android 4.4 was small; this candidate is not enabled in production.

CMG still has shared module state and ordered NAL state transitions. Adding
workers behind its existing lock cannot parallelize those transitions.
Likewise, an AES compiler switch does not replace an opaque generated wasm
transform with a hardware AES implementation. NDK r25 ARMv7 already enables
NEON. Switching the generated code from Thumb to ARM was slower in this trial.

## Real CMG trace experiment

`make-cmg-trace.py SOURCE OUTPUT_DIRECTORY` generates a **test-only** JNI
recorder and `CmgReplayBridge`. `Ku9StartupInstrumentation` accepts
`cmgNativeTrial` pointing to this library. It copies it to an isolated private
directory, loads it before any CMG native use, and records actual playback
calls. Installed plugin files are not replaced. Every record contains the
effective native clock, arguments, expected return, decoded NAL range and
delegate duration. Clock control calls retain their original behavior.

`CmgReplayTrial` runs in a separate test APK. It preserves call order and
compares every return and decoded NAL byte against the capture. Load only one
variant per instrumentation process. Network and trace writing are excluded
from replay timings. This verifies parity with the captured baseline, not
universal key/device compatibility.

`apply-cmg-update-workspace.py SOURCE OUTPUT` is an experimental second
candidate. It reuses only the padded UpdatePlayer tag allocation within a
runtime, clears the whole workspace on every update, and invalidates its
address on reset. It does not skip updates or parallelize state transitions.
Combine it with the bounded JNI-copy patch for the combined trial.

Real CCTV-1/5/8 CMG captures and replay results are under
`.codex-tmp/cmg-speed/`. `report-cmg-replay.py DIRECTORY` summarizes the
three-run baseline, region and combined JSON files. These experiments do not
add libraries to the production APK or publish site plugins.

## H5E PES repacking compatibility

The bundled H5E build also applies `apply-h5e-pes.py`. When removing encryption
padding makes a video PES shorter than its first TS payload slot, the writer
must move the PES header together with the ES and preserve existing PCR bytes.
Otherwise the packet retains PUSI but begins with a raw NAL, which Android 4.0's
Stagefright rejects with `1/-1007`. `h5e-pes-writer.h` is the production writer.
Run `test-h5e-pes.py --device SERIAL --ndk NDK_ROOT` for exhaustive payload-length
checks on Android; `run-h5e-stream-check.py` also checks every output video PES
header in whole-segment and fragmented decryption of real encrypted fixtures.

## Android 9 multi-ABI matrix

`run-api28-matrix.py DIRECTORY --device SERIAL` builds one isolated test APK
per ABI from the test native builds under `DIRECTORY/ABI/VARIANT`. It explicitly
installs each APK with `--abi`, then runs CCTV baseline/workspace TS benchmarks
and CMG baseline/combined exact trace replay. Inputs come from the captured
fixtures above. Android ARM execution on MEmu uses `libnb.so` translation, so
these measurements cannot rank ARM hardware. Three repetitions are used.

`report-api28-matrix.py DIRECTORY` verifies cross-ABI CCTV hashes and summarizes
native timings, playback audits and `NetworkCrashInstrumentation` results.
The local TLS fixture is `tests/tls-pressure-server.py DIRECTORY`; forward its
port with `adb reverse tcp:41443 tcp:41443` and pass instrumentation argument
`endpoint=https://127.0.0.1:41443/`. It produces a disposable self-signed TLS 1.2
certificate; no external service or application credentials are needed.

After testing, uninstall the isolated/test APKs, remove that single reverse
port, stop the fixture process, and restore any original host APK saved before
the architecture installs. Reports from 2026-10-11 are in
`.codex-tmp/crypto-api28/`. The production fix is in `TlsCompat` and
`ProxyHttpConnections`; experimental CJS libraries are still unpublished.
