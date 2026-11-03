"""Run the real rolling rewrite: retained history must not grow the live holdback."""
from pathlib import Path
import os, subprocess, tempfile
root=Path(__file__).resolve().parents[1]
source=(root/'app/src/main/java/xiao/bu/tv/HlsProxyServer.java').read_text(encoding='utf8')
def method(signature):
    a=source.index(signature);b=source.index('{',a)+1;depth=1
    while depth:depth+=(source[b]=='{')-(source[b]=='}');b+=1
    return source[a:b]
java=r'''package xiao.bu.tv;
import java.util.*;import java.util.concurrent.*;import java.net.*;import java.io.*;
class LiveWindowCheck {
 static final int LIVE_PLAYLIST_HISTORY_LIMIT=12,CCTV_MIN_PLAYABLE_SEGMENTS=2;
 static final String TAG="test";
 boolean remoteConsumer;int cctvLiveEdgeHoldBackSegments=2,adaptiveBufferLevel,cctvStartupDecryptSegments;
 String lastCctvRequestedUrl,lastCctvPlaylistUrl;long cctvStartupGeneration;
 Object cctvStartupLock=new Object();
 Map<String,LinkedHashMap<String,PlaylistSegment>> playlistSegmentHistory=new HashMap<>();
 Map<String,Boolean> cctvStartupReady=new HashMap<>();
 Map<String,FutureTask<byte[]>> cmgSegmentTasks=new HashMap<>(),cctvSegmentTasks=new HashMap<>();
 Map<String,byte[]> cctvSegmentCache=new HashMap<>();Map<String,String> cctvNextSegments=new LinkedHashMap<>();
 static class Log {static void w(String t,String s){}}
 static class YangshipinSegment {long number;}
 static class PlaylistSegment {long sequence;String url;List<String>tags;PlaylistSegment(long s,String u,List<String>t){sequence=s;url=u;tags=t;}}
 YangshipinSegment parseYangshipinSegment(String s){return null;}
 long parseMediaSequence(String[] lines){for(String s:lines)if(s.startsWith("#EXT-X-MEDIA-SEQUENCE:"))return Long.parseLong(s.substring(22));return 0;}
 String rewritePlaylistTagUris(URI b,String s){return s;}
 void rememberSegmentDuration(String u,List<String>s){}
 PlaylistSegment lastPlaylistSegment(LinkedHashMap<String,PlaylistSegment>h){PlaylistSegment last=null;for(PlaylistSegment s:h.values())last=s;return last;}
 boolean isYangshipinUrl(String s){return s.contains("ysp");}boolean needsH5eDecrypt(String s){return !isYangshipinUrl(s);}
 List<String>buildCmgPrefetchWindowLocked(List<PlaylistSegment>s){return Collections.emptyList();}
 List<String>buildCctvPrefetchWindowLocked(List<PlaylistSegment>s,boolean b){return Collections.emptyList();}
 void prefetchCmgSegments(List<String>s){}void prefetchCctvSegments(List<String>s){}void prefetchCctvSegment(String s){}
 void startCctvPlaylistMonitor(String s){}boolean ensureCctvStartupGate(String u,List<PlaylistSegment>s){return false;}
 boolean supportsH5eStreaming(){return true;}boolean isCctvSegmentReady(String s){return true;}
 String proxyUrl(String s){return s;}
 String segmentName(String s){return s;}
 METHODS
 String rewrite(int sequence,int count)throws Exception{
  StringBuilder body=new StringBuilder("#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:"+sequence+"\n");
  for(int i=0;i<count;i++)body.append("#EXTINF:4,\n").append(sequence+i).append(".ts\n");
  return rewriteBufferedMediaPlaylist("https://test/cctv/index.m3u8",new URI("https://test/cctv/"),body.toString().split("\n"));
 }
 static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
 static int last(String body){int value=-1;for(String s:body.split("\n"))if(s.endsWith(".ts"))value=Integer.parseInt(s.substring(s.lastIndexOf('/')+1,s.length()-3));return value;}
 public static void main(String[]args)throws Exception{
  for(int mode:new int[]{0,1,2,3})for(int count:new int[]{2,3,4,6}){
   LiveWindowCheck p=new LiveWindowCheck();p.cctvLiveEdgeHoldBackSegments=mode;
   int retained=Math.min(mode,Math.max(0,count-2));
   for(int n=100;n<120;n++){
    String body=p.rewrite(n,count);int expected=n+count-1-retained;
    check(last(body)==expected,"History enlarged source holdback mode="+mode+" count="+count+" seq="+n+" body="+body);
    check(body.contains("#EXT-X-TARGETDURATION:4")&&body.contains("#EXTINF:4,"),"Changed media duration");
   }
  }
  LiveWindowCheck p=new LiveWindowCheck();check(last(p.rewrite(100,3))==101,"Initial pair");
  check(last(p.rewrite(101,3))==102,"First refresh withheld an already decrypted next segment");
  check(last(p.rewrite(102,3))==103,"Second refresh stalled window");
  LiveWindowCheck slow=new LiveWindowCheck();slow.rewrite(100,3);
  List<PlaylistSegment> observed=new ArrayList<>();
  for(int n=101;n<=105;n++) observed.add(new PlaylistSegment(n,"https://test/cctv/"+n+".ts",Arrays.asList("#EXTINF:4,")));
  slow.rememberBufferedSegments("https://test/cctv/index.m3u8",observed);
  String resumed=slow.rewrite(104,3);
  check(resumed.contains("100.ts") && resumed.contains("103.ts"),"Lost monitor-observed history during slow TS");
  String gap=slow.rewrite(200,3);
  check(!gap.contains("100.ts") && gap.contains("200.ts"),"Real unobserved gap must still reset");
  System.out.println("PASS actual rolling rewrite: 320 mode/window/history cases, first-refresh progress and unchanged timing tags");
 }
}'''
signatures=['    private List<PlaylistSegment> rememberBufferedSegments(',
            '    private String rewriteBufferedMediaPlaylist(',
            '    private int cctvLiveEdgeHoldBack(',
            '    private int cctvLiveEdgeHoldBackForSegmentCount(']
java=java.replace('METHODS','\n'.join(method(s)for s in signatures))
with tempfile.TemporaryDirectory()as folder:
    target=Path(folder)/'LiveWindowCheck.java';target.write_text(java,encoding='utf8')
    jdk=Path(os.environ.get('JAVA_HOME','C:/Users/Administrator/.jdks/jbr-17.0.14'))/'bin'
    subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','-d',folder,str(target),str(root/'app/src/main/java/xiao/bu/tv/HlsUri.java')],check=True)
    subprocess.run([str(jdk/'java.exe'),'-cp',folder,'xiao.bu.tv.LiveWindowCheck'],check=True)
