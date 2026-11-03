package xiao.bu.tv;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import java.io.*;
import java.lang.reflect.*;
import org.json.*;
import tv.danmaku.ijk.media.player.IMediaPlayer;

/** Real device audit: user playlist -> parser -> automatic install -> native loop -> player. */
public final class TvbusInstrumentation extends Instrumentation {
    private Bundle args;
    private MainActivity activity;
    private Object get(String name) throws Exception { Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity); }
    private void set(String name,Object value) throws Exception { Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(activity,value); }
    @Override public void onCreate(Bundle args) { this.args=args;start(); }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            NetworkClient.initialize(getTargetContext());
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            try(InputStream in=new FileInputStream(args.getString("playlist","/data/local/tmp/tvbus-list.txt"))) {
                byte[] buffer=new byte[8192];int count;while((count=in.read(buffer))!=-1)out.write(buffer,0,count);
            }
            Method parse=PlaylistManager.class.getDeclaredMethod("parse",byte[].class);parse.setAccessible(true);
            ChannelCatalog.Group[] groups=(ChannelCatalog.Group[])parse.invoke(null,(Object)out.toByteArray());
            int count=0,tvbus=0;for(ChannelCatalog.Group group:groups)for(Channel c:group.channels){count++;if(TvbusPlugin.matches(c.url))tvbus++;}
            result.putInt("channels",count);result.putInt("tvbus",tvbus);
            if(tvbus<1)throw new AssertionError("No TVBus channels parsed");
            activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            SystemClock.sleep(2500);
            runOnMainSync(()->{try{
                ((java.util.concurrent.atomic.AtomicInteger)get("catalogLoadGeneration")).incrementAndGet();
                ChannelCatalog.GROUPS=groups;
                set("currentGroupIndex",0);set("currentChannelIndex",0);
                Method switcher=MainActivity.class.getDeclaredMethod("switchChannel",int.class);switcher.setAccessible(true);switcher.invoke(activity,0);
            }catch(Exception e){throw new RuntimeException(e);}});
            long deadline=SystemClock.elapsedRealtime()+90000;
            boolean loaded=false,rendered=false;String stream="";
            while(SystemClock.elapsedRealtime()<deadline) {
                File dir=new File(getTargetContext().getFilesDir(),"tvbus/"+BuildConfig.CJS_PLUGIN_ABI);
                File[] files=dir.listFiles((d,n)->n.endsWith(".so"));if(files!=null&&files.length>0)loaded=true;
                Object active=get("activePlayerStreamUrl");if(active!=null)stream=String.valueOf(active);
                if((Boolean)get("prepared") && stream.startsWith("http://127.0.0.1")) {
                    IMediaPlayer player=(IMediaPlayer)get("player");long position=player.getCurrentPosition();SystemClock.sleep(1500);
                    if(player.isPlaying()&&player.getCurrentPosition()>position){rendered=true;break;}
                }
                SystemClock.sleep(500);
            }
            result.putBoolean("pluginDownloaded",loaded);result.putBoolean("rendered",rendered);result.putString("stream",stream);
            // Reopen: reuse local installation, including if the upstream channel itself is offline.
            File dir=new File(getTargetContext().getFilesDir(),"tvbus/"+BuildConfig.CJS_PLUGIN_ABI);
            File[] installed=dir.listFiles((d,n)->n.endsWith(".so"));long before=installed!=null&&installed.length>0?installed[0].lastModified():-1;
            runOnMainSync(()->{try{Method m=MainActivity.class.getDeclaredMethod("switchChannel",int.class);m.setAccessible(true);m.invoke(activity,1);}catch(Exception e){throw new RuntimeException(e);}});
            SystemClock.sleep(10000);
            installed=dir.listFiles((d,n)->n.endsWith(".so"));long after=installed!=null&&installed.length>0?installed[0].lastModified():-2;
            result.putBoolean("cacheReused",before==after&&before>0);
            result.putString("result",rendered?"PASS_PLAYBACK":"PLUGIN_TESTED_SOURCE_NOT_PLAYING");
            finish(rendered?0:1,result);
        } catch(Throwable error) {
            android.util.Log.e("TvbusAudit","failed",error);result.putString("error",android.util.Log.getStackTraceString(error));finish(1,result);
        }
    }
}
