# TVBus compatibility plugin

TVBus/SOP channel URLs are accepted by both playlist parsers. Selecting a `tvbus://` or `sop://`
channel automatically downloads the engine for the current APK's process ABI from
[the upstream SDK](https://github.com/binstreamio/tvbus.android). There is no settings page.
The APK contains only the stable JNI bridge, not the native engine.

## Current playback and separate JAR (2026-10-26)

### Additional Hong Kong / Macau list audit

The 21 TVBus addresses supplied for 翡翠台 through 無線新聞台 all decode to
`sop://127.0.0.1:53812/<channel-id>`. The encoded strings contain no remote
broker. Channel identifiers were preserved during testing. As explicit diagnostic
experiments, the reference app's default `https://p2p.aliiptv.com` and the remote
broker used by the previously playable extracted channels, `http://br4.667788c.cc:80`,
were each tried against all 21 identifiers on MI 6 / Android 7.1.1. Neither run
produced a prepared playback URL: results were `-104` or `-113/100502`. HTTP 200
on broker requests alone is not playback success. Neither candidate was inferred
from the list, and no automatic remote substitution was added to the app. These
results do not establish that the channels are unavailable in their original app.

An exported loopback source now automatically uses its original local broker
when that exact port is reachable on the playback device. The reachability check
runs on the plugin worker and times out after 500 ms. A missing service produces
a specific message rather than attempting to download the engine repeatedly.
Provider-supplied `broker_url` remains supported. Engine reuse additionally checks
the provider profile, so changing settings for the same binary takes effect.
No configuration screen was added.

Evidence: `.codex-tmp/tvbus-hk-compat/decoded.json`,
`result-reference-all.json`, `result-extracted-broker-all.json` and
`result-final-release.json`. Both full-list experiments restored the original
playlist settings and selected channel. The separate 星空衛視 webview entry is
not counted among these 21 TVBus results.

`TvbusAddress` decodes Base58/DES TVBus addresses and accepts plain SOP addresses.
Remote broker requests are relayed by a per-channel loopback `TvbusBroker`, including
the SDK's `/v1/new_peer` and other `/v1/*` protocol endpoints. Binary bodies and
message seeds are preserved; app identity, role, authentication and signatures
are not rewritten. Closing or changing the channel closes broker sockets and
upstream connections. Native prepared callbacks now fall back between HLS and
HTTP; imported bundles may prefer HTTP, wait for initialization and choose an
available playback port.

`tools/tvbus/build-plugin.py` creates a dex JAR containing the JNI bridge,
ABI-specific native engines and `tvbus-plugin.json`. No Python runtime is needed:
the Android Java cryptography API handles address decoding. The independent
plugin is not embedded in the host APK. Import it through the existing floating
add button's local-file picker; `/api/tvbus/plugin` verifies DEX/native hashes,
ABI, format and size before atomically activating immutable private files. Failed
imports retain the previous bundle. No configuration screen was added.

Build the public SDK bundle from a directory containing
`arm64-v8a/libtvcore.so` and `armeabi-v7a/libtvcore.so`:

```powershell
python tools/tvbus/build-plugin.py --engines-dir PATH --output tvbus-compat.jar --upstream-base https://raw.githubusercontent.com/binstreamio/tvbus.android/master/tvbus/src/main/jniLibs/
```

Omit `--upstream-base` for provider-specific engines without a declared update
source. `--apk INPUT.apk` is also supported for local extraction experiments.

For the tested public SDK bundle, metadata contains the official per-ABI update
URLs. HEAD checks reuse the bundled native binary, establish a redirected-URL
baseline without downloading the same binary, and stage changes for the next
channel. Locally supplied bundles without upstream URLs are replaced by importing
a new JAR.

The same add dialog extracts TVBus/SOP URLs from pasted text and sends them to
temporary playback. A final cold-start quick-play check rendered tvN at 1280x720
with an advancing clock; the startup catalog refresh no longer replaces this
temporary channel. Explicit channel switching clears temporary playback and
closes the broker. Evidence: `result-quick-play.json` in the evidence directory.

On MI 6 / Android 7.1.1, real remote sources extracted from the supplied Super
Live app were exercised, rather than generated videos:

- arm64 Debug/public SDK: 5 of 8 sources rendered frames and advanced playback.
- arm32 Debug/imported public SDK JAR: 6 of 8 sources rendered frames and advanced playback.
- arm64 Release/imported public SDK JAR: 6 of 8 sources rendered frames and advanced playback.
  This run used one native file across all channel switches, downloaded no engine
  bodies, and logged unchanged upstream metadata. Both HLS and HTTP streams played.
- Individual failures varied between runs and included upstream connection failures.
  Successful examples included 东方卫视、凤凰卫视香港台、广东珠江、广东体育、NBA TV and tvN
  in the arm64 Release run.
- The unchanged native engine extracted from Super Live loaded and initialized,
  but returned `-120` on all 8 sources. It is not the engine in the delivered
  public-SDK plugin; no signature or authentication bypass was attempted.

The user additionally supplied
`tvbus://8NLd2KqJ344hKdRuGDAYc83JBWr6yihTGB8GhoswrKDXE3KGA6` (TVB明珠台).
It decodes to `sop://broker.6868a.cc:443/23111`. In the live device test, resolution
failed with `UnknownHostException`; independent Google and Cloudflare DNS-over-HTTPS
queries both returned DNS status 3 (NXDOMAIN). Playback of that address did not
succeed. DNS failures, TLS failures and upstream HTTP errors now have separate
channel-overlay details.

Source settings and the previously selected channel were restored after all
device runs. Evidence: `.codex-tmp/tvbus-playback/result-arm64-generic-relay.json`,
`result-arm32-public-jar.json`, `result-arm64-release-jar.json` and
`result-arm64-release-pearl.json`. These reports mark `syntheticFixture=false`.

Additional checks:

```powershell
python tests/tvbus-broker.test.py
python tests/tvbus-bundle.test.py
python tests/tvbus-live-config.test.py
python tests/tvbus-plugin.test.py
node tests/tvbus-playlist.test.cjs
node tests/quick-add.test.cjs
```

## Initial implementation and earlier audits

The channel overlay reports initial installation, connection, and engine errors.
Downloads retry once with a cache-busting URL. ELF architecture, size, download
length, and the installed file's SHA-256 are checked before loading. SHA-256 here
detects corruption; it is not an upstream signature or an independently pinned hash.

Installed binaries live in the app's private `files/tvbus/<abi>` directory under
immutable content-derived filenames. Reopening a channel reads the existing file.
After opening a TVBus channel, a separate worker checks upstream metadata at most
once per 30 minutes per activity. HTTP 304 skips the body; URL, ETag,
Last-Modified, or Content-Length changes trigger an atomic download. Failed update
checks leave the installed file in place. A changed binary is adopted when the
next TVBus channel opens, using a separate bridge class loader and native path.

Switching channels invalidates queued callbacks and stops the previous TVBus
stream. A source connection error is reported separately from installation errors
and does not trigger repeated plugin downloads. A connection without a prepared
callback times out after 45 seconds.

## Checks

```powershell
node tests/tvbus-playlist.test.cjs
python tests/tvbus-plugin.test.py
.\gradlew.bat :app:assembleArm64Debug :app:assembleArm64Release --offline
```

`TvbusInstrumentation` additionally exercises a supplied list on a device when
installation of the test APK is allowed. Its exit code indicates whether actual
playback advanced, not merely whether a binary was downloaded.

On the MI 6 / Android 7.1.1 audit, the upstream arm64 engine downloaded and
initialized, subsequent channels and an application restart reused the file, and
the update request returned 304. The supplied list's Jade, Pearl, and TTV entries
returned engine error `-113`, extra error `7`, before a playback URL was prepared.
The upstream demo address returned `-104`. Playback of this list is therefore
**not verified**. A working reference player/engine is needed to distinguish source
format, network, and engine compatibility. Original channel source settings were
restored after the audit.

## FongMi comparison (initial implementation; see current playback below)

The integration follows the initialization contract in
[FongMi TVBus.java](https://github.com/FongMi/TV/blob/fongmi/app/src/main/java/com/fongmi/android/tv/player/extractor/TVBus.java):
install the listener before native init, configure service port 0, playback port
8902 and running mode 1, and execute init/run on the engine thread. Like FongMi,
channel start does not wait for init to return, and run is invoked regardless of
the init return code. Optional native setters tolerate missing SDK APIs. The
listener is bound once per engine. Prepared callbacks accept only `hls`. HLS uses the existing HLS playback
path rather than being forced through the direct TS path.

FongMi's TVBus module contains the Java bridge; its live configuration supplies
`core.so` and optional provider-specific auth, name, pass, broker, domain and
options. The supplied TXT list contains channel URLs without those descriptors.
The integration does not invent provider parameters.

After these changes, Android 7.1.1 MI 6 testing covered one channel from each of
18 groups on both arm64 and arm32. Both engines initialized successfully
(`onInited` tvcore=0); the sampled addresses still returned -113/7 before
onPrepared. This does not establish that the source is dead: compatibility with
the provider's native engine/configuration remains unverified. Original source
settings were restored after both runs.

## Provider core descriptors

The existing add/import controls now accept live JSON. `lives`, inline
`groups/channels/urls`, linked TXT/M3U lists, and depot `urls` are read without a
new configuration screen. Relative engine and child-list paths resolve against
their declaring JSON; relative media paths resolve against the child list.

`core.so` selects the provider binary. `auth`, `name`, `pass`, `domain`, `broker`
and `option` are passed to the FongMi JNI API. URL-valued name/pass/domain fields
are read when the engine is initialized. A provider-supplied `resp` is served at
an opaque loopback URL; if `resp` points at an HTTP URL, it is fetched for each
authentication request so a renewed response is used. No auth responses or
credentials are synthesized. Descriptors that require `pkg/sign` identity hooks
are explicitly unsupported.

Descriptors are stored privately, separately from the phone's normalized channel
list. Selection uses source attribution and exact channel URL; conflicting
descriptors fail explicitly. Caches are isolated by ABI and a canonical descriptor
hash; reordering JSON object keys does not create a new plugin cache. Disabled or
removed sources do not participate in core selection. Plain TXT without a core
descriptor continues to use the upstream generic SDK fallback.

`python tests/tvbus-live-config.test.py` exercises production JSON conversion,
reference paths, core selection, offline caching, refresh, source conflicts, and
the auth server with real local HTTP fixtures. It uses the org.json build
dependency already present in the Gradle cache.

On MI 6 / Android 7.1.1, a **synthetic JNI engine fixture** validated settings,
emitted an HLS callback, and rendered a generated 320x180 H.264 video with an
advancing playback clock. Two channels reused one engine download. After the
fixture binary changed, metadata detection staged a second immutable binary;
opening the next channel used the new binary and continued rendering. This is an
integration test, not evidence that the supplied public TVBus channels work.
Fixture engines/media are not bundled in the APK. Source settings were restored.

## Original TXT audit after asynchronous-init alignment (2026-10-25)

On MI 6 / Android 7.1.1, the final arm64 Release build replaced `xiao.bu.tv`.
The original `E:/Downloads/tvbus(1).txt` (SHA-256
`0eb5a10acc5e75c1ef14493d175d89aa5f27df381fd75b575c25572a417dbb0f`)
was imported unchanged. All 750 TVBus addresses were exercised: 742 first
sources plus 8 alternate sources. Every address returned `errno=-113`,
`extra_errno=7`; none emitted an HLS prepared callback. The separate webview
entry 星空衛視 rendered frames and advanced its playback clock. That result is
not TVBus playback success.

With the same asynchronous init/run/start behavior, official unmodified arm32
V0.6.29 returned `tvcore=-102` at initialization. V0.6.12 and V0.6.5 each failed
to produce a playback callback within 18 seconds for Jade, TTV and CCTV-1 HD.
These older SDK probes are not proof that the list is invalid. The TXT contains
no provider core/auth descriptor, and compatibility with its intended engine
remains unresolved. Source settings and previous channel were restored after
testing. The aligned arm64 Release build remains installed.

Per-address evidence is in `.codex-tmp/tvbus-fongmi-aligned/`:
`result-genericall.json`, `result-genericextra.json`,
`result-legacy32.json`, and `result-mixed-webview.json`.
