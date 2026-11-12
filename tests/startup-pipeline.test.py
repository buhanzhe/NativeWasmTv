"""Execute production first-segment selection, deferred metadata and milestone guards."""
from pathlib import Path
import os, subprocess, tempfile

root = Path(__file__).resolve().parents[1]
source = (root/'app/src/main/java/xiao/bu/tv/HlsProxyServer.java').read_text(encoding='utf8')
def method(signature):
    start = source.index(signature)
    end = source.index('{', start) + 1
    depth = 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]

java = r'''import java.util.*;import java.util.concurrent.*;import java.net.*;
class PipelineCheck {
 static class AppLog {static void i(String tag,String message){}}
 static final int CMG_REMOTE_PREFETCH_WINDOW=3, CMG_LOCAL_PREFETCH_WINDOW=3,
   CCTV_PARALLEL_PREFETCH_WINDOW=3,CCTV_MIN_PLAYABLE_SEGMENTS=2;
 static final String TAG="check";
 boolean continuousTsMode,remoteConsumer,systemPlayerStartsAtFirst,parallelCctvDecrypt,readAheadPaused,
  running=true,sourceVideoProbeEnabled=true,deferStartupMetadata=true,playbackVideoReady;
 int adaptiveBufferLevel,cctvLiveEdgeHoldBackSegments=2,metadataCalls;long createdAt;
 String lastCctvRequestedUrl;byte[] deferredMetadataSample;
 Object sourceProbeLock=new Object();ServerSocket serverSocket;
 Map<String,String> cctvNextSegments=new HashMap<>();
 ConcurrentHashMap<String,Boolean> startupMilestones=new ConcurrentHashMap<>();
 Map<String,Integer> costs=new HashMap<>();
 ExecutorService genericPrefetchWorkers=Executors.newSingleThreadExecutor();
 static class SystemClock {static long elapsedRealtime(){return System.nanoTime()/1000000;}}
 static class Log {static void i(String t,String s){}}
 void startupCost(String s,long d){costs.put(s,costs.getOrDefault(s,0)+1);}
 String segmentName(String url){return url;}
 void sampleSourceVideoBody(byte[] body){metadataCalls++;}
 METHODS
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 public static void main(String[]args)throws Exception{
  PipelineCheck p=new PipelineCheck();
  try {
   List<String> urls=Arrays.asList("s0","s1","s2","s3","s4","s5");
   for(int i=0;i<urls.size()-1;i++)p.cctvNextSegments.put(urls.get(i),urls.get(i+1));
   p.systemPlayerStartsAtFirst=true;
   check(p.buildCmgPrefetchWindowFromUrlsLocked(urls).get(0).equals("s0"),"ICS CMG prefetched edge ahead of player");
   check(p.buildCctvPrefetchWindowFromUrlsLocked(urls,true).get(0).equals("s0"),"ICS H5E prefetched edge ahead of player");
   p.systemPlayerStartsAtFirst=false;
   check(p.buildCmgPrefetchWindowFromUrlsLocked(urls).get(0).equals("s3"),"Changed IJK CMG startup policy");
   check(p.buildCctvPrefetchWindowFromUrlsLocked(urls,true).get(0).equals("s3"),"Changed IJK H5E startup policy");
   p.lastCctvRequestedUrl="s1";
   for(boolean legacy:new boolean[]{false,true}){
    p.systemPlayerStartsAtFirst=legacy;
    check(p.buildCmgPrefetchWindowFromUrlsLocked(urls).get(0).equals("s2"),"CMG lost demand anchor");
    check(p.buildCctvPrefetchWindowFromUrlsLocked(urls,true).get(0).equals("s2"),"H5E lost demand anchor");
   }
   p.lastCctvRequestedUrl=null;check(p.buildCmgPrefetchWindowFromUrlsLocked(Collections.emptyList()).isEmpty(),"Empty playlist");
   p.sampleSourceVideo(new byte[]{1});p.sampleSourceVideo(new byte[]{2});
   check(p.metadataCalls==0,"Metadata competed with first frame");
   p.onPlaybackVideoReady();p.genericPrefetchWorkers.submit(()->{}).get();
   check(p.metadataCalls==1&&p.deferredMetadataSample==null,"Deferred sample was copied/replayed/lost");
   p.onPlaybackVideoReady();p.genericPrefetchWorkers.submit(()->{}).get();check(p.metadataCalls==1,"Duplicate readiness replays metadata");
   p.sampleSourceVideo(new byte[]{3});check(p.metadataCalls==2,"Metadata never resumed");
   p.startupMilestone("encrypted-first-byte","s0");p.startupMilestone("encrypted-first-byte","s1");
   check(p.costs.get("encrypted-first-byte")==1,"Duplicate milestone");
   byte[] audio=new byte[188];audio[0]=0x47;audio[1]=0x41;audio[2]=1;audio[3]=0x10;
   p.noteClearVideo(audio,256,"s0");check(!p.costs.containsKey("first-clear-video-pes"),"Audio counted as video");
   audio[2]=0;p.noteClearVideo(audio,256,"s0");p.noteClearVideo(audio,256,"s1");
   check(p.costs.get("first-clear-video-pes")==1,"Video milestone missing/repeated");
   p.running=false;p.startupMilestone("closed","s2");check(!p.costs.containsKey("closed"),"Retired proxy posted timing");
   System.out.println("PASS actual prefetch windows, demand anchors, metadata deferral and milestone isolation");
  } finally {p.genericPrefetchWorkers.shutdownNow();}
 }
}'''
signatures = ['    private List<String> buildCmgPrefetchWindowFromUrlsLocked(',
              '    private List<String> buildCctvPrefetchWindowFromUrlsLocked(',
              '    private int cctvLiveEdgeHoldBackForSegmentCount(',
              '    void onPlaybackVideoReady()', '    private void sampleSourceVideo(',
              '    private void startupMilestone(', '    private void noteClearVideo(']
java=java.replace('METHODS','\n'.join(method(s) for s in signatures))
# Demand fetches remain usable while only speculative work is paused.
for name in ['    private void prefetchCmgSegment(', '    private void prefetchCctvSegment(',
             '    private void pollCctvPlaylistForPrefetch(']:
    assert 'readAheadPaused' in method(name)
for name in ['    private void streamCmgSegment(String originUrl, OutputStream output,boolean rawBody)', '    private void streamH5eSegment(String originUrl, OutputStream output,boolean rawBody)']:
    actual=method(name)
    assert 'readAheadPaused' not in actual
    assert actual.index('lastCctvRequestedUrl = originUrl') < actual.index('writeProgressiveTs')
with tempfile.TemporaryDirectory() as folder:
    p=Path(folder)/'PipelineCheck.java';p.write_text(java,encoding='utf8')
    jdk=Path(os.environ.get('JAVA_HOME','C:/Users/Administrator/.jdks/jbr-17.0.14'))/'bin'
    subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','-d',folder,str(p)],check=True)
    subprocess.run([str(jdk/'java.exe'),'-cp',folder,'PipelineCheck'],check=True,timeout=15)
