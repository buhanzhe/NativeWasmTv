import fs from "node:fs";
import vm from "node:vm";

const [, , hlsCmgPath] = process.argv;

if (!hlsCmgPath) {
  console.error("usage: node inspect-cmg-wrapper.mjs hls.cmg.js");
  process.exit(1);
}

const source = fs.readFileSync(hlsCmgPath, "utf8");
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
const wrapper = sandbox.__cmgWrapper;

for (const key of Object.keys(wrapper)) {
  console.log(`${key}: ${Object.keys(wrapper[key] || {}).join(",")}`);
}
console.log("constants", {
  INITPLAYER: wrapper.fG.INITPLAYER,
  UPDATEPLAYER: wrapper.fG.UPDATEPLAYER,
  UNINITPLAYER: wrapper.fG.UNINITPLAYER,
  LIVEVIDEO: wrapper.fG.LIVEVIDEO,
  VODVIDEO: wrapper.fG.VODVIDEO
});
console.log("moduleActive:\n" + String(wrapper.fG.moduleActive));
console.log("moduleDecData:\n" + String(wrapper.fG.moduleDecData));
