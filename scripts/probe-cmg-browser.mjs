import fs from "node:fs";
import { chromium } from "playwright";

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
  for (let index = 0; index < data.length - 4; index++) {
    const prefix = startCodeLength(data, index);
    if (!prefix) {
      continue;
    }
    const start = index + prefix;
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
    index = start;
  }
  throw new Error("No H264 slice NAL found");
}

const workerJs = fs.readFileSync("build/cmg.worker.js", "utf8");
const nal = firstVideoNal(fs.readFileSync("build/ysp-cctv13.ts"));

const browser = await chromium.launch({ headless: true });
try {
  const page = await browser.newPage();
  await page.goto("https://www.yangshipin.cn/tv/home");
  const result = await page.evaluate(async ({ workerJs, nalBytes }) => {
    const script = document.createElement("script");
    script.textContent = workerJs;
    document.head.appendChild(script);
    const module = await new Promise(resolve => {
      const instance = window.CMGDecModule();
      instance.then(resolve);
    });
    const textEncoder = new TextEncoder();
    const mediaTagId = "player_container_player";
    const host = "https://www.yangshipin.cn";
    const allocateString = text => {
      const bytes = textEncoder.encode(text);
      const address = module._jsmalloc(bytes.length + 1);
      module.HEAP8.fill(0, address, address + bytes.length + 1);
      module.HEAP8.set(bytes, address);
      return address;
    };
    const common = allocateString(mediaTagId);
    const initResult = module._CMG_InitPlayer(common);
    const updateTag = module._CMG_UpdatePlayer(common).toString(16).padStart(8, "0");
    const tag = allocateString(mediaTagId);
    const hostBytes = textEncoder.encode(host);
    const data = new Uint8Array(nalBytes);
    const dataAddress = module._jsmalloc(data.length + hostBytes.length + 1024 * 1024);
    module.HEAP8.set(data, dataAddress);
    module.HEAP8.set(hostBytes, dataAddress + data.length);
    const functions = [
      module._CMG_jsdecLive7, module._CMG_jsdecLive6, module._CMG_jsdecLive5,
      module._CMG_jsdecLive4, module._CMG_jsdecLive3, module._CMG_jsdecLive2,
      module._CMG_jsdecLive1, module._CMG_jsdecLive0, module._CMG_jsdecLive8
    ];
    for (let index = 0; index < 8; index++) {
      if ("0123456".includes(updateTag[index])) {
        functions[index](tag, dataAddress, data.length, hostBytes.length);
      }
    }
    const outputLength = functions[8](tag, dataAddress, data.length, hostBytes.length);
    const output = Array.from(module.HEAPU8.slice(dataAddress, dataAddress + Math.min(outputLength, 32)));
    module._jsfree(dataAddress);
    module._jsfree(tag);
    module._CMG_UnInitPlayer(common);
    module._jsfree(common);
    return {
      initResult,
      updateTag,
      inputLength: data.length,
      outputLength,
      inputHead: Array.from(data.slice(0, 32)),
      outputHead: output
    };
  }, { workerJs, nalBytes: Array.from(nal) });
  console.log(JSON.stringify(result, null, 2));
} finally {
  await browser.close();
}
