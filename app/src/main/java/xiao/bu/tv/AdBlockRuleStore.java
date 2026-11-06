package xiao.bu.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import xiao.bu.tv.AppLog;

import java.io.BufferedReader;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import okhttp3.Request;
import okhttp3.Response;

/** Disk-backed anti-AD domain matcher with bounded hot-host memory. */
final class AdBlockRuleStore {
    static final String RULE_URL = "https://anti-ad.net/adguard.txt";
    static final String MD5_URL = "https://anti-ad.net/adguard.txt.md5";
    static final int NONE = 0;
    static final int BLOCK = 1;
    static final int ALLOW = -1;

    private static final String TAG = "AdBlockRules";
    private static final String DATABASE_NAME = "web_ad_block.db";
    private static final String TEMP_DATABASE_NAME = "web_ad_block.new.db";
    private static final long REFRESH_INTERVAL_MS = 4L * 24L * 60L * 60L * 1000L;
    private static final int MIN_RULES = 50000;
    private static final int MAX_TEXT_BYTES = 8 * 1024 * 1024;
    private static final int HOST_CACHE_SIZE = 2048;
    private static final ExecutorService UPDATE_WORKER = Executors.newSingleThreadExecutor();
    private static AdBlockRuleStore instance;

    private final Context context;
    private final Object databaseLock = new Object();
    private final LinkedHashMap<String, Integer> hostCache =
            new LinkedHashMap<String, Integer>(HOST_CACHE_SIZE, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
                    return size() > HOST_CACHE_SIZE;
                }
            };
    private SQLiteDatabase database;
    private volatile long databaseGeneration;
    private List<WildcardRule> wildcardRules;
    private volatile boolean updating;
    private volatile int ruleCount;
    private volatile long lastUpdatedAt;
    private volatile String version = "";
    private volatile String lastError = "";
    private volatile long lastAttemptAt;
    private volatile boolean queryErrorLogged;

    static synchronized AdBlockRuleStore get(Context context) {
        if (instance == null) instance = new AdBlockRuleStore(context.getApplicationContext());
        return instance;
    }

    private AdBlockRuleStore(Context context) {
        this.context = context;
        SharedPreferences preferences = preferences();
        ruleCount = preferences.getInt("ad_block_rule_count", 0);
        lastUpdatedAt = preferences.getLong("ad_block_rule_updated_at", 0L);
        version = preferences.getString("ad_block_rule_version", "");
        File stored = context.getDatabasePath(DATABASE_NAME);
        if (ruleCount > 0 && (!stored.isFile() || stored.length() == 0L)) {
            ruleCount = 0;
            lastUpdatedAt = 0L;
            version = "";
            preferences.edit()
                    .remove("ad_block_rule_count")
                    .remove("ad_block_rule_updated_at")
                    .remove("ad_block_rule_version")
                    .apply();
        }
    }

    int match(String host) {
        host = normalizeHost(host);
        if (host.length() == 0) return NONE;
        long generation;
        synchronized (hostCache) {
            Integer cached = hostCache.get(host);
            if (cached != null) return cached;
            generation = databaseGeneration;
        }
        int result;
        try { result = query(host); }
        catch (RuntimeException error) {
            if (!queryErrorLogged) {
                queryErrorLogged = true;
                AppLog.w(TAG, "Unable to query rule database; leaving requests untouched", error);
            }
            return NONE;
        }
        synchronized (hostCache) {
            // An in-flight old query must not repopulate a freshly cleared cache.
            if (generation == databaseGeneration) hostCache.put(host, result);
        }
        return result;
    }

    void refreshAsync(final boolean force) {
        long age = System.currentTimeMillis() - lastUpdatedAt;
        if (!force && ruleCount > 0 && age >= 0 && age < REFRESH_INTERVAL_MS) return;
        synchronized (this) {
            if (updating) return;
            long sinceAttempt = android.os.SystemClock.elapsedRealtime() - lastAttemptAt;
            if (!force && lastAttemptAt != 0 && sinceAttempt >= 0
                    && sinceAttempt < 15L * 60L * 1000L) return;
            lastAttemptAt = android.os.SystemClock.elapsedRealtime();
            updating = true;
        }
        UPDATE_WORKER.execute(new Runnable() {
            @Override public void run() {
                try {
                    update(force);
                    lastError = "";
                } catch (Exception error) {
                    lastError = error.getMessage() == null
                            ? error.getClass().getSimpleName() : error.getMessage();
                    AppLog.w(TAG, "Unable to update anti-AD rules", error);
                }
                finally { updating = false; }
            }
        });
    }

    int ruleCount() { return ruleCount; }
    void trimMemory() {
        // Do not acquire databaseLock or close SQLite on the UI thread: a rule
        // query/update may hold it. Drop only the disposable hot-host answers.
        synchronized (hostCache) { hostCache.clear(); }
    }
    long lastUpdatedAt() { return lastUpdatedAt; }
    String version() { return version; }
    boolean isUpdating() { return updating; }
    String lastError() { return lastError; }

    private int query(String host) {
        List<String> suffixes = suffixes(host);
        boolean blocked = false;
        synchronized (databaseLock) {
            if (ruleCount == 0) return NONE;
            SQLiteDatabase db;
            try { db = database(); }
            catch (RuntimeException error) {
                AppLog.w(TAG, "Unable to open rule database", error);
                return NONE;
            }
            if (ruleCount > 0) {
                StringBuilder sql = new StringBuilder("SELECT action FROM domain_rule WHERE host IN (");
                for (int i=0;i<suffixes.size();i++) sql.append(i == 0 ? "?" : ",?");
                sql.append(')');
                Cursor cursor = null;
                try {
                    cursor = db.rawQuery(sql.toString(), suffixes.toArray(new String[suffixes.size()]));
                    while (cursor.moveToNext()) {
                        int action = cursor.getInt(0);
                        if (action == ALLOW) return ALLOW;
                        if (action == BLOCK) blocked = true;
                    }
                } finally { if (cursor != null) cursor.close(); }
                for (WildcardRule rule : wildcardRules(db)) {
                    if (!rule.matches(suffixes)) continue;
                    if (rule.action == ALLOW) return ALLOW;
                    blocked = true;
                }
            }
        }
        return blocked ? BLOCK : NONE;
    }

    private List<WildcardRule> wildcardRules(SQLiteDatabase db) {
        if (wildcardRules != null) return wildcardRules;
        ArrayList<WildcardRule> loaded = new ArrayList<WildcardRule>();
        Cursor cursor = null;
        try {
            cursor = db.rawQuery("SELECT pattern, action FROM wildcard_rule", null);
            while (cursor.moveToNext()) {
                String glob = cursor.getString(0);
                loaded.add(new WildcardRule(glob, cursor.getInt(1)));
            }
        } finally { if (cursor != null) cursor.close(); }
        wildcardRules = loaded;
        return loaded;
    }

    private void update(boolean force) throws Exception {
        String expectedMd5 = downloadMd5();
        SharedPreferences preferences = preferences();
        Request.Builder request = new Request.Builder().url(RULE_URL);
        if (!force && ruleCount > 0) {
            String etag = preferences.getString("ad_block_rule_etag", "");
            String modified = preferences.getString("ad_block_rule_modified", "");
            if (etag.length() > 0) request.header("If-None-Match", etag);
            if (modified.length() > 0) request.header("If-Modified-Since", modified);
        }
        Response response = NetworkClient.sharedClient().newCall(request.build()).execute();
        try {
            if (response.code() == 304 && ruleCount > 0) {
                lastUpdatedAt = System.currentTimeMillis();
                preferences.edit().putLong("ad_block_rule_updated_at", lastUpdatedAt).apply();
                return;
            }
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("anti-AD HTTP " + response.code());
            }
            long contentLength = response.body().contentLength();
            if (contentLength > MAX_TEXT_BYTES) throw new IOException("anti-AD list is too large");
            File target = context.getDatabasePath(DATABASE_NAME);
            File directory = target.getParentFile();
            if (directory != null && !directory.exists() && !directory.mkdirs()) {
                throw new IOException("Cannot create database directory");
            }
            File temporary = new File(directory, TEMP_DATABASE_NAME);
            deleteDatabaseFiles(temporary);
            ImportResult imported;
            try {
                imported = importRules(temporary, response.body().byteStream(),
                        contentLength, expectedMd5);
                swapDatabase(temporary, target, imported);
            } finally {
                deleteDatabaseFiles(temporary);
            }
            lastUpdatedAt = System.currentTimeMillis();
            preferences.edit()
                    .putLong("ad_block_rule_updated_at", lastUpdatedAt)
                    .putInt("ad_block_rule_count", ruleCount)
                    .putString("ad_block_rule_version", version)
                    .putString("ad_block_rule_etag", header(response, "ETag"))
                    .putString("ad_block_rule_modified", header(response, "Last-Modified"))
                    .apply();
            AppLog.i(TAG, "Installed anti-AD " + version + " with " + ruleCount + " rules");
        } finally { response.close(); }
    }

    private String downloadMd5() throws IOException {
        Response response = NetworkClient.sharedClient().newCall(
                new Request.Builder().url(MD5_URL).build()).execute();
        try {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("anti-AD MD5 HTTP " + response.code());
            }
            String value = new String(BoundedResponseReader.read(response.body().byteStream(),
                    response.body().contentLength(), 128), "UTF-8").trim().toLowerCase(Locale.US);
            if (!value.matches("[0-9a-f]{32}")) throw new IOException("Invalid anti-AD MD5");
            return value;
        } finally { response.close(); }
    }

    private ImportResult importRules(File file, InputStream source, long expectedLength,
            String expectedMd5) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        CountingInputStream counted = new CountingInputStream(source, MAX_TEXT_BYTES);
        DigestInputStream digested = new DigestInputStream(counted, digest);
        BufferedReader reader = new BufferedReader(new InputStreamReader(digested, "UTF-8"), 32768);
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(file, null);
        int count = 0;
        String versionValue = "";
        try {
            // This is a disposable staging database. The verified file is swapped
            // into place only after the full input and MD5 checks succeed.
            applyPragma(db, "PRAGMA journal_mode=OFF");
            applyPragma(db, "PRAGMA synchronous=OFF");
            createSchema(db);
            db.beginTransaction();
            SQLiteStatement domain = db.compileStatement(
                    "INSERT OR REPLACE INTO domain_rule(host, action) VALUES(?, ?)");
            SQLiteStatement wildcard = db.compileStatement(
                    "INSERT INTO wildcard_rule(pattern, action) VALUES(?, ?)");
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("!Version:")) {
                        versionValue = line.substring("!Version:".length()).trim();
                        continue;
                    }
                    ParsedRule rule = parse(line);
                    if (rule == null) continue;
                    SQLiteStatement statement = rule.wildcard ? wildcard : domain;
                    statement.clearBindings();
                    statement.bindString(1, rule.host);
                    statement.bindLong(2, rule.action);
                    statement.executeInsert();
                    count++;
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
                domain.close();
                wildcard.close();
                reader.close();
            }
        } finally { db.close(); }
        if (count < MIN_RULES) throw new IOException("Incomplete anti-AD list: " + count);
        if (expectedLength >= 0 && counted.count != expectedLength) {
            throw new IOException("anti-AD size mismatch");
        }
        String actualMd5 = hex(digest.digest());
        if (!expectedMd5.equals(actualMd5)) throw new IOException("anti-AD MD5 mismatch");
        return new ImportResult(count, versionValue);
    }

    private void swapDatabase(File temporary, File target, ImportResult imported) throws IOException {
        synchronized (databaseLock) {
            if (database != null) { database.close(); database = null; }
            File backup = new File(target.getPath() + ".bak");
            deleteDatabaseFiles(backup);
            if (target.exists() && !target.renameTo(backup)) {
                throw new IOException("Cannot back up anti-AD database");
            }
            if (!temporary.renameTo(target)) {
                if (backup.exists()) backup.renameTo(target);
                throw new IOException("Cannot install anti-AD database");
            }
            deleteDatabaseFiles(backup);
            wildcardRules = null;
            queryErrorLogged = false;
            ruleCount = imported.count;
            version = imported.version;
            synchronized (hostCache) {
                databaseGeneration++;
                hostCache.clear();
            }
        }
    }

    private SQLiteDatabase database() {
        if (database != null && database.isOpen()) return database;
        File file = context.getDatabasePath(DATABASE_NAME);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        database = SQLiteDatabase.openOrCreateDatabase(file, null);
        createSchema(database);
        return database;
    }

    private static void createSchema(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS domain_rule(host TEXT PRIMARY KEY, action INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS wildcard_rule(pattern TEXT NOT NULL, action INTEGER NOT NULL)");
    }

    private static void applyPragma(SQLiteDatabase db, String statement) {
        Cursor cursor = null;
        try {
            cursor = db.rawQuery(statement, null);
            cursor.moveToFirst();
        } finally { if (cursor != null) cursor.close(); }
    }

    static ParsedRule parse(String line) {
        if (line == null) return null;
        boolean allow = line.startsWith("@@||");
        int start = allow ? 4 : line.startsWith("||") ? 2 : -1;
        if (start < 0) return null;
        int end = line.indexOf('^', start);
        if (end <= start) return null;
        // This is a domain-only engine. Ignoring $domain/$third-party/$script
        // restrictions would turn scoped filters (and exceptions) into global ones.
        if (end != line.length() - 1) return null;
        String host = line.substring(start, end).trim().toLowerCase(Locale.US);
        if (host.indexOf('/') >= 0 || host.indexOf(':') >= 0 || host.length() > 253) return null;
        boolean wildcard = host.indexOf('*') >= 0;
        if (!isValidHost(host, wildcard)) return null;
        if (!wildcard) host = normalizeHost(host);
        if (host.length() == 0 || host.indexOf('.') <= 0) return null;
        return new ParsedRule(host, allow ? ALLOW : BLOCK, wildcard);
    }

    private static List<String> suffixes(String host) {
        ArrayList<String> result = new ArrayList<String>();
        String current = host;
        while (current.length() > 0) {
            result.add(current);
            int dot = current.indexOf('.');
            if (dot < 0) break;
            current = current.substring(dot + 1);
        }
        return result;
    }

    private static String normalizeHost(String host) {
        if (host == null) return "";
        host = host.trim().toLowerCase(Locale.US);
        while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        return isValidHost(host, false) ? host : "";
    }

    private static boolean isValidHost(String host, boolean allowWildcard) {
        if (host == null || host.length() == 0) return false;
        for (int i=0;i<host.length();i++) {
            char character = host.charAt(i);
            if ((character >= 'a' && character <= 'z')
                    || (character >= '0' && character <= '9')
                    || character == '.' || character == '_' || character == '-'
                    || (allowWildcard && character == '*')) continue;
            return false;
        }
        return true;
    }

    private SharedPreferences preferences() {
        return context.getSharedPreferences(MainActivity.PREFERENCES, Context.MODE_PRIVATE);
    }

    private static String header(Response response, String name) {
        String value = response.header(name);
        return value == null ? "" : value;
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) value.append(String.format(Locale.US, "%02x", item & 255));
        return value.toString();
    }

    private static void deleteDatabaseFiles(File file) {
        if (file == null) return;
        file.delete();
        new File(file.getPath() + "-journal").delete();
        new File(file.getPath() + "-wal").delete();
        new File(file.getPath() + "-shm").delete();
    }

    static final class ParsedRule {
        final String host;
        final int action;
        final boolean wildcard;
        ParsedRule(String host, int action, boolean wildcard) {
            this.host = host;
            this.action = action;
            this.wildcard = wildcard;
        }
    }

    private static final class ImportResult {
        final int count;
        final String version;
        ImportResult(int count, String version) { this.count = count; this.version = version; }
    }

    private static final class WildcardRule {
        final Pattern pattern;
        final int action;
        WildcardRule(String glob, int action) {
            StringBuilder expression = new StringBuilder("^");
            for (int i=0;i<glob.length();i++) {
                char character = glob.charAt(i);
                if (character == '*') expression.append(".*");
                else {
                    if (".[]{}()+-^$|\\".indexOf(character) >= 0) expression.append('\\');
                    expression.append(character);
                }
            }
            pattern = Pattern.compile(expression.append('$').toString());
            this.action = action;
        }
        boolean matches(List<String> suffixes) {
            for (String suffix : suffixes) if (pattern.matcher(suffix).matches()) return true;
            return false;
        }
    }

    private static final class CountingInputStream extends FilterInputStream {
        final long limit;
        long count;
        CountingInputStream(InputStream input, long limit) { super(input); this.limit = limit; }
        @Override public int read() throws IOException {
            int value = in.read();
            if (value >= 0) increment(1);
            return value;
        }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = in.read(buffer, offset, length);
            if (count > 0) increment(count);
            return count;
        }
        private void increment(int value) throws IOException {
            count += value;
            if (count > limit) throw new IOException("anti-AD list exceeds size limit");
        }
    }
}
