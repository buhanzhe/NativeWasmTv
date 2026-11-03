package xiao.bu.tv;
import android.os.SystemClock;
import java.util.LinkedHashMap;
import java.util.Map;
/** Speculative scripts keep their cache writes private until actual selection. */
final class Ku9WarmCache {
    private static final class Value {
        final Ku9SiteCache cache;final String key,value;final long until;
        Value(Ku9SiteCache c,String k,String v,double ttl){cache=c;key=k;value=v;until=ttl>0?SystemClock.elapsedRealtime()+(long)Math.min(ttl,Long.MAX_VALUE/2):0;}
    }
    private final Map<String,Value> writes=new LinkedHashMap<>();
    private boolean promoted;
    private int bytes;
    synchronized String get(Ku9SiteCache cache,String scope,String key){
        Value value=writes.get(scope+'\n'+key);
        return value==null?cache.get(key):value.until>0&&value.until<=SystemClock.elapsedRealtime()?"":value.value;
    }
    synchronized void put(Ku9SiteCache cache,String scope,String key,String value,double ttl){
        if(promoted){cache.put(key,value,ttl);return;}
        value=value==null?"":value;String id=scope+'\n'+key;Value previous=writes.get(id);
        int occupied=bytes-(previous==null?0:previous.value.length()*2)+value.length()*2;
        if((previous==null&&writes.size()>=32)||occupied>256*1024||key.length()>2048)throw new IllegalStateException("Speculative script cache quota");
        writes.put(id,new Value(cache,key,value,ttl));bytes=occupied;
    }
    synchronized void promote(){
        long now=SystemClock.elapsedRealtime();for(Value value:writes.values())if(value.until==0||value.until>now)value.cache.put(value.key,value.value,value.until==0?0:value.until-now);
        writes.clear();bytes=0;promoted=true;
    }
}
