# nativeWasmTv
[**Linux.do友情链接，佬友好**](https://linux.do/u/buhanzhe)

[**恩山友情链接**](https://www.right.com.cn/forum/thread-8484303-1-1.html)

[简体中文](README.zh-CN.md) | English

`nTv` is a landscape Android TV player for 18 CCTV HLS channels: CCTV-1 through
CCTV-17 and CCTV-5+. It targets Android 4.1 (`minSdk 16`), uses pure Java for
the application layer, and plays through ijkplayer `0.8.8`. The application ID
is `xiao.bu.tv`.

## Download

- [Quark cloud drive](https://pan.quark.cn/s/e4db1433c7bb?pwd=KDmk) (access code: `KDmk`)

## Design

The app starts an HTTP proxy on `127.0.0.1`, rewrites the upstream m3u8, and
feeds the rewritten URL to ijkplayer. TS segments are downloaded by the proxy
and passed to JNI before ijkplayer sees them. Channel entry URLs request up to
the 4 Mbps range, and the proxy selects the highest bandwidth variant from the
master playlist so large TVs prefer the clearest available feed.

The JNI layer extracts H.264 PES payloads while retaining a byte-to-TS offset
map. It follows the call order in
[xiaoxi-ij478/cctv-h5e-decrypt](https://github.com/xiaoxi-ij478/cctv-h5e-decrypt):
initialize player state, update the per-NAL tag, invoke the selected VOD
decryptors, and finish with `CNTV_jsdecVOD8`. Decrypted NAL bytes are written
back into the original TS positions, so no remux step is needed during live
playback. The JNI layer also freezes the worker-visible clock for each TS
segment so slower Android TV devices match the upstream worker's fast execution
behavior.

The wasm payload has already been converted into native C with WABT `wasm2c`.
The generated code lives in `app/src/main/jni/generated`.

## Remote control

The remote-control interaction follows
[WebViewTvLive](https://github.com/hxh19950701/WebViewTvLive):

- `DPAD_UP` and `DPAD_DOWN` switch channels during playback.
- `DPAD_CENTER`, `ENTER`, or `MENU` opens the channel list.
- `BACK` or `MENU` closes the channel list.
- The channel list closes automatically after five seconds without input.
- `MEDIA_PLAY_PAUSE` pauses or resumes playback.
- Tapping the video opens the channel list on touchscreen devices.
- Pressing `BACK` twice within two seconds exits the app when the list is closed.

The launcher icon is reused from WebViewTvLive.

## Build

Requirements:

- Android SDK with platform `android-27`
- JDK 8
- Gradle `4.4`
- Android NDK with Linux `armeabi-v7a` toolchain
- WSL when using the bundled Linux NDK from Windows

Create `local.properties` from `local.properties.example`, then build the
native library:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build-native.ps1 `
  -NdkRoot C:\path\to\android_ndk
```

Build the debug APK:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-8'
.\gradlew.bat assembleDebug
```

When `local.properties` points to a Linux SDK under WSL, run Gradle inside WSL:

```powershell
wsl -e bash -lc "cd /mnt/c/path/to/nTv && ./gradlew assembleDebug"
```

The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

### Release signing

Release builds automatically use the IPTV certificate when these local files
exist inside the project:

```text
.signing/iptv-release.jks
.signing/keystore-info.properties
```

The properties file uses this format (replace each placeholder locally):

```properties
keystore=iptv-release.jks
alias=<key alias>
storePassword=<keystore password>
keyPassword=<key password>
```

The complete `.signing/` directory is ignored by Git because it contains the
private key and passwords. Build the signed release APK with:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-8'
.\gradlew.bat assembleRelease
```

The signed APK is generated at `app/build/outputs/apk/release/app-release.apk`.

## Automatic updates

The app checks `version.json` on startup. Both the manifest request and the APK
download are sent through `https://gh-proxy.com/`. When `versionCode` is newer
than the installed build, the app asks for confirmation, downloads the APK,
verifies its SHA-256 when provided, and opens Android's package installer.
The app does not request Android's `REQUEST_INSTALL_PACKAGES` permission.
It targets API 25 so the legacy installer intent remains available without that
manifest permission. Android 8.0+ can still require the user to approve this app
as an installation source; that system security confirmation cannot be bypassed.

The update endpoint is fixed to the latest GitHub Release manifest:

```text
https://gh-proxy.com/https://github.com/buhanzhe/NativeWasmTv/releases/latest/download/version.json
```

Older builds continue to read `version-iptv.json` from the `master` branch.
`generateVersionFile` therefore keeps generating that compatibility manifest;
do not delete it. Its legacy `apkUrl` and `sha256` fields always refer to the
32-bit `nTv.apk` asset.

Each release contains `nTv.apk` (ARMv7), `nTv64.apk` (ARM64), and `version.json`.
The architecture is fixed into each APK, so a 32-bit install only updates to
`nTv.apk`, while a 64-bit install only updates to `nTv64.apk`. Generate the
manifest from both signed APKs:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-8'
.\gradlew.bat :app:generateVersionFile `
  '-PupdateApk32=C:\path\to\nTv.apk' `
  '-PupdateApk64=C:\path\to\nTv64.apk' `
  '-PreleaseNotes=本次更新说明'
```

Upload the generated `version.json` with both matching APKs to the same Release.
The repository and Release asset must be publicly accessible because
`gh-proxy.com` cannot authenticate to a private GitHub repository. Always pass
both APK properties so clients can reject corrupted downloads.

Android 4.4 has TLS 1.2 support but does not enable it consistently. The app's
TLS compatibility layer enables TLS 1.2 and compatible ECDHE/AES cipher suites
for the update service and existing HTTPS playback requests.

## Regenerate wasm2c output

The checked-in native C was generated from upstream commit
`c56afb59bc4cf176acb137f7182c400565e0c4fa`. To regenerate it after updating
that repository, download WABT `1.0.39` for Windows and run:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\extract-wasm.ps1 `
  -WorkerJs C:\path\to\cctv.worker.new.js `
  -WabtRoot C:\path\to\wabt-1.0.39
```

The extracted wasm SHA-256 for the checked-in conversion is
`b645471c0b114f4a385c7432002d46f409f1766b0c424f770afb6c921abc66ce`.

## Notes

- The current build packages `armeabi-v7a`, which is suitable for Android 4.1+
  TV devices and phones with that ABI.
- Hardware decoding is enabled on Android 4.1+.
- The proxy intentionally falls back to the original segment when it cannot
  recognize an H5E stream or cannot perform an in-place NAL replacement.
- A trapped wasm call rejects that segment instead of passing encrypted bytes
  to ijkplayer, and resets the native wasm state before the next request.
- Use the player only with streams you are authorized to access.
