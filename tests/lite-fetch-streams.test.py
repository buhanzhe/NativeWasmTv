"""Real native engine/HTTP test: pull data before EOF and cancel retained bodies."""
import argparse,http.server,json,os,pathlib,subprocess,tempfile,threading

ROOT=pathlib.Path(__file__).resolve().parents[1]
SCRIPT=r'''<!doctype html><script>
(async function(){
 function check(value,message){if(!value)throw Error(message);}
 var begin=Date.now(),response=await fetch('/continuous.flv');
 check(response instanceof Response,'Response identity');
 check(response.bodyUsed===false,'unused stream was disturbed');
 var reader=response.body.getReader(),length=0;
 while(length<65536){var part=await reader.read();check(!part.done,'premature EOF');length+=part.value.byteLength;}
 check(Date.now()-begin<4000,'fetch waited for complete response');
 check(response.bodyUsed,'bodyUsed did not follow read');
 await reader.cancel();
 var aborter=new AbortController(),aborted=await fetch('/continuous.flv?abort=1',{signal:aborter.signal}),r=aborted.body.getReader();
 var readBytes=0;while(readBytes<98304){var data=await r.read();readBytes+=data.value.byteLength;}
 var waiting=r.read();setTimeout(function(){aborter.abort();},20);var rejected=false;try{await waiting;}catch(error){rejected=true;}check(rejected,'abort did not reject pending body read');
 var finite=await fetch('/json'),copy=finite.clone();check((await finite.json()).text==='汉字','JSON regression');check((await copy.text()).indexOf('汉字')>=0,'clone regression');
 __host(JSON.stringify({op:'log',value:'FETCH_STREAM_PASS headers before EOF, bounded pulls, cancel, abort, finite JSON and clone'}));
})().catch(function(error){__host(JSON.stringify({op:'log',value:'FETCH_STREAM_FAIL '+error.stack}));});
</script>'''

def main():
 p=argparse.ArgumentParser();p.add_argument('--core',type=pathlib.Path,required=True);a=p.parse_args();core=a.core.resolve()
 release=threading.Event();completed=threading.Event();opened=probed=0
 class Handler(http.server.BaseHTTPRequestHandler):
  def log_message(self,*args):pass
  def do_GET(self):
   nonlocal opened,probed
   if self.path.startswith('/continuous.flv'):
    if self.headers.get('Range'):
     probed+=1
     self.send_response(206);self.send_header('Content-Type','video/x-flv');self.send_header('Content-Length','4096');self.end_headers();self.wfile.write(b'FLV'+b'\0'*4093);return
    opened+=1;self.send_response(200);self.send_header('Content-Type','video/x-flv');self.end_headers()
    try:
     for i in range(3):self.wfile.write(bytes([i])*32768);self.wfile.flush()
     release.wait(15);completed.set()
    except (BrokenPipeError,ConnectionResetError):pass
   else:
    data=(json.dumps({'text':'汉字'},ensure_ascii=False) if self.path=='/json' else SCRIPT).encode('utf8')
    self.send_response(200);self.send_header('Content-Length',str(len(data)));self.send_header('Content-Type','application/json;charset=utf-8' if self.path=='/json' else 'text/html;charset=utf-8');self.end_headers();self.wfile.write(data)
 server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler);server.daemon_threads=True
 threading.Thread(target=server.serve_forever,daemon=True).start()
 try:
  with tempfile.TemporaryDirectory() as folder:
   dest=pathlib.Path(folder);cfg={'url':'http://127.0.0.1:'+str(server.server_port)+'/','seconds':6,'render':False,'headless':True,'memoryMiB':64,'concurrency':4,'probeMedia':True,'captureDir':str(dest)}
   config=dest/'config.json';config.write_text(json.dumps(cfg),'utf8');cp=os.pathsep.join(map(str,[core/'litecore.jar',*list((core/'lib').glob('*.jar'))]))
   cmd=['java','-Xmx128m','-Dfile.encoding=UTF-8','-Dntv.library='+str(core/'ntvLiteDesktop.dll'),'-cp',cp,'xiao.bu.tv.DesktopMain',str(config),str(core/'classes/assets/bootstrap.js'),str(dest/'result.json')]
   run=subprocess.run(cmd,capture_output=True,timeout=25);assert run.returncode==0,run.stderr.decode('utf8',errors='replace')[-1000:]
   report=json.loads((dest/'result.json').read_text('utf8'));messages=[str(x.get('value','')) for x in report.get('events',[])]
   assert any(s.startswith('FETCH_STREAM_PASS ') for s in messages),[s[:500] for s in messages if 'FETCH_STREAM_' in s]
   assert opened==2 and probed==0 and not completed.is_set(),(opened,probed,completed.is_set())
   assert not report.get('fatal'),report.get('fatal')
   print('PASS actual native engine: headers/data before EOF, no eager body pull, cancellation/abort, JSON/clone; finite memory budget')
 finally:release.set();server.shutdown();server.server_close()

if __name__=='__main__':main()
