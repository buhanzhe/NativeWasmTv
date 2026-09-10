package xiao.bu.tv;

import android.content.*;
import android.media.*;
import android.os.*;
import android.util.Log;
import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.util.concurrent.locks.LockSupport;

/** Shell-only decoder/encoder experiment. Never included in release APKs. */
public final class CastLatencyProbeReceiver extends BroadcastReceiver {
    private static volatile Thread decoderThread;
    private static volatile boolean decoding;
    private static boolean clockStarted;
    private static LocalControlServer routeAlias;
    private static final String TAG = "NtvCastLatency";
    static Object field(Object owner, String name) throws Exception {
        Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);
    }
    static Object invoke(Object owner,String name,Class<?>[] types,Object... args) throws Exception {
        Method m=owner.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(owner,args);
    }
    @Override public void onReceive(Context context, Intent intent) {
        try {
            startClock();
            String action=intent.getStringExtra("mode");
            MainActivity owner=CastKeepAliveService.localInputOwner();
            if(owner==null) throw new IllegalStateException("Open MainActivity first");
            WebViewCastManager manager=(WebViewCastManager)field(owner,"webViewCastManager");
            if("reassociate".equals(action)) {
                android.net.wifi.WifiManager wifi=(android.net.wifi.WifiManager)context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                wifi.disconnect();
                new Handler(Looper.getMainLooper()).postDelayed(() -> wifi.reconnect(), 600L);
            } else if("disable-direct".equals(action)) {
                Field f=owner.getClass().getDeclaredField("wifiDirectPermissionDenied");f.setAccessible(true);f.setBoolean(owner,true);
            } else if("route-alias".equals(action)) {
                new Thread(() -> {
                    try {
                        LocalControlServer current=(LocalControlServer)field(owner,"controlServer");
                        if(routeAlias==null) { routeAlias=new LocalControlServer((LocalControlServer.Listener)field(current,"listener"));routeAlias.start(); }
                        RemoteCatalogClient client=(RemoteCatalogClient)field(owner,"remoteCatalogClient");
                        String receiver=(String)field(owner,"remoteReceiverControlUrl");
                        String oldHost=current.getLanUrlForPeer(receiver);
                        String nextHost=routeAlias.getLanUrlForPeer(receiver);
                        RemoteCatalogClient.TakeoverStateProvider provider=(RemoteCatalogClient.TakeoverStateProvider)invoke(owner,"takeoverStateProvider",new Class<?>[0]);
                        boolean success=client.switchReceiverRoute(receiver,oldHost,nextHost,client.activeTakeoverSessionId(),provider);
                        Log.i(TAG,"ROUTE_ALIAS result="+success+" old="+oldHost+" new="+nextHost);
                    }catch(Exception e) {Log.e(TAG,"ROUTE_ALIAS failed",e);}
                },"route-alias-test").start();
            } else if("clock".equals(action)) {
                Log.i(TAG,"CLOCK mono="+System.nanoTime()/1000L+" wall="+System.currentTimeMillis());
            } else if("receive".equals(action)) {
                Channel channel=(Channel)invoke(owner,"currentChannel",new Class<?>[0]);
                invoke(owner,"startResolvedPlayer",new Class<?>[]{Channel.class,String.class,boolean.class,String.class},
                        channel,intent.getStringExtra("url"),true,intent.getStringExtra("transport"));
            } else if("stop".equals(action)) {
                decoding=false;
                if(decoderThread!=null) decoderThread.join(1000);
                if(manager!=null) manager.stop();
                invoke(owner,"endCastUiDrawing",new Class<?>[0]);
            } else if("decode".equals(action)) {
                decoding=false;
                if(decoderThread!=null)decoderThread.join(1000);
                invoke(owner,"releasePlayer",new Class<?>[0]);
                WebSourceView web=(WebSourceView)field(owner,"webSourceView");
                if(web!=null)web.hideForStreamPlayback();
                final int fps=intent.getIntExtra("fps",30);
                manager.start(new CastConfig("",1280,720,fps,intent.getIntExtra("bitrate",4000000),false,
                        intent.getStringExtra("codec"),intent.getStringExtra("transport")),null,true);
                manager.setVideoSize(1280,720,1,1);
                invoke(owner,"beginCastUiDrawing",new Class<?>[0]);
                final android.view.Surface surface=manager.videoInputSurface();
                final String path=intent.getStringExtra("path");
                decoding=true;
                decoderThread=new Thread(new Runnable(){public void run(){decode(path,surface,fps);}},"cast-source-probe");
                decoderThread.start();
            }
            setResultCode(1);
        } catch(Exception e){Log.e(TAG,"PROBE FAILED",e);setResultCode(-1);}
    }
    private static synchronized void startClock() {
        if(clockStarted)return;clockStarted=true;
        new Thread(new Runnable(){public void run(){
            try(java.net.ServerSocket server=new java.net.ServerSocket(18894,8,java.net.InetAddress.getByName("127.0.0.1"))){
                while(true)try(java.net.Socket socket=server.accept()){
                    socket.setSoTimeout(2000);
                    java.io.BufferedReader reader=new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream()));
                    String line;while((line=reader.readLine())!=null && !line.isEmpty()){}
                    String body="{\"monoUs\":"+(System.nanoTime()/1000L)+",\"bootUs\":"+(SystemClock.elapsedRealtimeNanos()/1000L)+"}";
                    byte[] data=body.getBytes("UTF-8");
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Length: "+data.length+"\r\n\r\n").getBytes("US-ASCII"));
                    socket.getOutputStream().write(data);
                }catch(Exception ignored){}
            }catch(Exception e){Log.e(TAG,"CLOCK FAILED",e);}
        }},"cast-clock-probe").start();
    }

    private static void decode(String path,android.view.Surface surface,int fps) {
        MediaExtractor extractor=new MediaExtractor();MediaCodec codec=null;
        try {
            extractor.setDataSource(path);MediaFormat format=null;
            for(int i=0;i<extractor.getTrackCount();i++){
                MediaFormat f=extractor.getTrackFormat(i);
                if(f.getString(MediaFormat.KEY_MIME).startsWith("video/")){format=f;extractor.selectTrack(i);break;}
            }
            if(format==null)throw new IllegalArgumentException("No video track");
            codec=MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            final long originPts=extractor.getSampleTime();
            Log.i(TAG,"SOURCE codec="+codec.getName()+" fps="+fps+" origin="+originPts+" path="+path);
            codec.configure(format,surface,null,0);codec.start();
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();boolean eos=false;
            long base=0,lastSlot=-1;android.util.LongSparseArray<Long> inputs=new android.util.LongSparseArray<Long>();
            while(decoding){
                if(!eos){int in=codec.dequeueInputBuffer(0);if(in>=0){
                    ByteBuffer b=codec.getInputBuffer(in);int size=extractor.readSampleData(b,0);long pts=extractor.getSampleTime();
                    if(size<0){eos=true;codec.queueInputBuffer(in,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);}
                    else{inputs.put(pts,System.nanoTime()/1000L);codec.queueInputBuffer(in,0,size,pts,0);extractor.advance();}
                }}
                int out=codec.dequeueOutputBuffer(info,1000);
                if(out>=0){
                    long decoded=System.nanoTime()/1000L,pts=info.presentationTimeUs;
                    if((info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0){codec.releaseOutputBuffer(out,false);break;}
                    long slot=(pts*fps+999)/1000000;
                    if(base==0)base=System.nanoTime()+100000000L-pts*1000L;
                    boolean show=slot!=lastSlot;lastSlot=slot;
                    if(show){
                        long due=base+pts*1000L;
                        while(decoding && System.nanoTime()<due-1000000L)LockSupport.parkNanos(Math.min(2000000L,due-System.nanoTime()));
                        if(!decoding){codec.releaseOutputBuffer(out,false);break;}
                        Long input=inputs.get(pts);
                        Log.i(TAG,"SOURCE_FRAME frame="+Math.round((pts-originPts)*60d/1000000d)+" media="+pts+" input="+input
                                +" decoded="+decoded+" release="+System.nanoTime()/1000L+" video="+due/1000L);
                        codec.releaseOutputBuffer(out,due);
                    }else codec.releaseOutputBuffer(out,false);
                    inputs.remove(pts);
                }
            }
        }catch(Exception e){Log.e(TAG,"SOURCE FAILED",e);}
        finally{if(codec!=null){try{codec.stop();}catch(Exception ignored){}codec.release();}extractor.release();}
    }
}
