const fs = require("fs");
const path = require("path");
const vm = require("vm");

const [
  , ,
  originalPath,
  hlsCmgPath = "build/hls.cmg.js",
  workerPath = "build/cmg.worker.js",
  metaPath = "build/cmg-debug-official-tag/seg-001-meta.txt",
  maxIndexArg = "71",
  outPath = "build/cmg-official-calltrace.jsonl"
] = process.argv;

if (!originalPath) {
  console.error("usage: node scripts/cmg-official-calltrace.cjs original.ts [hls.cmg.js] [cmg.worker.js] [meta.txt] [maxIndex] [out.jsonl]");
  process.exit(1);
}

function readMeta(file) {
  const meta = {};
  if (!file || !fs.existsSync(file)) {
    return meta;
  }
  for (const line of fs.readFileSync(file, "utf8").split(/\r?\n/)) {
    const at = line.indexOf("=");
    if (at > 0) {
      meta[line.slice(0, at)] = line.slice(at + 1);
    }
  }
  return meta;
}

function loadOfficialWrapper(sourcePath) {
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
      if (char === "'" || char === "\"" || char === "`") {
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
    + ";return {fG};};globalThis.__cmgWrapper=c();}());";
  const sandbox = {
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
  sandbox.self = sandbox;
  sandbox.window = sandbox;
  sandbox.globalThis = sandbox;
  sandbox.location = {
    origin: "https://www.yangshipin.cn",
    href: "https://www.yangshipin.cn/tv/home",
    protocol: "https:",
    host: "www.yangshipin.cn"
  };
  sandbox.activeURL = "https://www.yangshipin.cn/tv/home";
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
  return { sandbox, fG: sandbox.__cmgWrapper.fG };
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
      const headerLength = packet[offset + 8];
      current = { pid, chunks: [], payloadLength: 0, index: pes.length };
      offset += 9 + headerLength;
    }
    if (current && current.pid === pid && offset < 188) {
      const body = packet.subarray(offset);
      current.chunks.push({ pesOffset: current.payloadLength, data: body });
      current.payloadLength += body.length;
    }
  }
  if (current) {
    pes.push(current);
  }
  return pes;
}

function extractNals(ts) {
  const nals = [];
  for (const pes of extractPes(ts)) {
    const data = Buffer.concat(pes.chunks.map(chunk => chunk.data));
    const starts = [];
    for (let offset = 0; offset < data.length - 2; offset++) {
      const prefixLength = startCodeLength(data, offset);
      if (prefixLength) {
        starts.push({ offset, prefixLength });
        offset += prefixLength - 1;
      }
    }
    for (let index = 0; index < starts.length; index++) {
      const start = starts[index];
      const end = index + 1 < starts.length ? starts[index + 1].offset : data.length;
      const body = data.subarray(start.offset + start.prefixLength, end);
      nals.push({ index: nals.length, type: body.length ? (body[0] & 31) : -1, body });
    }
  }
  return nals;
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

function headHex(bytes, offset = 0, length = 16) {
  return Buffer.from(bytes.subarray(offset, Math.min(bytes.length, offset + length))).toString("hex");
}

async function loadWorker(workerPath) {
  const savedArgv = process.argv;
  process.argv = process.argv.slice(0, 2);
  const requireWorker = require(path.resolve(workerPath));
  let instance;
  instance = requireWorker({
    arguments: [],
    noInitialRun: true,
    print() {},
    printErr() {},
    monitorRunDependencies(count) {
      console.error(`[calltrace] worker deps ${count} calledRun=${instance && instance.calledRun}`);
    },
    onRuntimeInitialized() {
      console.error(`[calltrace] runtime callback calledRun=${instance && instance.calledRun}`);
    }
  });
  console.error("[calltrace] worker factory returned");
  for (let count = 0; count < 3000 && !instance.calledRun; count++) {
    if (count > 0 && count % 100 === 0) {
      console.error(`[calltrace] worker poll calledRun=${instance && instance.calledRun}`);
    }
    await new Promise(resolve => setTimeout(resolve, 10));
  }
  if (!instance.calledRun) {
    throw new Error("CMGDecModule runtime init timeout");
  }
  process.argv = savedArgv;
  return instance;
}

async function main() {
  console.error("[calltrace] extract nals");
  const nals = extractNals(fs.readFileSync(originalPath));
  const mediaTag = readMeta(metaPath).playerTag || "player_container_player";
  const maxIndex = Number(maxIndexArg);
  console.error("[calltrace] load worker");
  const module = await loadWorker(workerPath);
  console.error("[calltrace] load wrapper");
  const { sandbox, fG } = loadOfficialWrapper(hlsCmgPath);
  console.error("[calltrace] install wrappers");
  const rows = [];
  let currentIndex = -1;
  let mallocs = [];
  let frees = [];
  let calls = [];
  for (const name of ["_jsmalloc", "_jsfree", "_CMG_UpdatePlayer", "_CMG_InitPlayer",
      "_CMG_jsdecLive0", "_CMG_jsdecLive1", "_CMG_jsdecLive2", "_CMG_jsdecLive3",
      "_CMG_jsdecLive4", "_CMG_jsdecLive5", "_CMG_jsdecLive6", "_CMG_jsdecLive7",
      "_CMG_jsdecLive8"]) {
    const original = module[name];
    if (typeof original !== "function") {
      continue;
    }
    module[name] = function wrapped(...args) {
      const result = original.apply(this, args);
      if (currentIndex >= 0) {
        if (name === "_jsmalloc") {
          mallocs.push({ size: args[0], result });
        } else if (name === "_jsfree") {
          frees.push(args[0]);
        } else {
          calls.push({ name, args, result });
        }
      }
      return result;
    };
  }
  console.error("[calltrace] init player");
  fG.moduleActive(module, mediaTag, fG.INITPLAYER);
  let liveDecodeEnabled = false;
  for (const nal of nals) {
    if (nal.index > maxIndex) {
      break;
    }
    currentIndex = nal.index;
    if (nal.index % 10 === 0) {
      console.error(`[calltrace] nal ${nal.index}`);
    }
    mallocs = [];
    frees = [];
    calls = [];
    const beforeVmpTag = sandbox.vmpTag || "";
    const activeResult = fG.moduleActive(module, mediaTag, fG.UPDATEPLAYER);
    const activeVmpTag = sandbox.vmpTag || "";
    let decoded = false;
    let expected = nal.body;
    if (nal.type === 7) {
      const before = new Uint8Array(nal.body);
      const result = fG.moduleDecData(module, mediaTag, before, fG.LIVEVIDEO);
      decoded = true;
      if (!liveDecodeEnabled && nal.body.length > 2) {
        const bits = nal.body[2] & 3;
        liveDecodeEnabled = bits === 1 || bits === 2;
      }
      expected = Buffer.from(before);
      rows.push({
        index: nal.index,
        type: nal.type,
        length: nal.body.length,
        beforeVmpTag,
        activeResult,
        activeVmpTag,
        decoded,
        liveDecodeEnabled,
        diff: diffCount(expected, nal.body),
        mallocs,
        frees,
        calls,
        expectedHead64: headHex(expected, 0, 64)
      });
    } else if ((nal.type === 1 || nal.type === 5) && liveDecodeEnabled) {
      const result = fG.moduleDecData(module, mediaTag, new Uint8Array(nal.body), fG.LIVEVIDEO);
      expected = Buffer.from(result);
      decoded = true;
      rows.push({
        index: nal.index,
        type: nal.type,
        length: nal.body.length,
        beforeVmpTag,
        activeResult,
        activeVmpTag,
        decoded,
        liveDecodeEnabled,
        diff: diffCount(expected, nal.body),
        mallocs,
        frees,
        calls,
        expectedHead64: headHex(expected, 0, 64)
      });
    } else {
      rows.push({
        index: nal.index,
        type: nal.type,
        length: nal.body.length,
        beforeVmpTag,
        activeResult,
        activeVmpTag,
        decoded,
        liveDecodeEnabled,
        diff: 0,
        mallocs,
        frees,
        calls
      });
    }
  }
  currentIndex = -1;
  fG.moduleActive(module, mediaTag, fG.UNINITPLAYER);
  fs.writeFileSync(outPath, rows.map(row => JSON.stringify(row)).join("\n") + "\n");
  console.error(`wrote ${rows.length} rows to ${outPath}`);
}

main().catch(error => {
  console.error(error && error.stack ? error.stack : String(error));
  process.exit(1);
});
