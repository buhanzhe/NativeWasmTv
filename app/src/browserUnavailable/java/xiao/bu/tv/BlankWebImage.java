package xiao.bu.tv;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.util.Locale;
import java.util.Map;

/** A successful, decodable image response; never stored under the original image URL. */
public final class BlankWebImage {
    private BlankWebImage() {}
    private static final byte[] PNG = {
        -119,80,78,71,13,10,26,10,0,0,0,13,73,72,68,82,0,0,0,1,0,0,0,1,8,6,0,0,0,31,21,-60,-119,
        0,0,0,13,73,68,65,84,120,-100,99,96,96,96,96,0,0,0,5,0,1,-91,-10,69,64,0,0,0,0,73,69,78,68,-82,66,96,-126
    };
    public static byte[] bytes() { return PNG.clone(); }
    public static InputStream open() { return new ByteArrayInputStream(PNG); }
    public static int length() { return PNG.length; }

    public static String header(Map<String,String> headers, String name) {
        if (headers != null) for (Map.Entry<String,String> entry : headers.entrySet())
            if (name.equalsIgnoreCase(entry.getKey())) return entry.getValue();
        return null;
    }

    /** Legacy WebView has no request destination; ambiguous API endpoints remain untouched. */
    public static boolean matches(String url, String method, boolean document, Map<String,String> headers) {
        if (document || !"GET".equals(method) || header(headers,"Range") != null || url == null) return false;
        try {
            URI uri = new URI(url);
            if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) return false;
            String destination = header(headers,"Sec-Fetch-Dest");
            if (destination != null) return "image".equalsIgnoreCase(destination);
            String accept = header(headers,"Accept");
            if (accept != null && accept.toLowerCase(Locale.US).startsWith("image/")) return true;
            String path = uri.getPath();
            return path != null && path.toLowerCase(Locale.US).matches(".*\\.(png|jpe?g|gif|webp|bmp|ico|svg|avif)$");
        } catch (Exception ignored) { return false; }
    }
}
