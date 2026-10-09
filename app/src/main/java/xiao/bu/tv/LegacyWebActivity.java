package xiao.bu.tv;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import java.io.BufferedReader;
import java.io.FileReader;

/** API 14-25 WebKit runs inside the app process. Give it a disposable process/task. */
public final class LegacyWebActivity extends Activity implements WebSourceView.Listener {
    private final Handler ui = new Handler();
    private final Messenger inbox = new Messenger(new Handler(message -> { command(message.getData()); return true; }));
    private volatile Messenger player;
    private WebSourceView source;
    private FlyMouseCursorView cursor;
    private String token;
    private boolean bound;
    private volatile boolean running, monitoring;
    private volatile long uiBeat, rendererBeat;
    private volatile long memoryLimitKb;
    private int request = -1;
    private long lastBack;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            player = new Messenger(service);
            Bundle hello = new Bundle(); hello.putInt("pid", Process.myPid());
            send(LegacyWebSession.HELLO, hello);
        }
        @Override public void onServiceDisconnected(ComponentName name) { finish(); Process.killProcess(Process.myPid()); }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        token = getIntent().getStringExtra("token");
        if (token == null) { finish(); return; }
        if (getIntent().hasExtra("orientation")) setRequestedOrientation(getIntent().getIntExtra("orientation", -1));
        GithubProxy.initialize(this);
        NetworkClient.initialize(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | (android.os.Build.VERSION.SDK_INT >= 19 ? View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY : 0));
        source = new WebSourceView(this); source.setListener(this);
        android.widget.FrameLayout content = new android.widget.FrameLayout(this);
        content.addView(source, new android.widget.FrameLayout.LayoutParams(-1, -1));
        cursor = new FlyMouseCursorView(this);
        content.addView(cursor, new android.widget.FrameLayout.LayoutParams(-1, -1));
        setContentView(content);
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        ((ActivityManager)getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(memory);
        long total = android.os.Build.VERSION.SDK_INT >= 16 ? memory.totalMem / 1024L : 512L * 1024L;
        memoryLimitKb = Math.max(128L * 1024L, Math.min(320L * 1024L, total / 3L));
        // Binding ABOVE_CLIENT raises the player's priority relative to this memory-heavy process.
        bound = bindService(new Intent(this, LegacyWebGuardService.class), connection,
                Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT | Context.BIND_ABOVE_CLIENT);
        if (!bound) { finish(); return; }
        uiBeat = rendererBeat = SystemClock.elapsedRealtime(); running = true;
        new Thread(this::watch, "legacy-web-memory-guard").start();
        ui.post(tick);
        new Thread(() -> {
            try { ChannelCatalog.Group[] groups = new ChannelCatalogStore(this).load();
                if (groups != null && groups.length > 0) ChannelCatalog.GROUPS = groups;
            } catch (RuntimeException ignored) { }
        }, "legacy-web-catalog").start();
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (!token.equals(intent.getStringExtra("token"))) { finish(); Process.killProcess(Process.myPid()); }
        else if (intent.hasExtra("orientation")) setRequestedOrientation(intent.getIntExtra("orientation", -1));
    }
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            uiBeat = SystemClock.elapsedRealtime();
            if (monitoring) source.probeLegacyRenderer(() -> rendererBeat = SystemClock.elapsedRealtime());
            if (request >= 0) event("state", new Bundle());
            ui.postDelayed(this, 1000L);
        }
    };
    private void watch() {
        ActivityManager manager = (ActivityManager)getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        long lastMemoryCheck = 0L, lastSend = 0L;
        while (running) {
            long now = SystemClock.elapsedRealtime();
            long rss = residentKb();
            String failure = "";
            if (rss > memoryLimitKb) failure = "网页内存超限（" + rss / 1024L + "/" + memoryLimitKb / 1024L + " MiB）";
            if (now - lastMemoryCheck >= 1000L) {
                manager.getMemoryInfo(memory); lastMemoryCheck = now;
                // Keep headroom above the legacy lowmemorykiller foreground threshold.
                if (memory.availMem < Math.max(64L * 1024L * 1024L, memory.threshold)) failure = "设备内存不足";
            }
            if (monitoring && now - uiBeat > 8000L) failure = "网页界面无响应";
            if (monitoring && now - rendererBeat > 20000L) failure = "网页脚本持续无响应";
            if (failure.length() > 0 || now - lastSend >= 1000L) {
                Bundle beat = new Bundle(); beat.putString("failure", failure); beat.putLong("rssKb", rss);
                send(LegacyWebSession.HEARTBEAT, beat); lastSend = now;
            }
            if (failure.length() > 0) {
                android.util.Log.w("LegacyWebGuard", "browser self-stop rssKb=" + rss + " limitKb=" + memoryLimitKb + " reason=" + failure);
                // Main handles the notification. Death notification is the fallback if IPC is lost.
                SystemClock.sleep(250L); Process.killProcess(Process.myPid()); return;
            }
            SystemClock.sleep(200L);
        }
    }
    private static long residentKb() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/status"))) {
            String line;
            while ((line = reader.readLine()) != null) if (line.startsWith("VmRSS:"))
                return Long.parseLong(line.substring(6).trim().split("\\s+")[0]);
        } catch (Exception ignored) { }
        return 0L;
    }
    private void send(int what, Bundle data) {
        Messenger target = player;
        if (target == null) return;
        data.putString("token", token);
        Message message = Message.obtain(null, what); message.setData(data); message.replyTo = inbox;
        try { target.send(message); } catch (android.os.RemoteException ignored) { }
    }
    private void event(String name, Bundle data) {
        data.putString("event", name);
        Bundle state = new Bundle();
        state.putString("url", source.currentPageUrl()); state.putString("ua", source.activeUserAgent());
        state.putString("title", source.currentPageTitle()); state.putString("pageKey", source.currentResourcePageKey());
        state.putString("channelTitle", source.currentChannelTitle()); state.putString("channelUrl", source.currentChannelUrl());
        state.putString("group", source.currentChannelGroup());
        state.putBoolean("fullscreen", source.isInBrowserFullscreen());
        String media = source.currentWebMediaState().toString();
        if (media.length() < 100000) state.putString("media", media);
        data.putBundle("state", state); send(LegacyWebSession.EVENT, data);
    }
    private void result(int id, String error, String json) {
        Bundle data = new Bundle(); data.putInt("id", id); data.putString("error", error); data.putString("json", json);
        send(LegacyWebSession.RESULT, data);
    }
    private void command(Bundle data) {
        if (!running || !token.equals(data.getString("token"))) return;
        String action = data.getString("action", "");
        switch (action) {
            case "configuration": source.setLegacyTranspilation(data.getBoolean("transpile"));source.applyConfiguration(data.getString("viewport", "720p"), data.getBoolean("images", true),
                    data.getString("ua", "windows"), data.getString("version", "native"), data.getFloat("pageScale", 1f),
                    data.getBoolean("ads", true), data.getBoolean("rtc"), data.getBoolean("scripts"), data.getString("userScripts", "[]")); break;
            case "scale": source.setInterfaceScale(data.getFloat("scale", 1f)); cursor.setCastVisualScale(data.getFloat("scale", 1f)); break;
            case "transpilation":source.setLegacyTranspilation(data.getBoolean("enabled"));break;
            case "open": request = data.getInt("request"); monitoring = true;
                rendererBeat = SystemClock.elapsedRealtime(); source.open(request, data.getString("url"),
                        data.getString("script", ""), data.getBoolean("htmlMimeOverride", false)); break;
            case "hide": monitoring = false; source.hideForStreamPlayback(); showPlayer(); break;
            case "restore": source.restoreAfterStreamPlayback(); monitoring = true; rendererBeat = SystemClock.elapsedRealtime(); break;
            case "back": onBackPressed(); break;
            case "forward": source.goForwardIfPossible(); break;
            case "fullscreenExit": source.exitBrowserFullscreen(); break;
            case "pause": source.setMultimediaPaused(true); break;
            case "play": source.setMultimediaPaused(false); break;
            case "trim": source.trimMemory(); break;
            case "cache": source.clearBrowserCache(); break;
            case "shutdown":
                if (android.os.Build.VERSION.SDK_INT >= 21) android.webkit.CookieManager.getInstance().flush();
                else { android.webkit.CookieSyncManager.createInstance(this); android.webkit.CookieSyncManager.getInstance().sync(); }
                finish(); break;
            case "key": source.dispatchRemoteKey(data.getInt("key"), data.getInt("meta")); break;
            case "text": source.inputTextRemote(data.getString("text")); break;
            case "tab": source.openLinkInNewTab(data.getString("url")); break;
            case "backgroundTab": source.openLinkInBackgroundTab(data.getString("url")); break;
            case "bookmark": source.openBookmarkedPage(data.getString("url"), data.getString("title"), data.getString("group"), data.getBoolean("newTab")); break;
            case "pageScale": source.adjustCurrentPageScale(data.getFloat("factor")); break;
            case "context": source.showSmartContextAt(data.getFloat("x"), data.getFloat("y")); break;
            case "inspect": source.inspectSmartContext(data.getFloat("x"), data.getFloat("y"), value -> result(data.getInt("id"), null, value.toString())); break;
            case "compatibility": source.inspectCompatibility(value -> result(data.getInt("id"), null, value.toString())); break;
            case "ad": source.markImageAsAd(data.getString("url")); break;
            case "mouse": MotionEvent motion = data.getParcelable("motion");
                if (motion != null) { updateCursor(motion); source.dispatchRemoteMouseHover(source, motion); motion.recycle(); } break;
            case "mouseCancel": MotionEvent release = data.getParcelable("motion");
                if (release != null) { source.cancelRemoteMouseButton(release); release.recycle(); } break;
            case "touch": MotionEvent touch = data.getParcelable("motion");
                if (touch != null) { updateCursor(touch); source.dispatchTouchEvent(touch); touch.recycle(); } break;
            case "media": source.controlWebMedia(data.getString("mediaAction"), data.getString("page"), data.getString("media"),
                    error -> result(data.getInt("id"), error, null)); break;
        }
    }
    private void updateCursor(MotionEvent motion) {
        if (cursor == null || motion.getPointerCount() == 0 || motion.getToolType(0) != MotionEvent.TOOL_TYPE_MOUSE) return;
        cursor.moveBy(motion.getX() - cursor.cursorX(), motion.getY() - cursor.cursorY());
        if (motion.getActionMasked() == MotionEvent.ACTION_DOWN) cursor.pulseClick();
    }
    @Override public boolean dispatchGenericMotionEvent(MotionEvent motion) {
        updateCursor(motion); return super.dispatchGenericMotionEvent(motion);
    }
    private void showPlayer() {
        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
    }
    @Override protected void onResume() {
        super.onResume();
        rendererBeat = uiBeat = SystemClock.elapsedRealtime();
        monitoring = request >= 0 && source != null && source.isPageVisible();
        if (source != null) source.resumePage();
    }
    @Override protected void onPause() { monitoring = false; if (source != null) source.pausePage(); super.onPause(); }
    @Override protected void onDestroy() {
        running = false; ui.removeCallbacks(tick); if (bound) unbindService(connection);
        // Destroying a wedged in-process WebKit may block forever. Dispose of our process instead.
        super.onDestroy(); Process.killProcess(Process.myPid());
    }
    @Override public void onBackPressed() {
        if (source.exitBrowserFullscreen() || source.goBackIfPossible()) { lastBack = 0L; return; }
        long now = SystemClock.elapsedRealtime();
        if (lastBack > 0L && now - lastBack < 2000L) { onBrowserHome(); return; }
        lastBack = now;
        android.widget.Toast.makeText(this, "再按一次返回关闭网页", android.widget.Toast.LENGTH_SHORT).show();
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (key == KeyEvent.KEYCODE_MEDIA_NEXT || key == KeyEvent.KEYCODE_MEDIA_PREVIOUS
                || key == KeyEvent.KEYCODE_CHANNEL_UP || key == KeyEvent.KEYCODE_CHANNEL_DOWN) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                Bundle data = new Bundle(); data.putInt("direction",
                        key == KeyEvent.KEYCODE_MEDIA_NEXT || key == KeyEvent.KEYCODE_CHANNEL_UP ? 1 : -1);
                event("relative", data);
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
    @Override public void onPageStarted(int id, String url) { rendererBeat = SystemClock.elapsedRealtime(); event("started", LegacyWebSession.bundle("url", url)); }
    @Override public void onResourcesReset(int id, String url) { event("reset", LegacyWebSession.bundle("url", url)); }
    @Override public void onPageReady(int id, String url, String title) { event("ready", LegacyWebSession.bundle("url", url)); }
    @Override public void onPageError(int id, String reason) { onPageUnavailable(id, reason); }
    @Override public void onPageUnavailable(int id, String reason) {
        Bundle beat = LegacyWebSession.bundle("failure", "网页不可用: " + reason); send(LegacyWebSession.HEARTBEAT, beat);
    }
    @Override public void onStreamDiscovered(int id, String stream, String page, String ua, String cookies) {
        Bundle data = LegacyWebSession.bundle("stream", stream); data.putString("url", page); data.putString("ua", ua); data.putString("cookies", cookies); event("stream", data);
    }
    @Override public void onBrowserHome() { showPlayer(); event("home", new Bundle()); }
    @Override public void onBrowserChannel(int group, int channel) { showPlayer(); Bundle b = new Bundle(); b.putInt("group", group); b.putInt("channel", channel); event("channel", b); }
    @Override public void onBrowserDownloadImage(String url) { event("image", LegacyWebSession.bundle("url", url)); }
    @Override public void onBrowserClipboard(String text, String message) { Bundle b = LegacyWebSession.bundle("text", text); b.putString("message", message); event("clipboard", b); }
    @Override public void onBrowserUserScript(String url) { showPlayer(); event("script", LegacyWebSession.bundle("url", url)); }
    @Override public void onBrowserPoliciesChanged(boolean images, boolean ads, boolean rtc) { Bundle b = new Bundle(); b.putBoolean("images", images); b.putBoolean("ads", ads); b.putBoolean("rtc", rtc); event("policies", b); }
}
