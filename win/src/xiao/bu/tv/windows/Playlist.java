package xiao.bu.tv.windows;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import okhttp3.*;

/** M3U and nTv TXT, preserving signed URLs and the complete webview/UJS suffix. */
public final class Playlist {
  public record Channel(String name, String group, String url, Map<String,String> headers) {
    public Channel { headers = Map.copyOf(headers); }
    public boolean webpage() { return url.startsWith("webview://"); }
    @Override public String toString() { return group.isEmpty() ? name : "[" + group + "] " + name; }
  }
  public final String source;
  public final List<Channel> channels;
  public final int rejected;
  private static final int LIMIT = 8 * 1024 * 1024;
  private static final Pattern GROUP = Pattern.compile("group-title=\"([^\"]*)\"");
  private static final OkHttpClient HTTP = new OkHttpClient.Builder()
      .callTimeout(20, java.util.concurrent.TimeUnit.SECONDS).followRedirects(true).build();

  private Playlist(String source, List<Channel> channels, int rejected) {
    this.source = source; this.channels = List.copyOf(channels); this.rejected = rejected;
  }
  @Override public String toString() {
    if (source.startsWith("https://") || source.startsWith("http://")) return source;
    try { return Path.of(source).getFileName().toString(); } catch (Exception e) { return source; }
  }

  public static Playlist load(String source) throws IOException {
    URI base = null;
    byte[] data;
    if (source.startsWith("https://") || source.startsWith("http://")) {
      try (Response r = HTTP.newCall(new Request.Builder().url(source).build()).execute()) {
        if (!r.isSuccessful() || r.body() == null) throw new IOException("播放列表 HTTP " + r.code());
        base = r.request().url().uri();
        try (InputStream in = r.body().byteStream()) { data = bounded(in); }
      }
    } else {
      try (InputStream in = Files.newInputStream(Path.of(source))) { data = bounded(in); }
    }
    return parse(new String(data, StandardCharsets.UTF_8),source,base);
  }

  private static byte[] bounded(InputStream in) throws IOException {
    byte[] data = in.readNBytes(LIMIT + 1);
    if (data.length > LIMIT) throw new IOException("播放列表超过 8 MiB");
    return data;
  }

  public static Playlist parse(String text, String source, URI base) throws IOException {
    if (text.length() > LIMIT) throw new IOException("播放列表过大");
    List<Channel> channels = new ArrayList<>();
    Map<String,String> headers = new LinkedHashMap<>();
    String group = "", name = null, entryGroup = "";
    int rejected = 0;
    if (text.startsWith("\uFEFF")) text = text.substring(1);
    for (String raw : text.split("\\R")) {
      String line = raw.trim();
      if (line.isEmpty()) continue;
      if (line.startsWith("#EXTINF:")) {
        int comma = commaOutsideQuotes(line);
        name = comma < 0 ? "" : line.substring(comma + 1).trim();
        Matcher m = GROUP.matcher(comma < 0 ? line : line.substring(0,comma));
        entryGroup = m.find() ? m.group(1) : group;
        headers.clear();
        continue;
      }
      if (line.startsWith("#EXTVLCOPT:")) {
        int equals = line.indexOf('=');
        if (equals > 0) {
          String option = line.substring(11,equals).toLowerCase(Locale.ROOT);
          if (option.equals("http-user-agent")) headers.put("User-Agent",line.substring(equals+1));
          if (option.equals("http-referrer")) headers.put("Referer",line.substring(equals+1));
        }
        continue;
      }
      if (line.startsWith("#")) continue;
      String url;
      if (name != null) url = line;
      else {
        int comma = line.indexOf(',');
        if (comma < 1) { rejected++; continue; }
        name = line.substring(0,comma).trim(); url = line.substring(comma+1).trim();
        if (url.equals("#genre#")) { group = name; name = null; headers.clear(); continue; }
        entryGroup = group;
      }
      boolean webpage = url.startsWith("webview://");
      String actual = webpage ? url.substring(10) : url;
      try {
        if (base != null && !actual.matches("(?i)^https?://.*")) actual = base.resolve(actual).toString();
        HttpUrl parsed = HttpUrl.parse(actual);
        if (parsed == null || name.isEmpty()) throw new IllegalArgumentException();
        for (String value : headers.values())
          if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) throw new IllegalArgumentException();
        channels.add(new Channel(name,entryGroup,(webpage ? "webview://" : "") + actual,headers));
        if (channels.size() > 10000) throw new IOException("播放列表超过 10000 个频道");
      } catch (IllegalArgumentException e) { rejected++; }
      name = null; headers = new LinkedHashMap<>();
    }
    if (name != null) rejected++;
    if (channels.isEmpty()) throw new IOException("播放列表没有有效的 HTTP(S) 频道");
    return new Playlist(source,channels,rejected);
  }

  private static int commaOutsideQuotes(String line) {
    boolean quote = false;
    for (int i=0; i<line.length(); i++) {
      if (line.charAt(i)=='"') quote = !quote;
      if (line.charAt(i)==',' && !quote) return i;
    }
    return -1;
  }
}
