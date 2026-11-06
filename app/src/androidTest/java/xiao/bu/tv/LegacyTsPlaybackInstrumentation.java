package xiao.bu.tv;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/** Isolated API14 ARM test: actual proxy, system decoder and non-finite TS playback. */
public final class LegacyTsPlaybackInstrumentation extends Instrumentation {
    private Bundle args;
    private Activity activity;
    private MediaPlayer player;
    private LegacyStreamPlayer pushPlayer;
    private java.net.HttpURLConnection connection;
    private DirectVideoView view;
    private HlsProxyServer proxy;
    private volatile boolean prepared;
    private volatile String error="";
    private final AtomicInteger completions=new AtomicInteger();
    @Override public void onCreate(Bundle value){super.onCreate(value);args=value;start();}
    private interface Work {void run()throws Exception;}
    private void main(Work work)throws Exception {
        Throwable[] failure={null};runOnMainSync(()->{try{work.run();}catch(Throwable e){failure[0]=e;}});
        if(failure[0]!=null)throw new Exception(failure[0]);
    }
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    @Override public void onStart(){
        Bundle result=new Bundle();int code=1;
        try {
            if("true".equals(args.getString("mainChecks"))){
                Legacy404MainChecks.run(this,args);
                result.putString("stream","PASS actual MainActivity live TS and default-off neighbor probing\n");
                finish(-1,result);return;
            }
            NetworkClient.initialize(getTargetContext());
            Intent intent=new Intent();intent.setClassName(getTargetContext(),"xiao.bu.tv.WebPlaybackTestActivity");intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity=startActivitySync(intent);
            main(()->{view=new DirectVideoView(activity);activity.setContentView(view);});
            long ready=SystemClock.elapsedRealtime()+15000;
            while(!view.isSurfaceReady()&&SystemClock.elapsedRealtime()<ready)SystemClock.sleep(100);
            check(view.isSurfaceReady(),"No Surface");
            proxy=new HlsProxyServer(getTargetContext(),false,true,true,0,true,"low",0,0,0);proxy.start();
            String mode=args.getString("mode","ts");String source=args.getString("base")+"/"+args.getString("case",mode)+"/live.m3u8";
            boolean push="push".equals(mode);
            String address=push||"ts".equals(mode)?proxy.legacyTsUrl(source):proxy.playerHlsUrl(source);
            if(push){
                connection=(java.net.HttpURLConnection)new java.net.URL(address).openConnection();connection.setReadTimeout(15000);
                java.io.InputStream input=connection.getInputStream();
                main(()->{
                    pushPlayer=new LegacyStreamPlayer(view.getVideoSurface(),new LegacyStreamPlayer.Listener(){
                        public void prepared(){prepared=true;try{pushPlayer.start();}catch(Exception e){error=e.toString();}}
                        public void size(int w,int h){view.setVideoSize(w,h,1,1);}
                        public void ended(){completions.incrementAndGet();}
                        public void error(String text){error=text;}
                    });pushPlayer.prepare(input);
                });
            }else
            main(()->{
                player=new MediaPlayer();player.setDisplay(view.getVideoSurfaceHolder());player.setVolume(0,0);
                player.setOnPreparedListener(p->{prepared=true;p.start();});
                player.setOnVideoSizeChangedListener((p,w,h)->view.setVideoSize(w,h,1,1));
                player.setOnCompletionListener(p->completions.incrementAndGet());
                player.setOnErrorListener((p,w,e)->{error=w+"/"+e;return true;});
                if("ts".equals(mode))player.setDataSource(activity,android.net.Uri.parse(address),LegacyTsStream.playerHeaders());
                else player.setDataSource(address);
                player.prepareAsync();
            });
            long start=SystemClock.elapsedRealtime();
            while(!prepared&&error.isEmpty()&&SystemClock.elapsedRealtime()-start<30000)SystemClock.sleep(100);
            check(prepared&&error.isEmpty(),"Prepare failed "+error);
            long first=push?pushPlayer.position():player.getCurrentPosition();
            Bundle status=new Bundle();status.putString("stream","prepared mode="+mode+" position="+first+"\n");sendStatus(1,status);
            int seconds=Math.max(25,Math.min(180,Integer.parseInt(args.getString("seconds","35"))));
            long end=SystemClock.elapsedRealtime()+seconds*1000L;
            while(SystemClock.elapsedRealtime()<end&&error.isEmpty()&&completions.get()==0)SystemClock.sleep(250);
            long last=push?pushPlayer.position():player.getCurrentPosition(),duration=push?0:player.getDuration();
            JSONObject report=new JSONObject().put("mode",mode).put("sdk",android.os.Build.VERSION.SDK_INT)
                    .put("cores",Runtime.getRuntime().availableProcessors()).put("error",error)
                    .put("completions",completions.get()).put("positionMs",last).put("advancedMs",last-first)
                    .put("durationMs",duration).put("width",push?pushPlayer.width():player.getVideoWidth()).put("height",push?pushPlayer.height():player.getVideoHeight());
            File file=new File(getTargetContext().getExternalFilesDir(null),"legacy-"+mode+"-playback.json");
            try(FileOutputStream out=new FileOutputStream(file)){out.write(report.toString(2).getBytes("UTF-8"));}
            check(error.isEmpty()&&completions.get()==0&&last-first>seconds*800L,"Live stream failed "+report);
            check(duration<=0,"Live TS reported finite duration "+duration);
            result.putString("stream","PASS "+report+"\n");code=-1;
        }catch(Throwable failure){result.putString("stream",android.util.Log.getStackTraceString(failure));}
        finally{try{main(()->{if(player!=null)player.release();if(pushPlayer!=null)pushPlayer.close();if(activity!=null)activity.finish();});}catch(Exception ignored){}if(connection!=null)connection.disconnect();if(proxy!=null)proxy.close();}
        finish(code,result);
    }
}
