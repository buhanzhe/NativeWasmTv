"""Actual native core -> UJS selection -> hls.js/MSE relay -> FFmpeg frame.

Pass --java-command 'wine64 .../java.exe' for an actual Windows DLL execution.
No public live endpoint is used. The Linux run is a distinct portability check.
"""
import argparse
import base64
import functools
import http.server
import json
import os
from pathlib import Path
import queue
import shlex
import subprocess
import threading
import time

ROOT=Path(__file__).resolve().parents[2]


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--core',type=Path,required=True)
    p.add_argument('--java-command',default='java')
    p.add_argument('--windows',action='store_true',default=os.name=='nt')
    p.add_argument('--output',type=Path,required=True)
    a=p.parse_args();out=a.output.resolve();out.mkdir(parents=True,exist_ok=True)
    fixture=out/'fixture';fixture.mkdir(exist_ok=True)
    subprocess.run(['ffmpeg','-v','error','-nostdin','-y','-f','lavfi','-i','color=c=green:size=160x90:rate=25','-f','lavfi','-i','sine=frequency=440:sample_rate=44100','-t','8','-c:v','libx264','-threads','1','-pix_fmt','yuv420p','-g','25','-c:a','aac','-f','hls','-hls_time','1','-hls_list_size','0',str(fixture/'selected.m3u8')],check=True)
    (fixture/'hls.js').write_bytes((ROOT/'browser-core/litebrowser/desktop/test/assets/hls-1.5.18.min.js').read_bytes())
    (fixture/'index.html').write_text('''<!doctype html><body><button id="selected">Selected</button><video id="video"></video><script src="/hls.js"></script><script>
    document.getElementById('selected').onclick=function(){
      var wasm=new WebAssembly.Instance(new WebAssembly.Module(new Uint8Array([0,97,115,109,1,0,0,0,1,5,1,96,0,1,127,3,2,1,0,7,10,1,6,97,110,115,119,101,114,0,0,10,6,1,4,0,65,42,11])));
      if(wasm.exports.answer()!==42)throw Error('Actual wasm3 execution failed');
      var hls=new Hls({enableWorker:false,maxBufferLength:8});
      hls.loadSource('/selected.m3u8?token=a,b');hls.attachMedia(document.getElementById('video'));
    };</script></body>''',encoding='utf-8')
    requested=[]
    class Handler(http.server.SimpleHTTPRequestHandler):
        def log_message(self,*args): pass
        def do_GET(self): requested.append(self.path);super().do_GET()
    server=http.server.ThreadingHTTPServer(('127.0.0.1',0),functools.partial(Handler,directory=str(fixture)))
    threading.Thread(target=server.serve_forever,daemon=True).start()
    script="document.getElementById('selected').click();"
    url='webview://http://127.0.0.1:%d/index.html?ujs=%s'%(server.server_port,base64.urlsafe_b64encode(script.encode()).decode())
    tests=out/'classes';tests.mkdir(exist_ok=True)
    cp=os.pathsep.join(str(x) for x in [a.core/'litecore.jar',*sorted((a.core/'lib').glob('*.jar'))])
    subprocess.run(['javac','--release','17','-cp',cp,'-d',str(tests),str(ROOT/'browser-core/litebrowser/windows/test/xiao/bu/tv/SessionSmoke.java')],check=True)
    def path(v):
        value=str(Path(v).resolve())
        return 'Z:'+value.replace('/','\\') if a.windows and os.name!='nt' else value
    cp=(';' if a.windows else ':').join(path(x) for x in [tests,a.core/'litecore.jar',*sorted((a.core/'lib').glob('*.jar'))])
    dll=a.core/('ntvLiteDesktop.dll' if a.windows else 'libntvLiteDesktop.so')
    command=shlex.split(a.java_command)+['-Xmx256m','-Djava.awt.headless=true','-Dntv.library='+path(dll),'-cp',cp,'xiao.bu.tv.SessionSmoke',url,path(out/'profile')]
    process=subprocess.Popen(command,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=(out/'session.log').open('w'),text=True)
    lines=queue.Queue()
    def read():
        for line in process.stdout: lines.put(line.rstrip())
    threading.Thread(target=read,daemon=True).start()
    try:
        end=time.monotonic()+65;media=None
        while time.monotonic()<end:
            if process.poll() is not None: raise AssertionError('Session failed; see session.log')
            try: line=lines.get(timeout=1)
            except queue.Empty: continue
            if line.startswith('MEDIA '):media=line[6:];break
        assert media,'No actual media relay'
        frame=out/'actual-frame.png'
        result=subprocess.run(['ffmpeg','-v','error','-nostdin','-y','-rw_timeout','10000000','-i',media,'-frames:v','1','-threads','1',str(frame)],capture_output=True,text=True,timeout=25)
        (out/'decode.log').write_text(result.stderr)
        assert result.returncode==0 and frame.exists(),'Real decode failed'
        pixel=subprocess.check_output(['ffmpeg','-v','error','-i',str(frame),'-vf','crop=1:1:80:45,format=rgb24','-frames:v','1','-f','rawvideo','pipe:1'])
        assert len(pixel)==3 and pixel[1]>100 and pixel[0]<25 and pixel[2]<25,repr(pixel)
        assert '/selected.m3u8?token=a,b' in requested,'Original signed source URL changed'
        process.stdin.write('\n');process.stdin.flush();process.wait(timeout=12)
        assert process.returncode==0,'Session cleanup failed'
        info={'passed':True,'target':'windows-native' if os.name=='nt' else 'windows-wine' if a.windows else 'linux','checks':['native QuickJS/litehtml JNI loaded','wasm3 executed original WASM module','UJS selected original channel','real hls.js MSE output','FFmpeg decoded selected green frame','signed URL retained','owner thread cleanup'],'requested':requested}
        (out/'verification.json').write_text(json.dumps(info,indent=2));print(json.dumps(info))
    finally:
        if process.poll() is None:process.kill();process.wait()
        server.shutdown();server.server_close()


if __name__=='__main__':main()
