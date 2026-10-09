"""Local, host-independent compatibility fixtures. No external accounts/media."""
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
import argparse
import json
import re
from urllib.parse import parse_qs, urlsplit
from http.cookies import SimpleCookie

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
        parsed=urlsplit(self.path);path=parsed.path
        nonce=parse_qs(parsed.query).get('nonce',['probe'])[0]
        nonce=re.sub(r'[^a-zA-Z0-9_-]','',nonce)[:64] or 'probe'
        value=scripts.get(path)
        mime='application/javascript; charset=utf-8'
        if path=='/cookie-check':
            cookies=SimpleCookie(self.headers.get('Cookie',''))
            match=cookies.get('ntv_compat_cookie')
            value=json.dumps({'match':bool(match and match.value==nonce)});mime='application/json'
        if value is None:
            value=pages.get(path)
            mime='text/html; charset=utf-8'
            if path=='/cookie-sensors':
                # A real KitKat thread dependency: sensor startup needs UI services
                # while the native response's Set-Cookie writes reach Chromium.
                value='''<script>(function(){var nonce=%s,tries=0;function sensor(){}
window.addEventListener('deviceorientation',sensor,false);
var timer=setInterval(function(){if(document.cookie.indexOf('ntv_compat_cookie='+nonce)<0){
if(++tries<100)return;clearInterval(timer);window.__ntvGenericProof='FAIL';return;}
clearInterval(timer);var x=new XMLHttpRequest();x.open('GET','/cookie-check?nonce='+nonce,true);x.timeout=5000;
x.onload=function(){window.__ntvGenericProof=x.status===200&&JSON.parse(x.responseText).match?'PASS':'FAIL';
window.removeEventListener('deviceorientation',sensor,false);};
x.onerror=x.ontimeout=function(){window.__ntvGenericProof='FAIL';};x.send();},50);})();</script>''' % json.dumps(nonce)
            if value is not None:value='<!doctype html><html><head><meta charset="utf-8">'+value+'</head><body>Generic fixture</body></html>'
        if value is None:self.send_error(404);return
        data=value.encode('utf-8');self.send_response(200);self.send_header('Content-Type',mime);self.send_header('Content-Length',str(len(data)));self.send_header('Cache-Control','no-store');self.send_header('Connection','close')
        if path=='/cookie-sensors':self.send_header('Set-Cookie','ntv_compat_cookie='+nonce+'; Path=/; SameSite=Lax')
        self.end_headers();self.wfile.write(data)
    def log_message(self, *args): pass

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--port',type=int,default=18769);args=parser.parse_args()
    print('Generic fixtures on',args.port,flush=True);ThreadingHTTPServer(('0.0.0.0',args.port),Handler).serve_forever()
