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
  python tools/web-compat/test-injector.py   (JAVA_HOME points to JDK 8)

Android probes use LegacyWebCompatibilityInstrumentation:
  -e fixture true -e policy true: real-engine early API fixture
  -e benchmark true -e baseline /sdcard/.../before.user.js:
      10 alternating fresh-document execution samples of old/current bundles
  -e list /sdcard/.../sites.txt: document diagnostics, not a playback test
  -e playback true: require browser playback time to advance for 10 seconds
  -e native true: production webview:// channel switch + native first frame,
                  output fps and buffering over 10 seconds; restores settings
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
Small generic pages benefit after caching; tested large TV sites still have
syntax/runtime errors and cold-load regressions. This is why the switch stays OFF.
