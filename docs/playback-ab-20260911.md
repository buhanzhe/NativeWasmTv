# Android 4.4 playback comparison — 2026-09-11

Device: Samsung SM-G3608 (8ba8ceb8), Android 4.4.4, ARM32. Current release installed and tested first; then the user-specified v1.6.0 APK installed over it. Existing data retained. High quality, hardware decoding. Single pass; not a cold-start benchmark.

Timing: device log marker immediately before control request to first video-rendered log. CCTV uses play followed by RIGHT on both versions because the old endpoint ignores source selection. Thus CCTV timings include this extra input/route-switch overhead. 45-second no-frame timeout. YSP terminal auth errors stop early. Permission-overlay attempts were discarded.

| Channel | Current YSP ms | Online YSP ms | Current CCTV ms | Online CCTV ms |
|---|---:|---:|---:|---:|
| CCTV-1 | 4910 | Failed | 8840 | 8260 |
| CCTV-2 | 4930 | Failed | 10600 | Failed |
| CCTV-4 | 5270 | Failed | 7610 | 13410 |
| CCTV-7 | 5640 | Failed | 10150 | 9590 |
| CCTV-9 | 6590 | Failed | Failed | Failed |
| CCTV-10 | 4680 | Failed | 10360 | 8030 |
| CCTV-11 | 4730 | Failed | 10660 | 17850 |
| CCTV-12 | 8180 | Failed | 6770 | 24160 |
| CCTV-13 | 7410 | Failed | 8960 | 30090 |
| CCTV-14 | 7360 | Failed | Failed | Failed |

Current YSP: 10/10, 5970 ms mean. Online YSP: 0/10, auth-http-0; no valid mean. Current CCTV: 8/10; online CCTV: 7/10. Compare common successful channels separately; live-network variation prevents attributing the difference solely to JS/native code.

Raw logs and JSON: `.codex-tmp/play-ab-20260911/`. App stopped after testing; device left on online v1.6.0 as requested.

CCTV successful-sample means: current 9243.75 ms (8); online 15912.86 ms (7). Common channels 1,4,7,10,11,12,13: current 9050 ms versus online 15912.86 ms (43.1% lower).
