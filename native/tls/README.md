# Legacy Android HTTPS client

Mbed TLS **3.6.7 LTS**, using the Apache-2.0 license option.
[Official release](https://github.com/Mbed-TLS/mbedtls/releases/tag/mbedtls-3.6.7).
The unmodified `include/` and `library/` directories are vendored with the upstream
license and changelog. The application APK includes `assets/licenses/mbedtls.txt`.

Source archive: `mbedtls-3.6.7.tar.bz2`

SHA-256: `a7e8bcbec0e6f761b4af24f25677626b35f762f68eef79c08677a363212d11f6`

The archive was checked against the checksum published alongside the release.
To update it, replace the vendor sources from an official release, verify its
checksum, rebuild, and rerun the device tests below. No edits to vendor files
are required; the application-specific configuration lives in `config.h`.

## Scope and size

- ARM32/API 14 native library, used only on Android 4.0 (API 14/15). The ARM64
  APK does not contain this library. API 16+ retains its existing TLS provider.
- Loaded at the first legacy HTTPS handshake, never in `Application.onCreate`.
- HTTPS client, TLS 1.2, ECDHE with RSA/ECDSA, AES-GCM/CBC, P-256/P-384/X25519.
  No server, DTLS, PSK, TLS 1.0/1.1/1.3, CLI, or certificate files bundled.
- The stripped library is 144,856 bytes in the verified NDK r14b build.
- Normal 16 KiB TLS records are retained for public-CDN compatibility. Encrypted
  I/O uses one reusable 16 KiB Java buffer per connection. Native state is per
  socket and freed on close; no worker threads or global session cache are added.
- OkHttp still owns DNS, connect/read/write timeouts, redirects and connection
  pooling. HTTP/1.1 keep-alive avoids repeated handshakes for HLS segments.
  HTTP/2/ALPN and TLS session resumption are not implemented in this small adapter.
- `/dev/urandom` seeds CTR-DRBG after Android boot. Mbed TLS's default `/dev/random`
  can block indefinitely on these old kernels. No deterministic seed is used.

## Trust and I/O

The native client returns the complete DER certificate chain to Java, then calls
the supplied `X509TrustManager` before allowing application data. Hostname checking
remains with the HTTP client. This adapter does **not** change the application's
existing `TlsCompat` trust-all certificate/hostname policy. Its instrumentation
test supplies a rejecting trust manager to verify that the adapter honors rejection.

The underlying Java socket is closed before waiting for the engine lock, allowing
cancellation to interrupt a blocking handshake/read. Socket timeout exceptions
remain `SocketTimeoutException`; the native TLS timeout status preserves partial
records for OkHttp's idle-connection probe. Native calls are serialized per socket.
This is an internal blocking HTTP client adapter, not a general-purpose JSSE
provider, and does not replace the separate WebView network stack.

## Build and verify

```powershell
./scripts/build-tls.ps1 -NdkRoot <Android-NDK-r14b>
./scripts/build-release.ps1 -RebuildTls -NdkRoot <Android-NDK-r14b>
```

Normal release builds use the checked-in SO and check that it exists. An NDK is
needed only to rebuild the native library.

```powershell
./gradlew.bat :app:assembleArm32Debug :app:assembleArm32DebugAndroidTest
adb -s emulator-5564 install -r app/build/outputs/apk/arm32/debug/app-arm32-debug.apk
adb -s emulator-5564 install -r app/build/outputs/apk/androidTest/arm32/debug/app-arm32-debug-androidTest.apk
adb -s emulator-5564 shell am instrument -w -e tls true xiao.bu.tv.test/xiao.bu.tv.QuickJsInstrumentation
```

The explicit TLS test covers repeated real Gxtv API requests, rejection by the
Java trust manager, handshake timeout and concurrent cancellation against a
local stalled server. See `docs/compatibility-android-4.0.md` for playback evidence.

GitHub downloads use the same default `https://gh-proxy.com/` accelerator on all
Android versions. The API 14/15 HTTP routing branch and old accelerator prefix
handling have been removed. There is no migration of previously saved addresses.
The default accelerator can be verified on API 15 with:

```powershell
adb -s emulator-5564 shell am instrument -w -e github true xiao.bu.tv.test/xiao.bu.tv.QuickJsInstrumentation
```

This test uses the application HTTP stack to download the current Gxtv manifest,
runtime and matching-ABI native module
from the default HTTPS accelerator, and checks both files against manifest SHA-256.
