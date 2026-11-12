"""Real HTTP truncation -> shipping HLS relay -> exact bytes and decoded frame."""
import argparse,http.server,json,os,pathlib,subprocess,tempfile,threading,time,urllib.request
p=argparse.ArgumentParser();p.add_argument('--core',type=pathlib.Path,required=True);p.add_argument('--output',type=pathlib.Path,required=True);a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
cp=os.pathsep.join(map(str,[a.core/'litecore.jar',*sorted((a.core/'lib').glob('*.jar'))]))
code='''package xiao.bu.tv;import java.util.*;import okhttp3.*;
class ResumeProbe{public static void main(String[]a)throws Exception{OkHttpClient c=new OkHttpClient.Builder().retryOnConnectionFailure(false).build();try(LiteMediaRelay r=new LiteMediaRelay(c,a[0],Collections.emptyMap(),false,false,new LiteHlsSession())){System.out.println(r.url());System.out.flush();System.in.read();}finally{c.dispatcher().executorService().shutdownNow();c.connectionPool().evictAll();}}}'''
results=[]
with tempfile.TemporaryDirectory() as directory:
 root=pathlib.Path(directory);(root/'ResumeProbe.java').write_text(code,'utf8')
 subprocess.run(['javac','-encoding','UTF-8','-cp',cp,'-d',directory,str(root/'ResumeProbe.java')],check=True)
 subprocess.run(['ffmpeg','-v','error','-y','-f','lavfi','-i','testsrc2=size=320x180:rate=25','-t','2','-c:v','libx264','-threads','1','-pix_fmt','yuv420p','-f','mpegts',str(root/'original.ts')],check=True)
 original=(root/'original.ts').read_bytes()
 for mode in ['resume','idle-timeout','ignored-range','changed-validator','missing-validator']:
  state={'requests':[]}
  class Handler(http.server.BaseHTTPRequestHandler):
   def log_message(self,*args):pass
   def do_GET(self):
    if self.path=='/live.m3u8':
     data=b'#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:1\n#EXTINF:2,\npart.ts\n';self.send_response(200);self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data);return
    assert self.path=='/part.ts';offset=self.headers.get('Range','');state['requests'].append({'range':offset,'ifRange':self.headers.get('If-Range')})
    resumed=offset.startswith('bytes=') and offset!='bytes=0-'
    start=int(offset.split('=')[1].split('-')[0]) if resumed else 0
    status=206 if resumed and mode!='ignored-range' else 200
    if status==200:start=0
    self.send_response(status);self.send_header('Content-Length',str(len(original)-start))
    if mode!='missing-validator':self.send_header('ETag','"changed"' if resumed and mode=='changed-validator' else '"original"')
    if status==206:self.send_header('Content-Range',f'bytes {start}-{len(original)-1}/{len(original)}')
    self.end_headers();self.wfile.write(original[start:] if resumed else original[:len(original)//2]);self.wfile.flush()
    if mode=='idle-timeout' and not resumed:time.sleep(3.5)
    self.close_connection=True
  server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler);threading.Thread(target=server.serve_forever,daemon=True).start()
  process=subprocess.Popen(['java','-cp',directory+os.pathsep+cp,'xiao.bu.tv.ResumeProbe',f'http://127.0.0.1:{server.server_port}/live.m3u8'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,text=True)
  try:
   url=process.stdout.readline().strip();manifest=urllib.request.urlopen(url,timeout=5).read().decode();segment=next(s for s in manifest.splitlines() if s.startswith('http:'));received=b'';error=''
   try:received=urllib.request.urlopen(segment,timeout=10).read()
   except Exception as ex:error=type(ex).__name__
   assert len(state['requests'])==(1 if mode=='missing-validator' else 2),state
   recovered=mode in ['resume','idle-timeout']
   if recovered:
    assert not error and received==original,(error,len(received),len(original));saved=a.output/'continued.ts';saved.write_bytes(received)
    subprocess.run(['ffmpeg','-v','error','-y','-i',str(saved),'-frames:v','1',str(a.output/'frame.png')],check=True,timeout=15)
   else:assert error,(mode,len(received))
   results.append({'mode':mode,'passed':True,'requests':len(state['requests']),'exactOriginalBytes':recovered,'actualFrameDecoded':recovered})
  finally:
   if process.poll() is None:process.stdin.write('\n');process.stdin.flush();process.wait(timeout=5)
   server.shutdown();server.server_close()
(a.output/'verification.json').write_text(json.dumps(results,indent=2),'utf8');print(json.dumps(results))
