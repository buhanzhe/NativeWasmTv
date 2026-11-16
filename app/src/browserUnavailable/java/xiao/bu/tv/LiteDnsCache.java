package xiao.bu.tv;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.Dns;

/** Bounded short-lived DNS reuse, with one lookup in flight per hostname.
 * Public answers never outlive the shortest returned CNAME/A/AAAA TTL.
 * Failures are never retained. LAN names always use the system resolver. */
final class LiteDnsCache implements Dns {
  private final Dns delegate;
  private final long lifetimeNanos;
  private final int capacity;
  private final Map<String,Entry> cache=new LinkedHashMap<String,Entry>(16,.75f,true);
  private final ConcurrentHashMap<String,FutureTask<List<InetAddress>>> pending=new ConcurrentHashMap<>();
  final AtomicLong hits=new AtomicLong(),misses=new AtomicLong(),joined=new AtomicLong();
  private static final class Entry {
    final List<InetAddress> values;final long expires;
    Entry(List<InetAddress> values,long expires){this.values=values;this.expires=expires;}
  }
  LiteDnsCache(Dns delegate){this(delegate,60,256);}
  Dns delegate(){return delegate;}
  LiteDnsCache(Dns delegate,long seconds,int capacity){this.delegate=delegate;this.lifetimeNanos=TimeUnit.SECONDS.toNanos(seconds);this.capacity=capacity;}
  public List<InetAddress> lookup(final String hostname)throws UnknownHostException {
    if(hostname==null||hostname.isEmpty())throw new UnknownHostException("Empty hostname");
    final String key=hostname.toLowerCase(Locale.US);long now=System.nanoTime();
    synchronized(cache){Entry e=cache.get(key);if(e!=null&&now<e.expires){hits.incrementAndGet();return e.values;}cache.remove(key);}
    FutureTask<List<InetAddress>> candidate=new FutureTask<>(()->{
      boolean local=key.indexOf(':')>=0||key.matches("[0-9.]+")||key.indexOf('.')<0||key.endsWith(".localhost")||key.endsWith(".local");
      List<InetAddress> resolved=(local?Dns.SYSTEM:delegate).lookup(hostname);
      if(resolved==null||resolved.isEmpty())throw new UnknownHostException(hostname);
      List<InetAddress> result=Collections.unmodifiableList(new ArrayList<>(resolved));
      long keep=lifetimeNanos;if(delegate instanceof PublicDns){long seconds=((PublicDns)delegate).responseTtlSeconds();keep=Math.min(keep,TimeUnit.SECONDS.toNanos(seconds<0?5:seconds));}
      if(keep>0)synchronized(cache){cache.put(key,new Entry(result,System.nanoTime()+keep));while(cache.size()>capacity)cache.remove(cache.keySet().iterator().next());}
      return result;
    });
    FutureTask<List<InetAddress>> task=pending.putIfAbsent(key,candidate);
    if(task==null){task=candidate;misses.incrementAndGet();task.run();}else joined.incrementAndGet();
    try{return task.get();}
    catch(InterruptedException e){Thread.currentThread().interrupt();UnknownHostException failure=new UnknownHostException("DNS interrupted: "+hostname);failure.initCause(e);throw failure;}
    catch(ExecutionException e){UnknownHostException failure=new UnknownHostException(hostname);failure.initCause(e.getCause());throw failure;}
    finally{if(task.isDone())pending.remove(key,task);}
  }
}
