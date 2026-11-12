package xiao.bu.tv;

import android.content.Context;
import android.content.SharedPreferences;
import xiao.bu.tv.AppLog;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.zip.GZIPInputStream;

final class EpgManager {
    static final String DEFAULT_URL = "https://github.com/TvWasm/autoEPG/releases/latest/download/epg.xml";
    private static final String FALLBACK_URL = "http://epg.51zmt.top:8000/e.xml.gz";
    private static final String TAG = "EpgManager";
    private static final String CACHE_FILE = "epg-guide-cache.xml";
    private static final String CACHE_PREFS = "epg_cache";
    private static final String CACHE_SOURCE_URL = "source_url";
    private static final String CACHE_RESOLVED_URL = "resolved_url";
    private static final String CACHE_METADATA = "remote_metadata_v1";
    private static final int MAX_DOWNLOAD_BYTES = 8 * 1024 * 1024;
    private static final int MAX_XML_BYTES = 64 * 1024 * 1024;
    private static final long KEEP_FUTURE_MS = 36L * 60L * 60L * 1000L;

    interface Listener {
        void onUpdated();
    }

    private static final long KEEP_PAST_MS=IptvCatchup.MAX_DAYS*86400000L;

    static final class Program {
        final long startMillis;
        final long stopMillis;
        final String title;
        final String catchupId;

        Program(long startMillis, long stopMillis, String title) {
            this(startMillis,stopMillis,title,"");
        }
        Program(long startMillis,long stopMillis,String title,String catchupId){
            this.catchupId=catchupId==null?"":catchupId;
            this.startMillis = startMillis;
            this.stopMillis = stopMillis;
            this.title = title;
        }

        boolean isPlaying(long now) {
            return now >= startMillis && now < stopMillis;
        }
    }

    private static final class Guide {
        final Object revision = new Object();
        final Map<String, String> channelByAlias = new HashMap<String, String>();
        final Map<String, String> logosByChannel = new HashMap<String, String>();
        final Map<String, List<Program>> programsByChannel =
                new HashMap<String, List<Program>>();
    }

    private static final class EpgDatabase extends android.database.sqlite.SQLiteOpenHelper {
        EpgDatabase(Context c){super(c,"epg-guide.db",null,2);}
        public void onCreate(android.database.sqlite.SQLiteDatabase db){
            db.execSQL("CREATE TABLE sources(source TEXT PRIMARY KEY,resolved TEXT,validated INTEGER)");
            db.execSQL("CREATE TABLE aliases(source TEXT,alias TEXT,channel TEXT)");
            db.execSQL("CREATE TABLE logos(source TEXT,channel TEXT,url TEXT)");
            db.execSQL("CREATE TABLE programmes(source TEXT,channel TEXT,start INTEGER,stop INTEGER,title TEXT,catchup TEXT)");
            db.execSQL("CREATE INDEX programme_source ON programmes(source,channel,start)");
            db.execSQL("CREATE INDEX alias_source ON aliases(source)");
            db.execSQL("CREATE INDEX logo_source ON logos(source)");
        }
        public void onUpgrade(android.database.sqlite.SQLiteDatabase db,int oldVersion,int newVersion){
            // v1 stored lossy channel identities (CCTV4 regions/4K collided).
            // Rebuild this derived index from the retained XML backup once.
            for(String table:new String[]{"aliases","logos","programmes","sources"})
                db.execSQL("DROP TABLE IF EXISTS "+table);
            onCreate(db);
        }
    }
    private final EpgDatabase database;
    private static final class StoredGuide {
        final Guide guide;final String resolved;final long validated;
        StoredGuide(Guide g,String r,long v){guide=g;resolved=r;validated=v;}
    }
    private final Object databaseLock=new Object();
    // One disk writer prevents superseded downloads from repopulating a rebuild.
    private final java.util.concurrent.Executor cacheWorker = new java.util.concurrent.ThreadPoolExecutor(
            0,1,30L,java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.LinkedBlockingQueue<Runnable>(), runnable -> {
                Thread thread=new Thread(runnable,"epg-cache-worker");thread.setDaemon(true);return thread;
            });

    private void clearPersistentCache() throws IOException {
        synchronized(databaseLock){
            android.database.sqlite.SQLiteDatabase db=database.getWritableDatabase();
            db.beginTransaction();
            try{
                for(String table:new String[]{"aliases","logos","programmes","sources"})db.delete(table,null,null);
                db.setTransactionSuccessful();
            }finally{db.endTransaction();}
            if(!context.getSharedPreferences(CACHE_PREFS,Context.MODE_PRIVATE).edit().clear().commit())
                throw new IOException("节目单缓存信息清理失败");
            File[] files=context.getFilesDir().listFiles();
            if(files!=null)for(File file:files){
                String name=file.getName();
                if((CACHE_FILE.equals(name)||name.startsWith("epg-guide-cache-")&&name.endsWith(".xml"))
                        && file.isFile()&&!file.delete())throw new IOException("节目单 XML 缓存清理失败");
            }
        }
    }
    private StoredGuide readDatabase(String source){
        synchronized(databaseLock){
            try{
                android.database.sqlite.SQLiteDatabase db=database.getReadableDatabase();
                String[] args={source};String resolved;long validated;
                android.database.Cursor cursor=db.rawQuery("SELECT resolved,validated FROM sources WHERE source=?",args);
                try{if(!cursor.moveToFirst())return null;resolved=cursor.getString(0);validated=cursor.getLong(1);}finally{cursor.close();}
                Guide restored=new Guide();
                cursor=db.rawQuery("SELECT alias,channel FROM aliases WHERE source=?",args);
                try{while(cursor.moveToNext())restored.channelByAlias.put(cursor.getString(0),cursor.getString(1));}finally{cursor.close();}
                cursor=db.rawQuery("SELECT channel,url FROM logos WHERE source=?",args);
                try{while(cursor.moveToNext())restored.logosByChannel.put(cursor.getString(0),cursor.getString(1));}finally{cursor.close();}
                long now=System.currentTimeMillis();
                cursor=db.rawQuery("SELECT channel,start,stop,title,catchup FROM programmes WHERE source=? AND stop>? AND start<? ORDER BY channel,start,stop",
                        new String[]{source,Long.toString(now-KEEP_PAST_MS),Long.toString(now+KEEP_FUTURE_MS)});
                try{while(cursor.moveToNext()){
                    String channel=cursor.getString(0);List<Program> programmes=restored.programsByChannel.get(channel);
                    if(programmes==null){programmes=new ArrayList<Program>();restored.programsByChannel.put(channel,programmes);}
                    programmes.add(new Program(cursor.getLong(1),cursor.getLong(2),cursor.getString(3),cursor.getString(4)));
                }}finally{cursor.close();}
                return new StoredGuide(restored,resolved,validated);
            }catch(android.database.SQLException error){AppLog.w(TAG,"Unable to read SQLite EPG",error);return null;}
        }
    }
    private void saveDatabase(String source,String resolved,Guide snapshot,long validated){
        synchronized(databaseLock){
            android.database.sqlite.SQLiteDatabase db=database.getWritableDatabase();db.beginTransaction();
            try{
                String[] args={source};
                for(String table:new String[]{"aliases","logos","programmes","sources"})db.delete(table,"source=?",args);
                android.database.sqlite.SQLiteStatement row=db.compileStatement("INSERT INTO programmes VALUES(?,?,?,?,?,?)");
                try{for(Map.Entry<String,List<Program>> entry:snapshot.programsByChannel.entrySet())for(Program programme:entry.getValue()){
                    row.bindString(1,source);row.bindString(2,entry.getKey());row.bindLong(3,programme.startMillis);row.bindLong(4,programme.stopMillis);
                    row.bindString(5,programme.title);row.bindString(6,programme.catchupId);row.executeInsert();
                }}finally{row.close();}
                row=db.compileStatement("INSERT INTO aliases VALUES(?,?,?)");
                try{for(Map.Entry<String,String> alias:snapshot.channelByAlias.entrySet()){
                    row.bindString(1,source);row.bindString(2,alias.getKey());row.bindString(3,alias.getValue());row.executeInsert();
                }}finally{row.close();}
                row=db.compileStatement("INSERT INTO logos VALUES(?,?,?)");
                try{for(Map.Entry<String,String> logo:snapshot.logosByChannel.entrySet()){
                    row.bindString(1,source);row.bindString(2,logo.getKey());row.bindString(3,logo.getValue());row.executeInsert();
                }}finally{row.close();}
                android.content.ContentValues meta=new android.content.ContentValues();meta.put("source",source);meta.put("resolved",resolved);meta.put("validated",validated);
                db.insertOrThrow("sources",null,meta);db.setTransactionSuccessful();
            }finally{db.endTransaction();}
        }
    }
    private boolean freshDatabase(long validated){
        long now=System.currentTimeMillis();return validated<=now&&dayStart(validated)==dayStart(now);
    }

    private StoredGuide readCachedGuide(String source,int index){
        synchronized(databaseLock){
            StoredGuide stored=readDatabase(source);
            if(stored!=null)return stored;
            CacheSlot slot=cacheSlot(source,index);
            SharedPreferences prefs=context.getSharedPreferences(CACHE_PREFS,Context.MODE_PRIVATE);
            if(!source.equals(prefs.getString(slot.sourceKey,"")))return null;
            try{
                Guide restored=readCache(slot.file);
                if(restored==null)return null;
                String resolved=prefs.getString(slot.resolvedKey,source);
                resolveLogos(restored,resolved);
                long validated=slot.file.lastModified();
                saveDatabase(source,resolved,restored,validated);
                return new StoredGuide(restored,resolved,validated);
            }catch(Exception error){AppLog.w(TAG,"Unable to rebuild SQLite EPG from XML",error);return null;}
        }
    }

    private final Context context;
    private volatile Guide guide = new Guide();
    private volatile boolean loading;
    private volatile int refreshGeneration;
    private volatile HttpURLConnection activeDownload;
    private int workerGeneration;
    private volatile String lastError = "";
    private volatile String loadedUrl = "";
    private String attemptedSource;
    private long attemptedDay;
    private long attemptedAt;

    EpgManager(Context context) {
        this.context = context.getApplicationContext();
        this.database=new EpgDatabase(this.context);
    }

    boolean isLoading() {
        return loading;
    }

    String getLastError() {
        return lastError;
    }

    String getLoadedUrl() {
        return loadedUrl;
    }

    List<Program> programsFor(Channel channel) {
        return programsFor(guide, channel);
    }

    private static List<Program> programsFor(Guide snapshot, Channel channel) {
        if (channel == null) {
            return Collections.emptyList();
        }
        String channelId = channelIdFor(snapshot, channel);
        List<Program> result = channelId == null
                ? null : snapshot.programsByChannel.get(channelId);
        return result == null ? Collections.<Program>emptyList() : result;
    }

    /** One entry per recycled row: no alias normalization or guide scan during cursor moves. */
    static final class CurrentProgram {
        private Object revision;
        private Channel channel;
        private long from, until;
        private String title = "";

        String titleFor(EpgManager manager, Channel value, long now) {
            Guide next = manager == null ? null : manager.guide;
            Object token = next == null ? null : next.revision;
            if (token == revision && value == channel && now >= from && now < until) return title;
            // Keep only the revision token, never an obsolete complete guide in a recycled row.
            revision = token;
            channel = value;
            from = now;
            until = Long.MAX_VALUE;
            title = "";
            if (next == null) return title;
            // Guides are sorted, immutable snapshots. Preserve first-active semantics
            // even for overlapping programmes; invalidate exactly at stop/next-start.
            for (Program program : programsFor(next, value)) {
                if (program.startMillis > now) { until = program.startMillis; break; }
                if (program.isPlaying(now)) {
                    title = program.title;
                    until = program.stopMillis;
                    break;
                }
            }
            return title;
        }
    }

    String logoFor(Channel channel) {
        if (channel == null) return "";
        if (channel.logoUrl.length() > 0) return channel.logoUrl;
        Guide snapshot = guide;
        String id = channelIdFor(snapshot, channel);
        String logo = id == null ? null : snapshot.logosByChannel.get(id);
        return logo == null ? "" : logo;
    }

    private static String channelIdFor(Guide snapshot, Channel channel) {
        // XMLTV IDs are opaque. An explicit tvg-id wins over a display-name alias.
        String exact=channel.epgId==null?"":channel.epgId.trim();
        if(!exact.isEmpty()){
            if(snapshot.programsByChannel.containsKey(exact)||snapshot.logosByChannel.containsKey(exact))return exact;
            String requested=normalize(exact);
            String channelId=snapshot.channelByAlias.get(requested);
            if(channelId!=null)return channelId;
            if(snapshot.programsByChannel.containsKey(requested))return requested;
        }
        String name=channel.name.trim(),requested=normalize(name);
        if(snapshot.programsByChannel.containsKey(name)||snapshot.logosByChannel.containsKey(name))return name;
        String channelId=snapshot.channelByAlias.get(requested);
        if(channelId!=null)return channelId;
        return snapshot.programsByChannel.containsKey(requested)?requested:null;
    }

    private boolean restoringCache;
    void restoreCached(final String[] sourceUrls,final Listener listener){
        final List<String> sources=sanitizeSources(sourceUrls);
        final int generation;
        synchronized(this){if(restoringCache||!guide.programsByChannel.isEmpty())return;restoringCache=true;generation=refreshGeneration;}
        cacheWorker.execute(()->{
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            boolean restored=false;
            try{
                Guide cached=new Guide();List<String> urls=new ArrayList<String>();
                for(int i=0;i<sources.size();i++){
                    StoredGuide stored=readCachedGuide(sources.get(i),i);
                    if(stored!=null){mergeGuide(cached,stored.guide);urls.add(stored.resolved);}
                }
                synchronized(EpgManager.this){
                    if(generation==refreshGeneration&&guide.programsByChannel.isEmpty()&&!cached.programsByChannel.isEmpty()){
                        publish(cached,urls);restored=true;
                    }
                }
            }finally{synchronized(EpgManager.this){restoringCache=false;}}
            if(restored)notifyListener(listener);
        });
    }

    void refresh(final String sourceUrl, final Listener listener) {
        refresh(new String[] { sourceUrl }, listener);
    }

    void refresh(final String[] sourceUrls, final Listener listener) {
        refresh(sourceUrls,false,true,listener);
    }

    void rebuild(final String[] sourceUrls, boolean fetch, final Listener listener) {
        refresh(sourceUrls,true,fetch,listener);
    }

    private void refresh(final String[] sourceUrls, final boolean force, boolean fetch, final Listener listener) {
        final List<String> sources = fetch ? sanitizeSources(sourceUrls) : Collections.<String>emptyList();
        final String sourceSignature = joinSources(sources);
        final int requestId;
        final HttpURLConnection supersededDownload;
        synchronized (this) {
            long day = dayStart(System.currentTimeMillis());
            long now=android.os.SystemClock.elapsedRealtime();
            if (!force && sourceSignature.equals(attemptedSource) && attemptedDay == day
                    && (loading || lastError.length()==0 || !guide.programsByChannel.isEmpty()
                            || now-attemptedAt<300000L)) return;
            attemptedSource = sourceSignature;
            attemptedDay = day;
            attemptedAt = now;
            requestId = ++refreshGeneration;
            loading = true;
            lastError = "";
            if(force){guide=new Guide();loadedUrl="";}
            supersededDownload=force?activeDownload:null;
        }
        if(supersededDownload!=null)supersededDownload.disconnect();
        cacheWorker.execute(new Runnable() {
            @Override
            public void run() {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                try {
                    if(requestId!=refreshGeneration)return;
                    workerGeneration=requestId;
                    if(force)clearPersistentCache();
                    if(sources.isEmpty())return;
                    Map<String,StoredGuide> restoredSources=new HashMap<String,StoredGuide>();
                    // Restore disk data before waiting for a stale source's network
                    // validation. Cold starts and theme changes must not blank EPG.
                    if(guide.programsByChannel.isEmpty()) {
                        Guide disk=new Guide();
                        List<String> diskSources=new ArrayList<String>();
                        SharedPreferences prefs=context.getSharedPreferences(CACHE_PREFS,Context.MODE_PRIVATE);
                        for(int i=0;i<sources.size();i++) {
                            if(requestId!=refreshGeneration)return;
                            StoredGuide stored=readCachedGuide(sources.get(i),i);
                            restoredSources.put(sources.get(i),stored);
                            if(stored!=null&&!stored.guide.programsByChannel.isEmpty()){
                                mergeGuide(disk,stored.guide);diskSources.add(stored.resolved);continue;
                            }
                            CacheSlot slot=cacheSlot(sources.get(i),i);
                            if(!sources.get(i).equals(prefs.getString(slot.sourceKey,""))
                                    || isCacheFresh(slot.file))continue;
                            try {
                                Guide restored=readCache(slot.file);
                                if(restored==null)continue;
                                String resolved=prefs.getString(slot.resolvedKey,sources.get(i));
                                resolveLogos(restored,resolved);mergeGuide(disk,restored);diskSources.add(resolved);
                            }catch(Exception ignored){AppLog.w(TAG,"Unable to restore cached EPG");}
                        }
                        boolean restored=false;
                        synchronized(EpgManager.this){
                            if(requestId==refreshGeneration&&!disk.programsByChannel.isEmpty()){
                                publish(disk,diskSources);restored=true;
                            }
                        }
                        if(restored)notifyListener(listener);
                    }
                    Guide merged = new Guide();
                    List<String> loadedSources = new ArrayList<String>();
                    String firstError = "";
                    for (int index = 0; index < sources.size(); index++) {
                        if (requestId != refreshGeneration) return;
                        String source = sources.get(index);
                        try {
                            LoadedGuide loaded = loadGuide(source, cacheSlot(source, index), restoredSources.containsKey(source)?restoredSources.get(source):readCachedGuide(source,index),force);
                            mergeGuide(merged, loaded.guide);
                            loadedSources.add(loaded.sourceUrl);
                            // Expose the first usable cold-load source before a
                            // slow fallback finishes. Preserve an existing guide
                            // during refresh, and never mutate a published snapshot.
                            boolean published = false;
                            synchronized (EpgManager.this) {
                                if (requestId == refreshGeneration && index + 1 < sources.size()
                                        && guide.programsByChannel.isEmpty()
                                        && !merged.programsByChannel.isEmpty()) {
                                    publish(copyGuide(merged), loadedSources);
                                    published = true;
                                }
                            }
                            if (published) notifyListener(listener);
                        } catch (Exception sourceError) {
                            if (firstError.length() == 0) {
                                firstError = sourceError.getMessage() == null
                                        ? sourceError.getClass().getSimpleName()
                                        : sourceError.getMessage();
                            }
                            AppLog.w(TAG, "Unable to load EPG source " + source, sourceError);
                        }
                    }
                    if (merged.programsByChannel.isEmpty()) {
                        throw new IOException(firstError.length() == 0
                                ? "没有可用的节目单" : firstError);
                    }
                    synchronized (EpgManager.this) {
                        if (requestId == refreshGeneration) publish(merged, loadedSources);
                    }
                } catch (Exception error) {
                    synchronized(EpgManager.this){
                        if (requestId == refreshGeneration) {
                            lastError = error.getMessage() == null
                                    ? error.getClass().getSimpleName() : error.getMessage();
                            // Back off from completion, including slow DNS/TLS timeouts.
                            attemptedAt=android.os.SystemClock.elapsedRealtime();
                        }
                    }
                    AppLog.w(TAG, "Unable to refresh EPG", error);
                } finally {
                    synchronized(EpgManager.this){
                        if(requestId==refreshGeneration)loading=false;
                    }
                    notifyListener(listener);
                }
            }
        });
    }

    private LoadedGuide loadGuide(String sourceUrl, CacheSlot slot,StoredGuide stored,boolean force) throws Exception {
        SharedPreferences preferences = context.getSharedPreferences(CACHE_PREFS,
                Context.MODE_PRIVATE);
        boolean cacheMatches = sourceUrl.equals(preferences.getString(slot.sourceKey, ""));
        if(stored!=null&&freshDatabase(stored.validated)&&!stored.guide.programsByChannel.isEmpty())
            return new LoadedGuide(stored.guide,stored.resolved);
        Guide cachedGuide = stored==null?null:stored.guide;
        String cachedUrl = stored==null?preferences.getString(slot.resolvedKey, sourceUrl):stored.resolved;
        if (stored == null && cacheMatches) {
            try {
                cachedGuide = readCache(slot.file);
                if (cachedGuide != null && !cachedGuide.programsByChannel.isEmpty()) {
                    resolveLogos(cachedGuide, cachedUrl);
                    saveDatabase(sourceUrl,cachedUrl,cachedGuide,slot.file.lastModified());
                    if (isCacheFresh(slot.file)) return new LoadedGuide(cachedGuide, cachedUrl);
                }
            } catch (Exception cacheError) {
                if (!slot.file.delete()) AppLog.w(TAG, "Unable to remove invalid EPG cache");
                cachedGuide = null;
            }
        }
        RemoteFileMetadata previous = cacheMatches ? RemoteFileMetadata.fromJson(
                preferences.getString(slot.metadataKey, "")) : null;
        try {
            ValidatedDownload remote;
            try {
                remote = downloadUsableGuide(sourceUrl, previous, slot.file, force, cachedGuide);
            } catch (Exception primaryError) {
                if (!DEFAULT_URL.equals(sourceUrl)) throw primaryError;
                AppLog.w(TAG, "Primary EPG unavailable; trying fallback", primaryError);
                remote = downloadUsableGuide(FALLBACK_URL, previous, slot.file, force, cachedGuide);
            }
            Download downloaded = remote.download;
            if (downloaded.notModified) {
                refreshCacheValidation(slot, downloaded.metadata, downloaded.url);
                android.content.ContentValues validated=new android.content.ContentValues();
                validated.put("validated",System.currentTimeMillis());validated.put("resolved",downloaded.url);
                database.getWritableDatabase().update("sources",validated,"source=?",new String[]{sourceUrl});
                return new LoadedGuide(cachedGuide, downloaded.url);
            }
            Guide parsed = remote.guide;
            resolveLogos(parsed, downloaded.url);
            try{saveDatabase(sourceUrl,downloaded.url,parsed,System.currentTimeMillis());}
            catch(android.database.SQLException cacheError){AppLog.w(TAG,"SQLite EPG write failed; using downloaded guide",cacheError);}
            try{writeCache(slot, downloaded.bytes, sourceUrl, downloaded.url,downloaded.metadata);}
            catch(IOException cacheError){AppLog.w(TAG,"XML backup unavailable; SQLite EPG retained",cacheError);}
            return new LoadedGuide(parsed, downloaded.url);
        } catch (Exception downloadError) {
            if (cachedGuide != null && !cachedGuide.programsByChannel.isEmpty()) {
                AppLog.w(TAG, "Using stale EPG cache for " + sourceUrl, downloadError);
                return new LoadedGuide(cachedGuide, cachedUrl);
            }
            throw downloadError;
        }
    }

    private static final class ValidatedDownload {
        final Download download; final Guide guide;
        ValidatedDownload(Download download, Guide guide) { this.download=download;this.guide=guide; }
    }

    private ValidatedDownload downloadUsableGuide(String source, RemoteFileMetadata previous,
            File cache, boolean force, Guide cached) throws Exception {
        Download downloaded=download(source,previous,cache,force);
        Guide parsed=downloaded.notModified?cached:parse(downloaded.bytes);
        // HTTP 200 may be a proxy error page or an XMLTV file with no usable
        // programmes. Validate before choosing a source or replacing good cache.
        if(parsed==null||parsed.programsByChannel.isEmpty())
            throw new IOException(downloaded.notModified?"节目单缓存不可用":"节目单没有有效节目内容");
        return new ValidatedDownload(downloaded,parsed);
    }

    private static void resolveLogos(Guide next, String sourceUrl) {
        for (Map.Entry<String, String> icon : next.logosByChannel.entrySet()) {
            try {
                icon.setValue(checkedHttpUrl(new URL(new URL(sourceUrl), icon.getValue())).toString());
            } catch (Exception ignored) { icon.setValue(""); }
        }
    }

    private void publish(Guide next, List<String> sourceUrls) {
        guide = next;
        loadedUrl = joinSources(sourceUrls);
        lastError = "";
        AppLog.i(TAG, "EPG loaded source=" + loadedUrl
                + " aliases=" + next.channelByAlias.size()
                + " channels=" + next.programsByChannel.size());
    }

    private static Guide copyGuide(Guide source) {
        Guide copy = new Guide();
        copy.channelByAlias.putAll(source.channelByAlias);
        copy.logosByChannel.putAll(source.logosByChannel);
        copy.programsByChannel.putAll(source.programsByChannel);
        return copy;
    }

    private static void mergeGuide(Guide target, Guide incoming) {
        Map<String, List<String>> aliasesByChannel = new HashMap<String, List<String>>();
        for (Map.Entry<String, String> alias : incoming.channelByAlias.entrySet()) {
            List<String> aliases = aliasesByChannel.get(alias.getValue());
            if (aliases == null) {
                aliases = new ArrayList<String>();
                aliasesByChannel.put(alias.getValue(), aliases);
            }
            aliases.add(alias.getKey());
        }
        for (Map.Entry<String, List<Program>> entry : incoming.programsByChannel.entrySet()) {
            String channelId = entry.getKey();
            List<String> aliases = aliasesByChannel.get(channelId);
            boolean duplicate = target.programsByChannel.containsKey(channelId);
            if (!duplicate && aliases != null) {
                for (String alias : aliases) {
                    if (target.channelByAlias.containsKey(alias)) {
                        duplicate = true;
                        break;
                    }
                }
            }
            if (duplicate) continue;
            target.programsByChannel.put(channelId, entry.getValue());
            target.channelByAlias.put(channelId, channelId);
            if (aliases != null) {
                for (String alias : aliases) target.channelByAlias.put(alias, channelId);
            }
            String logo = incoming.logosByChannel.get(channelId);
            if (logo != null && logo.length() > 0) target.logosByChannel.put(channelId, logo);
        }
    }

    private static void notifyListener(Listener listener) {
        if (listener != null) {
            listener.onUpdated();
        }
    }

    private static final class LoadedGuide {
        final Guide guide;
        final String sourceUrl;

        LoadedGuide(Guide guide, String sourceUrl) {
            this.guide = guide;
            this.sourceUrl = sourceUrl;
        }
    }

    private static final class CacheSlot {
        final File file;
        final String sourceKey;
        final String resolvedKey;
        final String metadataKey;

        CacheSlot(File file, String suffix) {
            this.file = file;
            sourceKey = CACHE_SOURCE_URL + suffix;
            resolvedKey = CACHE_RESOLVED_URL + suffix;
            metadataKey = CACHE_METADATA + suffix;
        }
    }

    private CacheSlot cacheSlot(String sourceUrl, int index) {
        // Keep the old first-source cache available across the migration. Every
        // additional guide gets a stable URL-derived file and metadata namespace.
        if (index == 0) {
            return new CacheSlot(context.getFileStreamPath(CACHE_FILE), "");
        }
        String hash = shortHash(sourceUrl);
        return new CacheSlot(context.getFileStreamPath("epg-guide-cache-" + hash + ".xml"),
                "_" + hash);
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes("UTF-8"));
            StringBuilder result = new StringBuilder(16);
            for (int index = 0; index < 8; index++) {
                int part = digest[index] & 0xff;
                if (part < 16) result.append('0');
                result.append(Integer.toHexString(part));
            }
            return result.toString();
        } catch (Exception ignored) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static List<String> sanitizeSources(String[] sourceUrls) {
        Set<String> unique = new LinkedHashSet<String>();
        if (sourceUrls != null) {
            for (String source : sourceUrls) {
                String value = source == null ? "" : source.trim();
                if (value.length() > 0) unique.add(value);
                if (unique.size() >= 8) break;
            }
        }
        return new ArrayList<String>(unique);
    }

    private static String joinSources(List<String> sources) {
        StringBuilder joined = new StringBuilder();
        for (String source : sources) {
            if (joined.length() > 0) joined.append('\n');
            joined.append(source);
        }
        return joined.toString();
    }

    private Guide readCache(File file) throws Exception {
        RemoteFileMetadata.recover(file);
        if (!file.isFile() || file.length() == 0L) return null;
        if (file.length() > MAX_DOWNLOAD_BYTES) throw new IOException("节目单文件超过 8 MB");
        // Avoid ByteArrayOutputStream growth and its full-size toByteArray copy
        // while the old guide and video decoder are still using the heap.
        try (InputStream input = new FileInputStream(file)) {
            return parse(input);
        }
    }

    private boolean isCacheFresh(File cache) {
        long now = System.currentTimeMillis();
        return cache.isFile() && cache.lastModified() <= now
                && dayStart(cache.lastModified()) == dayStart(now);
    }

    static long dayStart(long millis) {
        Calendar day = Calendar.getInstance();
        day.setTimeInMillis(millis);
        day.set(Calendar.HOUR_OF_DAY, 0);
        day.set(Calendar.MINUTE, 0);
        day.set(Calendar.SECOND, 0);
        day.set(Calendar.MILLISECOND, 0);
        return day.getTimeInMillis();
    }

    private void writeCache(CacheSlot slot, byte[] bytes, String sourceUrl, String resolvedUrl,
            RemoteFileMetadata metadata) throws IOException {
        RemoteFileMetadata.writeAtomically(slot.file, bytes, "节目单");
        SharedPreferences preferences = context.getSharedPreferences(
                CACHE_PREFS, Context.MODE_PRIVATE);
        // Keep the cache file and its source identity in sync across cold starts.
        //noinspection ApplySharedPref
        preferences.edit().putString(slot.sourceKey, sourceUrl)
                .putString(slot.resolvedKey, resolvedUrl)
                .putString(slot.metadataKey, metadata == null ? "" : metadata.toJson()).commit();
    }

    private void refreshCacheValidation(CacheSlot slot, RemoteFileMetadata metadata,
            String resolvedUrl) {
        slot.file.setLastModified(System.currentTimeMillis());
        SharedPreferences.Editor editor = context.getSharedPreferences(
                CACHE_PREFS, Context.MODE_PRIVATE).edit();
        if (metadata != null) editor.putString(slot.metadataKey, metadata.toJson());
        if (resolvedUrl != null && resolvedUrl.length() > 0) {
            editor.putString(slot.resolvedKey, resolvedUrl);
        }
        editor.apply();
        AppLog.i(TAG, "EPG unchanged; reused validated cache");
    }

    private static final class Download {
        final byte[] bytes;
        final String url;
        final RemoteFileMetadata metadata;
        final boolean notModified;

        Download(byte[] bytes, String url, RemoteFileMetadata metadata,
                boolean notModified) {
            this.bytes = bytes;
            this.url = url;
            this.metadata = metadata;
            this.notModified = notModified;
        }
    }

    private Download download(String sourceUrl, RemoteFileMetadata previous,
            File cachedFile, boolean force) throws IOException {
        if (sourceUrl == null || sourceUrl.trim().length() == 0) {
            throw new IOException("未配置节目单地址");
        }
        URL current = checkedHttpUrl(new URL(sourceUrl.trim()));
        boolean reusableCache = !force && previous != null && previous.matchesLocal(cachedFile);
        for (int redirects = 0; redirects <= 5; redirects++) {
            // Apply the shared accelerator to GitHub EPGs and release redirects.
            // Keep the logical source URL for cache identity and relative icons.
            URL requestUrl = checkedHttpUrl(new URL(GithubProxy.apply(null, current.toString())));
            if (redirects == 0 && previous != null && previous.appliesTo(sourceUrl)
                    && reusableCache && previous.shouldProbeMd5()
                    && epgHeadMatches(requestUrl, sourceUrl, previous, cachedFile)) {
                return new Download(null, GithubProxy.unwrap(current.toString()),
                        previous, true);
            }
            HttpURLConnection connection = NetworkClient.open(requestUrl);
            synchronized(this){
                if(workerGeneration!=refreshGeneration){connection.disconnect();throw new IOException("节目单刷新已被新的保存替代");}
                activeDownload=connection;
            }
            if(force){connection.setUseCaches(false);connection.setRequestProperty("Cache-Control","no-cache");connection.setRequestProperty("Pragma","no-cache");}
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(25000);
            // Android 7 does not reliably follow an HTTP -> HTTPS redirect. Handle
            // redirects here so stable EPG entry points can rotate their CDN URL.
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 nTv/1.6");
            connection.setRequestProperty("Accept",
                    "application/xml,text/xml,application/gzip,*/*");
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (reusableCache) previous.applyConditionalHeaders(connection, sourceUrl);
            try {
                int status = connection.getResponseCode();
                if (status == HttpURLConnection.HTTP_MOVED_PERM
                        || status == HttpURLConnection.HTTP_MOVED_TEMP
                        || status == HttpURLConnection.HTTP_SEE_OTHER
                        || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.trim().length() == 0) {
                        throw new IOException("节目单重定向地址为空");
                    }
                    current = checkedHttpUrl(new URL(requestUrl, location.trim()));
                    continue;
                }
                if (status == HttpURLConnection.HTTP_NOT_MODIFIED
                        && reusableCache && previous.appliesTo(sourceUrl)) {
                    return new Download(null, GithubProxy.unwrap(current.toString()),
                            previous, true);
                }
                if (status < 200 || status >= 300) {
                    throw new IOException("节目单下载失败：HTTP " + status);
                }
                RemoteFileMetadata response = RemoteFileMetadata.fromResponse(
                        connection, sourceUrl);
                if (response.contentLength > MAX_DOWNLOAD_BYTES) {
                    throw new IOException("节目单文件超过 8 MB");
                }
                byte[] body = readAll(connection.getInputStream());
                response = response.verify(body, "节目单");
                String resolved = GithubProxy.unwrap(current.toString());
                if (previous != null && previous.appliesTo(sourceUrl)
                        && previous.sameContent(response)
                        && previous.matchesLocal(cachedFile)) {
                    return new Download(null, resolved, response, true);
                }
                return new Download(body, resolved, response, false);
            } finally {
                connection.disconnect();
                if(activeDownload==connection)activeDownload=null;
            }
        }
        throw new IOException("节目单重定向次数过多");
    }

    private static boolean epgHeadMatches(URL requestUrl, String sourceUrl,
            RemoteFileMetadata previous, File cachedFile) {
        HttpURLConnection connection = null;
        try {
            connection = NetworkClient.open(requestUrl);
            connection.setRequestMethod("HEAD");
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(12000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 nTv/1.6");
            connection.setRequestProperty("Accept",
                    "application/xml,text/xml,application/gzip,*/*");
            connection.setRequestProperty("Accept-Encoding", "identity");
            int status = connection.getResponseCode();
            return status >= 200 && status < 300 && previous.matchesRemote(
                    RemoteFileMetadata.fromResponse(connection, sourceUrl), cachedFile);
        } catch (IOException ignored) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static URL checkedHttpUrl(URL url) throws IOException {
        String protocol = url.getProtocol();
        if (!"http".equalsIgnoreCase(protocol) && !"https".equalsIgnoreCase(protocol)) {
            throw new IOException("节目单地址仅支持 HTTP 或 HTTPS");
        }
        return url;
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > MAX_DOWNLOAD_BYTES) {
                throw new IOException("节目单文件超过 8 MB");
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static Guide parse(byte[] bytes) throws Exception {
        return parse(new ByteArrayInputStream(bytes));
    }

    /** Bound both the stored bytes and gzip expansion without buffering the guide. */
    private static Guide parse(InputStream source) throws Exception {
        try (InputStream owned = source;
             PushbackInputStream header = new PushbackInputStream(new BufferedInputStream(
                     new LimitedGuideInputStream(owned, MAX_DOWNLOAD_BYTES), 16 * 1024), 2)) {
            int first = header.read(), second = header.read();
            if (second >= 0) header.unread(second);
            if (first >= 0) header.unread(first);
            try (InputStream decoded = first == 0x1f && second == 0x8b
                    ? new GZIPInputStream(header) : header) {
                return parseXml(new LimitedGuideInputStream(decoded, MAX_XML_BYTES));
            }
        }
    }

    private static final class LimitedGuideInputStream extends FilterInputStream {
        private final long limit;
        private long count;
        LimitedGuideInputStream(InputStream input, long limit) { super(input); this.limit = limit; }
        @Override public int read() throws IOException {
            int value = in.read();
            if (value >= 0) consumed(1);
            return value;
        }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            int size = in.read(buffer, offset, (int) Math.min(length, limit - count + 1));
            if (size > 0) consumed(size);
            return size;
        }
        private void consumed(int size) throws IOException {
            count += size;
            if (count > limit) throw new IOException("节目单内容超过解析上限");
        }
    }

    private static Guide parseXml(InputStream input) throws Exception {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(input, null);
            Guide result = new Guide();
            String currentChannelId = null;
            long now = System.currentTimeMillis();
            long today = dayStart(now);
            int event;
            while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && "channel".equals(parser.getName())) {
                    currentChannelId = xmlChannelId(parser.getAttributeValue(null, "id"));
                    if (currentChannelId.length() > 0) {
                        String alias=normalize(currentChannelId);
                        // An opaque ID must not replace a preceding display name.
                        if(!result.channelByAlias.containsKey(alias))result.channelByAlias.put(alias,currentChannelId);
                    }
                } else if (event == XmlPullParser.END_TAG
                        && "channel".equals(parser.getName())) {
                    currentChannelId = null;
                } else if (event == XmlPullParser.START_TAG
                        && "display-name".equals(parser.getName())
                        && currentChannelId != null) {
                    String alias = normalize(parser.nextText());
                    if (alias.length() > 0) {
                        result.channelByAlias.put(alias, currentChannelId);
                    }
                } else if (event == XmlPullParser.START_TAG
                        && "icon".equals(parser.getName()) && currentChannelId != null) {
                    String icon = parser.getAttributeValue(null, "src");
                    if (icon != null && icon.trim().length() > 0
                            && !result.logosByChannel.containsKey(currentChannelId)) {
                        result.logosByChannel.put(currentChannelId, icon.trim());
                    }
                } else if (event == XmlPullParser.START_TAG
                        && "programme".equals(parser.getName())) {
                    parseProgramme(parser, result, now, today);
                }
            }
            for (List<Program> programs : result.programsByChannel.values()) {
                Collections.sort(programs, new Comparator<Program>() {
                    @Override
                    public int compare(Program left, Program right) {
                        return left.startMillis < right.startMillis ? -1
                                : left.startMillis == right.startMillis ? 0 : 1;
                    }
                });
            }
            return result;
    }

    private static void parseProgramme(XmlPullParser parser, Guide result, long now, long today)
            throws Exception {
        String channelId = xmlChannelId(parser.getAttributeValue(null, "channel"));
        long start = parseXmlTvTime(parser.getAttributeValue(null, "start"));
        long stop = parseXmlTvTime(parser.getAttributeValue(null, "stop"));
        String catchupId=parser.getAttributeValue(null,"catchup-id");
        String title = "未命名节目";
        int depth = parser.getDepth();
        int event;
        while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && "title".equals(parser.getName())) {
                String value = parser.nextText();
                if (value != null && value.trim().length() > 0) {
                    title = value.trim();
                }
            } else if (event == XmlPullParser.END_TAG && parser.getDepth() == depth
                    && "programme".equals(parser.getName())) {
                break;
            }
        }
        if (channelId.length() == 0 || start <= 0L || stop <= start
                || stop <= now-KEEP_PAST_MS || start > now + KEEP_FUTURE_MS) {
            return;
        }
        List<Program> programs = result.programsByChannel.get(channelId);
        if (programs == null) {
            programs = new ArrayList<Program>();
            result.programsByChannel.put(channelId, programs);
        }
        programs.add(new Program(start, stop, title,catchupId));
    }

    private static long parseXmlTvTime(String raw) {
        if (raw == null) {
            return -1L;
        }
        String value = raw.trim();
        String[] patterns = new String[] { "yyyyMMddHHmmss Z", "yyyyMMddHHmmssZ",
                "yyyyMMddHHmmss" };
        for (String pattern : patterns) {
            try {
                SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
                format.setLenient(false);
                if (pattern.indexOf('Z') < 0) {
                    format.setTimeZone(TimeZone.getDefault());
                }
                Date parsed = format.parse(value);
                if (parsed != null) {
                    return parsed.getTime();
                }
            } catch (ParseException ignored) {
            }
        }
        return -1L;
    }

    private static String xmlChannelId(String raw){return raw==null?"":raw.trim();}

    static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.toUpperCase(Locale.US)
                .replace("中央电视台", "CCTV")
                .replace("央视", "CCTV")
                .replace("中国教育电视台", "CETV")
                .replace("福建东南卫视", "东南卫视")
                .replace("CGTN阿拉伯语", "CGTN阿语")
                .replace("CGTN西班牙语", "CGTN西语")
                .replace("CGTN外语纪录", "CGTN纪录")
                .replace("高清", "")
                .replace("频道", "")
                .replace("HD", "")
                .replace("PLUS", "+")
                .replace('＋', '+');
        StringBuilder normalized = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isLetterOrDigit(character) || character == '+'
                    || (character >= '\u4e00' && character <= '\u9fff')) {
                normalized.append(character);
            }
        }
        String result = normalized.toString();
        if(result.equals("中文国际")||result.equals("中文国际亚洲")||result.equals("国际亚洲"))return "CCTV4";
        if(result.equals("中文国际欧洲")||result.equals("国际欧洲"))return "CCTV4欧洲";
        if(result.equals("中文国际美洲")||result.equals("国际美洲"))return "CCTV4美洲";
        if (result.startsWith("CCTV")) {
            // Keep UHD channel identities even with a descriptive suffix.
            if (result.startsWith("CCTV4K"))return "CCTV4K";
            if (result.startsWith("CCTV8K"))return "CCTV8K";
            if (result.startsWith("CCTV16") && result.endsWith("4K")) {
                return "CCTV16";
            }
            int index = 4;
            StringBuilder number = new StringBuilder("CCTV");
            while (index < result.length() && Character.isDigit(result.charAt(index))) {
                number.append(result.charAt(index++));
            }
            if (index < result.length() && result.charAt(index) == '+') {
                number.append('+');
            }
            if (number.length() > 4) {
                if("CCTV4".contentEquals(number)){
                    String region=result.substring(index);
                    if(region.contains("欧洲")||region.equals("EUO")||region.equals("EU")||region.equals("EUROPE"))return "CCTV4欧洲";
                    if(region.contains("美洲")||region.equals("AME")||region.equals("AMERICA")||region.equals("AMERICAS"))return "CCTV4美洲";
                }
                return number.toString();
            }
        }
        if (result.endsWith("卫视4K")) {
            return result.substring(0, result.length() - 2);
        }
        return result;
    }
}
