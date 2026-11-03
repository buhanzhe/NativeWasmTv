"""Run actual provider policy: avoid a default-camera takeover, retain other sites."""
from pathlib import Path
import os,subprocess,tempfile

root=Path(__file__).resolve().parents[1]
jdk=Path(os.environ.get('JAVA_HOME','C:/Users/Administrator/.jdks/jbr-17.0.14'))/'bin'
android=Path('D:/android/sdk/platforms/android-28/android.jar')
source=root/'app/src/main/java/xiao/bu/tv/WebTvPlaybackPolicy.java'
harness='''package xiao.bu.tv;
public class SelectionPolicyCheck {
 static boolean automatic(String referer,String page){return !WebTvPlaybackPolicy.webPlayerOnly(referer)
  &&!WebTvPlaybackPolicy.webPlayerOnly(page)&&WebTvPlaybackPolicy.autoTakeoverAllowed(referer)
  &&WebTvPlaybackPolicy.autoTakeoverAllowed(page);}
 static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
 public static void main(String[] args){
  check(!automatic("https://www.huya.com/11342412","https://m.huya.com/11342412"),"Huya signed MSE playback must keep its web player");
  check(!WebTvPlaybackPolicy.webPlayerOnly("https://www.huya.com/30631741"),"Huya manual sniffed playback stays available");
  check(automatic("https://huya.com.example.test/11342412","https://example.test/?huya.com"),"Huya policy must match actual hostname");
  check(!automatic("https://www.mgtv.com/live/","https://www.mgtv.com/live/?tag=test"),"MGTV default camera must not take over before UJS selects target");
  check(!automatic("https://www.jxntv.cn/live/","https://www.jxntv.cn/live/#/jxtv6"),"Fragment-free resource Referer must not defeat nondefault Jiangxi selection");
  check(automatic("https://www.jxntv.cn/live/","https://www.jxntv.cn/live/#/jxtv1"),"Explicit Jiangxi default keeps native auto-play");
  check(!automatic("https://www.nmtv.cn/liveTv","https://www.nmtv.cn/liveTv"),"NMTV must not take over its initial default station");
  check(automatic("https://www.dtradio.com.cn/local/folder25","https://www.dtradio.com.cn/local/folder25"),"Unscripted Datong default keeps native HLS");
  check(!WebTvPlaybackPolicy.autoTakeoverAllowed("https://www.dtradio.com.cn/local/folder25","webview://https://www.dtradio.com.cn/local/folder25?%75js=YWJj"),"Fragment/query-free resource Referer must not bypass scripted Datong selection");
  check(WebTvPlaybackPolicy.autoTakeoverAllowed("https://www.dtradio.com.cn/local/folder25","https://www.dtradio.com.cn/local/folder25?title=ujs"),"Only the exact selection option changes policy");
  check(automatic("https://www.ntjoy.com/ntw/broadcastTvs.html","https://www.ntjoy.com/ntw/broadcastTvs.html"),"A station rights notice is not a reason to disable Nantong native priority");
  check(automatic("https://www.nmtv.cn.example.test/liveTv","https://example.test/?url=ntjoy.com"),"Selection exceptions match actual document hosts only");
  check(automatic("https://www.hebtv.com/live","https://www.hebtv.com/live"),"Other provinces keep native priority");
  check(!automatic("https://m.gdtv.cn/tvChannelDetail/43","https://www.gdtv.cn/"),"Guangdong protected SDK policy unchanged");
  check(automatic("https://mgtv.com.example.test/live","https://example.test/?url=mgtv.com"),"Host boundary must not match arbitrary URLs");
  check(!WebTvPlaybackPolicy.autoTakeoverAllowed("https://WWW.MGTV.COM/live/"),"Case-insensitive hostname");
  check(!WebTvPlaybackPolicy.requiresWebView("https://www.mgtv.com/live/",true,true),"Lite-core routing remains unchanged");
  check(WebTvPlaybackPolicy.requiresWebView("https://www.mgtv.com/live/",true,false),"Explicit original-player mode still routes to WebView");
  check(WebTvPlaybackPolicy.webPlayerOnly("https://live.jstv.com/?channelId=676"),"Jiangsu4K signed stream retains original SDK");
  check(!WebTvPlaybackPolicy.requiresWebView("https://live.jstv.com/?channelId=676",true,true),"Jiangsu4K Lite retains SDK through renewing HLS transport");
  check(WebTvPlaybackPolicy.requiresWebView("https://live.jstv.com/?channelId=676",false,true),"Jiangsu4K autoplay OFF keeps manual WebView");
  check(WebTvPlaybackPolicy.requiresWebView("https://live.jstv.com/?channelId=676",true,false),"Jiangsu4K original-player selection keeps WebView");
  check(!WebTvPlaybackPolicy.webPlayerOnly("https://live.jstv.com/?channelId=670"),"Other Jiangsu channels retain native priority");
  check(!WebTvPlaybackPolicy.webPlayerOnly("https://live.jstv.com.example.test/?channelId=676"),"Exact provider hostname only");
  check(!automatic("https://www.douyu.com/4837387","https://www.douyu.com/4837387"),"Desktop preview FLV stays with original SDK");
  check(automatic("https://m.douyu.com/4837387","https://m.douyu.com/4837387"),"Mobile Douyu HLS keeps native priority");
  check(WebTvPlaybackPolicy.autoTakeoverAllowed("https://m.douyu.com/4837387","https://www.douyu.com/4837387"),"Original catalog URL does not block mobile HLS");
  check(automatic("https://www.douyu.com.example.test/4837387","https://www.douyu.com.example.test/4837387"),"Douyu policy matches document hostname only");
  System.out.println("PASS default-camera selection guard, fragment-free Referer, native priority on other stations, hostname boundaries, Jiangsu 4K live-session routing");
 }
}'''
with tempfile.TemporaryDirectory() as tmp:
    folder=Path(tmp);test=folder/'SelectionPolicyCheck.java';test.write_text(harness,'utf8')
    subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','-cp',str(android),'-d',tmp,str(source),str(test)],check=True)
    subprocess.run([str(jdk/'java.exe'),'-cp',tmp+os.pathsep+str(android),'xiao.bu.tv.SelectionPolicyCheck'],check=True)
main=(root/'app/src/main/java/xiao/bu/tv/MainActivity.java').read_text('utf8')
at=main.index('public void onStreamDiscovered(');body=main[at:main.index('public void onBrowserChannelList()',at)]
assert body.index('rememberSniffedResource(resource)')<body.index('WebTvPlaybackPolicy.autoTakeoverAllowed'), 'Manual sniff list must remain available'
print('PASS discovery remains available before the automatic-only guard')
