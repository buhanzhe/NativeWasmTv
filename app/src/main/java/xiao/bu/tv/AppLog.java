package xiao.bu.tv;

import android.content.Context;
import java.io.*;
import java.util.ArrayDeque;
import java.util.Date;
import java.text.SimpleDateFormat;
import java.util.Locale;

/** Producers only enqueue bounded records; formatting and batched I/O happen off-thread. */
public final class AppLog {
    private static final Object lock = new Object(), fileLock = new Object(), nativeLock = new Object();
    private static final ArrayDeque<Entry> pending = new ArrayDeque<Entry>();
    private static final int MAX_FILE = 1024 * 1024, MAX_PENDING = 256, MAX_CHARS = 64 * 1024;
    private static final SimpleDateFormat timestamp = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);
    private static final Date date = new Date(); // Only accessed under fileLock.
    private static long formattedSecond = Long.MIN_VALUE;
    private static String secondPrefix;
    private static final int pid = android.os.Process.myPid();
    private static File current, previous;
    private static int pendingChars;
    private static long dropped;
    private static volatile String failure = "";
    private static volatile boolean nativeAvailable;
    private AppLog() { }

    private static final class Entry {
        final long time = System.currentTimeMillis();
        final int tid = android.os.Process.myTid(), level;
        final String tag, message;
        Entry(int level, String tag, String message) {
            this.level = level;
            this.tag = tag == null ? "App" : tag.length() <= 96 ? tag : tag.substring(0, 96) + "…";
            this.message = message.length() <= 8192 ? message : message.substring(0, 8192) + " [truncated]";
        }
        int chars() { return tag.length() + message.length(); }
    }

    static void initialize(Context context) {
        synchronized (lock) {
            if (current != null) return;
            File directory = new File(context.getFilesDir(), "logs");
            current = new File(directory, "app.log");
            previous = new File(directory, "app.previous.log");
        }
        Thread writer = new Thread(() -> {
            i("AppLog", "Application started; asynchronous log recording enabled");
            while (true) {
                synchronized (lock) {
                    try {
                        while (pending.isEmpty()) lock.wait();
                        // At most four background flushes/sec, no timer when idle.
                        long until = System.nanoTime() + 250000000L;
                        long remaining;
                        while ((remaining = until - System.nanoTime()) > 0)
                            lock.wait(Math.max(1, remaining / 1000000L));
                    } catch (InterruptedException stopped) { return; }
                }
                synchronized (fileLock) { flushLocked(); }
            }
        }, "app-log-writer");
        writer.setPriority(Thread.MIN_PRIORITY);
        writer.setDaemon(true);
        writer.start();
        Thread.UncaughtExceptionHandler delegate = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            append(7, "AndroidRuntime", "FATAL EXCEPTION: " + thread.getName() + "\n"
                    + android.util.Log.getStackTraceString(error));
            synchronized (fileLock) { flushLocked(); }
            if (delegate != null) delegate.uncaughtException(thread, error);
        });
    }

    private static native boolean nativeStart();
    private static native long nativeDroppedCount();
    /** Bind before player initialization, without loading decoder libraries in web/system-only sessions. */
    public static void loadIjkLibraries() {
        synchronized (nativeLock) {
            if (!nativeAvailable) {
                System.loadLibrary("ijkffmpeg");
                System.loadLibrary("ijksdl");
                try { nativeAvailable = nativeStart(); }
                catch (LinkageError unavailable) { w("AppLog", "IJK direct log capture unavailable", unavailable); }
            }
            tv.danmaku.ijk.media.player.IjkMediaPlayer.loadLibrariesOnce(null);
        }
    }
    public static void appendNative(int level, byte[] message) {
        if (level < 4) return;
        try { append(level, "IJKMEDIA", new String(message, "UTF-8")); }
        catch (UnsupportedEncodingException impossible) { }
    }

    private static void append(int level, String tag, String message) {
        // Verbose/debug remain in logcat; recording per-frame traces wastes old-device resources.
        if (level < 4) return;
        Entry entry = new Entry(level, tag, message == null ? "null" : message);
        synchronized (lock) {
            boolean wake = pending.isEmpty();
            while (pending.size() >= MAX_PENDING || pendingChars + entry.chars() > MAX_CHARS) {
                pendingChars -= pending.removeFirst().chars(); dropped++;
            }
            pending.addLast(entry); pendingChars += entry.chars();
            if (wake) lock.notifyAll();
        }
    }

    // fileLock serializes snapshots/rotation. Producers never acquire this lock.
    private static void flushLocked() {
        Entry[] batch;
        synchronized (lock) {
            if (current == null || pending.isEmpty()) return;
            batch = pending.toArray(new Entry[pending.size()]);
            pending.clear(); pendingChars = 0;
        }
        int index = 0;
        try {
            if (!current.getParentFile().isDirectory() && !current.getParentFile().mkdirs())
                throw new IOException("Cannot create log directory");
            long size = current.length();
            while (index < batch.length) {
                if (size >= MAX_FILE) {
                    if (previous.exists() && !previous.delete()) throw new IOException("Cannot remove old log");
                    if (!current.renameTo(previous)) throw new IOException("Cannot rotate log");
                    size = 0;
                }
                try (BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(current, true), 16384)) {
                    while (index < batch.length && size < MAX_FILE) {
                        byte[] bytes = format(batch[index]).getBytes("UTF-8");
                        output.write(bytes); size += bytes.length; index++;
                    }
                }
            }
            failure = "";
        } catch (IOException | SecurityException error) {
            failure = "日志文件写入失败，暂存日志可能不完整。";
            synchronized (lock) { dropped += batch.length - index; }
        }
    }

    private static String format(Entry entry) {
        long second = entry.time / 1000;
        if (second != formattedSecond) {
            date.setTime(entry.time);
            secondPrefix = timestamp.format(date);
            formattedSecond = second;
        }
        int millis = (int)(entry.time % 1000);
        String prefix = secondPrefix + "." + (millis < 100 ? "0" : "") + (millis < 10 ? "0" : "")
                + millis + " " + pid + " " + entry.tid + " "
                + "??VDIWEF".charAt(Math.max(0, Math.min(7, entry.level))) + " " + entry.tag + ": ";
        StringBuilder text = new StringBuilder(entry.message.length() + prefix.length() + 1);
        int start = 0;
        int lines = 0;
        // Avoid regex splitting and omit the empty line after an existing trailing newline.
        do {
            int end = entry.message.indexOf('\n', start);
            if (end < 0) end = entry.message.length();
            int contentEnd = end > start && entry.message.charAt(end - 1) == '\r' ? end - 1 : end;
            text.append(prefix).append(entry.message, start, contentEnd).append('\n');
            start = end + 1;
            if (++lines == 64 && start < entry.message.length()) {
                text.append(prefix).append("[remaining lines truncated]\n"); break;
            }
        } while (start < entry.message.length());
        return text.toString();
    }

    static String snapshot() throws IOException {
        synchronized (fileLock) {
            flushLocked();
            StringBuilder text = new StringBuilder();
            for (File file : new File[] { previous, current }) {
                if (file == null || !file.isFile()) continue;
                try (BufferedReader input = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"))) {
                    char[] buffer = new char[8192]; int count;
                    while ((count = input.read(buffer)) != -1) text.append(buffer, 0, count);
                }
            }
            return text.toString();
        }
    }

    static String note() {
        long lost;
        synchronized (lock) { lost = dropped; }
        long nativeLost = nativeAvailable ? nativeDroppedCount() : 0;
        return "默认保存 INFO 及以上的应用与 IJK 日志；后台批量写入，最多约 2 MB，自动轮换并保留重启前记录。"
                + (nativeAvailable ? " 包含 IJK/FFmpeg 日志。" : " IJK 直接日志尚未就绪或当前库不支持。")
                + (lost + nativeLost > 0 ? " 高负载时丢弃 " + (lost + nativeLost) + " 条日志。" : "") + failure;
    }

    private static int log(int level, String tag, String message, Throwable error) {
        String text = message == null ? "" : message;
        if (error != null) text += "\n" + android.util.Log.getStackTraceString(error);
        append(level, tag, text);
        return android.util.Log.println(level, tag, text);
    }
    public static int v(String t, String m) { return log(2,t,m,null); }
    public static int d(String t, String m) { return log(3,t,m,null); }
    public static int i(String t, String m) { return log(4,t,m,null); }
    public static int w(String t, String m) { return log(5,t,m,null); }
    public static int e(String t, String m) { return log(6,t,m,null); }
    public static int v(String t, String m, Throwable e) { return log(2,t,m,e); }
    public static int d(String t, String m, Throwable e) { return log(3,t,m,e); }
    public static int i(String t, String m, Throwable e) { return log(4,t,m,e); }
    public static int w(String t, String m, Throwable e) { return log(5,t,m,e); }
    public static int e(String t, String m, Throwable e) { return log(6,t,m,e); }
    public static int w(String t, Throwable e) { return log(5,t,"",e); }
}
