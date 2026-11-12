"""Actual Android native HLS keeps playing when its original signed URL expires."""
import argparse,base64,http.server,json,pathlib,subprocess,tempfile,threading,time
p=argparse.ArgumentParser();p.add_argument('--adb',default='adb');p.add_argument('--serial',required=True);p.add_argument('--output',type=pathlib.Path,required=True);a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
adb=[a.adb,'-s',a.serial]
with tempfile.TemporaryDirectory() as directory:
 root=pathlib.Path(directory)
 subprocess.run(['ffmpeg','-v','error','-nostdin','-y','-f','lavfi','-i','color=c=green:size=160x90:rate=25','-f','lavfi','-i','sine=frequency=440:sample_rate=44100','-t','30','-c:v','libx264','-threads','1','-pix_fmt','yuv420p','-g','25','-c:a','aac','-f','hls','-hls_time','1','-hls_list_size','0','media.m3u8'],cwd=root,check=True)
 (root/'index.html').write_text('''<!doctype html><video autoplay muted src="/media.m3u8?sign=first&t=69000001"></video><script>setTimeout(function(){var previous=new URL('/media.m3u8?sign=first&t=69000001',location.href).href,next=new URL('/media.m3u8?sign=renewed&t=69000002',location.href).href;function renew(){if(__host(JSON.stringify({op:'nativeHlsRefresh',previous:previous,url:next}))!==true)setTimeout(renew,500);}renew();},2000);</script>''','utf8')
 requests=[];started=None
 class Handler(http.server.SimpleHTTPRequestHandler):
  def __init__(self,*args,**kw):super().__init__(*args,directory=str(root),**kw)
  def log_message(self,*args):pass
  def do_GET(self):
   global started
   requests.append(self.path)
   if self.path.startswith('/media.m3u8'):
    if started is None:started=time.monotonic()
    elapsed=time.monotonic()-started
    if 'sign=first' in self.path and elapsed>4:
     self.send_response(404);self.send_header('Content-Length','0');self.end_headers();return
    first=max(0,min(26,int(elapsed)-1));body=('#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:'+str(first)+'\n'+''.join('#EXTINF:1,\nmedia'+str(i)+'.ts\n' for i in range(first,first+3))).encode()
    self.send_response(200);self.send_header('Content-Type','application/vnd.apple.mpegurl');self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
   else:super().do_GET()
 server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler);threading.Thread(target=server.serve_forever,daemon=True).start()
 try:
  url='webview://http://10.0.2.2:'+str(server.server_port)+'/index.html';encoded=base64.b64encode(url.encode()).decode()
  subprocess.run(adb+['shell','am','force-stop','xiao.bu.tv.litebrowser'],capture_output=True,check=True)
  result=subprocess.run(adb+['shell','am','instrument','-w','-e','mode','images','-e','siteBase64',encoded,'-e','decode','software','-e','directLite','true','-e','imageOrder','true','-e','auditSites','false','-e','strictPlayback','true','-e','captureFrames','true','-e','imageTimeoutMs','50000','-e','imageHoldMs','8000','xiao.bu.tv.litebrowser.test/xiao.bu.tv.HealthBalanceInstrumentation'],capture_output=True,timeout=100)
  text=result.stdout.decode('utf8','replace');(a.output/'instrumentation.txt').write_text(text,'utf8');data=next(json.loads(line) for line in text.splitlines() if line.startswith('{'));(a.output/'evidence.json').write_text(json.dumps(data,ensure_ascii=False,indent=2),'utf8')
  trial=data['imageTrials'][0];renewed=sum('sign=renewed' in path for path in requests)
  assert data.get('passed') and trial['playingAtEnd'] and renewed>=2,(trial.get('error'),renewed)
  print(json.dumps({'passed':True,'fixture':True,'renewedManifestRequests':renewed,'firstFrameMs':trial['firstFrameMs'],'advancedMs':trial['advancedMs'],'maxStallMs':trial['maxStallMs'],'preferencesRestored':data['preferencesRestored'],'systemVolumeRestored':data['systemVolumeRestored']}))
 finally:server.shutdown();server.server_close()
