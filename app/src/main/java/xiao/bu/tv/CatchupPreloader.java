package xiao.bu.tv;

import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;

/** Isolated, muted first-frame probe. It never touches the active player. */
final class CatchupPreloader {
    interface Factory { HlsProxyServer create(); }
    interface Listener { void ready(CatchupPreloader load); void failed(String reason); }
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final Factory factory;
    private final String url;
    private final long start,stop;
    private final boolean strict,direct;
    private volatile boolean closed;
    private volatile HlsProxyServer proxy;
    private Thread worker;
    private IjkMediaPlayer decoder;
    private SurfaceTexture texture;
    private Surface surface;
    private final Runnable timeout=()->fail("回看加载超时");
    CatchupPreloader(Factory factory,String url,long start,long stop,boolean strict,boolean direct,Listener listener){
        this.factory=factory;this.url=url;this.start=start;this.stop=stop;this.strict=strict;this.direct=direct;this.listener=listener;
    }
    void start(){
        if(strict&&direct){fail("此线路未提供可核验的回看时间");return;}
        main.postDelayed(timeout,25000);
        worker=new Thread(()->{
            HlsProxyServer candidate=null;
            try{
                candidate=factory.create();
                candidate.setCatchupWindow(start,stop,strict,(state,actual,error)->{if(!error.isEmpty())main.post(()->fail(error));});
                synchronized(this){
                    if(closed){candidate.close();return;}
                    proxy=candidate;
                    // Publish only after binding, so cancellation cannot close a
                    // server before start() reopens its socket.
                    candidate.start();
                }
                if(!direct)candidate.prepareManifest(url);
                HlsProxyServer prepared=candidate;
                main.post(()->openDecoder(prepared));
            }catch(Exception error){String reason=error.getMessage();main.post(()->fail(reason==null?"回看加载失败":reason));}
        },"catchup-preload");worker.start();
    }
    private void openDecoder(HlsProxyServer prepared){
        if(closed)return;
        try{
            AppLog.loadIjkLibraries();decoder=new IjkMediaPlayer();
            decoder.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER,"mediacodec",0);
            decoder.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC,"threads",2);
            decoder.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT,"live_start_index",0);
            decoder.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT,"timeout",10000000);
            decoder.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT,"rw_timeout",10000000);
            decoder.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER,"start-on-prepared",0);
            decoder.setVolume(0,0);
            texture=new SurfaceTexture(0);surface=new Surface(texture);decoder.setSurface(surface);
            decoder.setOnPreparedListener(player->{if(!closed)player.start();});
            decoder.setOnInfoListener((player,what,extra)->{
                if(!closed&&(what==3||what==10002&&player.getVideoWidth()<=0)){
                    main.removeCallbacks(timeout);listener.ready(this);
                }
                return false;
            });
            decoder.setOnErrorListener((player,what,extra)->{fail("回看解码失败（"+what+"/"+extra+"）");return true;});
            decoder.setOnCompletionListener(player->{if(!closed)fail("回看未输出画面或声音");});
            decoder.setDataSource(direct?prepared.mediaUrl(url):prepared.playerHlsUrl(url));decoder.prepareAsync();
        }catch(Exception|LinkageError error){fail("回看播放器加载失败："+error.getMessage());}
    }
    private void fail(String reason){if(closed)return;close();listener.failed(reason);}
    HlsProxyServer takeProxy(){HlsProxyServer result=proxy;proxy=null;close();return result;}
    void close(){
        synchronized(this){if(closed)return;closed=true;}
        main.removeCallbacks(timeout);if(worker!=null)worker.interrupt();
        IjkMediaPlayer old=decoder;decoder=null;Surface oldSurface=surface;surface=null;SurfaceTexture oldTexture=texture;texture=null;
        HlsProxyServer oldProxy=proxy;proxy=null;
        new Thread(()->{
            // release() detaches its native window. A separate setSurface(null)
            // can fail during teardown and must never prevent decoder release.
            if(old!=null)try{old.release();}catch(RuntimeException ignored){}
            if(oldSurface!=null)try{oldSurface.release();}catch(RuntimeException ignored){}
            if(oldTexture!=null)try{oldTexture.release();}catch(RuntimeException ignored){}
            if(oldProxy!=null)oldProxy.close();
        },"catchup-preload-release").start();
    }
}
