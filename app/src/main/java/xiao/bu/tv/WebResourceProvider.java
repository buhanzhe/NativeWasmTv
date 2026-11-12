package xiao.bu.tv;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import okhttp3.*;

/** Private main-process owner of the common web cache and streaming downloads. */
public final class WebResourceProvider extends ContentProvider {
    private static final String PUBLIC="X-Ntv-Shared-Public";
    private static final long INLINE_CACHE_LIMIT=32*1024;
    private Cache cache;private OkHttpClient client,baseClient,nativeClient,nativeBase;private final Semaphore slots=new Semaphore(8);
    private final ExecutorService copies=Executors.newFixedThreadPool(8,r->{Thread t=new Thread(r,"web-resource-copy");t.setPriority(Thread.MIN_PRIORITY);return t;});
    private final ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"web-resource-deadline");t.setDaemon(true);return t;});
    private final ConcurrentHashMap<String,Transfer> transfers=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,Flight> flights=new ConcurrentHashMap<>();
    private static final class Flight {final CountDownLatch headers=new CountDownLatch(1),done=new CountDownLatch(1);volatile boolean cacheable;}
    private final class Transfer {
        final String id;final long created=SystemClock.elapsedRealtime();volatile Call call;volatile Response response;
        ParcelFileDescriptor read,write;boolean cancelled,delivered,done;ScheduledFuture<?> deadline;
        Transfer(String value){id=value;}
        synchronized void cancel(){
            cancelled=true;if(call!=null)call.cancel();if(response!=null)response.close();close(read);
            if(write!=null&&Build.VERSION.SDK_INT>=19)try{write.closeWithError("Web resource cancelled");}catch(IOException ignored){}
            else close(write);read=write=null;
        }
        synchronized ParcelFileDescriptor take()throws FileNotFoundException{
            if(cancelled||delivered||read==null)throw new FileNotFoundException("Resource unavailable");
            ParcelFileDescriptor value=read;read=null;delivered=true;if(done){if(deadline!=null)deadline.cancel(false);transfers.remove(id,this);}return value;
        }
        synchronized void finish(){done=true;response=null;write=null;if(delivered||cancelled){if(deadline!=null)deadline.cancel(false);transfers.remove(id,this);}}
    }
    private static void close(ParcelFileDescriptor descriptor){if(descriptor!=null)try{descriptor.close();}catch(IOException ignored){}}
    @Override public boolean onCreate(){return true;}
    private synchronized OkHttpClient client(){
        NetworkClient.initialize(getContext());
        OkHttpClient base=NetworkClient.sharedClient();if(client!=null&&baseClient==base)return client;baseClient=base;
        if(cache==null)cache=new Cache(new File(getContext().getCacheDir(),"web-resource-http-cache"),(getContext().getCacheDir().getUsableSpace()>=256L*1024*1024?64L:16L)*1024*1024);
        client=guarded(base);return client;
    }
    private synchronized OkHttpClient nativeClient()throws Exception{
        client();OkHttpClient base=LiteHttp.client(getContext(),NetworkClient.sharedClient().dns());
        if(nativeClient==null||nativeBase!=base){nativeBase=base;nativeClient=guarded(base);}return nativeClient;
    }
    private OkHttpClient guarded(OkHttpClient base){
        return base.newBuilder().cache(cache).cookieJar(CookieJar.NO_COOKIES)
            // This provider accepts only GET. Allow OkHttp to try the next
            // resolved address or replace a stale pooled connection, within
            // the same deadline; HTTP errors and TLS trust errors still fail.
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(true).callTimeout(35,TimeUnit.SECONDS)
            .addNetworkInterceptor(chain->{
                Response response=chain.proceed(chain.request());
                // Cache identity cannot silently switch accounts, UA or document origin.
                Set<String> vary=new LinkedHashSet<>();for(String value:response.headers("Vary"))for(String field:value.split(","))vary.add(field.trim());
                vary.addAll(Arrays.asList("User-Agent","Cookie","Authorization","Origin","Referer","Accept-Language"));
                StringBuilder fields=new StringBuilder();for(String field:vary){if(fields.length()>0)fields.append(", ");fields.append(field);}
                boolean publiclyReusable=response.cacheControl().isPublic()&&!response.cacheControl().isPrivate()&&!response.cacheControl().noStore()
                    &&response.headers("Set-Cookie").isEmpty()&&chain.request().header("Cookie")==null&&chain.request().header("Authorization")==null;
                for(String value:response.headers("Vary"))for(String field:value.split(","))if(field.trim().equalsIgnoreCase("Cookie")||field.trim().equalsIgnoreCase("Authorization")||field.trim().equals("*"))publiclyReusable=false;
                Response.Builder updated=response.newBuilder().header("Vary",fields.toString());
                if(response.code()!=304)updated.header(PUBLIC,publiclyReusable?"true":"false");
                String type=response.header("Content-Type","").toLowerCase(Locale.US);
                if(response.code()!=304&&(response.code()!=200||!allowedType(type)||!response.headers("Set-Cookie").isEmpty()))updated.header("Cache-Control","no-store");
                return updated.build();
            }).build();
    }
    private static boolean allowedType(String type){return type.startsWith("text/html")||type.startsWith("application/xhtml+xml")||type.startsWith("text/css")
        ||type.contains("javascript")||type.startsWith("application/wasm")||type.startsWith("image/")&&!type.startsWith("image/svg")
        ||type.startsWith("font/")||type.startsWith("application/font")||type.startsWith("application/x-font")||type.startsWith("application/vnd.ms-fontobject");}
    static boolean accepted(Response response,boolean document){
        String type=response.header("Content-Type","").toLowerCase(Locale.US);
        boolean html=type.startsWith("text/html")||type.startsWith("application/xhtml+xml");
        return document?html:!html&&allowedType(type);
    }
    @Override public Bundle call(String method,String id,Bundle args){
        Bundle reply=new Bundle();
        if(method!=null&&method.startsWith("profile_"))return SharedWebProfile.handle(getContext(),method.substring(8),args);
        if("clear".equals(method)){
            for(Transfer transfer:transfers.values())transfer.cancel();transfers.clear();
            try{client();cache.evictAll();LiteEngine.httpCache(getContext()).evictAll();reply.putBoolean("ok",true);}catch(IOException error){reply.putString("error",error.getMessage());}return reply;
        }
        if("cancel".equals(method)){
            if(id!=null&&id.length()<=64){Transfer value=transfers.get(id);if(value==null&&transfers.size()<128){Transfer marker=new Transfer(id);Transfer prior=transfers.putIfAbsent(id,marker);value=prior==null?marker:prior;}if(value!=null)value.cancel();}
            return reply;
        }
        if(!"open".equals(method)||id==null||id.length()>64||args==null)return reply;
        for(Transfer value:transfers.values())if(SystemClock.elapsedRealtime()-value.created>60000){value.cancel();transfers.remove(value.id,value);}
        Transfer created=new Transfer(id),existing=transfers.putIfAbsent(id,created),transfer=existing==null?created:existing;
        if(transfer.cancelled||existing!=null)return reply;
        boolean acquired=false,handed=false;Flight flight=null;String key=null;boolean leader=false;
        try{
            Request.Builder builder=new Request.Builder().url(args.getString("url"));
            if(args.getBoolean("document"))builder.tag(WebResourceHub.Kind.class,WebResourceHub.Kind.DOCUMENT);
            ArrayList<String> names=args.getStringArrayList("names"),values=args.getStringArrayList("values");
            if(names==null||values==null||names.size()!=values.size()||names.size()>96)return reply;
            if(names!=null&&values!=null&&names.size()<=96)for(int i=0;i<Math.min(names.size(),values.size());i++){
                String name=names.get(i);if(!Arrays.asList("host","connection","content-length","transfer-encoding","accept-encoding").contains(name.toLowerCase(Locale.US)))builder.header(name,values.get(i));
            }
            Request request=builder.build();if(!WebResourceHub.eligible(request))return reply;
            if(request.header("User-Agent")==null)builder.header("User-Agent",HlsProxyServer.DEFAULT_USER_AGENT);
            if(request.header("Accept-Language")==null)builder.header("Accept-Language",Locale.getDefault().toString().replace('_','-'));
            request=builder.build();long timeout=Math.max(1,Math.min(35000,args.getLong("timeout",12000))),until=SystemClock.elapsedRealtime()+timeout;
            key=key(request);Flight own=new Flight();Flight prior=flights.putIfAbsent(key,own);flight=prior==null?own:prior;leader=prior==null;
            if(!leader){
                while(!transfer.cancelled&&SystemClock.elapsedRealtime()<until&&!flight.headers.await(100,TimeUnit.MILLISECONDS)){}
                if(flight.cacheable)while(!transfer.cancelled&&SystemClock.elapsedRealtime()<until&&!flight.done.await(100,TimeUnit.MILLISECONDS)){}
            }
            if(transfer.cancelled||SystemClock.elapsedRealtime()>=until)return reply;
            acquired=slots.tryAcquire(100,TimeUnit.MILLISECONDS);if(!acquired)return reply;
            OkHttpClient transport=args.getBoolean("liteTls")?nativeClient():client();if(args.getBoolean("systemDns"))transport=transport.newBuilder().dns(LegacySystemDns.create()).build();
            Call call=transport.newCall(request);transfer.call=call;call.timeout().timeout(Math.max(1,until-SystemClock.elapsedRealtime()),TimeUnit.MILLISECONDS);if(transfer.cancelled)call.cancel();
            Response response=call.execute();transfer.response=response;
            boolean accepted=response.code()==200&&response.body()!=null&&accepted(response,args.getBoolean("document"))&&(!args.getBoolean("anonymous")||"true".equals(response.header(PUBLIC)));
            if(leader){flight.cacheable=accepted&&!response.cacheControl().noStore()&&response.headers("Set-Cookie").isEmpty();flight.headers.countDown();}
            if(!accepted)return reply;
            reply.putBoolean("handled",true);reply.putBoolean("cached",response.cacheResponse()!=null);reply.putLong("length",response.body().contentLength());reply.putString("type",response.header("Content-Type","application/octet-stream"));
            reply.putString("protocol",response.protocol().toString());reply.putLong("sent",response.sentRequestAtMillis());reply.putLong("received",response.receivedResponseAtMillis());
            reply.putBoolean("network",response.networkResponse()!=null);
            if(response.networkResponse()!=null)reply.putInt("networkCode",response.networkResponse().code());
            if(response.cacheResponse()!=null){reply.putLong("cacheSent",response.cacheResponse().sentRequestAtMillis());reply.putLong("cacheReceived",response.cacheResponse().receivedResponseAtMillis());}
            ArrayList<String> hn=new ArrayList<>(),hv=new ArrayList<>();for(int i=0;i<response.headers().size();i++){if(PUBLIC.equalsIgnoreCase(response.headers().name(i)))continue;hn.add(response.headers().name(i));hv.add(response.headers().value(i));}reply.putStringArrayList("names",hn);reply.putStringArrayList("values",hv);
            // OkHttp has already checked freshness, Vary, UA and credentials.
            // Fresh small disk hits need no pipe, descriptor RPC or copy worker.
            // Network bodies continue streaming so cold HTML never waits here.
            long cachedLength=response.body().contentLength();
            boolean smallCached=response.cacheResponse()!=null&&response.networkResponse()==null
                    &&(cachedLength>=0?cachedLength<=INLINE_CACHE_LIMIT:
                            !response.body().source().request(INLINE_CACHE_LIMIT+1));
            if(smallCached){
                byte[] body=response.body().bytes();
                if(transfer.cancelled)return new Bundle();
                reply.putLong("length",body.length);
                reply.putByteArray("body",body);
                return reply;
            }
            ParcelFileDescriptor[] pipe=Build.VERSION.SDK_INT>=19?ParcelFileDescriptor.createReliablePipe():ParcelFileDescriptor.createPipe();
            synchronized(transfer){if(transfer.cancelled){close(pipe[0]);close(pipe[1]);return new Bundle();}transfer.read=pipe[0];transfer.write=pipe[1];}
            final Flight selected=flight;final String selectedKey=key;final boolean owns=leader;
            transfer.deadline=deadlines.schedule(()->{transfer.cancel();transfers.remove(transfer.id,transfer);},Math.max(1,until-SystemClock.elapsedRealtime()),TimeUnit.MILLISECONDS);
            copies.execute(()->{
                try(Response owned=response){
                    OutputStream output=new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]);
                    InputStream input=owned.body().byteStream();byte[] buffer=new byte[32768];int count;
                    long copied=0,length=owned.body().contentLength();
                    while(!transfer.cancelled&&(count=input.read(buffer))!=-1){copied+=count;if(length>=0&&copied==length)input.read();output.write(buffer,0,count);}
                    output.close();
                }catch(IOException|RuntimeException error){
                    if(Build.VERSION.SDK_INT>=19)try{pipe[1].closeWithError("Web resource transfer failed");}catch(IOException ignored){}
                    if(transfer.call!=null)transfer.call.cancel();
                }
                finally{close(pipe[1]);transfer.finish();slots.release();if(owns){selected.done.countDown();flights.remove(selectedKey,selected);}}
            });handed=true;return reply;
        }catch(Exception error){
            if(error instanceof InterruptedException)Thread.currentThread().interrupt();
            Bundle failure=new Bundle();failure.putString("failure",error instanceof java.net.UnknownHostException?"dns":error instanceof InterruptedIOException?"timeout":error instanceof javax.net.ssl.SSLException?"tls":"io");return failure;
        }
        finally{if(!handed){transfer.cancel();transfers.remove(id,transfer);if(acquired)slots.release();if(leader&&flight!=null){flight.headers.countDown();flight.done.countDown();flights.remove(key,flight);}}}
    }
    private static String key(Request request)throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");digest.update(request.url().toString().getBytes("UTF-8"));
        TreeMap<String,String> headers=new TreeMap<>();for(String name:request.headers().names())headers.put(name.toLowerCase(Locale.US),request.header(name));
        for(Map.Entry<String,String> entry:headers.entrySet()){digest.update((byte)0);digest.update(entry.getKey().getBytes("UTF-8"));digest.update((byte)0);digest.update(entry.getValue().getBytes("UTF-8"));}
        return android.util.Base64.encodeToString(digest.digest(),android.util.Base64.NO_WRAP);
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{
        List<String> path=uri.getPathSegments();if(!"r".equals(mode)||path.size()!=2||!path.get(0).equals("data"))throw new FileNotFoundException("Invalid resource");
        Transfer transfer=transfers.get(path.get(1));if(transfer==null)throw new FileNotFoundException("Expired resource");return transfer.take();
    }
    @Override public String getType(Uri uri){return "application/octet-stream";}
    @Override public Cursor query(Uri uri,String[] p,String s,String[] a,String o){return null;}
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
    @Override public int delete(Uri uri,String s,String[] a){throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues values,String s,String[] a){throw new UnsupportedOperationException();}
}
