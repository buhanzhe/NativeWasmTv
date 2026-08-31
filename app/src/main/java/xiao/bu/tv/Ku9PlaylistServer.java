package xiao.bu.tv;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.Charset;

/** Serves the latest generated live playlist so the player can poll it normally. */
final class Ku9PlaylistServer implements Closeable {
    private static final String TAG = "Ku9PlaylistServer";
    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private ServerSocket socket;
    private volatile boolean running;
    private volatile byte[] playlist = new byte[0];

    void start() throws IOException {
        socket = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        running = true;
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "ku9-live-playlist");
        thread.setDaemon(true);
        thread.start();
    }

    void update(String content) {
        playlist = content.getBytes(UTF_8);
    }

    String url() {
        return "http://127.0.0.1:" + socket.getLocalPort() + "/live.m3u8";
    }

    private void acceptLoop() {
        while (running) {
            try {
                handle(socket.accept());
            } catch (SocketException error) {
                if (running) {
                    Log.w(TAG, "Playlist socket failed", error);
                }
            } catch (IOException error) {
                if (running) {
                    Log.w(TAG, "Playlist request failed", error);
                }
            }
        }
    }

    private void handle(Socket client) throws IOException {
        try {
            client.setSoTimeout(3000);
            consumeHeaders(new BufferedInputStream(client.getInputStream()));
            byte[] body = playlist;
            BufferedOutputStream output = new BufferedOutputStream(client.getOutputStream());
            output.write(("HTTP/1.1 200 OK\r\n"
                    + "Content-Type: application/vnd.apple.mpegurl\r\n"
                    + "Cache-Control: no-store, no-cache, must-revalidate\r\n"
                    + "Connection: close\r\n"
                    + "Content-Length: " + body.length + "\r\n\r\n").getBytes(UTF_8));
            output.write(body);
            output.flush();
        } finally {
            try {
                client.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void consumeHeaders(BufferedInputStream input) throws IOException {
        int matched = 0;
        int read = 0;
        int current;
        while (read++ < 16 * 1024 && (current = input.read()) != -1) {
            if ((matched == 0 || matched == 2) && current == '\r') {
                matched++;
            } else if ((matched == 1 || matched == 3) && current == '\n') {
                if (++matched == 4) {
                    return;
                }
            } else {
                matched = current == '\r' ? 1 : 0;
            }
        }
    }

    @Override
    public void close() {
        running = false;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            socket = null;
        }
    }
}
