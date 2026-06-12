const fs = require("fs");
const path = require("path");

const [, , workerPath, inputPath, outputPath, mode = "live",
  mediaTagID = "player_container_player", pageHost = "https://www.cctv.com",
  updateMode = "all"] = process.argv;

if (!workerPath || !inputPath || !outputPath) {
  console.error("usage: node test-live-decrypt.cjs worker.js in.264 out.264 [live|vod] [mediaTagID] [pageHost] [all|target]");
  process.exit(1);
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
    host: "",
    href: "blob:https://www.cctv.com/native",
    protocol: "blob:"
  }
};
global.location = global.self.location;

const factory = require(path.resolve(workerPath));
const moduleInstance = factory();

moduleInstance.then(module => {
  const input = fs.readFileSync(inputPath);
  const nalus = splitAnnexB(input);
  const commonAddress = module._jsmalloc(mediaTagID.length + 2048);
  const encoder = new TextEncoder();
  const host = encoder.encode(pageHost);
  const common = encoder.encode(mediaTagID);
  let decrypted = 0;
  let changedLength = 0;

  module.HEAP8.fill(0, commonAddress, commonAddress + common.length + 2048);
  module.HEAP8.set(common, commonAddress);
  module._CNTV_InitPlayer(commonAddress);

  const functions = mode === "live"
    ? [module._CNTV_jsdecLive7, module._CNTV_jsdecLive6, module._CNTV_jsdecLive5,
      module._CNTV_jsdecLive4, module._CNTV_jsdecLive3, module._CNTV_jsdecLive2,
      module._CNTV_jsdecLive1, module._CNTV_jsdecLive0, module._CNTV_jsdecLive8]
    : [module._CNTV_jsdecVOD7, module._CNTV_jsdecVOD6, module._CNTV_jsdecVOD5,
      module._CNTV_jsdecVOD4, module._CNTV_jsdecVOD3, module._CNTV_jsdecVOD2,
      module._CNTV_jsdecVOD1, module._CNTV_jsdecVOD0, module._CNTV_jsdecVOD8];

  const output = nalus.map(nalu => {
    if (!nalu.body.length) {
      return Buffer.concat([nalu.prefix, nalu.body]);
    }
    const type = nalu.body[0] & 0x1f;
    const target = type === 1 || type === 5;
    let tag = "";
    if (updateMode === "all" || target) {
      tag = module._CNTV_UpdatePlayer(commonAddress).toString(16).padStart(8, "0");
    }
    if (!target) {
      return Buffer.concat([nalu.prefix, nalu.body]);
    }

    const dataAddress = module._jsmalloc(nalu.body.length + host.length + 1048576);
    module.HEAP8.set(nalu.body, dataAddress);
    module.HEAP8.set(host, dataAddress + nalu.body.length);
    for (let index = 0; index < 8; index++) {
      if ("0123456".includes(tag[index])) {
        functions[index](commonAddress, dataAddress, nalu.body.length, host.length);
      }
    }
    const resultLength = functions[8](commonAddress, dataAddress, nalu.body.length, host.length);
    const result = Buffer.from(module.HEAP8.slice(dataAddress, dataAddress + resultLength));
    module._jsfree(dataAddress);
    decrypted++;
    if (resultLength !== nalu.body.length) {
      changedLength++;
    }
    return Buffer.concat([nalu.prefix, result]);
  });

  module._CNTV_UnInitPlayer(commonAddress);
  module._jsfree(commonAddress);
  fs.writeFileSync(outputPath, Buffer.concat(output));
  console.log(JSON.stringify({ mode, mediaTagID, pageHost, updateMode, nalus: nalus.length, decrypted, changedLength }));
});
