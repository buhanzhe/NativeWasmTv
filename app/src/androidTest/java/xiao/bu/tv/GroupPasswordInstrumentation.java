package xiao.bu.tv;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Actual Activity gates, remote input, six themes and local encoded-media playback. */
public final class GroupPasswordInstrumentation extends Instrumentation {
    private MainActivity activity;
    private ServerSocket server;
    private volatile boolean serving = true;
    private final ExecutorService peers = Executors.newFixedThreadPool(3);
    private final JSONArray checks = new JSONArray();
    private boolean visibilityOnly;

    @Override public void onCreate(Bundle args) { super.onCreate(args);visibilityOnly=args!=null&&"true".equals(args.getString("visibilityOnly"));start(); }
    private void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        checks.put(message);
    }
    private Object get(String name) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name); field.setAccessible(true); return field.get(activity);
    }
    private void set(String name, Object value) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name); field.setAccessible(true); field.set(activity, value);
    }
    private Object call(String name, Class<?>[] types, Object... values) throws Exception {
        Method method = MainActivity.class.getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(activity, values);
    }
    private void ui(Runnable action) {
        final Throwable[] error = {null};
        runOnMainSync(() -> { try { action.run(); } catch (Throwable failure) { error[0] = failure; } });
        if (error[0] != null) throw new AssertionError(error[0]);
    }
    private void invoke(String name, int value) {
        ui(() -> { try { call(name, new Class<?>[]{int.class}, value); } catch (Exception e) { throw new RuntimeException(e); } });
    }
    private void enterGroup(int group) {
        invoke("showChannelMenu", group);
        ui(() -> { try { call("enterChannelColumn", new Class<?>[]{}); } catch (Exception e) { throw new RuntimeException(e); } });
    }
    private void await(Callable<Boolean> condition, String message) throws Exception {
        long until = SystemClock.elapsedRealtime() + 18000;
        while (!condition.call()) {
            if (SystemClock.elapsedRealtime() > until) throw new AssertionError(message + " timed out");
            Thread.sleep(60);
        }
    }
    private GroupPasswordDialog dialog() throws Exception { return (GroupPasswordDialog) get("groupPasswordDialog"); }
    private void popup() throws Exception {
        await(() -> dialog() != null && dialog().isShowing()
                && dialog().getWindow().getDecorView().hasWindowFocus(), "focused password popup");
        waitForIdleSync();
    }
    private void closed() throws Exception { await(() -> dialog() == null, "password popup dismissal"); }
    private void digits(String value) {
        for (int i = 0; i < value.length(); i++) sendKeyDownUpSync(KeyEvent.KEYCODE_0 + value.charAt(i) - '0');
        waitForIdleSync();
    }
    private void keypad(String digit, int... directions) throws Exception {
        for (int key : directions) sendKeyDownUpSync(key);
        waitForIdleSync();
        View focused = dialog().getCurrentFocus();
        if (!(focused instanceof TextView) || !digit.contentEquals(((TextView) focused).getText()))
            throw new AssertionError("DPAD keypad focus: expected " + digit);
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
    }
    private TextView text(View view, String value) {
        if (view instanceof TextView && value.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) { TextView result = text(group.getChildAt(i), value); if (result != null) return result; }
        }
        return null;
    }
    private void ready(int group, int channel) throws Exception {
        await(() -> (int) get("currentGroupIndex") == group && (int) get("currentChannelIndex") == channel
                && (boolean) get("prepared") && (boolean) get("videoRenderingStarted"), "native frame " + group + ":" + channel);
    }
    private byte[] asset(String name) throws IOException {
        try (InputStream input = getContext().getAssets().open(name); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] block = new byte[8192]; int n; while ((n = input.read(block)) != -1) out.write(block, 0, n); return out.toByteArray();
        }
    }
    private void serve(Socket socket) {
        try (Socket peer = socket) {
            peer.setSoTimeout(3000);
            BufferedReader in = new BufferedReader(new InputStreamReader(peer.getInputStream(), "US-ASCII"));
            String first = in.readLine(), line; if (first == null) return;
            String path = first.split(" ")[1].split("\\?", 2)[0]; while ((line = in.readLine()) != null && !line.isEmpty()) { }
            byte[] body; String type;
            if (path.equals("/stream.m3u8")) {
                StringBuilder hls = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n");
                for (int i = 0; i < 7; i++) hls.append("#EXTINF:2,\n/mse/seg").append(i).append(".ts\n");
                body = hls.append("#EXT-X-ENDLIST\n").toString().getBytes("UTF-8"); type = "application/vnd.apple.mpegurl";
            } else {
                if (!path.matches("/mse/seg[0-6]\\.ts")) return;
                body = asset(path.substring(1)); type = "video/mp2t";
            }
            peer.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));
            peer.getOutputStream().write(body);
        } catch (Exception ignored) { }
    }
    private void restore(SharedPreferences preferences, Map<String, ?> values) {
        SharedPreferences.Editor editor = preferences.edit().clear();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            Object value = entry.getValue(); String key = entry.getKey();
            if (value instanceof String) editor.putString(key, (String) value);
            else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) editor.putInt(key, (Integer) value);
            else if (value instanceof Long) editor.putLong(key, (Long) value);
            else if (value instanceof Float) editor.putFloat(key, (Float) value);
            else if (value instanceof Set) editor.putStringSet(key, (Set<String>) value);
        }
        editor.commit();
    }
    @Override public void onStart() {
        if(visibilityOnly){runVisibilityChecks();return;}
        SharedPreferences preferences = getTargetContext().getSharedPreferences(MainActivity.PREFERENCES, 0);
        SharedPreferences skins = getTargetContext().getSharedPreferences("tv_ui", 0);
        Map<String, ?> saved = preferences.getAll(), savedSkin = skins.getAll();
        ChannelCatalog.Group[] original = ChannelCatalog.GROUPS;
        Bundle result = new Bundle(); int code = -1;
        File evidence = new File(getTargetContext().getExternalFilesDir(null), "group-password-evidence"); evidence.mkdirs();
        try {
            preferences.edit().putBoolean("group_password_required",false).apply();
            server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
            new Thread(() -> { while (serving) try { Socket socket = server.accept(); peers.execute(() -> serve(socket)); } catch (Exception ignored) { } }, "password-media-fixture").start();
            ActivityMonitor monitor = addMonitor(MainActivity.class.getName(), null, false);
            getTargetContext().startActivity(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            activity = (MainActivity) waitForMonitorWithTimeout(monitor, 15000); removeMonitor(monitor);
            if (activity == null) throw new AssertionError("Activity startup");
            Thread.sleep(1500);
            ui(() -> { try {
                set("startupMaintenanceDestroyed", true);
                Object loaded = get("player");
                if (loaded instanceof tv.danmaku.ijk.media.player.IMediaPlayer)
                    ((tv.danmaku.ijk.media.player.IMediaPlayer) loaded).pause();
            } catch (Exception ignored) { } });
            String url = "http://127.0.0.1:" + server.getLocalPort() + "/stream.m3u8";
            String list = "公开频道,#genre#\n公开台," + url + "?c=0\n测试频道_10086,#genre#\n6\n测试一台," + url + "?c=1\n测试二台," + url + "?c=2\n高清秒播_0709,#genre#\n高清台," + url + "?c=3\n八位密码_01234567,#genre#\n八位台," + url + "?c=4\n";
            Method parse = PlaylistManager.class.getDeclaredMethod("parse", byte[].class, String.class); parse.setAccessible(true);
            ChannelCatalog.Group[] groups = (ChannelCatalog.Group[]) parse.invoke(null, list.getBytes("UTF-8"), "");
            check(groups.length == 4 && groups[1].channels.length == 2 && groups[1].displayTitle.equals("测试频道") && groups[2].password.equals("0709") && groups[3].password.equals("01234567"), "TXT genre suffix parsed, stray numeric line ignored, 4/5/8-digit passwords and leading zero preserved");
            ui(() -> { try { set("groupPasswordRequired",false); set("rememberChannelSource",true);set("rememberChannelDecode",true); ChannelCatalog.GROUPS = groups; set("currentGroupIndex", 0); set("currentChannelIndex", 0); set("currentSourceIndex", 0); call("switchChannel", new Class<?>[]{int.class}, 0); } catch (Exception e) { throw new RuntimeException(e); } });
            ready(0,0);
            check(!(boolean)get("groupPasswordRequired"),"password checking defaults off");
            groups[0].channels[0]=groups[0].channels[0].withAdditionalUrl(url+"?c=0b");
            ui(()->{try{call("switchCustomSource",new Class<?>[]{int.class,boolean.class,String.class},1,false,"");}catch(Exception e){throw new RuntimeException(e);}});
            call("handleWebSettings",new Class<?>[]{JSONObject.class},new JSONObject().put("channelDecodeMode","software"));ready(0,0);
            check((int)get("currentSourceIndex")==1&&"software".equals(get("decodeMode")),"manual source 2 and software selected");
            ui(()->{try{set("currentGroupIndex",1);call("switchChannel",new Class<?>[]{int.class},0);}catch(Exception e){throw new RuntimeException(e);}});ready(1,0);
            check(dialog()==null&&get("decodeMode").equals(get("defaultDecodeMode")),"disabled password accepts protected group; other channel uses decoder default");
            ui(()->{try{set("currentGroupIndex",0);call("switchChannel",new Class<?>[]{int.class},0);}catch(Exception e){throw new RuntimeException(e);}});ready(0,0);
            check((int)get("currentSourceIndex")==1&&"software".equals(get("decodeMode")),"return to original channel restores source 2 and software with native frame");
            call("handleWebSettings",new Class<?>[]{JSONObject.class},new JSONObject().put("groupPasswordRequired",true));waitForIdleSync();
            ready(0, 0); Object retained = get("player"); int before = (int) get("playRequestId");
            enterGroup(1); popup(); digits("00000");
            check(dialog() != null && text(dialog().getWindow().getDecorView(), "密码错误，请重试") == null,
                    "full wrong password is not checked before confirmation");
            sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER);
            check(dialog() != null && text(dialog().getWindow().getDecorView(), "密码错误，请重试") != null, "wrong remote password leaves popup open with error");
            check(get("player") == retained && (int) get("playRequestId") == before && (int) get("currentGroupIndex") == 0, "wrong password does not replace or stop current playback");
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); closed();
            check(get("player") == retained && (int) get("playRequestId") == before, "Back cancels without a tune");
            for (String id : TvUiPlugin.IDS) {
                TvUiPlugin.select(getTargetContext(), id); waitForIdleSync();
                ui(() -> activity.onTvUiChanged());
                enterGroup(1); popup(); Thread.sleep(180);
                final GroupPasswordDialog shown = dialog();
                Bundle palette = xiao.bu.tv.skin.SharedTvUi.dialogPalette(id);
                ui(() -> {
                    View decor = shown.getWindow().getDecorView(); TextView key = text(decor, "确认");
                    check(key != null && key.hasFocus() && key.getCurrentTextColor() == palette.getInt("focusText"), id + " focus text follows channel palette");
                    check(text(decor, "请输入「测试频道」的密码") != null, id + " hides password suffix from popup");
                });
                Bitmap picture = getUiAutomation().takeScreenshot();
                if (picture == null) throw new AssertionError("Screenshot " + id);
                try (OutputStream out = new FileOutputStream(new File(evidence, id + ".png"))) { picture.compress(Bitmap.CompressFormat.PNG, 100, out); } finally { picture.recycle(); }
                sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); closed();
            }
            enterGroup(1); popup();
            keypad("1", KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_LEFT);
            keypad("0", KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT);
            keypad("0"); keypad("8", KeyEvent.KEYCODE_DPAD_UP); keypad("6", KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_RIGHT);
            check(dialog() != null && dialog().isShowing() && get("player") == retained,
                    "full correct DPAD password waits for confirmation without replacing playback");
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
            closed(); check(dialog() == null, "DPAD-only keypad enters 10086 without hardware numeric keys");
            await(() -> (int) get("browsingGroupIndex") == 1, "unlocked list entry");
            invoke("switchBrowsingChannel", 0); ready(1, 0);
            check(dialog() == null, "correct password permits actual selected-channel first frame");
            invoke("switchChannel", 1); ready(1, 1);
            invoke("startChannel", 1); ready(1, 1);
            check(dialog() == null, "same-group switch and retry do not prompt again");
            invoke("showChannelMenu", 0); enterGroup(1); popup();
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); closed();
            check((int) get("currentGroupIndex") == 1, "leaving and reentering browsing group relocks while cancelling keeps current playback");
            invoke("switchRelative", 1); popup();
            check((int) get("currentGroupIndex") == 1, "relative gesture/channel switch is gated before entering the next protected group");
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); closed();
            ui(() -> { try { call("enterNumericChannel", new Class<?>[]{int.class}, 4); call("commitNumericChannel", new Class<?>[]{}); } catch (Exception e) { throw new RuntimeException(e); } });
            popup(); check((int) get("currentGroupIndex") == 1, "global numeric selection is gated before changing playback identity");
            digits("709"); sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER);
            check(dialog() != null, "0709 is not accepted as 709"); digits("0709");
            check(dialog() != null && dialog().isShowing(), "correct four-digit password waits for confirmation");
            sendKeyDownUpSync(KeyEvent.KEYCODE_NUMPAD_ENTER); closed(); ready(2, 0);
            check(dialog() == null, "leading-zero password unlocks and reaches real native frame");
            enterGroup(3); popup();
            check(text(dialog().getWindow().getDecorView(), "请输入 8 位数字密码") != null, "eight-digit popup shows required length");
            int eightBefore = (int) get("playRequestId");
            digits("0123456");
            check(dialog() != null && dialog().isShowing() && (int) get("currentGroupIndex") == 2 && (int) get("playRequestId") == eightBefore,
                    "first seven remote digits keep current channel and popup open");
            Bitmap eightPicture = getUiAutomation().takeScreenshot();
            if (eightPicture == null) throw new AssertionError("Eight-digit screenshot");
            try (OutputStream out = new FileOutputStream(new File(evidence, "eight-digit-remote.png"))) { eightPicture.compress(Bitmap.CompressFormat.PNG, 100, out); } finally { eightPicture.recycle(); }
            digits("8");
            check(dialog() != null && text(dialog().getWindow().getDecorView(), "密码错误，请重试") == null,
                    "eighth wrong digit waits for confirmation");
            sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER);
            check(dialog() != null && text(dialog().getWindow().getDecorView(), "密码错误，请重试") != null,
                    "wrong eighth remote digit is rejected");
            digits("01234567");
            check(dialog() != null && dialog().isShowing() && (int) get("playRequestId") == eightBefore,
                    "correct eight-digit password keeps playback gated before confirmation");
            sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER); closed();
            invoke("switchBrowsingChannel", 0); ready(3, 0);
            check(dialog() == null && "".equals(get("numericChannelInput")), "eight-digit remote password with leading zero unlocks without numeric channel leakage and reaches native frame");
            ui(() -> { try { call("closeChannelList", new Class<?>[]{}); } catch (Exception e) { throw new RuntimeException(e); } });
            digits("7");
            check("7".equals(get("numericChannelInput")) && (int) get("passwordKeyUntilUp") == -1,
                    "fresh press of the final password digit resumes normal numeric channel input");
            ui(() -> { try { call("clearNumericChannelInput", new Class<?>[]{}); } catch (Exception e) { throw new RuntimeException(e); } });
            Method favoriteKey = MainActivity.class.getDeclaredMethod("favoriteKey", ChannelCatalog.Group.class, Channel.class); favoriteKey.setAccessible(true);
            String key = (String) favoriteKey.invoke(null, groups[1], groups[1].channels[0]);
            ChannelCatalog.Group favorite = new ChannelCatalog.Group("我的收藏", ChannelCatalog.SOURCE_FAVORITES,
                    new Channel[]{groups[1].channels[0].asFavorite(key, ChannelCatalog.SOURCE_CUSTOM)});
            ui(() -> ChannelCatalog.GROUPS = new ChannelCatalog.Group[]{groups[0], groups[1], groups[2], groups[3], favorite});
            invoke("showChannelMenu", 4); invoke("switchBrowsingChannel", 0); popup();
            check((int) get("currentGroupIndex") == 3, "favorite cannot bypass the original group's password");
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); closed();
            JSONObject summary = new JSONObject().put("checks", checks).put("themeScreenshots", TvUiPlugin.IDS.length).put("nativeFrames", true).put("syntheticLocalMedia", true);
            try (OutputStream out = new FileOutputStream(new File(evidence, "result.json"))) { out.write(summary.toString(2).getBytes("UTF-8")); }
            result.putString("stream", "PASS: " + summary.toString() + "\n");
        } catch (Throwable failure) {
            try {
                Bitmap picture = getUiAutomation().takeScreenshot();
                if (picture != null) {
                    try (OutputStream out = new FileOutputStream(new File(evidence, "failure.png"))) { picture.compress(Bitmap.CompressFormat.PNG, 100, out); }
                    finally { picture.recycle(); }
                }
            } catch (Exception ignored) { }
            code = 0; result.putString("stream", android.util.Log.getStackTraceString(failure));
        } finally {
            serving = false; try { if (server != null) server.close(); } catch (IOException ignored) { } peers.shutdownNow();
            if (activity != null) ui(() -> { try { if (dialog() != null) dialog().cancel(); activity.finish(); } catch (Exception ignored) { } });
            ChannelCatalog.GROUPS = original;
            restore(preferences, saved); restore(skins, savedSkin);
            try { TvUiPlugin.select(getTargetContext(), savedSkin.containsKey("selected") ? (String) savedSkin.get("selected") : "builtin"); } catch (Exception ignored) { }
        }
        finish(code, result);
    }

    private int visibleChannels() throws Exception { return ((android.widget.ListAdapter)get("channelAdapter")).getCount(); }
    private void runVisibilityChecks() {
        SharedPreferences prefs=getTargetContext().getSharedPreferences(MainActivity.PREFERENCES,0);
        SharedPreferences skins=getTargetContext().getSharedPreferences("tv_ui",0);
        Map<String,?> saved=prefs.getAll(),savedSkin=skins.getAll();
        ChannelCatalog.Group[] original=ChannelCatalog.GROUPS;
        Bundle result=new Bundle();int code=-1;
        File evidence=new File(getTargetContext().getExternalFilesDir(null),"group-password-visibility");evidence.mkdirs();
        try {
            ActivityMonitor monitor=addMonitor(MainActivity.class.getName(),null,false);
            getTargetContext().startActivity(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            activity=(MainActivity)waitForMonitorWithTimeout(monitor,15000);removeMonitor(monitor);
            if(activity==null)throw new AssertionError("Activity startup");
            Thread.sleep(1500);
            Channel[] hidden={new Channel("2","私密一台","hidden1","http://127.0.0.1:1/one.m3u8",null,null),
                    new Channel("3","私密二台","hidden2","http://127.0.0.1:1/two.m3u8",null,null)};
            ChannelCatalog.Group[] groups={new ChannelCatalog.Group("公开频道",3,new Channel[]{new Channel("1","公开台","public","http://127.0.0.1:1/public.m3u8",null,null)}),
                    new ChannelCatalog.Group("私密分组_01234567",3,hidden)};
            ui(()->{try{
                set("startupMaintenanceDestroyed",true);set("groupPasswordRequired",true);
                ChannelCatalog.GROUPS=groups;set("currentGroupIndex",0);set("currentChannelIndex",0);
                call("openChannelList",new Class<?>[]{});
            }catch(Exception error){throw new RuntimeException(error);}});
            int request=(int)get("playRequestId");
            for(String id:TvUiPlugin.IDS){
                TvUiPlugin.select(getTargetContext(),id);waitForIdleSync();ui(()->activity.onTvUiChanged());
                invoke("showChannelMenu",0);check(visibleChannels()==1,id+" public rows remain visible");
                invoke("showChannelMenu",1);waitForIdleSync();
                check(visibleChannels()==0,id+" locked group supplies no adapter rows");
                check(!(boolean)get("epgExpanded")&&((android.widget.ListAdapter)get("epgAdapter")).getCount()==0,id+" locked group clears programme rows");
                ui(()->{try{check(text((View)get("channelListPanel"),"私密一台")==null&&text((View)get("channelListPanel"),"私密二台")==null,id+" no private text in view tree");}catch(Exception e){throw new RuntimeException(e);}});
                enterGroup(1);popup();digits("00000000");
                check(visibleChannels()==0&&dialog()!=null,id+" full wrong password waits for confirmation");
                sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER);
                check(visibleChannels()==0&&dialog()!=null,id+" wrong remote password keeps rows absent");
                sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);closed();check(visibleChannels()==0,id+" cancel keeps rows absent");
                enterGroup(1);popup();
                Bitmap screenshot=getUiAutomation().takeScreenshot();
                if(screenshot!=null){try(OutputStream output=new FileOutputStream(new File(evidence,id+"-locked.png"))){screenshot.compress(Bitmap.CompressFormat.PNG,100,output);}finally{screenshot.recycle();}}
                digits("01234567");
                check(visibleChannels()==0&&dialog()!=null,id+" full correct password keeps rows absent until confirmed");
                final GroupPasswordDialog waiting=dialog();
                ui(()->{TextView confirm=text(waiting.getWindow().getDecorView(),"确认");check(confirm!=null, id+" confirm button exists");confirm.performClick();});
                closed();await(()->visibleChannels()==2,id+" unlock binds channel rows");
                invoke("showChannelMenu",1);check(visibleChannels()==2,id+" same visit remains unlocked");
                invoke("showChannelMenu",0);invoke("showChannelMenu",1);check(visibleChannels()==0,id+" leave/reentry removes rows again");
            }
            check((int)get("playRequestId")==request,"browsing, wrong passwords and cancellation do not retune playback");
            call("handleWebSettings",new Class<?>[]{JSONObject.class},new JSONObject().put("groupPasswordRequired",false));waitForIdleSync();
            check(visibleChannels()==2,"disabling protection restores open list immediately");
            call("handleWebSettings",new Class<?>[]{JSONObject.class},new JSONObject().put("groupPasswordRequired",true));waitForIdleSync();
            check(visibleChannels()==0,"enabling protection removes existing channel rows immediately");
            JSONObject summary=new JSONObject().put("checks",checks).put("themeScreenshots",TvUiPlugin.IDS.length);
            try(OutputStream output=new FileOutputStream(new File(evidence,"result.json"))){output.write(summary.toString(2).getBytes("UTF-8"));}
            result.putString("stream","PASS "+summary+"\n");
        }catch(Throwable error){code=1;result.putString("stream",android.util.Log.getStackTraceString(error));}
        finally{
            if(activity!=null)ui(()->{try{if(dialog()!=null)dialog().cancel();activity.finish();}catch(Exception ignored){}});
            ChannelCatalog.GROUPS=original;restore(prefs,saved);restore(skins,savedSkin);
            try{TvUiPlugin.select(getTargetContext(),savedSkin.containsKey("selected")?(String)savedSkin.get("selected"):"builtin");}catch(Exception ignored){}
        }
        finish(code,result);
    }
}
