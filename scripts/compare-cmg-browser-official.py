import argparse
import base64
import json
from pathlib import Path

from playwright.sync_api import sync_playwright


ROOT = Path(__file__).resolve().parents[1]


def read_meta(path: Path) -> dict:
    meta = {}
    if not path or not path.exists():
        return meta
    for line in path.read_text(encoding="utf-8").splitlines():
        if "=" in line:
            key, value = line.split("=", 1)
            meta[key] = value
    return meta


def start_code_length(data: bytes, offset: int) -> int:
    if offset + 4 <= len(data) and data[offset:offset + 4] == b"\x00\x00\x00\x01":
        return 4
    if offset + 3 <= len(data) and data[offset:offset + 3] == b"\x00\x00\x01":
        return 3
    return 0


def read_pts(data: bytes, offset: int) -> int:
    return (
        ((data[offset] & 0x0E) << 29)
        | (data[offset + 1] << 22)
        | ((data[offset + 2] & 0xFE) << 14)
        | (data[offset + 3] << 7)
        | ((data[offset + 4] & 0xFE) >> 1)
    )


def extract_pes(ts: bytes) -> list:
    pes = []
    current = None
    for packet_offset in range(0, len(ts) - 187, 188):
        packet = ts[packet_offset:packet_offset + 188]
        if packet[0] != 0x47:
            continue
        pid = ((packet[1] & 0x1F) << 8) | packet[2]
        payload_start = (packet[1] & 0x40) != 0
        adaptation_control = (packet[3] >> 4) & 3
        if (adaptation_control & 1) == 0:
            continue
        offset = 4
        if adaptation_control & 2:
            offset += packet[offset] + 1
        if offset >= 188:
            continue
        if (
            payload_start
            and offset + 9 <= 188
            and packet[offset:offset + 3] == b"\x00\x00\x01"
            and 0xE0 <= packet[offset + 3] <= 0xEF
        ):
            if current:
                pes.append(current)
            flags = packet[offset + 7]
            header_length = packet[offset + 8]
            timestamp_offset = offset + 9
            dts = 0
            if (flags & 0xC0) and timestamp_offset + 5 <= 188:
                dts = read_pts(packet, timestamp_offset)
            if (flags & 0xC0) == 0xC0 and timestamp_offset + 10 <= 188:
                dts = read_pts(packet, timestamp_offset + 5)
            current = {
                "index": len(pes),
                "pid": pid,
                "dts": dts,
                "chunks": [],
                "payloadLength": 0,
            }
            offset += 9 + header_length
        if current and current["pid"] == pid and offset < 188:
            body = packet[offset:188]
            current["chunks"].append({
                "tsOffset": packet_offset + offset,
                "pesOffset": current["payloadLength"],
                "data": body,
            })
            current["payloadLength"] += len(body)
    if current:
        pes.append(current)
    return pes


def pes_to_ts_offset(pes: dict, pes_offset: int) -> int:
    for chunk in pes["chunks"]:
        start = chunk["pesOffset"]
        end = start + len(chunk["data"])
        if start <= pes_offset < end:
            return chunk["tsOffset"] + pes_offset - start
    return -1


def extract_nals(ts: bytes) -> list:
    nals = []
    for pes in extract_pes(ts):
        data = b"".join(chunk["data"] for chunk in pes["chunks"])
        starts = []
        offset = 0
        while offset < len(data) - 2:
            prefix_length = start_code_length(data, offset)
            if prefix_length:
                starts.append((offset, prefix_length))
                offset += prefix_length
            else:
                offset += 1
        for index, (start, prefix_length) in enumerate(starts):
            end = starts[index + 1][0] if index + 1 < len(starts) else len(data)
            body_start = start + prefix_length
            body = data[body_start:end]
            nals.append({
                "globalIndex": len(nals),
                "pesIndex": pes["index"],
                "nalIndexInPes": index,
                "type": body[0] & 31 if body else -1,
                "prefixLength": prefix_length,
                "pesOffset": start,
                "bodyPesOffset": body_start,
                "tsOffset": pes_to_ts_offset(pes, body_start),
                "body": body,
            })
    return nals


def first_diff(left: bytes, right: bytes) -> int:
    limit = min(len(left), len(right))
    for index in range(limit):
        if left[index] != right[index]:
            return index
    return -1 if len(left) == len(right) else limit


def diff_count(left: bytes, right: bytes) -> int:
    limit = min(len(left), len(right))
    diff = abs(len(left) - len(right))
    for index in range(limit):
        if left[index] != right[index]:
            diff += 1
    return diff


def b64(data: bytes) -> str:
    return base64.b64encode(data).decode("ascii")


def unb64(text: str) -> bytes:
    return base64.b64decode(text.encode("ascii"))


def extract_js_function(source: str, name: str) -> str:
    start = source.find("function " + name)
    if start < 0:
        raise ValueError(f"Unable to locate JS helper function {name}")
    open_brace = source.find("{", start)
    if open_brace < 0:
        raise ValueError(f"Unable to locate JS helper function body {name}")
    depth = 0
    quote = ""
    escaped = False
    for index in range(open_brace, len(source)):
        char = source[index]
        if quote:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == quote:
                quote = ""
            continue
        if char in ("'", '"', "`"):
            quote = char
        elif char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return source[start:index + 1]
    raise ValueError(f"Unclosed JS helper function {name}")


def build_hls_wrapper_source(hls_source: str) -> str:
    cut = hls_source.find(";var fI=function")
    if cut < 0:
        raise ValueError("Unable to locate hls.cmg.js wrapper cut point")
    helpers = extract_js_function(hls_source, "a0b") + "\n" + extract_js_function(hls_source, "a0a")
    return (
        helpers
        + "\n"
        + hls_source[:cut]
        + ";return {fG,fg,fh,fj,fk,fm,fp,fq,fu,fv,fw,fx};};"
        + "window.__cmgWrapper=c();}());"
        + "\n//# sourceURL=hls.cmg.wrapper.local.js"
    )


def build_browser_expected(args, media_tag: str, nals: list) -> list:
    print(f"[official] reading wrapper JS from {args.hls}", flush=True)
    hls_source = build_hls_wrapper_source(Path(args.hls).read_text(encoding="utf-8"))
    print(f"[official] reading worker JS from {args.worker}", flush=True)
    worker_source = Path(args.worker).read_text(encoding="utf-8")
    print(f"[official] reading wasm from {args.wasm}", flush=True)
    wasm_b64 = b64(Path(args.wasm).read_bytes())
    print(f"[official] building payload for {len(nals)} NALs", flush=True)
    payload = [{
        "globalIndex": nal["globalIndex"],
        "pesIndex": nal["pesIndex"],
        "nalIndexInPes": nal["nalIndexInPes"],
        "type": nal["type"],
        "length": len(nal["body"]),
        "b64": b64(nal["body"]),
    } for nal in nals]

    with sync_playwright() as pw:
        browser = pw.chromium.launch(
            headless=not args.headed,
            args=[
                "--autoplay-policy=no-user-gesture-required",
                "--disable-web-security",
            ],
        )
        try:
            page = browser.new_page(ignore_https_errors=True)
            page.on("console", lambda msg: print(f"[browser:{msg.type}] {msg.text}", flush=True))
            print(f"[official] loading browser page {args.page_url}", flush=True)
            page.goto(args.page_url, wait_until="load", timeout=15000)
            print("[official] initializing CMG module and wrapper worker", flush=True)
            setup_info = page.evaluate(
                """async ({ hlsSource, workerSource, mediaTag, wasmB64 }) => {
                  const bootstrap = `
                    var window = self;
                    self.window = self;
                    self.globalThis = self;
                    self.parent = self;
                    self.top = self;
                    self.activeURL = "https://www.yangshipin.cn/tv/home";
                    self.isVodDecode = false;
                    self.vmpTag = "";
                    self.document = {
                      location: { href: "https://www.yangshipin.cn/tv/home", origin: "https://www.yangshipin.cn", host: "www.yangshipin.cn", protocol: "https:" },
                      currentScript: null,
                      body: {},
                      addEventListener() {},
                      removeEventListener() {},
                      createElement() { return {}; },
                      getElementsByTagName() { return []; }
                    };
                    self.navigator = self.navigator || { userAgent: "Mozilla/5.0" };
                    console.debug("NTV before cmg.worker source");
                    ${workerSource}
                    console.debug("NTV after cmg.worker source");
                    console.debug("NTV before hls wrapper source");
                    ${hlsSource}
                    console.debug("NTV after hls wrapper source");
                    const __ntvB64ToBytes = text => {
                      const raw = atob(text);
                      const out = new Uint8Array(raw.length);
                      for (let i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i);
                      return out;
                    };
                    const __ntvBytesToB64 = bytes => {
                      const chunk = 0x8000;
                      let raw = "";
                      for (let i = 0; i < bytes.length; i += chunk) {
                        raw += String.fromCharCode(...bytes.subarray(i, Math.min(bytes.length, i + chunk)));
                      }
                      return btoa(raw);
                    };
                    let __ntvCtx = null;
                    self.onmessage = async event => {
                      const { id, cmd, payload } = event.data || {};
                      try {
                        if (cmd === "init") {
                          if (typeof self.CMGDecModule !== "function") {
                            throw new Error("CMGDecModule not exposed by worker source");
                          }
                          console.debug("NTV before CMGDecModule");
                          const wasmBinary = __ntvB64ToBytes(payload.wasmB64);
                          const moduleCandidate = self.CMGDecModule({ wasmBinary });
                          const module = moduleCandidate && typeof moduleCandidate._jsmalloc === "function"
                            ? moduleCandidate
                            : await moduleCandidate;
                          console.debug("NTV after CMGDecModule");
                          const fG = self.__cmgWrapper.fG;
                          console.debug("NTV before InitPlayer");
                          __ntvCtx = {
                            module,
                            fG,
                            mediaTag: payload.mediaTag,
                            liveDecodeEnabled: false,
                            initResult: fG.moduleActive(module, payload.mediaTag, fG.INITPLAYER)
                          };
                          self.postMessage({ id, ok: true, result: {
                            initResult: __ntvCtx.initResult,
                            liveVideo: fG.LIVEVIDEO,
                            initPlayer: fG.INITPLAYER,
                            updatePlayer: fG.UPDATEPLAYER,
                            activeURL: self.activeURL || null,
                            vmpTag: self.vmpTag || null
                          }});
                          return;
                        }
                        if (cmd === "decode") {
                          const ctx = __ntvCtx;
                          const fG = ctx.fG;
                          const module = ctx.module;
                          const out = [];
                          for (const nal of payload.nals) {
                            const before = __ntvB64ToBytes(nal.b64);
                            const beforeVmpTag = self.vmpTag || null;
                            const activeResult = fG.moduleActive(module, ctx.mediaTag, fG.UPDATEPLAYER);
                            const activeVmpTag = self.vmpTag || null;
                            let expected = before;
                            let decoded = false;
                            let moduleAfterLength = null;
                            let moduleAfterHead = null;
                            let officialMutation = "none";
                            if (nal.type === 7) {
                              const moduleResult = fG.moduleDecData(module, ctx.mediaTag, before, fG.LIVEVIDEO);
                              decoded = true;
                              moduleAfterLength = moduleResult ? moduleResult.length : null;
                              moduleAfterHead = moduleResult
                                ? Array.from(new Uint8Array(moduleResult).slice(0, 48)) : null;
                              if (!ctx.liveDecodeEnabled && before.length > 2) {
                                const bits = before[2] & 3;
                                ctx.liveDecodeEnabled = bits === 1 || bits === 2;
                              }
                              expected = before.slice();
                              officialMutation = "sps-moduleDecData";
                            } else if ((nal.type === 1 || nal.type === 5) && ctx.liveDecodeEnabled) {
                              const moduleResult = fG.moduleDecData(module, ctx.mediaTag, before, fG.LIVEVIDEO);
                              expected = new Uint8Array(moduleResult || []);
                              decoded = true;
                              moduleAfterLength = expected.length;
                              moduleAfterHead = Array.from(expected.slice(0, 48));
                              officialMutation = "moduleDecData";
                            }
                            out.push({
                              globalIndex: nal.globalIndex,
                              pesIndex: nal.pesIndex,
                              nalIndexInPes: nal.nalIndexInPes,
                              type: nal.type,
                              beforeLength: nal.length,
                              expectedLength: expected.length,
                              decoded,
                              officialMutation,
                              liveDecodeEnabled: ctx.liveDecodeEnabled,
                              activeResult,
                              beforeVmpTag,
                              activeVmpTag,
                              afterVmpTag: self.vmpTag || null,
                              moduleAfterLength,
                              moduleAfterHead,
                              expectedB64: __ntvBytesToB64(expected)
                            });
                          }
                          self.postMessage({ id, ok: true, result: out });
                          return;
                        }
                        if (cmd === "close") {
                          if (__ntvCtx) {
                            try {
                              __ntvCtx.fG.moduleActive(__ntvCtx.module, __ntvCtx.mediaTag, __ntvCtx.fG.UNINITPLAYER);
                            } catch (e) {}
                          }
                          self.postMessage({ id, ok: true, result: null });
                          return;
                        }
                        throw new Error("Unknown command " + cmd);
                      } catch (error) {
                        self.postMessage({ id, ok: false, error: error && (error.stack || error.message || String(error)) });
                      }
                    };
                    //# sourceURL=ntv-official-cmg-worker.js
                  `;
                  const blob = new Blob([bootstrap], { type: "text/javascript" });
                  const url = URL.createObjectURL(blob);
                  const worker = new Worker(url);
                  let nextId = 1;
                  const pending = new Map();
                  worker.onmessage = event => {
                    const { id, ok, result, error } = event.data || {};
                    const item = pending.get(id);
                    if (!item) return;
                    pending.delete(id);
                    ok ? item.resolve(result) : item.reject(new Error(error || "worker call failed"));
                  };
                  worker.onerror = event => {
                    for (const item of pending.values()) item.reject(new Error(event.message || "worker error"));
                    pending.clear();
                  };
                  window.__ntvOfficialCmgCall = (cmd, payload) => new Promise((resolve, reject) => {
                    const id = nextId++;
                    const timer = setTimeout(() => {
                      pending.delete(id);
                      reject(new Error("worker call timeout: " + cmd));
                    }, 120000);
                    pending.set(id, {
                      resolve: value => { clearTimeout(timer); resolve(value); },
                      reject: error => { clearTimeout(timer); reject(error); }
                    });
                    worker.postMessage({ id, cmd, payload });
                  });
                  window.__ntvOfficialCmgWorker = { worker, url };
                  return await window.__ntvOfficialCmgCall("init", { mediaTag, wasmB64 });
                }""",
                {
                    "hlsSource": hls_source,
                    "workerSource": worker_source,
                    "mediaTag": media_tag,
                    "wasmB64": wasm_b64,
                },
            )
            print(f"[official] setup {json.dumps(setup_info, ensure_ascii=False)}", flush=True)
            if args.setup_only:
                return []

            results = []
            total = len(payload)
            chunk_size = max(1, args.chunk_size)
            for start in range(0, total, chunk_size):
                end = min(total, start + chunk_size)
                print(f"[official] decoding NAL {start}..{end - 1} of {total}", flush=True)
                chunk_results = page.evaluate(
                    """async ({ nals }) => await window.__ntvOfficialCmgCall("decode", { nals })""",
                    {"nals": payload[start:end]},
                )
                results.extend(chunk_results)
            page.evaluate(
                """async () => {
                  try { await window.__ntvOfficialCmgCall("close", {}); } catch (e) {}
                  if (window.__ntvOfficialCmgWorker) {
                    window.__ntvOfficialCmgWorker.worker.terminate();
                    URL.revokeObjectURL(window.__ntvOfficialCmgWorker.url);
                  }
                }"""
            )
            return results
        finally:
            browser.close()


def write_jsonl(path: Path, records: list) -> None:
    with path.open("w", encoding="utf-8") as fh:
        for record in records:
            slim = dict(record)
            slim.pop("expectedB64", None)
            fh.write(json.dumps(slim, ensure_ascii=False, separators=(",", ":")) + "\n")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("original_ts")
    parser.add_argument("app_ts")
    parser.add_argument("meta")
    parser.add_argument("--hls", default=str(ROOT / "build" / "hls.cmg.js"))
    parser.add_argument("--worker", default=str(ROOT / "build" / "cmg.worker.js"))
    parser.add_argument("--wasm", default=str(ROOT / "build" / "cmg.wasm"))
    parser.add_argument("--out", default=str(ROOT / "build" / "cmg-browser-official-compare.json"))
    parser.add_argument("--jsonl", default=str(ROOT / "build" / "cmg-browser-official-nals.jsonl"))
    parser.add_argument("--headed", action="store_true")
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--chunk-size", type=int, default=16)
    parser.add_argument("--setup-only", action="store_true")
    parser.add_argument("--page-url", default="about:blank")
    args = parser.parse_args()

    print(f"[main] reading TS dumps", flush=True)
    original_ts = Path(args.original_ts).read_bytes()
    app_ts = Path(args.app_ts).read_bytes()
    meta = read_meta(Path(args.meta))
    media_tag = meta.get("playerTag") or "player_container_player"
    print(f"[main] extracting NALs mediaTag={media_tag}", flush=True)
    original_nals = extract_nals(original_ts)
    app_nals = extract_nals(app_ts)
    if args.limit > 0:
        original_nals = original_nals[:args.limit]
        app_nals = app_nals[:args.limit]
    print(f"[main] originalNALs={len(original_nals)} appNALs={len(app_nals)}", flush=True)
    official = build_browser_expected(args, media_tag, original_nals)

    first_mismatch = None
    compared = min(len(official), len(app_nals), len(original_nals))
    changed_expected = 0
    changed_app = 0
    decoded_count = 0
    for index in range(compared):
        original = original_nals[index]
        app = app_nals[index]
        expected = unb64(official[index]["expectedB64"])
        if official[index]["decoded"]:
            decoded_count += 1
        if diff_count(expected, original["body"]) > 0:
            changed_expected += 1
        if diff_count(app["body"], original["body"]) > 0:
            changed_app += 1
        if original["type"] != app["type"] and first_mismatch is None:
            first_mismatch = {
                "reason": "nal-type",
                "index": index,
                "originalType": original["type"],
                "appType": app["type"],
                "originalTsOffset": original["tsOffset"],
                "appTsOffset": app["tsOffset"],
            }
            break
        mismatch_at = first_diff(expected, app["body"])
        if mismatch_at >= 0:
            first_mismatch = {
                "reason": "after-bytes",
                "index": index,
                "type": original["type"],
                "pesIndex": original["pesIndex"],
                "nalIndexInPes": original["nalIndexInPes"],
                "officialMutation": official[index]["officialMutation"],
                "decoded": official[index]["decoded"],
                "beforeVmpTag": official[index]["beforeVmpTag"],
                "activeVmpTag": official[index]["activeVmpTag"],
                "afterVmpTag": official[index]["afterVmpTag"],
                "expectedLength": len(expected),
                "appLength": len(app["body"]),
                "firstDiff": mismatch_at,
                "originalTsOffset": original["tsOffset"] + mismatch_at,
                "appTsOffset": app["tsOffset"] + mismatch_at,
                "officialDiffFromOriginal": diff_count(expected, original["body"]),
                "appDiffFromOriginal": diff_count(app["body"], original["body"]),
                "originalHead": original["body"][max(0, mismatch_at - 16):mismatch_at + 32].hex(),
                "expectedHead": expected[max(0, mismatch_at - 16):mismatch_at + 32].hex(),
                "appHead": app["body"][max(0, mismatch_at - 16):mismatch_at + 32].hex(),
            }
            break

    report = {
        "originalTs": str(Path(args.original_ts)),
        "appTs": str(Path(args.app_ts)),
        "meta": meta,
        "mediaTag": media_tag,
        "originalNalCount": len(original_nals),
        "appNalCount": len(app_nals),
        "officialNalCount": len(official),
        "comparedNalCount": compared,
        "officialDecodedNalCount": decoded_count,
        "changedExpectedNalCount": changed_expected,
        "changedAppNalCount": changed_app,
        "firstMismatch": first_mismatch,
    }
    Path(args.out).write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    write_jsonl(Path(args.jsonl), official)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 2 if first_mismatch else 0


if __name__ == "__main__":
    raise SystemExit(main())
