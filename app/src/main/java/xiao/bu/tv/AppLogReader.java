package xiao.bu.tv;

import android.os.Build;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.regex.Pattern;

/** On-demand bounded logcat snapshot; never runs on the UI thread. */
final class AppLogReader {
    private static final Pattern ERROR_LINE = Pattern.compile("^\\S+\\s+\\S+\\s+\\d+\\s+\\d+\\s+[EF]\\s+.*");
    static String read(String filter) throws Exception {
        final Process process;
        try {
            process = new ProcessBuilder(Build.VERSION.SDK_INT >= 21
                    ? new String[] {"/system/bin/logcat", "-d", "-v", "threadtime", "-b", "main", "-b", "crash"}
                    : new String[] {"/system/bin/logcat", "-d", "-v", "threadtime", "-b", "main"})
                    .redirectErrorStream(true).start();
        } catch (java.io.IOException | SecurityException unavailable) {
            return unavailable();
        }
        Thread timeout = new Thread(new Runnable() {
            public void run() {
                try { Thread.sleep(4000L); process.destroy(); }
                catch (InterruptedException ignored) { }
            }
        }, "log-snapshot-timeout");
        timeout.setDaemon(true); timeout.start();
        ArrayDeque<String> lines = new ArrayDeque<String>();
        Pattern ownProcess = Build.VERSION.SDK_INT < 16
                ? Pattern.compile("^\\S+\\s+\\S+\\s+" + android.os.Process.myPid() + "\\s+.*") : null;
        boolean crashesOnly = "crash".equals(filter), errorsOnly = "error".equals(filter);
        int size = 0, context = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // Pre-Jelly Bean can expose other applications' logs; retain only this process.
                if (ownProcess != null && !ownProcess.matcher(line).matches()) continue;
                String lower = line.toLowerCase(Locale.US);
                boolean crash = lower.contains("fatal exception") || lower.contains("fatal signal")
                        || lower.contains("sigabrt") || lower.contains("sigsegv") || lower.contains("abort message")
                        || lower.contains("am_crash") || lower.contains("backtrace:");
                boolean error = errorsOnly && (ERROR_LINE.matcher(line).matches()
                        || lower.contains("player error") || lower.contains("failed") || lower.contains("exception"));
                if (crash) context = 40;
                boolean include = crashesOnly ? context > 0 : !errorsOnly || error || context > 0;
                if (context > 0) context--;
                if (!include) continue;
                lines.addLast(line); size += line.length() + 1;
                while (size > 256 * 1024 || lines.size() > 1500) size -= lines.removeFirst().length() + 1;
            }
            if (process.waitFor() != 0) return unavailable();
        } catch (java.io.IOException | SecurityException unavailable) {
            return unavailable();
        } finally { timeout.interrupt(); process.destroy(); }
        StringBuilder result = new StringBuilder(size);
        for (String line : lines) result.append(line).append('\n');
        return new JSONObject().put("ok", true).put("text", result.toString())
                .put("note", "仅显示系统允许读取的应用日志；日志缓冲被清理后无法恢复。").toString();
    }
    private static String unavailable() throws org.json.JSONException {
        return new JSONObject().put("ok", true).put("available", false).put("text", "")
                .put("note", "系统不允许读取日志或读取超时，请通过已授权的 ADB 导出日志。").toString();
    }
}
