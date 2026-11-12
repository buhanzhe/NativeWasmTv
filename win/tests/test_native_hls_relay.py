"""Selected native HLS uses the same bounded, header-preserving relay on Windows."""
import argparse,http.server,pathlib,subprocess,tempfile,threading,json,hashlib,sys,time,urllib.request
p=argparse.ArgumentParser();p.add_argument('--bundle',type=pathlib.Path,required=True);p.add_argument('--output',type=pathlib.Path,required=True);p.add_argument('--live',action='store_true');p.add_argument('--seconds',type=int);p.add_argument('--dependency-retry',action='store_true');p.add_argument('--dependency-http-timeout',action='store_true');p.add_argument('--renewal',action='store_true');a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
with tempfile.TemporaryDirectory() as directory:
 root=pathlib.Path(directory)
 subprocess.run(['ffmpeg','-v','error','-nostdin','-y','-f','lavfi','-i','color=c=green:size=160x90:rate=25','-f','lavfi','-i','sine=frequency=440:sample_rate=44100','-t','12','-c:v','libx264','-threads','1','-pix_fmt','yuv420p','-g','25','-c:a','aac','-f','hls','-hls_time','1','-hls_list_size','0','media.m3u8'],cwd=root,check=True)
 if a.live:
  manifest=root/'media.m3u8';manifest.write_text(manifest.read_text('utf8').replace('#EXT-X-ENDLIST',''),'utf8')
 (root/'index.html').write_text('<!doctype html><video autoplay muted src="/media.m3u8"></video>','utf8')
 if a.dependency_http_timeout:
  (root/'index.html').write_text('<!doctype html><video autoplay muted></video><script src="/startup.js"></script>','utf8')
  (root/'startup.js').write_text("document.querySelector('video').src='/media.m3u8';",'utf8')
 if a.renewal:
  (root/'index.html').write_text('''<!doctype html><video autoplay muted src="/media.m3u8?sign=first&t=69000001"></video><script>setTimeout(function(){var previous=new URL('/media.m3u8?sign=first&t=69000001',location.href).href,next=new URL('/media.m3u8?sign=renewed&t=69000002',location.href).href;if(__host(JSON.stringify({op:'nativeHlsRefresh',previous:previous,url:new URL('/another.m3u8?sign=renewed&t=69000002',location.href).href}))!==false)throw Error('Channel change accepted');if(__host(JSON.stringify({op:'nativeHlsRefresh',previous:previous,url:next}))!==true)throw Error('Owned renewal rejected');},1500);</script>''','utf8')
 started=time.monotonic()
 requests=[]
 class Handler(http.server.SimpleHTTPRequestHandler):
  def __init__(self,*args,**kw):super().__init__(*args,directory=str(root),**kw)
  def log_message(self,*args):pass
  def do_GET(self):
   requests.append(self.path)
   if a.dependency_http_timeout and self.path=='/startup.js' and requests.count('/startup.js')==1:
    time.sleep(9);self.close_connection=True;return
   if a.renewal and self.path.startswith('/media.m3u8?sign=first') and time.monotonic()-started>2:
    self.send_response(404);self.send_header('Content-Length','0');self.end_headers();return
   if a.dependency_retry and self.path=='/index.html' and requests.count('/index.html')==2:
    # Test runner preflight is the first GET; the first actual page fails.
    body=b"<!doctype html><script>Promise.reject(new Error('Loader timeout'));</script>";self.send_response(200);self.send_header('Cache-Control','no-store');self.send_header('Content-Length',str(len(body)));self.send_header('Content-Type','text/html');self.end_headers();self.wfile.write(body)
   else:super().do_GET()
 server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler);threading.Thread(target=server.serve_forever,daemon=True).start()
 try:
  playlist=(a.bundle/'playlists/webview.txt').read_bytes();data={'kind':'local-fixture','source_commit':'fixture','playlist_sha256':hashlib.sha256(playlist).hexdigest(),'channels':[{'id':'native-hls-relay','name':'Selected native HLS relay','source_line':None,'url':'webview://http://127.0.0.1:'+str(server.server_port)+'/index.html'}]};selection=a.output/'selection.json';selection.write_text(json.dumps(data),'utf8')
  renewal_errors=[];renewal_verified=[]
  def check_renewed_transport():
   try:
    file=a.output/'run/native-hls-relay/media-private.json';deadline=time.monotonic()+15
    while not file.exists() and time.monotonic()<deadline:time.sleep(.1)
    time.sleep(3)
    url=json.loads(file.read_text('utf8'))['url']
    response=urllib.request.urlopen(url,timeout=5);assert response.status==200 and b'#EXTM3U' in response.read();renewal_verified.append(True)
   except Exception as error:renewal_errors.append(str(error))
  checker=threading.Thread(target=check_renewed_transport) if a.renewal else None
  if checker:checker.start()
  result=subprocess.run([sys.executable,str(pathlib.Path(__file__).with_name('test_live_channels.py')),'--bundle',str(a.bundle),'--selection',str(selection),'--fixture','--seconds',str(a.seconds if a.seconds is not None else 2 if a.live else 8),'--jobs','1','--output',str(a.output/'run')])
  if checker:
   checker.join(timeout=10);assert not checker.is_alive() and not renewal_errors and renewal_verified,renewal_errors
   assert sum(x.startswith('/media.m3u8?sign=renewed') for x in requests)>=2,requests
   assert not any(x.startswith('/another.m3u8') for x in requests),requests
  d=json.loads((a.output/'run/report.json').read_text('utf8'));row=d['channels'][0]
  assert result.returncode==0 and row['status']=='passed',row
  private=json.loads((a.output/'run/native-hls-relay/media-private.json').read_text('utf8'));assert not private.get('virtualMse') and private['url'].startswith('http://127.0.0.1:') and not private['headers'] and bool(private.get('liveHls'))==a.live,private
  if a.dependency_retry or a.dependency_http_timeout:
   evidence=json.loads((a.output/'run/native-hls-relay/page-handoff.json').read_text('utf8'));assert evidence.get('dependencyRetries')==1 and requests.count('/index.html')==3,evidence
   if a.dependency_http_timeout:assert requests.count('/startup.js')==2,requests
  print('PASS original native HLS -> shared bounded loopback transport -> actual AV decode and frame; clean owner shutdown')
 finally:server.shutdown();server.server_close()
