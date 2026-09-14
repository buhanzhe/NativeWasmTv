package xiao.bu.tv;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.widget.FrameLayout;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Real app Ku9/WebView + NetworkClient diagnosis, without editing the channel list. */
public final class JxntvInstrumentation extends Instrumentation {
    private Ku9ScriptResolver resolver;
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle report = new Bundle(); StringBuilder output = new StringBuilder(); int status = -1;
        try {
            if ((getTargetContext().getApplicationInfo().flags & 2) == 0)
                throw new IllegalStateException("Requires matching debug APKs");
            final MainActivity activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            runOnMainSync(() -> resolver = new Ku9ScriptResolver(activity,
                    (FrameLayout) activity.findViewById(android.R.id.content)));
            int sequence = 100;
            for (String id : new String[]{"jxtv1", "jxtv2", "jxtv3", "jxtv5", "jxtv6", "jxtv7", "jxtv8", "tcpd"}) {
                final int request = sequence++;
                final CountDownLatch done = new CountDownLatch(1);
                final Ku9ScriptResolver.Result[] result = {null}; final String[] error = {null};
                runOnMainSync(() -> resolver.resolve(request, "Jiangxi test",
                        "https://raw.githubusercontent.com/TvWasm/cjs/main/k-web/ku9/js/jxntv.js?id=" + id,
                        new Ku9ScriptResolver.Callback() {
                            public void onResolved(int r, Ku9ScriptResolver.Result value) { result[0]=value; done.countDown(); }
                            public void onFailed(int r, String reason) { error[0]=reason; done.countDown(); }
                        }));
                if (!done.await(40, TimeUnit.SECONDS)) throw new Exception("Ku9 timeout");
                if (error[0]!=null) throw new Exception(id+": "+error[0]);
                JSONObject headers = new JSONObject().put("Referer",result[0].referer)
                        .put("User-Agent",result[0].userAgent);
                JSONObject response = new JSONObject(Ku9HttpClient.requestJson(result[0].url,"GET",headers.toString(),"",true,65536));
                output.append(id).append(" playlist=").append(response.optInt("code"))
                        .append(" referer=").append(result[0].referer).append(" error=").append(response.optString("error")).append('\n');
                if (!response.optString("body").startsWith("#EXTM3U")) throw new Exception("Not HLS: "+response.optString("body").substring(0,Math.min(100,response.optString("body").length())));
                for (String line:response.getString("body").split("\n")) {
                    if (line.trim().length()==0 || line.startsWith("#")) continue;
                    java.net.HttpURLConnection c=NetworkClient.open(new java.net.URL(new java.net.URL(result[0].url),line.trim()));
                    c.setConnectTimeout(10000);c.setReadTimeout(10000);
                    c.setRequestProperty("Referer",result[0].referer);c.setRequestProperty("User-Agent",result[0].userAgent);
                    try { int code=c.getResponseCode(); output.append("segment=").append(code).append('\n');
                        if(code!=200) throw new Exception("Segment HTTP "+code);
                        java.io.InputStream input=c.getInputStream(); try { if(input.read()!=0x47)throw new Exception("Not TS"); } finally {input.close();}
                    } finally {c.disconnect();} break;
                }
            }
            runOnMainSync(() -> resolver.destroy());
            java.lang.reflect.Field requestField=MainActivity.class.getDeclaredField("playRequestId");
            requestField.setAccessible(true);
            java.lang.reflect.Method play=MainActivity.class.getDeclaredMethod("resolveKu9Source",Channel.class,String.class,int.class);
            play.setAccessible(true);
            final Throwable[] playError={null};
            runOnMainSync(()->{try {
                int next=requestField.getInt(activity)+1;requestField.setInt(activity,next);
                String url="https://raw.githubusercontent.com/TvWasm/cjs/main/k-web/ku9/js/jxntv.js?id=jxtv1";
                play.invoke(activity,new Channel("1","Jiangxi test","jxntv-test",url,null,null),url,next);
            }catch(Throwable error){playError[0]=error;}});
            if(playError[0]!=null)throw new Exception(playError[0]);
            java.lang.reflect.Field playerField=MainActivity.class.getDeclaredField("player");playerField.setAccessible(true);
            long limit=android.os.SystemClock.elapsedRealtime()+20000;
            boolean rendered=false;
            while(android.os.SystemClock.elapsedRealtime()<limit) {
                Thread.sleep(500);
                final boolean[] frame={false};
                runOnMainSync(()->{try {
                    tv.danmaku.ijk.media.player.IjkMediaPlayer player=(tv.danmaku.ijk.media.player.IjkMediaPlayer)playerField.get(activity);
                    frame[0]=player!=null && player.isPlaying() && player.getVideoOutputFramesPerSecond()>0;
                }catch(Throwable ignored){}});
                if(frame[0]){rendered=true;break;}
            }
            if(!rendered)throw new Exception("Production playback did not render frames");
            output.append("PASS app WebView resolver + playlists + segments + production IJK output\n");
        } catch(Throwable error) {status=0;output.append(android.util.Log.getStackTraceString(error));}
        finally {runOnMainSync(()->{if(resolver!=null)resolver.destroy();});}
        report.putString("stream",output.toString());finish(status,report);
    }
}
