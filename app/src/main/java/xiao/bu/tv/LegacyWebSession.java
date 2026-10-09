package xiao.bu.tv;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import java.util.HashMap;
import java.util.UUID;
import org.json.JSONObject;

/** Player-side facade. No WebView or WebKit singleton is constructed here on API 14-25. */
final class LegacyWebSession {
    static LegacyWebSession current;
    static final int HELLO = 1, EVENT = 2, HEARTBEAT = 3, COMMAND = 4, RESULT = 5;
    final WebSourceView owner;
    final Handler handler = new Handler();
    final HashMap<Integer, WebSourceView.MediaControlResult> results = new HashMap<>();
    final HashMap<Integer, WebSourceView.SmartContextCallback> inspections = new HashMap<>();
    Bundle configuration = new Bundle(), state = new Bundle();
    String token = "", url = "", script = "";
    int request = -1, pid, retiredPid, sequence;
    float scale = 1f;
    boolean active, visible, suspended;
    boolean htmlMimeOverride;
    Messenger browser;
    IBinder.DeathRecipient death;
    long lastBeat, started;
    LegacyWebSession(WebSourceView owner) { this.owner = owner; }
    void open(int id, String value, String pageScript, boolean forceHtml) {
        final int previousPid = pid > 0 ? pid : retiredPid;
        close();
        current = this;
        request = id; url = value; script = pageScript;
        htmlMimeOverride = forceHtml;
        token = UUID.randomUUID().toString();
        active = visible = true; suspended = false;
        state = new Bundle(); state.putString("url", url);
        started = lastBeat = SystemClock.elapsedRealtime();
        owner.setVisibility(android.view.View.VISIBLE);
        final String generation = token;
        handler.post(new Runnable() {
            @Override public void run() {
                if (!active || !generation.equals(token)) return;
                // killProcess is asynchronous. Do not deliver a new singleTask intent to the dying host.
                if (isBrowserProcess(previousPid)) { handler.postDelayed(this, 100L); return; }
                try { owner.getContext().startActivity(browserIntent()); }
                catch (RuntimeException error) { fail("无法启动网页进程"); }
            }
        });
        handler.postDelayed(watch, 1000L);
    }
    private final Runnable watch = new Runnable() {
        @Override public void run() {
            if (!active) return;
            if (SystemClock.elapsedRealtime() - lastBeat > 15000L) {
                fail("网页进程无响应"); return;
            }
            handler.postDelayed(this, 1000L);
        }
    };
    void receive(Message message) {
        Bundle data = message.getData();
        if (!active || !token.equals(data.getString("token"))) { rejectHello(message); return; }
        if (message.what == HELLO) {
            if (browser != null || message.replyTo == null) return;
            int candidate = data.getInt("pid");
            if (!isBrowserProcess(candidate)) return;
            browser = message.replyTo; pid = candidate;
            final String generation = token;
            death = () -> handler.post(() -> {
                if (active && token.equals(generation)) fail("网页进程已退出（崩溃或内存不足）");
            });
            try { browser.getBinder().linkToDeath(death, 0); }
            catch (android.os.RemoteException error) { fail("网页进程已退出"); return; }
            send("configuration", configuration);
            send("scale", bundle("scale", scale));
            Bundle open = bundle("url", url); open.putInt("request", request); open.putString("script", script);
            open.putBoolean("htmlMimeOverride", htmlMimeOverride);
            send("open", open);
        } else if (message.what == HEARTBEAT) {
            lastBeat = SystemClock.elapsedRealtime();
            String failure = data.getString("failure", "");
            if (failure.length() > 0) { fail(failure); return; }
        } else if (message.what == EVENT) {
            Bundle snapshot = data.getBundle("state");
            if (snapshot != null) state = snapshot;
            owner.deliverLegacyEvent(data.getString("event", ""), data, request);
        } else if (message.what == RESULT) {
            int id = data.getInt("id");
            WebSourceView.MediaControlResult callback = results.remove(id);
            if (callback != null) callback.complete(data.getString("error"));
            WebSourceView.SmartContextCallback inspection = inspections.remove(id);
            if (inspection != null) inspection.onResult(json(data.getString("json")));
        }
    }
    boolean isBrowserProcess(int candidate) {
        if (candidate <= 0 || candidate == Process.myPid()) return false;
        ActivityManager manager = (ActivityManager) owner.getContext().getSystemService(Context.ACTIVITY_SERVICE);
        java.util.List<ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
        if (processes != null) for (ActivityManager.RunningAppProcessInfo process : processes)
            if (process.pid == candidate && process.uid == Process.myUid()
                    && (owner.getContext().getPackageName() + ":web_browser").equals(process.processName)) return true;
        return false;
    }
    private int startingBrowserPid() {
        ActivityManager manager = (ActivityManager) owner.getContext().getSystemService(Context.ACTIVITY_SERVICE);
        java.util.List<ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
        if (processes != null) for (ActivityManager.RunningAppProcessInfo process : processes)
            if (process.uid == Process.myUid() && process.pid != Process.myPid()
                    && (owner.getContext().getPackageName() + ":web_browser").equals(process.processName)) return process.pid;
        return 0;
    }
    static void rejectHello(Message message) {
        if (message.what != HELLO || message.replyTo == null) return;
        Bundle payload = bundle("action", "shutdown"); payload.putString("token", message.getData().getString("token"));
        Message shutdown = Message.obtain(null, COMMAND); shutdown.setData(payload);
        try { message.replyTo.send(shutdown); } catch (android.os.RemoteException ignored) { }
    }
    void fail(String reason) {
        if (!active) return;
        int failedRequest = request;
        boolean wasVisible = visible;
        Log.w("LegacyWebGuard", "unavailable request=" + request + " browserPid=" + pid + " reason=" + reason);
        stop(true);
        // A hidden sniffing document failing must not interrupt an already playing native stream.
        if (wasVisible) owner.legacyUnavailable(failedRequest, reason);
    }
    void close() {
        stop(false);
    }
    private void stop(boolean emergency) {
        if (active && pid == 0) pid = startingBrowserPid();
        Messenger retiringBrowser = browser;
        String retiringToken = token;
        active = visible = false;
        handler.removeCallbacks(watch);
        if (browser != null && death != null) {
            try { browser.getBinder().unlinkToDeath(death, 0); }
            catch (java.util.NoSuchElementException ignored) { }
        }
        if (!emergency && retiringBrowser != null) {
            Bundle payload = bundle("action", "shutdown"); payload.putString("token", retiringToken);
            Message shutdown = Message.obtain(null, COMMAND); shutdown.setData(payload);
            try { retiringBrowser.send(shutdown); } catch (android.os.RemoteException ignored) { }
        }
        // Deliberate cancellation is disarmed before terminating. Never wait for a hung WebView UI.
        if (isBrowserProcess(pid)) {
            retiredPid = pid;
            final int retired = pid;
            if (emergency) Process.killProcess(retired);
            else handler.postDelayed(() -> { if (isBrowserProcess(retired)) Process.killProcess(retired); }, 300L);
        }
        browser = null; death = null; pid = 0; request = -1;
        java.util.ArrayList<WebSourceView.MediaControlResult> pending = new java.util.ArrayList<>(results.values());
        results.clear();
        java.util.ArrayList<WebSourceView.SmartContextCallback> inspecting = new java.util.ArrayList<>(inspections.values());
        inspections.clear();
        if (current == this) current = null;
        for (WebSourceView.MediaControlResult callback : pending) callback.complete("网页已关闭");
        for (WebSourceView.SmartContextCallback callback : inspecting) callback.onResult(new JSONObject());
    }
    void send(String action, Bundle data) {
        if (browser == null || !active) return;
        Bundle payload = new Bundle(data); payload.putString("action", action); payload.putString("token", token);
        Message message = Message.obtain(null, COMMAND); message.setData(payload);
        try { browser.send(message); }
        catch (android.os.RemoteException error) { fail("网页进程通信中断"); }
    }
    void command(String action) { send(action, new Bundle()); }
    void configure(Bundle value) { configuration = value; send("configuration", value); }
    void hide() { visible = false; command("hide"); owner.setVisibility(android.view.View.GONE); }
    private Intent browserIntent() {
        Intent intent = new Intent(owner.getContext(), LegacyWebActivity.class)
                .putExtra("token", token).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (owner.getContext() instanceof android.app.Activity)
            intent.putExtra("orientation", ((android.app.Activity)owner.getContext()).getRequestedOrientation());
        return intent;
    }
    boolean restore() {
        if (!active || visible || suspended) return false;
        visible = true; command("restore"); owner.setVisibility(android.view.View.VISIBLE);
        owner.getContext().startActivity(browserIntent().addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        return true;
    }
    void present() {
        if (!active || !visible || browser == null) return;
        owner.getContext().startActivity(browserIntent().addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
    }
    void suspend(WebViewShutdown.Completion completion) {
        if (!active || visible || suspended) { completion.onComplete(false); return; }
        // Once native playback is stable, discard the entire isolated process, including background tabs.
        suspended = true; close(); completion.onComplete(true);
    }
    String value(String key) { return state.getString(key, ""); }
    JSONObject media() { return active && visible ? json(value("media")) : new JSONObject(); }
    void mediaControl(String action, String page, String media, WebSourceView.MediaControlResult callback) {
        int id = ++sequence; results.put(id, callback);
        Bundle data = bundle("mediaAction", action); data.putString("page", page); data.putString("media", media); data.putInt("id", id);
        send("media", data);
        handler.postDelayed(() -> { WebSourceView.MediaControlResult pending = results.remove(id);
            if (pending != null) pending.complete("网页媒体控制超时"); }, 3000L);
    }
    void inspect(float x, float y, WebSourceView.SmartContextCallback callback) {
        int id = ++sequence; inspections.put(id, callback);
        Bundle data = bundle("x", x); data.putFloat("y", y); data.putInt("id", id); send("inspect", data);
        handler.postDelayed(() -> { WebSourceView.SmartContextCallback pending = inspections.remove(id);
            if (pending != null) pending.onResult(new JSONObject()); }, 2000L);
    }
    void inspectCompatibility(WebSourceView.SmartContextCallback callback) {
        int id = ++sequence; inspections.put(id, callback);
        Bundle data = new Bundle(); data.putInt("id", id); send("compatibility", data);
        handler.postDelayed(() -> {
            WebSourceView.SmartContextCallback pending = inspections.remove(id);
            if (pending != null) pending.onResult(new JSONObject());
        }, 3000L);
    }
    static JSONObject json(String value) { try { return new JSONObject(value); } catch (Exception ignored) { return new JSONObject(); } }
    static Bundle bundle(String key, String value) { Bundle b = new Bundle(); b.putString(key, value); return b; }
    static Bundle bundle(String key, float value) { Bundle b = new Bundle(); b.putFloat(key, value); return b; }
}
