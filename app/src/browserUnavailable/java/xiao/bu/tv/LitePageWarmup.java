package xiao.bu.tv;

import android.content.Context;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import okhttp3.*;

/** Network-only preparation: never creates a DOM/JS realm or executes a page. */
public final class LitePageWarmup implements java.io.Closeable {
  private static final int HTML_LIMIT=512*1024, SCRIPT_LIMIT=1024*1024, PAGE_LIMIT=4*1024*1024;
  private static final Pattern TAG=Pattern.compile("(?is)<!--.*?-->|<script\\b([^>]*)>(.*?)</script\\s*>|<base\\b([^>]*)>");
  private static final Pattern ATTR=Pattern.compile("(?is)([a-z][a-z0-9_-]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");
  private final OkHttpClient client;
  private final ThreadPoolExecutor workers=new ThreadPoolExecutor(2,2,10,TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(12),r->new Thread(()->{android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);r.run();},"page-resource-warmup"));
  private final Set<Call> calls=Collections.newSetFromMap(new ConcurrentHashMap<Call,Boolean>());
  private final Set<String> queued=Collections.newSetFromMap(new ConcurrentHashMap<String,Boolean>());
  private volatile boolean closed;
  private volatile int generation;
  public LitePageWarmup(Context context,Dns dns)throws Exception {
    // GET preparation must not rotate an active channel's cookie session.
    CookieJar readOnly=new CookieJar(){public List<Cookie> loadForRequest(HttpUrl u){return LiteCookies.PROFILE.loadForRequest(u);}public void saveFromResponse(HttpUrl u,List<Cookie> c){ }};
    client=LiteHttp.client(context,dns).newBuilder().cookieJar(readOnly).retryOnConnectionFailure(false).callTimeout(5,TimeUnit.SECONDS).build();
    workers.allowCoreThreadTimeOut(true);
  }
  public void dns(String url) { submit(url,false); }
  public void page(String url) { submit(url,true); }
  private void submit(String raw,boolean page) {
    HttpUrl parsed=HttpUrl.parse(raw);if(parsed==null||closed)return;
    final HttpUrl url=parsed.newBuilder().removeAllQueryParameters("ujs").fragment(null).build();
    final int epoch=generation;final String key=epoch+":"+(page?"page:":"dns:")+url;if(!queued.add(key))return;
    try{workers.execute(()->{try{if(closed||epoch!=generation)return;client.dns().lookup(url.host());if(page)prepare(url,epoch);}catch(Exception ignored){}finally{queued.remove(key);}});}catch(RejectedExecutionException rejected){queued.remove(key);}
  }
  private byte[] get(HttpUrl url,String referer,int maximum,int epoch)throws IOException {
    if(closed||epoch!=generation)throw new IOException("Warmup cancelled");
    Request request=new Request.Builder().url(url).header("User-Agent",LiteHttp.USER_AGENT).header("Referer",referer).build();
    Call call=WebResourceHub.newCall(client,request);calls.add(call);
    if(closed||epoch!=generation)call.cancel();
    try(Response response=call.execute()){
      if(!response.isSuccessful()||response.body()==null)return null;
      String type=response.header("Content-Type","").toLowerCase(Locale.US);
      if(type.contains("mpegurl")||type.startsWith("video/")||type.startsWith("audio/"))return null;
      long length=response.body().contentLength();if(length>maximum)return null;
      try(InputStream in=response.body().byteStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
        byte[] block=new byte[16384];int count;
        while((count=in.read(block))!=-1){if(closed||epoch!=generation||out.size()+count>maximum)throw new IOException("Warmup size limit");out.write(block,0,count);}return out.toByteArray();
      }
    }finally{calls.remove(call);}
  }
  private void prepare(HttpUrl page,int epoch)throws Exception {
    byte[] html=get(page,page.toString(),HTML_LIMIT,epoch);if(html==null||closed||epoch!=generation)return;
    List<HttpUrl> scripts=scripts(new String(html,"UTF-8"),page);int bytes=html.length;
    // One page occupies one worker. Up to two adjacent pages run concurrently;
    // script execution/order is left entirely to the actual foreground engine.
    for(HttpUrl script:scripts){if(closed||epoch!=generation||bytes>=PAGE_LIMIT)break;byte[] data=get(script,page.toString(),Math.min(SCRIPT_LIMIT,PAGE_LIMIT-bytes),epoch);if(data!=null)bytes+=data.length;}
  }
  static List<HttpUrl> scripts(String html,HttpUrl page) {
    LinkedHashSet<HttpUrl> result=new LinkedHashSet<>();HttpUrl base=page;boolean baseSeen=false;
    Matcher tags=TAG.matcher(html);while(tags.find()&&result.size()<6){
      if(tags.group().startsWith("<!--"))continue;
      boolean script=tags.group(1)!=null;String attributes=script?tags.group(1):tags.group(3);Matcher attrs=ATTR.matcher(attributes);String target=null,type="";
      while(attrs.find()){String name=attrs.group(1).toLowerCase(Locale.US),value=attrs.group(2)!=null?attrs.group(2):attrs.group(3)!=null?attrs.group(3):attrs.group(4);if(name.equals(script?"src":"href"))target=value.replace("&amp;","&").replace("&#38;","&");if(name.equals("type"))type=value;}
      if(!script){if(!baseSeen&&target!=null){HttpUrl resolved=page.resolve(target);if(resolved!=null)base=resolved;baseSeen=true;}continue;}
      if(target==null||!type.isEmpty()&&!type.equalsIgnoreCase("module")&&!type.toLowerCase(Locale.US).contains("javascript"))continue;
      HttpUrl resolved=base.resolve(target);if(resolved!=null)result.add(resolved);
    }
    return new ArrayList<>(result);
  }
  boolean idle(){return workers.getActiveCount()==0&&workers.getQueue().isEmpty();}
  public void cancel() { ++generation;workers.getQueue().clear();for(Call call:calls)call.cancel();queued.clear(); }
  public void close() { closed=true;cancel();workers.shutdownNow(); }
}
