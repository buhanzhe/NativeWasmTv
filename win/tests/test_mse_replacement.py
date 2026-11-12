"""Actual page -> two owned MSE sessions -> real decoded frames across a switch."""
import argparse,http.server,pathlib,subprocess,tempfile,threading,json,hashlib,sys

def main():
 p=argparse.ArgumentParser();p.add_argument('--bundle',type=pathlib.Path,required=True);p.add_argument('--output',type=pathlib.Path,required=True);p.add_argument('--detached-owner',action='store_true');a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
 with tempfile.TemporaryDirectory() as temp:
  root=pathlib.Path(temp)
  subprocess.run(['ffmpeg','-v','error','-nostdin','-y','-f','lavfi','-i','testsrc2=size=160x90:rate=25','-f','lavfi','-i','sine=frequency=440:sample_rate=44100','-t','12','-c:v','libx264','-profile:v','baseline','-threads','1','-pix_fmt','yuv420p','-g','50','-c:a','aac','-f','hls','-hls_time','2','-hls_segment_type','fmp4','-hls_fmp4_init_filename','init.mp4','-hls_list_size','0','media.m3u8'],cwd=root,check=True)
  names=['init.mp4',*[x.name for x in sorted(root.glob('*.m4s'))]]
  library=pathlib.Path(__file__).resolve().parents[2]/'browser-core/litebrowser/desktop/test/assets/hls-1.5.18.min.js'
  (root/'hls.js').write_bytes(library.read_bytes())
  page='''<!doctype html><video id="video" autoplay muted></video><script>
  (async function(){const code=await fetch('/hls.js').then(r=>r.text()),Constructor=(function(){var module={exports:{}},exports=module.exports,define;eval(code);return module.exports;})(),video=document.getElementById('video');var player;
  function start(){if(player)player.destroy();player=new Constructor({enableWorker:false,maxBufferLength:12,maxMaxBufferLength:20});player.loadSource('/media.m3u8');player.attachMedia(video);video.play().catch(()=>{});}
  start();setTimeout(start,5000);})().catch(e=>console.error(e.stack||String(e)));
  </script>'''
  if a.detached_owner:
   page=page.replace(",video=document.getElementById('video');var player;",";let video=document.getElementById('video');var player;")
   page=page.replace('if(player)player.destroy();','if(player){player.destroy();video.remove();video=document.createElement("video");video.id="replacement";video.autoplay=true;video.muted=true;}')
   page=page.replace('player.attachMedia(video);','player.attachMedia(video);if(!video.isConnected)document.body.appendChild(video);')
  (root/'index.html').write_text(page,'utf8')
  class Handler(http.server.SimpleHTTPRequestHandler):
   def __init__(self,*args,**kwargs):super().__init__(*args,directory=str(root),**kwargs)
   def log_message(self,*args):pass
  server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler);threading.Thread(target=server.serve_forever,daemon=True).start()
  try:
   playlist=(a.bundle/'playlists/webview.txt').read_bytes();manifest={'kind':'local-fixture','source_commit':'local fixture','playlist_sha256':hashlib.sha256(playlist).hexdigest(),'channels':[{'id':'owned-mse-switch','name':'Owned MSE switch','source_line':None,'url':'webview://http://127.0.0.1:'+str(server.server_port)+'/index.html'}]};selection=a.output/'selection.json';selection.write_text(json.dumps(manifest),'utf8');out=a.output/'run'
   result=subprocess.run([sys.executable,str(pathlib.Path(__file__).with_name('test_live_channels.py')),'--bundle',str(a.bundle),'--selection',str(selection),'--fixture','--seconds','15','--jobs','1','--output',str(out)])
   report=json.loads((out/'report.json').read_text('utf8'));row=report['channels'][0]
   assert result.returncode==0 and row['status']=='passed',row
   assert row.get('source_changes',0)>=1,row
   assert len(row.get('decoder_generations',[]))>=2,row
   print('PASS real MSE replacement, generation-scoped clocks, actual decoder progress and process cleanup')
  finally:server.shutdown();server.server_close()

if __name__=='__main__':main()
