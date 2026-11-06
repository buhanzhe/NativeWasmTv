package xiao.bu.tv;

import org.json.JSONObject;
import java.io.BufferedReader;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.regex.Pattern;

/** Snapshot of the app-owned bounded files, independent of logcat permissions. */
final class AppLogReader {
    private static final Pattern ERROR_LINE = Pattern.compile("^\\S+\\s+\\S+\\s+\\d+\\s+\\d+\\s+[EF]\\s+.*");
    static String read(String filter) throws Exception {
        return new JSONObject().put("ok", true).put("text", readText(filter))
                .put("available", true).put("note", AppLog.note()).toString();
    }
    static String readText(String filter) throws Exception {
        String snapshot = AppLog.snapshot();
        if (!"crash".equals(filter) && !"error".equals(filter)) return snapshot;
        ArrayDeque<String> lines = new ArrayDeque<String>();
        boolean crashesOnly = "crash".equals(filter), errorsOnly = "error".equals(filter);
        int size = 0, context = 0;
        try (BufferedReader reader = new BufferedReader(new java.io.StringReader(snapshot))) {
            String line;
            while ((line = reader.readLine()) != null) {
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
                while (size > 3 * 1024 * 1024) size -= lines.removeFirst().length() + 1;
            }
        }
        StringBuilder result = new StringBuilder(size);
        for (String line : lines) result.append(line).append('\n');
        return result.toString();
    }
}
