package xiao.bu.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.tvbus.engine.Listener;
import com.tvbus.engine.TVListener;
import dalvik.system.DexClassLoader;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/** On-demand TVBus engine. Immutable version files allow adoption on the next channel. */
final class TvbusPlugin implements Closeable {
    private static final String TAG = "TvbusPlugin";
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final long CHECK_INTERVAL = 30 * 60 * 1000L;
    private static final Object INSTALL_LOCK = new Object();
    private static final java.util.Map<String, Class<?>> BRIDGES = new java.util.HashMap<>();
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService updater = Executors.newSingleThreadExecutor();
    private final AtomicInteger generation = new AtomicInteger();
    private volatile boolean closed;
    private volatile Engine engine;
    private final java.util.Map<String,Long> checkedAt = new java.util.HashMap<>();
    interface Callback { void status(String text); void ready(String url); void failed(String text); }
    TvbusPlugin(Context context) { this.context = context.getApplicationContext(); }
    static boolean matches(String url) { return TvbusAddress.matches(url); }
    interface CoreProvider { JSONObject load() throws Exception; }
    private static final class Profile {
        final JSONObject core;
        final String origin, key;
        final TvbusBundle.Installed bundle;
        Profile(JSONObject core, Context context) throws Exception {
            bundle = core.optString("so", "").trim().isEmpty() ? TvbusBundle.installed(context) : null;
            this.core = bundle == null ? new JSONObject() : new JSONObject(bundle.defaults.toString());
            java.util.Iterator<String> keys = core.keys();
            while(keys.hasNext()) { String key=keys.next(); this.core.put(key,core.get(key)); }
            if (!core.optString("pkg", "").isEmpty() && !core.optString("sign", "").isEmpty())
                throw new IOException("该 TVBus core 依赖原应用签名环境，当前不支持");
            String so = core.optString("so", "").trim();
            origin = so.isEmpty() ? bundle == null ? upstreamUrl() : bundle.origin : TvbusLiveConfig.online(so);
            key = this.core.length() == 0 && bundle == null ? "" : TvbusLiveConfig.hash((bundle == null ? "" : bundle.digest) + TvbusLiveConfig.canonical(this.core));
        }
    }
    private SharedPreferences prefs(Profile profile) {
        return context.getSharedPreferences("tvbus-plugin-" + BuildConfig.CJS_PLUGIN_ABI + profile.key, 0);
    }
    private File directory(Profile profile) throws IOException {
        File dir = new File(context.getFilesDir(), "tvbus/" + BuildConfig.CJS_PLUGIN_ABI
                + (profile.key.isEmpty() ? "" : "/" + profile.key));
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("无法创建 TVBus 插件目录");
        return dir;
    }
    static String upstreamUrl() {
        return "https://raw.githubusercontent.com/binstreamio/tvbus.android/master/tvbus/src/main/jniLibs/"
                + BuildConfig.CJS_PLUGIN_ABI + "/libtvcore.so";
    }
    private File cached(Profile profile) throws Exception {
        String digest = prefs(profile).getString("digest", "");
        if (!digest.matches("[a-f0-9]{64}")) return profile.bundle == null ? null : profile.bundle.nativeFile;
        File file = new File(directory(profile), "libtvcore-" + digest + ".so");
        if (!file.isFile()) return null;
        byte[] bytes = read(new FileInputStream(file));
        validate(bytes);
        return digest.equals(hash(bytes)) ? file : null;
    }
    private void post(int token, Runnable runnable) {
        main.post(() -> { if (!closed && generation.get() == token) runnable.run(); });
    }
    void play(String url, CoreProvider provider, Callback callback) {
        final int token = generation.incrementAndGet();
        worker.execute(() -> {
            if (closed || token != generation.get()) return;
            try {
                final Profile profile = new Profile(provider.load(), context);
                final TvbusAddress.Route route = TvbusAddress.route(url, profile.core.optString("broker_url", ""));
                Log.i(TAG, "Core selected: " + (profile.key.isEmpty() ? "generic SDK fallback" : "provider descriptor " + profile.key.substring(0, 12)));
                File file;
                try { file = cached(profile); } catch (Exception invalid) { file = null; }
                if (file == null) {
                    post(token, () -> callback.status("正在安装 TVBus 兼容插件…"));
                    file = downloadWithRetry(profile);
                }
                if (closed || token != generation.get()) return;
                try { activate(file, profile); }
                catch (Throwable failure) {
                    Log.w(TAG, "Cached engine failed, retry upstream", failure);
                    post(token, () -> callback.status("TVBus 插件加载失败，正在重新下载…"));
                    activate(downloadWithRetry(profile), profile);
                }
                if (closed || token != generation.get()) return;
                final Engine active = engine;
                active.request = null;
                if (active.started) active.call("halt");
                if (active.broker != null) active.broker.close();
                active.broker = new TvbusBroker(route);
                final Request request = new Request(token, callback);
                active.request = request;
                post(token, () -> callback.status("TVBus 插件已就绪，正在连接频道…"));
                active.ensureStarted();
                if (closed || token != generation.get()) return;
                active.call("play", String.class, active.broker.address());
                Log.i(TAG, "Channel started token=" + token + " plugin=" + active.file.getName());
                main.postDelayed(() -> {
                    if (closed || generation.get() != token) return;
                    if (!request.completed.get()) {
                        callback.failed("TVBus 源连接超时，请重试或切换频道");
                        stop();
                    }
                }, 45000);
                updater.execute(() -> checkInBackground(profile));
            } catch (Throwable error) {
                Log.e(TAG, "TVBus open failed", error);
                if (engine != null && engine.broker != null) { engine.broker.close(); engine.broker = null; }
                post(token, () -> callback.failed(error instanceof IOException ? error.getMessage() : "TVBus 插件安装或加载失败，下次打开频道将重试"));
            }
        });
    }
    void stop() {
        generation.incrementAndGet();
        main.removeCallbacksAndMessages(null);
        if (!closed) worker.execute(() -> { if (engine != null) {
            engine.request = null;
            try { engine.call("halt"); } catch (Exception ignored) { }
            if (engine.broker != null) { engine.broker.close(); engine.broker = null; }
        } });
    }
    private void activate(File file, Profile profile) throws Exception {
        if (engine != null && engine.file.equals(file) && engine.profile.key.equals(profile.key) && engine.startupError == null
                && (!engine.started || engine.loop.isAlive())) return;
        if (engine != null) {
            engine.call("halt"); engine.call("close"); engine.loop.join(5000);
            if (engine.authReply != null) engine.authReply.close();
            if (engine.broker != null) engine.broker.close();
            if (engine.loop.isAlive()) throw new IOException("旧 TVBus 引擎尚未退出");
            engine = null;
        }
        engine = new Engine(file, profile);
    }
    private static final class Request {
        final int token;
        final Callback callback;
        final java.util.concurrent.atomic.AtomicBoolean completed = new java.util.concurrent.atomic.AtomicBoolean();
        Request(int token, Callback callback) { this.token = token; this.callback = callback; }
    }
    private final class Engine {
        final File file;
        final Object core;
        final Thread loop;
        TvbusAuthReply authReply;
        TvbusBroker broker;
        final Profile profile;
        volatile Request request;
        volatile boolean started;
        volatile Throwable startupError;
        final CountDownLatch initialized = new CountDownLatch(1);
        Engine(File file, Profile profile) throws Exception {
            this.file = file;
            this.profile = profile;
            Class<?> type = bridge(file, profile);
            core = type.getConstructor(String.class).newInstance(file.getAbsolutePath());
            // FongMi binds the native listener once, before setters and init/run.
            call("listen", Listener.class, new TVListener() {
                public void onInited(String result) {
                    Log.i(TAG, "onInited " + result);
                    try {
                        if (new JSONObject(result).optInt("tvcore", -1) == 0) initialized.countDown();
                    } catch (Exception ignored) { }
                }
                public void onStart(String result) { Log.i(TAG, "onStart " + result); }
                public void onInfo(String result) { }
                public void onQuit(String result) { Log.i(TAG, "onQuit " + result); }
                public void onPrepared(String result) {
                    Log.i(TAG, "onPrepared " + result);
                    Request active = request;
                    if (active == null || generation.get() != active.token) return;
                    try {
                        String stream = preparedUrl(new JSONObject(result), profile.core.optBoolean("preferHttp", false));
                        if (stream.isEmpty()) return;
                        if (stream.startsWith("-")) {
                            fail(active, "TVBus 源返回错误（" + stream + "）"); return;
                        }
                        TvbusLiveConfig.online(stream);
                        if (active.completed.compareAndSet(false, true))
                            post(active.token, () -> active.callback.ready(stream));
                    } catch (Exception error) { fail(active, "TVBus 返回的 HLS 地址无效"); }
                }
                public void onStop(String result) {
                    Log.i(TAG, "onStop " + result);
                    Request active = request;
                    if (active == null || generation.get() != active.token) return;
                    try {
                        JSONObject info = new JSONObject(result);
                        int error = info.optInt("errno", 0);
                        if (error < 0) {
                            String detail = info.optString("extra_errno", "");
                            String reason=broker==null?"":broker.failureDetail();
                            fail(active, "TVBus 源连接失败（" + error + (detail.isEmpty() ? "" : " / " + detail) + "）" + (reason.isEmpty()?"":"："+reason));
                        }
                    } catch (Exception ignored) { }
                }
            });
            loop = new Thread(() -> {
                try {
                    int result = (Integer) call("initialize", Context.class, context);
                    // FongMi runs the event loop regardless of init's return value;
                    // some SDK versions complete initialization asynchronously.
                    Log.i(TAG, "Engine init returned " + result + " (mode=1, serv=0) " + file.getName());
                    if (profile.core.optBoolean("waitForInit", false) && result != 0) throw new IOException("TVBus init=" + result);
                    call("loop");
                } catch (Throwable error) {
                    startupError = error;
                    initialized.countDown();
                    Log.e(TAG, "Engine loop ended", error);
                    Request active = request;
                    if (active != null) fail(active, "TVBus 引擎运行失败，请重试");
                }
            }, "tvbus-engine");
            loop.setDaemon(true);
        }
        private void fail(Request active, String message) {
            active.completed.set(true);
            post(active.token, () -> active.callback.failed(message));
        }
        void ensureStarted() throws Exception {
            if (!started) {
            JSONObject settings = TvbusLiveConfig.runtimeCore(profile.core);
            if (settings.optBoolean("dynamicPort", false)) {
                try (java.net.ServerSocket port = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
                    settings.put("playPort", port.getLocalPort());
                }
            }
            String response = settings.optString("resp", "");
            authReply = response.isEmpty() ? null : new TvbusAuthReply(response);
            try {
                if (authReply != null) settings.put("auth", authReply.url());
                String auth = settings.optString("auth", "");
                if (!auth.isEmpty()) TvbusLiveConfig.online(auth);
                call("settings", JSONObject.class, settings);
                call("configure");
            } catch (Throwable error) {
                if (authReply != null) authReply.close();
                throw new IOException("TVBus core 参数与原生引擎不兼容", error);
            }
                started = true; loop.start();
            }
            // Match TVCore.init(): start the init/run thread, then submit start()
            // without waiting for init. Channel timeout is handled by play().
            if (profile.core.optBoolean("waitForInit", false)) {
                long deadline = android.os.SystemClock.elapsedRealtime() + 20000;
                while (!initialized.await(200, TimeUnit.MILLISECONDS)) {
                    Request active=request;
                    if (closed || active == null || generation.get()!=active.token) throw new IOException("TVBus 播放已取消");
                    if (android.os.SystemClock.elapsedRealtime()>=deadline) throw new IOException("TVBus 初始化超时");
                }
                if (startupError != null) throw new IOException("TVBus 初始化失败", startupError);
            }
        }
        Object call(String name) throws Exception { return core.getClass().getMethod(name).invoke(core); }
        Object call(String name, Class<?> type, Object value) throws Exception { return core.getClass().getMethod(name, type).invoke(core, value); }
    }
    private Class<?> bridge(File file, Profile profile) throws Exception {
        synchronized (BRIDGES) {
            Class<?> existing = BRIDGES.get(file.getAbsolutePath());
            if (existing != null) return existing;
            File optimized = new File(context.getCacheDir(), "tvbus-dex");
            if (!optimized.isDirectory() && !optimized.mkdirs()) throw new IOException("无法创建插件加载目录");
            // A separate bridge class per version gives JNI a separate method table. Already
            // loaded libraries are never overwritten or rebound underneath active playback.
            DexClassLoader loader = new DexClassLoader(profile.bundle == null ? context.getApplicationInfo().sourceDir : profile.bundle.jar.getAbsolutePath(),
                    optimized.getAbsolutePath(), null, TvbusPlugin.class.getClassLoader()) {
                @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    if (!"com.tvbus.engine.TVCore".equals(name)) return super.loadClass(name, resolve);
                    synchronized (this) {
                        Class<?> type = findLoadedClass(name);
                        if (type == null) type = findClass(name);
                        if (resolve) resolveClass(type);
                        return type;
                    }
                }
            };
            Class<?> type = loader.loadClass("com.tvbus.engine.TVCore");
            BRIDGES.put(file.getAbsolutePath(), type);
            return type;
        }
    }
    private void checkInBackground(Profile profile) {
        if (profile.origin.isEmpty()) return;
        if (closed) return;
        long now = android.os.SystemClock.elapsedRealtime();
        Long previousCheck = checkedAt.get(profile.key);
        if (previousCheck != null && now - previousCheck < CHECK_INTERVAL) return;
        checkedAt.put(profile.key, now);
        try {
            synchronized (INSTALL_LOCK) {
                HttpURLConnection connection = connect(profile.origin);
                connection.setRequestMethod("HEAD");
                String etag = prefs(profile).getString("etag", ""), modified = prefs(profile).getString("modified", "");
                if (!etag.isEmpty()) connection.setRequestProperty("If-None-Match", etag);
                if (!modified.isEmpty()) connection.setRequestProperty("If-Modified-Since", modified);
                boolean changed;
                try {
                    int status = connection.getResponseCode();
                    if (status == 304) { Log.i(TAG, "Plugin unchanged (304), no download"); return; }
                    if (status != 200) throw new IOException("TVBus HEAD HTTP " + status);
                    String freshEtag = header(connection, "ETag"), freshModified = header(connection, "Last-Modified");
                    int length = connection.getContentLength();
                    boolean bundledBaseline = profile.bundle != null && prefs(profile).getString("url", "").isEmpty();
                    changed = metadataChanged(bundledBaseline ? connection.getURL().toString() : prefs(profile).getString("url", ""),
                            bundledBaseline ? freshEtag : etag, bundledBaseline ? freshModified : modified,
                            bundledBaseline ? (int)profile.bundle.nativeFile.length() : prefs(profile).getInt("length", -1),
                            connection.getURL().toString(), freshEtag, freshModified, length);
                    if (bundledBaseline && !changed) prefs(profile).edit().putString("url",connection.getURL().toString())
                            .putString("etag",freshEtag).putString("modified",freshModified).putInt("length",length).apply();
                } finally { connection.disconnect(); }
                if (changed) { downloadWithRetry(profile); Log.i(TAG, "New plugin staged; next channel will use it"); }
                else Log.i(TAG, "Plugin metadata unchanged, no download");
            }
        } catch (Exception error) { Log.w(TAG, "Silent update skipped; keep installed engine", error); }
    }
    static String preparedUrl(JSONObject result) {
        return preparedUrl(result, false);
    }
    static String preparedUrl(JSONObject result, boolean preferHttp) {
        String primary = result.optString(preferHttp ? "http" : "hls", "").trim();
        return primary.isEmpty() ? result.optString(preferHttp ? "hls" : "http", "").trim() : primary;
    }
    static boolean metadataChanged(String oldUrl, String oldEtag, String oldModified, int oldLength,
            String url, String etag, String modified, int length) {
        return !url.equals(oldUrl) || !etag.isEmpty() && !etag.equals(oldEtag)
                || !modified.isEmpty() && !modified.equals(oldModified)
                || length > 0 && length != oldLength;
    }
    private File downloadWithRetry(Profile profile) throws Exception {
        if (profile.origin.isEmpty()) throw new IOException("TVBus 本地插件加载失败，请重新导入 JAR");
        synchronized (INSTALL_LOCK) {
            Exception failure = null;
            for (int attempt = 0; attempt < 2; attempt++) {
                String url = profile.origin + (attempt == 0 ? "" : (profile.origin.contains("?") ? "&" : "?") + "retry=" + System.currentTimeMillis());
                HttpURLConnection connection = connect(url);
                try {
                    if (connection.getResponseCode() != 200) throw new IOException("TVBus download HTTP " + connection.getResponseCode());
                    int length = connection.getContentLength();
                    if (length > MAX_BYTES) throw new IOException("TVBus plugin too large");
                    byte[] bytes = read(connection.getInputStream());
                    if (length >= 0 && bytes.length != length) throw new EOFException("TVBus incomplete download");
                    validate(bytes);
                    String digest = hash(bytes);
                    File target = new File(directory(profile), "libtvcore-" + digest + ".so");
                    if (!target.isFile() || !digest.equals(hash(read(new FileInputStream(target))))) {
                        File temporary = File.createTempFile("download-", ".tmp", directory(profile));
                        try {
                            try (FileOutputStream out = new FileOutputStream(temporary)) { out.write(bytes); out.getFD().sync(); }
                            if (!temporary.setReadable(true, true) || !temporary.setExecutable(true, true)
                                    || !temporary.renameTo(target)) throw new IOException("TVBus plugin commit failed");
                        } finally { temporary.delete(); }
                    }
                    prefs(profile).edit().putString("digest", digest).putString("etag", header(connection, "ETag"))
                            .putString("modified", header(connection, "Last-Modified"))
                            .putString("url", connection.getURL().toString().replaceAll("([?&])retry=[0-9]+$", ""))
                            .putInt("length", bytes.length).apply();
                    Log.i(TAG, "Plugin downloaded bytes=" + bytes.length + " sha256=" + digest);
                    return target;
                } catch (Exception error) { failure = error; }
                finally { connection.disconnect(); }
            }
            throw failure;
        }
    }
    private static HttpURLConnection connect(String url) throws IOException {
        HttpURLConnection connection = NetworkClient.openComponent(new URL(url), true, 45000);
        connection.setConnectTimeout(12000); connection.setReadTimeout(20000);
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setInstanceFollowRedirects(true);
        return connection;
    }
    private static String header(HttpURLConnection connection, String name) { String value = connection.getHeaderField(name); return value == null ? "" : value; }
    private static byte[] read(InputStream stream) throws IOException {
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[32768]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("TVBus download cancelled");
                if (output.size() + count > MAX_BYTES) throw new IOException("TVBus plugin too large");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }
    static void validate(byte[] bytes) throws IOException {
        if (bytes.length < 1024 || bytes[0] != 0x7f || bytes[1] != 'E' || bytes[2] != 'L' || bytes[3] != 'F' || bytes[5] != 1)
            throw new IOException("TVBus 下载内容不是有效 ELF 插件");
        String abi = BuildConfig.CJS_PLUGIN_ABI;
        int machine = (bytes[18] & 255) | (bytes[19] & 255) << 8;
        int expected = "arm64-v8a".equals(abi) ? 183 : "armeabi-v7a".equals(abi) ? 40 : 3;
        if (machine != expected || bytes[4] != (expected == 183 ? 2 : 1)) throw new IOException("TVBus 插件架构不匹配");
    }
    private static String hash(byte[] bytes) throws Exception {
        StringBuilder text = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) text.append(String.format(Locale.US, "%02x", b & 255));
        return text.toString();
    }
    @Override public void close() {
        closed = true; generation.incrementAndGet(); main.removeCallbacksAndMessages(null);
        worker.execute(() -> {
            if (engine != null) {
                try { engine.call("halt"); engine.call("close"); } catch (Exception ignored) { }
                if (engine.authReply != null) engine.authReply.close();
                if (engine.broker != null) engine.broker.close();
            }
        });
        worker.shutdown(); updater.shutdownNow();
    }
}
