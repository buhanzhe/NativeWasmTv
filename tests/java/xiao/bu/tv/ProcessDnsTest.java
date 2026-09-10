package xiao.bu.tv;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Dns;

public final class ProcessDnsTest {
    private static void check(boolean condition) { if (!condition) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Dns resolver = new Dns() {
            public List<InetAddress> lookup(String host) throws UnknownHostException {
                calls.incrementAndGet(); entered.countDown();
                try { release.await(); } catch (InterruptedException error) { throw new UnknownHostException(); }
                return Collections.singletonList(InetAddress.getByAddress(new byte[] {1,2,3,4}));
            }
        };
        final ProcessDns dns = new ProcessDns(resolver);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<List<InetAddress>> first = workers.submit(() -> dns.lookup("EXAMPLE.COM"));
            entered.await();
            Future<List<InetAddress>> second = workers.submit(() -> dns.lookup("example.com"));
            release.countDown();
            check(first.get().equals(second.get()));
            for (int i = 0; i < 500; i++) dns.lookup("example.com");
            check(calls.get() == 1);
            new ProcessDns(resolver).lookup("example.com");
            check(calls.get() == 2); // New client/process re-resolves.
        } finally { release.countDown(); workers.shutdownNow(); }
        final AtomicInteger attempts = new AtomicInteger();
        ProcessDns recovery = new ProcessDns(host -> {
            if (attempts.incrementAndGet() == 1) throw new UnknownHostException("offline");
            return Collections.singletonList(InetAddress.getByAddress(new byte[] {1,2,3,4}));
        });
        try { recovery.lookup("retry.test"); throw new AssertionError(); } catch (UnknownHostException expected) { }
        recovery.lookup("retry.test"); recovery.lookup("retry.test");
        check(attempts.get() == 2);
        System.out.println("ProcessDnsTest passed: concurrency, cache reuse, restart, failure recovery");
    }
}
