Actual API19 WebView synthetic TypedArray.from fixture

Before injecting the compatibility bundle, save native constructor references:
  window.__typedNativeBefore = {};
  ['Int8Array', 'Uint8Array', 'Uint8ClampedArray', 'Int16Array', 'Uint16Array',
   'Int32Array', 'Uint32Array', 'Float32Array', 'Float64Array'].forEach(function(name) {
    window.__typedNativeBefore[name] = window[name];
  });
After injection execute fixture.html. The fixture checks all nine original
constructor identities, conversions, iterable/arraylike input, map/thisArg,
clamping, errors and a valid prototype-linked ES5 subclass.

Before bundle 3dbac4bf lacks Uint8Array.from (0/1 initial existence check).
After bundle 0282e7d8 passes 20/20, with no window errors, across eight samples.
Exact hashes, reported UA and SDK are recorded in api19-results.json. The
Windows-shaped Chrome 30 UA is the harness profile; execution used the actual
Android 4.4.2/API19 WebView in the emulator.

No captured third-party SDK bytes are distributed. These results test selected
semantics, not live page behavior, playback, whole-page compatibility or native
compilation budgets. See the parent README for upstream conformance limits.
