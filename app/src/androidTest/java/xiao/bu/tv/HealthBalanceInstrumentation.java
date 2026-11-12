package xiao.bu.tv;

import android.app.*;import android.content.*;import android.media.AudioManager;import android.os.*;
import java.io.*;import java.net.*;import java.util.*;import java.util.concurrent.*;import org.json.*;
import tv.danmaku.ijk.media.player.*;

/** Real player/audio output and advisory probes in a separate application ID. */
public final class HealthBalanceInstrumentation extends WebTaskNavigationInstrumentation {
    private Bundle args;private ServerSocket server;private final ExecutorService sockets=Executors.newFixedThreadPool(4);
    private final Map<String,byte[]> files=new HashMap<>();private final JSONArray rows=new JSONArray();
    private volatile boolean repaired,networkClosed;private ServerSocket rtspEntry,rtspBridge;private ChannelHealth health;private JSONObject report=new JSONObject();
    private final long fixtureStartedAt=SystemClock.elapsedRealtime();
    private final java.util.concurrent.atomic.AtomicInteger warmPages=new java.util.concurrent.atomic.AtomicInteger(),protectedReads=new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.ConcurrentHashMap<String,JSONObject> profileReports=new java.util.concurrent.ConcurrentHashMap<>();private volatile String profileAgent="",profileLogoutNames="";private volatile boolean profileRootCookies=true,profilePrivatePath;
    private volatile int warmToken=1;private volatile boolean resourceScriptsExecuted=true;
    private final java.util.concurrent.ConcurrentHashMap<String,java.util.concurrent.atomic.AtomicInteger> resourceRequests=new java.util.concurrent.ConcurrentHashMap<>();
    private int resourceCount(String path){java.util.concurrent.atomic.AtomicInteger value=resourceRequests.get(path);return value==null?0:value.get();}
    interface Condition {boolean get()throws Exception;}
    void await(Condition c,long ms,String why)throws Exception{long end=SystemClock.elapsedRealtime()+ms;while(SystemClock.elapsedRealtime()<end){if(c.get())return;SystemClock.sleep(50);}throw new AssertionError(why);}
    static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    @Override public void onCreate(Bundle value){args=value;start();}
    @Override public void callActivityOnCreate(Activity target,Bundle saved){super.callActivityOnCreate(target,saved);if(target instanceof MainActivity)try{((java.util.concurrent.atomic.AtomicInteger)field(target,"catalogLoadGeneration")).incrementAndGet();}catch(Exception e){throw new RuntimeException(e);}}
    private static byte[] read(InputStream input)throws IOException{try(InputStream in=input;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))>0)out.write(b,0,n);return out.toByteArray();}}
    private String manifest(boolean aes,boolean missing){StringBuilder s=new StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n");if(aes)s.append("#EXT-X-KEY:METHOD=AES-128,URI=\"/key\",IV=0x00000000000000000000000000000000\n");for(int i=0;i<180;i++){if(i>0&&i%6==0)s.append("#EXT-X-DISCONTINUITY\n");s.append("#EXTINF:2,\n/").append(missing?"missing.ts":(aes?"aes/":"")+"seg"+(i%6)+".ts").append('\n');}return s.append("#EXT-X-ENDLIST\n").toString();}
    private String liveFixture(){
        long end=Math.min(450,1+Math.max(0,(SystemClock.elapsedRealtime()-fixtureStartedAt)/2000)),first=Math.max(0,end-6);
        StringBuilder text=new StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:").append(first).append('\n');
        for(long i=first;i<end;i++)text.append("#EXTINF:2,\n/continuous/part").append(String.format(Locale.US,"%03d",i)).append(".ts\n");return text.toString();
    }
    private void serve(Socket socket){try(Socket client=socket){client.setSoTimeout(4000);BufferedReader in=new BufferedReader(new InputStreamReader(client.getInputStream(),"UTF-8"));String first=in.readLine();if(first==null)return;String[] request=first.split(" ");String path=new URI(request[1]).getPath(),line;Map<String,String> headers=new HashMap<>();while((line=in.readLine())!=null&&!line.isEmpty()){int at=line.indexOf(':');if(at>0)headers.put(line.substring(0,at).toLowerCase(Locale.US),line.substring(at+1).trim());}
        int status=200;String type="application/vnd.apple.mpegurl",extra="";byte[] body;
        java.util.concurrent.atomic.AtomicInteger counted=resourceRequests.get(path);if(counted==null){resourceRequests.putIfAbsent(path,new java.util.concurrent.atomic.AtomicInteger());counted=resourceRequests.get(path);}int requestNumber=counted.incrementAndGet();if(path.equals("/resource-report")&&!"loaded=1".equals(new URI(request[1]).getRawQuery()))resourceScriptsExecuted=false;
        if(path.equals("/userscript-page.html")){
            type="text/html";extra="Cache-Control: no-store\r\n";String stage=new URI(request[1]).getRawQuery();
            body=("<!doctype html><video autoplay muted src='/live.m3u8'></video><script>var state={stage:'"+stage+"',value:window.__testDocStart===undefined?null:window.__testDocStart,applied:Object.keys(window.__ntvUserScripts||{}).length,siteHooks:typeof window.__selectedMgtvSource,ua:navigator.userAgent};var x=new XMLHttpRequest();x.open('GET','/blank-report?state='+encodeURIComponent(JSON.stringify(state)),true);x.send()</script>").getBytes("UTF-8");
        }
        else if(path.equals("/blank-page.html")){
            type="text/html";extra="Cache-Control: no-store\r\n";
            String stage=new URI(request[1]).getRawQuery();
            body=("<!doctype html><html><body><video id='v' autoplay muted></video><script>"+
                "var state={stage:'"+stage+"',loads:0,errors:0,sizes:[],complete:[]},urls=['/blank-poster.png','/blank-banner.jpg','/blank-asset.svg'];"+
                "function report(){var x=new XMLHttpRequest();x.open('GET','/blank-report?state='+encodeURIComponent(JSON.stringify(state)),true);x.send();if(state.loads===4&&state.errors===0){var v=document.getElementById('v');v.src='/live.m3u8';v.play()}}"+
                "function loaded(img){state.loads++;state.sizes.push([img.naturalWidth,img.naturalHeight]);state.complete.push(img.complete);if(state.loads+state.errors===4)report()}function failed(){state.errors++;report()}"+
                "for(var i=0;i<urls.length;i++){var img=new Image();img.onload=function(){loaded(this)};img.onerror=failed;document.body.appendChild(img);img.src=urls[i]}"+
                "</script><img src='/blank-initial.png' onload='loaded(this)' onerror='failed()'></body></html>").getBytes("UTF-8");
        }
        else if(path.equals("/blank-report")){type="application/json";extra="Cache-Control: no-store\r\n";String query=new URI(request[1]).getRawQuery();JSONObject state=new JSONObject(URLDecoder.decode(query.substring("state=".length()),"UTF-8"));profileReports.put(state.getString("stage"),state);body="{}".getBytes("UTF-8");}
        else if(path.equals("/blank-poster.png")||path.equals("/blank-banner.jpg")||path.equals("/blank-asset.svg")||path.equals("/blank-initial.png")){
            type="image/png";extra="Cache-Control: public, max-age=600\r\n";android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(2,3,android.graphics.Bitmap.Config.ARGB_8888);bitmap.eraseColor(0xff00ff00);ByteArrayOutputStream png=new ByteArrayOutputStream();bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,png);body=png.toByteArray();bitmap.recycle();
        }
        else if(path.startsWith("/profile/")&&path.endsWith(".html")){
            type="text/html";extra="Cache-Control: no-store\r\n";String stage=path.substring(path.lastIndexOf('/')+1).replace(".html","");String code="";
            if(stage.equals("lite-set")){profileAgent=headers.get("user-agent");extra+="Set-Cookie: sid=lite; Path=/; HttpOnly; Max-Age=3600\r\nSet-Cookie: privatepath=path; Path=/profile/private/; Max-Age=3600\r\nSet-Cookie: removed=old; Path=/; Max-Age=3600\r\n";code="localStorage.setItem('auth','lite-token');localStorage.setItem('delete-me','old');localStorage.setItem('special','quote\\\" \\\\ 中文');document.cookie='script=lite; Path=/; Max-Age=3600';";}
            if(stage.equals("system-check")){profileRootCookies&=String.valueOf(headers.get("cookie")).contains("sid=lite")&&!String.valueOf(headers.get("cookie")).contains("privatepath=")&&!String.valueOf(headers.get("cookie")).contains("secureonly=");extra+="Set-Cookie: sid=system; Path=/; HttpOnly; Max-Age=3600\r\nSet-Cookie: removed=; Max-Age=0; Path=/\r\nSet-Cookie: newsecret=system; Path=/; HttpOnly\r\n";}
            if(stage.equals("lite-check"))profileRootCookies&=String.valueOf(headers.get("cookie")).contains("sid=system")&&!String.valueOf(headers.get("cookie")).contains("removed=")&&!String.valueOf(headers.get("cookie")).contains("secureonly=");
            if(stage.equals("lite-logout")){extra+="Set-Cookie: sid=; Max-Age=0; Path=/; HttpOnly\r\nSet-Cookie: newsecret=; Max-Age=0; Path=/; HttpOnly\r\n";code="localStorage.clear();document.cookie='script=; Path=/; Max-Age=0';";}
            if(stage.equals("system-logout-check")){profileLogoutNames=String.valueOf(headers.get("cookie")).replaceAll("=[^;]*", "");profileRootCookies&=!String.valueOf(headers.get("cookie")).contains("sid=")&&!String.valueOf(headers.get("cookie")).contains("script=")&&!String.valueOf(headers.get("cookie")).contains("newsecret=");}
            String after=stage.equals("system-check")?"localStorage.setItem('auth','system-token');localStorage.removeItem('delete-me');localStorage.direct='direct-value';document.cookie='script=system; Path=/; Max-Age=3600';var q=new XMLHttpRequest();q.open('GET','/profile/private/report',true);q.send();":"";
            String js=code+"var state={stage:"+JSONObject.quote(stage)+",auth:localStorage.getItem('auth'),deleted:localStorage.getItem('delete-me'),special:localStorage.getItem('special'),direct:localStorage.getItem('direct'),keys:localStorage.length,ua:navigator.userAgent,visible:document.cookie};var x=new XMLHttpRequest();x.open('GET','/profile/report?state='+encodeURIComponent(JSON.stringify(state)),true);x.send();"+after;
            body=("<!doctype html><html><head></head><body>Profile fixture<script>"+js+"</script></body></html>").getBytes("UTF-8");
        }
        else if(path.equals("/profile/report")){type="application/json";extra="Cache-Control: no-store\r\n";String query=new URI(request[1]).getRawQuery();JSONObject state=new JSONObject(URLDecoder.decode(query.substring("state=".length()),"UTF-8"));profileReports.put(state.getString("stage"),state);body="{}".getBytes("UTF-8");}
        else if(path.equals("/profile/private/report")){profilePrivatePath=String.valueOf(headers.get("cookie")).contains("privatepath=path");type="application/json";extra="Cache-Control: no-store\r\n";body="{}".getBytes("UTF-8");}
        else if(path.startsWith("/test-logo-")){
            if(path.contains("slow"))SystemClock.sleep(1800);
            if(path.contains("bad")){status=404;body=new byte[0];}
            else if(path.contains("oversize")){body=new byte[513*1024];}
            else {type="image/png";android.graphics.Bitmap b=android.graphics.Bitmap.createBitmap(256,128,android.graphics.Bitmap.Config.ARGB_8888);b.eraseColor(path.contains("red")?0xffff0000:0xff00ff00);ByteArrayOutputStream png=new ByteArrayOutputStream();b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,png);body=png.toByteArray();b.recycle();}
        }
        else if(path.equals("/timeout.m3u8")){SystemClock.sleep(4500);return;}
        else if(path.equals("/neighbor-cache.html")||path.equals("/neighbor-static.html")||path.equals("/neighbor-nostore.html")){
            type="text/html";extra=path.equals("/neighbor-nostore.html")?"Cache-Control: no-store\r\n":"Cache-Control: public, max-age=600\r\n";
            body=(path.equals("/neighbor-cache.html")?"<!doctype html><video id='v' autoplay muted></video><script src='/neighbor-shared.js'></script>":"<!doctype html><video autoplay muted src='/live.m3u8'></video>").getBytes("UTF-8");
        }
        else if(path.equals("/neighbor-shared.js")){
            type="application/javascript";extra="Cache-Control: public, max-age=600\r\n";
            body="document.cookie='probe=changed; Path=/';localStorage.setItem('probe','changed');var v=document.getElementById('v');v.src='/live.m3u8';v.play();".getBytes("UTF-8");
        }
        else if(path.equals("/resource-page.html")){
            type="text/html";extra="Cache-Control: no-store\r\n";
            body=("<!doctype html><html><head><link rel='stylesheet' href='/shared.css'><script src='/shared.js'></script>"+(requestNumber>1?"<script src='/system-shared.js'></script>":"")+"</head><body><img src='/shared.png'><script>var x=new XMLHttpRequest();x.open('GET','/resource-report?loaded='+window.sharedLoaded,true);x.send()</script></body></html>").getBytes("UTF-8");
        }
        else if(path.startsWith("/shared")||path.equals("/system-shared.js")||path.equals("/resource-report")||path.endsWith("resource.js")||path.equals("/revalidate.js")||path.equals("/slow-shared.js")){
            extra="Cache-Control: public, max-age=600\r\nETag: \"shared-v1\"\r\n";type="application/javascript";
            if(path.equals("/slow-shared.js"))SystemClock.sleep(600);if(path.equals("/timeout-resource.js"))SystemClock.sleep(1500);
            if(path.equals("/revalidate.js")){if("\"shared-v1\"".equals(headers.get("if-none-match")))status=304;else extra="Cache-Control: public, max-age=0\r\nETag: \"shared-v1\"\r\n";}
            if(path.equals("/resource-report"))extra="Cache-Control: no-store\r\n";
            if(path.equals("/nostore-resource.js"))extra="Cache-Control: no-store\r\n";
            if(path.equals("/private-resource.js"))extra="Cache-Control: private, max-age=600\r\n";
            if(path.equals("/cookievary-resource.js"))extra+="Vary: Cookie\r\n";
            if(path.equals("/shared.css")){type="text/css";body="body{background:#ffffff;color:#202020}".getBytes("UTF-8");}
            else if(path.equals("/shared.png")||path.equals("/resource-report")){type="image/png";body=android.util.Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4////fwAJ+wP9KobjigAAAABJRU5ErkJggg==",android.util.Base64.DEFAULT);}
            else body=(path.equals("/signed-resource.js")?"token:"+new URI(request[1]).getRawQuery():path.equals("/vary-resource.js")?"variant:"+headers.get("cookie")+"|"+headers.get("user-agent"):"window.sharedLoaded=1;\n/*"+path+" "+(path.equals("/nostore-resource.js")?requestNumber:1)+"*/").getBytes("UTF-8");
        }
        else if(path.equals("/catalog.json")){type="application/json";body="{\"protocol\":5,\"sites\":[]}".getBytes("UTF-8");}
        else if(path.startsWith("/warm-")&&path.endsWith(".html")){
            warmPages.incrementAndGet();SystemClock.sleep(800);type="text/html";extra="Set-Cookie: fixture=prepared; Path=/\r\n";
            String content=path.contains("idb")?"indexedDB.open('preparse-forbidden',1)":path.contains("mse")?"var m=new MediaSource();document.getElementById('v').src=URL.createObjectURL(m)":"localStorage.setItem('fixture','prepared');setTimeout(function(){document.getElementById('v').src='/warm-protected.m3u8?token="+warmToken+"';document.getElementById('v').play()},700)";
            body=("<!doctype html><video id='v' autoplay muted></video><script>"+content+"</script>").getBytes("UTF-8");
        }
        else if(path.equals("/warm-protected.m3u8")){
            protectedReads.incrementAndGet();String query=new URI(request[1]).getRawQuery();
            if(!String.valueOf(headers.get("referer")).contains("/warm-")||!String.valueOf(headers.get("cookie")).contains("fixture=prepared")||!("token="+warmToken).equals(query)){status=403;body=new byte[0];}
            else body=manifest(false,false).getBytes("UTF-8");
        }
        else if(path.equals("/ku9/js/speed-fixture.js")){type="application/javascript";body=("function main(item){var flv=ku9.getQuery(item.url,'format')==='flv';return {url:'http://"+headers.get("host")+"'+(flv?'/fixture.flv':'/preflight-live-ku9.m3u8')};}").getBytes("UTF-8");}
        else if(path.startsWith("/logos/")){type="image/png";body=read(getContext().getAssets().open(path.substring(1)));}
        else if(path.startsWith("/preflight-live-")){body=liveFixture().getBytes("UTF-8");}
        else if(path.matches("/continuous/part[0-9]{3}\\.ts")){body=read(getContext().getAssets().open(path.substring(1)));type="video/mp2t";}
        else if(path.startsWith("/slowbad-")){SystemClock.sleep(500);status=404;body=new byte[0];}
        else if(path.equals("/missing.m3u8")&&!repaired||path.equals("/missing.ts")){status=404;body="missing".getBytes("UTF-8");}
        else if(path.equals("/gone.m3u8")){status=410;body=new byte[0];}
        else if(path.equals("/forbidden.m3u8")||path.equals("/protected.m3u8")&&(!String.valueOf(headers.get("referer")).contains("page.html")||!String.valueOf(headers.get("cookie")).contains("fixture=1"))){status=403;body="auth required".getBytes("UTF-8");}
        else if(path.equals("/server-error.m3u8")){status=503;body=new byte[0];}
        else if(path.equals("/page.html")){type="text/html";extra="Set-Cookie: fixture=1; Path=/\r\n";body="<!doctype html><video controls autoplay src='/protected.m3u8'></video>".getBytes("UTF-8");}
        else if(path.equals("/script.html")){type="text/html";body="<!doctype html><video id='v' autoplay muted></video><script>setTimeout(function(){var v=document.getElementById('v');v.src='/fixture.mp4';v.play()},250)</script>".getBytes("UTF-8");}
        else if(path.equals("/html.m3u8")){type="text/html";body="<!doctype html><html>please login</html>".getBytes("UTF-8");}
        else if(path.equals("/key")){body="0123456789abcdef".getBytes("US-ASCII");type="application/octet-stream";}
        else if(path.startsWith("/aes/")){byte[] clear=files.get(path.substring(4));javax.crypto.Cipher cipher=javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,new javax.crypto.spec.SecretKeySpec("0123456789abcdef".getBytes("US-ASCII"),"AES"),new javax.crypto.spec.IvParameterSpec(new byte[16]));body=cipher.doFinal(clear);type="video/mp2t";}
        else if(files.containsKey(path)){body=files.get(path);type=path.endsWith(".ts")?"video/mp2t":path.endsWith(".wav")?"audio/wav":path.endsWith(".mp3")?"audio/mpeg":path.endsWith(".flv")?"video/x-flv":"video/mp4";}
        else body=manifest(path.equals("/encrypted.m3u8"),path.equals("/expired-segment.m3u8")).getBytes("UTF-8");
        if(path.equals("/gzip-resource.js")){ByteArrayOutputStream compressed=new ByteArrayOutputStream();try(java.util.zip.GZIPOutputStream gzip=new java.util.zip.GZIPOutputStream(compressed)){gzip.write(body);}body=compressed.toByteArray();extra+="Content-Encoding: gzip\r\n";}
        if(path.equals("/cancel-resource.js")){body=new byte[2*1024*1024];Arrays.fill(body,(byte)'x');}
        int from=0,to=body.length-1;String range=headers.get("range");if(status==200&&range!=null&&range.startsWith("bytes=")){String[] part=range.substring(6).split("-",-1);from=Integer.parseInt(part[0]);if(part.length>1&&!part[1].isEmpty())to=Math.min(to,Integer.parseInt(part[1]));if(from>=body.length){status=416;from=0;to=-1;}else{status=206;extra+="Content-Range: bytes "+from+"-"+to+"/"+body.length+"\r\n";}}
        OutputStream out=client.getOutputStream();out.write(("HTTP/1.1 "+status+" Result\r\nContent-Type: "+type+"\r\nContent-Length: "+(Math.max(0,to-from+1)+(path.equals("/truncated-resource.js")?1024:0))+"\r\nAccept-Ranges: bytes\r\n"+extra+"Connection: close\r\n\r\n").getBytes("UTF-8"));if(!request[0].equals("HEAD")&&to>=from){if(path.equals("/cancel-resource.js")){for(int at=from;at<=to;at+=16384){out.write(body,at,Math.min(16384,to-at+1));out.flush();SystemClock.sleep(8);}}else out.write(body,from,to-from+1);}out.flush();
    }catch(Exception error){android.util.Log.e("HealthBalanceFixture","Request failed",error);}}
    private Channel channel(String name,String...urls){return new Channel(name,name,null,urls,null,null,null,null);}
    private int state(Channel c)throws Exception{Map<String,ChannelHealth.State> states=(Map)field(health,"cache");ChannelHealth.State s=states.get(ChannelHealth.key(c));return s==null?99:s.status;}
    private IMediaPlayer player()throws Exception{return (IMediaPlayer)field(activity,"player");}
    private boolean ready()throws Exception{return player()!=null&&(Boolean)field(activity,"prepared")&&player().isPlaying()&&((Boolean)field(activity,"videoRenderingStarted")||(Boolean)field(activity,"audioOnlyPlayback"));}
    private boolean readyWeb(Channel target)throws Exception{
        boolean[] yes={false};main(()->{WebSourceView web=(WebSourceView)field(activity,"webSourceView");JSONObject state=web.currentWebMediaState();
            yes[0]=call("currentChannel",new Class<?>[0])==target&&web.isPageVisible()&&state.optBoolean("playing")&&!state.optBoolean("mediaPaused",true)&&state.optInt("mediaReadyState")>=2&&state.optInt("videoWidth")>0&&state.optLong("mediaTimeMs")>0;});return yes[0];
    }
    private void start(Channel channel)throws Exception{main(()->{ChannelCatalog.GROUPS=new ChannelCatalog.Group[]{new ChannelCatalog.Group("实测",ChannelCatalog.SOURCE_CUSTOM,new Channel[]{channel})};set("currentGroupIndex",0);set("quickChannel",null);call("startChannel",new Class<?>[]{int.class},0);});await(()->ready()&&field(activity,"activePlayerChannel")==channel,30000,"No real playback: "+channel.name);}
    private JSONObject balance(Channel c,boolean delayed)throws Exception{long begin=SystemClock.elapsedRealtime();start(c);long ready=SystemClock.elapsedRealtime();IMediaPlayer seen=player();if(delayed){SystemClock.sleep(3000);check(!(Boolean)field(activity,"balanceApplied"),"Silent intro incorrectly set volume");}
        await(()->(Boolean)field(activity,"balanceApplied"),delayed?7000:3200,"Balance did not apply: "+c.name);long applied=SystemClock.elapsedRealtime();PlaybackVolumeBalance value=(PlaybackVolumeBalance)field(activity,"volumeBalance");check(player()==seen,"Player replaced for volume measurement");check(value.firstAudibleAt>=0&&applied-value.firstAudibleAt<=3000,"Audible balance exceeded three seconds");check(value.samples>=3,"No real PCM samples");JSONObject row=new JSONObject().put("name",c.name).put("gain",value.gain).put("firstReadyMs",ready-begin).put("applyFromReadyMs",applied-ready).put("applyFromAudibleMs",applied-value.firstAudibleAt).put("decodedDb",value.level).put("samples",value.samples);rows.put(row);return row;}
    private void save(){try(FileOutputStream out=new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null),"health-balance-result.json"))){report.put("volume",rows);out.write(report.toString(2).getBytes("UTF-8"));}catch(Exception ignored){}}
    private void capturePreflight(String name)throws Exception{
        android.graphics.Bitmap[] image={null};
        if(Build.VERSION.SDK_INT>=18)image[0]=getUiAutomation().takeScreenshot();
        else main(()->{android.view.View root=activity.getWindow().getDecorView();image[0]=android.graphics.Bitmap.createBitmap(root.getWidth(),root.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);root.draw(new android.graphics.Canvas(image[0]));});
        if(image[0]!=null)try(FileOutputStream out=new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null),name+".png"))){image[0].compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}finally{image[0].recycle();}
    }
    private JSONObject switched(Channel channel,String phase,long began,JSONArray trials)throws Exception{
        await(()->ready()&&field(activity,"activePlayerChannel")==channel,45000,"Switch failed: "+phase+" "+channel.name);
        long first=SystemClock.elapsedRealtime()-began;IMediaPlayer seen=player();long position=seen.getCurrentPosition();SystemClock.sleep(1250);
        check(player()==seen&&seen.isPlaying(),"Switch playback replaced/stopped: "+phase);
        long advanced=seen.getCurrentPosition()-position;check(advanced>450,"Playback clock stalled: "+phase+" "+advanced);
        check((Boolean)field(activity,"audioOnlyPlayback")||seen.getVideoWidth()>0&&seen.getVideoHeight()>0,"Video dimensions missing: "+phase);
        JSONObject row=new JSONObject().put("phase",phase).put("channel",channel.name).put("firstReadyMs",first).put("advancedMs",advanced).put("backend",seen.getClass().getSimpleName()).put("source",field(activity,"currentSourceIndex"));trials.put(row);save();return row;
    }
    private void assertPreflightTheme(String id,JSONArray outcomes)throws Exception{
        main(()->call("closeChannelList"));main(()->call("showChannelBar",new Class<?>[]{String.class,String.class},((Channel)call("currentChannel",new Class<?>[0])).name,"上线前主题检查"));waitForIdleSync();
        Object chrome=field(activity,"tvUiChrome"),card=field(activity,"channelBar");
        if(!id.equals("builtin")){
            check(chrome!=null&&card!=field(chrome,"originalCard"),"Channel card mixed with classic: "+id);
            check(field(card,"this$0")==TvUiPlugin.provider(),"Channel card uses another theme: "+id);
        }
        capturePreflight("preflight-"+id+"-card");main(()->call("openChannelList"));waitForIdleSync();
        chrome=field(activity,"tvUiChrome");
        if(!id.equals("builtin"))check(field(field(activity,"channelListPanel"),"this$0")==TvUiPlugin.provider(),"Channel panel uses another theme: "+id);
        check(TvUiPlugin.failure().isEmpty(),"Renderer rejected: "+TvUiPlugin.failure());
        check(activity.findViewById(R.id.channel_bar)==field(activity,"channelBar")&&activity.findViewById(R.id.channel_list_panel)==field(activity,"channelListPanel"),"Duplicate or detached themed overlays");
        capturePreflight("preflight-"+id+"-panel");main(()->call("closeChannelList"));
        outcomes.put(new JSONObject().put("theme",id).put("card",true).put("panel",true).put("matchedRenderer",true));save();
    }
    private void preflight(String base)throws Exception{
        AudioManager audio=(AudioManager)getTargetContext().getSystemService(Context.AUDIO_SERVICE);audio.setStreamVolume(AudioManager.STREAM_MUSIC,0,0);
        settings(new JSONObject().put("playerEngine",args.getString("engine",Build.VERSION.SDK_INT<=15?"system":"ijk")).put("continuousTsEnabled",Boolean.parseBoolean(args.getString("continuous","false"))).put("autoSwitchSourceSeconds",0).put("playerVolumeBalance",false).put("neighborProbeEnabled",false));
        Channel[] channels={new Channel("1","HLS一台",null,base+"/preflight-live-1.m3u8",null,null),new Channel("12","MP4视频",null,base+"/fixture.mp4",null,null),new Channel("1234","备用线路1234",null,new String[]{base+"/missing.m3u8",base+"/preflight-live-backup.m3u8"},null,null,null,null),new Channel("1000","跨组HLS",null,base+"/preflight-live-2.m3u8",null,null),new Channel("2000","MP3广播",null,base+"/fixture.mp3",null,null),new Channel("9999","长频道名称中文ABC1234567890测试",null,base+"/preflight-live-3.m3u8",null,null)};
        main(()->{ChannelCatalog.GROUPS=new ChannelCatalog.Group[]{new ChannelCatalog.Group("第一组",ChannelCatalog.SOURCE_CUSTOM,Arrays.copyOfRange(channels,0,3)),new ChannelCatalog.Group("第二组",ChannelCatalog.SOURCE_CUSTOM,Arrays.copyOfRange(channels,3,6))};set("currentGroupIndex",0);set("currentChannelIndex",0);});
        JSONArray trials=new JSONArray(),themes=new JSONArray();report.put("switchTrials",trials).put("themes",themes).put("engine",args.getString("engine",Build.VERSION.SDK_INT<=15?"system":"ijk"));
        for(int i=0;i<channels.length;i++){
            String id=TvUiPlugin.IDS[i];TvUiPlugin.select(getTargetContext(),id);waitForIdleSync();final int target=i;long began=SystemClock.elapsedRealtime();
            main(()->{set("currentGroupIndex",target/3);call("switchChannel",new Class<?>[]{int.class},target%3);});
            switched(channels[i],"theme-"+id,began,trials);assertPreflightTheme(id,themes);
        }
        long began=SystemClock.elapsedRealtime();main(()->call("switchRelative",new Class<?>[]{int.class},1));switched(channels[0],"cross-group-wrap",began,trials);
        int request=(Integer)field(activity,"playRequestId");began=SystemClock.elapsedRealtime();main(()->{for(int i=0;i<5;i++)call("switchRelative",new Class<?>[]{int.class},1);});
        switched(channels[5],"rapid-five-latest-wins",began,trials);check((Integer)field(activity,"playRequestId")==request+1,"Rapid keys launched redundant playback requests");
        ChannelCatalog.Group[] smallGroups=ChannelCatalog.GROUPS;
        Channel fourDigits=new Channel("1234","第1234台",null,base+"/preflight-live-four.m3u8",null,null);Channel[] large=new Channel[1234];Arrays.fill(large,channels[0]);large[1233]=fourDigits;
        main(()->{ChannelCatalog.GROUPS=new ChannelCatalog.Group[]{new ChannelCatalog.Group("四位选台",ChannelCatalog.SOURCE_CUSTOM,large)};set("currentGroupIndex",0);set("currentChannelIndex",0);for(int digit:new int[]{1,2,3,4})call("enterNumericChannel",new Class<?>[]{int.class},digit);check("1234".equals(((android.widget.TextView)field(activity,"numericChannelOverlay")).getText().toString()),"Four-digit overlay truncated");});
        waitForIdleSync();main(()->{android.widget.TextView number=(android.widget.TextView)field(activity,"numericChannelOverlay");check(number.getWidth()-number.getPaddingLeft()-number.getPaddingRight()+1>=number.getPaint().measureText("1234"),"Four-digit overlay clipped");});capturePreflight("preflight-number-1234");
        began=SystemClock.elapsedRealtime();main(()->call("commitNumericChannel"));switched(fourDigits,"numeric-1234",began,trials);
        began=SystemClock.elapsedRealtime();main(()->{ChannelCatalog.GROUPS=smallGroups;set("currentGroupIndex",0);set("currentChannelIndex",0);call("switchChannel",new Class<?>[]{int.class},0);});switched(channels[0],"before-swipes",began,trials);
        for(int i=0;i<4;i++){
            int direction=i%2==0?1:-1;began=SystemClock.elapsedRealtime();main(()->call("animateRelativeChannelSwitch",new Class<?>[]{int.class,float.class,float.class},direction,0f,direction>0?-1f:1f));
            switched(channels[i%2==0?1:0],"swipe-"+i,began,trials);check(!(Boolean)field(activity,"channelSwitchAnimating"),"Swipe animation did not finish");
        }
        int cycles=Integer.parseInt(args.getString("cycles","12"));
        for(int i=0;i<cycles;i++){int target=i%2==0?3:0;began=SystemClock.elapsedRealtime();main(()->{set("currentGroupIndex",target/3);call("switchChannel",new Class<?>[]{int.class},target%3);});switched(channels[target],"stress-"+i,began,trials);}
        long duration=Long.parseLong(args.getString("holdMs","60000")),start=SystemClock.elapsedRealtime(),previous=player().getCurrentPosition(),advanced=0;IMediaPlayer stable=player();int samples=0;
        main(()->{call("closeChannelList");((android.view.View)field(activity,"channelBar")).setVisibility(android.view.View.GONE);});
        while(SystemClock.elapsedRealtime()-start<duration){SystemClock.sleep(1000);check(player()==stable&&stable.isPlaying(),"Stability player stopped/restarted");long now=stable.getCurrentPosition();advanced+=Math.max(0,now-previous);previous=now;samples++;report.put("stableElapsedMs",SystemClock.elapsedRealtime()-start).put("stableAdvancedMs",advanced).put("stableSamples",samples).put("stableBackend",stable.getClass().getSimpleName());save();}
        check(advanced>=duration*.75,"Stability playback clock advanced too slowly");report.put("stablePassed",true).put("fourDigitInput",true).put("rapidKeysCoalesced",true).put("swipePassed",true).put("switchCount",trials.length());save();
    }
    private void volumeChecks(String base)throws Exception{
        settings(new JSONObject().put("playerVolumeBalance",true));
        AudioManager audio=(AudioManager)getTargetContext().getSystemService(Context.AUDIO_SERVICE);
        int maximum=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        audio.setStreamVolume(AudioManager.STREAM_MUSIC,Math.min(3,maximum),0);
        main(()->set("playerVolumeTarget",-30d));
        JSONObject loud=balance(channel("大声MP3",base+"/loud.mp3"),false);
        check(loud.getDouble("gain")<.8,"Loud source was not attenuated");
        PlaybackVolumeBalance measured=(PlaybackVolumeBalance)field(activity,"volumeBalance");
        int samples=measured.samples;float gain=measured.gain;
        check(!(Boolean)field(activity,"balanceMeasuring"),"PCM measurement did not stop after adjustment");
        long begin=SystemClock.elapsedRealtime(),cpu=android.os.Process.getElapsedCpuTime();
        SystemClock.sleep(3200);
        check(measured.samples==samples&&measured.gain==gain,"Stable channel was adjusted twice");
        report.put("oncePerChannel",true).put("steadyCpuMs",android.os.Process.getElapsedCpuTime()-cpu).put("steadyWallMs",SystemClock.elapsedRealtime()-begin).put("pssKb",Debug.getPss());
        double target=(Double)field(activity,"playerVolumeTarget");
        audio.setStreamVolume(AudioManager.STREAM_MUSIC,Math.min(4,maximum),0);
        await(()->Math.abs((Double)field(activity,"playerVolumeTarget")-target)>.1,3500,"Manual adjustment did not update threshold");
        double learned=(Double)field(activity,"playerVolumeTarget");
        check(measured.gain==gain,"Manual change triggered automatic gain again");
        check(Math.abs(getTargetContext().getSharedPreferences(MainActivity.PREFERENCES,0).getFloat("player_volume_target",99)-(float)learned)<.001,"Learned threshold was not persisted");
        report.put("manualVolumeLearns",true).put("learnedTargetDb",learned);
        JSONObject remembered=balance(channel("记忆偏好HLS",base+"/remembered-volume.m3u8"),false);
        double output=remembered.getDouble("decodedDb")+20*Math.log10(remembered.getDouble("gain"))+PlaybackVolumeBalance.systemDb(audio.getStreamVolume(AudioManager.STREAM_MUSIC),maximum);
        check(Math.abs(output-learned)<.5,"Next channel did not use learned target");report.put("nextChannelUsesLearnedTarget",true).put("nextChannelEffectiveDb",output);
        main(()->set("playerVolumeTarget",-30d));
        JSONObject quiet=balance(channel("轻声MP3",base+"/quiet.mp3"),false);check(quiet.getDouble("gain")==1,"Quiet source was amplified");
        balance(channel("静音四秒后出声",base+"/delayed.mp3"),true);
        start(channel("一直静音",base+"/silent.mp3"));SystemClock.sleep(4200);check(!(Boolean)field(activity,"balanceApplied"),"All-silent track was adjusted");report.put("silenceDoesNotAdjust",true);
        balance(channel("MP3音频",base+"/fixture.mp3"),false);
        balance(channel("HLS音视频",base+"/live.m3u8"),false);
        audio.setStreamVolume(AudioManager.STREAM_MUSIC,Math.min(3,maximum),0);
        start(channel("手动优先",base+"/loud.mp3?manual=early"));
        audio.setStreamVolume(AudioManager.STREAM_MUSIC,Math.min(4,maximum),0);
        await(()->(Boolean)field(activity,"balanceApplied")&&!(Boolean)field(activity,"balanceManualPending"),3500,"Early manual preference was not learned");
        check(((PlaybackVolumeBalance)field(activity,"volumeBalance")).gain==1,"Auto volume overrode early manual change");report.put("earlyManualWins",true);
        settings(new JSONObject().put("playerVolumeBalance",false));
        check(((PlaybackVolumeBalance)field(activity,"volumeBalance")).gain==1&&!(Boolean)field(activity,"balanceMeasuring"),"Disable did not restore unity gain/stop measurement");report.put("disableRestoresGain",true);save();
    }
    private void cancelScans(String base,Channel current)throws Exception{
        start(current);SystemClock.sleep(5500);IMediaPlayer foreground=player();
        Channel[] channels=new Channel[25];channels[0]=current;
        for(int i=1;i<channels.length;i++)channels[i]=channel("取消测试"+i,base+"/slowbad-cancel-"+i+".m3u8");
        main(()->{ChannelCatalog.GROUPS=new ChannelCatalog.Group[]{new ChannelCatalog.Group("取消探测",ChannelCatalog.SOURCE_CUSTOM,channels)};set("currentGroupIndex",0);set("currentChannelIndex",0);});
        settings(new JSONObject().put("probeAllChannels",true));health.resume();
        await(()->health.completed>=2&&health.scanning,10000,"Active scan never advanced");
        JSONObject before=scanProgress();int completed=before.getInt("completed");
        long begin=SystemClock.elapsedRealtime();settings(new JSONObject().put("cancelChannelProbe",true).put("channelProbeScanId",before.getLong("scanId")));
        JSONObject cancelled=scanProgress();check("cancelled".equals(cancelled.getString("phase"))&&!cancelled.getBoolean("running")&&cancelled.getInt("completed")>=completed,"Cancellation did not retain partial results");
        int frozen=cancelled.getInt("completed");SystemClock.sleep(1000);check(scanProgress().getInt("completed")==frozen,"Cancelled callback advanced progress");
        check(player()==foreground&&foreground.isPlaying(),"Cancel interrupted foreground playback");
        report.put("activeCancelMs",SystemClock.elapsedRealtime()-begin-1000).put("cancelledProgress",cancelled).put("cancelPreservesForeground",true);
        main(()->player().pause());main(()->call("updateChannelHealth"));settings(new JSONObject().put("probeAllChannels",true));
        JSONObject waiting=scanProgress();check("waiting".equals(waiting.getString("phase")),"Restarted scan did not respect pause");
        settings(new JSONObject().put("cancelChannelProbe",true).put("channelProbeScanId",before.getLong("scanId")));check(scanProgress().getBoolean("running"),"Stale cancel stopped newer scan");
        settings(new JSONObject().put("cancelChannelProbe",true).put("channelProbeScanId",waiting.getLong("scanId")));check("cancelled".equals(scanProgress().getString("phase")),"Waiting scan did not cancel");
        main(()->player().start());report.put("waitingCancelAndStaleId",true);save();
    }
    private void neighbors(String base)throws Exception{
        Channel[] c=new Channel[8];for(int i=0;i<c.length;i++)c[i]=channel("邻台"+i,base+"/neighbor-"+i+".m3u8");
        start(c[3]);IMediaPlayer before=player();long position=before.getCurrentPosition();
        main(()->{ChannelCatalog.GROUPS=new ChannelCatalog.Group[]{new ChannelCatalog.Group("跨组A",ChannelCatalog.SOURCE_CUSTOM,Arrays.copyOfRange(c,0,4)),new ChannelCatalog.Group("跨组B",ChannelCatalog.SOURCE_CUSTOM,Arrays.copyOfRange(c,4,8))};set("currentGroupIndex",0);set("currentChannelIndex",3);});
        settings(new JSONObject().put("neighborProbeEnabled",true));
        SystemClock.sleep(1500);for(int i:new int[]{0,1,2,4,5,6})check(state(c[i])==99,"Neighbor probe ran before stable playback");
        await(()->{for(int i:new int[]{0,1,2,4,5,6})if(state(c[i])!=1)return false;return true;},45000,"Six cross-group neighbors not discovered");
        check(state(c[7])==99,"Neighbor probe exceeded three on either side");check(player()==before&&before.isPlaying()&&before.getCurrentPosition()>position+2000,"Neighbor probes disturbed playback");
        settings(new JSONObject().put("neighborProbeEnabled",false));report.put("stableThenSixCrossGroupNeighbors",true);
        CountDownLatch loaded=new CountDownLatch(1);ChannelHealth reload=new ChannelHealth(getTargetContext(),loaded::countDown);
        try{check(loaded.await(8,TimeUnit.SECONDS),"Cache reload timed out");check(reload.source(c[0],-1)==0&&reload.source(c[6],-1)==0,"Cache did not survive reload");reload.neighbors(ChannelCatalog.GROUPS,0,3);reload.resume();SystemClock.sleep(800);check(reload.completed==0,"Fresh neighbors were redundantly probed");}finally{reload.close();}
        report.put("sqliteReloadNoRepeat",true);save();
    }
    private void network(Channel current)throws Exception{
        rtspEntry=new ServerSocket(18554,4,InetAddress.getByName("127.0.0.1"));rtspBridge=new ServerSocket(18558,4,InetAddress.getByName("127.0.0.1"));
        new Thread(()->{while(!networkClosed)try{Socket client=rtspEntry.accept(),bridge=rtspBridge.accept();new Thread(()->copy(client,bridge),"rtsp-fixture-send").start();new Thread(()->copy(bridge,client),"rtsp-fixture-receive").start();}catch(Exception ignored){}},"rtsp-fixture-accept").start();
        new Thread(()->datagrams(false),"udp-fixture").start();new Thread(()->datagrams(true),"rtp-fixture").start();
        Channel[] c={channel("RTSP-TCP","rtsp://127.0.0.1:18554/fixture"),channel("RTP-TS","rtp://127.0.0.1:18560"),channel("UDP-TS","udp://127.0.0.1:18562"),ChannelCatalog.CCTV_CHANNELS[0],channel("公网MP3","https://icecast.radiofrance.fr/fip-midfi.mp3"),channel("虎牙11342386","webview://https://www.huya.com/11342386"),channel("虎牙30995188","webview://https://www.huya.com/30995188")};
        Channel[] catalogue=new Channel[c.length+1];catalogue[0]=current;System.arraycopy(c,0,catalogue,1,c.length);
        main(()->{ChannelCatalog.GROUPS=new ChannelCatalog.Group[]{new ChannelCatalog.Group("真实网络",ChannelCatalog.SOURCE_CUSTOM,catalogue)};set("currentGroupIndex",0);set("currentChannelIndex",0);});
        health.all(ChannelCatalog.GROUPS);await(()->!health.scanning,160000,"Network probe did not finish");JSONArray found=new JSONArray();for(Channel item:c)found.put(new JSONObject().put("name",item.name).put("status",state(item)));report.put("networkProbe",found);save();
        for(int i=0;i<3;i++)check(state(c[i])==1,"Network media not discovered: "+c[i].name);
        settings(new JSONObject().put("playerVolumeBalance",true));
        for(int i=0;i<5;i++)if(state(c[i])>0)balance(c[i],false);
        report.put("networkPassed",true);
    }
    private void copy(Socket input,Socket output){try(Socket a=input;Socket b=output){byte[] block=new byte[8192];int n;while((n=a.getInputStream().read(block))>0){b.getOutputStream().write(block,0,n);b.getOutputStream().flush();}}catch(Exception ignored){}}
    private void publicChannels()throws Exception{
        CjsPluginRuntime.ensureCatalog();CjsPluginRuntime.ensureInstalled("tv.cctv.com");CjsPluginRuntime.ensureInstalled("yangshipin.cn");
        settings(new JSONObject().put("playerVolumeBalance",true));
        Channel[] c={ChannelCatalog.CCTV_CHANNELS[0],ChannelCatalog.CCTV_CHANNELS[1],channel("央视频CCTV1","webview://https://www.yangshipin.cn/tv/home?pid=600001859")};
        for(Channel item:c){balance(item,false);await(()->health.source(item,-1)==0,10000,"Foreground success did not verify public channel");}
        report.put("publicChannelsPassed",true);
    }
    private void firstFrames(String base)throws Exception{
        TvUiPlugin.select(getTargetContext(),args.getString("theme","ui3"));waitForIdleSync();
        JSONArray timings=new JSONArray();ArrayList<Long> enabled=new ArrayList<>(),disabled=new ArrayList<>();
        for(int i=0;i<8;i++){
            boolean on=i%4==1||i%4==2;settings(new JSONObject().put("playerVolumeBalance",on));
            long begin=SystemClock.elapsedRealtime();start(channel("首帧"+i,base+"/frame-"+i+".m3u8"));long elapsed=SystemClock.elapsedRealtime()-begin;
            (on?enabled:disabled).add(elapsed);timings.put(new JSONObject().put("enabled",on).put("firstReadyMs",elapsed));
            check(!(Boolean)field(activity,"balanceApplied"),"Balance adjustment preceded first-frame observation");
        }
        Collections.sort(enabled);Collections.sort(disabled);report.put("firstFrameTrials",timings).put("enabledMedianMs",(enabled.get(1)+enabled.get(2))/2).put("disabledMedianMs",(disabled.get(1)+disabled.get(2))/2);save();
    }
    private void scanSpeed(String base,Channel current)throws Exception{
        SystemClock.sleep(6000);IMediaPlayer foreground=player();long position=foreground.getCurrentPosition();
        ArrayList<Channel> channels=new ArrayList<>();channels.add(current);
        for(int i=0;i<12;i++)channels.add(channel("慢失效"+i,base+"/slowbad-"+i+".m3u8"));
        for(int i=0;i<3;i++)channels.add(channel("可用"+i,base+"/scan-good-"+i+".m3u8"));
        channels.add(channels.get(2));channels.add(channels.get(3));
        main(()->{ChannelCatalog.GROUPS=new ChannelCatalog.Group[]{new ChannelCatalog.Group("扫描性能",ChannelCatalog.SOURCE_CUSTOM,channels.toArray(new Channel[0]))};set("currentGroupIndex",0);set("currentChannelIndex",0);});
        boolean progressApi;try{ChannelHealth.class.getDeclaredMethod("progress");progressApi=true;}catch(NoSuchMethodException oldVersion){progressApi=false;}
        long begin=SystemClock.elapsedRealtime();health.all(ChannelCatalog.GROUPS);health.resume();int last=-1,updates=0;JSONArray snapshots=new JSONArray();
        while(health.scanning&&SystemClock.elapsedRealtime()-begin<40000){JSONObject snapshot=progressApi?scanProgress():health.status();int done=snapshot.getInt("completed");check(done>=last&&done<=16,"Scan progress regressed or counted duplicates");if(done>last){snapshots.put(snapshot);updates++;last=done;}SystemClock.sleep(100);}
        check(!health.scanning&&health.completed==16&&health.total==16,"Scan did not finish unique channels");
        for(int i=1;i<=12;i++)check(state(channels.get(i))==-1,"Slow entry absence not confirmed");
        for(int i=13;i<16;i++)check(state(channels.get(i))==1,"Real HLS not discovered");
        report.put("scanMs",SystemClock.elapsedRealtime()-begin).put("progressUpdates",updates).put("progressSnapshots",snapshots).put("finalProgress",health.status());
        check(player()==foreground&&foreground.isPlaying()&&foreground.getCurrentPosition()>position+1000,"Scan interrupted foreground playback");
        report.put("foregroundStayedPlaying",true);save();
        if(progressApi){
            JSONObject finished=scanProgress();check(!finished.getBoolean("running")&&finished.getInt("completed")==16&&"complete".equals(finished.getString("phase")),"HTTP progress did not finish at total");
            main(()->player().pause());await(()->!player().isPlaying(),1500,"Player did not pause");main(()->call("updateChannelHealth"));health.all(ChannelCatalog.GROUPS);JSONObject waiting=scanProgress();report.put("waitingProgress",waiting);save();
            check(waiting.getInt("cached")==4&&waiting.getInt("completed")==4&&"waiting".equals(waiting.getString("phase")),"Fresh good cache or waiting phase not reflected");
            SystemClock.sleep(700);check(scanProgress().getInt("completed")==4,"Paused scan continued work");
            main(()->player().start());health.resume();await(()->!health.scanning,30000,"Paused scan did not resume");
            check(scanProgress().getInt("scanBad")==12,"Repeated scan did not recheck failed entries");report.put("httpProgress",true).put("cacheReuseAndPauseResume",true);save();
        }
    }
    private JSONObject scanProgress()throws Exception{
        LocalControlServer control=(LocalControlServer)field(activity,"controlServer");
        HttpURLConnection connection=(HttpURLConnection)new URL("http://127.0.0.1:"+control.getPort()+"/api/channels/probe").openConnection();
        connection.setConnectTimeout(1500);connection.setReadTimeout(1500);
        try{check(connection.getResponseCode()==200,"Progress endpoint failed");JSONObject progress=new JSONObject(new String(read(connection.getInputStream()),"UTF-8"));check(!progress.has("settings"),"Progress fetched whole catalog settings");return progress;}finally{connection.disconnect();}
    }
    private void litePreparse(String base,Channel current)throws Exception{
        check(LiteWebPlayback.supported(),"Browser package required");
        CjsPluginRuntime.setManifestUrl(base+"/catalog.json");CjsPluginRuntime.ensureCatalog();
        settings(new JSONObject().put("litePreparseEnabled",false).put("webSourceEngine","lite"));
        Channel cold=channel("网页冷启动","webview://"+base+"/warm-cold.html");long begin=SystemClock.elapsedRealtime();start(cold);report.put("coldFirstFrameMs",SystemClock.elapsedRealtime()-begin);save();
        start(current);
        String origin=base;getTargetContext().getSharedPreferences("lite-storage",0).edit().putString(origin,"{\"fixture\":\"active\"}").commit();
        okhttp3.HttpUrl page=okhttp3.HttpUrl.parse(base);
        LiteCookies.PROFILE.saveFromResponse(page,Collections.singletonList(okhttp3.Cookie.parse(page,"fixture=active; Path=/")));
        Channel target=channel("网页预解析","webview://"+base+"/warm-target.html");Channel[] list={current,target};
        main(()->{ChannelCatalog.GROUPS=new ChannelCatalog.Group[]{new ChannelCatalog.Group("预解析",ChannelCatalog.SOURCE_CUSTOM,list)};set("currentGroupIndex",0);set("currentChannelIndex",0);});
        IMediaPlayer foreground=player();long position=foreground.getCurrentPosition();
        settings(new JSONObject().put("litePreparseEnabled",true));
        android.app.ActivityManager.MemoryInfo memory=new android.app.ActivityManager.MemoryInfo();((android.app.ActivityManager)activity.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(memory);
        report.put("availableBeforePreparseMiB",memory.availMem/1024/1024).put("lowMemory",memory.lowMemory).put("cores",ContinuousTsPolicy.physicalCores()).put("windowActive",field(activity,"windowContentActive")).put("preparseProfileEligible",callProfile(target.sourceUrl(0))!=null);save();
        await(()->field(activity,"liteWebPreloader")!=null&&((LiteWebPreloader)field(activity,"liteWebPreloader")).ready(),25000,"Automatic neighbor preparation did not become ready");
        LiteWebPreloader cache=(LiteWebPreloader)field(activity,"liteWebPreloader");LiteWebPlayback retained=(LiteWebPlayback)field(cache,"prepared");
        check(player()==foreground&&foreground.isPlaying()&&foreground.getCurrentPosition()>position+1000,"Preparse disturbed foreground output");
        check("active".equals(new JSONObject(getTargetContext().getSharedPreferences("lite-storage",0).getString(origin,"{}")).getString("fixture")),"Preparse changed active localStorage");
        check(LiteCookies.PROFILE.loadForRequest(page).toString().contains("fixture=active"),"Preparse rotated the active cookie");
        main(()->{set("healthPosition",player().getCurrentPosition()+1000L);call("updateChannelHealth");});
        check(cache.ready()&&field(cache,"prepared")==retained,"A transient playback-stat stall discarded the ready page");
        report.put("readySurvivesStatsStall",true);save();
        Object preparedEngine=field(retained,"engine");
        check((Boolean)field(preparedEngine,"parkedPreparse"),"Ready background engine did not reduce its timer frequency");
        long[] idleCpu=preparseCpu(preparedEngine);
        int hold=Math.max(0,Math.min(240,Integer.parseInt(args.getString("holdReadySeconds","0"))));
        long retainedAt=SystemClock.elapsedRealtime();JSONArray holdSamples=new JSONArray();report.put("retainedSamples",holdSamples);
        while(SystemClock.elapsedRealtime()-retainedAt<hold*1000L){
            SystemClock.sleep(1000);check(cache.ready()&&field(cache,"prepared")==retained,"Prepared page lost during five-minute lease");
            check(player()==foreground&&foreground.isPlaying(),"Background preparse interrupted the foreground player");
            if(holdSamples.length()==0||SystemClock.elapsedRealtime()-retainedAt>holdSamples.length()*15000L){
                holdSamples.put(new JSONObject().put("elapsedMs",SystemClock.elapsedRealtime()-retainedAt).put("positionMs",foreground.getCurrentPosition()).put("pssKiB",android.os.Debug.getPss()));save();
            }
        }
        long[] finishedCpu=preparseCpu(preparedEngine);long idleWall=finishedCpu[0]-idleCpu[0];
        report.put("readyRetainedMs",SystemClock.elapsedRealtime()-retainedAt);
        if(idleWall>=1000)report.put("preparedWorkerCpuMs",(finishedCpu[1]-idleCpu[1])/1000000.0).put("preparedWorkerCpuPercent",(finishedCpu[1]-idleCpu[1])/10000.0/idleWall);save();
        int reads=warmPages.get();begin=SystemClock.elapsedRealtime();main(()->call("switchChannel",new Class<?>[]{int.class},1));await(()->ready()&&field(activity,"activePlayerChannel")==target,30000,"Adopted page did not render video");
        report.put("warmFirstFrameMs",SystemClock.elapsedRealtime()-begin);
        check(field(activity,"liteWebPlayback")==retained,"Preparsed page/session was not adopted");check(warmPages.get()==reads,"Cache hit reloaded the document");check(protectedReads.get()>0,"Authenticated media was not requested");
        check(!(Boolean)field(preparedEngine,"parkedPreparse"),"Foreground adoption kept the background timer throttle");
        report.put("sameSessionAdopted",true).put("cookiesAndStorageIsolated",true).put("foregroundStayedPlaying",true);save();
        // A changed UA must not reuse the old prepared session.
        settings(new JSONObject().put("litePreparseEnabled",false));start(current);
        LiteWebPreloader.Request config=(LiteWebPreloader.Request)callProfile(target.sourceUrl(0));
        // Configuration helper is disabled while the global switch is off.
        check(config==null,"Disabled preparse still supplied a cache profile");
        settings(new JSONObject().put("litePreparseEnabled",true));
        Channel expired=channel("过期签名回退","webview://"+base+"/warm-expiring.html");
        main(()->{ChannelCatalog.GROUPS=new ChannelCatalog.Group[]{new ChannelCatalog.Group("过期",ChannelCatalog.SOURCE_CUSTOM,new Channel[]{current,expired})};set("currentGroupIndex",0);set("currentChannelIndex",0);set("litePreparseRequest",-1);});
        await(()->((LiteWebPreloader)field(activity,"liteWebPreloader")).ready(),25000,"Signed page did not prepare");
        reads=warmPages.get();warmToken++;main(()->call("switchChannel",new Class<?>[]{int.class},1));await(()->ready()&&field(activity,"activePlayerChannel")==expired,30000,"Expired authentication did not recover through fresh parsing");
        check(warmPages.get()>reads,"Expired prepared URL was not re-parsed");report.put("expiredAuthColdRetry",true);save();
        settings(new JSONObject().put("litePreparseEnabled",false));start(current);
        LiteWebPreloader isolated=new LiteWebPreloader(activity);
        try{
            LiteWebPreloader.Request idb=new LiteWebPreloader.Request(base+"/warm-idb.html","","",0,"test");main(()->isolated.prepare(idb));
            await(()->!isolated.occupied(),12000,"IndexedDB page retained a background session");check(!isolated.ready(),"IndexedDB page was reusable");
            LiteWebPreloader.Request mse=new LiteWebPreloader.Request(base+"/warm-mse.html","","",0,"test");main(()->isolated.prepare(mse));
            await(()->!isolated.occupied(),12000,"MSE page retained a background session");check(!isolated.ready(),"MSE page was reusable");
        }finally{main(isolated::close);}
        report.put("mseAndIndexedDbStayCold",true);
        check(!LiteWebPreloader.eligible(15,1,1024L*1024*1024,false)&&!LiteWebPreloader.eligible(19,4,100L*1024*1024,false)&&!LiteWebPreloader.eligible(25,4,1024L*1024*1024,true),"Legacy or low-memory policy changed");
    }
    private long[] preparseCpu(Object engine)throws Exception{
        android.os.Handler worker=(android.os.Handler)field(engine,"worker");
        java.util.concurrent.FutureTask<long[]> snapshot=new java.util.concurrent.FutureTask<>(()->new long[]{SystemClock.elapsedRealtime(),android.os.Debug.threadCpuTimeNanos()});
        check(worker.post(snapshot),"Prepared engine worker stopped");return snapshot.get(8,java.util.concurrent.TimeUnit.SECONDS);
    }
    private Object callProfile(String source)throws Exception{Object[] result={null};main(()->result[0]=call("litePreparseProfile",new Class<?>[]{String.class},source));return result[0];}
    private String resource(String url,String agent,String cookie)throws Exception{
        okhttp3.Request.Builder request=new okhttp3.Request.Builder().url(url).header("User-Agent",agent).header("Referer",url.substring(0,url.lastIndexOf('/'))+"/resource-page.html");if(cookie!=null)request.header("Cookie",cookie);
        try(okhttp3.Response response=WebResourceHub.newCall(NetworkClient.sharedClient(),request.build()).execute()){check(response.code()==200,"Shared resource failed");check(response.receivedResponseAtMillis()>0&&(response.networkResponse()!=null||response.cacheResponse()!=null),"Resource lost its origin/cache clock metadata");if(response.cacheResponse()!=null)check(response.cacheResponse().receivedResponseAtMillis()>0,"Cached clock metadata was lost");return response.body().string();}
    }
    private void sharedResources(String base)throws Exception{
        CjsPluginRuntime.setManifestUrl(base+"/catalog.json");CjsPluginRuntime.ensureCatalog();SharedWebResources.clear(activity);
        String agent=DesktopWebProfile.userAgent("windows",LiteEngine.defaultUserAgent(base),"128");
        LiteEngine engine=LiteEngine.withNetworkThreadMultiplier(activity,new LiteEngine.Listener(){public void frame(JSONObject frame){}public void message(String text){android.util.Log.i("SharedResourceTest",text);}public void navigate(String url){}public void media(String url,Map<String,String> headers){}},NetworkClient.sharedClient().dns(),0);
        try{engine.setHeadless(true);engine.userAgentOverride(agent);engine.load(base+"/resource-page.html");await(()->resourceCount("/resource-report")>=1,20000,"Lite page did not execute shared JS/image");SystemClock.sleep(400);}finally{engine.close();}
        report.put("liteResourceCounts",new JSONObject().put("js",resourceCount("/shared.js")).put("css",resourceCount("/shared.css")).put("png",resourceCount("/shared.png")));save();
        check(resourceCount("/shared.js")==1&&resourceCount("/shared.css")==1&&resourceCount("/shared.png")==1,"Lite page unexpectedly duplicated static downloads");
        settings(new JSONObject().put("litePreparseEnabled",false).put("webSourceEngine","system").put("webViewUserAgent","windows").put("webViewBrowserVersion","128"));
        Channel page=channel("共享资源网页","webview://"+base+"/resource-page.html");
        main(()->{ChannelCatalog.GROUPS=new ChannelCatalog.Group[]{new ChannelCatalog.Group("共享缓存",ChannelCatalog.SOURCE_CUSTOM,new Channel[]{page})};set("currentGroupIndex",0);set("quickChannel",null);call("startChannel",new Class<?>[]{int.class},0);});
        await(()->resourceCount("/resource-report")>=2,25000,"System WebView did not execute shared JS/image");SystemClock.sleep(400);
        report.put("webViewResourceCounts",new JSONObject().put("js",resourceCount("/shared.js")).put("css",resourceCount("/shared.css")).put("png",resourceCount("/shared.png")));save();
        check(resourceCount("/shared.js")==1&&resourceCount("/shared.css")==1&&resourceCount("/shared.png")==1,"System WebView did not reuse Lite resources");check(resourceScriptsExecuted,"A renderer did not execute the shared external script");
        main(()->call("stopCurrentChannelLoad"));
        check(resourceCount("/system-shared.js")==1,"System-only resource was not downloaded");
        LiteEngine reverse=LiteEngine.withNetworkThreadMultiplier(activity,new LiteEngine.Listener(){public void frame(JSONObject frame){}public void message(String text){android.util.Log.i("SharedReverseTest",text);}public void navigate(String url){}public void media(String url,Map<String,String> headers){}},NetworkClient.sharedClient().dns(),0);
        try{reverse.setHeadless(true);reverse.userAgentOverride(agent);reverse.load(base+"/resource-page.html");await(()->resourceCount("/resource-report")>=3,20000,"Lite did not execute system-cached resources; counts="+resourceRequests);SystemClock.sleep(200);}finally{reverse.close();}
        check(resourceScriptsExecuted&&resourceCount("/system-shared.js")==1&&resourceCount("/shared.js")==1,"Lite did not reuse resources downloaded by the system WebView");
        okhttp3.Call beforeStart=WebResourceHub.newCall(NetworkClient.sharedClient(),new okhttp3.Request.Builder().url(base+"/never-resource.js").build());beforeStart.cancel();boolean cancelledBeforeStart=false;try{beforeStart.execute();}catch(IOException expected){cancelledBeforeStart=true;}check(cancelledBeforeStart&&beforeStart.isExecuted()&&resourceCount("/never-resource.js")==0,"Pre-start cancellation reached the server");
        java.util.concurrent.CountDownLatch callbackDone=new java.util.concurrent.CountDownLatch(1);java.util.concurrent.atomic.AtomicInteger callbacks=new java.util.concurrent.atomic.AtomicInteger();
        okhttp3.Call asynchronous=WebResourceHub.newCall(NetworkClient.sharedClient(),new okhttp3.Request.Builder().url(base+"/async-resource.js").build());
        asynchronous.enqueue(new okhttp3.Callback(){public void onFailure(okhttp3.Call call,IOException error){callbacks.addAndGet(10);callbackDone.countDown();}public void onResponse(okhttp3.Call call,okhttp3.Response response)throws IOException{response.body().string();response.close();callbacks.incrementAndGet();callbackDone.countDown();throw new IOException("Callback fixture");}});
        check(asynchronous.isExecuted()&&callbackDone.await(10,TimeUnit.SECONDS),"Asynchronous resource did not finish");SystemClock.sleep(100);check(callbacks.get()==1,"Resource callback was delivered twice");
        okhttp3.Call bounded=WebResourceHub.newCall(NetworkClient.sharedClient(),new okhttp3.Request.Builder().url(base+"/timeout-resource.js").build());bounded.timeout().timeout(150,TimeUnit.MILLISECONDS);long boundedAt=SystemClock.elapsedRealtime();boolean timedOut=false;try(okhttp3.Response unexpected=bounded.execute()){unexpected.body().string();}catch(IOException expected){timedOut=true;}check(timedOut&&SystemClock.elapsedRealtime()-boundedAt<900&&resourceCount("/timeout-resource.js")==1,"Shared transport exceeded the call budget or retried after timeout");
        java.util.concurrent.ExecutorService concurrent=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{Future<String> a=concurrent.submit(()->resource(base+"/slow-shared.js",agent,null)),b=concurrent.submit(()->resource(base+"/slow-shared.js",agent,null));check(a.get(15,TimeUnit.SECONDS).equals(b.get(15,TimeUnit.SECONDS))&&resourceCount("/slow-shared.js")==1,"Concurrent identical downloads were not coalesced");}finally{concurrent.shutdownNow();}
        resource(base+"/revalidate.js",agent,null);resource(base+"/revalidate.js",agent,null);resource(base+"/revalidate.js",agent,null);check(resourceCount("/revalidate.js")==2,"304 revalidation was not reused");
        resource(base+"/nostore-resource.js",agent,null);resource(base+"/nostore-resource.js",agent,null);check(resourceCount("/nostore-resource.js")==2,"no-store resource was cached");
        String a=resource(base+"/vary-resource.js",agent,"account=a"),b=resource(base+"/vary-resource.js",agent,"account=b");check(a.contains("account=a")&&b.contains("account=b")&&resourceCount("/vary-resource.js")==2,"Cookie variants were confused");
        String variant=resource(base+"/vary-resource.js",agent+" changed","account=b");check(variant.contains(" changed")&&resourceCount("/vary-resource.js")==3,"UA variants were confused");
        check(!resource(base+"/signed-resource.js?token=a",agent,null).equals(resource(base+"/signed-resource.js?token=b",agent,null)),"Signed query identity was lost");
        check(resource(base+"/gzip-resource.js",agent,null).contains("gzip-resource.js")&&resource(base+"/gzip-resource.js",agent,null).contains("gzip-resource.js")&&resourceCount("/gzip-resource.js")==1,"Gzip cache replay failed");
        for(int i=0;i<2;i++){boolean failed=false;try{resource(base+"/truncated-resource.js",agent,null);}catch(IOException expected){failed=true;}check(failed,"Truncated body was silently accepted");}check(resourceCount("/truncated-resource.js")==2,"Truncated body was cached");
        okhttp3.Call cancelled=WebResourceHub.newCall(NetworkClient.sharedClient(),new okhttp3.Request.Builder().url(base+"/cancel-resource.js").header("User-Agent",agent).header("Referer",base+"/resource-page.html").build());
        try(okhttp3.Response partial=cancelled.execute()){check(partial.body().byteStream().read()!=-1,"Cancellation fixture was empty");cancelled.cancel();}SystemClock.sleep(200);
        check(resource(base+"/cancel-resource.js",agent,null).length()==2*1024*1024&&resourceCount("/cancel-resource.js")==2,"Cancelled download poisoned cache");
        if(Build.VERSION.SDK_INT<21)for(String path:new String[]{"/private-resource.js","/cookievary-resource.js"}){resource(base+path,agent,null);android.webkit.WebResourceResponse privateResponse=SharedWebResources.intercept(activity,base+path,base+"/resource-page.html",agent,null,false);if(privateResponse!=null){privateResponse.getData().close();throw new AssertionError("Legacy WebView reused a private/cookie-varying resource");}}
        check(!WebResourceHub.eligible(new okhttp3.Request.Builder().url(base+"/live.m3u8").build()),"Live media entered static cache");
        SharedWebResources.clear(activity);resource(base+"/shared.js",agent,null);check(resourceCount("/shared.js")==2,"Shared cache clear did not evict resources");
        report.put("webViewReuse",true).put("crossProcessWebViewReuse",Build.VERSION.SDK_INT<24).put("systemToLiteReuse",true).put("concurrentDownloadCoalescing",true).put("etag304",true).put("noStoreAndCookieIsolation",true).put("cacheClear",true).put("boundedCallTimeout",true).put("responseClockMetadata",true).put("gzipReplay",true).put("uaAndQueryIsolation",true).put("truncatedAndCancelledNotCached",true).put("legacyPrivateBypass",Build.VERSION.SDK_INT<21);
    }
    private void liteProfilePage(String url,String mode,String version)throws Exception{
        SharedWebProfile.beginLite(activity,url);String fallback=LiteEngine.defaultUserAgent(url);
        String ua=SharedWebProfile.userAgent(activity,url,mode,version,DesktopWebProfile.userAgent(mode,fallback,version),true);
        String stage=url.substring(url.lastIndexOf('/')+1).replace(".html","");
        LiteEngine engine=LiteEngine.withNetworkThreadMultiplier(activity,new LiteEngine.Listener(){public void frame(JSONObject frame){}public void message(String value){android.util.Log.i("ProfileFixture",value);}public void navigate(String url){}public void media(String url,Map<String,String> h){}},NetworkClient.sharedClient().dns(),0);
        try{engine.setHeadless(true);engine.userAgentOverride(ua);engine.load(url);await(()->profileReports.containsKey(stage),20000,"Lite profile fixture did not finish: "+stage+" counts="+resourceRequests);SystemClock.sleep(100);}finally{engine.close();}
    }
    private void systemProfilePage(String url)throws Exception{
        String stage=url.substring(url.lastIndexOf('/')+1).replace(".html","");Channel channel=channel("会话共享", "webview://"+url);
        main(()->{ChannelCatalog.GROUPS=new ChannelCatalog.Group[]{new ChannelCatalog.Group("会话共享",ChannelCatalog.SOURCE_CUSTOM,new Channel[]{channel})};set("currentGroupIndex",0);set("quickChannel",null);call("startChannel",new Class<?>[]{int.class},0);});
        await(()->profileReports.containsKey(stage),25000,"System profile fixture did not finish: "+stage);
        CountDownLatch done=new CountDownLatch(1);main(()->{WebSourceView view=(WebSourceView)field(activity,"webSourceView");if(!view.flushSharedProfile(done::countDown))done.countDown();});check(done.await(2,TimeUnit.SECONDS),"System profile flush hung");
    }
    private void sharedProfile(String base)throws Exception{
        CjsPluginRuntime.setManifestUrl(base+"/catalog.json");CjsPluginRuntime.ensureCatalog();
        settings(new JSONObject().put("webSourceEngine","system").put("webViewUserAgent","windows").put("webViewBrowserVersion","native").put("litePreparseEnabled",false));
        liteProfilePage(base+"/profile/lite-set.html","windows","native");
        okhttp3.HttpUrl secure=okhttp3.HttpUrl.parse(base.replace("http:","https:"));LiteCookies.PROFILE.saveFromResponse(secure,Collections.singletonList(okhttp3.Cookie.parse(secure,"secureonly=secret; Path=/; Secure; HttpOnly")));
        systemProfilePage(base+"/profile/system-check.html");JSONObject nativeState=profileReports.get("system-check");
        check("lite-token".equals(nativeState.optString("auth"))&&"old".equals(nativeState.optString("deleted")),"First system script did not receive Lite localStorage: "+nativeState);
        check(nativeState.optString("visible").contains("script=lite")&&!nativeState.optString("visible").contains("sid="),"System cookie/HttpOnly transfer failed");check(profileAgent.equals(nativeState.optString("ua")),"UA changed across engines using native version");
        await(()->profilePrivatePath,3000,"Cookie path was not retained");
        main(()->call("stopCurrentChannelLoad"));liteProfilePage(base+"/profile/lite-check.html","windows","native");JSONObject lite=profileReports.get("lite-check");
        check("system-token".equals(lite.optString("auth"))&&lite.isNull("deleted")&&"direct-value".equals(lite.optString("direct")),"System localStorage modifications/removals were not imported: "+lite);
        check(lite.optString("visible").contains("script=system")&&!lite.optString("visible").contains("sid=")&&!lite.optString("visible").contains("newsecret="),"HttpOnly was exposed to Lite JS");check(profileAgent.equals(lite.optString("ua")),"UA was not retained returning to Lite");check(profileRootCookies,"Cookie scope or authenticated HTTP request failed");
        liteProfilePage(base+"/profile/lite-logout.html","windows","native");systemProfilePage(base+"/profile/system-logout-check.html");JSONObject logout=profileReports.get("system-logout-check");check(logout.optInt("keys",-1)==0&&!logout.optString("visible").contains("script="),"Logout restored stale storage/cookies");check(profileRootCookies,"Logout cookies were resurrected: "+profileLogoutNames);
        Bundle openArgs=new Bundle();openArgs.putString("url",base);Bundle old=SharedWebProfile.rpc(activity,"open",openArgs);SharedWebProfile.beginLite(activity,base);
        Bundle stale=new Bundle();stale.putString("url",base);stale.putString("lease",old.getString("lease"));stale.putString("cookies","stale=bad");SharedWebProfile.pack(stale,"state",new JSONObject().put("storage",new JSONObject().put("stale","bad")).toString());check(!SharedWebProfile.rpc(activity,"capture",stale).getBoolean("ok"),"Retired WebView overwrote the foreground profile");
        String explicit=DesktopWebProfile.userAgent("windows",LiteEngine.defaultUserAgent(base),"128");check(explicit.equals(SharedWebProfile.userAgent(activity,base,"windows","128",explicit,true)),"Explicit UA change did not apply");check(explicit.equals(SharedWebProfile.userAgent(activity,base,"windows","128","different native version",false)),"Selected UA did not stay pinned");
        report.put("firstScriptStorageRestored",true).put("bidirectionalCookiesAndStorage",true).put("httpOnlyPathSecureScopes",true).put("logoutAndDeletion",true).put("nativeVersionUaPreserved",true).put("explicitUaChange",true).put("staleSessionRejected",true);
    }
    private void neighborCache(String base,Channel current)throws Exception{
        CjsPluginRuntime.setManifestUrl(base+"/catalog.json");CjsPluginRuntime.ensureCatalog();SharedWebResources.clear(activity);
        getTargetContext().getSharedPreferences(MainActivity.PREFERENCES,0).edit().putString("web_view_user_agent","windows").putString("web_view_browser_version","128").commit();
        LiteCookies.PROFILE.initialize(activity);okhttp3.HttpUrl origin=okhttp3.HttpUrl.parse(base);LiteCookies.PROFILE.saveFromResponse(origin,Collections.singletonList(okhttp3.Cookie.parse(origin,"probe=active; Path=/")));
        String storage=SharedWebProfile.origin(base);getTargetContext().getSharedPreferences("lite-storage",0).edit().putString(storage,"{\"probe\":\"active\"}").commit();
        String agent=DesktopWebProfile.userAgent("windows",LiteEngine.defaultUserAgent(base),"128");CountDownLatch ready=new CountDownLatch(1);
        LiteEngine prepared=LiteEngine.forPreparse(activity,new LiteEngine.Listener(){public void frame(JSONObject f){}public void navigate(String u){}public void message(String m){android.util.Log.i("NeighborCacheFixture",m);}public void media(String u,Map<String,String> h){ready.countDown();}},NetworkClient.sharedClient().dns(),0);
        try{prepared.setHeadless(true);prepared.userAgentOverride(agent);prepared.load(base+"/neighbor-cache.html");check(ready.await(15,TimeUnit.SECONDS),"Warm webpage did not discover media");}finally{prepared.close();}
        check(resourceCount("/neighbor-cache.html")==1&&resourceCount("/neighbor-shared.js")==1,"Initial preparation duplicated downloads");
        IMediaPlayer foreground=player();long position=foreground.getCurrentPosition();ChannelHealth original=health;ChannelHealth probe=new ChannelHealth(activity,()->{});health=probe;JSONArray measurements=new JSONArray();
        try{
            Channel dynamic=channel("动态网页缓存", "webview://"+base+"/neighbor-cache.html"),simple=channel("静态网页缓存", "webview://"+base+"/neighbor-static.html"),uncached=channel("禁止缓存网页", "webview://"+base+"/neighbor-nostore.html");
            for(Channel candidate:new Channel[]{dynamic,dynamic,simple,simple,uncached,uncached}){
                await(()->!probe.busy(),2000,"Previous probe did not release");probe.pause();((Map)field(probe,"cache")).remove(ChannelHealth.key(candidate));
                ChannelCatalog.Group[] groups={new ChannelCatalog.Group("缓存邻台",ChannelCatalog.SOURCE_CUSTOM,new Channel[]{current,candidate})};
                System.gc();SystemClock.sleep(250);android.app.ActivityManager.MemoryInfo probeMemory=new android.app.ActivityManager.MemoryInfo();((android.app.ActivityManager)activity.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(probeMemory);
                report.put("probeAvailableMiB",probeMemory.availMem/1024/1024);save();
                long at=SystemClock.elapsedRealtime();probe.neighbors(groups,0,0);probe.resume();await(()->state(candidate)!=99,16000,"Web neighbor probe did not finish");
                int status=state(candidate);measurements.put(new JSONObject().put("name",candidate.name).put("status",status).put("ms",SystemClock.elapsedRealtime()-at));boolean memorySkip=candidate==dynamic&&status==0&&probeMemory.availMem<160L*1024*1024;check(status==1||memorySkip,"Cached neighbor not confirmed: "+candidate.name+" status="+status+" availableMiB="+probeMemory.availMem/1024/1024);if(memorySkip)report.put("dynamicProbeSkippedForMemory",true);
            }
            check(resourceCount("/neighbor-cache.html")==1&&resourceCount("/neighbor-shared.js")==1,"Dynamic neighbor re-downloaded prepared document/scripts");
            check(resourceCount("/neighbor-static.html")==1,"Static neighbor did not reuse its document");check(resourceCount("/neighbor-nostore.html")==2,"no-store document was reused");
            check(player()==foreground&&foreground.isPlaying()&&foreground.getCurrentPosition()>position+1000,"Neighbor cache checks interrupted foreground playback");
            check(LiteCookies.PROFILE.loadForRequest(origin).toString().contains("probe=active"),"Probe modified active cookies");check(new JSONObject(getTargetContext().getSharedPreferences("lite-storage",0).getString(storage,"{}")).getString("probe").equals("active"),"Probe modified active localStorage");
            await(()->!probe.busy(),2000,"Probe worker did not release");int done=probe.completed;probe.neighbors(new ChannelCatalog.Group[]{new ChannelCatalog.Group("已探测",ChannelCatalog.SOURCE_CUSTOM,new Channel[]{current,simple})},0,0);probe.resume();SystemClock.sleep(150);check(!probe.busy()&&probe.completed==done,"Fresh good status repeated a media probe");
            report.put("neighborCacheMeasurements",measurements).put("dynamicDocumentDownloads",resourceCount("/neighbor-cache.html")).put("scriptDownloads",resourceCount("/neighbor-shared.js")).put("staticDocumentDownloads",resourceCount("/neighbor-static.html")).put("noStoreDownloads",resourceCount("/neighbor-nostore.html")).put("foregroundStayedPlaying",true).put("probeProfileIsolated",true);
        }finally{probe.close();health=original;}
        settings(new JSONObject().put("webSourceEngine","lite").put("webViewUserAgent","windows").put("webViewBrowserVersion","128").put("litePreparseEnabled",false));
        long switchAt=SystemClock.elapsedRealtime();start(channel("探测后切台", "webview://"+base+"/neighbor-cache.html"));
        check(resourceCount("/neighbor-cache.html")==1&&resourceCount("/neighbor-shared.js")==1,"Foreground channel did not reuse probed resources");
        report.put("foregroundSwitchMs",SystemClock.elapsedRealtime()-switchAt).put("foregroundReusedProbeCache",true).put("freshHealthStatusSkipsWork",true);
    }
    private void blankImages(String base)throws Exception{
        CjsPluginRuntime.setManifestUrl(base+"/catalog.json");CjsPluginRuntime.ensureCatalog();SharedWebResources.clear(activity);
        byte[] png=BlankWebImage.bytes();android.graphics.Bitmap bitmap=android.graphics.BitmapFactory.decodeByteArray(png,0,png.length);
        check(bitmap!=null&&bitmap.getWidth()==1&&bitmap.getHeight()==1&&android.graphics.Color.alpha(bitmap.getPixel(0,0))==0,"Blank PNG is not decodable/transparent");
        JSONArray outcomes=new JSONArray();report.put("blankImageTrials",outcomes);
        for(String engine:new String[]{"system","lite"})for(boolean images:new boolean[]{false,true,false}){
            main(()->call("stopCurrentChannelLoad"));settings(new JSONObject().put("webSourceEngine",engine).put("webViewLoadImages",images).put("webViewUserAgent","windows").put("litePreparseEnabled",false));
            String stage=engine+"-"+images+"-"+outcomes.length();int before=resourceCount("/blank-poster.png")+resourceCount("/blank-banner.jpg")+resourceCount("/blank-asset.svg")+resourceCount("/blank-initial.png");
            long begin=SystemClock.elapsedRealtime();start(channel("图片加载后才播放", "webview://"+base+"/blank-page.html?"+stage));
            await(()->profileReports.containsKey(stage),3000,"Image onload report missing: "+stage);JSONObject state=profileReports.get(stage);
            check(state.optInt("loads")==4&&state.optInt("errors")==0,"Images fired error instead of load: "+state);
            JSONArray sizes=state.getJSONArray("sizes");for(int i=0;i<sizes.length();i++){JSONArray size=sizes.getJSONArray(i);check(size.getInt(0)==(images?2:1)&&size.getInt(1)==(images?3:1),"Image dimensions/cache poisoned: "+state);}
            for(int i=0;i<state.getJSONArray("complete").length();i++)check(state.getJSONArray("complete").getBoolean(i),"Image not complete after load");
            int downloads=resourceCount("/blank-poster.png")+resourceCount("/blank-banner.jpg")+resourceCount("/blank-asset.svg")+resourceCount("/blank-initial.png")-before;
            check(images||downloads==0,"No-image mode downloaded original images");
            state.put("engine",engine).put("images",images).put("firstFrameMs",SystemClock.elapsedRealtime()-begin).put("originalDownloads",downloads).put("actualVideoPlaying",ready());outcomes.put(state);save();
        }
        report.put("transparentPngDecoded",true).put("toggleRestoresRealImagesWithoutClearingCache",true);
    }
    private void userScriptKernel(String base)throws Exception{
        CjsPluginRuntime.setManifestUrl(base+"/catalog.json");CjsPluginRuntime.ensureCatalog();JSONArray trials=new JSONArray();report.put("scriptTrials",trials);
        String source="// ==UserScript==\n// @name Document start fixture\n// @match http://127.0.0.1/*\n// @run-at document-start\n// @grant none\n// ==/UserScript==\nwindow.__testDocStart=17;";
        for(int i=0;i<5;i++){
            String code=i==3?source.replace("// @run-at","// @exclude-match http://127.0.0.1/*\n// @run-at"):source;
            JSONArray scripts=new JSONArray();if(i==4)scripts.put(new JSONObject().put("name","Invalid syntax").put("enabled",true).put("source","function {"));
            scripts.put(new JSONObject().put("name","Document start fixture").put("enabled",i!=2).put("source",code));
            main(()->call("stopCurrentChannelLoad"));settings(new JSONObject().put("webSourceEngine","lite").put("litePreparseEnabled",false).put("webViewUserScriptEnabled",i!=1).put("webViewUserScripts",scripts));
            String stage="userscript-"+i;start(channel("通用脚本入口", "webview://"+base+"/userscript-page.html?"+stage));
            await(()->profileReports.containsKey(stage),3000,"Document-start report missing");JSONObject state=profileReports.get(stage);
            boolean applied=i==0||i==4;check(applied?state.optInt("value")==17:state.isNull("value"),"Script matching/switch/order failure: "+state);
            check(state.optInt("applied")== (applied?1:0)&&"undefined".equals(state.optString("siteHooks")),"Reusable core ran a hidden site adapter: "+state);
            state.put("case",i).put("actualVideoPlaying",ready());trials.put(state);save();
        }
        report.put("documentStartBeforePageCode",true).put("masterAndEntrySwitches",true).put("excludeMatch",true).put("syntaxIsolation",true).put("noHiddenSiteScripts",true);
    }
    private void snapshotBuiltinScripts(SharedPreferences prefs)throws Exception{
        JSONArray entries=new JSONArray(prefs.getString("web_view_user_scripts_v2","[]"));JSONObject matches=new JSONObject();
        for(String name:getTargetContext().getAssets().list("userscripts"))if(name.endsWith(".user.js")){
            String expected=new String(read(getTargetContext().getAssets().open("userscripts/"+name)),"UTF-8");boolean found=false;
            for(int i=0;i<entries.length();i++){JSONObject entry=entries.getJSONObject(i);if(entry.optBoolean("enabled",true)&&expected.equals(entry.optString("source"))){found=true;break;}}
            matches.put(name,found&&prefs.getBoolean("web_view_user_script_enabled",true));
        }
        report.put("builtinScriptsMatchAssets",matches);
    }
    private void imageBenchmark()throws Exception{
        NetworkClient.setDnsMode("system");
        main(()->{AutoUpdater updater=(AutoUpdater)field(activity,"autoUpdater");if(updater!=null)updater.destroy();});
        report.put("backgroundUpdateDisabledForBenchmark",true);
        String traceScript=args.getString("traceScriptBase64","");
        if(args.containsKey("traceScriptPath"))traceScript=android.util.Base64.encodeToString(read(new FileInputStream(args.getString("traceScriptPath"))),android.util.Base64.NO_WRAP);
        if(!traceScript.isEmpty()){
            SharedPreferences prefs=getTargetContext().getSharedPreferences(MainActivity.PREFERENCES,0);
            JSONArray scripts=new JSONArray(prefs.getString("web_view_user_scripts_v2","[]"));
            scripts.put(new JSONObject().put("id","benchmark-lifecycle").put("name","Benchmark lifecycle trace").put("enabled",true).put("source",new String(android.util.Base64.decode(traceScript,0),"UTF-8")));
            prefs.edit().putString("web_view_user_scripts_v2",scripts.toString()).putBoolean("web_view_user_script_enabled",true).commit();
        }
        JSONArray trials=new JSONArray();report.put("imageTrials",trials);save();
        String[] sites=args.containsKey("siteBase64")?new String[]{new String(android.util.Base64.decode(args.getString("siteBase64"),0),"UTF-8")}:args.containsKey("imageSites")?args.getString("imageSites").split(","):new String[]{"https://www.huya.com/11342386","https://live.fjtv.net/setv/","https://www.hsnewsnet.com/web/tvnews/"};
        String[] imageOrder=args.getString("imageOrder","true,false,false,true").split(",");long deadline=Long.parseLong(args.getString("imageTimeoutMs","30000")),hold=Long.parseLong(args.getString("imageHoldMs","2500"));boolean auditSites=Boolean.parseBoolean(args.getString("auditSites","false"));
        for(String site:sites)for(String imageOption:imageOrder){boolean images=Boolean.parseBoolean(imageOption);
            main(()->call("stopCurrentChannelLoad"));SharedWebResources.clear(activity);System.gc();
            settings(new JSONObject().put("webSourceEngine","lite").put("webViewLoadImages",images).put("webViewUserAgent","windows").put("webViewBrowserVersion","native").put("litePreparseEnabled",false));
            Channel channel=channel("真实网页验证",site.startsWith("webview://")?site:"webview://"+site);long begin=SystemClock.elapsedRealtime();JSONObject row=new JSONObject().put("site",site).put("images",images);trials.put(row);save();
            main(()->{ChannelCatalog.GROUPS=new ChannelCatalog.Group[]{new ChannelCatalog.Group("图片对比",ChannelCatalog.SOURCE_CUSTOM,new Channel[]{channel})};set("currentGroupIndex",0);set("quickChannel",null);
                if(Boolean.parseBoolean(args.getString("directLite","false"))){set("quickChannel",channel);call("openLiteWebSource",new Class<?>[]{Channel.class,String.class,int.class,String.class},channel,channel.sourceUrl(0),(Integer)field(activity,"playRequestId"),"");}
                else call("startChannel",new Class<?>[]{int.class},0);
            });
            long first=0,positionAtFirst=0,initialFrames=-1,latestFrames=-1,lastSample=0,previousPosition=-1,advanced=0,lastAdvance=0,maxStall=0;JSONObject timing=new JSONObject();String error="";JSONArray captures=new JSONArray(),playbackSamples=new JSONArray();
            IMediaPlayer frameOwner=null;long previousFrames=-1,decodedFrames=0;
            while(SystemClock.elapsedRealtime()-begin<(first==0?deadline:first+hold)){
                LiteWebPlayback lite=(LiteWebPlayback)field(activity,"liteWebPlayback");if(lite!=null){if(auditSites)lite.enableAudit();timing=lite.startupTimings();JSONObject audit=lite.auditSnapshot();row.put("audit",audit);if(audit.optJSONArray("messages")!=null)row.put("diagnostics",audit.optJSONArray("messages"));}
                if(first==0&&ready()){first=SystemClock.elapsedRealtime()-begin;positionAtFirst=player().getCurrentPosition();}
                if(first>0&&SystemClock.elapsedRealtime()-lastSample>=500){
                    lastSample=SystemClock.elapsedRealtime();IMediaPlayer active=player();long position=active==null?-1:active.getCurrentPosition();
                    if(lastAdvance==0)lastAdvance=lastSample;if(position>previousPosition&&previousPosition>=0){advanced+=Math.min(1500,position-previousPosition);lastAdvance=lastSample;}
                    previousPosition=position;maxStall=Math.max(maxStall,lastSample-lastAdvance);
                    if(active instanceof IjkMediaPlayer){
                        long counter=IjkStreamMetrics.outputFrameCount((IjkMediaPlayer)active);
                        if(counter>=0){
                            if(initialFrames<0)initialFrames=counter;
                            else decodedFrames+=active!=frameOwner||counter<previousFrames?counter:Math.max(0,counter-previousFrames);
                            frameOwner=active;previousFrames=counter;latestFrames=initialFrames+decodedFrames;
                        }
                    }
                    playbackSamples.put(new JSONObject().put("atMs",lastSample-begin).put("positionMs",position).put("playing",ready()).put("outputFrames",latestFrames));
                }
                if(first>0&&Build.VERSION.SDK_INT>=18&&Boolean.parseBoolean(args.getString("captureFrames","false"))&&captures.length()<2&&SystemClock.elapsedRealtime()-begin-first>(captures.length()==0?5000:30000)){
                    android.graphics.Bitmap picture=getUiAutomation().takeScreenshot();if(picture!=null){File file=new File(getTargetContext().getExternalFilesDir(null),"compat-frame-"+trials.length()+"-"+captures.length()+".png");try(FileOutputStream out=new FileOutputStream(file)){picture.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}finally{picture.recycle();}captures.put(file.getName());row.put("frames",captures);save();}
                }
                SystemClock.sleep(150);
            }
            if(first==0)error="No first frame within "+deadline+" milliseconds";
            boolean playingAtEnd=first>0&&ready();if(first>0&&!playingAtEnd)error="Playback interrupted after first frame";
            if(first>0&&Boolean.parseBoolean(args.getString("strictPlayback","false"))&&(advanced<hold*0.65||maxStall>=6000||initialFrames<0||latestFrames-initialFrames<20)){playingAtEnd=false;error="Insufficient actual decoder progress: clock="+advanced+" frames="+(latestFrames-initialFrames)+" stall="+maxStall;}
            if(playingAtEnd&&Build.VERSION.SDK_INT>=18&&Boolean.parseBoolean(args.getString("captureFrames","false"))){
                android.graphics.Bitmap picture=getUiAutomation().takeScreenshot();if(picture!=null){File file=new File(getTargetContext().getExternalFilesDir(null),"compat-frame-"+trials.length()+"-end.png");try(FileOutputStream out=new FileOutputStream(file)){picture.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}finally{picture.recycle();}captures.put(file.getName());row.put("frames",captures);}
            }
            row.put("frameMeasurement","Cumulative actual decoder output across player generations");
            if(player()!=null)row.put("backend",player().getClass().getSimpleName());
            if(auditSites&&player()!=null){
                row.put("nativeDurationMs",player().getDuration());
                Object transport=field(activity,"proxy");
                if(transport!=null)row.put("proxyState",new JSONObject().put("metadataProbeMode",field(transport,"metadataProbeMode")).put("finiteMedia",field(transport,"finiteMedia")).put("nativeMediaDownloads",field(transport,"nativeMediaDownloads")).put("readingStartupManifest",field(transport,"readingStartupManifest")));
                // Capture the manifest actually delivered to the decoder, only
                // after the measured observation so this GET cannot repair it.
                String source=player().getDataSource();
                if(source!=null&&source.startsWith("http://127.")&&source.endsWith(".m3u8"))try{
                    HttpURLConnection connection=(HttpURLConnection)new URL(source).openConnection();connection.setConnectTimeout(1000);connection.setReadTimeout(1000);
                    try(InputStream in=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                        byte[] block=new byte[1024];int count;while((count=in.read(block))!=-1&&out.size()+count<=16384)out.write(block,0,count);
                        row.put("decoderManifest",out.toString("UTF-8"));
                    }finally{connection.disconnect();}
                }catch(Exception diagnostic){row.put("decoderManifestError",diagnostic.getClass().getSimpleName());}
            }
            row.put("playbackSamples",playbackSamples).put("advancedMs",advanced).put("outputFramesAdvanced",initialFrames<0?-1:latestFrames-initialFrames).put("maxStallMs",maxStall);
            row.put("elapsedMs",SystemClock.elapsedRealtime()-begin).put("firstFrameMs",first>0?first:JSONObject.NULL).put("timing",timing).put("error",error).put("playable",playingAtEnd).put("playingAtEnd",playingAtEnd).put("positionAtFirstMs",positionAtFirst).put("positionAtEndMs",playingAtEnd?player().getCurrentPosition():JSONObject.NULL).put("holdMs",hold);save();
            if(playingAtEnd){IMediaPlayer active=player();row.put("videoWidth",active.getVideoWidth()).put("videoHeight",active.getVideoHeight());if(active instanceof IjkMediaPlayer)row.put("videoOutputFps",((IjkMediaPlayer)active).getVideoOutputFramesPerSecond());save();}
            if(Boolean.parseBoolean(args.getString("stopOnFailure","false")))check(playingAtEnd,"Real channel failed: "+site+" images="+images+" "+error);
        }
    }
    private void litePublic(Channel current)throws Exception{
        CjsPluginRuntime.setManifestUrl(current.sourceUrl(0).replace("/live.m3u8","/catalog.json"));CjsPluginRuntime.ensureCatalog();
        JSONArray outcomes=new JSONArray();report.put("publicPreparse",outcomes);
        settings(new JSONObject().put("webSourceEngine","lite").put("litePreparseEnabled",false));
        String[] sites=args.containsKey("publicUrl")?new String[]{args.getString("publicUrl")}:new String[]{"https://www.hsnewsnet.com/web/tvnews/","https://live.fjtv.net/setv/"};
        for(String url:sites){
            JSONObject result=new JSONObject().put("url",url);outcomes.put(result);
            String domain=WebDomainSettings.key("webview://"+url);
            if("true".equals(args.getString("compareSystem"))){
                settings(new JSONObject().put("webPreferenceScope","domain").put("webPreferenceDomain",domain).put("webSourceEngine","system"));
                long system=SystemClock.elapsedRealtime();
                Channel systemTarget=channel("系统内核对照","webview://"+url);
                try{
                    main(()->{ChannelCatalog.GROUPS=new ChannelCatalog.Group[]{new ChannelCatalog.Group("原生对照",ChannelCatalog.SOURCE_CUSTOM,new Channel[]{systemTarget})};set("currentGroupIndex",0);set("quickChannel",null);call("startChannel",new Class<?>[]{int.class},0);});
                    await(()->ready()&&field(activity,"activePlayerChannel")==systemTarget||readyWeb(systemTarget),30000,"System WebView video did not produce frames");
                    result.put("systemFirstFrameMs",SystemClock.elapsedRealtime()-system).put("systemVideoInWebView",readyWeb(systemTarget));
                }catch(Exception|AssertionError error){result.put("systemComparisonError",error.toString());}
                save();start(current);
            }
            settings(new JSONObject().put("webPreferenceScope","domain").put("webPreferenceDomain",domain).put("webSourceEngine","lite"));
            if(args.containsKey("publicUrl")){long cold=SystemClock.elapsedRealtime();start(channel("网页冷启动","webview://"+url));result.put("coldFirstFrameMs",SystemClock.elapsedRealtime()-cold);save();}
            start(current);Channel target=channel("真实网页", "webview://"+url);
            main(()->{ChannelCatalog.GROUPS=new ChannelCatalog.Group[]{new ChannelCatalog.Group("真实网页",ChannelCatalog.SOURCE_CUSTOM,new Channel[]{current,target})};set("currentGroupIndex",0);set("currentChannelIndex",0);});
            settings(new JSONObject().put("litePreparseEnabled",true));
            long begin=SystemClock.elapsedRealtime();while(SystemClock.elapsedRealtime()-begin<23000){LiteWebPreloader cache=(LiteWebPreloader)field(activity,"liteWebPreloader");if(cache!=null&&(cache.ready()||((Integer)field(activity,"litePreparseRequest")).intValue()==((Integer)field(activity,"playRequestId")).intValue()&&!cache.occupied()))break;SystemClock.sleep(100);}
            LiteWebPreloader cache=(LiteWebPreloader)field(activity,"liteWebPreloader");result.put("ready",cache!=null&&cache.ready()).put("prepareMs",SystemClock.elapsedRealtime()-begin).put("failure",cache==null?"Not started":field(cache,"failure"));save();
            if(cache!=null&&cache.ready()){
                begin=SystemClock.elapsedRealtime();main(()->call("switchChannel",new Class<?>[]{int.class},1));await(()->ready()&&field(activity,"activePlayerChannel")==target,30000,"Prepared public site failed playback: "+url);result.put("firstFrameMs",SystemClock.elapsedRealtime()-begin);SystemClock.sleep(5000);check(ready(),"Public web playback stopped after handoff");
                long hold=Long.parseLong(args.getString("publicHoldMs","0"));hold=Math.max(0,Math.min(600000,hold));
                long heldAt=SystemClock.elapsedRealtime(),last=player().getCurrentPosition(),advanced=0;
                IMediaPlayer retainedPlayer=player();JSONArray samples=new JSONArray();result.put("stability",samples);
                while(SystemClock.elapsedRealtime()-heldAt<hold){
                    SystemClock.sleep(1000);check(player()==retainedPlayer&&ready(),"Prepared public playback stopped/restarted");
                    long now=retainedPlayer.getCurrentPosition();advanced+=Math.max(0,now-last);last=now;
                    if(samples.length()==0||SystemClock.elapsedRealtime()-heldAt>samples.length()*15000L){samples.put(new JSONObject().put("elapsedMs",SystemClock.elapsedRealtime()-heldAt).put("positionMs",now).put("advancedMs",advanced).put("pssKiB",android.os.Debug.getPss()));save();}
                }
                check(hold==0||advanced>=hold*.75,"Prepared public playback clock stalled");result.put("stableElapsedMs",SystemClock.elapsedRealtime()-heldAt).put("stableAdvancedMs",advanced).put("samePlayerRetained",true);save();
            }
            settings(new JSONObject().put("litePreparseEnabled",false));save();
        }
    }
    private void epgOptions(String base)throws Exception{
        check(!(Boolean)field(activity,"epgShowLogos")&&!(Boolean)field(activity,"epgDatePicker"),"New display options must default off");
        long today=EpgManager.dayStart(System.currentTimeMillis());Calendar next=Calendar.getInstance();next.setTimeInMillis(today);next.add(Calendar.DATE,1);long tomorrow=next.getTimeInMillis();
        Channel channel=new Channel("1","节目单测试台","fixture","http://127.0.0.1/unavailable",null,null,null,"fixture");
        EpgManager manager=(EpgManager)field(activity,"epgManager");
        Class<?> type=Class.forName("xiao.bu.tv.EpgManager$Guide");java.lang.reflect.Constructor<?> constructor=type.getDeclaredConstructor();constructor.setAccessible(true);Object guide=constructor.newInstance();
        @SuppressWarnings("unchecked") Map<String,List<EpgManager.Program>> programmes=(Map<String,List<EpgManager.Program>>)field(guide,"programsByChannel");
        programmes.put("fixture",Arrays.asList(new EpgManager.Program(today+3600000,today+7200000,"今日节目")));
        android.graphics.Bitmap image=android.graphics.Bitmap.createBitmap(56,28,android.graphics.Bitmap.Config.ARGB_8888);image.eraseColor(android.graphics.Color.BLUE);ByteArrayOutputStream bytes=new ByteArrayOutputStream();image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,bytes);image.recycle();files.put("/epg-logo.png",bytes.toByteArray());
        @SuppressWarnings("unchecked") Map<String,String> logos=(Map<String,String>)field(guide,"logosByChannel");logos.put("fixture",base+"/epg-logo.png");
        java.lang.reflect.Field guideField=EpgManager.class.getDeclaredField("guide");guideField.setAccessible(true);
        java.lang.reflect.Field loading=EpgManager.class.getDeclaredField("loading");loading.setAccessible(true);
        main(()->{
            guideField.set(manager,guide);loading.setBoolean(manager,true);
            ChannelCatalog.GROUPS=new ChannelCatalog.Group[]{new ChannelCatalog.Group("显示选项",ChannelCatalog.SOURCE_CUSTOM,new Channel[]{channel})};
            set("currentGroupIndex",0);set("currentChannelIndex",0);call("openChannelList");set("epgExpanded",true);call("showEpgForBrowsingChannel",new Class<?>[]{int.class},0);
        });
        check(field((ChannelListAdapter)field(activity,"channelAdapter"),"logos")==null,"Disabled logos started loading");
        settings(new JSONObject().put("epgDatePicker",true));
        android.widget.ListView dates=(android.widget.ListView)field(activity,"epgDateList");EpgListAdapter adapter=(EpgListAdapter)field(activity,"epgAdapter");
        check(dates.getVisibility()==android.view.View.GONE,"One-day guide must hide date picker even when enabled");
        programmes.put("fixture",Arrays.asList(new EpgManager.Program(today+3600000,today+7200000,"今日节目"),new EpgManager.Program(tomorrow+3600000,tomorrow+7200000,"明日节目")));
        main(()->call("showEpgForBrowsingChannel",new Class<?>[]{int.class},0));waitForIdleSync();
        check(dates.getVisibility()==android.view.View.VISIBLE&&dates.getCount()==2,"Actual two-day guide did not expose exactly two dates: visible="+dates.getVisibility()+" rows="+dates.getCount()+" enabled="+field(activity,"epgDatePicker")+" expanded="+field(activity,"epgExpanded")+" adapter="+adapter.hasDatePicker()+" source="+manager.programsFor(channel).size());
        main(()->call("focusEpgList"));check(dates.hasFocus(),"Remote could not focus date picker");
        main(()->call("moveChannelMenuSelection",new Class<?>[]{int.class},1));
        check(adapter.selectedDay()==tomorrow&&adapter.getCount()==1&&"明日节目".equals(adapter.getItem(0).title),"Date selection did not filter the programme list");
        main(()->activity.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_DPAD_RIGHT)));
        check(((android.view.View)field(activity,"epgList")).hasFocus(),"Right key did not enter programmes");
        main(()->activity.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_DPAD_LEFT)));
        check(dates.hasFocus(),"Left key did not return to dates");
        programmes.put("fixture",Arrays.asList(new EpgManager.Program(today+3600000,today+7200000,"今日节目")));
        main(()->call("showEpgForBrowsingChannel",new Class<?>[]{int.class},0));
        check(dates.getVisibility()==android.view.View.GONE&&adapter.getCount()==1,"Single-day refresh retained stale dates/content");
        settings(new JSONObject().put("epgShowLogos",true));waitForIdleSync();
        android.widget.ListView channels=(android.widget.ListView)field(activity,"channelList");
        await(()->channels.getChildCount()>0,3000,"Channel row not visible");
        await(()->{Object holder=channels.getChildAt(0).getTag();Object logo=field(holder,"logo");return logo instanceof android.widget.ImageView&&((android.widget.ImageView)logo).getDrawable()!=null;},5000,"Source/EPG logo did not load");
        programmes.put("fixture",Arrays.asList(new EpgManager.Program(today+3600000,today+7200000,"今日节目"),new EpgManager.Program(tomorrow+3600000,tomorrow+7200000,"明日节目")));
        main(()->call("showEpgForBrowsingChannel",new Class<?>[]{int.class},0));waitForIdleSync();SystemClock.sleep(200);
        if(Build.VERSION.SDK_INT>=18){android.graphics.Bitmap screen=getUiAutomation().takeScreenshot();try(FileOutputStream out=new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null),"epg-options-on.png"))){screen.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}finally{screen.recycle();}}
        settings(new JSONObject().put("epgShowLogos",false).put("epgDatePicker",false));waitForIdleSync();
        Object logo=field(channels.getChildAt(0).getTag(),"logo");check(logo==null||((android.widget.ImageView)logo).getVisibility()==android.view.View.GONE,"Disabled logo remains visible");
        SharedPreferences prefs=getTargetContext().getSharedPreferences(MainActivity.PREFERENCES,0);
        check(!prefs.getBoolean("epg_show_logos",true)&&!prefs.getBoolean("epg_date_picker",true),"Display switches were not persisted independently");
        report.put("theme",TvUiPlugin.current()==null?"builtin":TvUiPlugin.current().id);
        report.put("defaultOff",true).put("oneDayHidden",true).put("actualDatesOnly",true).put("remoteDateAndProgrammeNavigation",true).put("singleDayRefreshClearsPicker",true).put("logoLoadedAndDisabled",true).put("settingsPersisted",true);
    }
    private boolean logoReady(android.widget.ImageView image)throws Exception{boolean[] yes={false};main(()->yes[0]=image.getDrawable()!=null&&image.getVisibility()==android.view.View.VISIBLE);return yes[0];}
    private void logoChecks(String base)throws Exception{
        settings(new JSONObject().put("playerEngine",Build.VERSION.SDK_INT<=15?"system":"ijk").put("continuousTsEnabled",Build.VERSION.SDK_INT<=15).put("epgShowLogos",true));
        start(channel("台标测试直播",base+"/preflight-live-logos.m3u8"));IMediaPlayer original=player();long began=original.getCurrentPosition(),wall=SystemClock.elapsedRealtime();
        File dir=new File(getTargetContext().getCacheDir(),"logo-preflight-"+System.nanoTime());check(dir.mkdirs(),"Logo fixture directory");
        Context context=new ContextWrapper(getTargetContext()){@Override public Context getApplicationContext(){return this;}@Override public File getCacheDir(){return dir;}};
        ChannelLogoLoader[] loader={null};android.widget.ImageView[] images=new android.widget.ImageView[12];
        try{
            main(()->{loader[0]=new ChannelLogoLoader();for(int i=0;i<images.length;i++){images[i]=new android.widget.ImageView(context);loader[0].load(images[i],"相同频道",base+"/test-logo-green.png");}});
            await(()->logoReady(images[11]),6000,"Pending duplicate logo did not arrive");check(resourceCount("/test-logo-green.png")==1,"Inflight logo not coalesced");
            main(()->{for(int round=0;round<100;round++)for(android.widget.ImageView image:images)loader[0].load(image,"相同频道",base+"/test-logo-green.png");});check(resourceCount("/test-logo-green.png")==1,"Warm redraw refetched logo");
            main(()->{loader[0].close();loader[0]=new ChannelLogoLoader();loader[0].load(images[0],"相同频道",base+"/test-logo-green.png");});
            await(()->logoReady(images[0]),3000,"Cold loader failed disk reuse");check(resourceCount("/test-logo-green.png")==1,"Disk cache refetched logo");
            main(()->loader[0].load(images[0],"相同频道",base+"/test-logo-red.png"));await(()->logoReady(images[0]),3000,"Changed logo URL failed");
            main(()->check(((android.graphics.drawable.BitmapDrawable)images[0].getDrawable()).getBitmap().getPixel(0,0)==0xffff0000,"Stale logo after URL change"));
            main(()->{loader[0].load(images[1],"回收行",base+"/test-logo-slow.png");loader[0].load(images[1],"新频道",base+"/test-logo-red.png");});await(()->logoReady(images[1]),6000,"Recycled row failed");SystemClock.sleep(2000);
            main(()->check(((android.graphics.drawable.BitmapDrawable)images[1].getDrawable()).getBitmap().getPixel(0,0)==0xffff0000,"Old async request overwrote row"));
            main(()->loader[0].load(images[2],"坏图",base+"/test-logo-bad.png"));await(()->((Map)field(loader[0],"pending")).isEmpty(),6000,"Missing logo remained pending");
            main(()->{for(int i=0;i<20;i++)loader[0].load(images[3],"坏图",base+"/test-logo-bad.png");check(images[3].getVisibility()==android.view.View.INVISIBLE,"Missing logo moved title column");});SystemClock.sleep(200);check(resourceCount("/test-logo-bad.png")==1,"Failure cache redownloaded missing logo");
            main(()->loader[0].load(images[4],"超大图",base+"/test-logo-oversize.png"));await(()->((Map)field(loader[0],"pending")).isEmpty(),6000,"Oversize logo remained pending");check(!logoReady(images[4]),"Oversize logo accepted");
            final int[] memory={0};main(()->{android.util.LruCache cache=(android.util.LruCache)field(loader[0],"cache");memory[0]=cache.size();check(cache.maxSize()==ChannelLogoLoader.MEMORY_BYTES&&cache.size()<=cache.maxSize(),"Unbounded bitmap memory");});
            ChannelLogoCache disk=new ChannelLogoCache(dir);String key=ChannelLogoLoader.cacheKey(context,"相同频道",base+"/test-logo-green.png");check(disk.fileFor(key).setLastModified(System.currentTimeMillis()-ChannelLogoCache.VALID_MS-1000),"Cannot expire test PNG");
            main(()->{loader[0].close();loader[0]=new ChannelLogoLoader();loader[0].load(images[0],"相同频道",base+"/test-logo-green.png");});await(()->logoReady(images[0]),3000,"Expired logo failed refresh");check(resourceCount("/test-logo-green.png")==2,"Expired PNG reused");
            try(FileOutputStream broken=new FileOutputStream(disk.fileFor(key))){broken.write(new byte[]{1,2,3});}
            main(()->{loader[0].close();loader[0]=new ChannelLogoLoader();loader[0].load(images[0],"相同频道",base+"/test-logo-green.png");});await(()->logoReady(images[0]),3000,"Corrupt PNG failed refresh");check(resourceCount("/test-logo-green.png")==3,"Corrupt PNG reused");
            android.graphics.Bitmap tiny=android.graphics.Bitmap.createBitmap(4,4,android.graphics.Bitmap.Config.ARGB_8888);tiny.eraseColor(0xff123456);
            for(int i=0;i<140;i++)disk.write("quota-"+i,tiny,System.currentTimeMillis());for(int i=0;i<10;i++)try(RandomAccessFile fill=new RandomAccessFile(new File(disk.fileFor(key).getParentFile(),"quota-heavy-"+i+".png"),"rw")){fill.setLength(512*1024);}disk.write("quota-final",tiny,System.currentTimeMillis());tiny.recycle();File[] cached=disk.fileFor(key).getParentFile().listFiles();long bytes=0;int count=0;for(File f:cached)if(f.getName().endsWith(".png")){bytes+=f.length();count++;}check(count<=ChannelLogoCache.MAX_FILES&&bytes<=ChannelLogoCache.MAX_BYTES,"Disk quota exceeded");
            int[] queued={0};main(()->{android.widget.ImageView[] rush=new android.widget.ImageView[50];for(int i=0;i<rush.length;i++){rush[i]=new android.widget.ImageView(context);loader[0].load(rush[i],"快滚"+i,base+"/test-logo-slow.png?row="+i);}ThreadPoolExecutor worker=(ThreadPoolExecutor)field(loader[0],"worker");queued[0]=worker.getQueue().size();check(queued[0]<=24,"Unbounded queue");for(android.widget.ImageView view:rush)loader[0].clear(view);check(((Map)field(loader[0],"pending")).isEmpty(),"Recycled jobs retained");});
            report.put("logoCache",new JSONObject().put("rapidScrollQueueBounded",true).put("queuedAtPeak",queued[0]).put("coalescedTargets",12).put("coldNetworkRequests",1).put("warmBinds",1200).put("warmNetworkRequests",0).put("diskReuse",true).put("changedUrlRefreshes",true).put("recycledRowSafe",true).put("failureBackoff",true).put("expiredAndCorruptRefresh",true).put("oversizeRejected",true).put("memoryBytes",memory[0]).put("memoryLimitBytes",ChannelLogoLoader.MEMORY_BYTES).put("diskFilesAfter140Writes",count).put("diskBytes",bytes));
        }finally{main(()->{if(loader[0]!=null)loader[0].close();});File cache=new File(dir,"channel-logos-v1");File[] children=cache.listFiles();if(children!=null)for(File f:children)f.delete();cache.delete();dir.delete();}
        EpgManager manager=new EpgManager(getTargetContext());java.lang.reflect.Method parse=EpgManager.class.getDeclaredMethod("parse",byte[].class);parse.setAccessible(true);Object guide=parse.invoke(null,(Object)("<tv><channel id='opaque'><icon src='"+base+"/test-logo-green.png'/></channel></tv>").getBytes("UTF-8"));java.lang.reflect.Field guideField=EpgManager.class.getDeclaredField("guide");guideField.setAccessible(true);guideField.set(manager,guide);check(manager.logoFor(new Channel("1","不匹配名称","opaque",base+"/live.m3u8",null,null,null,"opaque")).contains("test-logo-green"),"Opaque EPG ID with icon but no programmes not resolved");report.put("logoOnlyEpgId",true);
        Channel[] lineup=new Channel[7];for(int i=0;i<lineup.length;i++)lineup[i]=channel("CCTV-"+(i+1)+" 台标测试",base+"/preflight-live-logos.m3u8").withLogo("true".equals(args.getString("publicLogos"))?"https://raw.githubusercontent.com/TvWasm/autoEPG/main/assets/logos/cctv"+(i+1)+".png":base+"/logos/cctv"+(i+1)+".png");
        main(()->{ChannelCatalog.GROUPS=new ChannelCatalog.Group[]{new ChannelCatalog.Group("台标回归",ChannelCatalog.SOURCE_CUSTOM,lineup)};set("currentGroupIndex",0);set("currentChannelIndex",0);});
        JSONArray themes=new JSONArray();
        for(String id:TvUiPlugin.IDS){
            TvUiPlugin.select(getTargetContext(),id);waitForIdleSync();main(()->call("openChannelList"));
            await(()->{boolean[] ready={false};main(()->{android.widget.ListView list=(android.widget.ListView)field(activity,"channelList");int count=list.getChildCount();ready[0]=count>=4;for(int i=0;i<count;i++){android.view.View row=list.getChildAt(i);Object holder=row.getTag();android.widget.ImageView logo=(android.widget.ImageView)field(holder,"logo");if(logo==null||logo.getDrawable()==null)ready[0]=false;}});return ready[0];},30000,"Not all visible logos loaded: "+id);
            int[] shape=new int[3];main(()->{android.widget.ListView list=(android.widget.ListView)field(activity,"channelList");for(int i=0;i<list.getChildCount();i++){Object holder=list.getChildAt(i).getTag();android.widget.ImageView logo=(android.widget.ImageView)field(holder,"logo");android.graphics.Bitmap b=((android.graphics.drawable.BitmapDrawable)logo.getDrawable()).getBitmap();check(b.getWidth()<=ChannelLogoLoader.MAX_DIMENSION&&b.getHeight()<=ChannelLogoLoader.MAX_DIMENSION,"Unbounded decoded logo size");int slot=Math.round(logo.getParent()==null?56*activity.themeScale():logo.getWidth());check(b.getWidth()>=Math.min(246,slot),"Logo undersampled for screen: "+b.getWidth()+" < "+slot);check(b.getWidth()>b.getHeight()*2,"Transparent padding not trimmed");if(i==0){shape[0]=b.getWidth();shape[1]=b.getHeight();shape[2]=slot;}}});
            capturePreflight("logos-"+id);themes.put(new JSONObject().put("theme",id).put("allVisibleRowsLoaded",true).put("decodedWidth",shape[0]).put("decodedHeight",shape[1]).put("displaySlotWidth",shape[2]).put("notUndersampled",true));
            settings(new JSONObject().put("epgShowLogos",false));main(()->{ChannelListAdapter adapter=(ChannelListAdapter)field(activity,"channelAdapter");android.widget.ListView list=(android.widget.ListView)field(activity,"channelList");adapter.refreshVisibleSelection(list);for(int i=0;i<list.getChildCount();i++){android.widget.ImageView logo=(android.widget.ImageView)field(list.getChildAt(i).getTag(),"logo");check(logo==null||logo.getDrawable()==null,"Hidden setting retained logo");}});
            settings(new JSONObject().put("epgShowLogos",true));main(()->call("closeChannelList"));check(TvUiPlugin.failure().isEmpty(),"Logo rejected renderer");
        }
        report.put("logoThemes",themes).put("publicLogoUrls","true".equals(args.getString("publicLogos")));main(()->{TvUiPlugin.select(getTargetContext(),"ui3");call("openChannelList");});SystemClock.sleep(500);capturePreflight("logos-blue-final");main(()->call("closeChannelList"));
        check(player()==original&&original.isPlaying()&&original.getCurrentPosition()>began+1000,"Logo work interrupted playback");report.put("logosForegroundUninterrupted",true).put("logosElapsedMs",SystemClock.elapsedRealtime()-wall);
        long switchedAt=SystemClock.elapsedRealtime();start(channel("台标后切台",base+"/preflight-live-next.m3u8"));report.put("logoAfterSwitchReadyMs",SystemClock.elapsedRealtime()-switchedAt);
    }

    private void speedChecks(String base)throws Exception{
        settings(new JSONObject().put("playerEngine",Build.VERSION.SDK_INT<=15?"system":"ijk").put("continuousTsEnabled",Build.VERSION.SDK_INT<=15).put("playerVolumeBalance",false).put("neighborProbeEnabled",false).put("autoSwitchSourceSeconds",0));
        audioMuteForSpeed();JSONArray cases=new JSONArray();report.put("speedTrials",cases).put("mode","speed");
        ArrayList<String[]> inputs=new ArrayList<>();inputs.add(new String[]{"Local HLS","m3u8",base+"/preflight-live-speed.m3u8"});inputs.add(new String[]{"Local FLV","flv",base+"/fixture.flv"});inputs.add(new String[]{"Local Ku9 HLS","ku9js",base+"/ku9/js/speed-fixture.js?format=hls"});inputs.add(new String[]{"Local Ku9 FLV","ku9js-flv",base+"/ku9/js/speed-fixture.js?format=flv"});
        if("true".equals(args.getString("publicSources"))){inputs.add(new String[]{"CJS CMG CCTV1","cjs","https://raw.githubusercontent.com/TvWasm/cjs/main/cmg.cjs?id=600001859"});inputs.add(new String[]{"CJS CCTV3","cjs","https://raw.githubusercontent.com/TvWasm/cjs/main/cctv.cjs?id=cctv3"});inputs.add(new String[]{"Ku9 Fujian","ku9js","https://raw.githubusercontent.com/TvWasm/cjs/main/k-web/ku9/js/fjtv.js?id=setv"});inputs.add(new String[]{"Ku9 Hebei","ku9js","https://raw.githubusercontent.com/TvWasm/cjs/main/k-web/ku9/js/hebtv.js?id=hbws"});}
        if(args.containsKey("directHlsBase64"))inputs.add(new String[]{"Fujian direct HLS","m3u8-public",new String(android.util.Base64.decode(args.getString("directHlsBase64"),0),"UTF-8")});
        if("true".equals(args.getString("publicSources")))inputs.add(new String[]{"Public FLV reference VOD","flv-public-vod","https://sf1-cdn-tos.huoshanstatic.com/obj/media-fe/xgplayer_doc_video/flv/xgplayer-demo-480p.flv"});
        for(String[] input:inputs)for(int trial=0;trial<2;trial++){
            if(args.containsKey("speedFilter")&&!input[0].contains(args.getString("speedFilter")))continue;
            JSONObject row=new JSONObject().put("name",input[0]).put("kind",input[1]).put("visit",trial);cases.put(row);save();Channel target=channel(input[0],input[2]);long began=SystemClock.elapsedRealtime(),cpu=android.os.Process.getElapsedCpuTime();
            main(()->{ChannelCatalog.GROUPS=new ChannelCatalog.Group[]{new ChannelCatalog.Group("Speed benchmark",ChannelCatalog.SOURCE_CUSTOM,new Channel[]{target})};set("currentGroupIndex",0);set("quickChannel",null);call("startChannel",new Class<?>[]{int.class},0);});
            long first=-1;while(SystemClock.elapsedRealtime()-began<28000){if(ready()&&field(activity,"activePlayerChannel")==target){first=SystemClock.elapsedRealtime()-began;break;}if(((Integer)field(activity,"loadErrorRequestId")).intValue()== ((Integer)field(activity,"playRequestId")).intValue()&&!(Boolean)field(activity,"loadingActive"))break;SystemClock.sleep(30);}
            int request=(Integer)field(activity,"playRequestId");row.put("firstReadyMs",first).put("readyMeasurement",Build.VERSION.SDK_INT<=15?"Rendering callback or clock/dimensions inference; see renderingCallback":"Rendering callback").put("cpuToReadyMs",android.os.Process.getElapsedCpuTime()-cpu).put("timing",((ChannelStartupTiming)field(activity,"channelStartupTiming")).format(request)).put("request",request).put("error",((Integer)field(activity,"loadErrorRequestId")).intValue()==request?field(activity,"loadError"):"");
            boolean ok=false;if(first>=0){IMediaPlayer owner=player();long position=owner.getCurrentPosition();SystemClock.sleep(1800);long advanced=owner==player()?owner.getCurrentPosition()-position:-1;ok=ready()&&owner==player()&&advanced>500;row.put("advancedMs",advanced).put("backend",owner.getClass().getSimpleName()).put("videoWidth",owner.getVideoWidth()).put("videoHeight",owner.getVideoHeight()).put("renderingCallback",!(owner instanceof AndroidMediaPlayer)||((Integer)field(activity,"systemFirstFrameRequestId")).intValue()==request);
                if(trial==0){main(()->{call("closeChannelList");((android.view.View)field(activity,"channelBar")).setVisibility(android.view.View.GONE);});capturePreflight("speed-"+input[1]+"-"+input[0].replace(' ','-'));}
            }
            row.put("passed",ok);save();Bundle phase=new Bundle();phase.putString("phase","speed "+input[0]+" visit="+trial+" first="+first+" ok="+ok);sendStatus(0,phase);
            if(!ok)break;
        }
        if("true".equals(args.getString("publicSources"))){
            java.lang.reflect.Field f=CjsPluginRuntime.class.getDeclaredField("INSTALL_LOCK");f.setAccessible(true);Object lock=f.get(null);CountDownLatch held=new CountDownLatch(1),release=new CountDownLatch(1);
            Thread updater=new Thread(()->{synchronized(lock){held.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException ignored){}}},"cjs-update-lock-test");updater.start();
            try{check(held.await(20,TimeUnit.SECONDS),"Background lock unavailable");long now=SystemClock.elapsedRealtime();CjsPluginRuntime.ensureCatalog();CjsPluginRuntime.prepareSource("https://raw.githubusercontent.com/TvWasm/cjs/main/cmg.cjs?id=600001859");long elapsed=SystemClock.elapsedRealtime()-now;report.put("cjsCachedPrepareWhileUpdateLockedMs",elapsed);check(elapsed<300,"Cached playback waited for update lock");}finally{release.countDown();updater.join(1000);}
        }
        boolean all=true;for(int i=0;i<cases.length();i++)all&=cases.getJSONObject(i).optBoolean("passed");report.put("completed",true).put("allPlaybackCasesPassed",all).put("passed",true);
    }
    private void audioMuteForSpeed(){((AudioManager)getTargetContext().getSystemService(Context.AUDIO_SERVICE)).setStreamVolume(AudioManager.STREAM_MUSIC,0,0);}

    private void datagrams(boolean rtp){try(DatagramSocket socket=new DatagramSocket()){int seq=0;byte[] packet=new byte[1328];while(!networkClosed)for(int i=0;i<6&&!networkClosed;i++){byte[] ts=files.get("/seg"+i+".ts");for(int at=0;at<ts.length&&!networkClosed;at+=1316){int length=Math.min(1316,ts.length-at),offset=rtp?12:0;if(rtp){packet[0]=(byte)0x80;packet[1]=33;packet[2]=(byte)(seq>>8);packet[3]=(byte)seq++;long time=SystemClock.elapsedRealtime()*90;for(int b=0;b<4;b++)packet[4+b]=(byte)(time>>(24-b*8));packet[11]=1;}System.arraycopy(ts,at,packet,offset,length);socket.send(new DatagramPacket(packet,offset+length,InetAddress.getByName("127.0.0.1"),rtp?18560:18562));SystemClock.sleep(12);}}}catch(Exception error){android.util.Log.e("HealthBalanceFixture","Datagram fixture failed",error);}}
    @Override public void onStart(){Bundle result=new Bundle();int code=-1;SharedPreferences prefs=getTargetContext().getSharedPreferences(MainActivity.PREFERENCES,0);Map<String,?> saved=new HashMap<>(prefs.getAll());ChannelCatalog.Group[] groups=ChannelCatalog.GROUPS;AudioManager audio=(AudioManager)getTargetContext().getSystemService(Context.AUDIO_SERVICE);int originalVolume=audio.getStreamVolume(AudioManager.STREAM_MUSIC);Map<String,Map<String,?>> profiles=new HashMap<>();for(String name:new String[]{"tv_ui","lite-cookie-profile","lite-storage","lite-storage-access","cjs_sites_v5","shared-web-profile"})profiles.put(name,new HashMap<>(getTargetContext().getSharedPreferences(name,0).getAll()));try{
        report.put("sdk",Build.VERSION.SDK_INT).put("model",Build.MODEL);
        for(int i=0;i<6;i++)files.put("/seg"+i+".ts",read(getContext().getAssets().open("mse/seg"+i+".ts")));
        for(String name:new String[]{"fixture.mp4","fixture.mp3","fixture.flv","loud.mp3","quiet.mp3","delayed.mp3","silent.mp3"})files.put("/"+name,read(getContext().getAssets().open(name)));
        String fixtureHost="profile".equals(args.getString("mode"))?"127.33."+(1+new Random().nextInt(254))+"."+(1+new Random().nextInt(254)):"resources".equals(args.getString("mode"))?"127.11.22.33":"127.0.0.1";
        server=new ServerSocket(0,16,InetAddress.getByName(fixtureHost));new Thread(()->{while(!server.isClosed())try{Socket client=server.accept();sockets.execute(()->serve(client));}catch(Exception ignored){}},"health-balance-fixture").start();String base="http://"+fixtureHost+":"+server.getLocalPort();
        prefs.edit().putBoolean("continuous_ts_enabled",false).putBoolean("neighbor_probe_enabled",false).putBoolean("player_volume_balance",false).putString("player_engine","ijk").putString("decode_mode",args.getString("decode","auto")).putString("resolution_mode","low").putString("network_dns","ali").putBoolean("auto_update_channel_list",false).putFloat("player_volume_target",-30).putString("last_channel_snapshot_v2",new JSONObject().put("groupTitle","bootstrap").put("catalogSource",ChannelCatalog.SOURCE_CUSTOM).put("number","1").put("name","bootstrap").put("urls",new JSONArray().put("http://A/ku9/js/__health_balance_bootstrap__.js")).put("sourceIndex",0).toString()).commit();
        if("1".equals(args.getString("externalLaunch"))){
            ActivityMonitor launcher=addMonitor(MainActivity.class.getName(),null,false);Bundle phase=new Bundle();phase.putString("phase","await_main");sendStatus(0,phase);
            activity=(MainActivity)launcher.waitForActivityWithTimeout(20000);removeMonitor(launcher);check(activity!=null,"Host did not launch test Activity");
        }else activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));SystemClock.sleep(1000);
        main(()->{call("stopCurrentChannelLoad");((java.util.concurrent.atomic.AtomicInteger)field(activity,"catalogLoadGeneration")).incrementAndGet();set("remoteCatalogUrl","");set("rememberChannelSource",false);set("rememberChannelDecode",false);set("playerEngine","ijk");set("decodeMode",args.getString("decode","auto"));set("defaultDecodeMode",args.getString("decode","auto"));});
        if("epg-options".equals(args.getString("mode"))){epgOptions(base);report.put("passed",true);return;}
        if("logos".equals(args.getString("mode"))){audio.setStreamVolume(AudioManager.STREAM_MUSIC,0,0);logoChecks(base);report.put("passed",true);return;}
        if("speed".equals(args.getString("mode"))){speedChecks(base);return;}
        audio.setStreamVolume(AudioManager.STREAM_MUSIC,"1".equals(args.getString("muteFixture"))?0:Math.min(3,audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)),0);
        Channel current=channel("当前视频",base+"/live.m3u8");start(current);IMediaPlayer before=player();long position=before.getCurrentPosition();
        health=(ChannelHealth)field(activity,"channelHealth");
        if("frame-check".equals(args.getString("mode"))){
            args.putString("engine",Build.VERSION.SDK_INT<=15?"system":"ijk");args.putString("continuous",Build.VERSION.SDK_INT<=15?"true":"false");args.putString("cycles","0");args.putString("holdMs","0");preflight(base);
            main(()->((android.view.View)field(activity,"channelBar")).setVisibility(android.view.View.GONE));
            check(activity.hasWindowFocus(),"Frame check lost foreground");report.put("frameBackend",player().getClass().getSimpleName());
            Bundle phase=new Bundle();phase.putString("phase","capture_frames");sendStatus(0,phase);SystemClock.sleep(8000);
            check(ready()&&activity.hasWindowFocus(),"Frame check playback stopped");report.put("passed",true);return;
        }
        if("preflight".equals(args.getString("mode"))){preflight(base);report.put("passed",true);return;}
        if("volume".equals(args.getString("mode"))){volumeChecks(base);report.put("passed",true);return;}
        if("cancel".equals(args.getString("mode"))){cancelScans(base,current);report.put("passed",true);return;}
        if("system-volume".equals(args.getString("mode"))){
            settings(new JSONObject().put("playerEngine","system").put("playerVolumeBalance",true));start(current);
            check(player() instanceof AndroidMediaPlayer,"System player fixture fell back to IJK");SystemClock.sleep(3500);
            check(!(Boolean)field(activity,"balanceApplied")&&!(Boolean)field(activity,"balanceMeasuring"),"System player unexpectedly claimed PCM balance");
            report.put("systemPlayerBalanceUnsupported",true).put("manualSystemVolumeUsable",audio.getStreamVolume(AudioManager.STREAM_MUSIC)>0).put("passed",true);return;
        }
        if("addresses".equals(args.getString("mode"))){
            LocalControlServer control=(LocalControlServer)field(activity,"controlServer");JSONArray access=new JSONArray();
            for(String host:new String[]{"127.0.0.1","[::1]"}){
                HttpURLConnection c=(HttpURLConnection)new URL("http://"+host+":"+control.getPort()+"/index.html").openConnection();c.setConnectTimeout(2000);c.setReadTimeout(2000);
                try{int status=c.getResponseCode();check(status==200,"Management listener unavailable on "+host+" HTTP "+status);access.put(new JSONObject().put("host",host).put("status",status));}finally{c.disconnect();}
            }
            report.put("managementLoopbacks",access).put("advertised",control.getLanUrl()).put("ipv4",new JSONArray(LanAddresses.ipv4(AndroidLanAddresses.collect(activity)))).put("passed",true);return;
        }
        if("network".equals(args.getString("mode"))){network(current);report.put("passed",true);return;}
        if("neighbors".equals(args.getString("mode"))){neighbors(base);report.put("passed",true);return;}
        if("public".equals(args.getString("mode"))){publicChannels();report.put("passed",true);return;}
        if("firstFrames".equals(args.getString("mode"))){firstFrames(base);report.put("passed",true);return;}
        if("scan".equals(args.getString("mode"))){scanSpeed(base,current);report.put("passed",true);return;}
        if("lite".equals(args.getString("mode"))){litePreparse(base,current);report.put("passed",true);return;}
        if("lite-public".equals(args.getString("mode"))){litePublic(current);report.put("passed",true);return;}
        if("resources".equals(args.getString("mode"))){sharedResources(base);report.put("passed",true);return;}
        if("profile".equals(args.getString("mode"))){sharedProfile(base);report.put("passed",true);return;}
        if("neighbor-cache".equals(args.getString("mode"))){neighborCache(base,current);report.put("passed",true);return;}
        if("images".equals(args.getString("mode"))){CjsPluginRuntime.setManifestUrl(base+"/catalog.json");CjsPluginRuntime.ensureCatalog();imageBenchmark();report.put("passed",true);return;}
        if("blank-images".equals(args.getString("mode"))){blankImages(base);report.put("passed",true);return;}
        if("userscripts".equals(args.getString("mode"))){userScriptKernel(base);report.put("passed",true);return;}
        Channel[] matrix={current,channel("HTTP404",base+"/missing.m3u8"),channel("备用线路",base+"/missing.m3u8",base+"/live.m3u8"),channel("鉴权403",base+"/forbidden.m3u8"),channel("失效分片",base+"/expired-segment.m3u8"),channel("普通HLS",base+"/clear.m3u8"),channel("AES128HLS",base+"/encrypted.m3u8"),channel("MP4",base+"/fixture.mp4"),channel("MP3",base+"/fixture.mp3"),channel("FLV",base+"/fixture.flv"),channel("网页带鉴权", "webview://"+base+"/page.html"),channel("HTML伪装清单",base+"/html.m3u8"),channel("动态网页","webview://"+base+"/script.html"),channel("CJS配置","https://raw.githubusercontent.com/TvWasm/cjs/main/cmg.cjs?id=600001859"),channel("服务503",base+"/server-error.m3u8"),channel("已移除410",base+"/gone.m3u8")};
        main(()->{ChannelCatalog.GROUPS=new ChannelCatalog.Group[]{new ChannelCatalog.Group("探测矩阵",ChannelCatalog.SOURCE_CUSTOM,matrix)};set("currentGroupIndex",0);set("currentChannelIndex",0);});
        health=(ChannelHealth)field(activity,"channelHealth");health.all(ChannelCatalog.GROUPS);
        await(()->!health.scanning,140000,"Probe matrix did not finish");JSONArray probe=new JSONArray();for(Channel c:matrix)probe.put(new JSONObject().put("name",c.name).put("status",state(c)).put("source",health.source(c,-1)));report.put("probe",probe);save();
        check(player()==before&&before.isPlaying()&&before.getCurrentPosition()>position+1000,"Probes interrupted foreground playback");
        for(int i:new int[]{1,15})check(state(matrix[i])==-1,"Confirmed absent not bad: "+matrix[i].name);
        for(int i:new int[]{3,4,11,13,14})check(state(matrix[i])==0,"Uncertain source incorrectly classified: "+matrix[i].name);
        for(int i:new int[]{0,2,5,6,7,8,9,10})check(state(matrix[i])==1,"Known real media not found: "+matrix[i].name);
        check(health.source(matrix[2],-1)==1,"Backup line not selected");
        main(()->{int[] location=(int[])call("adjacentChannelLocation",new Class<?>[]{int.class,int.class,int.class},0,0,1);check(location!=null&&location[1]==2,"Navigation failed to skip confirmed absence");location=(int[])call("adjacentChannelLocation",new Class<?>[]{int.class,int.class,int.class},0,2,1);check(location!=null&&location[1]==3,"Uncertain source was incorrectly skipped");});
        report.put("foregroundStayedPlaying",true).put("backupAndUnknownNavigation",true);save();
        repaired=true;start(matrix[1]);await(()->!health.bad(matrix[1]),10000,"Real successful retry did not repair cached bad result");report.put("manualRetryRepairsBad",true);
        settings(new JSONObject().put("playerVolumeBalance",true));
        JSONObject loud=balance(channel("大声MP3",base+"/loud.mp3"),false);check(loud.getDouble("gain")<.8,"Loud source was not attenuated");
        float gain=((PlaybackVolumeBalance)field(activity,"volumeBalance")).gain;double target=(Double)field(activity,"playerVolumeTarget");audio.setStreamVolume(AudioManager.STREAM_MUSIC,Math.min(4,audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)),0);
        await(()->Math.abs((Double)field(activity,"playerVolumeTarget")-target)>.1,3500,"Manual adjustment did not update threshold");check(((PlaybackVolumeBalance)field(activity,"volumeBalance")).gain==gain,"Manual change triggered automatic gain again");report.put("manualVolumeLearns",true);
        main(()->set("playerVolumeTarget",-30d));JSONObject quiet=balance(channel("轻声MP3",base+"/quiet.mp3"),false);check(quiet.getDouble("gain")==1,"Quiet source was amplified");
        balance(channel("静音四秒后出声",base+"/delayed.mp3"),true);
        start(channel("一直静音",base+"/silent.mp3"));SystemClock.sleep(4200);check(!(Boolean)field(activity,"balanceApplied"),"All-silent track was adjusted");report.put("silenceDoesNotAdjust",true);
        balance(channel("MP3音频",base+"/fixture.mp3"),false);
        balance(channel("HLS音视频",base+"/live.m3u8"),false);
        report.put("passed",true);
    }catch(Throwable error){code=0;try{report.put("passed",false).put("failure",android.util.Log.getStackTraceString(error));}catch(Exception ignored){}}
    finally{try{if("images".equals(args.getString("mode")))snapshotBuiltinScripts(prefs);}catch(Exception ignored){}networkClosed=true;for(ServerSocket s:new ServerSocket[]{rtspEntry,rtspBridge,server})if(s!=null)try{s.close();}catch(Exception ignored){}try{if(activity!=null){main(()->activity.finish());if(Build.VERSION.SDK_INT>=17)await(()->activity.isDestroyed(),5000,"Activity did not close");SystemClock.sleep(200);}}catch(Throwable cleanup){code=0;try{report.put("passed",false).put("cleanupFailure",android.util.Log.getStackTraceString(cleanup));}catch(Exception ignored){}}sockets.shutdownNow();ChannelCatalog.GROUPS=groups;restore(prefs,saved);for(Map.Entry<String,Map<String,?>> profile:profiles.entrySet())restore(getTargetContext().getSharedPreferences(profile.getKey(),0),profile.getValue());audio.setStreamVolume(AudioManager.STREAM_MUSIC,originalVolume,0);try{report.put("preferencesRestored",saved.equals(prefs.getAll())).put("themePreferenceRestored",profiles.get("tv_ui").equals(getTargetContext().getSharedPreferences("tv_ui",0).getAll())).put("systemVolumeRestored",audio.getStreamVolume(AudioManager.STREAM_MUSIC)==originalVolume);}catch(Exception ignored){}save();result.putString("stream",report.toString());finish(code,result);}}
}
