# nTv

`nTv` is a landscape Android TV player for 18 CCTV HLS channels: CCTV-1 through
CCTV-17 and CCTV-5+. It targets Android 4.1 (`minSdk 16`), uses pure Java for
the application layer, and plays through ijkplayer `0.8.8`. The application ID
is `xiao.bu.tv`.

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
