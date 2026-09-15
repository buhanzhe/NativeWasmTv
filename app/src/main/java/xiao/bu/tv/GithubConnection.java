package xiao.bu.tv;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Map;

/** Lazy GET/HEAD failover preserves caller headers, redirects and download streams. */
final class GithubConnection extends HttpURLConnection {
    interface Opener { HttpURLConnection open(URL url) throws IOException; }
    private final Opener opener;
    private volatile HttpURLConnection active;
    private boolean responseReady;
    private volatile boolean cancelled;
    private IOException failure;

    GithubConnection(URL url, Opener opener) { super(url); this.opener = opener; }
    private HttpURLConnection create(String target, boolean retryable) throws IOException {
        HttpURLConnection connection = opener.open(new URL(target));
        connection.setConnectTimeout(retryable ? bounded(getConnectTimeout(), 4000) : getConnectTimeout());
        connection.setReadTimeout(retryable ? bounded(getReadTimeout(), 8000) : getReadTimeout());
        connection.setInstanceFollowRedirects(getInstanceFollowRedirects());
        connection.setUseCaches(getUseCaches()); connection.setIfModifiedSince(getIfModifiedSince());
        connection.setDoInput(getDoInput()); connection.setDoOutput(getDoOutput());
        connection.setRequestMethod(getRequestMethod());
        for (Map.Entry<String, List<String>> header : getRequestProperties().entrySet())
            for (String value : header.getValue()) connection.addRequestProperty(header.getKey(), value);
        if (fixedContentLength >= 0) connection.setFixedLengthStreamingMode(fixedContentLength);
        else if (chunkLength > 0) connection.setChunkedStreamingMode(chunkLength);
        active = connection;
        if (cancelled) { connection.disconnect(); throw new IOException("GitHub request cancelled"); }
        return connection;
    }
    private static int bounded(int value, int max) { return value <= 0 ? max : Math.min(value, max); }
    private synchronized HttpURLConnection response() throws IOException {
        if (cancelled) throw new IOException("GitHub request cancelled");
        if (failure != null) throw failure;
        if (responseReady) return active;
        if (active != null) { active.getResponseCode(); responseReady = true; return active; }
        boolean canRetry = !getDoOutput() && ("GET".equals(method) || "HEAD".equals(method));
        String[] routes = canRetry ? GithubProxy.candidates(url.toString()) : new String[] {url.toString()};
        IOException last = null;
        for (int i = 0; i < routes.length; i++) {
            HttpURLConnection connection = null;
            try {
                connection = create(routes[i], canRetry);
                int status = connection.getResponseCode();
                if (canRetry && GithubProxy.retryable(status)) {
                    GithubProxy.failed(routes[i]);
                    if (i + 1 < routes.length) { connection.disconnect(); continue; }
                } else if (status >= 200 && status < 400) GithubProxy.succeeded(routes[i]);
                responseReady = true; connected = true; return connection;
            } catch (IOException error) {
                last = error;
                if (canRetry) GithubProxy.failed(routes[i]);
                if (connection != null) connection.disconnect();
                if (cancelled) break;
            }
        }
        failure = last == null ? new IOException("No GitHub accelerator available") : last;
        throw failure;
    }
    @Override public void connect() throws IOException { response(); }
    @Override public int getResponseCode() throws IOException { return response().getResponseCode(); }
    @Override public String getResponseMessage() throws IOException { return response().getResponseMessage(); }
    @Override public InputStream getInputStream() throws IOException { return response().getInputStream(); }
    @Override public InputStream getErrorStream() { try { return response().getErrorStream(); } catch (IOException error) { return null; } }
    @Override public OutputStream getOutputStream() throws IOException {
        if (active == null) create(url.toString(), false);
        return active.getOutputStream();
    }
    @Override public String getHeaderField(String name) { try { return response().getHeaderField(name); } catch (IOException error) { return null; } }
    @Override public String getHeaderField(int index) { try { return response().getHeaderField(index); } catch (IOException error) { return null; } }
    @Override public String getHeaderFieldKey(int index) { try { return response().getHeaderFieldKey(index); } catch (IOException error) { return null; } }
    @Override public Map<String, List<String>> getHeaderFields() { try { return response().getHeaderFields(); } catch (IOException error) { return java.util.Collections.emptyMap(); } }
    @Override public URL getURL() { return active == null ? url : active.getURL(); }
    @Override public void disconnect() { cancelled = true; HttpURLConnection connection = active; if (connection != null) connection.disconnect(); }
    @Override public boolean usingProxy() { return active != null && active.usingProxy(); }
}
