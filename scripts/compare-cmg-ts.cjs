const fs = require("fs");
const path = require("path");
const vm = require("vm");

const [
  , ,
  originalPath,
  appPath,
  hlsCmgPath = "build/hls.cmg.js",
  workerPath = "build/cmg.worker.js",
  metaPath = "",
  maxTargetsArg = "0",
  wasmPath = "build/cmg.wasm",
  tracePath = ""
] = process.argv;

if (!originalPath || !appPath) {
  console.error("usage: node scripts/compare-cmg-ts.cjs original.ts app.ts [hls.cmg.js] [cmg.worker.js] [meta.txt] [maxTargets]");
  process.exit(1);
}

function readMeta(file) {
  if (!file || !fs.existsSync(file)) {
    return {};
  }
  const meta = {};
  for (const line of fs.readFileSync(file, "utf8").split(/\r?\n/)) {
    const at = line.indexOf("=");
    if (at > 0) {
      meta[line.slice(0, at)] = line.slice(at + 1);
    }
  }
  return meta;
}

function loadOfficialWrapper(sourcePath, locationHref) {
  const source = fs.readFileSync(sourcePath, "utf8");
  function extractFunction(name) {
    const start = source.indexOf(`function ${name}`);
    if (start < 0) {
      throw new Error(`Unable to locate helper ${name}`);
    }
    const open = source.indexOf("{", start);
    let depth = 0;
    let quote = "";
    let escaped = false;
    for (let index = open; index < source.length; index++) {
      const char = source[index];
      if (quote) {
        if (escaped) {
          escaped = false;
        } else if (char === "\\") {
          escaped = true;
        } else if (char === quote) {
          quote = "";
        }
        continue;
      }
      if (char === "'" || char === '"' || char === "`") {
        quote = char;
      } else if (char === "{") {
        depth++;
      } else if (char === "}") {
        depth--;
        if (depth === 0) {
          return source.slice(start, index + 1);
        }
      }
    }
    throw new Error(`Unclosed helper ${name}`);
  }
  const cut = source.indexOf(";var fI=function");
  if (cut < 0) {
    throw new Error("Unable to locate CMG wrapper cut point");
  }
  const code = `${extractFunction("a0b")}\n${extractFunction("a0a")}\n`
    + source.slice(0, cut)
    + ";return {fG,fg,fh,fj,fk,fm,fp,fq,fu,fv,fw,fx};};globalThis.__cmgWrapper=c();}());";
  const sandbox = {
    __ntvNow: Date.now(),
    console: { log() {}, debug() {}, warn() {}, error() {} },
    setTimeout,
    clearTimeout,
    Promise,
    Uint8Array,
    ArrayBuffer,
    DataView,
    TextDecoder,
    TextEncoder,
    atob: text => Buffer.from(text, "base64").toString("binary"),
    btoa: text => Buffer.from(text, "binary").toString("base64")
  };
  const RealDate = Date;
  function FakeDate(...args) {
    return args.length ? new RealDate(...args) : new RealDate(sandbox.__ntvNow);
  }
  FakeDate.now = () => sandbox.__ntvNow;
  FakeDate.parse = RealDate.parse;
  FakeDate.UTC = RealDate.UTC;
  FakeDate.prototype = RealDate.prototype;
  sandbox.Date = FakeDate;
  sandbox.self = sandbox;
  sandbox.window = sandbox;
  sandbox.globalThis = sandbox;
  sandbox.location = {
    origin: "https://www.yangshipin.cn",
    href: locationHref || "https://www.yangshipin.cn/tv/home",
    protocol: "https:",
    host: "www.yangshipin.cn"
  };
  sandbox.activeURL = "https://www.yangshipin.cn";
  sandbox.document = {
    currentScript: null,
    location: sandbox.location,
    body: {},
    addEventListener() {},
    removeEventListener() {},
    createElement() { return {}; },
    getElementsByTagName() { return []; }
  };
  sandbox.navigator = { userAgent: "Mozilla/5.0" };
  sandbox.URL = { createObjectURL() { return "blob:x"; }, revokeObjectURL() {} };
  sandbox.Worker = function Worker() {};
  sandbox.XMLHttpRequest = function XMLHttpRequest() {};
  sandbox.fetch = function fetch() { return Promise.reject(new Error("fetch disabled")); };
  vm.runInNewContext(code, sandbox, { timeout: 15000 });
  return { sandbox, wrapper: sandbox.__cmgWrapper };
}

function startCodeLength(data, offset) {
  if (offset + 4 <= data.length && data[offset] === 0 && data[offset + 1] === 0
      && data[offset + 2] === 0 && data[offset + 3] === 1) {
    return 4;
  }
  if (offset + 3 <= data.length && data[offset] === 0 && data[offset + 1] === 0
      && data[offset + 2] === 1) {
    return 3;
  }
  return 0;
}

function readPts(data, offset) {
  return (BigInt(data[offset] & 0x0e) << 29n)
    | (BigInt(data[offset + 1]) << 22n)
    | (BigInt(data[offset + 2] & 0xfe) << 14n)
    | (BigInt(data[offset + 3]) << 7n)
    | BigInt((data[offset + 4] & 0xfe) >> 1);
}

function extractPes(ts) {
  const pes = [];
  let current;
  for (let packetOffset = 0; packetOffset + 188 <= ts.length; packetOffset += 188) {
    const packet = ts.subarray(packetOffset, packetOffset + 188);
    if (packet[0] !== 0x47) {
      continue;
    }
    const pid = ((packet[1] & 0x1f) << 8) | packet[2];
    const start = (packet[1] & 0x40) !== 0;
    const adaptationControl = (packet[3] >> 4) & 3;
    if ((adaptationControl & 1) === 0) {
      continue;
    }
    let offset = 4;
    if (adaptationControl & 2) {
      offset += packet[4] + 1;
    }
    if (offset >= 188) {
      continue;
    }
    if (start && offset + 9 <= 188 && packet[offset] === 0 && packet[offset + 1] === 0
        && packet[offset + 2] === 1 && packet[offset + 3] >= 0xe0 && packet[offset + 3] <= 0xef) {
      if (current) {
        pes.push(current);
      }
      const flags = packet[offset + 7];
      const headerLength = packet[offset + 8];
      const timestampOffset = offset + 9;
      current = { pid, dts: 0n, chunks: [], payloadLength: 0, index: pes.length };
      if ((flags & 0xc0) && timestampOffset + 5 <= 188) {
        current.dts = readPts(packet, timestampOffset);
      }
      if ((flags & 0xc0) === 0xc0 && timestampOffset + 10 <= 188) {
        current.dts = readPts(packet, timestampOffset + 5);
      }
      offset += 9 + headerLength;
    }
    if (current && current.pid === pid && offset < 188) {
      const body = packet.subarray(offset);
      current.chunks.push({
        tsOffset: packetOffset + offset,
        pesOffset: current.payloadLength,
        data: body
      });
      current.payloadLength += body.length;
    }
  }
  if (current) {
    pes.push(current);
  }
  return pes;
}

function pesPayload(pes) {
  return Buffer.concat(pes.chunks.map(chunk => chunk.data));
}

function pesToTsOffset(pes, pesOffset) {
  for (const chunk of pes.chunks) {
    if (pesOffset >= chunk.pesOffset && pesOffset < chunk.pesOffset + chunk.data.length) {
      return chunk.tsOffset + (pesOffset - chunk.pesOffset);
    }
  }
  return -1;
}

function splitAnnexB(pes) {
  const data = pesPayload(pes);
  const starts = [];
  for (let offset = 0; offset < data.length - 2; offset++) {
    const prefixLength = startCodeLength(data, offset);
    if (prefixLength) {
      starts.push({ offset, prefixLength });
      offset += prefixLength - 1;
    }
  }
  return starts.map((start, index) => {
    const end = index + 1 < starts.length ? starts[index + 1].offset : data.length;
    const bodyStart = start.offset + start.prefixLength;
    const body = data.subarray(bodyStart, end);
    return {
      globalIndex: -1,
      pesIndex: pes.index,
      nalIndexInPes: index,
      type: body.length ? (body[0] & 31) : -1,
      prefixLength: start.prefixLength,
      pesOffset: start.offset,
      bodyPesOffset: bodyStart,
      tsOffset: pesToTsOffset(pes, bodyStart),
      body
    };
  });
}

function extractNals(ts) {
  const nals = [];
  for (const pes of extractPes(ts)) {
    for (const nal of splitAnnexB(pes)) {
      nal.globalIndex = nals.length;
      nals.push(nal);
    }
  }
  return nals;
}

function firstDiff(left, right) {
  const length = Math.min(left.length, right.length);
  for (let index = 0; index < length; index++) {
    if (left[index] !== right[index]) {
      return index;
    }
  }
  return left.length === right.length ? -1 : length;
}

function diffCount(left, right) {
  const length = Math.min(left.length, right.length);
  let diff = Math.abs(left.length - right.length);
  for (let index = 0; index < length; index++) {
    if (left[index] !== right[index]) {
      diff++;
    }
  }
  return diff;
}

function headHex(bytes, offset = 0, length = 32) {
  return Buffer.from(bytes.subarray(offset, Math.min(bytes.length, offset + length))).toString("hex");
}

function loadWorkerFactory(workerPath, traceImports) {
  const resolved = path.resolve(workerPath);
  if (!traceImports) {
    return require(resolved);
  }
  let source = fs.readFileSync(resolved, "utf8");
  const marker = "d:eb},asm=Module.asm";
  const replacement = "d:eb},__ntvWrapImports=(Module.__traceImports&&Object.keys(asmLibraryArg).forEach(function(k){var f=asmLibraryArg[k];if(typeof f==='function')asmLibraryArg[k]=function(){var a=Array.prototype.slice.call(arguments),r;try{r=f.apply(this,arguments)}catch(e){Module.__traceImports(k,a,undefined,e&&e.message?e.message:String(e));throw e}Module.__traceImports(k,a,r);return r}})),asm=Module.asm";
  if (!source.includes(marker)) {
    throw new Error("Unable to patch CMG worker import table");
  }
  source = source.replace(marker, replacement);
  const moduleObject = { exports: {} };
  const wrapped = new vm.Script(
    `(function(exports, require, module, __filename, __dirname) { ${source}\n})`,
    { filename: resolved }
  ).runInThisContext();
  wrapped(moduleObject.exports, require, moduleObject, resolved, path.dirname(resolved));
  return moduleObject.exports;
}

async function main() {
  console.error("[compare] reading dumps");
  const meta = readMeta(metaPath);
  const mediaTagId = meta.playerTag || "player_container_player";
  const originalTs = fs.readFileSync(originalPath);
  const appTs = fs.readFileSync(appPath);
  const originalNals = extractNals(originalTs);
  const appNals = extractNals(appTs);
  const callTracePath = process.env.CMG_CALLTRACE || "";
  const importTracePath = process.env.CMG_IMPORTTRACE || "";
  const callTraceRows = [];
  const importTraceRows = [];
  let callTraceIndex = -1;
  let callTraceMallocs = [];
  let callTraceFrees = [];
  let callTraceCalls = [];
  let importTraceCalls = [];
  console.error(`[compare] nals original=${originalNals.length} app=${appNals.length} mediaTag=${mediaTagId}`);
  console.error("[compare] loading worker module");
  const savedArgv = process.argv;
  process.argv = process.argv.slice(0, 2);
  const requireWorker = loadWorkerFactory(workerPath, !!importTracePath);
  const { module } = await new Promise((resolve, reject) => {
    let instance;
    const timer = setTimeout(() => reject(new Error("CMGDecModule runtime init timeout")), 30000);
    const ready = () => {
      if (instance && instance.calledRun) {
        clearTimeout(timer);
        resolve({ module: instance });
        return true;
      }
      return false;
    };
    try {
      instance = requireWorker({
        arguments: [],
        noInitialRun: true,
        __traceImports(name, args, result, thrown) {
          if (callTraceIndex !== -1) {
            importTraceCalls.push({
              name,
              args,
              result,
              throw: thrown || undefined
            });
          }
        },
        print() {},
        printErr() {},
        monitorRunDependencies(count) {
          console.error(`[compare] worker deps ${count} calledRun=${instance && instance.calledRun}`);
          ready();
        },
        onRuntimeInitialized() {
          console.error(`[compare] runtime callback calledRun=${instance && instance.calledRun}`);
          ready();
        }
      });
      console.error("[compare] worker factory returned");
      let pollCount = 0;
      const poll = setInterval(() => {
        pollCount++;
        if (pollCount % 100 === 0) {
          console.error(`[compare] worker poll calledRun=${instance && instance.calledRun}`);
        }
        if (ready()) {
          clearInterval(poll);
        }
      }, 10);
    } catch (error) {
      clearTimeout(timer);
      reject(error);
    }
  });
  process.argv = savedArgv;
  console.error("[compare] worker ready");
  console.error("[compare] loading official wrapper");
  const cmgActiveURL = meta.activeURL || "https://www.yangshipin.cn";
  const { sandbox, wrapper } = loadOfficialWrapper(hlsCmgPath, meta.locationHref || "");
  const fG = wrapper.fG;
  const initTimeMs = Number(meta.initTimeMs) || 0;
  const updateBaseTimeMs = Number(meta.updateBaseTimeMs) || 0;
  const updateTraceEntries = (meta.updateTrace || "")
    .split(";")
    .map(entry => entry.split(",", -1))
    .filter(parts => parts.length > 0 && parts[0] !== "")
    .map(parts => ({ delta: Number(parts[0]) || 0, tag: parts[1] || "" }));
  let updateTraceIndex = 0;
  function setNowForInit() {
    if (initTimeMs > 0) {
      sandbox.__ntvNow = initTimeMs;
    }
  }
  function setNowForUpdate() {
    let traceEntry = null;
    if (updateBaseTimeMs > 0 && updateTraceIndex < updateTraceEntries.length) {
      traceEntry = updateTraceEntries[updateTraceIndex];
      sandbox.__ntvNow = updateBaseTimeMs + traceEntry.delta;
    }
    updateTraceIndex++;
    return traceEntry;
  }
  sandbox.activeURL = cmgActiveURL;
  if (callTracePath) {
    for (const name of ["_jsmalloc", "_jsfree", "_CMG_UpdatePlayer", "_CMG_InitPlayer",
        "_CMG_jsdecLive0", "_CMG_jsdecLive1", "_CMG_jsdecLive2", "_CMG_jsdecLive3",
        "_CMG_jsdecLive4", "_CMG_jsdecLive5", "_CMG_jsdecLive6", "_CMG_jsdecLive7",
        "_CMG_jsdecLive8"]) {
      const original = module[name];
      if (typeof original !== "function") {
        continue;
      }
      module[name] = function wrappedCmgCall(...args) {
        const topBefore = module.HEAP32 ? module.HEAP32[17904 >> 2] : null;
        const firstArgHead = args[0] && module.HEAPU8
          ? headHex(Buffer.from(module.HEAPU8.slice(args[0], args[0] + 32)), 0, 32)
          : "";
        let result;
        try {
          result = original.apply(this, args);
        } catch (error) {
          if (callTraceIndex !== -1) {
            callTraceCalls.push({
              name,
              args,
              throw: error && error.message ? error.message : String(error),
              topBefore,
              topAfter: module.HEAP32 ? module.HEAP32[17904 >> 2] : null,
              firstArgHead
            });
          }
          throw error;
        }
        const topAfter = module.HEAP32 ? module.HEAP32[17904 >> 2] : null;
        if (callTraceIndex !== -1) {
          if (name === "_jsmalloc") {
            callTraceMallocs.push({ size: args[0], result });
          } else if (name === "_jsfree") {
            callTraceFrees.push(args[0]);
          } else {
            callTraceCalls.push({ name, args, result, topBefore, topAfter, firstArgHead });
          }
        }
        return result;
      };
    }
  }
  console.error("[compare] InitPlayer");
  callTraceIndex = callTracePath ? -2 : -1;
  callTraceMallocs = [];
  callTraceFrees = [];
  callTraceCalls = [];
  importTraceCalls = [];
  setNowForInit();
  fG.moduleActive(module, mediaTagId, fG.INITPLAYER);
  if (callTracePath) {
    callTraceRows.push({
      index: -2,
      type: "init",
      length: 0,
      decoded: false,
      liveDecodeEnabled: false,
      officialDiffFromOriginal: 0,
      beforeVmpTag: null,
      activeResult: null,
      activeVmpTag: sandbox.vmpTag || null,
      afterVmpTag: sandbox.vmpTag || null,
      mallocs: callTraceMallocs,
      frees: callTraceFrees,
      calls: callTraceCalls
      ,
      dynamicTop: module.HEAP32 ? module.HEAP32[17904 >> 2] : null
    });
    callTraceIndex = -1;
  }
  if (importTracePath) {
    importTraceRows.push({
      index: -2,
      type: "init",
      imports: importTraceCalls
    });
  }

  const maxTargets = Number(maxTargetsArg) || 0;
  let targets = 0;
  let decodedCount = 0;
  let changedExpected = 0;
  let changedApp = 0;
  let firstMismatch = null;
  let lastVmpTag = null;
  let liveDecodeEnabled = false;
  const trace = [];

  const total = Math.min(originalNals.length, appNals.length);
  for (let index = 0; index < total; index++) {
    if (index % 25 === 0) {
      console.error(`[compare] nal ${index}/${total}`);
    }
    const original = originalNals[index];
    const app = appNals[index];
    callTraceIndex = callTracePath ? index : -1;
    callTraceMallocs = [];
    callTraceFrees = [];
    callTraceCalls = [];
    importTraceCalls = [];
    if (original.type !== app.type) {
      firstMismatch = {
        reason: "nal-sequence-type",
        index,
        originalType: original.type,
        appType: app.type,
        originalPes: original.pesIndex,
        appPes: app.pesIndex,
        originalTsOffset: original.tsOffset,
        appTsOffset: app.tsOffset
      };
      break;
    }

    let expected = original.body;
    let decoded = false;
    let officialMutation = "none";
    let activeResult = null;
    let beforeVmpTag = sandbox.vmpTag || null;
    let activeVmpTag = null;
    let officialDiff = 0;
    const officialUpdateTraceEntry = setNowForUpdate();
    activeResult = fG.moduleActive(module, mediaTagId, fG.UPDATEPLAYER);
    if (officialUpdateTraceEntry && officialUpdateTraceEntry.tag) {
      sandbox.vmpTag = officialUpdateTraceEntry.tag;
    }
    activeVmpTag = sandbox.vmpTag || null;
    lastVmpTag = sandbox.vmpTag || lastVmpTag;
    if (original.type === 7) {
      sandbox.activeURL = cmgActiveURL;
      const before = new Uint8Array(original.body);
      const moduleResult = fG.moduleDecData(module, mediaTagId, before, fG.LIVEVIDEO);
      decoded = true;
      decodedCount++;
      if (!liveDecodeEnabled && original.body.length > 2) {
        const bits = original.body[2] & 3;
        liveDecodeEnabled = bits === 1 || bits === 2;
      }
      expected = Buffer.from(before);
      officialMutation = "sps-moduleDecData";
      officialDiff = diffCount(expected, original.body);
      if (moduleResult && moduleResult.length !== before.length) {
        officialMutation += ` moduleLen=${moduleResult.length}`;
      }
    } else if ((original.type === 1 || original.type === 5) && liveDecodeEnabled) {
      targets++;
      sandbox.activeURL = cmgActiveURL;
      const result = fG.moduleDecData(module, mediaTagId, new Uint8Array(original.body), fG.LIVEVIDEO);
      expected = Buffer.from(result);
      decoded = true;
      decodedCount++;
      officialMutation = "moduleDecData";
      officialDiff = diffCount(expected, original.body);
    } else if (original.type === 1 || original.type === 5) {
      targets++;
    }
    if (officialDiff > 0) {
      changedExpected++;
    }
    if (diffCount(app.body, original.body) > 0) {
      changedApp++;
    }
    if (tracePath) {
      trace.push({
        index,
        type: original.type,
        pesIndex: original.pesIndex,
        nalIndexInPes: original.nalIndexInPes,
        length: original.body.length,
        activeResult,
        beforeVmpTag,
        activeVmpTag,
        afterVmpTag: sandbox.vmpTag || null,
        decoded,
        liveDecodeEnabled,
        officialMutation,
        officialDiffFromOriginal: officialDiff,
        appDiffFromOriginal: diffCount(app.body, original.body),
        firstAppDiff: firstDiff(app.body, original.body),
        originalB64: Buffer.from(original.body).toString("base64"),
        expectedB64: Buffer.from(expected).toString("base64")
      });
    }
    if (callTracePath) {
      callTraceRows.push({
        index,
        type: original.type,
        length: original.body.length,
        decoded,
        liveDecodeEnabled,
        officialDiffFromOriginal: officialDiff,
        beforeVmpTag,
        activeResult,
        activeVmpTag,
        afterVmpTag: sandbox.vmpTag || null,
        mallocs: callTraceMallocs,
        frees: callTraceFrees,
        calls: callTraceCalls,
        dynamicTop: module.HEAP32 ? module.HEAP32[17904 >> 2] : null,
        expectedHead64: headHex(Buffer.from(expected), 0, 64)
      });
    }
    if (importTracePath) {
      importTraceRows.push({
        index,
        type: original.type,
        length: original.body.length,
        decoded,
        activeVmpTag,
        imports: importTraceCalls
      });
    }

    const mismatchAt = firstDiff(expected, app.body);
    if (mismatchAt >= 0) {
      firstMismatch = {
        reason: "after-bytes",
        index,
        type: original.type,
        pesIndex: original.pesIndex,
        nalIndexInPes: original.nalIndexInPes,
        officialMutation,
        decoded,
        liveDecodeEnabled,
        activeResult,
        beforeVmpTag,
        activeVmpTag,
        afterVmpTag: sandbox.vmpTag || null,
        expectedLength: expected.length,
        appLength: app.body.length,
        firstDiff: mismatchAt,
        originalTsOffset: original.tsOffset + mismatchAt,
        appTsOffset: app.tsOffset + mismatchAt,
        officialDiffFromOriginal: officialDiff,
        appDiffFromOriginal: diffCount(app.body, original.body),
        originalHead: headHex(original.body, Math.max(0, mismatchAt - 16)),
        expectedHead: headHex(expected, Math.max(0, mismatchAt - 16)),
        appHead: headHex(app.body, Math.max(0, mismatchAt - 16))
      };
      break;
    }

    if (maxTargets > 0 && targets >= maxTargets) {
      break;
    }
  }

  fG.moduleActive(module, mediaTagId, fG.UNINITPLAYER);
  callTraceIndex = -1;

  const report = {
    originalPath,
    appPath,
    mediaTagId,
    meta,
    originalNalCount: originalNals.length,
    appNalCount: appNals.length,
    comparedNalCount: total,
    comparedVclNals: targets,
    officialDecodedNalCount: decodedCount,
    changedExpectedNalCount: changedExpected,
    changedAppNalCount: changedApp,
    activeURL: cmgActiveURL,
    lastVmpTag,
    firstMismatch
  };
  console.log(JSON.stringify(report, null, 2));
  if (tracePath) {
    fs.writeFileSync(tracePath, trace.map(item => JSON.stringify(item)).join("\n") + "\n");
  }
  if (callTracePath) {
    fs.writeFileSync(callTracePath, callTraceRows.map(item => JSON.stringify(item)).join("\n") + "\n");
  }
  if (importTracePath) {
    fs.writeFileSync(importTracePath, importTraceRows.map(item => JSON.stringify(item)).join("\n") + "\n");
  }
  if (firstMismatch) {
    process.exitCode = 2;
  }
}

main().catch(error => {
  console.error(error && error.stack ? error.stack : String(error));
  process.exit(1);
});
