from pathlib import Path
import subprocess,tempfile,os
root=Path(__file__).resolve().parents[1];s=(root/'app/src/main/java/xiao/bu/tv/MainActivity.java').read_text('utf8');a=s.index('    private void probeLegacySystemVideoReady(');b=s.index('    private void markAndroidHlsVideoReady(',a);method=s[a:b]
java='''import java.util.*;class ProbeCheck {
 static class Build {static class VERSION {static int SDK_INT=15;}}
 static class SystemClock {static long now;static long elapsedRealtime(){return now;}}
 static class AndroidMediaPlayer {long position;boolean playing=true;int width=320,height=180;long getCurrentPosition(){return position;}boolean isPlaying(){return playing;}int getVideoWidth(){return width;}int getVideoHeight(){return height;}}
 static class LegacyStreamMediaPlayer extends AndroidMediaPlayer {}
 static class Channel {}
 static class Queue {Runnable pending;int posts;void postDelayed(Runnable r,long delay){pending=r;posts++;}void tick(){SystemClock.now+=250;Runnable r=pending;pending=null;if(r!=null)r.run();}}
 AndroidMediaPlayer player=new AndroidMediaPlayer();int playRequestId=1,marks;boolean prepared=true,videoRenderingStarted,windowContentActive=true,buffering,finishing;Queue channelBar=new Queue();boolean isFinishing(){return finishing;}
 void markAndroidHlsVideoReady(AndroidMediaPlayer p,Channel c,int r){marks++;videoRenderingStarted=true;}
 METHOD
 void start(){SystemClock.now=0;probeLegacySystemVideoReady(player,new Channel(),1);}
 static void ok(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  ProbeCheck a=new ProbeCheck();a.start();a.player.position=120;a.channelBar.tick();ok(a.marks==1&&a.channelBar.pending==null);
  a=new ProbeCheck();a.start();for(int i=0;i<10;i++)a.channelBar.tick();ok(a.marks==0&&a.channelBar.posts==6);
  a=new ProbeCheck();a.start();a.player.position=50;a.channelBar.tick();ok(a.marks==0);a.player.position=130;a.channelBar.tick();ok(a.marks==1);
  for(int guard=0;guard<7;guard++){a=new ProbeCheck();a.start();a.player.position=300;if(guard==0)a.buffering=true;if(guard==1)a.windowContentActive=false;if(guard==2)a.player.playing=false;if(guard==3)a.player.width=0;if(guard==4)a.playRequestId=2;if(guard==5)a.player=new AndroidMediaPlayer();if(guard==6)a.prepared=false;a.channelBar.tick();ok(a.marks==0);}
  a=new ProbeCheck();a.player=new LegacyStreamMediaPlayer();a.start();ok(a.channelBar.posts==0);Build.VERSION.SDK_INT=19;a=new ProbeCheck();a.start();ok(a.channelBar.posts==0);
  System.out.println("PASS legacy startup clock advance, size/buffering/pause/stale guards, bounded six ticks and unchanged newer/continuous paths");
 }
}'''.replace('METHOD',method)
with tempfile.TemporaryDirectory() as d:
 p=Path(d);(p/'ProbeCheck.java').write_text(java,'utf8');subprocess.run(['javac','-encoding','UTF-8',str(p/'ProbeCheck.java')],check=True);subprocess.run(['java','-cp',d,'ProbeCheck'],check=True)
