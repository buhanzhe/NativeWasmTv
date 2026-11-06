package xiao.bu.tv.windows;

import xiao.bu.tv.WindowsCoreSession;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;

/** The production Windows session, held alive while its actual media is decoded. */
public final class RealChannelProbe {
  public static void main(String[] args) throws Exception {
    CountDownLatch ready=new CountDownLatch(1);AtomicBoolean failed=new AtomicBoolean();
    WindowsCoreSession session=new WindowsCoreSession(args[0],Path.of(args[1]),new WindowsCoreSession.Listener() {
      public void media(String url,Map<String,String> headers) {
        System.out.println("MEDIA_JSON "+new JSONObject().put("url",url).put("headers",new JSONObject(headers)));
        System.out.flush();ready.countDown();
      }
      public void failed(String message) {
        failed.set(true);System.out.println("FAILED_JSON "+new JSONObject().put("message",message));
        System.out.flush();ready.countDown();
      }
    });
    try {
      session.start();
      if(!ready.await(75,TimeUnit.SECONDS))throw new IOException("No media handoff within 75 seconds");
      if(failed.get())throw new IOException("Web session reported failure");
      // Single-byte reads avoid Wine's unsupported available0 query on a
      // redirected Windows stdin pipe; the same bounded ASCII protocol works
      // on native Windows and does not change the production session.
      StringBuilder line=new StringBuilder();int value;
      while((value=System.in.read())>=0) {
        if(value=='\r')continue;
        if(value=='\n') {
          String command=line.toString();line.setLength(0);
          if(command.equals("STOP"))break;
          if(command.startsWith("TIME "))session.playbackTime(Double.parseDouble(command.substring(5)));
        } else {
          if(line.length()>=128)throw new IOException("Control line too long");
          line.append((char)value);
        }
      }
    } finally {
      session.close();
      if(!session.awaitClosed(8000))throw new IOException("Native session did not close");
    }
  }
}
