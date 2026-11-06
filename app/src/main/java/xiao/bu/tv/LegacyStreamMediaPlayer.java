package xiao.bu.tv;

import android.content.Context;
import android.net.Uri;
import android.view.Surface;
import android.view.SurfaceHolder;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import tv.danmaku.ijk.media.player.AndroidMediaPlayer;

/** Existing system-player callbacks with an API14/15 native TS push source. */
final class LegacyStreamMediaPlayer extends AndroidMediaPlayer {
    private volatile LegacyStreamPlayer stream;
    private volatile HttpURLConnection connection;
    private volatile boolean released,playing;
    private volatile int width,height;
    private float left=1,right=1;
    private String address;
    private Surface output;
    private SurfaceHolder holder;
    private volatile int generation;
    private final MediaPositionClock clock=new MediaPositionClock();
    @Override public void setDisplay(SurfaceHolder value){holder=value;output=value==null?null:value.getSurface();}
    @Override public void setSurface(Surface value){output=value;}
    @Override public void setScreenOnWhilePlaying(boolean enabled){if(holder!=null)holder.setKeepScreenOn(enabled);}
    @Override public void setDataSource(String value){address=value;}
    @Override public void setDataSource(Context context,Uri uri){address=uri.toString();}
    @Override public void setDataSource(Context context,Uri uri,Map<String,String> headers){address=uri.toString();}
    @Override public String getDataSource(){return address;}
    @Override public void prepareAsync()throws IllegalStateException {
        if(released||address==null||output==null||!output.isValid())throw new IllegalStateException("TS player requires a source and Surface");
        final int token=++generation;
        Thread worker=new Thread(()->{
            HttpURLConnection opened=null;LegacyStreamPlayer next=null;
            try {
                opened=(HttpURLConnection)new URL(address).openConnection();opened.setConnectTimeout(10000);opened.setReadTimeout(15000);
                connection=opened;java.io.InputStream input=opened.getInputStream();
                synchronized(this){
                    if(released||token!=generation){opened.disconnect();return;}
                    next=new LegacyStreamPlayer(output,new LegacyStreamPlayer.Listener(){
                        public void prepared(){if(!current(token))return;try{stream.volume(left,right);notifyOnPrepared();}catch(IOException e){failed(token,e.toString());}}
                        public void size(int w,int h){if(!current(token))return;width=w;height=h;notifyOnVideoSizeChanged(w,h,1,1);}
                        public void ended(){if(current(token)){playing=false;notifyOnCompletion();}}
                        public void error(String text){failed(token,text);}
                        public void info(int what,int extra){if(current(token))notifyOnInfo(what,extra);}
                    });stream=next;next.prepare(input);
                }
            }catch(Exception error){if(next!=null)next.close();if(opened!=null)opened.disconnect();if(current(token))AppLog.w("LegacyStreamPlayer","Native TS source open failed",error);failed(token,error.toString());}
        },"legacy-stream-open");worker.setDaemon(true);worker.start();
    }
    private boolean current(int token){return !released&&token==generation;}
    private void failed(int token,String text){
        if(!current(token))return;playing=false;AppLog.w("LegacyStreamPlayer",text);
        new android.os.Handler(android.os.Looper.getMainLooper()).post(()->{if(current(token))notifyOnError(1,-1004);});
    }
    @Override public void start(){try{if(stream!=null){stream.start();playing=true;}}catch(IOException e){failed(generation,e.toString());}}
    @Override public void pause(){try{if(stream!=null)stream.pause();playing=false;}catch(IOException e){failed(generation,e.toString());}}
    @Override public void stop(){pause();}
    @Override public boolean isPlaying(){return playing&&!released;}
    @Override public long getCurrentPosition(){
        LegacyStreamPlayer active=stream;
        try{return active==null?0:clock.position(active,0,active.position(),0,android.os.SystemClock.elapsedRealtime(),playing);}
        catch(IOException e){return 0;}
    }
    @Override public long getDuration(){return 0;}
    @Override public int getVideoWidth(){return width;}
    @Override public int getVideoHeight(){return height;}
    @Override public int getVideoSarNum(){return 1;}
    @Override public int getVideoSarDen(){return 1;}
    @Override public void seekTo(long position){notifyOnSeekComplete();} // Live resume stays at the live feed.
    @Override public void setVolume(float l,float r){left=l;right=r;try{if(stream!=null)stream.volume(l,r);}catch(IOException e){failed(generation,e.toString());}}
    @Override public void setLooping(boolean looping){}
    @Override public boolean isLooping(){return false;}
    @Override public synchronized void reset(){generation++;playing=false;if(stream!=null){stream.close();stream=null;}if(connection!=null){connection.disconnect();connection=null;}width=height=0;address=null;}
    @Override public synchronized void release(){if(released)return;released=true;reset();super.release();}
}
