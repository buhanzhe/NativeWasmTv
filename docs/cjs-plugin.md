# Website plugins (protocol 4)

The shell uses a plain JSON `catalog.json` to discover independent site releases. It only
installs the current channel's site, containing its own `runtime.json` and one native
library for the APK architecture. Caches, version checks, pending updates and loaded
libraries are keyed by site + ABI.

Catalogs/manifests no longer use Base64 wrapping or digital signatures. SHA-256 is checked
once when downloading runtime/SO files; cold startup checks only lightweight metadata and
ELF architecture. Old cached envelopes are converted locally to plain JSON without any
network request. Existing scripts, native libraries and ABI/version caches remain usable.
Older APKs need updating before they can download the new plain manifests.

Channel M3Us also accept online `.cjs` addresses such as
`https://raw.githubusercontent.com/TvWasm/cjs/main/gxtv.cjs?id=f3335975f9fe11e88bcfe41f13b60c62`.
Catalog `sources` and `playback` metadata select the site and validate its parameters.
`cctv.cjs?id=cctv1` and `cmg.cjs?id=600001859` use the same path. Optional
`quality=high|medium|low` applies only to that playback. Scripts receive decoded query
parameters in `item.params`; stored channel URLs remain the original `.cjs` URLs.
Legacy page URLs remain supported. Earlier protocol 4 APKs need updating to recognize
these direct `.cjs` channel entries; site scripts and native library versions are unchanged.

- `sites/tv.cctv.com`: `cctv.so` and CCTV API JS.
- `sites/yangshipin.cn`: `yangshipin.so` combines signing and CMG; all YSP templates live here.
- `sites/tv.gxtv.cn`: `gxtv.so` and its API JS; no shared site-transformer SO.

Quality keys are `high`, `medium`, `low`, with one to three advertised choices. The host
reads provider mappings, caps Yangshipin by channel availability, and picks CCTV master
playlist renditions. Site JS receives mapped `item.quality` and may return a single URL
or up to three `streams`. The management UI displays advertised choices only.

Cached startup doesn't wait for version checks; first video frame schedules a worker.
Network work never holds the monitor needed by playback/UI. Active script/native versions
remain pinned until the next process, independently for each website. Failed pending
validation preserves the active version. Version 3 storage is never loaded by v4.
On first use in a process, a 20-byte ELF header check rejects a wrong-ABI library.
An incomplete, unused site cache can be replaced by a fully verified staged download
of the same version. Files already pinned by this process are never replaced.

Upgrading from protocol 3 downloads each site once into the new namespace; subsequent
cached starts use the local site immediately. The protocol 4 catalog requires a v4 host.

The old CCTV HTTP implementation has moved to the site's JS. Optional `ttlSec` (max 600)
keeps resolved URLs in a bounded cache keyed by site, script digest, URL and quality, so
returning to the channel can skip WebView/API work. No cross-site request prefetch runs.

See [full protocol](https://github.com/TvWasm/cjs/blob/main/docs/plugin-protocol.md).

## Validation (2026-09-08)

- Plain manifests, artifact hashes and both ELF ABIs passed the CJS verifier.
- ARM32/ARM64 release builds and control-page JavaScript syntax checks passed.
- Android 4.4.4: first video frames confirmed for Yangshipin, CCTV and Gxtv; sequential
  channel visits downloaded/loaded only the relevant missing website.
- CCTV high/medium/low selected 1280x720, 854x480 and 640x360 on the tested live source.
- Android 7.1.1 ARM64: Yangshipin and CCTV first frames confirmed. Gxtv download and API
  resolution completed; no first frame was confirmed before a system WebView update
  terminated the process. This case remains unverified.
- Additional APK architecture-switch and cache-repair device tests were not completed:
  automatic approval rejected the covering installation command without a detailed reason.
