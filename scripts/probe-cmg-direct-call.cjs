const fs = require("fs");
const path = require("path");

const [, , workerPath, inputPath, callSpec = "live-chain",
  mediaTagId = "player_container_player", pageHost = "https://www.yangshipin.cn",
  maxBytes = "0", initMode = "catch", nalTypeArg = "slice"] = process.argv;

if (!workerPath || !inputPath) {
  console.error("usage: node probe-cmg-direct-call.cjs cmg.worker.js in.ts [live-chain|vod-chain|live0..live8|vod0..vod8|update] [mediaTagId] [pageHost] [maxBytes] [catch|skip] [slice|0-31]");
  process.exit(1);
}

function log(message, value) {
  if (value === undefined) {
    console.log(message);
  } else {
    console.log(message, JSON.stringify(value));
  }
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

function firstVideoNal(ts, wantedType) {
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
    if ((wantedType < 0 && (type === 1 || type === 5)) || type === wantedType) {
      return data.subarray(start, end);
    }
    offset = start;
  }
  throw new Error("No matching H264 NAL found");
}

function allocateString(module, text) {
  const bytes = Buffer.from(text);
  const address = module._jsmalloc(bytes.length + 1);
  module.HEAP8.fill(0, address, address + bytes.length + 1);
  module.HEAP8.set(bytes, address);
  return address;
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

log("load-module");
const factory = require(path.resolve(workerPath));
log("factory-loaded", { type: typeof factory });
const instance = factory({
  print() {},
  printErr() {}
});
log("factory-created", { hasThen: !!instance && typeof instance.then });

instance.then(module => {
  log("module-ready");

  const wantedType = nalTypeArg === "slice" ? -1 : Number(nalTypeArg);
  const originalNal = firstVideoNal(fs.readFileSync(inputPath), wantedType);
  const data = +maxBytes > 0 ? originalNal.subarray(0, +maxBytes) : originalNal;
  const host = Buffer.from(pageHost);
  log("input", {
    requestedNalType: nalTypeArg,
    nalType: data.length ? data[0] & 31 : -1,
    length: data.length,
    head: Array.from(data.subarray(0, 32))
  });

  const commonAddress = allocateString(module, mediaTagId);
  if (initMode !== "skip") {
    try {
      log("before-init");
      log("after-init", { result: module._CMG_InitPlayer(commonAddress) });
    } catch (error) {
      log("init-error", { error: error && error.message ? error.message : String(error) });
    }
  }

  log("before-update");
  const updateTag = module._CMG_UpdatePlayer(commonAddress).toString(16).padStart(8, "0");
  log("after-update", { updateTag });

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
  const functions = callSpec.startsWith("vod") ? vodFunctions : liveFunctions;
  const tagAddress = allocateString(module, mediaTagId);
  const dataAddress = module._jsmalloc(data.length + host.length + 1024 * 1024);
  module.HEAP8.set(data, dataAddress);
  module.HEAP8.set(host, dataAddress + data.length);

  let outputLength = data.length;
  if (callSpec.endsWith("-chain")) {
    for (let index = 0; index < 8; index++) {
      if ("0123456".includes(updateTag[index])) {
        log("before-call", { index });
        functions[index](tagAddress, dataAddress, data.length, host.length);
        log("after-call", { index });
      }
    }
    log("before-call", { index: 8 });
    outputLength = functions[8](tagAddress, dataAddress, data.length, host.length);
    log("after-call", { index: 8, outputLength });
  } else if (callSpec !== "update") {
    const index = Number(callSpec.replace(/^(live|vod)/, ""));
    log("before-call", { index });
    outputLength = functions[index](tagAddress, dataAddress, data.length, host.length);
    log("after-call", { index, outputLength });
  }

  log("output", {
    outputLength,
    head: Array.from(module.HEAPU8.slice(dataAddress, dataAddress + Math.min(outputLength, 32)))
  });
  let changedBytes = Math.abs(outputLength - data.length);
  const compareLength = Math.min(outputLength, data.length);
  for (let index = 0; index < compareLength; index++) {
    if (module.HEAPU8[dataAddress + index] !== data[index]) {
      changedBytes++;
    }
  }
  log("diff", { changedBytes });
  process.exit(0);
});
