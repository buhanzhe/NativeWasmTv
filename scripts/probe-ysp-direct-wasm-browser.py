import base64
import json
from pathlib import Path

from playwright.sync_api import sync_playwright


ROOT = Path(__file__).resolve().parents[1]


def main() -> int:
    worker_js = (ROOT / "build" / "cmg.worker.current.js").read_text(encoding="utf-8")
    before_b64 = (ROOT / "build" / "official-cmg-nal-1-before.b64").read_text(encoding="ascii").strip()
    official_after_b64 = (ROOT / "build" / "official-cmg-nal-1-after.b64").read_text(encoding="ascii").strip()
    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True)
        page = browser.new_page()
        page.goto("https://www.yangshipin.cn/tv/home", wait_until="domcontentloaded", timeout=60000)
        result = page.evaluate(
            """async ({ workerJs, beforeB64, officialAfterB64 }) => {
              function fromB64(text) {
                const raw = atob(text);
                const out = new Uint8Array(raw.length);
                for (let i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i);
                return out;
              }
              function sha256Hex(bytes) {
                return crypto.subtle.digest('SHA-256', bytes).then(buf =>
                  Array.from(new Uint8Array(buf)).map(v => v.toString(16).padStart(2, '0')).join('')
                );
              }
              function firstDiff(a, b) {
                const len = Math.min(a.length, b.length);
                for (let i = 0; i < len; i++) if (a[i] !== b[i]) return i;
                return a.length === b.length ? -1 : len;
              }
              function diffCount(a, b) {
                const len = Math.min(a.length, b.length);
                let count = Math.abs(a.length - b.length);
                for (let i = 0; i < len; i++) if (a[i] !== b[i]) count++;
                return count;
              }
              function writeString(module, text, extra = 2048) {
                const bytes = new TextEncoder().encode(text);
                const ptr = module._jsmalloc(bytes.length + 1 + extra);
                module.HEAPU8.fill(0, ptr, ptr + bytes.length + 1 + extra);
                module.HEAPU8.set(bytes, ptr);
                return ptr;
              }
              const script = document.createElement('script');
              script.textContent = workerJs;
              document.head.appendChild(script);
              const module = await self.CMGDecModule();
              const before = fromB64(beforeB64);
              const officialAfter = fromB64(officialAfterB64);
              const playerTag = '1780652630064';
              const host = 'https://www.yangshipin.cn';
              const tagPtr = writeString(module, playerTag);
              const initRet = module._CMG_InitPlayer(tagPtr);
              const update = module._CMG_UpdatePlayer(tagPtr);
              const dataPtr = module._jsmalloc(before.length + host.length + 2048);
              module.HEAPU8.fill(0, dataPtr, dataPtr + before.length + host.length + 2048);
              module.HEAPU8.set(before, dataPtr);
              module.HEAPU8.set(new TextEncoder().encode(host), dataPtr + before.length);
              const outLen = module._CMG_jsdecLive8(tagPtr, dataPtr, before.length, host.length);
              const out = module.HEAPU8.slice(dataPtr, dataPtr + outLen);
              return {
                initRet,
                update: (update >>> 0).toString(16).padStart(8, '0'),
                outLen,
                beforeSha: await sha256Hex(before),
                outSha: await sha256Hex(out),
                officialAfterSha: await sha256Hex(officialAfter),
                nativeEquivalentDiff: diffCount(before, out),
                officialDiff: diffCount(before, officialAfter),
                outVsOfficialFirstDiff: firstDiff(officialAfter, out),
                outVsOfficialDiff: diffCount(officialAfter, out),
                outHead: Array.from(out.slice(0, 64)),
                officialHead: Array.from(officialAfter.slice(0, 64))
              };
            }""",
            {
                "workerJs": worker_js,
                "beforeB64": before_b64,
                "officialAfterB64": official_after_b64,
            },
        )
        browser.close()
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
