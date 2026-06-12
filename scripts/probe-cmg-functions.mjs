import fs from "node:fs";
import path from "node:path";
import { createRequire } from "node:module";
import { Worker, isMainThread, parentPort, workerData } from "node:worker_threads";

const require = createRequire(import.meta.url);

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

function firstVideoNal(ts) {
  const chunks = [];
  let currentPid = -1;
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
      if (currentPid >= 0 && chunks.length) {
        break;
      }
      currentPid = pid;
      offset += 9 + packet[offset + 8];
    }
    if (currentPid === pid && offset < 188) {
      chunks.push(packet.subarray(offset));
    }
  }
  const data = Buffer.concat(chunks);
  for (let offset = 0; offset < data.length - 4; offset++) {
    const prefix = startCodeLength(data, offset);
    if (!prefix) {
      continue;
    }
    const start = offset + prefix;
    const type = data[start] & 0x1f;
    let end = data.length;
    for (let next = start + 1; next < data.length - 4; next++) {
      if (startCodeLength(data, next)) {
        end = next;
        break;
      }
    }
    if (type === 1 || type === 5) {
      return data.subarray(start, end);
    }
    offset = start;
  }
  throw new Error("No H264 slice NAL found");
}

async function runWorkerRun(options) {
  return await new Promise(resolve => {
    const messages = [];
    const worker = new Worker(new URL(import.meta.url), { workerData: options });
    const timer = setTimeout(async () => {
      await worker.terminate();
      resolve({ status: "timeout", messages });
    }, options.timeoutMs);
    worker.on("message", message => messages.push(message));
    worker.on("error", error => {
      clearTimeout(timer);
      resolve({ status: "error", error: error.message, messages });
    });
    worker.on("exit", code => {
      clearTimeout(timer);
      resolve({ status: code === 0 ? "ok" : "exit", code, messages });
    });
  });
}

function allocateString(module, text) {
  const bytes = Buffer.from(text);
  const address = module._jsmalloc(bytes.length + 1);
  module.HEAP8.fill(0, address, address + bytes.length + 1);
  module.HEAP8.set(bytes, address);
  return address;
}

if (isMainThread) {
  const [, , workerPath, inputPath, callSpec = "live-chain", timeoutMs = "8000",
    mediaTagId = "player_container_player", pageHost = "https://www.yangshipin.cn",
    maxBytes = "0", initMode = "catch"] = process.argv;
  if (!workerPath || !inputPath) {
    console.error("usage: node probe-cmg-functions.mjs cmg.worker.js in.ts [live-chain|vod-chain|live0..live8|vod0..vod8|update] [timeoutMs] [mediaTagId] [pageHost] [maxBytes] [catch|skip]");
    process.exit(1);
  }
  const nal = firstVideoNal(fs.readFileSync(inputPath));
  const input = +maxBytes > 0 ? nal.subarray(0, +maxBytes) : nal;
  const result = await runWorkerRun({
    workerPath: path.resolve(workerPath),
    input: Array.from(input),
    callSpec,
    timeoutMs: +timeoutMs,
    mediaTagId,
    pageHost,
    initMode
  });
  console.log(JSON.stringify({
    callSpec,
    inputLength: input.length,
    inputHead: Array.from(input.subarray(0, 32)),
    ...result
  }, null, 2));
} else {
  global.self = {
    location: {
      host: "www.yangshipin.cn",
      href: "blob:https://www.yangshipin.cn/native",
      origin: "https://www.yangshipin.cn",
      protocol: "blob:"
    }
  };
  global.location = global.self.location;

  const factory = require(workerData.workerPath);
  const moduleInstance = factory();
  const module = await new Promise(resolve => {
    moduleInstance.then(resolve);
  });
  const mediaTagId = workerData.mediaTagId;
  const data = Buffer.from(workerData.input);
  const host = Buffer.from(workerData.pageHost);
  const commonAddress = allocateString(module, mediaTagId);
  let initError = "";
  if (workerData.initMode !== "skip") {
    try {
      parentPort.postMessage({ stage: "before-init" });
      parentPort.postMessage({ stage: "after-init", result: module._CMG_InitPlayer(commonAddress) });
    } catch (error) {
      initError = error && error.message ? error.message : String(error);
      parentPort.postMessage({ stage: "init-error", error: initError });
    }
  }
  parentPort.postMessage({ stage: "before-update" });
  const updateTag = module._CMG_UpdatePlayer(commonAddress).toString(16).padStart(8, "0");
  parentPort.postMessage({ stage: "after-update", updateTag });

  const liveFunctions = [
    module._CMG_jsdecLive7, module._CMG_jsdecLive6, module._CMG_jsdecLive5,
    module._CMG_jsdecLive4, module._CMG_jsdecLive3, module._CMG_jsdecLive2,
    module._CMG_jsdecLive1, module._CMG_jsdecLive0, module._CMG_jsdecLive8
  ];
  const vodFunctions = [
    module._CMG_jsdecVOD7, module._CMG_jsdecVOD6, module._CMG_jsdecVOD5,
    module._CMG_jsdecVOD4, module._CMG_jsdecVOD3, module._CMG_jsdecVOD2,
    module._CMG_jsdecVOD1, module._CMG_jsdecVOD0, module._CMG_jsdecVOD8
  ];
  const functions = workerData.callSpec.startsWith("vod") ? vodFunctions : liveFunctions;
  const tagAddress = allocateString(module, mediaTagId);
  const dataAddress = module._jsmalloc(data.length + host.length + 1024 * 1024);
  module.HEAP8.set(data, dataAddress);
  module.HEAP8.set(host, dataAddress + data.length);

  let outputLength = data.length;
  if (workerData.callSpec.endsWith("-chain")) {
    for (let index = 0; index < 8; index++) {
      if ("0123456".includes(updateTag[index])) {
        parentPort.postMessage({ stage: "before-call", index, name: `${workerData.callSpec.slice(0, 4)}${index}` });
        functions[index](tagAddress, dataAddress, data.length, host.length);
        parentPort.postMessage({ stage: "after-call", index });
      }
    }
    parentPort.postMessage({ stage: "before-call", index: 8, name: `${workerData.callSpec.slice(0, 4)}8` });
    outputLength = functions[8](tagAddress, dataAddress, data.length, host.length);
    parentPort.postMessage({ stage: "after-call", index: 8, outputLength });
  } else if (workerData.callSpec === "update") {
    outputLength = data.length;
  } else {
    const index = Number(workerData.callSpec.replace(/^(live|vod)/, ""));
    parentPort.postMessage({ stage: "before-call", index });
    outputLength = functions[index](tagAddress, dataAddress, data.length, host.length);
    parentPort.postMessage({ stage: "after-call", index, outputLength });
  }

  const output = Buffer.from(module.HEAPU8.slice(dataAddress, dataAddress + Math.min(outputLength, 32)));
  parentPort.postMessage({
    stage: "done",
    initError,
    updateTag,
    outputLength,
    outputHead: Array.from(output)
  });
}
