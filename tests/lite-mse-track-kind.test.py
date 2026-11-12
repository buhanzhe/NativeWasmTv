"""Real parsed AAC in video/mp4 must remain audible alongside a video buffer."""
import argparse,json,os,pathlib,subprocess,tempfile,urllib.request
root=pathlib.Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser();parser.add_argument('--core',type=pathlib.Path,required=True);args=parser.parse_args()
code=r'''package xiao.bu.tv;
import java.nio.file.*;
class Check {public static void main(String[] a)throws Exception{
 try(LiteMseRelay relay=new LiteMseRelay()){
  for(int i=0;i<2;i++){
   String name=i==0?"audio":"video";
   relay.add(i+1,"video/mp4; codecs=\""+(i==0?"mp4a.40.2":"avc1.42c00a")+"\"");
   relay.append(i+1,Files.readAllBytes(Paths.get(a[0],name+"-init.mp4")),0);
   for(int n=0;n<3;n++)relay.append(i+1,Files.readAllBytes(Paths.get(a[0],name+n+".m4s")),0);
  }
  relay.end();if(!relay.ready())throw new AssertionError("Actual media not ready");
  System.out.println(relay.url());System.out.flush();System.in.read();
 }
}}
'''
with tempfile.TemporaryDirectory() as folder:
 p=pathlib.Path(folder)
 for name in ['audio','video']:
  command=['ffmpeg','-v','error','-nostdin','-y','-f','lavfi','-i','sine=frequency=440:sample_rate=44100' if name=='audio' else 'testsrc2=size=160x90:rate=25','-t','6']
  command+=['-c:a','aac','-vn'] if name=='audio' else ['-c:v','libx264','-threads','1','-g','50','-pix_fmt','yuv420p','-an']
  subprocess.run(command+['-f','hls','-hls_time','2','-hls_segment_type','fmp4','-hls_fmp4_init_filename',name+'-init.mp4','-hls_list_size','0',name+'.m3u8'],cwd=p,check=True)
 (p/'Check.java').write_text(code,'utf8')
 jar=args.core.resolve()/'lib/json-20180813.jar';assert jar.is_file(),'Build litecore dependencies first'
 sources=[root/'browser-core/litebrowser/src/main/java/xiao/bu/tv'/name for name in ['LiteFragmentTs.java','LiteMseRelay.java','LiteLoopbackServer.java']]
 subprocess.run(['javac','-encoding','UTF-8','-cp',str(jar),'-d',folder,*map(str,sources),str(p/'Check.java')],check=True)
 process=subprocess.Popen(['java','-cp',folder+os.pathsep+str(jar),'xiao.bu.tv.Check',folder],stdin=subprocess.PIPE,stdout=subprocess.PIPE,text=True)
 try:
  url=process.stdout.readline().strip();assert url.startswith('http://127.0.0.1:')
  master=urllib.request.urlopen(url,timeout=5).read().decode();assert 'TYPE=AUDIO' in master and 'URI="1.m3u8"' in master and master.endswith('2.m3u8\n'),master
  probe=json.loads(subprocess.check_output(['ffprobe','-v','error','-show_streams','-of','json',url],timeout=15))
  assert {'audio','video'} <= {s['codec_type'] for s in probe['streams']},probe
  result=subprocess.run(['ffmpeg','-v','error','-nostdin','-i',url,'-t','2','-map','0:a:0','-af','volumedetect','-f','null','-'],capture_output=True,timeout=15)
  assert result.returncode==0,result.stderr.decode('utf8','replace')
 finally:
  if process.poll() is None:process.stdin.write('\n');process.stdin.flush();process.wait(timeout=5)
 print('PASS actual AAC carried in video/mp4 + AVC -> separate HLS tracks -> real audio/video decoder discovery and audio decode')
