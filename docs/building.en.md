# nTv build guide

[Home](../README.en.md) · [User guide](user-guide.en.md) · [完整中文说明](building.md)

## Release editions

`build-release.bat` builds standalone public ARM32, ARM64 and x86 editions without requiring `browser-core`. With a complete authorized private core, the pinned release certificate and package ID `xiao.bu.tv`, it additionally builds `ntv_bro.apk` (ARM32), `ntv_bro64.apk` (ARM64, Android 5+) and `ntv_full.apk` (ARM32/ARM64/x86, Android 4+). Missing private sources or a different certificate/package ID skip all browser editions while keeping ordinary builds available. Native authorization remains enforced.

The full APK includes all three supported native ABIs, the lightweight browser, IJK and QuickJS/TLS. All release editions exclude H5E streaming and retain whole-segment decryption. System WebView is supplied by Android; downloadable TVBus plugins remain managed separately. There are no x86_64 native libraries. Plugin architecture follows the current process. Each edition has a separate updater asset/hash. Only produced artifacts are advertised. Release manifests, mappings and `build-summary.json` are written to the output directory without overwriting workspace manifests. Use `-PwithoutBrowserCore` to verify public-only Gradle builds.

## Requirements and commands

Use JDK 17, Android SDK Platform 34 and Build Tools 35.0.0. The wrapper pins Gradle 8.11.1 with Android Gradle Plugin 8.9.1. Configure `sdk.dir` in `local.properties`. Release signing uses `.signing/iptv-release.jks` and `.signing/keystore-info.properties` (`alias`, `storePassword`, `keyPassword`). Keep signing files private.

```powershell
# Public-only debug build; no private browser project required.
./gradlew.bat :app:assembleArm64Debug -PwithoutBrowserCore --console=plain

# Signed release editions available for this checkout and certificate.
./build-release.bat

# Authorized browser editions.
./gradlew.bat :app:assembleNtvBro64Release :app:assembleNtvFullRelease --console=plain
```

Builds are incremental by default; use `build-release.bat -Clean` for a clean build. The default output directory is `output/`, with English release assets, Chinese filename aliases, update manifests, `build-summary.json` and `mapping.zip`. Hash-specific R8 mappings remain archived under `mapping/<APK SHA256>/mapping.txt`. Keep each APK's mapping for crash diagnosis. Startup manifests retain versionCode 6; `version-lite.json` uses the APK's actual version.

## Private browser and site scripts

The protected Java/JS, litehtml, WASM and browser-specific QuickJS/TLS sources belong to a separately authorized `browser-core/` checkout, which is ignored by the public repository. Binary snapshots under `prebuilt/litebrowser/` cannot replace its Java interfaces and assets. Ordinary editions use networking/profile helpers and an unavailable-browser facade under `app/src/browserUnavailable/`.

Site interactions and site-specific user agents belong in the application's user scripts. Add `@user-agent` with the required identity and limit its scope with `@match` and exclusions. Protocol authentication and clock policies live in `WebSiteCompatibility`, exposed through the optional `LiteHostPolicy` interface. The reusable browser core has no application site table.

Browser APK redistribution still requires complete corresponding source under the applicable licenses; see [copyright and use notes](../COPYRIGHT.md). Binary-only snapshots do not satisfy that obligation.
