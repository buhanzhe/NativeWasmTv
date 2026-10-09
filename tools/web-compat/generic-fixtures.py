"""Local, host-independent compatibility fixtures. No external accounts/media."""
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
import argparse

scripts = {
    '/external.js': "const values=new Set([1,2,2]); let sum=0; for(const v of values)sum+=v; window.externalProof=sum===3; const tag=document.createElement('script');tag.src='/chunk.js';document.head.appendChild(tag);",
    '/chunk.js': "async function finish(){const result=await Promise.resolve({value:4}); const {value}=result; window.__ntvGenericProof=window.externalProof&&value===4?'PASS':'FAIL';} finish();",
    '/entry.mjs': "import {value} from './value.mjs'; import('./dynamic.mjs').then(({extra})=>{window.__ntvGenericProof=value+extra===7&&import.meta.url.indexOf('entry.mjs')>=0?'PASS':'FAIL';});",
    '/value.mjs': "export const value=3;",
    '/dynamic.mjs': "export const extra=4;",
    '/legacy.js': "window.__ntvGenericProof='PASS';",
    '/plain.js': "var untouched=1;",
}
pages = {
    '/inline': '<script>const fn=(x=2)=>x+1; class Example {value(){return fn();}} async function run(){const {value}={value:await Promise.resolve(new Example().value())};const a={x:value};window.__ntvGenericProof=a?.x===3&&[...new Set([1,1])].length===1?"PASS":"FAIL";}run();</script>',
    '/external': '<script src="/external.js"></script>',
    '/module': '<script type="module" src="/entry.mjs"></script>',
    '/importmap': '<script type="importmap">{"imports":{"dep":"./value.mjs"}}</script><script type="module">import {value} from "dep";window.__ntvGenericProof=value===3?"PASS":"FAIL";</script>',
    '/dynamic-classic': '<script>import("./dynamic.mjs").then(({extra})=>window.__ntvGenericProof=extra===4?"PASS":"FAIL");</script>',
    '/inline-module': '<script type="module">import {value} from "./value.mjs";window.__ntvGenericProof=value===3?"PASS":"FAIL";</script>',
    '/data-order': '<!-- <script>const invalid =;</script> --><script type="application/json" id="data">{"value":3}</script><textarea><script>const invalid =;</script></textarea><script>var order=[1];</script><script>let x=JSON.parse(document.getElementById("data").textContent);order.push(x.value);</script><script>window.__ntvGenericProof=order.join(",")==="1,3"?"PASS":"FAIL";</script>',
    '/nomodule': '<script type="module" src="/entry.mjs"></script><script nomodule src="/legacy.js"></script>',
    '/es5': '<script src="/plain.js"></script><script>window.__ntvGenericProof=untouched===1?"PASS":"FAIL";</script>',
}

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        path=self.path.split('?',1)[0]
        value=scripts.get(path)
        mime='application/javascript; charset=utf-8'
        if value is None:
            value=pages.get(path)
            mime='text/html; charset=utf-8'
            if value is not None:value='<!doctype html><html><head><meta charset="utf-8">'+value+'</head><body>Generic fixture</body></html>'
        if value is None:self.send_error(404);return
        data=value.encode('utf-8');self.send_response(200);self.send_header('Content-Type',mime);self.send_header('Content-Length',str(len(data)));self.send_header('Cache-Control','no-store');self.send_header('Connection','close');self.end_headers();self.wfile.write(data)
    def log_message(self, *args): pass

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--port',type=int,default=18769);args=parser.parse_args()
    print('Generic fixtures on',args.port,flush=True);ThreadingHTTPServer(('0.0.0.0',args.port),Handler).serve_forever()
