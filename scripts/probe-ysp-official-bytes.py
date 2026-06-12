import base64
import json
import os
import re
import sys
import time
from pathlib import Path

from playwright.sync_api import Error, TimeoutError, sync_playwright


ROOT = Path(__file__).resolve().parents[1]
OUT_DIR = ROOT / "build"
BYTES_OUT = OUT_DIR / "official-cmg-bytes.jsonl"
SUMMARY_OUT = OUT_DIR / "official-cmg-summary.jsonl"
ACTIVE_OUT = OUT_DIR / "official-cmg-active.jsonl"
SEGMENT_OUT = OUT_DIR / "official-first-segment.ts"


HLS_HOOK = r"""
;(function() {
  try {
    if (!fG || !fG.moduleDecData || fG.__ntvBytesHooked) return;
    fG.__ntvBytesHooked = true;
    var originalModuleDecData = fG.moduleDecData;
    var originalModuleActive = fG.moduleActive;
    var fullCount = 0;
    var summaryCount = 0;
    var activeCount = 0;
    var maxFull = 4;
    var maxSummary = 80;
    var maxActive = 120;

    function b64(bytes) {
      var chunk = 0x8000;
      var parts = [];
      for (var i = 0; i < bytes.length; i += chunk) {
        var s = "";
        var end = Math.min(i + chunk, bytes.length);
        for (var j = i; j < end; j++) s += String.fromCharCode(bytes[j]);
        parts.push(btoa(s));
      }
      if (parts.length === 1) return parts[0];
      var raw = "";
      for (var p = 0; p < parts.length; p++) raw += atob(parts[p]);
      return btoa(raw);
    }

    function head(bytes, n) {
      var out = [];
      var len = Math.min(bytes.length, n);
      for (var i = 0; i < len; i++) out.push(bytes[i]);
      return out;
    }

    function firstDiff(a, b) {
      var len = Math.min(a.length, b.length);
      for (var i = 0; i < len; i++) {
        if (a[i] !== b[i]) return i;
      }
      return a.length === b.length ? -1 : len;
    }

    function diffCount(a, b) {
      var len = Math.min(a.length, b.length);
      var diff = Math.abs(a.length - b.length);
      for (var i = 0; i < len; i++) {
        if (a[i] !== b[i]) diff++;
      }
      return diff;
    }

    fG.moduleDecData = function(module, mediaTagId, data, mediaType) {
      var before = new Uint8Array(data);
      var beforeVmpTag = self.vmpTag || null;
      var result = originalModuleDecData.apply(this, arguments);
      var afterVmpTag = self.vmpTag || null;
      var after = result ? new Uint8Array(result) : new Uint8Array(0);
      var nalType = before.length ? (before[0] & 31) : -1;
      var record = {
        source: "hls.cmg.js/moduleDecData",
        time: Date.now(),
        mediaTagId: mediaTagId,
        mediaType: mediaType,
        activeURL: self.activeURL || null,
        vmpTag: afterVmpTag,
        beforeVmpTag: beforeVmpTag,
        afterVmpTag: afterVmpTag,
        beforeLen: before.length,
        afterLen: after.length,
        nalType: nalType,
        beforeHead: head(before, 48),
        afterHead: head(after, 48),
        firstDiff: firstDiff(before, after),
        diffCount: diffCount(before, after)
      };
      if (summaryCount < maxSummary) {
        console.log("__CMG_DEC_SUMMARY__" + JSON.stringify(record));
        summaryCount++;
      }
      if ((nalType === 1 || nalType === 5) && before.length >= 1000 && fullCount < maxFull) {
        record.beforeB64 = b64(before);
        record.afterB64 = b64(after);
        console.log("__CMG_DEC_BYTES__" + JSON.stringify(record));
        fullCount++;
      }
      return result;
    };
    fG.moduleActive = function(module, mediaTagId, mode) {
      var beforeVmpTag = self.vmpTag || null;
      var result = originalModuleActive.apply(this, arguments);
      var afterVmpTag = self.vmpTag || null;
      if (activeCount < maxActive) {
        console.log("__CMG_ACTIVE__" + JSON.stringify({
          time: Date.now(),
          mediaTagId: mediaTagId,
          mode: mode,
          result: result,
          beforeVmpTag: beforeVmpTag,
          afterVmpTag: afterVmpTag
        }));
        activeCount++;
      }
      return result;
    };
    console.log("__CMG_HOOK_READY__moduleDecData");
  } catch (e) {
    console.log("__CMG_HOOK_ERROR__" + (e && e.stack ? e.stack : String(e)));
  }
})();
"""


WORKER_HOOK = r"""
;(function() {
  try {
    if (!self.CMGDecModule || self.CMGDecModule.__ntvHooked) return;
    var originalFactory = self.CMGDecModule;
    function hookModule(module) {
      if (!module || module.__ntvNativeHooked) return module;
      module.__ntvNativeHooked = true;
      var original = module._CMG_jsdecLive8;
      if (typeof original === "function") {
        module._CMG_jsdecLive8 = function(tagPtr, dataPtr, length, hostLength) {
          var before = module.HEAPU8.slice(dataPtr, dataPtr + Math.max(0, length));
          var ret = original.apply(this, arguments);
          var afterLen = Math.max(0, Math.min(ret || length || 0, before.length));
          var after = module.HEAPU8.slice(dataPtr, dataPtr + afterLen);
          if (!module.__ntvLive8Count) module.__ntvLive8Count = 0;
          if (module.__ntvLive8Count < 12) {
            var host = "";
            try {
              if (hostLength > 0) {
                host = new TextDecoder("utf-8").decode(module.HEAPU8.slice(dataPtr + length, dataPtr + length + hostLength));
              }
            } catch (e) {}
            var out = {
              source: "cmg.worker.js/_CMG_jsdecLive8",
              time: Date.now(),
              length: length,
              ret: ret,
              hostLength: hostLength,
              host: host,
              nalType: before.length ? (before[0] & 31) : -1,
              beforeHead: Array.from(before.slice(0, 32)),
              afterHead: Array.from(after.slice(0, 32))
            };
            console.log("__CMG_LIVE8_SUMMARY__" + JSON.stringify(out));
          }
          module.__ntvLive8Count++;
          return ret;
        };
      }
      console.log("__CMG_HOOK_READY__Live8");
      return module;
    }
    self.CMGDecModule = function() {
      var instance = originalFactory.apply(this, arguments);
      if (instance && typeof instance.then === "function") {
        return instance.then(hookModule);
      }
      return hookModule(instance);
    };
    self.CMGDecModule.__ntvHooked = true;
  } catch (e) {
    console.log("__CMG_WORKER_HOOK_ERROR__" + (e && e.stack ? e.stack : String(e)));
  }
})();
"""


def patch_hls(source: str) -> str:
    marker = ";var fI=function"
    if marker not in source:
        return source + HLS_HOOK
    return source.replace(marker, HLS_HOOK + marker, 1)


def patch_worker(source: str) -> str:
    return source + WORKER_HOOK


def write_jsonl(path: Path, payload: dict) -> None:
    with path.open("a", encoding="utf-8") as fh:
        fh.write(json.dumps(payload, ensure_ascii=False, separators=(",", ":")) + "\n")


def try_click_cctv13(page) -> None:
    candidates = [
        "CCTV-13",
        "CCTV13",
        "新闻",
    ]
    for text in candidates:
        try:
            locator = page.get_by_text(text, exact=False).first
            locator.click(timeout=5000)
            print(f"clicked channel candidate: {text}", flush=True)
            return
        except (Error, TimeoutError):
            pass
    try:
        page.evaluate(
            """() => {
              const els = Array.from(document.querySelectorAll('*'));
              const el = els.find(e => /CCTV-?13|新闻/.test((e.innerText || '').trim()));
              if (el) el.click();
            }"""
        )
        print("clicked channel candidate by DOM scan", flush=True)
    except Error as exc:
        print(f"channel click failed: {exc}", flush=True)


def main() -> int:
    OUT_DIR.mkdir(exist_ok=True)
    for path in (BYTES_OUT, SUMMARY_OUT, ACTIVE_OUT):
        if path.exists():
            path.unlink()
    if SEGMENT_OUT.exists():
        SEGMENT_OUT.unlink()

    captured = {"full": 0, "summary": 0, "segment": False}
    headed = "--headless" not in sys.argv
    max_full = int(os.environ.get("CMG_MAX_FULL", "4"))
    extra_seconds = int(os.environ.get("CMG_EXTRA_SECONDS", "10"))
    pid = os.environ.get("CMG_PID", "")

    with sync_playwright() as pw:
        browser = pw.chromium.launch(
            headless=not headed,
            args=[
                "--autoplay-policy=no-user-gesture-required",
                "--disable-blink-features=AutomationControlled",
                "--disable-web-security",
            ],
        )
        context = browser.new_context(
            viewport={"width": 1365, "height": 768},
            ignore_https_errors=True,
            user_agent=(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                "AppleWebKit/537.36 (KHTML, like Gecko) "
                "Chrome/148.0.0.0 Safari/537.36"
            ),
        )

        def route_hls(route):
            response = route.fetch()
            body = response.text()
            patched = patch_hls(body)
            patched = patched.replace("var maxFull = 4;", "var maxFull = %d;" % max_full, 1)
            route.fulfill(
                response=response,
                body=patched,
                headers={**response.headers, "content-type": "application/javascript"},
            )

        def route_worker(route):
            response = route.fetch()
            body = response.text()
            patched = patch_worker(body)
            route.fulfill(
                response=response,
                body=patched,
                headers={**response.headers, "content-type": "application/javascript"},
            )

        context.route(re.compile(r".*/hls\.cmg\.js.*"), route_hls)
        context.route(re.compile(r".*/cmg\.worker\.js.*"), route_worker)

        page = context.new_page()

        def on_console(message):
            text = message.text
            if text.startswith("__CMG_DEC_BYTES__"):
                payload = json.loads(text[len("__CMG_DEC_BYTES__") :])
                write_jsonl(BYTES_OUT, payload)
                captured["full"] += 1
                print(
                    "full bytes "
                    f"#{captured['full']} type={payload.get('nalType')} "
                    f"len={payload.get('beforeLen')} diff={payload.get('diffCount')} "
                    f"firstDiff={payload.get('firstDiff')}",
                    flush=True,
                )
            elif text.startswith("__CMG_DEC_SUMMARY__"):
                payload = json.loads(text[len("__CMG_DEC_SUMMARY__") :])
                write_jsonl(SUMMARY_OUT, payload)
                captured["summary"] += 1
            elif text.startswith("__CMG_ACTIVE__"):
                payload = json.loads(text[len("__CMG_ACTIVE__") :])
                write_jsonl(ACTIVE_OUT, payload)
                print(
                    f"active mode={payload.get('mode')} result={payload.get('result')} "
                    f"before={payload.get('beforeVmpTag')} after={payload.get('afterVmpTag')}",
                    flush=True,
                )
            elif text.startswith("__CMG_"):
                print(text[:500], flush=True)

        page.on("console", on_console)

        def on_response(response):
            if captured["segment"]:
                return
            url = response.url
            if ".ts" not in url.lower():
                return
            try:
                body = response.body()
                if body and len(body) >= 188:
                    SEGMENT_OUT.write_bytes(body)
                    captured["segment"] = True
                    print(f"saved first ts segment: {len(body)} bytes {url}", flush=True)
            except Error:
                pass

        page.on("response", on_response)

        page_url = "https://www.yangshipin.cn/tv/home"
        if pid:
            page_url += "?pid=" + pid
        print("opening official YSP page...", page_url, flush=True)
        page.goto(page_url, wait_until="domcontentloaded", timeout=60000)
        page.wait_for_timeout(5000)
        if not pid:
            try_click_cctv13(page)
            page.wait_for_timeout(1000)
        try:
            page.mouse.click(680, 360)
        except Error:
            pass

        deadline = time.time() + 120
        while time.time() < deadline and captured["full"] < 1:
            page.wait_for_timeout(1000)
        extra_deadline = time.time() + extra_seconds
        while captured["full"] > 0 and captured["full"] < max_full and time.time() < extra_deadline:
            page.wait_for_timeout(500)

        browser.close()

    print(
        f"done: full={captured['full']} summary={captured['summary']} "
        f"bytes={BYTES_OUT} summaryFile={SUMMARY_OUT} segment={SEGMENT_OUT if captured['segment'] else 'none'}",
        flush=True,
    )
    return 0 if captured["full"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
