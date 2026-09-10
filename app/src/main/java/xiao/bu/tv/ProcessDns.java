package xiao.bu.tv;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import okhttp3.Dns;

/** One successful resolution per hostname for this network client's process lifetime. */
final class ProcessDns implements Dns {
    private final Dns delegate;
    private final ConcurrentHashMap<String, FutureTask<List<InetAddress>>> entries =
            new ConcurrentHashMap<String, FutureTask<List<InetAddress>>>();

    ProcessDns(Dns delegate) { this.delegate = delegate; }

    @Override public List<InetAddress> lookup(final String hostname) throws UnknownHostException {
        if (hostname == null || hostname.length() == 0) throw new UnknownHostException("Empty hostname");
        String key = hostname.toLowerCase(Locale.US);
        FutureTask<List<InetAddress>> task = entries.get(key);
        if (task == null) {
        FutureTask<List<InetAddress>> candidate = new FutureTask<List<InetAddress>>(
                new Callable<List<InetAddress>>() {
                    @Override public List<InetAddress> call() throws UnknownHostException {
                        List<InetAddress> result = delegate.lookup(hostname);
                        if (result == null || result.isEmpty()) throw new UnknownHostException(hostname);
                        return Collections.unmodifiableList(new ArrayList<InetAddress>(result));
                    }
                });
        task = entries.putIfAbsent(key, candidate);
        if (task == null) { task = candidate; task.run(); }
        }
        try {
            return task.get();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            UnknownHostException failure = new UnknownHostException("DNS interrupted: " + hostname);
            failure.initCause(error);
            throw failure;
        } catch (ExecutionException error) {
            // A temporary outage must not poison this host until the next launch.
            entries.remove(key, task);
            UnknownHostException failure = new UnknownHostException(hostname);
            failure.initCause(error.getCause());
            throw failure;
        }
    }
}
