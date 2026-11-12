"""A real non-IDR random-access fragment must retain AVC decoder parameters."""
import pathlib,subprocess,tempfile
root=pathlib.Path(__file__).resolve().parents[1]
code=r'''package xiao.bu.tv;
import java.nio.file.*;import java.io.*;
class Check {public static void main(String[] args)throws Exception{
 LiteFragmentTs.Feed feed=new LiteFragmentTs.Feed();feed.append(Files.readAllBytes(Paths.get(args[0])),0,32*1024*1024);
 try(OutputStream out=Files.newOutputStream(Paths.get(args[2]))){for(LiteFragmentTs.Result result:feed.append(Files.readAllBytes(Paths.get(args[1])),0,32*1024*1024)){
  boolean sps=false,pps=false;byte[] b=result.bytes;for(int i=0;i<b.length-5;i++)if(b[i]==0&&b[i+1]==0&&b[i+2]==0&&b[i+3]==1){sps|=(b[i+4]&31)==7;pps|=(b[i+4]&31)==8;}
  if(!sps||!pps)throw new AssertionError("TS fragment lost join parameters");out.write(b);
 }}
}}
'''
with tempfile.TemporaryDirectory() as folder:
 p=pathlib.Path(folder)
 subprocess.run(['ffmpeg','-v','error','-nostdin','-y','-f','lavfi','-i','testsrc2=size=160x90:rate=25','-t','6','-c:v','libx264','-threads','1','-g','50','-bf','2','-keyint_min','50','-sc_threshold','0','-x264-params','open-gop=1:scenecut=0','-pix_fmt','yuv420p','-an','-f','hls','-hls_time','2','-hls_segment_type','fmp4','-hls_fmp4_init_filename','init.mp4','-hls_list_size','0','video.m3u8'],cwd=p,check=True)
 (p/'Check.java').write_text(code,'utf8');subprocess.run(['javac','-encoding','UTF-8','-d',folder,str(root/'browser-core/litebrowser/src/main/java/xiao/bu/tv/LiteFragmentTs.java'),str(p/'Check.java')],check=True)
 subprocess.run(['ffmpeg','-v','error','-nostdin','-y','-f','lavfi','-i','testsrc2=size=160x90:rate=25','-t','2','-c:v','libx264','-threads','1','-g','25','-bf','0','-pix_fmt','yuv420p','-an','-movflags','frag_every_frame+empty_moov+default_base_moof','frames.mp4'],cwd=p,check=True)
 subprocess.run(['java','-cp',folder,'xiao.bu.tv.Check',str(p/'init.mp4'),str(p/'frames.mp4'),str(p/'per-frame.ts')],check=True)
 for index in [0,1]:
  ts=p/('out'+str(index)+'.ts');subprocess.run(['java','-cp',folder,'xiao.bu.tv.Check',str(p/'init.mp4'),str(p/('video'+str(index)+'.m4s')),str(ts)],check=True)
  result=subprocess.run(['ffmpeg','-v','error','-nostdin','-i',str(ts),'-map','0:v:0','-f','framemd5','-'],capture_output=True,timeout=15)
  frames=sum(1 for line in result.stdout.splitlines() if line and not line.startswith(b'#'))
  assert frames>=25,('Fragment',index,'decoded frames',frames,result.stderr.decode('utf8',errors='replace')[:350])
 print('PASS actual AVC/fMP4 -> MPEG-TS -> decoded frames, IDR and open-GOP random access after new initialization')
