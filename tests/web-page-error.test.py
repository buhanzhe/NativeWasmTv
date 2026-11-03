"""Network/HTTP errors retain the WebView; only engine failures kill its host."""
from pathlib import Path
import os, subprocess, tempfile

root = Path(__file__).resolve().parents[1]
web = (root/'app/src/main/java/xiao/bu/tv/WebSourceView.java').read_text(encoding='utf8')
legacy = (root/'app/src/main/java/xiao/bu/tv/LegacyWebActivity.java').read_text(encoding='utf8')

def method(source, signature):
    start = source.index(signature)
    at = source.index('{', start)+1
    depth = 1
    while depth:
        depth += (source[at]=='{')-(source[at]=='}')
        at += 1
    return source[start:at].replace('@Override ', '')

java = r'''import java.util.*;
public class PageErrorCheck {
 static class Bundle extends HashMap<String,String>{}
 static class LegacyWebSession {static final int HEARTBEAT=3;static Bundle bundle(String k,String v){Bundle b=new Bundle();b.put(k,v);return b;}}
 static class android {static class widget {static class Toast {static final int LENGTH_LONG=1;static Toast makeText(Object c,String t,int l){return new Toast();}void show(){}}}}
 static class Log {static void w(String a,String b){}}
 static class Build {static class VERSION {static int SDK_INT=25;}}
 static class WebViewClient {static final int ERROR_FAILED_SSL_HANDSHAKE=-11,ERROR_HOST_LOOKUP=-2;}
 static class WebView {}
 static class WebResourceRequest {boolean main=true;String url="https://station.test/live";boolean isForMainFrame(){return main;}String getUrl(){return url;}}
 static class WebResourceResponse {int getStatusCode(){return 503;}}
 static class Legacy {
  int request=9,events,failures;String eventName,message;
  void event(String name,Bundle data){events++;eventName=name;message=data.get("reason");}
  void send(int code,Bundle b){if(code!=LegacyWebSession.HEARTBEAT||b.get("failure")==null)throw new AssertionError();failures++;}
  LEGACY
 }
 int requestId=9,pageLoadTerminalGeneration=-1,pageLoadGeneration=4,errors,unavailable;
 String pageUrl="https://station.test/live",pageCoverError,TAG="fixture";boolean pageActive=true,loading=true;
 WebView webView=new WebView();SourceClient sourceClient=new SourceClient();
 Legacy host=new Legacy();
 class Listener {void onPageError(int id,String message){errors++;host.onPageError(id,message);}void onPageUnavailable(int id,String message){unavailable++;host.onPageUnavailable(id,message);}}
 Listener listener=new Listener();
 void setLoadingVisible(boolean b){loading=b;}
 static String normalizedDocument(String url){if(url==null)return "";return url.split("#",2)[0];}
 static boolean sameDocument(String a,String b){return normalizedDocument(a).equals(normalizedDocument(b));}
 static boolean isBlankPage(String url){return url==null||url.equals("about:blank");}
 class SourceClient {
  String attemptedFallback;boolean fallbackRetryActive,fallbackExhausted,retry;
  boolean retryHttpsWithLegacyClient(WebView view,String url){return retry;}
  CALLBACKS
 }
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 public static void main(String[] args){
  PageErrorCheck c=new PageErrorCheck();
  c.sourceClient.onReceivedError(c.webView,-2,"net::ERR_NAME_NOT_RESOLVED",c.pageUrl);
  check(c.errors==1&&c.unavailable==0&&c.host.failures==0&&c.host.events==1&&"error".equals(c.host.eventName),"DNS must not kill host or auto-skip playlist");
  check(c.host.message.contains("域名解析失败")&&!c.loading,"Actionable DNS error, spinner dismissed");
  c.sourceClient.onReceivedError(c.webView,-6,"connection failed","https://tracker.test/pixel");
  check(c.errors==1,"Subresources do not fail current page");
  WebResourceRequest r=new WebResourceRequest();r.main=false;c.sourceClient.onReceivedHttpError(c.webView,r,new WebResourceResponse());
  r.main=true;r.url="https://station.test/previous";c.sourceClient.onReceivedHttpError(c.webView,r,new WebResourceResponse());
  check(c.errors==1,"Iframe and stale document HTTP failures ignored");
  r.url=c.pageUrl;c.sourceClient.onReceivedHttpError(c.webView,r,new WebResourceResponse());
  check(c.errors==2&&c.host.failures==0&&"HTTP 503".equals(c.host.message),"HTTP error retains browser for retry and error-body JS/media");
  c.sourceClient.retry=true;c.sourceClient.onReceivedError(c.webView,-11,"ssl",c.pageUrl);check(c.errors==2,"Legacy TLS compatibility retry preserved");
  c.sourceClient.retry=false;c.sourceClient.attemptedFallback=c.pageUrl;c.sourceClient.fallbackRetryActive=true;
  c.sourceClient.onReceivedError(c.webView,-11,"cancelled",c.pageUrl);check(c.errors==2,"Delayed SSL cancel must not defeat active retry");
  c.sourceClient.fallbackExhausted=true;c.sourceClient.onReceivedError(c.webView,-11,"ssl",c.pageUrl);check(c.errors==3,"Exhausted TLS retry becomes recoverable error");
  SourceClient retired=c.sourceClient;c.sourceClient=c.new SourceClient();retired.onReceivedError(c.webView,-2,"stale",c.pageUrl);check(c.errors==3,"Retired WebView callback ignored");
  c.host.onPageError(8,"stale");c.host.onPageUnavailable(8,"stale");check(c.host.events==3&&c.host.failures==0,"Stale request cannot fail current host");
  c.host.onPageUnavailable(9,"renderer crashed");check(c.host.failures==1,"Actual engine failure must keep emergency shutdown");
  System.out.println("PASS recoverable DNS/HTTP, subresource/stale filtering, isolated IPC, TLS retry, engine failure protection");
 }
}'''
java = java.replace('LEGACY', '\n'.join(method(legacy, s) for s in (
    'public void onPageError(', 'public void onPageUnavailable(')))
java = java.replace('CALLBACKS', '\n'.join(method(web, s) for s in (
    'public void onReceivedError(', 'public void onReceivedHttpError(')))
jdk = Path(os.environ.get('JAVA_HOME','C:/Users/Administrator/.jdks/jbr-17.0.14'))/'bin'
with tempfile.TemporaryDirectory() as tmp:
    p = Path(tmp)/'PageErrorCheck.java'
    p.write_text(java, encoding='utf8')
    subprocess.run([str(jdk/'javac.exe'), '-encoding', 'UTF-8', str(p)], check=True)
    subprocess.run([str(jdk/'java.exe'), '-cp', tmp, 'PageErrorCheck'], check=True)
