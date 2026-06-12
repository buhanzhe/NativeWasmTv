import fs from "node:fs";
import path from "node:path";
import vm from "node:vm";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);

const [, , hlsCmgPath, workerPath, inputPath, outputPath, mode = "live",
  mediaTagId = "player_container_player", maxTargets = "40"] = process.argv;

if (!hlsCmgPath || !workerPath || !inputPath || !outputPath) {
  console.error("usage: node probe-cmg-wrapper.mjs hls.cmg.js cmg.worker.js in.ts out.264 [live|vod] [mediaTagId] [maxTargets]");
  process.exit(1);
}

function loadOfficialWrapper(sourcePath) {
  const source = fs.readFileSync(sourcePath, "utf8");
  const cut = source.indexOf(";var fI=function");
  if (cut < 0) {
    throw new Error("Unable to locate CMG wrapper cut point");
  }
  const code = source.slice(0, cut)
    + ";return {fG,fg,fh,fj,fk,fm,fp,fq,fu,fv,fw,fx};};globalThis.__cmgWrapper=c();}();";
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
      current = { pid, dts: 0n, chunks: [] };
      if ((flags & 0xc0) && timestampOffset + 5 <= 188) {
        current.dts = readPts(packet, timestampOffset);
      }
      if ((flags & 0xc0) === 0xc0 && timestampOffset + 10 <= 188) {
        current.dts = readPts(packet, timestampOffset + 5);
      }
      offset += 9 + headerLength;
    }
    if (current && current.pid === pid && offset < 188) {
      current.chunks.push(packet.subarray(offset));
    }
  }
  if (current) {
    pes.push(current);
  }
  return pes;
}

function splitAnnexB(data) {
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
    return {
      prefix: data.subarray(start.offset, start.offset + start.prefixLength),
      body: data.subarray(start.offset + start.prefixLength, end)
    };
  });
}

global.self = {
  location: {
    host: "www.yangshipin.cn",
    href: "blob:https://www.yangshipin.cn/native",
    origin: "https://www.yangshipin.cn",
    protocol: "blob:"
  }
};
global.location = global.self.location;

const moduleInstance = require(path.resolve(workerPath))();
const module = await new Promise(resolve => {
  moduleInstance.then(resolve);
});
const { sandbox, wrapper } = loadOfficialWrapper(hlsCmgPath);
const fG = wrapper.fG;
fG.moduleActive(module, mediaTagId, fG.INITPLAYER);

let decrypted = 0;
let changedBytes = 0;
const output = [];
for (const pes of extractPes(fs.readFileSync(inputPath))) {
  if (+maxTargets > 0 && decrypted >= +maxTargets) {
    break;
  }
  for (const nalu of splitAnnexB(Buffer.concat(pes.chunks))) {
    if (+maxTargets > 0 && decrypted >= +maxTargets) {
      break;
    }
    if (!nalu.body.length) {
      continue;
    }
    const type = nalu.body[0] & 0x1f;
    if (type !== 1 && type !== 5) {
      output.push(nalu.prefix, nalu.body);
      continue;
    }
    fG.moduleActive(module, mediaTagId, fG.UPDATEPLAYER);
    const result = fG.moduleDecData(module, mediaTagId, new Uint8Array(nalu.body),
      mode === "live" ? fG.LIVEVIDEO : fG.VODVIDEO);
    output.push(nalu.prefix, Buffer.from(result));
    const minLength = Math.min(result.length, nalu.body.length);
    for (let index = 0; index < minLength; index++) {
      if (result[index] !== nalu.body[index]) {
        changedBytes++;
      }
    }
    changedBytes += Math.abs(result.length - nalu.body.length);
    decrypted++;
  }
}
fG.moduleActive(module, mediaTagId, fG.UNINITPLAYER);
fs.writeFileSync(outputPath, Buffer.concat(output));
console.log(JSON.stringify({
  mode,
  mediaTagId,
  activeURL: sandbox.self.activeURL,
  vmpTag: sandbox.self.vmpTag,
  decrypted,
  changedBytes
}));
