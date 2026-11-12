package xiao.bu.tv;

import android.content.*;
import android.net.Uri;
import android.os.*;
import android.webkit.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.*;
import okio.*;

/** Both browser processes use the main-process cache owner, never two disk writers. */
final class SharedWebResources implements WebResourceHub.Loader {
    private final Context context;private final Uri authority;
    private static SharedWebResources installed;
    private SharedWebResources(Context c){context=c.getApplicationContext();authority=Uri.parse("content://"+c.getPackageName()+".webresources");}
    static synchronized void install(Context context){NetworkClient.initialize(context);if(installed==null){installed=new SharedWebResources(context);WebResourceHub.install(installed);}}
    static void clear(Context context){Bundle reply=new SharedWebResources(context).rpc("clear",null,new Bundle());if(reply==null||!reply.getBoolean("ok"))throw new IllegalStateException(reply==null?"网页资源缓存服务未响应":reply.getString("error","网页资源缓存清理失败"));}
    private Bundle rpc(String method,String id,Bundle args){return context.getContentResolver().call(authority,method,id,args);}
    @Override public Call newCall(OkHttpClient client,Request request){return new ResourceCall(client,request);}
    private final class ResourceCall implements Call {
        private final OkHttpClient client;private final Request request;private final boolean fallbackAllowed;private final String id=UUID.randomUUID().toString();
        private final AtomicBoolean executed=new AtomicBoolean();private final Timeout timeout=new Timeout();
        private volatile boolean cancelled,opening;private volatile Call fallback;
        ResourceCall(OkHttpClient c,Request r){this(c,r,true);}
        ResourceCall(OkHttpClient c,Request r,boolean fallback){client=c;request=r;fallbackAllowed=fallback;timeout.timeout(c.callTimeoutMillis()>0?c.callTimeoutMillis():35000,TimeUnit.MILLISECONDS);}
        public Request request(){return request;}public boolean isExecuted(){return executed.get();}public boolean isCanceled(){return cancelled;}
        public Timeout timeout(){return timeout;}public Call clone(){return new ResourceCall(client,request,fallbackAllowed);}
        public void cancel(){cancelled=true;Call call=fallback;if(call!=null)call.cancel();if(opening)try{rpc("cancel",id,new Bundle());}catch(RuntimeException ignored){}}
        private void claim(){if(!executed.compareAndSet(false,true))throw new IllegalStateException("Already executed");}
        public void enqueue(Callback callback){
            claim();client.dispatcher().executorService().execute(()->{
                Response response;
                try{response=executeInternal();}catch(IOException error){callback.onFailure(this,error);return;}
                try{callback.onResponse(this,response);}catch(IOException error){response.close();AppLog.d("WebResources","Response callback closed: "+error.getClass().getSimpleName());}
            });
        }
        public Response execute()throws IOException{
            claim();return executeInternal();
        }
        private Response executeInternal()throws IOException{
            if(cancelled)throw new IOException("Cancelled");timeout.throwIfReached();
            long budget=timeout.timeoutNanos()>0?Math.min(timeout.timeoutNanos(),TimeUnit.SECONDS.toNanos(35)):TimeUnit.SECONDS.toNanos(35);
            if(timeout.hasDeadline())budget=Math.min(budget,timeout.deadlineNanoTime()-System.nanoTime());
            final long until=System.nanoTime()+budget;
            Request.Builder builder=request.newBuilder().url(request.url().newBuilder().fragment(null).build());
            List<Cookie> cookies=client.cookieJar().loadForRequest(request.url());
            if(!cookies.isEmpty()){StringBuilder value=new StringBuilder();for(Cookie cookie:cookies){if(value.length()>0)value.append("; ");value.append(cookie.name()).append('=').append(cookie.value());}builder.header("Cookie",value.toString());}
            Request actual=builder.build();Bundle args=new Bundle();args.putString("url",actual.url().toString());
            args.putBoolean("document",actual.tag(WebResourceHub.Kind.class)==WebResourceHub.Kind.DOCUMENT);
            args.putBoolean("anonymous",actual.tag(WebResourceHub.Kind.class)==WebResourceHub.Kind.PUBLIC_STATIC);
            args.putBoolean("systemDns",LegacySystemDns.isSystem(client.dns()));
            args.putBoolean("liteTls",client.sslSocketFactory() instanceof LiteTlsSocket.Factory);
            args.putLong("timeout",Math.max(1,TimeUnit.NANOSECONDS.toMillis(until-System.nanoTime())));
            ArrayList<String> names=new ArrayList<>(),values=new ArrayList<>();for(int i=0;i<actual.headers().size();i++){names.add(actual.headers().name(i));values.add(actual.headers().value(i));}args.putStringArrayList("names",names);args.putStringArrayList("values",values);
            Response result=null;boolean transportFailed=false;
            try{
                opening=true;if(cancelled)throw new IOException("Cancelled");
                Bundle reply=rpc("open",id,args);
                if(cancelled)throw new IOException("Cancelled");
                if(reply!=null&&reply.containsKey("failure")){
                    transportFailed=true;String failure=reply.getString("failure");
                    if("dns".equals(failure))throw new java.net.UnknownHostException("Web resource DNS failed");
                    if("timeout".equals(failure))throw new java.net.SocketTimeoutException("Web resource timeout");
                    if("tls".equals(failure))throw new javax.net.ssl.SSLException("Web resource TLS failed");
                    throw new IOException("Web resource download failed");
                }
                if(reply!=null&&reply.getBoolean("handled")){
                    final long expected=reply.getLong("length",-1);
                    byte[] cachedBody=reply.getByteArray("body");
                    InputStream stream;
                    if(cachedBody!=null){
                        if(cachedBody.length>32768||cachedBody.length!=expected)throw new IOException("Invalid cached resource");
                        stream=new ByteArrayInputStream(cachedBody);
                    }else{
                        ParcelFileDescriptor descriptor=context.getContentResolver().openFileDescriptor(authority.buildUpon().appendPath("data").appendPath(id).build(),"r");
                        if(descriptor==null)throw new IOException("Missing resource pipe");
                        stream=new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
                    }
                    InputStream body=new FilterInputStream(stream){
                        private boolean ended;private long received;
                        private int checked(int n,int count)throws IOException{if(n<0){ended=true;if(expected>=0&&received!=expected)throw new EOFException("Incomplete web resource");}else received+=count;return n;}
                        public int read()throws IOException{int n=in.read();return checked(n,n<0?0:1);}
                        public int read(byte[] b,int off,int len)throws IOException{int n=in.read(b,off,len);return checked(n,Math.max(0,n));}
                        public void close()throws IOException{try{if(!ended&&cachedBody==null)cancel();}finally{super.close();}}
                    };
                    Headers.Builder headers=new Headers.Builder();ArrayList<String> hn=reply.getStringArrayList("names"),hv=reply.getStringArrayList("values");
                    if(hn!=null&&hv!=null)for(int i=0;i<Math.min(hn.size(),hv.size());i++)headers.add(hn.get(i),hv.get(i));
                    Headers responseHeaders=headers.build();client.cookieJar().saveFromResponse(actual.url(),Cookie.parseAll(actual.url(),responseHeaders));
                    result=new Response.Builder().request(WebResourceHub.responseRequest(request,actual)).protocol(Protocol.get(reply.getString("protocol","http/1.1"))).code(200).message("OK").headers(responseHeaders)
                        .sentRequestAtMillis(reply.getLong("sent")).receivedResponseAtMillis(reply.getLong("received"))
                        .body(ResponseBody.create(MediaType.parse(reply.getString("type","application/octet-stream")),reply.getLong("length",-1),Okio.buffer(Okio.source(body)))).build();
                    Response metadata=result.newBuilder().body(null).build();
                    if(reply.getBoolean("cached"))result=result.newBuilder().cacheResponse(metadata.newBuilder().sentRequestAtMillis(reply.getLong("cacheSent")).receivedResponseAtMillis(reply.getLong("cacheReceived")).build()).build();
                    if(reply.getBoolean("network"))result=result.newBuilder().networkResponse(metadata.newBuilder().code(reply.getInt("networkCode",200)).build()).build();
                    if(cancelled){result.close();throw new IOException("Cancelled");}return result;
                }
            }catch(RuntimeException error){if(cancelled)throw new IOException("Cancelled",error);}
            catch(IOException error){if(result!=null)result.close();if(cancelled||transportFailed)throw error;}
            finally{if(result==null)try{rpc("cancel",id,new Bundle());}catch(RuntimeException ignored){}}
            if(cancelled)throw new IOException("Cancelled");
            // WebView already owns fallback. Repeating this request through a
            // second OkHttp call before returning null would download rejected
            // responses and redirects twice before Chromium even starts.
            if(!fallbackAllowed)throw new IOException("Resource left to WebView");
            long remaining=until-System.nanoTime();if(remaining<=0)throw new InterruptedIOException("Web resource timeout");
            if(args.getBoolean("anonymous"))throw new IOException("Public resource unavailable");
            fallback=client.newCall(request);fallback.timeout().timeout(remaining,TimeUnit.NANOSECONDS);if(cancelled)fallback.cancel();return fallback.execute();
        }
    }
    static WebResourceResponse intercept(Context context,String url,String page,String agent,Map<String,String> supplied,boolean document){
        if(url==null||!url.startsWith("http://")&&!url.startsWith("https://"))return null;
        try{
            Request.Builder builder=new Request.Builder().url(url);if(document)builder.tag(WebResourceHub.Kind.class,WebResourceHub.Kind.DOCUMENT);
            if(supplied!=null)for(Map.Entry<String,String> header:supplied.entrySet())builder.header(header.getKey(),header.getValue());
            builder.header("User-Agent",agent);if(builder.build().header("Referer")==null&&page!=null)builder.header("Referer",page);
            Request request=builder.build();if(!WebResourceHub.eligible(request))return null;
            HttpUrl parent=HttpUrl.parse(page==null?"":page);boolean same=parent!=null&&parent.scheme().equals(request.url().scheme())&&parent.host().equals(request.url().host())&&parent.port()==request.url().port();
            if(Build.VERSION.SDK_INT<21){
                // KitKat cannot expose request cookies/headers and its CookieManager
                // deadlocks inside interception. Only anonymous, explicitly public static
                // responses can be shared here; authenticated/private loads stay native.
                if(!same||document)return null;
                request=request.newBuilder().removeHeader("Cookie").removeHeader("Authorization").tag(WebResourceHub.Kind.class,WebResourceHub.Kind.PUBLIC_STATIC).build();
            }else if(request.header("Cookie")==null){String cookies=CookieManager.getInstance().getCookie(url);if(cookies!=null&&!cookies.isEmpty()){
                if(!same)return null;request=request.newBuilder().header("Cookie",cookies).build();
            }}
            SharedWebResources owner=new SharedWebResources(context);Response response=owner.new ResourceCall(NetworkClient.sharedClient(),request,false).execute();
            if(response.code()!=200||response.body()==null||!response.request().url().equals(request.url())||!WebResourceProvider.accepted(response,document)){response.close();return null;}
            for(String cookie:response.headers("Set-Cookie"))CookieManager.getInstance().setCookie(url,cookie);
            MediaType type=response.body().contentType();String mime=type==null?"application/octet-stream":type.type()+"/"+type.subtype();
            String encoding=type!=null&&type.charset()!=null?type.charset().name():mime.startsWith("text/")||mime.contains("javascript")?"UTF-8":null;
            WebResourceResponse out=new WebResourceResponse(mime,encoding,new FilterInputStream(response.body().byteStream()){
                public void close()throws IOException{try{super.close();}finally{response.close();}}
            });
            if(Build.VERSION.SDK_INT>=21){Map<String,String> headers=new HashMap<>();for(String name:response.headers().names())if(!name.equalsIgnoreCase("Set-Cookie"))headers.put(name,response.header(name));out.setStatusCodeAndReasonPhrase(200,"OK");out.setResponseHeaders(headers);}
            return out;
        }catch(IOException|RuntimeException error){return null;}
    }
}
