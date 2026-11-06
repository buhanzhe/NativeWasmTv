"""Exercise the packaged native host, real WASM/UJS/MSE, decoder and GPU output."""
import argparse
import base64
import functools
import http.server
import json
from pathlib import Path
import struct
import subprocess
import threading

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--exe',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--ffmpeg',default='ffmpeg')
    parser.add_argument('--audit-only',action='store_true')
    a=parser.parse_args();exe=a.exe.resolve();out=a.output.resolve();out.mkdir(parents=True,exist_ok=True)
    binary=exe.read_bytes();assert binary[:2]==b'MZ' and len(binary)<10_000_000
    if a.audit_only:
        for text in (b'D:/documents',b'D:\\documents',b'function Context(canvas)',b'g.__desktopWorkerEnter=function'):
            assert text not in binary,text
        for text in ('--probe','--self-test','--render-test'):
            assert text.encode('utf-16-le') not in binary,text
        print(json.dumps({'passed':True,'bytes':len(binary),'audit':'no plaintext embedded scripts, local build paths or developer commands'}));return
    fixture=out/'fixture';fixture.mkdir(exist_ok=True)
    subprocess.run([a.ffmpeg,'-v','error','-nostdin','-y','-f','lavfi','-i','color=c=green:size=320x180:rate=50','-f','lavfi','-i','sine=frequency=440:sample_rate=48000','-t','8','-c:v','libx264','-threads','1','-pix_fmt','yuv420p','-g','50','-c:a','aac','-f','hls','-hls_time','1','-hls_list_size','0',str(fixture/'selected.m3u8')],check=True)
    core=Path(__file__).resolve().parents[2]/'browser-core'
    (fixture/'hls.js').write_bytes((core/'litebrowser/desktop/test/assets/hls-1.5.18.min.js').read_bytes())
    (fixture/'index.html').write_text('''<!doctype html><body><button id="selected">Selected</button><video id="video"></video><script src="/hls.js"></script><script>
document.getElementById('selected').onclick=function(){
var wasm=new WebAssembly.Instance(new WebAssembly.Module(new Uint8Array([0,97,115,109,1,0,0,0,1,5,1,96,0,1,127,3,2,1,0,7,10,1,6,97,110,115,119,101,114,0,0,10,6,1,4,0,65,42,11])));
if(wasm.exports.answer()!==42)throw Error('WASM failed');
var hls=new Hls({enableWorker:false,maxBufferLength:8});hls.loadSource('/selected.m3u8?token=a,b');hls.attachMedia(document.getElementById('video'));
};</script></body>''',encoding='utf-8')
    requested=[]
    class Handler(http.server.SimpleHTTPRequestHandler):
        def log_message(self,*args):pass
        def do_GET(self):requested.append(self.path);super().do_GET()
    server=http.server.ThreadingHTTPServer(('127.0.0.1',0),functools.partial(Handler,directory=str(fixture)))
    threading.Thread(target=server.serve_forever,daemon=True).start()
    script=base64.urlsafe_b64encode(b"document.getElementById('selected').click();").decode()
    url=f'webview://http://127.0.0.1:{server.server_port}/index.html?ujs={script}'
    def run(mode,name):
        report=out/(name+'.json')
        result=subprocess.run([str(exe),mode,url,'--report',str(report),'10'],capture_output=True,timeout=40)
        (out/(name+'.log')).write_bytes(result.stderr)
        data=json.loads(report.read_text(encoding='utf-8'));assert result.returncode==0 and data['passed'],data
        return data
    try:
        subprocess.run([str(exe),'--self-test',str(out/'playlist.json')],check=True,timeout=15)
        media=run('--probe','mse');assert media['decoder_route']=='mse' and media['video_frames']>=100 and media['audio_frames']>=100 and media['position_seconds']>=5 and media['cleanup_complete']
        r,g,b=media['center_rgb'];assert g>100 and r<20 and b<20
        assert '/selected.m3u8?token=a,b' in requested
        display=run('--render-test','display');assert display['source_width']==320 and display['source_height']==180
        pixels=(out/'display.json.bmp').read_bytes();offset=struct.unpack_from('<I',pixels,10)[0];width,height=struct.unpack_from('<ii',pixels,18);height=abs(height);at=offset+((height//2)*width+width//2)*4
        b,g,r=pixels[at:at+3];assert g>100 and r<20 and b<20,'GPU output is not the selected channel'
        print(json.dumps({'passed':True,'bytes':len(binary),'mse_video_frames':media['video_frames'],'first_frame_ms':media['total_first_frame_ms'],'gpu_output':display['gpu_output'],'checks':['828 bundled channels','actual WASM and UJS','signed URL unchanged','MSE video and audio decode','rendered green pixels','session cleanup']}))
    finally:server.shutdown();server.server_close()

if __name__=='__main__':main()
