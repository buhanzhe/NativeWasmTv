"""Run production recovery scheduling and resolver cache selection with a fake clock/UI."""
from pathlib import Path
import subprocess, tempfile

root = Path(__file__).resolve().parents[1]
main = (root/'app/src/main/java/xiao/bu/tv/MainActivity.java').read_text(encoding='utf-8')
ysp = (root/'app/src/main/java/xiao/bu/tv/YangshipinWebResolver.java').read_text(encoding='utf-8')
cjs = (root/'app/src/main/java/xiao/bu/tv/CjsSiteResolver.java').read_text(encoding='utf-8')
def method(text, signature):
    start = text.index(signature)
    brace = text.index('{', start); depth = 1; end = brace + 1
    while depth:
        depth += (text[end] == '{') - (text[end] == '}'); end += 1
    return text[start:end]

refresh = method(main, '    private boolean refreshYangshipinPlayback(')
sync = method(main, '    private void syncPlaybackRecoveryTarget(')
cache_start = ysp.index('                String cacheKey = pendingRequest.cacheKey();')
cache_end = ysp.index('                if (!keepTracePage)', cache_start)
ysp_lookup = ysp[cache_start:cache_end]
cjs_start = method(cjs, '    private void start(Pending request)').replace('android.content.SharedPreferences', 'Prefs')
timeout_guard = method(main, '        if (!freshYangshipin && !refreshingHttpSource')

java = r'''
import java.util.*;
public class YangshipinRecoveryCheck {
 static final String TAG="test";
 static void check(boolean ok){if(!ok)throw new AssertionError();}
 static class SystemClock {static long elapsedRealtime(){return 1000;}}
 static class Log {static void w(String a,String b){}static void i(String a,String b){}}
 static class Channel {String name="CCTV-1";int sourceCount(){return 2;}}
 static class ChannelCatalog {static final int SOURCE_YSP_CCTV=1,SOURCE_YSP_SATELLITE=2,SOURCE_CUSTOM=3;}
 static class Queue {Runnable next;long delay;void postDelayed(Runnable r,long d){next=r;delay=d;}void run(){Runnable r=next;next=null;r.run();}}
 static class Warmup {int clears;void foreground(String s){check(s.isEmpty());clears++;}}
 Queue channelBar=new Queue();Warmup channelNetworkWarmup=new Warmup();
 int playRequestId=7,currentGroupIndex,currentChannelIndex,currentSourceIndex,source=1;
 int yangshipinRefreshScheduledRequest=-1,stallRecoveryRequestId=-1,playbackRecoveryAttempts;
 int continuousHttpSourceRequestId=-1,continuousHttpRefreshScheduledRequest=-1;
 int starts,clears,releases,hides;boolean finishing,fresh;long lastPlaybackRecoveryAt;
 String playbackRecoveryTarget="0:0:0",configured="webview://ysp?pid=1";
 Object catchupProgram,quickChannel;
 static final int YANGSHIPIN_REFRESH_MAX_ATTEMPTS=3;
 Channel currentChannel(){return new Channel();}int currentCatalogSource(){return source;}
 String configuredPlaybackUrl(Channel c){return configured;}
 static String extractYangshipinPid(String s){return s.contains("ysp?pid=")?"1":null;}
 void clearPendingPlayer(){clears++;}void releasePlayer(boolean b){releases++;}
 void abortChannelSwitchAnimation(){}void hideLoading(){hides++;}
 void showChannelBar(String a,String b){}void showLoading(String a,String b){}
 boolean isFinishing(){return finishing;}
 void startChannel(int i,Object a,Object b,boolean f){starts++;fresh=f;playRequestId++;}
 int sourceTimeouts;void scheduleCustomSourceTimeout(Channel c,int request){sourceTimeouts++;}
 void schedule(boolean freshYangshipin){boolean refreshingHttpSource=false;int source=currentCatalogSource(),requestId=playRequestId;Channel channel=currentChannel();TIMEOUT_GUARD}
 REFRESH
 SYNC
 static class Prefs {
  Map<String,Object> values=new HashMap<>();
  long getLong(String k,long d){return (Long)values.getOrDefault(k,d);}
  String getString(String k,String d){return (String)values.getOrDefault(k,d);}
  Prefs edit(){return this;}Prefs remove(String k){values.remove(k);return this;}void apply(){}
 }
 static class CachedUrl {String url;long createdAt;CachedUrl(String u,long at){url=u;createdAt=at;}}
 static class Callback {void onFailed(int id,String reason){throw new AssertionError(reason);}}
 static class Site {String id="yangshipin.cn";}
 static class Pending {String cacheKey="one",definition="high",quality="high";Site site=new Site();boolean browser;int requestId;boolean fresh;Callback callback=new Callback();String cacheKey(){return cacheKey;}}
 static class Activity {Prefs prefs=new Prefs();Prefs getSharedPreferences(String k,int m){return prefs;}}
 Activity activity=new Activity();Prefs resolverPrefs=new Prefs();Map<String,CachedUrl> apiCache=new HashMap<>();
 Pending pendingRequest=new Pending(),pending;Channel channel=new Channel();boolean speculative;int cacheHits,network;
 static final long API_CACHE_MS=600000;
 void completeApi(Pending p,String u){cacheHits++;check(u.equals("cached"));}
 void yspLookup(boolean fresh){YSP_LOOKUP network++;}
 void complete(Pending p,String u){cacheHits++;check(u.equals("cached"));}
 void execute(Pending p){network++;}
 CJS_START
 public static void main(String[] args){
  YangshipinRecoveryCheck a=new YangshipinRecoveryCheck();
  check(a.refreshYangshipinPlayback(7,"clock stopped"));check(a.playbackRecoveryAttempts==1&&a.starts==0&&a.channelBar.delay==500&&a.channelNetworkWarmup.clears==1);
  check(a.refreshYangshipinPlayback(7,"duplicate 403"));check(a.playbackRecoveryAttempts==1);
  a.channelBar.run();check(a.starts==1&&a.fresh);
  check(!a.refreshYangshipinPlayback(7,"stale callback"));
  check(a.refreshYangshipinPlayback(8,"HTTP 403"));check(a.playbackRecoveryAttempts==2&&a.channelBar.delay==1000);a.channelBar.run();
  check(a.refreshYangshipinPlayback(9,"resolver failed"));check(a.playbackRecoveryAttempts==3&&a.channelBar.delay==1500);a.channelBar.run();
  check(a.refreshYangshipinPlayback(10,"still unavailable"));check(a.channelBar.next==null&&a.starts==3&&a.releases==1&&a.currentChannelIndex==0&&a.currentSourceIndex==0);
  a=new YangshipinRecoveryCheck();a.refreshYangshipinPlayback(7,"stall");a.playRequestId++;a.channelBar.run();check(a.starts==0);
  a=new YangshipinRecoveryCheck();a.refreshYangshipinPlayback(7,"stall");a.finishing=true;a.channelBar.run();check(a.starts==0);
  a=new YangshipinRecoveryCheck();a.source=3;a.configured="https://ordinary/stream.m3u8";check(!a.refreshYangshipinPlayback(7,"stall"));
  a.configured="webview://ysp?pid=1";check(a.refreshYangshipinPlayback(7,"custom YSP"));
  a=new YangshipinRecoveryCheck();a.catchupProgram=new Object();check(!a.refreshYangshipinPlayback(7,"catchup"));
  a.catchupProgram=null;a.quickChannel=new Object();check(!a.refreshYangshipinPlayback(7,"temporary"));
  a=new YangshipinRecoveryCheck();a.source=3;a.schedule(true);check(a.sourceTimeouts==0);a.schedule(false);check(a.sourceTimeouts==1);
  for(boolean memory:new boolean[]{true,false})for(boolean fresh:new boolean[]{true,false}){
   a=new YangshipinRecoveryCheck();long now=System.currentTimeMillis();
   a.resolverPrefs.values.put("url_one","cached");a.resolverPrefs.values.put("url_one_at",now);
   a.resolverPrefs.values.put("url_other","untouched");
   if(memory)a.apiCache.put("one",new CachedUrl("cached",now));
   a.yspLookup(fresh);check(a.network==(fresh?1:0)&&a.cacheHits==(fresh?0:1));
   check(a.resolverPrefs.values.get("url_other").equals("untouched"));
   if(fresh)check(!a.apiCache.containsKey("one")&&!a.resolverPrefs.values.containsKey("url_one")&&!a.resolverPrefs.values.containsKey("url_one_at"));
  }
  for(boolean fresh:new boolean[]{true,false}){
   a=new YangshipinRecoveryCheck();a.activity.prefs.values.put("one","cached");a.activity.prefs.values.put("one:until",System.currentTimeMillis()+60000);
   a.activity.prefs.values.put("other","untouched");Pending p=new Pending();p.fresh=fresh;a.start(p);
   check(a.network==(fresh?1:0)&&a.cacheHits==(fresh?0:1)&&a.activity.prefs.values.get("other").equals("untouched"));
  }
  System.out.println("PASS fresh YSP/CJS resolution, exact cache eviction, duplicate/stale callbacks, channel-switch/exit cancellation, three-attempt limit and unaffected other sources");
 }
}
'''.replace('\n REFRESH\n','\n'+refresh+'\n').replace('\n SYNC\n','\n'+sync+'\n').replace('YSP_LOOKUP',ysp_lookup).replace('CJS_START',cjs_start).replace('TIMEOUT_GUARD',timeout_guard)
with tempfile.TemporaryDirectory(prefix='ntv-ysp-recovery-') as directory:
    path=Path(directory)/'YangshipinRecoveryCheck.java';path.write_text(java,encoding='utf-8')
    subprocess.run(['javac','-encoding','UTF-8',str(path)],check=True)
    subprocess.run(['java','-cp',directory,'YangshipinRecoveryCheck'],check=True)
