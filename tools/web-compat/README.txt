Android 4.4 television website compatibility

Scope: https://github.com/buhanzhe/webSourceM3U8/blob/main/webview.txt
Reviewed 2026-10-09: 397 webview:// entries, 98 distinct exact hostnames.
hosts.txt records this scope. It is not a claim that every page can play.

The app automatically loads this local ES5 userscript on API <= 19. It runs
independently of user-imported scripts. An old WebView lacks document-start
injection, so native HTTP(S) GET documents receive an early HTML prelude.
Other WebView versions keep their existing navigation/network behavior.

The exported .user.js also works as a standalone userscript on old engines.
Native TLS/API helpers are available only inside nTv; external userscripts
must rely on the browser's TLS and CORS support.

CZTV /liveTV/:id and IQilu /live/:channel have lightweight player pages.
They read current public playback responses instead of hardcoding media URLs.
CZTV uses server time and Unix seconds when signing the website's CDN URLs.
Its public API retries a transport/server failure once within a 12-second
total budget. Permission-denied responses are not retried.
IQilu reads the current channel/config from HTML and uses its public exchange
protocol. Guangdianyun /tv/:id?uin=:tenant resolves the public API, but the
tested channel 2851 CDN returns HTTP 403, so playback remains unverified.
Playback-denied responses never fall back to internal pullUrl.

Hebei /19/19js/st/xdszb/index.shtml?index=0..6 now uses the same lightweight
shell. Its official page's ES6 player/EPG scripts are not evaluated. A bounded
native empty POST reads the fixed public seven-channel catalog (256 KiB limit,
10-second total timeout, no redirect/retry), and the ES5 adapter validates the
selected entry before applying the website's public t/k signature. API Date
corrects old-device clocks. Existing URL query parameters are preserved; stale
t/k values are replaced. Invalid/missing stream data and denied API responses
fail closed, without alternate/private stream fields or authentication bypass.
Absent index selects channel zero; invalid or repeated indexes show an error
instead of silently playing a different channel. General transpilation stays OFF.

The schema/signature were inspected in the official page on 2026-10-12. Offline
fixtures prove the contract, not current upstream availability or native playback.
The generated bundle also passed ten synthetic scenarios in the official API19
x86 emulator (Android 4.4.2, Chromium 30): seven signed channel selections, two
invalid indexes and an API denial. This used a minimal WebView test APK, not nTv.
Run all seven URLs in hebei-sites.txt through the existing native instrumentation
on a real 4.4 box before declaring channels playable. Check first frame, 10-second
position advance, FPS, rebuffer count, rapid switches, Back and reopen behavior.

KitKat MediaPlayer may fetch HLS outside WebView interception. One explicit
manifest request feeds the app's existing resource discovery/native playback.
The browser process now initializes NetworkClient so saved DNS preferences
also apply to HTML/API compatibility requests.

The optional generic transpiler described below handles syntax; polyfills alone
cannot do this. No fake MSE, WebAssembly, Proxy or codec capability is installed.
All compiler/polyfill code ships locally; none is downloaded at runtime.

Rebuild (no network dependencies):
  node tools/web-compat/build.cjs
Output: app/src/main/assets/control/userscripts/kitkat-tv.user.js
Vendor versions, MIT licenses and SHA-256 are in vendors.json/third_party.

Standard library: pinned core-js 3.50.0, selected modules for Chrome 33.
The config is corejs/config.json. Promise/URLSearchParams vendors and handwritten
Object/Array/String/Number polyfills were replaced; fetch and small DOM helpers
remain separate. No core-js compiler or Node dependencies ship in the APK.
Regenerate core-js (developer dependencies only, versions/integrity locked):
  npm ci --prefix tools/web-compat/corejs --ignore-scripts
  node tools/web-compat/build-corejs.cjs
  node tools/web-compat/build.cjs
The regular build.cjs remains offline and needs no npm packages.

Checks:
  node tools/web-compat/test.cjs
  node tools/web-compat/test.cjs path/to/acorn
  node tools/web-compat/test-sites.cjs
  python tools/web-compat/test-site-adapter.py
  python tools/web-compat/test-injector.py   (JAVA_HOME points to JDK 8)

Android probes use LegacyWebCompatibilityInstrumentation:
  -e fixture true -e policy true: real-engine early API fixture
  -e benchmark true -e baseline /sdcard/.../before.user.js:
      10 alternating fresh-document execution samples of old/current bundles
  -e list /sdcard/.../sites.txt: document diagnostics, not a playback test
  -e playback true: require browser playback time to advance for 10 seconds
  -e native true: production webview:// channel switch + native first frame,
                  output fps and buffering over 10 seconds; restores settings
                  -e transpile true enables the experiment for this run;
                  per-channel screenshots and browser diagnostics are recorded
  -e dumpStall true: optional same-UID SIGQUIT diagnostic for a stalled browser;
                    collect /data/anr/traces.txt to inspect that browser's stack
JSONL results are written to the app's external files directory.

Generic syntax experiment (Android 4.4, default OFF):
  Browser configuration > Old webpage syntax compatibility (experimental).
  API setting: webViewLegacyTranspilation; applies to newly opened documents.
  Callback injection avoids about:blank/unfinished documents. This prevents a
  Promise scheduler capturing a discarded MutationObserver before the HTML prelude.
  No host rules are added for this path. core-js still runs in WebView; QuickJS
  executes Babel/Acorn only, never the supplied page and never a DOM emulation.

  Inline classic JS and declared/dynamically loaded .js/.mjs resources are
  adapted on interception workers. JSON/data scripts stay untouched. ESM is
  compiled to System.register and loaded by the bundled SystemJS 6.15.1 s-loader.
  Relative/static/dynamic imports and inline import maps are covered by fixtures.
  Existing nomodule scripts take priority over replacement module entries.
  Deferred modules start after DOMContentLoaded; mixed classic/module event
  ordering and inline import.meta.url are not a full native-module emulation.
  eval/Function, worker/blob scripts, iframe documents, CSS and browser engine
  features remain outside this first experiment.

  A Java lexical preflight bypasses the compiler for plain scripts. Potential
  modern syntax is checked in Acorn before Babel runs; transformed output must
  parse as ES5. AST feature selection reduces unnecessary Babel passes, with
  preset-env fallback for uncovered syntax. Babel initialization stays lazy.
  The persistent worker is in a non-exported :web_transpiler process. Each native
  runtime has a 64 MiB heap, 768 KiB stack, 15 s initialization and 8 s transform
  deadlines. A 25 s IPC watchdog only kills that verified compiler process.
  Idle runtimes are released after 30 s. Player/browser UI threads do not compile
  or wait on the long compilation lock.

  Input <=512 KiB, generated output <=2 MiB, main HTML <=1 MiB. The document stops
  cold compilation after accumulated work reaches 8 s or two errors; the last
  in-flight request can exceed that soft budget. Existing cache hits and plain
  scripts still work. Oversized/failed resources use the browser's original path;
  native network streams are not returned for oversized JS. KitKat response reads
  end cleanly on IOException instead of propagating through BrowserBlocking JNI.

  Cache: SHA-256 of compiler assets/config, original URL, module flag and source;
  16 MiB/512 files LRU, atomic writes, missing/corrupt entries rebuilt. ES5 scripts
  cache metadata only. Errors cache for 30 s. Changing source/compiler invalidates
  output; normal WebView cache clearing includes transformed scripts.
  Bump the Java VERSION key when changing syntax preflight policy. Asset hashes
  cover compiler/loader edits automatically. Private IPC orphans are cleaned when
  older than the caller's 25 s watchdog, at service start/idle cleanup.

Reproduce compiler assets and tests:
  npm ci --prefix tools/web-compat/transpile --ignore-scripts
  node tools/web-compat/build-transpiler.cjs
  node tools/web-compat/test-transpiler.cjs
  python tools/web-compat/test-syntax.py
  python tools/web-compat/generic-fixtures.py --port 18769
  Instrumentation: -e generic URL1|URL2 ... [-e mode before] [-e label NAME]
  Instrumentation: -e transpiler true (cold/cache, budget failures, native bridge)
  Production isolated-browser probes: -e urls URL1|URL2 -e transpile true
      [-e settleSeconds 5] [-e proof true for the local fixtures]
  Device tested: SM-G3608 / Android 4.4.4; no Android 7.1 device tested for this work.

Observed dense-modern 10/100/300 KB stress scripts exceed the 8 s transform limit.
Small generic pages benefit after caching. In the 2026-10-09 18-site real-device
comparison, Nanjing TV gained actual native playback (6.875 s first frame, 25 fps,
no rebuffer during 10 s); with transpilation OFF it had no first frame in 45 s.
Other tested large TV sites still have syntax/runtime errors and load regressions,
including on cached revisits. This is why the switch stays OFF.

Real-site testing also exposed a separate Chromium 33 cookie/UI circular wait:
nativeSetCookie on UI waited for Chromium while sensor initialization waited for
UI services. Native HTTP response cookies now use LegacyWebCookies' bounded,
serial worker on pre-Lollipop. It does not perform CookieManager calls on the
interception thread. A cookie-sensors fixture adds a fresh response cookie while
starting deviceorientation, then verifies document.cookie and an XHR cookie header
at the server. Three production-isolated-browser runs passed on Android 4.4:
  -e urls http://HOST:18769/cookie-sensors?nonce=UNIQUE
  -e proof true -e settleSeconds 2 -e timeoutSeconds 15
The fixture is ES5 and should pass without -e transpile true.
Local measurements/report: reports/real-webview-4.4-20261009.txt.

Guizhou /tv/ch01..ch06 and /tv/ch13 (2026-10-12):
- Uses the official public /v1/tv/:channel/ metadata response and preserves
  stream_url exactly, including the server-issued CDN token. No CDN fallback.
- Native metadata GET is bounded to 10 seconds and 256KiB, disables redirects,
  makes no retries, and requests normal no-cache revalidation. Browser-only
  userscript uses the official simple GET to avoid adding a CORS preflight.
- Empty remote_address matches the official component's client-navigation IP
  default; it never invents a viewer IP or uses another region's address.
- If txTime is malformed or duplicated, fail closed. If the token is already
  expired at the metadata HTTP Date, show a stale-address error without media
  handoff. Do not use the potentially wrong Android clock or modify a token.
  Date is a conservative lower bound: unusual cache Age-only expiry may still
  reach the CDN, which remains authoritative. No retry/refresh loop is installed.
- Seven metadata APIs returned HTTP200, but ch01 returned an already-expired
  token and CDN HTTP403. Revalidation still returned stale metadata. This is
  upstream service/cache evidence, not a claim of live playback or an Android
  decoder defect. Other channels' live playback is not established.

Additional offline check:
  node tools/web-compat/test-guizhou.cjs

Captured-page compatibility adapters (2026-10-12):
- Hainan /live.html: the known getOtherHtml helper waits for XHR DONE before
  reading status/body. A source fingerprint leaves unknown future helpers alone.
- Heyuan /itv/: restore only missing jQuery3 .size() as a collection count alias.
Both retain the native page and native XHR/security/network behavior. No broad
error suppression or replacement of native XHR is used.
Actual API19 captured replays remove the two targeted JavaScript exceptions.
They do NOT prove live acceptance: missing captures remain, and Hainan's CSP
and media CORS errors are still visible. No video playback claim is made.
Regression: node tools/web-compat/test-page-adapters.cjs

Shared jQuery 3 collection-size compatibility (2026-10-12):
- Restores the documented removed .size() as this.length only when a callable
  global jQuery has a numeric 3.x.y version, no existing/inherited size member,
  and an extensible fn object. Existing behavior is never overwritten.
- Installs at startup, pending DOMContentLoaded/window.load, and captured script
  load events; no polling, global property setters, eval hooks, or fake DOM.
- Covers external jQuery loaded after window.load and replacement global copies.
  Cannot intercept a single inline/external script defining jQuery then calling
  .size() before that script ends, or private noConflict(true) references.
- Replaces former Heyuan/Harbin/Weifang path copies. Actual API19 tests remove
  their targeted .size exceptions; Heyuan/Harbin retain original page content.
  Weifang still has missing API/CORS errors and a placeholder: no page-pass claim.
- Actual API19 real-jQuery3.7.1 delayed-load fixture: 7/7 checks passed.
  node tools/web-compat/test-jquery-size.cjs
Official contract: https://api.jquery.com/size/
Timing constraints: https://api.jquery.com/ready/

TypedArray.from standard-library coverage (2026-10-12):
- Selects the official pinned core-js es.typed-array.from module, including its
  shared native typed-array prototype setup. API19 already has the nine numeric
  typed-array constructors; this selection does not add replacement constructors.
- Handles numeric conversion, Uint8ClampedArray ties-to-even, arraylike/iterable
  input, map function/thisArg, valid typed-array subclass construction, and
  null-source/noncallable-mapper/invalid-receiver errors.
- Upstream limitation: a linked subclass constructor returning an undersized
  typed array or a plain object is not validated as strictly as the native
  implementation. This is not a claim of complete TypedArray.from conformance.
- Conformant native from methods and existing numeric constructors are retained.
  No WebAssembly, MSE, codecs or network/security capabilities are fabricated.
- This addresses the missing Uint8Array.from reached by captured Shenzhen SDK
  execution, not Shenzhen's API401 or the separate page compilation budget.
  node tools/web-compat/test-typed-array-from.cjs
