package xiao.bu.tv;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaPlayer;
import android.os.*;
import android.view.*;
import android.widget.FrameLayout;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Synthetic media through the real main-APK kernel and Android native decoder. */
public final class LiteKernelInstrumentation extends Instrumentation {
    private volatile boolean serving=true,prepared,firstFrame;
    private volatile String error,source;
    private tv.danmaku.ijk.media.player.IMediaPlayer player;
    private boolean useIjk;
    private LiteWebPlayback session;
    private MainActivity activity;
    private SurfaceView surface;
    private final AtomicBoolean windows=new AtomicBoolean(),privateQuery=new AtomicBoolean();
    private ServerSocket server;
    private ExecutorService peers=Executors.newFixedThreadPool(4);
    public void onCreate(Bundle args){super.onCreate(args);start();}
    private byte[] asset(String path)throws IOException {
        try(InputStream in=getContext().getAssets().open(path);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] block=new byte[16384];int n;while((n=in.read(block))!=-1)out.write(block,0,n);return out.toByteArray();
        }
    }
    private void serve(Socket socket){
        try(Socket s=socket){
            s.setSoTimeout(3000);BufferedReader in=new BufferedReader(new InputStreamReader(s.getInputStream(),"UTF-8"));String request=in.readLine(),line,ua="";
            String path=request.split(" ")[1];while((line=in.readLine())!=null&&!line.isEmpty())if(line.toLowerCase(java.util.Locale.US).startsWith("user-agent:"))ua=line;
            if(ua.contains("Windows NT 10.0; Win64; x64"))windows.set(true);
            if(path.contains("ujs="))privateQuery.set(true);
            byte[] bytes;String type="text/html; charset=utf-8";
            if(path.equals("/unknown-media"))bytes="finite-unknown-length-body".getBytes("UTF-8");
            else if(path.startsWith("/selection"))bytes=("<!doctype html><html><body><button id='go'>Play</button><video id='v'></video><script>if(navigator.platform!=='Win32')throw Error('Windows platform');document.getElementById('go').onclick=function(){document.getElementById('v').src='/stream.m3u8';};</script></body></html>").getBytes("UTF-8");
            else if(path.startsWith("/stream.m3u8")){StringBuilder hls=new StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n");for(int i=0;i<7;i++)hls.append("#EXTINF:2,\n/mse/seg").append(i).append(".ts\n");hls.append("#EXT-X-ENDLIST\n");bytes=hls.toString().getBytes("UTF-8");type="application/vnd.apple.mpegurl";}
            else {String name=path.substring(1).split("\\?",2)[0];if(!name.startsWith("mse/")||name.contains(".."))throw new IOException("Fixture route");bytes=asset(name);if(name.endsWith(".ts"))type="video/mp2t";else if(name.endsWith(".mp4")||name.endsWith(".m4s"))type="video/mp4";}
            s.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: "+type+"\r\n"+(path.endsWith(".ts")||path.equals("/unknown-media")?"":"Content-Length: "+bytes.length+"\r\n")+"Connection: close\r\n\r\n").getBytes("UTF-8"));s.getOutputStream().write(bytes);
        }catch(Exception ignored){ }
    }
    private void await(java.util.concurrent.Callable<Boolean> check,int milliseconds,String label)throws Exception {
        long end=SystemClock.uptimeMillis()+milliseconds;while(SystemClock.uptimeMillis()<end){if(error!=null)throw new AssertionError(label+": "+error);if(check.call())return;Thread.sleep(50);}throw new AssertionError(label+" timed out");
    }
    private void awaitPlayback(String label)throws Exception {
        await(()->prepared&&firstFrame&&player.isPlaying(),20000,label+" first frame");
        long observed=0,lastProgressWall=SystemClock.uptimeMillis(),lastPosition=player.getCurrentPosition(),limit=lastProgressWall+25000;
        while(observed<10000&&SystemClock.uptimeMillis()<limit){
            if(error!=null)throw new AssertionError(label+": "+error);
            Thread.sleep(100);long wall=SystemClock.uptimeMillis(),position=player.getCurrentPosition(),delta=position-lastPosition;
            // Some MediaPlayer implementations publish position in batches.
            // Count each real positive advance against elapsed time since the
            // previous advance, rather than discarding the intervening polls.
            if(player.isPlaying()&&delta>0&&delta<1500){observed+=Math.min(delta,wall-lastProgressWall);lastProgressWall=wall;}
            else if(!player.isPlaying()||delta<0||delta>=1500)lastProgressWall=wall;
            lastPosition=position;
        }
        if(observed<10000)throw new AssertionError(label+" observed playback only "+observed+" ms");
    }
    private void open(String url,String script){
        prepared=firstFrame=false;error=source=null;
        session=new LiteWebPlayback(activity,new LiteWebPlayback.Host(){
            public void status(String value){ }
            public void failure(String value){error=value;}
            public void stream(String url,boolean temporary){
                source=url;
                try{if(player!=null)player.release();if(useIjk){tv.danmaku.ijk.media.player.IjkMediaPlayer ijk=new tv.danmaku.ijk.media.player.IjkMediaPlayer();ijk.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_PLAYER,"mediacodec",1);player=ijk;}else player=new tv.danmaku.ijk.media.player.AndroidMediaPlayer();player.setDisplay(surface.getHolder());player.setOnPreparedListener(p->{prepared=true;p.start();});player.setOnInfoListener((p,w,e)->{if(w==MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START)firstFrame=true;return false;});player.setOnErrorListener((p,w,e)->{error=w+"/"+e;return true;});player.setDataSource(url);player.prepareAsync();}catch(Exception e){error=e.toString();}
            }
            public boolean playing(){return prepared&&player!=null&&player.isPlaying();}
            public void setPaused(boolean paused){if(prepared&&player!=null){if(paused&&player.isPlaying())player.pause();else if(!paused&&!player.isPlaying())player.start();}}
            public long position(){return player.getCurrentPosition();}
            public long duration(){return player.getDuration();}
            public int width(){return player.getVideoWidth();}
            public int height(){return player.getVideoHeight();}
        });session.open(url,script);
    }
    public void onStart(){Bundle output=new Bundle();int status=-1;
        try{
            server=new ServerSocket(0,4,InetAddress.getByName("127.0.0.1"));new Thread(()->{while(serving)try{Socket s=server.accept();peers.execute(()->serve(s));}catch(Exception ignored){ }},"lite-main-fixture").start();
            // A saved channel can start drawing before startActivitySync sees
            // an idle queue on emulators. Observe creation with a bounded wait,
            // then pause that player before adding the fixture's own surface.
            ActivityMonitor monitor=addMonitor(MainActivity.class.getName(),null,false);
            try{
                getTargetContext().startActivity(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                activity=(MainActivity)monitor.waitForActivityWithTimeout(10000);
                if(activity==null)throw new IOException("Main activity creation timed out");
            }finally{removeMonitor(monitor);}
            runOnMainSync(()->{
                try{for(java.lang.reflect.Field f:MainActivity.class.getDeclaredFields())if(tv.danmaku.ijk.media.player.IMediaPlayer.class.isAssignableFrom(f.getType())){f.setAccessible(true);Object p=f.get(activity);if(p!=null)((tv.danmaku.ijk.media.player.IMediaPlayer)p).pause();}}catch(Exception ignored){ }
                activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON|WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);surface=new SurfaceView(activity);((FrameLayout)activity.findViewById(android.R.id.content)).addView(surface,new FrameLayout.LayoutParams(-1,-1));
            });
            await(()->surface.getHolder().getSurface().isValid(),5000,"Actual native surface");
            if(BuildConfig.NETWORK_WARMUP_CHECKS)ChannelWarmupChecks.run(this,activity,(FrameLayout)activity.findViewById(android.R.id.content));
            String base="http://127.0.0.1:"+server.getLocalPort();
            HlsProxyServer proxy=new HlsProxyServer();
            try{proxy.start();HttpURLConnection response=(HttpURLConnection)new URL(proxy.mediaUrl(base+"/unknown-media")).openConnection();response.setReadTimeout(5000);try{if(!"chunked".equalsIgnoreCase(response.getHeaderField("Transfer-Encoding")))throw new AssertionError("Unknown media response has no explicit EOF");try(InputStream body=response.getInputStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){int b;while((b=body.read())!=-1)bytes.write(b);if(!bytes.toString("UTF-8").equals("finite-unknown-length-body"))throw new AssertionError("Unknown media response changed");}}finally{response.disconnect();}}finally{proxy.close();}
            runOnMainSync(()->open(base+"/selection?keep=1&ujs="+android.util.Base64.encodeToString("document.getElementById('go').click();".getBytes(java.nio.charset.Charset.forName("UTF-8")),android.util.Base64.NO_WRAP),""));
            awaitPlayback("Selected HTML to relay to system native video");
            final tv.danmaku.ijk.media.player.IMediaPlayer retained=player;final String retainedSource=source;
            runOnMainSync(()->session.open(base+"/mse/init.mp4",""));Thread.sleep(500);
            if(player!=retained||!retainedSource.equals(source)||session.finalHandoffs()!=1||!player.isPlaying())throw new AssertionError("Late page navigation interrupted the final native owner");
            final SniffedMediaProbe resourceProbe=new SniffedMediaProbe();final SniffedMediaProbe.Result[] initResult={null};
            resourceProbe.submit(base+"/mse/init.mp4",base,"nTv fixture","",value->initResult[0]=value);
            try{await(()->initResult[0]!=null,10000,"Initialization MP4 resource validation");if(!"fragment".equals(initResult[0].type)||"ready".equals(initResult[0].status))throw new AssertionError("Initialization-only MP4 accepted as a whole movie");}finally{resourceProbe.close();}
            if(!windows.get()||privateQuery.get())throw new AssertionError("Windows UA or private selection query handling");
            String first=source;runOnMainSync(()->{session.close();session=null;player.release();player=null;});Thread.sleep(300);
            try{HttpURLConnection closed=(HttpURLConnection)new URL(first).openConnection();closed.setConnectTimeout(1000);closed.setReadTimeout(1000);int code=closed.getResponseCode();closed.disconnect();throw new AssertionError("Closed channel relay still responds "+code);}catch(IOException expected){ }
            useIjk=true;runOnMainSync(()->open(base+"/selection","document.getElementById('go').click();"));awaitPlayback("IJK native chunked TS");
            runOnMainSync(()->{session.close();session=null;player.release();player=null;});
            runOnMainSync(()->open(base+"/mse/long/startup.html",""));awaitPlayback("IJK native progress lets the page append later MSE fragments");
            File folder=new File(getTargetContext().getExternalFilesDir(null),"lite-kernel-test");folder.mkdirs();Bitmap screenshot=getUiAutomation().takeScreenshot();if(screenshot!=null)try(OutputStream out=new FileOutputStream(new File(folder,"mse-native.png"))){screenshot.compress(Bitmap.CompressFormat.PNG,100,out);screenshot.recycle();}
            output.putString("stream","PASS main APK: unknown-length HLS proxy chunked EOF and system/IJK native chunked TS playback, Windows HTTP/navigator profile, original-page selection without leaked ujs, validated loopback handoff, native first frame and 10-second observed advancing playback for both decoders, closed-channel relay invalidation, virtual MSE native-progress feedback and later encoded appends. Synthetic fixture only.\n");
        }catch(Throwable failure){status=0;output.putString("stream",android.util.Log.getStackTraceString(failure));}
        finally{serving=false;try{if(server!=null)server.close();}catch(Exception ignored){ }peers.shutdownNow();runOnMainSync(()->{if(session!=null)session.close();if(player!=null)player.release();if(activity!=null)activity.finish();});}
        finish(status,output);
    }
}
