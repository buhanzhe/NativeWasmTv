package xiao.bu.tv;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import xiao.bu.tv.AppLog;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Durable advisory metadata. URLs/credentials and channel availability are never stored. */
final class ChannelDecodeHints {
    private static final int MEMORY_LIMIT = 512;
    private static final long RETRY_MS = 5 * 60 * 1000L;
    private static final String[] COLUMNS = {"source_key","w","h","codec","depth","scan","fps","origin","updated"};
    static final class Hint {
        final int width, height, depth, origin;
        final String codec, scan;
        final float fps;
        final long updated;
        Hint(int w, int h, String c, int d, String s, float f, int o, long at) {
            width=w; height=h; codec=c; depth=d; scan=s; fps=f; origin=o; updated=at;
        }
        boolean valid() {
            return width>0 && height>0 && codec!=null && !"--".equals(codec) && !codec.isEmpty()
                    && depth>0 && depth<32 && ("interlaced".equals(scan) || "progressive".equals(scan))
                    && fps>=1f && fps<=240f;
        }
        boolean same(Hint other) {
            return other!=null && width==other.width && height==other.height && depth==other.depth
                    && codec.equals(other.codec) && scan.equals(other.scan) && Math.abs(fps-other.fps)<0.15f;
        }
        boolean interlaced50() { return height==1080 && "interlaced".equals(scan) && fps>=24f && fps<=26f; }
        VideoStreamDescription description() {
            return new VideoStreamDescription(depth | (1L<<24) | (("interlaced".equals(scan)?2L:1L)<<27));
        }
    }
    private static final class Store extends SQLiteOpenHelper {
        Store(Context c) { super(c,"channel_decode_hints.db",null,2); }
        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS hints (source_key TEXT PRIMARY KEY,w INTEGER,h INTEGER,codec TEXT,depth INTEGER,scan TEXT,fps REAL,origin INTEGER NOT NULL DEFAULT 0,updated INTEGER NOT NULL DEFAULT 0)");
        }
        @Override public void onOpen(SQLiteDatabase db) { super.onOpen(db); onCreate(db); }
        @Override public void onUpgrade(SQLiteDatabase db,int from,int to) {
            if(from<2) {
                db.execSQL("ALTER TABLE hints RENAME TO hints_legacy");
                onCreate(db);
                // Preserve complete records even past their old expiry deadline.
                db.execSQL("INSERT INTO hints SELECT source_key,w,h,codec,depth,scan,fps,0,COALESCE(expires,0) FROM hints_legacy WHERE w>0 AND h>0 AND depth>0 AND depth<32 AND codec IS NOT NULL AND codec<>'' AND codec<>'--' AND scan IN ('progressive','interlaced') AND fps>=1 AND fps<=240");
                db.execSQL("DROP TABLE hints_legacy");
            }
        }
    }
    private final Store store;
    private final HandlerThread thread = new HandlerThread("channel-decode-hints", android.os.Process.THREAD_PRIORITY_BACKGROUND);
    private final Handler worker;
    private final SniffedMediaProbe probe = new SniffedMediaProbe();
    private final Map<String,Hint> memory = new LinkedHashMap<String,Hint>(32,.75f,true);
    private final Set<String> pendingReads = Collections.newSetFromMap(new ConcurrentHashMap<String,Boolean>());
    private final Set<String> missing = Collections.newSetFromMap(new ConcurrentHashMap<String,Boolean>());
    private final Map<String,Long> retryAfter = new ConcurrentHashMap<>();
    private final Map<String,Long> invalidatedAt = new ConcurrentHashMap<>();
    private final AtomicInteger generation = new AtomicInteger();
    private final Object scheduling = new Object();
    private volatile String activeKey;
    private volatile boolean closed;
    ChannelDecodeHints(Context context) {
        store=new Store(context.getApplicationContext()); thread.start(); worker=new Handler(thread.getLooper());
        worker.post(() -> {
            List<String> keys=new ArrayList<>();List<Hint> hints=new ArrayList<>();
            try(Cursor c=store.getReadableDatabase().query("hints",COLUMNS,null,null,null,null,"updated DESC","512")) {
                while(c.moveToNext()) {Hint hint=read(c);if(hint.valid()){keys.add(c.getString(0));hints.add(hint);}}
                for(int n=keys.size()-1;n>=0;n--)cache(keys.get(n),hints.get(n));
            } catch(RuntimeException e) { AppLog.w("ChannelDecodeHints","Unable to load advisory metadata",e); }
        });
    }
    static String key(String url) { return MediaTrackSelection.urlKey("decode_hint",url); }
    private static Hint read(Cursor c) {
        return new Hint(c.getInt(1),c.getInt(2),c.getString(3),c.getInt(4),c.getString(5),c.getFloat(6),c.getInt(7),c.getLong(8));
    }
    private Hint cached(String key) { synchronized(memory) {return memory.get(key);} }
    private void cache(String key,Hint hint) {
        synchronized(memory) {
            memory.put(key,hint);
            while(memory.size()>MEMORY_LIMIT)memory.remove(memory.keySet().iterator().next());
        }
        missing.remove(key); retryAfter.remove(key);
    }
    private void markMissing(String key) {
        if(missing.size()>=MEMORY_LIMIT)missing.clear();
        missing.add(key);
    }
    /** UI reads never block on SQLite. The scheduling worker checks disk before any probe. */
    Hint get(String url) {
        if(url==null || closed)return null;
        String key=key(url);Hint hint=cached(key);
        if(hint==null && !missing.contains(key) && pendingReads.add(key))worker.post(() -> {
            try {if(!closed)lookup(key);}finally{pendingReads.remove(key);}
        });
        return hint;
    }
    private Hint lookup(String key) {
        Hint hint=cached(key);
        if(hint!=null || missing.contains(key))return hint;
        try(Cursor c=store.getReadableDatabase().query("hints",COLUMNS,"source_key=?",new String[]{key},null,null,null)) {
            if(c.moveToFirst()) {hint=read(c);if(hint.valid()){cache(key,hint);return hint;}}
            markMissing(key);
        }catch(RuntimeException e){markMissing(key);AppLog.w("ChannelDecodeHints","Unable to read advisory metadata",e);}
        return null;
    }
    void remember(String url,int w,int h,String codec,VideoStreamDescription description,float fps) {
        if(url==null || closed)return;
        Hint hint=new Hint(w,h,codec,description.bitDepth,description.scanType,fps,1,System.currentTimeMillis());
        if(!hint.valid())return;
        String key=key(url);Hint old=cached(key);
        if(old!=null && old.origin==1 && hint.same(old))return;
        worker.post(() -> save(key,hint));
    }
    private void save(String key,Hint hint) {
        Hint old=lookup(key);
        // Current playback outranks an auxiliary probe, including a late cancelled one.
        if(old!=null && (old.origin>hint.origin || old.origin==hint.origin && hint.same(old)))return;
        cache(key,hint);
        try {
            ContentValues values=new ContentValues();values.put("source_key",key);values.put("w",hint.width);values.put("h",hint.height);
            values.put("codec",hint.codec);values.put("depth",hint.depth);values.put("scan",hint.scan);values.put("fps",hint.fps);
            values.put("origin",hint.origin);values.put("updated",hint.updated);
            store.getWritableDatabase().insertWithOnConflict("hints",null,values,SQLiteDatabase.CONFLICT_REPLACE);
        }catch(RuntimeException e){AppLog.w("ChannelDecodeHints","Unable to save advisory metadata",e);}
    }
    void invalidate(String url) {
        if(url==null || closed)return;
        String key=key(url);synchronized(memory){memory.remove(key);}markMissing(key);
        long now=SystemClock.elapsedRealtime();invalidatedAt.put(key,now);
        for(Map.Entry<String,Long> entry:invalidatedAt.entrySet())
            if(now-entry.getValue()>60000L)invalidatedAt.remove(entry.getKey());
        defer(key,5000L);
        worker.post(() -> {
            synchronized(memory){memory.remove(key);}markMissing(key);
            try {store.getWritableDatabase().delete("hints","source_key=?",new String[]{key});}
            catch(RuntimeException e){AppLog.w("ChannelDecodeHints","Unable to invalidate hint",e);}
        });
    }
    private void defer(String key,long delay) {
        if(retryAfter.size()>=MEMORY_LIMIT)retryAfter.clear();
        retryAfter.put(key,SystemClock.elapsedRealtime()+delay);
    }
    void cancel() {
        synchronized(scheduling) {
            generation.incrementAndGet();
            String key=activeKey;activeKey=null;
            if(key!=null)defer(key,5000L); // Rapid tuning must not restart the same incomplete probe repeatedly.
            probe.clear();
        }
    }
    void schedule(List<String> sources) {
        final int expected;
        synchronized(scheduling) {if(closed)return;cancel();expected=generation.get();}
        List<String> unique=new ArrayList<>(new LinkedHashSet<>(sources));unique.remove(null);unique.remove("");
        worker.post(() -> next(unique,0,expected));
    }
    private void acceptProbe(String key,Hint hint,long startedAt,boolean ready) {
        Long invalidated=invalidatedAt.get(key);
        // Do not resurrect an explicitly rejected type from an older probe.
        if(invalidated!=null && invalidated>=startedAt)return;
        if(ready && hint.valid())save(key,hint);
        else defer(key,RETRY_MS);
    }
    private void next(List<String> sources,int index,int expected) {
        if(closed || expected!=generation.get() || index>=sources.size())return;
        String url=sources.get(index),key=key(url);
        Long retry=retryAfter.get(key);
        if(lookup(key)!=null || retry!=null && retry>SystemClock.elapsedRealtime()) {
            worker.post(() -> next(sources,index+1,expected));return;
        }
        synchronized(scheduling) {
            if(closed || expected!=generation.get())return;
            activeKey=key;
            final long startedAt=SystemClock.elapsedRealtime();
            probe.submitLightweight(url,result -> worker.post(() -> {
                if(closed)return;
                Hint hint=new Hint(result.width,result.height,result.videoCodec,result.videoDescription.bitDepth,
                        result.videoDescription.scanType,result.frameRate,0,System.currentTimeMillis());
                // A completed result is useful even after tuning moved the neighbor window.
                acceptProbe(key,hint,startedAt,"ready".equals(result.status));
                if(expected!=generation.get())return;
                activeKey=null;
                AppLog.i("ChannelDecodeHints","Preparse neighbor "+(index+1)+"/"+sources.size()+" status="+result.status
                        +" scan="+result.videoDescription.scanType+" fps="+result.frameRate);
                worker.postDelayed(() -> next(sources,index+1,expected),1000L);
            }));
        }
    }
    void close() {
        if(closed)return;
        closed=true;cancel();probe.close();
        // Drain previously queued playback updates before closing the durable store.
        worker.post(() -> {store.close();thread.quit();});
    }
}
