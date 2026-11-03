"""Exercise the real barrier for overlapping URL resolution and proxy retirement."""
from pathlib import Path
import os
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
source = (root/'app/src/main/java/xiao/bu/tv/MainActivity.java').read_text(encoding='utf8')
def method(signature):
    start = source.index(signature)
    pos = source.index('{', start) + 1
    depth = 1
    while depth:
        depth += (source[pos] == '{') - (source[pos] == '}')
        pos += 1
    return source[start:pos]

java = r'''package xiao.bu.tv;
public class PreparationCheck {
 boolean activeEmbeddedYangshipinResolver;
 int playRequestId=30,freshYangshipinRequestId=-1,warmResultRequest,starts,failures;
 CjsSource activeCjsSource;Proxy proxy=new Proxy();CjsSiteResolver cjsSiteResolver=new CjsSiteResolver();
 ChannelNetworkWarmup channelNetworkWarmup;
 static class CjsSource {String url;}
 static class Proxy {int writes;void setWebRequestHeaders(String r,String u,Object x){writes++;}void configureCjsTransformer(String t,String[]a,String[]h){writes++;}}
 static class CjsSiteResolver {
  interface Callback {void onResolved(int id,Result r);void onFailed(int id,String reason);}
  static class Result {String referer="",transformer="h5e",url="http://media/live.m3u8";String[]transformerArgs={},mediaHosts={};boolean directDataSource;}
  Callback callback;void cancel(){}void resolve(int id,String n,String p,String q,String s,boolean f,Callback c){callback=c;}
 }
 static class ChannelNetworkWarmup {static class CjsLease{CjsSiteResolver resolver;CjsSiteResolver.Result result;}CjsLease takeCjs(String p,String q,String s,int i,CjsSiteResolver.Callback c){return null;}}
 static class Log {static void w(String tag,String message){}}
 static String TAG="test";
 void updateLoadingStatus(String s){}void showChannelBar(String n,String s){}String customSourceStatus(String s){return s;}
 void logSelectedChannelUrl(int i){}boolean refreshYangshipinPlayback(int i,String s){return false;}boolean retryWarmedSource(int i){return false;}
 void handleUnavailableSource(String s,String reason){failures++;}String playbackResolutionMode(){return "high";}
 void startResolvedPlayer(Channel c,String url,boolean direct){starts++;}
 static class CjsPluginRuntime {static boolean supportsSite(String page){return page.contains("cctv.com")||page.contains("yangshipin.cn");}}
 boolean isWebViewSource(String s){return s!=null&&s.startsWith("webview://");}String webViewPage(String s){return s.substring(10);}
 Channel extractCctvWebChannel(String s){if(s.contains("cctv.com")){Channel c=new Channel("1","cctv1","cctv1",null,null,null,"fhd");c.streamId="cctv1";return c;}return null;}
 static class Channel {
  String number,name,streamId,yangshipinPid,yangshipinMaxDefinition="fhd",url;
  Channel(String n,String name,String id,Object ignored,String pid,Object unused,String definition){number=n;this.name=name;yangshipinPid=pid;yangshipinMaxDefinition=definition;}
 }
 static class ChannelCatalog {
  static int SOURCE_CUSTOM=2,SOURCE_CCTV_WEB=0;static Channel known;
  static Channel findYangshipinChannelByPid(String pid){return known!=null&&pid.equals(known.yangshipinPid)?known:null;}
 }
 String configuredPlaybackUrl(Channel c){return c.url;}
 static String extractYangshipinPid(String url){return url!=null&&(url.matches("[0-9]+")||url.contains("yangshipin.cn"))?url:null;}
 static int checks;static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
 public static void main(String[]args){
  ChannelPreparationGate gate=new ChannelPreparationGate();int[] commits={0};
  check(gate.defer(()->commits[0]++),"fast URL must wait for old CMG session");
  check(commits[0]==0,"result changed runtime before proxy retirement");gate.open();
  check(commits[0]==1,"held URL not delivered when new proxy ready");gate.open();
  check(commits[0]==1,"duplicate retirement delivered URL twice");
  check(!gate.defer(()->commits[0]++),"slow URL should proceed immediately after proxy ready");
  check(commits[0]==1,"ready gate also executed caller's immediate path");
  gate=new ChannelPreparationGate();int[] current={20};final int resolved=20;
  gate.defer(()->{if(resolved==current[0])commits[0]++;});current[0]=21;gate.open();
  check(commits[0]==1,"obsolete selection reconfigured current playback");
  gate=new ChannelPreparationGate();int[] failures={0};gate.defer(()->failures[0]++);
  check(failures[0]==0,"fast failure started a fallback using old proxy");gate.open();
  check(failures[0]==1,"held failure lost its fallback");
  PreparationCheck a=new PreparationCheck();Channel imported=new Channel("4","CCTV4","custom",null,null,null,"fhd");imported.url="600001814";
  a.activeEmbeddedYangshipinResolver=true;
  Channel selected=a.swipeYangshipinTarget(imported,ChannelCatalog.SOURCE_CUSTOM);
  check(selected!=null&&"600001814".equals(selected.yangshipinPid),"imported YSP page missed early resolution because catalog channel has no pid");
  ChannelCatalog.known=selected;check(a.swipeYangshipinTarget(imported,2)==selected,"imported and ordinary paths use different resolver channels");
  a.activeEmbeddedYangshipinResolver=false;
  check(a.swipeYangshipinTarget(imported,2)==null,"unrelated custom page is prefetched");
  check(a.swipeYangshipinTarget(selected,1)==selected,"built-in YSP excluded");
  check(a.swipeYangshipinTarget(selected,0)==null,"CCTV web route incorrectly treated as YSP");
  imported.url="webview://https://tv.cctv.com/live/cctv1/";
  check(a.selectedCjsPage(imported,2).equals("https://tv.cctv.com/live/cctv1/"),"CJS CCTV page selection bypassed");
  imported.url="webview://https://tv.cctv.com/live/cctv1?from=shared";
  check(a.selectedCjsPage(imported,2).equals("https://tv.cctv.com/live/cctv1/"),"Shared CCTV page without trailing slash was not canonicalized");
  imported.url="webview://https://www.yangshipin.cn/tv/home?pid=123";
  check(a.selectedCjsPage(imported,2).contains("yangshipin.cn"),"CJS YSP route bypassed");
  imported.url="webview://https://tv.gxtv.cn/page";check(a.selectedCjsPage(imported,2)==null,"Stateful unrelated CJS site started early");
  gate=new ChannelPreparationGate();a.resolveCjsSite(imported,"test",30,gate);
  CjsSiteResolver.Callback cb=a.cjsSiteResolver.callback;cb.onResolved(30,new CjsSiteResolver.Result());
  check(a.starts==0&&a.proxy.writes==0,"CJS changed old proxy before retirement");
  gate.open();check(a.starts==1&&a.proxy.writes==2,"CJS URL/configuration lost after retirement");
  gate=new ChannelPreparationGate();a.resolveCjsSite(imported,"test",30,gate);
  a.cjsSiteResolver.callback.onResolved(30,new CjsSiteResolver.Result());a.playRequestId=31;gate.open();
  check(a.starts==1&&a.proxy.writes==2,"Deferred CJS result configured another channel");
  gate=new ChannelPreparationGate();a.resolveCjsSite(imported,"test",31,gate);
  a.cjsSiteResolver.callback.onFailed(31,"failed");check(a.failures==0,"Failure fallback crossed release barrier");
  gate.open();check(a.failures==1,"Failure result lost");
  System.out.println("PASS "+checks+" early resolution barrier checks: fast/slow result, duplicate completion, stale selection and failure handoff");
 }
 METHODS
}'''
java = java.replace('METHODS', method('    private Channel swipeYangshipinTarget(')
                    + method('    private static Channel yangshipinChannelForPid(')
                    + method('    private String selectedCjsPage(')
                    + method('    private void resolveCjsSite(final Channel channel, final String pageUrl,\n            final int requestId, final ChannelPreparationGate gate)'))
jdk = Path(os.environ.get('JAVA_HOME', 'C:/Users/Administrator/.jdks/jbr-17.0.14'))/'bin'
with tempfile.TemporaryDirectory() as folder:
    target = Path(folder)/'PreparationCheck.java'
    target.write_text(java, encoding='utf8')
    subprocess.run([str(jdk/'javac.exe'), '-encoding', 'UTF-8', '-d', folder, str(target),
                    str(root/'app/src/main/java/xiao/bu/tv/ChannelPreparationGate.java')], check=True)
    subprocess.run([str(jdk/'java.exe'), '-cp', folder, 'xiao.bu.tv.PreparationCheck'], check=True)
