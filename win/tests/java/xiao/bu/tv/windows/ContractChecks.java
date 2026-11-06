package xiao.bu.tv.windows;

import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

public final class ContractChecks {
  static void check(boolean ok,String message) { if (!ok) throw new AssertionError(message); }
  static void waitFor(BooleanSupplier condition,String message) throws Exception {
    long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean() && System.nanoTime()<end) Thread.sleep(10);
    check(condition.getAsBoolean(),message);
  }
  static final class FakeProcess extends Process {
    final PipedInputStream input=new PipedInputStream();
    final PipedOutputStream output;
    final CountDownLatch done=new CountDownLatch(1);
    volatile boolean alive=true;
    FakeProcess() throws IOException { output=new PipedOutputStream(input); }
    public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
    public InputStream getInputStream() { return input; }
    public InputStream getErrorStream() { return InputStream.nullInputStream(); }
    public int waitFor() throws InterruptedException { done.await(); return 0; }
    public boolean waitFor(long timeout,TimeUnit unit) throws InterruptedException { return done.await(timeout,unit); }
    public int exitValue() { if (alive) throw new IllegalThreadStateException(); return 0; }
    public boolean isAlive() { return alive; }
    public void destroy() { alive=false; done.countDown(); try { output.close(); } catch (IOException ignored) { } }
  }
  public static void main(String[] args) throws Exception {
    String signed="webview://https://fixture.test/page?ujs=ab+c%2F%3D&token=x,y#keep";
    Playlist txt=Playlist.parse("\uFEFF电视,#genre#\n名字,"+signed+"\n坏行\n", "fixture.txt",null);
    check(txt.channels.size()==1 && txt.rejected==1,"TXT rejected count");
    check(txt.channels.get(0).url().equals(signed),"UJS/signed URL altered");
    check(txt.channels.get(0).group().equals("电视"),"TXT group");
    Playlist m3u=Playlist.parse("#EXTM3U\n#EXTINF:-1 group-title=\"A,B\",频道甲\n#EXTVLCOPT:http-referrer=https://fixture.test/\nmedia/live.m3u8?token=a,b\n#EXTINF:-1,频道乙\nhttps://fixture.test/two.mp4\n", "fixture.m3u",URI.create("https://fixture.test/path/list.m3u"));
    check(m3u.channels.size()==2,"M3U channels");
    check(m3u.channels.get(0).group().equals("A,B"),"Comma inside attributes");
    check(m3u.channels.get(0).url().equals("https://fixture.test/path/media/live.m3u8?token=a,b"),"Relative/signed M3U");
    check(m3u.channels.get(0).headers().get("Referer").equals("https://fixture.test/"),"Media header lost");
    check(m3u.channels.get(1).headers().isEmpty(),"Header leaked to next entry");
    boolean rejected=false;
    try { Playlist.parse("#EXTM3U\n#EXTINF:-1,bad\nfile:///etc/passwd\n", "invalid",null); }
    catch (IOException expected) { rejected=true; }
    check(rejected,"Unsupported scheme accepted");
    List<String> command=PlayerController.command(Path.of("C:/space path/ffplay.exe"),"名字 & 甲","https://fixture.test/a?token=x&b=y",Map.of("Referer","https://fixture.test/"));
    check(command.get(command.size()-1).equals("https://fixture.test/a?token=x&b=y"),"Argument splitting");
    rejected=false;
    try { PlayerController.command(Path.of("ffplay"),"title","https://fixture.test/",Map.of("X-Test","ok\r\nBad: injected")); }
    catch (IllegalArgumentException expected) { rejected=true; }
    check(rejected,"Header injection accepted");
    Path file=Files.createTempFile("ntv-player-", ".exe");
    List<FakeProcess> processes=new CopyOnWriteArrayList<>();
    List<String> urls=new CopyOnWriteArrayList<>();
    List<String> events=new CopyOnWriteArrayList<>();
    PlayerController c=new PlayerController(file,file.getParent(),events::add,cmd -> {
      FakeProcess process=new FakeProcess(); processes.add(process); urls.add(cmd.get(cmd.size()-1)); return process;
    });
    try {
      c.play(new Playlist.Channel("A","","https://fixture.test/a",Map.of()));
      waitFor(() -> urls.size()==1,"First channel did not launch");
      c.play(new Playlist.Channel("B","","https://fixture.test/b",Map.of()));
      c.play(new Playlist.Channel("C","","https://fixture.test/c",Map.of()));
      waitFor(() -> urls.contains("https://fixture.test/c"),"Final channel did not launch");
      check(!processes.get(0).isAlive(),"Previous player leaked");
      c.stop();
      waitFor(() -> processes.stream().noneMatch(Process::isAlive),"Stop leaked a player");
      check(events.stream().noneMatch(s -> s.contains("退出") || s.equals("播放结束")),"Stale player exit changed active status");
    } finally { c.close(); check(c.awaitClosed(5000),"Controller did not shut down"); Files.deleteIfExists(file); }
    System.out.println("PASS playlist formats, exact UJS/signed URLs, headers, rapid tune replacement, stale exits and cleanup");
  }
}
