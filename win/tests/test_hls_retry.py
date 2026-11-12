"""Exercise the shipping relay's one-request retry and denial policy over real HTTP."""
import argparse,http.server,json,os,pathlib,subprocess,tempfile,threading,time,urllib.error,urllib.request
p=argparse.ArgumentParser();p.add_argument('--core',type=pathlib.Path,required=True);p.add_argument('--output',type=pathlib.Path,required=True);a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
core=a.core.resolve();cp=os.pathsep.join(map(str,[core/'litecore.jar',*sorted((core/'lib').glob('*.jar'))]))
code='''package xiao.bu.tv;import java.util.*;import okhttp3.*;
class RetryProbe{public static void main(String[]a)throws Exception{
 OkHttpClient client=new OkHttpClient.Builder().retryOnConnectionFailure(false).build();
 try(LiteMediaRelay relay=new LiteMediaRelay(client,a[0],Collections.emptyMap(),false,false,new LiteHlsSession())){System.out.println(relay.url());System.out.flush();System.in.read();}
 finally{client.dispatcher().executorService().shutdownNow();client.connectionPool().evictAll();}
}}
'''
results=[]
with tempfile.TemporaryDirectory() as directory:
 root=pathlib.Path(directory);(root/'RetryProbe.java').write_text(code,'utf8')
 subprocess.run(['javac','-encoding','UTF-8','-cp',cp,'-d',directory,str(root/'RetryProbe.java')],check=True)
 subprocess.run(['ffmpeg','-v','error','-nostdin','-y','-f','lavfi','-i','color=c=green:size=160x90:rate=25','-t','3','-c:v','libx264','-threads','1','-pix_fmt','yuv420p','-g','25','-f','hls','-hls_time','1','-hls_list_size','0','media.m3u8'],cwd=root,check=True)
 for mode in ['503','timeout','403']:
  state={'calls':0}
  class Handler(http.server.SimpleHTTPRequestHandler):
   def __init__(self,*args,**kw):super().__init__(*args,directory=directory,**kw)
   def log_message(self,*args):pass
   def do_GET(self):
    if self.path=='/media.m3u8':
     state['calls']+=1
     if state['calls']==1:
      if mode=='timeout':time.sleep(3.5);self.close_connection=True;return
      self.send_response(int(mode));self.send_header('Content-Length','0');self.end_headers();return
    super().do_GET()
  server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler);threading.Thread(target=server.serve_forever,daemon=True).start()
  process=subprocess.Popen(['java','-cp',directory+os.pathsep+cp,'xiao.bu.tv.RetryProbe','http://127.0.0.1:'+str(server.server_port)+'/media.m3u8'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,text=True)
  try:
   url=process.stdout.readline().strip();assert url.startswith('http://127.0.0.1:')
   try:
    response=urllib.request.urlopen(url,timeout=10);status=response.status;body=response.read()
   except urllib.error.HTTPError as error:status=error.code;body=b''
   calls=state['calls'];assert status==(403 if mode=='403' else 200),(mode,status)
   assert calls==(1 if mode=='403' else 2),(mode,calls)
   if mode!='403':
    assert body.startswith(b'#EXTM3U')
    frame=a.output/(mode+'-frame.png');subprocess.run(['ffmpeg','-v','error','-nostdin','-y','-i',url,'-frames:v','1','-threads','1',str(frame)],check=True,timeout=15);assert frame.stat().st_size>100
   results.append({'fault':mode,'passed':True,'upstreamCallsForOneRequest':calls,'status':status,'actualFrameDecoded':mode!='403'})
  finally:
   if process.poll() is None:process.stdin.write('\n');process.stdin.flush();process.wait(timeout=5)
   server.shutdown();server.server_close()
(a.output/'verification.json').write_text(json.dumps(results,indent=2),'utf8');print(json.dumps(results))
