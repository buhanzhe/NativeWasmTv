import fs from "node:fs";
import { pathToFileURL } from "node:url";

const [, , workerPath, inputPath, outputPath, mode = "vod", tagMode = "dts", delayMs = "0", freezeTime = "false"] = process.argv;
if (!workerPath || !inputPath || !outputPath) {
  console.error("usage: node probe-worker-decrypt.mjs worker.js in.ts out.264 [live|vod] [base|dts]");
  process.exit(1);
}

if (freezeTime === "true") {
  const now = Date.now();
  Date.now = () => now;
}

const { CNTVModule } = await import(pathToFileURL(workerPath).href);
const module = CNTVModule();
module.__DECRYPTER_SET_URL?.("https://www.cctv.com");
await new Promise(resolve => {
  module.onRuntimeInitialized = resolve;
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
    if (packet[0] !== 0x47) continue;
    const pid = ((packet[1] & 0x1f) << 8) | packet[2];
    const start = (packet[1] & 0x40) !== 0;
    const adaptationControl = (packet[3] >> 4) & 3;
    if ((adaptationControl & 1) === 0) continue;
    let offset = 4;
    if (adaptationControl & 2) offset += packet[4] + 1;
    if (offset >= 188) continue;
    if (start && offset + 9 <= 188 && packet[offset] === 0 && packet[offset + 1] === 0
        && packet[offset + 2] === 1 && packet[offset + 3] >= 0xe0 && packet[offset + 3] <= 0xef) {
      if (current) pes.push(current);
      const flags = packet[offset + 7];
      const headerLength = packet[offset + 8];
      const timestampOffset = offset + 9;
      current = { pid, dts: 0n, chunks: [] };
      if ((flags & 0xc0) && timestampOffset + 5 <= 188) current.dts = readPts(packet, timestampOffset);
      if ((flags & 0xc0) === 0xc0 && timestampOffset + 10 <= 188) current.dts = readPts(packet, timestampOffset + 5);
      offset += 9 + headerLength;
    }
    if (current && current.pid === pid && offset < 188) current.chunks.push(packet.subarray(offset));
  }
  if (current) pes.push(current);
  return pes;
}

function allocateString(text) {
  const bytes = Buffer.from(text);
  const address = module._jsmalloc(bytes.length + 1);
  module.HEAP8.fill(0, address, address + bytes.length + 1);
  module.HEAP8.set(bytes, address);
  return address;
}

const baseTag = "player_container_player";
const pageHost = "https://www.cctv.com";
const commonAddress = allocateString(baseTag);
module._CNTV_InitPlayer(commonAddress);
const updateTags = new Set();
const functions = mode === "live"
  ? [module._CNTV_jsdecLive7, module._CNTV_jsdecLive6, module._CNTV_jsdecLive5,
    module._CNTV_jsdecLive4, module._CNTV_jsdecLive3, module._CNTV_jsdecLive2,
    module._CNTV_jsdecLive1, module._CNTV_jsdecLive0, module._CNTV_jsdecLive8]
  : [module._CNTV_jsdecVOD7, module._CNTV_jsdecVOD6, module._CNTV_jsdecVOD5,
    module._CNTV_jsdecVOD4, module._CNTV_jsdecVOD3, module._CNTV_jsdecVOD2,
    module._CNTV_jsdecVOD1, module._CNTV_jsdecVOD0, module._CNTV_jsdecVOD8];

let decrypted = 0;
let changedLength = 0;
let pesIndex = 0;
const output = [];
for (const pes of extractPes(fs.readFileSync(inputPath))) {
  let nalIndex = 0;
  for (const nalu of splitAnnexB(Buffer.concat(pes.chunks))) {
    if (!nalu.body.length) continue;
    const type = nalu.body[0] & 0x1f;
    if (type !== 1 && type !== 5) {
      output.push(nalu.prefix, nalu.body);
      continue;
    }
    const updateTag = module._CNTV_UpdatePlayer(commonAddress).toString(16).padStart(8, "0");
    updateTags.add(updateTag);
    const mediaTag = tagMode === "dts" || tagMode === "dts1"
      ? `${baseTag}##${pes.dts}##${tagMode === "dts1" ? 1 : 0}` : baseTag;
    const tagAddress = allocateString(mediaTag);
    const dataAddress = module._jsmalloc(nalu.body.length + pageHost.length + 1024 * 1024);
    module.HEAP8.set(nalu.body, dataAddress);
    module.HEAP8.set(Buffer.from(pageHost), dataAddress + nalu.body.length);
    for (let index = 0; index < 8; index++) {
      if ("0123456".includes(updateTag[index])) {
        functions[index](tagAddress, dataAddress, nalu.body.length, pageHost.length);
      }
    }
    let resultLength;
    try {
      resultLength = functions[8](tagAddress, dataAddress, nalu.body.length, pageHost.length);
    } catch (error) {
      console.error(JSON.stringify({
        pesIndex, nalIndex, type, length: nalu.body.length,
        prefix: Buffer.from(nalu.body.subarray(0, 24)).toString("hex")
      }));
      throw error;
    }
    output.push(nalu.prefix, Buffer.from(module.HEAP8.slice(dataAddress, dataAddress + resultLength)));
    module._jsfree(dataAddress);
    module._jsfree(tagAddress);
    decrypted++;
    if (resultLength !== nalu.body.length) changedLength++;
    nalIndex++;
    if (+delayMs) Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, +delayMs);
  }
  pesIndex++;
}
module._CNTV_UnInitPlayer(commonAddress);
module._jsfree(commonAddress);
fs.writeFileSync(outputPath, Buffer.concat(output));
console.log(JSON.stringify({ mode, tagMode, delayMs: +delayMs, freezeTime, updateTags: [...updateTags], decrypted, changedLength }));
