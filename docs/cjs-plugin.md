# CJS compatibility plugin

NativeWasmTv no longer packages the provider wasm2c sources, provider JavaScript, or their
native libraries. They are released by [TvWasm/cjs](https://github.com/TvWasm/cjs) and loaded
through the online-only protocol v3.

The shell accepts a configurable manifest URL. It verifies the RSA-SHA256 envelope and every
SHA-256 file digest, selects the current ABI, stages all required files in application-private
storage, and switches versions only after the whole set is durable. Failed installs keep the
previous version.

The plugin is lazy. `CjsPluginRuntime.initialize()` stores the application context and compares
one preference containing the previous APK ABI. Cold start performs no plugin directory scan,
parsing, network access, hashing, or native loading.
The active JS bundle and C modules open on first use of a related source. Normal custom streams
never require the plugin.

Protocol v3 adds signed per-site declarations and the compact `cmg.cjs`, `cctv.cjs`, and
`gxtv.cjs` version descriptors. After cached playback has rendered its first frame, the host
checks the matching descriptor once per process. A higher component version downloads the
complete signed plugin transactionally and activates it on the next safe process start.

`webview://` pages from a declared online host
are resolved by that site's JS entry, while media decryption stays in its native module. The
first site is `tv.gxtv.cn`: its JS requests the current channel metadata and the common native
site module dispatches its xhls transformer to restore both H.264 and AAC PES payloads before
IJK receives the TS segment. The app host handles signed site declarations generically and
does not contain the Guangxi algorithm or domain-specific playback code.

The CJS protocol accepts HTTP(S) manifests and files only. It does not load local manifests,
user-uploaded scripts, external-storage plugins, `file://`, or `content://` sources.

Plugin state and files are isolated by the compile-time APK ABI. Switching between the 32-bit
and 64-bit APKs selects a separate cache and downloads the matching native modules when needed.
Every downloaded ELF is checked for its class and ARM machine type before activation.

The wire format and compatibility rules are maintained in the cjs repository at
[`docs/plugin-protocol.md`](https://github.com/TvWasm/cjs/blob/main/docs/plugin-protocol.md).
