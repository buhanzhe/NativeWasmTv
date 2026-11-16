package xiao.bu.tv;

import java.util.regex.Pattern;
import okhttp3.*;

/** Optional application-owned download/cache transport; no DOM or native dependency. */
public final class WebResourceHub {
  public enum Kind { STATIC, DOCUMENT, PUBLIC_STATIC }
  public interface Loader { Call newCall(OkHttpClient client,Request request); }
  private static volatile Loader loader;
  private static final Pattern STATIC=Pattern.compile("(?i)\\.(?:m?js|css|wasm|png|jpe?g|gif|webp|ico|woff2?|ttf|otf)$");
  private WebResourceHub(){}
  public static void install(Loader value){loader=value;}
  public static boolean eligible(Request request){
    return "GET".equals(request.method())&&request.header("Range")==null
      &&(request.tag(Kind.class)==Kind.DOCUMENT||STATIC.matcher(request.url().encodedPath()).find());
  }
  public static Call newCall(OkHttpClient client,Request request){
    Loader current=loader;
    return current!=null&&eligible(request)?current.newCall(client,request):client.newCall(request);
  }
  /** HTTP/cache keys omit anchors; a document navigation must retain its route. */
  public static Request responseRequest(Request original,Request wire){
    if(original.tag(Kind.class)!=Kind.DOCUMENT)return wire;
    return wire.newBuilder().url(wire.url().newBuilder().encodedFragment(original.url().encodedFragment()).build()).build();
  }
}
