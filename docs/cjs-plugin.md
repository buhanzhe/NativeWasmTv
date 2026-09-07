# CJS compatibility plugin

NativeWasmTv no longer packages the provider wasm2c sources, provider JavaScript, or their
native libraries. They are released by [TvWasm/cjs](https://github.com/TvWasm/cjs) and loaded
through protocol v1.

The shell accepts a configurable manifest URL. It verifies the RSA-SHA256 envelope and every
SHA-256 file digest, selects the current ABI, stages all required files in application-private
storage, and switches versions only after the whole set is durable. Failed installs keep the
previous version.

The plugin is lazy. `CjsPluginRuntime.initialize()` only stores the application context. Cold
start performs no plugin directory scan, parsing, network access, hashing, or native loading.
The active JS bundle and C modules open on first use of a related source. Normal custom streams
never require the plugin.

The wire format and compatibility rules are maintained in the cjs repository at
[`docs/plugin-protocol.md`](https://github.com/TvWasm/cjs/blob/main/docs/plugin-protocol.md).
