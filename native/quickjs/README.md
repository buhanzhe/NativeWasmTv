# Embedded QuickJS

Source: [Fabrice Bellard's QuickJS 2026-06-04](https://bellard.org/quickjs/quickjs-2026-06-04.tar.xz).
Archive SHA-256: `b376e839b322978313d929fd20663b11ba58b75df5a46c126dd19ea2fa70ad2a`.
The MIT license is retained in `vendor/LICENSE`. Only the engine C files/headers are
vendored; the command-line tools and quickjs-libc filesystem/process APIs are excluded.

Local changes disable Atomics for single-thread runtime instances and undefine the
allocator compatibility macros before QuickJS's own allocator-use guards. `compat.h`
tracks allocation sizes and supplies log2 for Android API 14, which lacks the native
exports expected by upstream. Allocation tracking preserves QuickJS's heap limit.

Each CJS resolution runs on a worker thread with its own runtime/context, 16 MiB heap
budget and 256 KiB JS stack budget. The interrupt handler checks cancellation and a
20-second execution deadline. Java HTTP requests retain their own connection/read timeouts;
cancellation takes effect after a currently blocking HTTP call returns. Runtime memory
is freed on success, error and cancellation. Promise jobs are drained; timers and browser
DOM APIs are not supplied. JNI converts strings through UTF-8 byte arrays, including NUL
and supplementary Unicode characters.

`libntvquickjs.so` is a generic interpreter bundled in the APK, independent of downloaded
site decryption libraries. CCTV/Gxtv `main(item)` execution uses it; Yangshipin's browser
authorization flow and the separate Ku9 resolver retain their existing implementations.

Build with Android NDK r14b:

```powershell
./scripts/build-quickjs.ps1 -NdkRoot C:/android-ndk-r14b
./scripts/build-release.ps1 -RebuildQuickJs -NdkRoot C:/android-ndk-r14b
```

ARM32 targets API 14 and ARM64 targets API 21. Prebuilt libraries are checked in so regular
release builds need no NDK; the release script verifies both engine libraries exist.
`app/src/androidTest/java/xiao/bu/tv/QuickJsInstrumentation.java` checks Unicode, native
callbacks, Promise/BigInt, syntax errors, interruption, heap limits and runtime lifecycle.
