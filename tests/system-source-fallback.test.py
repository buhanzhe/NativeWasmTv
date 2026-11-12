"""Confirmed HTTP absence may use a backup without weakening decoder protection."""
import os,subprocess,tempfile
from pathlib import Path
root=Path(__file__).resolve().parents[1]
source=(root/'app/src/main/java/xiao/bu/tv/MainActivity.java').read_text('utf8')
start=source.index('    private boolean fallbackMissingSystemSource(');end=source.index('    private void showPlaybackForbidden(',start)
java='''package xiao.bu.tv;import java.io.IOException;
class FallbackCheck {
 boolean prepared;int calls;Proxy proxy=new Proxy();static class Proxy{Throwable failure;Throwable lastPlaybackFailure(){return failure;}}
 static class Channel{int count=2;int sourceCount(){return count;}}
 void handleUnavailableSource(String label,String detail){calls++;}
 METHODS
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[]args){
  FallbackCheck c=new FallbackCheck();Channel channel=new Channel();
  for(String message:new String[]{"Upstream HTTP 404","HTTP/1.1 410 Gone"}){c.proxy.failure=new IOException("outer",new IOException(message));int before=c.calls;check(c.fallbackMissingSystemSource(channel,1)&&c.calls==before+1);}
  for(String message:new String[]{"404","HTTP 4040","HTTP 403","HTTP 503","ION allocation failed","MediaCodec died"}){c.proxy.failure=new IOException(message);int before=c.calls;check(!c.fallbackMissingSystemSource(channel,1)&&before==c.calls);}
  c.proxy.failure=new IOException("HTTP 404");c.prepared=true;check(!c.fallbackMissingSystemSource(channel,1));c.prepared=false;
  check(!c.fallbackMissingSystemSource(channel,100));channel.count=1;check(!c.fallbackMissingSystemSource(channel,1));channel.count=2;c.proxy=null;check(!c.fallbackMissingSystemSource(channel,1));
  check(!PlaybackHttpError.isMissing(null));Throwable a=new IOException(),b=new IOException();a.initCause(b);b.initCause(a);check(!PlaybackHttpError.isMissing(a));
  System.out.println("PASS confirmed 404/410 and nested causes use backups; no switch for decoder crash, ambiguous IO, prepared/single-source streams or stale HTTP text");
 }
}'''.replace('METHODS',source[start:end])
with tempfile.TemporaryDirectory() as folder:
 p=Path(folder)/'FallbackCheck.java';p.write_text(java,'utf8');jdk=Path(os.environ.get('JAVA_HOME','C:/Users/Administrator/.jdks/jbr-17.0.14'))/'bin'
 files=[p,*[root/'app/src/main/java/xiao/bu/tv'/name for name in ['PlaybackHttpError.java','SystemPlaybackError.java']]]
 subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','-d',folder,*map(str,files)],check=True)
 subprocess.run([str(jdk/'java.exe'),'-cp',folder,'xiao.bu.tv.FallbackCheck'],check=True,timeout=5)
