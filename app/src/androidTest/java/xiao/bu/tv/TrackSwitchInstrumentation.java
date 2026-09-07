package xiao.bu.tv;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.ArrayList;
import tv.danmaku.ijk.media.player.misc.ITrackInfo;
import org.json.JSONObject;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;

/** Runs the real Activity control path against deterministic local media, not WAN streams. */
public final class TrackSwitchInstrumentation extends Instrumentation {
    private MainActivity activity;
    private Bundle arguments;
    private final StringBuilder report = new StringBuilder();
    private SharedPreferences prefs;
    private Map<String, ?> saved;

    @Override public void onCreate(Bundle args) { super.onCreate(args); arguments = args; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        int code = -1;
        try {
            Intent launch = new Intent(getTargetContext(), MainActivity.class);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity = (MainActivity) startActivitySync(launch);
            SystemClock.sleep(3000);
            prefs = activity.getSharedPreferences((String) field("PREFERENCES"), 0);
            saved = prefs.getAll();
            String url = arguments.getString("url", "http://127.0.0.1:18889/tracks.mp4");
            onMain(() -> {
                invoke("cancelPendingChannelSwitch", new Class<?>[0]);
            }, true);
            onMain(() -> {
                Channel channel = new Channel("TEST", "Track regression", "", url, null, null);
                invoke("showLoading", new Class<?>[]{String.class, String.class}, channel.name, "本地切轨测试");
                invoke("startPlayer", new Class<?>[]{Channel.class, String.class, boolean.class, boolean.class}, channel, url, false, true);
            }, false);
            waitPrepared();
            advancing("baseline");
            final ArrayList<Integer> audio = new ArrayList<Integer>(), subtitles = new ArrayList<Integer>();
            ITrackInfo[] tracks = player().getTrackInfo();
            for(int i=0;i<tracks.length;i++) {
                if(tracks[i].getTrackType()==ITrackInfo.MEDIA_TRACK_TYPE_AUDIO)audio.add(i);
                if(tracks[i].getTrackType()==ITrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT)subtitles.add(i);
            }
            if(url.endsWith(".m3u8")) {
                java.io.InputStream input=new java.net.URL(url).openStream();
                java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
                try { byte[] b=new byte[4096];int n;while((n=input.read(b))!=-1)bytes.write(b,0,n); } finally { input.close(); }
                final HlsMediaTracks.Manifest manifest=HlsMediaTracks.parseMaster(url,bytes.toString("UTF-8"));
                onMain(() -> { Field f=MainActivity.class.getDeclaredField("mediaTrackManifest");f.setAccessible(true);f.set(activity,manifest); },false);
                for(int i=0;i<manifest.subtitles.size();i++)subtitles.add(HlsMediaTracks.SUBTITLE_BASE+i);
            }
            note("audio="+audio+" subtitles="+subtitles);
            control("seek", "positionMs", 17000);
            SystemClock.sleep(1500);
            for (int i = 0; i < Integer.parseInt(arguments.getString("rounds", "3")); i++) {
                for (int index : audio) {
                    if(index==Integer.parseInt(arguments.getString("unsupported", "-1"))) {
                        int before=player().getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_AUDIO);
                        boolean rejected=false;
                        try { control("audioTrack", "index", index); } catch(Exception expected) { rejected=true; }
                        check(rejected,"Unsupported audio accepted");
                        check(player().getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_AUDIO)==before,"Original audio was lost");
                        advancing("unsupported audio preserved "+before);
                        continue;
                    }
                    control("audioTrack", "index", index);
                    check(player().getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_AUDIO)==index,"Audio selection failed "+index);
                    advancing("audio " + index + " round " + i);
                }
                control("subtitleEnabled", "enabled", false);
                advancing("subtitles off");
                control("subtitleEnabled", "enabled", true);
                advancing("subtitles on");
                for(int index:subtitles) {
                    control("subtitleTrack", "index", index);
                    control("subtitleTrack", "index", index); // duplicate UI event must be harmless
                    advancing("subtitle "+index);
                    if(index<HlsMediaTracks.SUBTITLE_BASE)
                        check(player().getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT)==index,"Subtitle selection failed");
                    boolean visible=false;
                    for(int n=0;n<12;n++) {
                        final boolean[] value={false};
                        onMain(()->value[0]=((android.widget.TextView)activity.findViewById(R.id.subtitle_text)).length()>0,false);
                        if(value[0]){visible=true;break;}
                        SystemClock.sleep(250);
                    }
                    check(visible,"No rendered subtitle "+index);
                }
            }
            control("toggle", null, null);
            long paused = player().getCurrentPosition();
            control("audioTrack", "index", audio.get(0));
            SystemClock.sleep(1800);
            check(!player().isPlaying(), "Paused switch resumed playback");
            check(Math.abs(player().getCurrentPosition() - paused) < 1600, "Paused position lost");
            control("toggle", null, null);
            advancing("resume after paused audio switch");
            if (!arguments.containsKey("unsupported")) {
                for(int i=0;i<12;i++)control("audioTrack","index",audio.get(i%audio.size()));
                advancing("12 rapid audio switches");
                if (!url.endsWith(".m3u8")) checkRecoveryReopen(url);
            }
            checkOverlay();
            note("PASS");
        } catch (Throwable error) {
            code = 1;
            note("FAIL " + Log.getStackTraceString(error));
        } finally {
            // Only restore preferences touched by this test; don't replace the channel database.
            if (prefs != null && saved != null) {
                SharedPreferences.Editor edit = prefs.edit();
                for (String key : prefs.getAll().keySet()) {
                    if (key.startsWith("media_") || key.startsWith("subtitle")) {
                        Object value = saved.get(key);
                        if (value == null) edit.remove(key);
                        else if (value instanceof String) edit.putString(key, (String)value);
                        else if (value instanceof Boolean) edit.putBoolean(key, (Boolean)value);
                        else if (value instanceof Integer) edit.putInt(key, (Integer)value);
                    }
                }
                edit.commit();
            }
        }
        result.putString("stream", report.toString());
        finish(code, result);
    }

    private void checkRecoveryReopen(String url) throws Exception {
        final long position=player().getCurrentPosition();
        final int[] tracks={player().getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_AUDIO),
                player().getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_VIDEO),
                player().getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT)};
        onMain(() -> {
            invoke("startIjkPlayer",new Class<?>[]{Channel.class,String.class,boolean.class,boolean.class,int[].class},
                    field("activePlayerChannel"),url,false,true,tracks);
            for(String name:new String[]{"trackResumePlayer","trackResumePosition","trackResumePlaying"}) {
                Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);
                f.set(activity,name.equals("trackResumePlayer")?player():name.equals("trackResumePosition")?position:true);
            }
        },false);
        waitPrepared();
        SystemClock.sleep(1500);
        check(player().getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_AUDIO)==tracks[0],"Recovery lost audio choice");
        check(player().getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT)==tracks[2],"Recovery lost subtitle choice");
        check(Math.abs(player().getCurrentPosition()-position)<3500,"Recovery lost position");
        advancing("recovery reopen at "+position);
    }

    private void checkOverlay() throws Exception {
        onMain(() -> {
            Field field = MainActivity.class.getDeclaredField("showDebugInfo"); field.setAccessible(true); field.set(activity, true);
            invoke("applyDebugInfoVisibility", new Class<?>[0]);
            invoke("showLoading", new Class<?>[]{String.class, String.class}, "Track regression", "正在连接视频 · 线路 1/2");
        }, false);
        SystemClock.sleep(500);
        final int[] bottom = new int[2];
        onMain(() -> {
            View bar = activity.findViewById(R.id.channel_bar), debug = activity.findViewById(R.id.debug_info_overlay);
            bottom[0] = bar.getBottom(); bottom[1] = debug.getTop();
        }, false);
        check(bottom[0] + 4 <= bottom[1], "Overlay overlaps: " + bottom[0] + " / " + bottom[1]);
        note("overlay gap=" + (bottom[1] - bottom[0]));
        android.graphics.Bitmap screenshot = getUiAutomation().takeScreenshot();
        if(screenshot!=null) {
            java.io.File file=new java.io.File(getTargetContext().getExternalFilesDir(null),"track-overlay.png");
            java.io.FileOutputStream out=new java.io.FileOutputStream(file);
            try { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out); }
            finally { out.close();screenshot.recycle(); }
            note("screenshot="+file);
        }
    }

    private void advancing(String label) throws Exception {
        waitPrepared();
        IjkMediaPlayer p = player();
        long first = p.getCurrentPosition();
        float fps = 0;
        for (int i=0;i<8;i++) {
            SystemClock.sleep(500);
            fps = Math.max(fps, p.getVideoOutputFramesPerSecond());
            if (i >= 3 && p.getCurrentPosition() >= first + 650 && fps >= 5) {
                note(label + " position=" + first + "->" + p.getCurrentPosition() + " fps=" + fps); return;
            }
        }
        throw new AssertionError(label + " stalled position=" + first + "->" + p.getCurrentPosition() + " fps=" + fps);
    }
    private void waitPrepared() throws Exception {
        for (int i=0;i<80;i++) {
            if (Boolean.TRUE.equals(field("prepared")) && player()!=null) return;
            SystemClock.sleep(150);
        }
        throw new AssertionError("prepare timeout");
    }
    private void control(String action, String key, Object value) throws Exception {
        JSONObject request = new JSONObject().put("action", action);
        if (key!=null) request.put(key,value);
        onMain(() -> invoke("applyMediaControl", new Class<?>[]{String.class,JSONObject.class},action,request), false);
    }
    private IjkMediaPlayer player() throws Exception { return (IjkMediaPlayer)field("player"); }
    private Object field(String name) throws Exception {
        Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity);
    }
    private Object invoke(String name, Class<?>[] types, Object... values) throws Exception {
        Method m=MainActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(activity,values);
    }
    private interface Work { void run() throws Exception; }
    private void onMain(Work work, boolean optional) throws Exception {
        final Exception[] failure={null};
        runOnMainSync(() -> { try { work.run(); } catch(Exception error) { failure[0]=error; } });
        if(failure[0]!=null&&!optional) throw failure[0];
    }
    private static void check(boolean value, String message) { if(!value)throw new AssertionError(message); }
    private void note(String text) { report.append(text).append('\n');Log.i("nTvTrackTest",text); }
}
