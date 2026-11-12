package xiao.bu.tv;
import okhttp3.HttpUrl;import org.json.JSONObject;import java.util.*;
/** App compatibility, outside the reusable core. DOM actions live in UserScripts. */
final class WebSiteCompatibility implements LiteHostPolicy {
public boolean preserveMediaPipeline(String value){
    if(value==null)return false;
    HttpUrl page=HttpUrl.parse(value);if(page==null)return false;String host=page.host();
    // Guangxi's Rainbow SDK processes its encrypted live stream before MSE.
    // Raw HLS still has valid TS headers, but direct decoding produces corrupt
    // frames. Keep its original SDK processing before the encoded-media handoff.
    // Guangdianyun's short-lived signed URLs must be consumed by the real
    // player while its page is active, then exported through actual MSE.
    return host.equals("www.huya.com")||host.equals("web.guangdianyun.tv")||host.equals("tv.gxtv.cn")||host.equals("cctv.com")||host.endsWith(".cctv.com")||host.equals("cntv.cn")||host.endsWith(".cntv.cn")||host.equals("yangshipin.cn")||host.endsWith(".yangshipin.cn")||host.equals("www.qtv.com.cn")&&page.encodedPath().startsWith("/live/tv/");
  }
public static String selectedCztvStream(String page,String logged){
    if(page==null||logged==null||logged.length()>16384)return null;
    HttpUrl origin=HttpUrl.parse(page);String suffix=" 我是src.value";
    if(origin==null||!origin.host().equals("www.cztv.com")||!origin.encodedPath().matches("/liveTV/[0-9]+/?")||!logged.endsWith(suffix))return null;
    String raw=logged.substring(0,logged.length()-suffix.length());
    if(!raw.startsWith("http://")&&!raw.startsWith("https://"))return null;
    return HttpUrl.parse(raw)==null?null:raw;
  }
public static String selectedGdtvStream(String page,String request,String body){
    HttpUrl origin=HttpUrl.parse(page),api=HttpUrl.parse(request);
    if(origin==null||api==null||!(origin.host().equals("www.gdtv.cn")||origin.host().equals("m.gdtv.cn"))||!api.host().equals("gdtv-api.gdtv.cn")||body==null||body.length()>256*1024)return null;
    java.util.regex.Matcher route=java.util.regex.Pattern.compile("^/tvChannelDetail/([0-9]+)/?$").matcher(origin.encodedPath());
    String start=api.queryParameter("startTime");
    if(!route.matches()||start!=null&&!start.isEmpty()&&!start.equals("undefined")&&!start.equals("null"))return null;
    if(!api.encodedPath().equals("/api/tv/v2/tvChannel/"+route.group(1)))return null;
    String requested=api.queryParameter("tvChannelPk");if(requested!=null&&!requested.equals(route.group(1)))return null;
    try{
      JSONObject selected=new JSONObject(body);if(!route.group(1).equals(selected.optString("pk")))return null;
      Object value=selected.opt("playUrl");if(!(value instanceof String))return null;
      String raw=(String)value;
      // The official player receives JSON text containing the selected HD URL.
      // Parse that data as JSON only; no SDK keys, signatures or code execution.
      if(raw.trim().startsWith("{"))raw=new JSONObject(raw).optString("hd","");
      HttpUrl media=HttpUrl.parse(raw);
      return media!=null&&media.encodedPath().toLowerCase(Locale.US).endsWith(".m3u8")?media.toString():null;
    }catch(Exception ignored){return null;}
  }
 public boolean preferNativeHls(String page,String media){return LiteLiveMediaPolicy.preferHls(page,media);}
 public boolean allowAuthRenewal(String page){return LiteLiveMediaPolicy.selectedLivePage(page);}
 public String selectedSource(String page,String request,String body){return selectedGdtvStream(page,request,body);}
 public String selectedLogSource(String page,String log){return selectedCztvStream(page,log);}
 private static boolean miguHost(String host){return host.endsWith(".cmvideo.cn")||host.endsWith(".miguvideo.com");}
 public boolean matchesSelectedHost(String page,String provider,String raw,String media){HttpUrl owner=HttpUrl.parse(page),target=HttpUrl.parse(raw),url=HttpUrl.parse(media);return owner!=null&&target!=null&&url!=null&&owner.host().equals("m.miguvideo.com")&&provider.equals("migu-live")&&target.encodedPath().endsWith(".m3u8")&&miguHost(target.host())&&miguHost(url.host());}
 public boolean nativeSelectedHls(String page,String provider,String raw){HttpUrl owner=HttpUrl.parse(page),target=HttpUrl.parse(raw);return owner!=null&&target!=null&&target.encodedPath().endsWith(".m3u8")&&(owner.host().equals("www.mgtv.com")&&provider.equals("mgtv-live")||owner.host().equals("www.sxtygdy.com")&&provider.equals("taiyuan-live")||owner.host().equals("www.ngcz.tv")&&provider.equals("ngcz-live"));}
 public boolean isPreroll(String page,String media){HttpUrl owner=HttpUrl.parse(page),url=HttpUrl.parse(media);return owner!=null&&url!=null&&owner.host().equals("m.miguvideo.com")&&url.host().equals("ggv.cmvideo.cn")&&url.encodedPath().startsWith("/v1/iflyad/deliverysystem/");}
 public int scriptBudgetMillis(String page,int sdk,int normal){HttpUrl u=HttpUrl.parse(page);if(sdk<=19&&u!=null&&(u.host().equals("yangshipin.cn")||u.host().endsWith(".yangshipin.cn")||u.host().equals("tv.cctv.com")))return 15000;return normal;}
 public boolean freshNavigation(String page){HttpUrl u=HttpUrl.parse(page);return u!=null&&u.host().equals("www.cztv.com")&&u.encodedPath().matches("/liveTV/[0-9]+/?");}
 public int readTimeoutMillis(String page,String request,String method){HttpUrl u=HttpUrl.parse(request);return method.equals("GET")&&u!=null&&u.isHttps()&&u.host().equals("lp.open.weixin.qq.com")&&u.encodedPath().equals("/connect/l/qrconnect")?25000:0;}
 public int requestTimeoutMillis(String page,String request,String method){HttpUrl u=HttpUrl.parse(request);if(!method.equals("GET")||u==null)return 0;if(u.host().equals("tytv-hls.sxtygdy.com")&&u.encodedPath().matches("(?i).*\\.(ts|m4s)$"))return 6000;return readTimeoutMillis(page,request,method)>0?35000:0;}
 public boolean ignoreDocumentClock(String page){HttpUrl u=HttpUrl.parse(page);return u!=null&&((u.host().equals("yangshipin.cn")||u.host().equals("www.yangshipin.cn"))&&(u.encodedPath().equals("/video/home")||u.encodedPath().equals("/tv/home"))||u.host().equals("share-laibin.gxlbcloud.com")&&u.encodedPath().startsWith("/fhshare/"));}
 public long apiClock(String page,String request,String body,long serverDate){
  long clock=LiteApiClock.bozhou(page,request,body);if(clock>0)return clock;
  HttpUrl u=HttpUrl.parse(request);if(u==null||!u.isHttps()||!preserveMediaPipeline(page)||!u.host().endsWith(".yangshipin.cn")||!u.encodedPath().equals("/v1/player/auth")&&!(u.host().equals("h5access.yangshipin.cn")&&u.encodedPath().equals("/web/open/token")))return 0;
  try{JSONObject root=new JSONObject(body),data=root.optJSONObject("data");String ts=data==null?"":data.optString("ts");if(root.optInt("code",-1)==0&&ts.matches("[0-9]{10,13}")){long value=Long.parseLong(ts);return value<1000000000000L?value*1000:value;}}catch(Exception ignored){}return serverDate;
 }
 public boolean traceApiStatus(String request){HttpUrl u=HttpUrl.parse(request);return u!=null&&(u.host().endsWith(".yangshipin.cn")||u.host().endsWith(".cntv.cn"))&&u.encodedPath().matches("/(?:v1/player/(?:auth|get_live_info)|api/v3/vdn/live)");}
}
