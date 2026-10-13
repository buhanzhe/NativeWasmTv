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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import okhttp3.Dns;

/** Successful process-local DNS cache with bounded callers and shared in-flight lookups. */
final class ProcessDns implements Dns {
    private static final long WAIT_MS = 5000L;
    // Android's system resolver may ignore interruption. Never create a thread per
    // timeout. A small bounded queue admits normal multi-host page bursts. Keep the resolver
    // and TLS policy; overload fails closed instead of selecting another DNS route.
    private static final ThreadPoolExecutor LOOKUPS = new ThreadPoolExecutor(
            2, 2, 30L, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(8),
            new ThreadFactory() {
                @Override public Thread newThread(Runnable task) {
                    Thread thread = new Thread(task, "ntv-dns-resolver");
                    thread.setDaemon(true);
                    return thread;
                }
            });
    static { LOOKUPS.allowCoreThreadTimeOut(true); }

    private static class LookupTask extends FutureTask<List<InetAddress>> {
        final long deadline;
        LookupTask(Callable<List<InetAddress>> work, long deadline) {
            super(work);
            this.deadline = deadline;
        }
    }

    private final Dns delegate;
    private final long waitMs;
    private final ConcurrentHashMap<String, LookupTask> entries =
            new ConcurrentHashMap<String, LookupTask>();

    ProcessDns(Dns delegate) { this(delegate, WAIT_MS); }

    /** Package-local shorter deadline for deterministic tests; production uses WAIT_MS. */
    ProcessDns(Dns delegate, long waitMs) {
        if (delegate == null || waitMs <= 0 || waitMs > WAIT_MS)
            throw new IllegalArgumentException("Invalid DNS resolver or deadline");
        this.delegate = delegate;
        this.waitMs = waitMs;
    }

    void invalidate(String hostname) {
        if (hostname != null) entries.remove(hostname.toLowerCase(Locale.US));
    }

    @Override public List<InetAddress> lookup(final String hostname) throws UnknownHostException {
        if (hostname == null || hostname.length() == 0) throw new UnknownHostException("Empty hostname");
        final String key = hostname.toLowerCase(Locale.US);
        LookupTask task = entries.get(key);
        if (task == null) {
            final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMs);
            LookupTask candidate = new LookupTask(
                    new Callable<List<InetAddress>>() {
                        @Override public List<InetAddress> call() throws UnknownHostException {
                            // Queued work must not start a resolver after its shared budget.
                            if (System.nanoTime() >= deadline)
                                throw new UnknownHostException("DNS queue deadline: " + hostname);
                            List<InetAddress> result = delegate.lookup(hostname);
                            if (result == null || result.isEmpty()) throw new UnknownHostException(hostname);
                            return Collections.unmodifiableList(new ArrayList<InetAddress>(result));
                        }
                    }, deadline) {
                @Override protected void done() {
                    try { get(); }
                    catch (Exception failed) {
                        // Do not erase a replacement installed after invalidation.
                        entries.remove(key, this);
                    }
                }
            };
            task = entries.putIfAbsent(key, candidate);
            if (task == null) {
                task = candidate;
                try { LOOKUPS.execute(candidate); }
                catch (RejectedExecutionException busy) {
                    entries.remove(key, candidate);
                    candidate.cancel(false);
                    throw failure("DNS resolver busy: " + hostname, busy);
                }
            }
        }
        try {
            return task.get(Math.max(0L, task.deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            // Remove only work still queued after the shared absolute deadline.
            // Running tasks stay shared, avoiding duplicate uninterruptible lookups.
            if (LOOKUPS.remove(task)) {
                entries.remove(key, task);
                task.cancel(false);
            }
            throw failure("DNS lookup timed out: " + hostname, timeout);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            // A cancelled caller must not cancel another caller's shared lookup.
            throw failure("DNS interrupted: " + hostname, interrupted);
        } catch (ExecutionException error) {
            entries.remove(key, task);
            throw failure(hostname, error.getCause());
        } catch (java.util.concurrent.CancellationException cancelled) {
            throw failure("DNS resolver busy: " + hostname, cancelled);
        }
    }

    private static UnknownHostException failure(String message, Throwable cause) {
        UnknownHostException failure = new UnknownHostException(message);
        failure.initCause(cause);
        return failure;
    }
}
