"""Real bounded reporter: scope, sanitization, throttling, asynchronous context and SDK attachments."""
from pathlib import Path
import os,subprocess,tempfile
root=Path(__file__).resolve().parents[1]
java=r'''package xiao.bu.tv;
import java.util.*;import java.util.concurrent.*;
public class Check {
 static class Clock implements PlayerErrorReports.Clock {long time;public long now(){return time;}}
 static class Queue implements Executor {List<Runnable> tasks=new ArrayList<>();public void execute(Runnable task){tasks.add(task);}void flush(){List<Runnable> list=new ArrayList<>(tasks);tasks.clear();for(Runnable task:list)task.run();}}
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static String line(int pid,String level,String tag,String text){return "11-02 12:00:00.000 "+pid+" 456 "+level+" "+tag+": "+text;}
 static String details(Throwable t){return ((PlayerErrorReports.PlaybackFailure)t).details;}
 public static void main(String[] args){
  Clock clock=new Clock();Queue queue=new Queue();List<Throwable> sent=new ArrayList<>();
  PlayerErrorReports reports=new PlayerErrorReports(clock,queue,(error,thread)->sent.add(error));
  PlayerErrorReports.Scene a=reports.begin(1,"IJK","Channel A","https://user:password@cdn.example/path?token=topsecret","auto",1);
  PlayerErrorReports.Tail tail=reports.new Tail(123,"nonce");
  tail.line(line(123,"E","IJKMEDIA","PPS id out of range: 0"));check(queue.tasks.isEmpty(),"old buffered errors ignored before marker");
  tail.line(line(123,"I",PlayerErrorReports.MARKER_TAG,"nonce:"+a.id));
  tail.line(line(999,"E","IJKMEDIA","PPS id out of range: 0"));
  tail.line(line(123,"E","IJKMEDIA","csd-0: naked"));
  tail.line(line(123,"E","IJKMEDIA","configure acodec:0x12"));
  tail.line(line(123,"E","IJKMEDIA","broken pipe EPIPE"));
  String[][] noise={
   {"AudioTrack","pause(): notifyTrackSessionId 123"},{"AudioTrack","stop(): notifyTrackSessionId 123"},
   {"AudioTrack","start(): notifyTrackSessionId 123"},{"AudioTrack","start(123): prior state:STATE_PAUSED"},
   {"IJKMEDIA","SDL_JNI_ThreadDestroyed: [123] didn't call SDL_JNI_DetachThreadEnv() explicity"},
   {"IJKMEDIA","SDL_AMediaCodecJava_stop: stop"},{"IJKMEDIA","SDL_Overlay(ffmpeg): overlay_free_l(123)"},
   {"IJKMEDIA","IJK_GLES2_Renderer_TexCoords_cropRight"},{"MediaCodec","Media Quality Service not found."},
   {"ACodec","configureCodec_amlogic_processVideoHwComposed,videoDecoder 1"},
   {"MainActivity","Unable to resolve dynamic source java.io.InterruptedIOException: 源地址解析已取消"}};
  for(String[] row:noise){tail.line(line(123,"E",row[0],row[1]));check(PlayerErrorReports.diagnosticOnly(row[0],row[1].toLowerCase(java.util.Locale.US)),"known diagnostic suppressed: "+row[1]);}
  String[][] real={{"AudioTrack","obtainBuffer: timeout"},{"MediaCodec","Codec reported err 0x80000000/UNKNOWN_ERROR"},
   {"IJKMEDIA","Failed to reconnect at 123"},{"IJKMEDIA","Invalid NAL unit 0, skipping."},
   {"MainActivity","Unable to resolve dynamic source java.net.SocketTimeoutException: 源地址内容识别超时"}};
  for(String[] row:real)check(!PlayerErrorReports.diagnosticOnly(row[0],row[1].toLowerCase(java.util.Locale.US)),"real failures preserved");
  check(queue.tasks.isEmpty(),"other PID, compatibility dumps and intentional disconnects filtered");
  tail.line(line(123,"E","IJKMEDIA","PPS id out of range: 0 url=https://cdn.example/secret?token=secret"));
  for(int i=0;i<10000;i++)tail.line(line(123,"E","IJKMEDIA","PPS id out of range: "+i));
  check(queue.tasks.size()==1,"native error flood coalesced");check(a.logs().length()<=6024,"native context bounded");
  reports.error(1,"IJK","Error_1_-1007","what=1 extra=-1007",null);
  check(queue.tasks.size()==2,"callback not suppressed by native log budget");
  reports.error(0,"IJK","old","wrong channel",null);check(queue.tasks.size()==2,"stale callback ignored");
  PlayerErrorReports.Scene b=reports.begin(2,"SystemPlayer","Channel B","https://other.example/live?auth=secret","hardware",0);
  queue.flush();check(sent.size()==2,"asynchronous sink");
  for(Throwable t:sent){String d=details(t);check(d.contains("Channel A")&&!d.contains("Channel B"),"queued context immutable across switch");check(!d.contains("topsecret")&&!d.contains("user:password")&&!d.contains("/path?"),"source credentials/path/query omitted");}
  clock.time=11000;
  tail.line(line(123,"E","IJKMEDIA","new decoder error"));queue.flush();check(details(sent.get(sent.size()-1)).contains("Channel A"),"ordered native logs retain old scene until marker");
  tail.line(line(123,"I",PlayerErrorReports.MARKER_TAG,"nonce:"+b.id));clock.time=22000;
  tail.line(line(123,"E","MediaCodec","decoder configure failed token=abcdef"));queue.flush();
  check(details(sent.get(sent.size()-1)).contains("Channel B"),"new native scene restored");
  check(!details(sent.get(sent.size()-1)).contains("abcdef"),"standalone secrets redacted");
  clock.time=24000;RuntimeException caught=new IllegalStateException("failed https://cdn.example/private?signature=abc Authorization=xyz");
  caught.setStackTrace(new StackTraceElement[]{new StackTraceElement("real.Player","prepare","Player.java",123)});
  reports.error(2,"SystemPlayer","PrepareException",caught.getMessage(),caught);queue.flush();
  Throwable t=sent.get(sent.size()-1);check(t.getStackTrace()[0].getMethodName().equals("prepare"),"real exception stack preserved");
  check(details(t).contains("IllegalStateException")&&!details(t).contains("signature=abc")&&!details(t).contains("xyz"),"caught exception redacted");
  check(t.getMessage().length()<1000&&!t.getMessage().contains("Channel B")&&!t.getMessage().contains("[nTvPlayer:"),"SDK signature excludes volatile channel/ID below truncation limit");
  check(((PlayerErrorReports.PlaybackFailure)t).id.length()>0&&details(t).contains("Recent player errors"),"full diagnostics available to official Bugly log attachment");
  reports.end();clock.time+=2000;int count=sent.size();reports.error(2,"IJK","ended","skip",null);queue.flush();check(sent.size()==count,"ended playback ignored");
  reports.unscopedException("IJK","ReleaseException",new IllegalStateException("release failed"));queue.flush();check(sent.size()==count+1&&!details(sent.get(sent.size()-1)).contains("Channel B")&&details(sent.get(sent.size()-1)).contains("channel=unavailable"),"old release exception never attributed to new channel");
  Clock c=new Clock();Queue q=new Queue();List<Throwable> many=new ArrayList<>();PlayerErrorReports budget=new PlayerErrorReports(c,q,(e,th)->many.add(e));budget.begin(9,"IJK","budget","http://cdn.example/test.flv","auto",0);
  for(int i=0;i<100;i++){c.time=i*2000;budget.error(9,"IJK","different"+i,"different",null);}q.flush();check(many.size()==10,"ten reports per ten-minute window");
  c.time=600001;budget.error(9,"IJK","fresh","new window",null);q.flush();check(many.size()==11,"window resets");
  PlayerErrorReports failing=new PlayerErrorReports(c,task->{throw new RejectedExecutionException();},(e,th)->{});failing.begin(1,"IJK","safe","http://cdn.example/a.flv","auto",0);failing.error(1,"IJK","error","safe",null);
  check(!PlayerErrorReports.clean("Authorization: Bearer SECRET123\nCookie: a=ABC; b=DEF",1000).contains("SECRET123"),"bearer header redacted");
  check(!PlayerErrorReports.clean("Cookie: a=ABC; b=DEF",1000).contains("DEF"),"entire cookie header redacted");
  PlayerErrorReports.PlaybackFailure first=new PlayerErrorReports.PlaybackFailure(1,"same","first channel"),second=new PlayerErrorReports.PlaybackFailure(99,"same","second channel");
  check(first.getMessage().equals(second.getMessage())&&!first.id.equals(second.id),"same issue groups while attached contexts retain independent IDs");
  System.out.println("PASS native PID/marker isolation, 10000 error flood, bounded context, per-error/global budgets, stale callbacks, source/exception redaction, real stack, async SDK attachment identity, queue rejection");
 }
}'''
with tempfile.TemporaryDirectory() as folder:
 out=Path(folder);pkg=out/'xiao/bu/tv';pkg.mkdir(parents=True)
 (pkg/'PlayerErrorReports.java').write_text((root/'app/src/main/java/xiao/bu/tv/PlayerErrorReports.java').read_text(encoding='utf8'),encoding='utf8')
 (pkg/'Check.java').write_text(java,encoding='utf8');jdk=Path(os.environ.get('JAVA_HOME','C:/Users/Administrator/.jdks/jbr-17.0.14'))/'bin'
 subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','-d',folder,*map(str,pkg.glob('*.java'))],check=True)
 subprocess.run([str(jdk/'java.exe'),'-cp',folder,'xiao.bu.tv.Check'],check=True)
