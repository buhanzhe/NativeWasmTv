package xiao.bu.channel.bl;

import android.content.SharedPreferences;
import org.json.*;
import okhttp3.*;
import okhttp3.Cookie;
import java.io.*;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Java/API-14 port of the pinned newBV Web API contracts. No private transport is created. */
final class BlApi {
    static final String UA="Mozilla/5.0 (Linux; Android 7.0) AppleWebKit/537.36 Chrome/119.0.0.0 Safari/537.36";
    // Bilibili's web DASH mirrors can reject the mobile client UA for the same signed URL.
    private static final String DASH_UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    static final String API="https://api.bilibili.com",PASSPORT="https://passport.bilibili.com",LIVE="https://api.live.bilibili.com";
    // newBV requests the full DASH format set and selects a representation locally.
    private static final String DASH_FORMATS="4048",DASH_MAX_QUALITY=Integer.toString(BlStreams.QUALITY_8K);
    private static final CacheControl MEDIA_CACHE_CONTROL=new CacheControl.Builder().noStore().build();
    private static final int[] MIX={46,47,18,2,53,8,23,32,15,50,10,31,58,3,45,35,27,43,5,49,33,9,42,19,29,28,14,39,12,38,41,13,37,48,7,16,24,55,40,61,26,17,0,1,60,51,30,4,22,25,54,21,56,59,6,63,57,62,11,36,20,34,44,52};
    final OkHttpClient client;
    final SharedPreferences prefs;
    final BlDiagnostics diagnostics=new BlDiagnostics();
    private final Map<String,BlDiagnostics.Failure> transportFailures=Collections.synchronizedMap(new LinkedHashMap<String,BlDiagnostics.Failure>(32,.75f,true){protected boolean removeEldestEntry(Map.Entry<String,BlDiagnostics.Failure> entry){return size()>32;}});
    BlDiagnostics.Failure transportFailure(String url){HttpUrl parsed=HttpUrl.parse(url);return parsed==null?null:transportFailures.get(parsed.toString());}
    void clearTransportFailure(String url){HttpUrl parsed=HttpUrl.parse(url);if(parsed!=null)transportFailures.remove(parsed.toString());}
    private final JSONObject config;
    private final Set<Call> pending=Collections.newSetFromMap(new ConcurrentHashMap<Call,Boolean>());
    private final List<Cookie> cookies=new ArrayList<>();
    private volatile boolean closed;
    private final Object signingLock=new Object();
    private volatile long serverOffset;
    private String wbi="";private long wbiTime;private boolean bootstrapped;private JSONObject historyCursor=new JSONObject();
    BlApi(OkHttpClient client,SharedPreferences prefs,JSONObject config){
        this.client=client;this.prefs=prefs;this.config=config;
        try{JSONArray a=new JSONArray(prefs.getString("cookies","[]"));for(int i=0;i<a.length();i++){JSONObject c=a.getJSONObject(i);Cookie.Builder b=new Cookie.Builder().name(c.getString("name")).value(c.getString("value")).domain(c.getString("domain")).path(c.optString("path","/"));b.expiresAt(c.optLong("expires",Long.MAX_VALUE));if(c.optBoolean("secure"))b.secure();cookies.add(b.build());}}catch(Exception ignored){}
    }
    synchronized boolean signedIn(){for(Cookie c:cookies)if(c.name().equals("SESSDATA")&&c.expiresAt()>System.currentTimeMillis()&&!c.value().isEmpty())return true;return false;}
    synchronized String cookie(String name){for(Cookie c:cookies)if(c.name().equals(name)&&c.expiresAt()>System.currentTimeMillis())return c.value();return "";}
    synchronized String cookieHeader(HttpUrl url){StringBuilder s=new StringBuilder();for(Cookie c:cookies)if(c.matches(url)&&c.expiresAt()>System.currentTimeMillis()){if(s.length()>0)s.append("; ");s.append(c.name()).append('=').append(c.value());}return s.toString();}
    synchronized void logout(){cookies.clear();prefs.edit().remove("cookies").remove("user").apply();}
    private synchronized void receiveCookies(Response response){
        String host=response.request().url().host();if(!host.equals("bilibili.com")&&!host.endsWith(".bilibili.com"))return;
        for(String header:response.headers("Set-Cookie")){
            Cookie c=Cookie.parse(response.request().url(),header);if(c==null)continue;
            for(Iterator<Cookie> i=cookies.iterator();i.hasNext();){Cookie old=i.next();if(old.name().equals(c.name())&&old.domain().equals(c.domain())&&old.path().equals(c.path()))i.remove();}
            if(c.expiresAt()>System.currentTimeMillis())cookies.add(c);
        }
        if(cookies.size()>48)cookies.subList(0,cookies.size()-48).clear();
        JSONArray a=new JSONArray();for(Cookie c:cookies)try{a.put(new JSONObject().put("name",c.name()).put("value",c.value()).put("domain",c.domain()).put("path",c.path()).put("expires",c.expiresAt()).put("secure",c.secure()));}catch(JSONException ignored){}
        prefs.edit().putString("cookies",a.toString()).apply();
    }
    Request.Builder request(String url){return request(url,UA);}
    private Request.Builder request(String url,String userAgent){
        HttpUrl parsed=HttpUrl.parse(url);if(parsed==null)throw new IllegalArgumentException("无效地址");
        Request.Builder b=new Request.Builder().url(parsed).header("User-Agent",userAgent).header("Referer",url.contains("/search/")?"https://search.bilibili.com/":url.contains("api.live.")?"https://live.bilibili.com/":"https://www.bilibili.com/");
        String cookie=cookieHeader(parsed);if(!cookie.isEmpty())b.header("Cookie",cookie);return b;
    }
    Request.Builder mediaRequest(String url){return request(url).cacheControl(MEDIA_CACHE_CONTROL);}
    Request.Builder dashRequest(String url,long position,long length){
        String end=length<0?"":Long.toString(position+length-1);
        return request(url,DASH_UA).cacheControl(MEDIA_CACHE_CONTROL)
                .header("Accept-Encoding","identity").header("Range","bytes="+position+"-"+end);
    }
    Response execute(Request request)throws IOException{return execute(request,null);}
    Response execute(Request request,final Set<Call> owner)throws IOException{
        return execute(request,owner,null);
    }
    Response execute(Request request,final Set<Call> owner,java.util.concurrent.atomic.AtomicBoolean cancelled)throws IOException{
        return execute(request,owner,cancelled,null);
    }
    Response execute(Request request,final Set<Call> owner,java.util.concurrent.atomic.AtomicBoolean cancelled,
                     java.util.concurrent.atomic.AtomicLong mediaBytes)throws IOException{
        if(closed)throw new IOException("频道已关闭");final Call call=client.newCall(request);pending.add(call);if(owner!=null)owner.add(call);
        try{
            if(closed||cancelled!=null&&cancelled.get()){call.cancel();throw new IOException("请求已取消");}Response response=call.execute();final ResponseBody body=response.body();
            if(!response.isSuccessful()){BlDiagnostics.Failure failure=BlDiagnostics.response(response);transportFailures.put(request.url().toString(),failure);diagnostics.record("HTTP 响应",failure,"");}else transportFailures.remove(request.url().toString());
            if(body==null){pending.remove(call);if(owner!=null)owner.remove(call);return response;}
            boolean countMedia=mediaBytes!=null&&response.isSuccessful()&&response.cacheResponse()==null;
            final okio.BufferedSource source=okio.Okio.buffer(new okio.ForwardingSource(body.source()){
                @Override public long read(okio.Buffer sink,long amount)throws IOException{
                    long read=super.read(sink,amount);
                    if(countMedia&&read>0)mediaBytes.addAndGet(read);
                    return read;
                }
                public void close()throws IOException{try{super.close();}finally{pending.remove(call);if(owner!=null)owner.remove(call);}}
            });
            return response.newBuilder().body(new OwnedBody(call,body.contentType(),body.contentLength(),source)).build();
        }catch(IOException e){pending.remove(call);if(owner!=null)owner.remove(call);if(closed||call.isCanceled())throw e;BlDiagnostics.Failure failure=new BlDiagnostics.Failure("网络连接",e.getMessage(),request,0,0,"","",e);transportFailures.put(request.url().toString(),failure);diagnostics.record("连接请求",failure,"");throw failure;}
    }
    private static final class OwnedBody extends ResponseBody {
        final Call call;final MediaType type;final long length;final okio.BufferedSource source;
        OwnedBody(Call call,MediaType type,long length,okio.BufferedSource source){this.call=call;this.type=type;this.length=length;this.source=source;}
        public MediaType contentType(){return type;}public long contentLength(){return length;}public okio.BufferedSource source(){return source;}
    }
    // Call.cancel schedules HTTP/2 RST on OkHttp's writer. Response.close may write it inline.
    void cancel(Response response){if(response.body() instanceof OwnedBody)((OwnedBody)response.body()).call.cancel();}
    byte[] bytes(String url,int limit)throws Exception{
        try(Response r=execute(request(url).build())){if(!r.isSuccessful()||r.body()==null)throw BlDiagnostics.response(r);InputStream in=r.body().byteStream();if("deflate".equalsIgnoreCase(r.header("Content-Encoding")))in=new java.util.zip.InflaterInputStream(in);try{return read(in,limit);}catch(IOException e){throw new BlDiagnostics.Failure("响应读取",e.getMessage(),r.request(),r.code(),0,"",r.header("Content-Type",""),e);}}
    }
    static byte[] read(InputStream in,int max)throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] chunk=new byte[16384];int n;while((n=in.read(chunk))!=-1){if(b.size()+n>max)throw new IOException("响应超过大小限制");b.write(chunk,0,n);}return b.toByteArray();}
    String endpoint(String origin,String path){
        String replacement=origin.equals(API)?config.optString("apiOrigin",origin):origin.equals(PASSPORT)?config.optString("passportOrigin",origin):config.optString("liveOrigin",origin);
        return replacement.replaceAll("/$","")+path;
    }
    private String query(String origin,String path,Map<String,String> params,boolean sign)throws Exception{
        TreeMap<String,String> query=new TreeMap<>(params);if(sign)sign(query);
        StringBuilder u=new StringBuilder(endpoint(origin,path));for(Map.Entry<String,String> p:query.entrySet()){u.append(u.indexOf("?")<0?'?':'&').append(encode(p.getKey())).append('=').append(encode(p.getValue()));}
        return u.toString();
    }
    JSONObject get(String origin,String path,Map<String,String> params,boolean sign)throws Exception{return json(request(query(origin,path,params,sign)).build());}
    byte[] binary(String path,Map<String,String> params,int limit)throws Exception{return bytes(query(API,path,params,path.contains("/wbi/")),limit);}
    JSONObject get(String path,String... pairs)throws Exception{return get(API,path,map(pairs),path.contains("/wbi/"));}
    private JSONObject getDash(String path,String... pairs)throws Exception{
        return data(json(request(query(API,path,map(pairs),path.contains("/wbi/")),DASH_UA).build()));
    }
    JSONObject post(String path,String... pairs)throws Exception{
        requireLogin();FormBody.Builder body=new FormBody.Builder();Map<String,String> data=map(pairs);data.put("csrf",cookie("bili_jct"));for(Map.Entry<String,String> p:data.entrySet())body.add(p.getKey(),p.getValue());
        return json(request(endpoint(API,path)).post(body.build()).build());
    }
    private JSONObject json(Request request)throws Exception{
        try(Response r=execute(request)){
            receiveCookies(r);if(!r.isSuccessful()||r.body()==null)throw BlDiagnostics.response(r);
            try{java.text.SimpleDateFormat f=new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz",Locale.US);long server=f.parse(r.header("Date")).getTime()+Math.min(3600,Long.parseLong(r.header("Age","0")))*1000;long offset=server-System.currentTimeMillis();if(Math.abs(offset)<7L*86400000)serverOffset=offset;}catch(Exception ignored){}
            JSONObject value;try{value=new JSONObject(new String(read(r.body().byteStream(),8*1024*1024),"UTF-8"));}catch(JSONException e){throw new BlDiagnostics.Failure("响应格式","接口响应不是有效 JSON",request,r.code(),0,r.header("X-Bili-Trace-Id",""),r.header("Content-Type",""),null);}catch(IOException e){throw new BlDiagnostics.Failure("响应读取",e.getMessage(),request,r.code(),0,"",r.header("Content-Type",""),e);}int code=value.optInt("code",0);
            boolean navKeys=request.url().encodedPath().endsWith("/x/web-interface/nav")&&value.optJSONObject("data")!=null&&value.optJSONObject("data").optJSONObject("wbi_img")!=null;
            if(code!=0&&!navKeys)throw new ApiException(code,value.optString("message",value.optString("msg","请求失败")),request,r.header("X-Bili-Trace-Id",""));
            JSONObject data=value.optJSONObject("data");if(data!=null&&data.has("v_voucher"))throw new ApiException(-352,"站点要求验证，请登录后重试或稍后再试",request,r.header("X-Bili-Trace-Id",""));return value;
        }
    }
    private void sign(Map<String,String> values)throws Exception{synchronized(signingLock){
        if(!bootstrapped){bootstrapped=true;try{
            JSONObject spi=data(get(API,"/x/frontend/finger/spi",map(),false));String b3=spi.optString("b_3",spi.optString("b3"));synchronized(this){if(!b3.isEmpty()&&cookie("buvid3").isEmpty())cookies.add(new Cookie.Builder().name("buvid3").value(b3).domain("bilibili.com").path("/").build());}
            if(config.optString("apiOrigin",API).equals(API))try(Response r=execute(request("https://www.bilibili.com/").build())){receiveCookies(r);}
        }catch(Exception ignored){/* A temporary SPI failure must not block public read APIs. */}}
        if(wbi.isEmpty()||System.currentTimeMillis()-wbiTime>3600000){
            JSONObject data=get(API,"/x/web-interface/nav",map(),false).optJSONObject("data");JSONObject keys=data==null?null:data.optJSONObject("wbi_img");if(keys==null)throw new IOException("暂时无法获取接口签名，请重试");
            String all=key(keys.getString("img_url"))+key(keys.getString("sub_url"));StringBuilder mixed=new StringBuilder();for(int i:MIX)if(i<all.length())mixed.append(all.charAt(i));if(mixed.length()<32)throw new IOException("接口签名无效");wbi=mixed.substring(0,32);wbiTime=System.currentTimeMillis();
        }
        values.put("wts",String.valueOf((System.currentTimeMillis()+serverOffset)/1000));TreeMap<String,String> sorted=new TreeMap<>(values);StringBuilder query=new StringBuilder();
        for(Map.Entry<String,String> e:sorted.entrySet()){String value=e.getValue().replaceAll("[!'()*]","");values.put(e.getKey(),value);if(query.length()>0)query.append('&');query.append(encode(e.getKey())).append('=').append(encode(value));}
        values.put("w_rid",md5(query+wbi));
    }}
    static String key(String url){int a=url.lastIndexOf('/'),b=url.lastIndexOf('.');return b>a?url.substring(a+1,b):"";}
    static String encode(String s)throws Exception{return URLEncoder.encode(s,"UTF-8").replace("+","%20");}
    static String md5(String s)throws Exception{byte[] hash=MessageDigest.getInstance("MD5").digest(s.getBytes("UTF-8"));StringBuilder out=new StringBuilder();for(byte b:hash)out.append(String.format(Locale.US,"%02x",b&255));return out.toString();}
    static Map<String,String> map(String... pairs){Map<String,String> m=new LinkedHashMap<>();for(int i=0;i+1<pairs.length;i+=2)if(pairs[i+1]!=null)m.put(pairs[i],pairs[i+1]);return m;}
    static JSONObject data(JSONObject response){JSONObject d=response.optJSONObject("data");if(d==null)d=response.optJSONObject("result");return d==null?new JSONObject():d;}
    void requireLogin()throws ApiException{if(!signedIn())throw new ApiException(-101,"请先扫码登录");}
    long uid(){try{return Long.parseLong(cookie("DedeUserID"));}catch(Exception e){return 0;}}
    int publishedBlVersion()throws Exception{
        byte[] archive=bytes("https://raw.githubusercontent.com/buhanzhe/NativeWasmTv/master/plugins/bl/dist/bl.jar",4*1024*1024);
        try(java.util.zip.ZipInputStream zip=new java.util.zip.ZipInputStream(new ByteArrayInputStream(archive))){
            for(int entryCount=0;entryCount<6;entryCount++){
                java.util.zip.ZipEntry entry=zip.getNextEntry();
                if(entry==null)break;
                if("channel-plugin.json".equals(entry.getName())){
                    JSONObject manifest=new JSONObject(new String(read(zip,4096),"UTF-8"));
                    int version=manifest.optInt("version");
                    if("xiao.bu.channel.bl.BlPlugin".equals(manifest.optString("entry"))&&version>0)return version;
                    break;
                }
                read(zip,4*1024*1024);
            }
        }
        throw new IOException("发布文件缺少有效的 BL 版本信息");
    }
    JSONObject profile()throws Exception{JSONObject d=data(get("/x/web-interface/nav"));if(!d.optBoolean("isLogin"))throw new ApiException(-101,"登录已失效，请重新扫码");prefs.edit().putString("user",d.toString()).apply();return d;}
    JSONArray popular(int page)throws Exception{return array(data(get("/x/web-interface/popular","pn",""+page,"ps","24")),"list");}
    JSONArray home(int page)throws Exception{
        JSONObject d=data(get("/x/web-interface/wbi/index/top/feed/rcmd","fresh_type","3","ps","24","fresh_idx",""+page,"fresh_idx_1h",""+page,"feed_version","V8"));return array(d,"item");
    }
    JSONObject detail(String bvid)throws Exception{return data(get("/x/web-interface/view",bvid.startsWith("BV")?"bvid":"aid",bvid));}
    JSONArray related(String bvid)throws Exception{return array(get("/x/web-interface/archive/related","bvid",bvid),"data");}
    JSONObject play(String bvid,long cid,int quality)throws Exception{
        JSONObject d=data(get("/x/player/playurl","bvid",bvid,"cid",""+cid,"qn",""+quality,"fnval","1","fnver","0","fourk","0","type","mp4","platform","html5","otype","json"));
        if(d.has("v_voucher"))throw new ApiException(-352,"播放接口要求验证，请稍后重试或登录");return d;
    }
    JSONObject playDash(String bvid,long cid)throws Exception{
        return getDash("/x/player/wbi/playurl","bvid",bvid,"cid",""+cid,"qn",DASH_MAX_QUALITY,"fnval",DASH_FORMATS,"fnver","0","fourk","1","prefer_codec","7");
    }
    JSONObject episodeDash(long ep,long cid)throws Exception{
        String[] query={"ep_id",""+ep,"cid",""+cid,"qn",DASH_MAX_QUALITY,"fnval",DASH_FORMATS,"fnver","0","fourk","1"};
        try{
            JSONObject video=getDash("/pgc/player/web/v2/playurl",query).optJSONObject("video_info");
            if(video!=null&&(video.optJSONObject("dash")!=null||array(video,"durl").length()>0))return video;
        }catch(Exception error){
            diagnostics.record("番剧 v2 DASH 接口失败",error,"尝试番剧兼容接口");
        }
        return getDash("/pgc/player/web/playurl",query);
    }
    JSONArray search(String word,String type,int page)throws Exception{return array(data(get("/x/web-interface/wbi/search/type","keyword",word,"search_type",type,"page",""+page,"page_size","24")),"result");}
    List<String> trendingSearches()throws Exception{
        JSONObject trending=data(get("/x/web-interface/wbi/search/square","limit","10")).optJSONObject("trending");
        JSONArray rows=array(trending,"list");
        Set<String> words=new LinkedHashSet<>();
        for(int i=0;i<rows.length()&&words.size()<10;i++){
            JSONObject row=rows.optJSONObject(i);
            String word=row==null?"":row.optString("keyword").trim();
            if(!word.isEmpty()&&word.length()<=256)words.add(word);
        }
        return new ArrayList<>(words);
    }
    JSONArray history(int page)throws Exception{if(!signedIn())return new JSONArray(prefs.getString("history","[]"));if(page==1)historyCursor=new JSONObject();JSONObject value=data(get("/x/web-interface/history/cursor","ps","24","view_at",""+historyCursor.optLong("view_at"),"max",""+historyCursor.optLong("max"),"business",historyCursor.optString("business","archive")));JSONObject cursor=value.optJSONObject("cursor");if(cursor!=null)historyCursor=cursor;return array(value,"list");}
    JSONArray dynamic(String offset)throws Exception{requireLogin();return array(data(get("/x/polymer/web-dynamic/v1/feed/all","type","video","offset",offset)),"items");}
    JSONArray favoriteFolders()throws Exception{requireLogin();return array(data(get("/x/v3/fav/folder/created/list-all","up_mid",""+uid())),"list");}
    JSONArray favorites(long id,int page)throws Exception{requireLogin();return array(data(get("/x/v3/fav/resource/list","media_id",""+id,"pn",""+page,"ps","24","order","mtime","type","0")),"medias");}
    JSONArray later()throws Exception{requireLogin();return array(data(get("/x/v2/history/toview")),"list");}
    JSONArray followedSeasons(int type,int page)throws Exception{
        requireLogin();
        long user=uid();
        if(user<=0)throw new ApiException(-101,"账号信息已过期，请重新登录");
        return array(data(get("/x/space/bangumi/follow/list","type",""+type,"follow_status","0",
                "vmid",""+user,"pn",""+page,"ps","24")),"list");
    }
    JSONArray followed(int page)throws Exception{requireLogin();return array(data(get("/x/relation/followings","vmid",""+uid(),"pn",""+page,"ps","24","order","desc")),"list");}
    JSONArray uploads(long mid,int page)throws Exception{return array(data(get("/x/space/wbi/arc/search","mid",""+mid,"pn",""+page,"ps","24","order","pubdate")).optJSONObject("list"),"vlist");}
    JSONArray region(int rid,int page)throws Exception{JSONObject d=data(get("/x/web-interface/region/feed/rcmd","from_region",""+rid,"display_id",""+page,"request_cnt","15","device","web","plat","30"));JSONArray list=array(d,"archives");return list.length()>0?list:array(d,"items");}
    JSONArray seasons(int page,int seasonType)throws Exception{return array(data(get("/pgc/season/index/result","season_type",""+seasonType,"type","1","page",""+page,"pagesize","24","order","3","sort","0")),"list");}
    JSONObject season(long id)throws Exception{return data(get("/pgc/view/web/season","season_id",""+id));}
    JSONObject episodePlay(long episode,long cid,int quality)throws Exception{return data(get("/pgc/player/web/playurl","ep_id",""+episode,"cid",""+cid,"qn",""+quality,"fnval","1","fourk","0"));}
    JSONArray comments(long aid,int page)throws Exception{return array(data(get("/x/v2/reply","type","1","oid",""+aid,"pn",""+page,"ps","20","sort","2")),"replies");}
    JSONArray subtitles(String bvid,long cid)throws Exception{JSONObject sub=data(get("/x/player/wbi/v2","bvid",bvid,"cid",""+cid)).optJSONObject("subtitle");return array(sub,"subtitles");}
    JSONArray live(int page)throws Exception{return array(data(get(LIVE,"/xlive/web-interface/v1/webMain/getMoreRecList",map("platform","web","page",""+page),false)),"recommend_room_list");}
    JSONArray liveAreas()throws Exception{
        return array(get(LIVE,"/room/v1/area/getList",Collections.<String,String>emptyMap(),false),"data");
    }
    JSONArray liveArea(int category,int page)throws Exception{
        return array(get(LIVE,"/room/v1/Area/getRoomList",map("parent_area_id",""+category,"area_id","0",
                "page",""+page,"page_size","24","sort_type","online"),false),"data");
    }
    JSONObject livePlay(long room,int quality,boolean system)throws Exception{try{return BlLiveStreams.select(data(get(LIVE,"/xlive/web-room/v2/index/getRoomPlayInfo",map("room_id",""+room,"protocol","0,1","format","0,1,2","codec","0","qn",""+quality,"platform","web","ptype","8"),false)),system);}catch(Exception e){diagnostics.record("直播 v2 回退旧接口",e,"room="+room+"; 请求画质="+quality);return BlLiveStreams.legacy(data(get(LIVE,"/room/v1/Room/playUrl",map("cid",""+room,"platform","h5","quality","4","qn",""+quality),false)));}}
    JSONObject liveLegacy(long room,int quality)throws Exception{return BlLiveStreams.legacy(data(get(LIVE,"/room/v1/Room/playUrl",map("cid",""+room,"platform","h5","quality","4","qn",""+quality),false)));}
    JSONObject qr()throws Exception{return data(get(PASSPORT,"/x/passport-login/web/qrcode/generate",map(),false));}
    JSONObject poll(String key)throws Exception{return data(get(PASSPORT,"/x/passport-login/web/qrcode/poll",map("qrcode_key",key),false));}
    void remember(JSONObject video,long cid){try{
        JSONArray old=new JSONArray(prefs.getString("history","[]")),a=new JSONArray();JSONObject item=new JSONObject();for(String key:new String[]{"bvid","aid","title","pic","duration","owner","stat"})if(video.has(key))item.put(key,video.opt(key));item.put("cid",cid);a.put(item);
        for(int i=0;i<old.length()&&a.length()<100;i++){JSONObject v=old.optJSONObject(i);if(v!=null&&!v.optString("bvid").equals(video.optString("bvid")))a.put(v);}prefs.edit().putString("history",a.toString()).apply();
    }catch(Exception ignored){}}
    int lastWatchedPart(JSONObject video){
        JSONArray parts=array(video,"pages");
        String bvid=video.optString("bvid");
        if(parts.length()<2||bvid.isEmpty())return 0;
        try{
            JSONArray history=new JSONArray(prefs.getString("history","[]"));
            for(int i=0;i<history.length();i++){
                JSONObject item=history.optJSONObject(i);
                if(item==null||!bvid.equals(item.optString("bvid")))continue;
                long saved=item.optLong("cid");
                for(int part=0;part<parts.length();part++){
                    JSONObject entry=parts.optJSONObject(part);
                    if(entry!=null&&saved>0&&entry.optLong("cid")==saved)return part;
                }
                break;
            }
        }catch(JSONException ignored){}
        return 0;
    }

    long resumePosition(String bvid,long cid){return Math.max(0,prefs.getLong("position:"+bvid+":"+cid,0));}
    void savePosition(String bvid,long cid,long position){SharedPreferences.Editor edit=prefs.edit();String key="position:"+bvid+":"+cid;int count=0;for(String existing:prefs.getAll().keySet())if(existing.startsWith("position:")){if(++count>=256&&!existing.equals(key))edit.remove(existing);}edit.putLong(key,position).apply();}
    static JSONArray array(JSONObject object,String name){if(object==null)return new JSONArray();JSONArray a=object.optJSONArray(name);return a==null?new JSONArray():a;}
    void close(){closed=true;for(Call call:pending)call.cancel();pending.clear();transportFailures.clear();}
    static final class ApiException extends BlDiagnostics.Failure{final int code;ApiException(int code,String message){this(code,message,null,"");}ApiException(int code,String message,Request request,String trace){super("接口业务",(code==-101?"请先登录":message)+" ("+code+")",request,request==null?0:200,code,trace,"application/json",null);this.code=code;}}
}
