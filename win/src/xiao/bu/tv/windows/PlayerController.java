package xiao.bu.tv.windows;

import xiao.bu.tv.WindowsCoreSession;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.*;

/** Serial tune/stop operations and generation checks prevent stale playback. */
public final class PlayerController implements AutoCloseable {
  interface ProcessFactory { Process start(List<String> command) throws IOException; }
  private final Path player, profile;
  private final Consumer<String> status;
  private final ProcessFactory factory;
  private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r,"ntv-player"); t.setDaemon(true); return t;
  });
  private final AtomicLong generation = new AtomicLong();
  private WindowsCoreSession session;
  private Process process;
  private volatile boolean closed;

  public PlayerController(Path player, Path profile, Consumer<String> status) {
    this(player,profile,status,command -> new ProcessBuilder(command).redirectErrorStream(true).start());
  }
  PlayerController(Path player, Path profile, Consumer<String> status, ProcessFactory factory) {
    this.player = player; this.profile = profile; this.status = status;
    this.factory = factory;
  }
  public void play(Playlist.Channel channel) {
    if (closed) return;
    long token = generation.incrementAndGet();
    worker.execute(() -> {
      stopCurrent();
      if (!current(token)) return;
      status.accept("正在打开：" + channel.name());
      if (!channel.webpage()) { launch(token,channel,channel.url(),channel.headers()); return; }
      session = new WindowsCoreSession(channel.url(),profile,new WindowsCoreSession.Listener() {
        public void media(String url, Map<String,String> headers) {
          enqueue(() -> { if (current(token)) launch(token,channel,url,headers); });
        }
        public void failed(String message) {
          enqueue(() -> { if (current(token)) { stopCurrent(); status.accept("打开失败：" + message); } });
        }
      });
      session.start();
    });
  }
  private void enqueue(Runnable action) {
    try { worker.execute(action); } catch (RejectedExecutionException ignored) { }
  }
  private boolean current(long token) { return !closed && generation.get() == token; }

  private void launch(long token, Playlist.Channel channel, String url, Map<String,String> headers) {
    if (!current(token)) return;
    if (process != null && process.isAlive()) return;
    try {
      if (!Files.isRegularFile(player)) throw new IOException("找不到播放器：" + player);
      List<String> command = command(player,channel.name(),url,headers);
      process = factory.start(command);
      Process selected = process;
      WindowsCoreSession owner = session;
      status.accept("正在播放：" + channel.name() + "（在播放窗口按空格暂停，F 全屏）");
      Thread monitor = new Thread(() -> monitor(selected,owner,token),"ntv-player-events");
      monitor.setDaemon(true); monitor.start();
    } catch (IOException | IllegalArgumentException e) {
      stopCurrent(); status.accept("播放失败：" + e.getMessage());
    }
  }

  static List<String> command(Path player, String name, String url, Map<String,String> headers) {
    List<String> cmd = new ArrayList<>(List.of(player.toString(),"-hide_banner","-loglevel","warning",
        "-stats","-autoexit","-window_title",name,"-x","960","-y","540"));
    StringBuilder raw = new StringBuilder();
    for (Map.Entry<String,String> h : headers.entrySet()) {
      if (!h.getKey().matches("[A-Za-z0-9-]+") || h.getValue().indexOf('\r')>=0 || h.getValue().indexOf('\n')>=0)
        throw new IllegalArgumentException("无效的媒体请求头");
      raw.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
    }
    if (raw.length()>0) { cmd.add("-headers"); cmd.add(raw.toString()); }
    cmd.add("-i"); cmd.add(url);
    return cmd;
  }

  private void monitor(Process selected, WindowsCoreSession owner, long token) {
    Pattern clock = Pattern.compile("^\\s*([0-9]+(?:\\.[0-9]+)?)\\s+(?:A-V|M-V|M-A):.*");
    try (BufferedReader in = new BufferedReader(new InputStreamReader(selected.getInputStream(),StandardCharsets.UTF_8))) {
      String line;
      while ((line=in.readLine())!=null) {
        Matcher m=clock.matcher(line);
        if (owner!=null && current(token) && m.matches()) owner.playbackTime(Double.parseDouble(m.group(1)));
      }
      int code = selected.waitFor();
      enqueue(() -> {
        if (current(token)) {
          stopCurrent();
          status.accept(code==0 ? "播放结束" : "播放器退出（代码 " + code + "），请重试或切换频道");
        }
      });
    } catch (IOException e) {
      if (current(token)) enqueue(() -> { if (current(token)) { stopCurrent(); status.accept("播放器连接中断"); } });
    } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
  }

  public void stop() {
    if (closed) return;
    generation.incrementAndGet();
    enqueue(this::stopCurrent);
  }
  private void stopCurrent() {
    if (session!=null) session.close();
    if (process!=null) {
      process.destroy();
      try { if (!process.waitFor(1500,TimeUnit.MILLISECONDS)) process.destroyForcibly(); }
      catch (InterruptedException e) { process.destroyForcibly(); Thread.currentThread().interrupt(); }
      process=null;
    }
    if (session!=null) {
      try { session.awaitClosed(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
      session=null;
    }
  }
  @Override public void close() {
    closed=true; generation.incrementAndGet();
    enqueue(this::stopCurrent); worker.shutdown();
  }
  public boolean awaitClosed(long timeoutMillis) throws InterruptedException {
    return worker.awaitTermination(timeoutMillis,TimeUnit.MILLISECONDS);
  }
}
