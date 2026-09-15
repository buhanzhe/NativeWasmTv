package xiao.bu.tv;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.os.Bundle;
import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;

/** A cover must still load after its origin server goes offline. No user catalog changes. */
public final class AlbumArtCacheInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle(); int status = -1;
        try (ServerSocket server = new ServerSocket(0)) {
            Bitmap source = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
            source.eraseColor(0xff3b7abc);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            source.compress(Bitmap.CompressFormat.PNG, 100, output); source.recycle();
            byte[] png = output.toByteArray();
            server.setSoTimeout(10000);
            Thread origin = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
                    String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) { }
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: "
                            + png.length + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
                    socket.getOutputStream().write(png);
                    socket.getOutputStream().flush();
                } catch (Exception ignored) { }
            });
            origin.start();
            String url = "http://127.0.0.1:" + server.getLocalPort() + "/cover-" + System.nanoTime() + ".png";
            NetworkClient.initialize(getTargetContext());
            Method read = AlbumArtLoader.class.getDeclaredMethod("readArtwork", android.content.Context.class,
                    String.class, String.class, boolean.class);
            read.setAccessible(true);
            Bitmap first = (Bitmap) read.invoke(null, getTargetContext(), url, null, true);
            if (first == null) throw new AssertionError("Initial cover download failed");
            first.recycle(); origin.join(10000); server.close();
            Bitmap cached = (Bitmap) read.invoke(null, getTargetContext(), url, null, true);
            if (cached == null || cached.getPixel(0, 0) != 0xff3b7abc)
                throw new AssertionError("Cover failed after origin shut down");
            cached.recycle();
            result.putString("stream", "PASS cover served from OkHttp disk cache with origin offline\n");
        } catch (Throwable error) {
            status = 0; result.putString("stream", android.util.Log.getStackTraceString(error));
        }
        finish(status, result);
    }
}
