import fs from "node:fs";
import path from "node:path";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);

const [, , workerPath, inputPath, outputPath, mode = "live",
  mediaTagId = "player_container_player", pageHost = "https://www.yangshipin.cn",
  updateMode = "digits", tagMode = "base", maxTargets = "0",
  initMode = "catch"] = process.argv;

if (!workerPath || !inputPath || !outputPath) {
  console.error("usage: node probe-cmg-decrypt.mjs worker.js in.ts out.264 [live|vod] [mediaTagId] [pageHost] [digits|all|none] [base|dts|dts1] [maxTargets] [catch|skip|strict]");
  process.exit(1);
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

const factory = require(path.resolve(workerPath));
const moduleInstance = factory({
  print() {},
  printErr() {}
});
const module = await new Promise(resolve => {
  moduleInstance.then(resolve);
});

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

function allocateString(text) {
  const bytes = Buffer.from(text);
  const address = module._jsmalloc(bytes.length + 1);
  module.HEAP8.fill(0, address, address + bytes.length + 1);
  module.HEAP8.set(bytes, address);
  return address;
}

const commonAddress = allocateString(mediaTagId);
let initError = "";
let initResult = null;
if (initMode !== "skip") {
  try {
    initResult = module._CMG_InitPlayer(commonAddress);
  } catch (error) {
    initError = error && error.message ? error.message : String(error);
    if (initMode === "strict") {
      throw error;
    }
  }
}

const functions = mode === "live"
  ? [module._CMG_jsdecLive7, module._CMG_jsdecLive6, module._CMG_jsdecLive5,
    module._CMG_jsdecLive4, module._CMG_jsdecLive3, module._CMG_jsdecLive2,
    module._CMG_jsdecLive1, module._CMG_jsdecLive0, module._CMG_jsdecLive8]
  : [module._CMG_jsdecVOD7, module._CMG_jsdecVOD6, module._CMG_jsdecVOD5,
    module._CMG_jsdecVOD4, module._CMG_jsdecVOD3, module._CMG_jsdecVOD2,
    module._CMG_jsdecVOD1, module._CMG_jsdecVOD0, module._CMG_jsdecVOD8];

const host = Buffer.from(pageHost);
const updateTags = new Set();
let decrypted = 0;
let changedLength = 0;
let changedBytes = 0;
const output = [];

for (const pes of extractPes(fs.readFileSync(inputPath))) {
  if (+maxTargets > 0 && decrypted >= +maxTargets) {
    break;
  }
  const annexb = Buffer.concat(pes.chunks);
  for (const nalu of splitAnnexB(annexb)) {
    if (+maxTargets > 0 && decrypted >= +maxTargets) {
      break;
    }
    if (!nalu.body.length) {
      continue;
    }
    const type = nalu.body[0] & 0x1f;
    const target = type === 1 || type === 5;
    if (!target) {
      output.push(nalu.prefix, nalu.body);
      continue;
    }
    const updateTag = module._CMG_UpdatePlayer(commonAddress).toString(16).padStart(8, "0");
    updateTags.add(updateTag);
    const tag = tagMode === "dts" || tagMode === "dts1"
      ? `${mediaTagId}##${pes.dts}##${tagMode === "dts1" ? 1 : 0}`
      : mediaTagId;
    const tagAddress = allocateString(tag);
    const dataAddress = module._jsmalloc(nalu.body.length + host.length + 1024 * 1024);
    module.HEAP8.set(nalu.body, dataAddress);
    module.HEAP8.set(host, dataAddress + nalu.body.length);
    for (let index = 0; index < 8; index++) {
      if (updateMode === "all" || (updateMode === "digits" && "0123456".includes(updateTag[index]))) {
        functions[index](tagAddress, dataAddress, nalu.body.length, host.length);
      }
    }
    const resultLength = functions[8](tagAddress, dataAddress, nalu.body.length, host.length);
    const result = Buffer.from(module.HEAP8.slice(dataAddress, dataAddress + resultLength));
    output.push(nalu.prefix, result);
    module._jsfree(dataAddress);
    module._jsfree(tagAddress);
    decrypted++;
    if (resultLength !== nalu.body.length) {
      changedLength++;
    }
    const minLength = Math.min(result.length, nalu.body.length);
    for (let index = 0; index < minLength; index++) {
      if (result[index] !== nalu.body[index]) {
        changedBytes++;
      }
    }
    changedBytes += Math.abs(result.length - nalu.body.length);
  }
}

let uninitError = "";
try {
  module._CMG_UnInitPlayer(commonAddress);
} catch (error) {
  uninitError = error && error.message ? error.message : String(error);
}
module._jsfree(commonAddress);
fs.writeFileSync(outputPath, Buffer.concat(output));
console.log(JSON.stringify({
  mode,
  mediaTagId,
  pageHost,
  updateMode,
  tagMode,
  initMode,
  initResult,
  initError,
  uninitError,
  updateTags: [...updateTags],
  decrypted,
  changedLength,
  changedBytes
}));
