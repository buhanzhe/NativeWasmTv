package xiao.bu.tv;

import android.app.Instrumentation;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;

/** Run against an isolated applicationId: verifies source persistence without multicast/network I/O. */
public final class MulticastPlaylistInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void contains(ChannelCatalog.Group[] groups, String name, String url) {
        for (ChannelCatalog.Group group : groups) for (Channel channel : group.channels)
            if (name.equals(channel.name) && url.equals(channel.sourceUrl(0))) return;
        throw new AssertionError("Missing persisted channel " + name + " " + url);
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            // API 15 can start the instrumentation thread before Application is attached.
            long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
            while (getTargetContext().getApplicationContext() == null
                    && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(10);
            check(getTargetContext().getApplicationContext() != null, "Target Application not ready");
            String[] addresses = {"rtp://225.1.2.47:10276", "udp://@239.1.2.3:1234", "RTP://[ff15::1234]:5004",
                    "udp://239.1.2.4:1234?localaddr=192.168.1.2&fifo_size=1000000",
                    "rtsp://10.254.216.130/PLTV/88888888/224/3221225621/10000100000000060000000000009742_0.smil?",
                    "rtsp://user:password@192.168.1.1:554/live?token=a%2Bb&x=1,2"};
            JSONArray sources = new JSONArray();
            for (int i = 0; i < addresses.length; i++) {
                check(MulticastPlaylist.valid(addresses[i]), "Rejected valid multicast " + addresses[i]);
                sources.put(new JSONObject().put("id", "multicast" + i).put("name", "组播频道" + i)
                        .put("location", addresses[i]).put("enabled", true));
            }
            android.os.StrictMode.setThreadPolicy(new android.os.StrictMode.ThreadPolicy.Builder()
                    .detectNetwork().penaltyDeath().build());
            PlaylistManager manager = new PlaylistManager(getTargetContext());
            ChannelCatalog.Group[] saved = manager.updateSources(sources).groups;
            ChannelCatalog.Group[] restored = new PlaylistManager(getTargetContext()).loadCached();
            for (int i = 0; i < addresses.length; i++) {
                contains(saved, "组播频道" + i, addresses[i]);
                contains(restored, "组播频道" + i, addresses[i]);
                check(new String(manager.readForMobile(addresses[i]), "UTF-8").contains(addresses[i]), "Mobile source read lost URL");
            }
            // Exercise actual uploaded TXT/M3U files, including UTF-8 BOM/CRLF,
            // redundant TXT commas, native protocols and unchanged URL parameters.
            for (String format : new String[] {"txt", "m3u"}) {
                StringBuilder playlist = new StringBuilder("\uFEFF");
                // Separate groups avoid intentional cross-source URL deduplication
                // hiding the second fixture behind the first fixture's display name.
                playlist.append("txt".equals(format) ? format + "文件导入,#genre#\r\n" : "#EXTM3U\r\n");
                for (int i = 0; i < addresses.length; i++) {
                    String name = format + "频道" + i;
                    playlist.append("txt".equals(format) ? name + ",," : "#EXTINF:-1 group-title=\"" + format + "文件导入\"," + name + "\r\n")
                            .append(addresses[i]).append("\r\n");
                }
                PlaylistManager.ImportedFile imported = manager.importLocalPlaylist("multicast_" + format,
                        "组播测试." + format, playlist.toString().getBytes("UTF-8"));
                sources.put(new JSONObject().put("id", "multicast_" + format).put("name", format + "导入")
                        .put("location", imported.location).put("enabled", true));
                saved = manager.updateSources(sources).groups;
                restored = new PlaylistManager(getTargetContext()).loadCached();
                for (int i = 0; i < addresses.length; i++) {
                    contains(saved, format + "频道" + i, addresses[i]);
                    contains(restored, format + "频道" + i, addresses[i]);
                    check(new String(manager.readForMobile(imported.location), "UTF-8").contains(addresses[i]),
                            "File read lost URL " + format);
                    contains(restored, "组播频道" + i, addresses[i]);
                }
            }
            for (String invalid : new String[] {"rtp://", "rtp://:1234", "udp://239.1.2.3:70000", "udp://239.1.2.3:0"})
                check(!MulticastPlaylist.valid(invalid), "Accepted invalid endpoint " + invalid);
            result.putString("result", "PASS direct-address and uploaded TXT/M3U imports; RTP/UDP/RTSP, SMIL and empty query, @multicast, IPv6, URL parameters, BOM/CRLF, saved sources, restart, mobile reads, invalid ports; no network I/O");
            finish(-1, result);
        } catch (Throwable error) {
            result.putString("error", android.util.Log.getStackTraceString(error)); finish(1, result);
        }
    }
}
