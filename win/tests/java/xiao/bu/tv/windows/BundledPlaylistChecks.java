package xiao.bu.tv.windows;

import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import javax.swing.*;
import org.json.*;

/** Verify the exact upstream snapshot, every parsed URL, and no-argument startup. */
public final class BundledPlaylistChecks {
  static <T> T edt(Callable<T> work) throws Exception {
    FutureTask<T> task=new FutureTask<>(work);SwingUtilities.invokeAndWait(task);return task.get();
  }
  static JList<?> find(Component c) {
    if (c instanceof JList<?>) return (JList<?>)c;
    if (c instanceof Container) for (Component child:((Container)c).getComponents()) {
      JList<?> result=find(child);if(result!=null)return result;
    }
    return null;
  }
  public static void main(String[] args) throws Exception {
    Path home=Path.of(args[0]);
    Path file=home.resolve("playlists/webview.txt");byte[] bytes=Files.readAllBytes(file);
    JSONObject info=new JSONObject(Files.readString(home.resolve("playlists/webview.source.json")));
    String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    if(!hash.equals(info.getString("sha256")))throw new AssertionError("Snapshot bytes changed");
    Playlist playlist=Playlist.load(file.toString());
    if(playlist.rejected!=0||playlist.channels.size()!=info.getInt("channel_count"))throw new AssertionError("Missing/rejected built-in channels");
    int at=0,groups=0;
    for(String line:new String(bytes,StandardCharsets.UTF_8).split("\\R")) {
      if(line.isBlank())continue;
      if(line.endsWith(",#genre#")){groups++;continue;}
      int comma=line.indexOf(',');String url=line.substring(comma+1);
      if(!playlist.channels.get(at++).url().equals(url))throw new AssertionError("Signed URL/UJS changed at channel "+at);
    }
    if(groups!=info.getInt("group_count"))throw new AssertionError("Groups changed");
    System.setProperty("ntv.home",home.toString());WindowsTv.main(new String[0]);
    JFrame frame=null;
    try {
      long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);boolean loaded=false;
      while(System.nanoTime()<deadline) {
        loaded=edt(() -> {
          for(Frame f:Frame.getFrames())if(f.isShowing()){
            JList<?> list=find(f);if(list!=null&&list.getModel().getSize()==playlist.channels.size())return true;
          }
          return false;
        });
        if(loaded)break;Thread.sleep(50);
      }
      if(!loaded)throw new AssertionError("Default startup did not load bundled list");
      if(ProcessHandle.current().children().anyMatch(p -> p.info().command().orElse("").toLowerCase(Locale.ROOT).contains("ffplay")))
        throw new AssertionError("Built-in list unexpectedly started a channel");
      System.out.println("PASS exact upstream snapshot, "+at+" original channel URLs, "+groups+" groups and offline default startup");
    } finally {
      edt(() -> { for(Frame f:Frame.getFrames())f.dispose();return null; });
    }
  }
}
