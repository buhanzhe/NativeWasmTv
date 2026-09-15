package xiao.bu.tv;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Shared cancellable renderer for HLS WebVTT and M3U SRT/WebVTT sidecars. */
final class HlsSubtitlePlayer {
    // disconnect() can send a TLS close-notify on Android 7; never do it on the UI thread.
    private static final ExecutorService CONNECTION_CLEANUP = Executors.newSingleThreadExecutor();

    interface Output {
        long positionMs();
        void text(String value);
        default void error() {}
    }

    private final String url, headers;
    private final Output output;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<String, HlsMediaTracks.Vtt> cache = new LinkedHashMap<String, HlsMediaTracks.Vtt>();
    private volatile boolean closed;
    private volatile HttpURLConnection connection;
    private HlsMediaTracks.Playlist playlist;
    private long refreshedAt, retryAt;
    private Long originOffset;
    private boolean busy, errorReported;
    private String displayed = "";

    HlsSubtitlePlayer(String url, String headers, Output output) {
        this.url = url;
        this.headers = headers;
        this.output = output;
        main.post(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (closed) return;
            long position = output.positionMs(),
                now = SystemClock.elapsedRealtime();
            HlsMediaTracks.Segment segment = playlist == null ? null : playlist.at(position);
            String text = "";
            if (segment != null) {
                HlsMediaTracks.Vtt vtt = cache.get(segment.url);
                if (vtt != null) {
                    long offset =
                        vtt.timestampOffsetMs == null || originOffset == null
                            ? 0
                            : vtt.timestampOffsetMs - originOffset;
                    // MPEG-TS timestamps wrap at 33 bits. Use the epoch nearest this segment.
                    long wrap = (1L << 33) / 90;
                    while (offset > wrap / 2) offset -= wrap;
                    while (offset < -wrap / 2) offset += wrap;
                    StringBuilder active = new StringBuilder();
                    for (HlsMediaTracks.Cue cue : vtt.cues)
                        if (position >= cue.startMs + offset && position < cue.endMs + offset) {
                            if (active.length() > 0) active.append('\n');
                            active.append(cue.text);
                        }
                    text = active.toString();
                }
            }
            if (!text.equals(displayed)) {
                displayed = text;
                output.text(text);
            }
            boolean refresh =
                playlist == null ||
                (!playlist.finite && now - refreshedAt >= Math.max(1000, playlist.targetMs / 2));
            HlsMediaTracks.Segment next =
                segment == null ? null : playlist.at(segment.startMs + segment.durationMs);
            boolean prefetch =
                next != null &&
                !cache.containsKey(next.url) &&
                position >= segment.startMs + segment.durationMs / 2;
            if (
                !busy &&
                now >= retryAt &&
                (refresh || prefetch || (segment != null && !cache.containsKey(segment.url)))
            ) load(position, refresh);
            main.postDelayed(this, 150);
        }
    };

    private void load(final long position, final boolean refresh) {
        busy = true;
        final HlsMediaTracks.Playlist previous = playlist;
        final Long oldOrigin = originOffset;
        final Map<String, HlsMediaTracks.Vtt> cached = new LinkedHashMap<String, HlsMediaTracks.Vtt>(cache);
        worker.execute(
            new Runnable() {
                @Override
                public void run() {
                    try {
                        final HlsMediaTracks.Playlist list;
                        if (refresh) {
                            Response response = read(url);
                            if (!response.body.trim().startsWith("#EXTM3U")) {
                                final HlsMediaTracks.Vtt cues = HlsMediaTracks.parseVtt(response.body);
                                if (cues.cues.isEmpty()) throw new IOException("No SRT/WebVTT cues");
                                final HlsMediaTracks.Playlist standalone = new HlsMediaTracks.Playlist();
                                long end = 1;
                                for (HlsMediaTracks.Cue cue : cues.cues) end = Math.max(end, cue.endMs);
                                standalone.finite = true;
                                standalone.segments.add(new HlsMediaTracks.Segment(response.url, 0, 0, end));
                                main.post(() -> {
                                    if (closed) return;
                                    playlist = standalone;
                                    cache.put(response.url, cues);
                                    busy = false;
                                });
                                return;
                            }
                            list = HlsMediaTracks.parsePlaylist(response.url, response.body, previous);
                        } else list = previous;
                        final Map<String, HlsMediaTracks.Vtt> loaded = new LinkedHashMap<
                            String,
                            HlsMediaTracks.Vtt
                        >();
                        Long origin = oldOrigin;
                        // Establish the timestamp epoch from the start of the playlist, even after seeking.
                        if (origin == null && !list.segments.isEmpty()) {
                            HlsMediaTracks.Segment first = list.segments.get(0);
                            HlsMediaTracks.Vtt vtt = HlsMediaTracks.parseVtt(read(first.url).body);
                            loaded.put(first.url, vtt);
                            origin =
                                vtt.timestampOffsetMs == null ? 0 : vtt.timestampOffsetMs - first.startMs;
                        }
                        HlsMediaTracks.Segment current = list.at(position);
                        if (current != null) {
                            int index = list.segments.indexOf(current);
                            for (int i = index; i < Math.min(index + 2, list.segments.size()); i++) {
                                HlsMediaTracks.Segment s = list.segments.get(i);
                                if (!loaded.containsKey(s.url) && !cached.containsKey(s.url)) loaded.put(
                                    s.url,
                                    HlsMediaTracks.parseVtt(read(s.url).body)
                                );
                            }
                        }
                        final Long epoch = origin;
                        main.post(
                            new Runnable() {
                                @Override
                                public void run() {
                                    if (closed) return;
                                    playlist = list;
                                    originOffset = epoch;
                                    cache.putAll(loaded);
                                    while (cache.size() > 6) cache.remove(cache.keySet().iterator().next());
                                    refreshedAt = SystemClock.elapsedRealtime();
                                    busy = false;
                                }
                            }
                        );
                    } catch (final Exception error) {
                        main.post(
                            new Runnable() {
                                @Override
                                public void run() {
                                    if (closed) return;
                                    busy = false;
                                    retryAt = SystemClock.elapsedRealtime() + 2000;
                                    if (!errorReported) { errorReported = true; output.error(); }
                                    Log.w("nTvSubtitle", "Unable to load subtitles; will retry", error);
                                }
                            }
                        );
                    }
                }
            }
        );
    }

    private static final class Response {

        final String url, body;

        Response(String url, String body) {
            this.url = url;
            this.body = body;
        }
    }

    private Response read(String target) throws IOException {
        if (closed) throw new IOException("Subtitle session closed");
        HttpURLConnection current = NetworkClient.open(new URL(target));
        connection = current;
        current.setConnectTimeout(6000);
        current.setReadTimeout(6000);
        if (headers != null) for (String header : headers.split("\\r?\\n")) {
            int colon = header.indexOf(':');
            if (colon > 0) current.setRequestProperty(
                header.substring(0, colon).trim(),
                header.substring(colon + 1).trim()
            );
        }
        try {
            int status = current.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException("Subtitle HTTP " + status);
            InputStream input = current.getInputStream();
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int count;
                long deadline = SystemClock.elapsedRealtime() + 10000L;
                while ((count = input.read(buffer)) != -1) {
                    if (closed || Thread.currentThread().isInterrupted() || SystemClock.elapsedRealtime() > deadline) throw new IOException(
                        "Subtitle load cancelled"
                    );
                    if (bytes.size() + count > 2 * 1024 * 1024) throw new IOException(
                        "Subtitle document too large"
                    );
                    bytes.write(buffer, 0, count);
                }
                byte[] data = bytes.toByteArray();
                String charset = data.length >= 2 && ((data[0] == (byte)0xff && data[1] == (byte)0xfe)
                        || (data[0] == (byte)0xfe && data[1] == (byte)0xff)) ? "UTF-16" : "UTF-8";
                return new Response(current.getURL().toString(), new String(data, charset).replace("\uFEFF", ""));
            } finally {
                input.close();
            }
        } finally {
            current.disconnect();
            if (connection == current) connection = null;
        }
    }

    void close() {
        closed = true;
        main.removeCallbacks(tick);
        worker.shutdownNow();
        final HttpURLConnection active = connection;
        if (active != null) CONNECTION_CLEANUP.execute(new Runnable() {
            @Override public void run() {
                try { active.disconnect(); } catch (RuntimeException ignored) { }
            }
        });
        cache.clear();
        output.text("");
    }
}
