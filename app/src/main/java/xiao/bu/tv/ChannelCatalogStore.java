package xiao.bu.tv;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Persistent, already-parsed channel catalog.
 *
 * <p>The source M3U files remain the recovery format. This database only avoids
 * reparsing and merging them on every process start. It deliberately uses the
 * framework SQLite implementation so Android 4.x builds do not gain another
 * native dependency.</p>
 */
final class ChannelCatalogStore extends SQLiteOpenHelper {
    private static final String TAG = "ChannelCatalogStore";
    private static final String DATABASE_NAME = "channel-catalog.db";
    private static final int DATABASE_VERSION = 1;

    private static final String CREATE_GROUPS = "CREATE TABLE catalog_groups ("
            + "_id INTEGER PRIMARY KEY, position INTEGER NOT NULL, "
            + "title TEXT NOT NULL, source INTEGER NOT NULL)";
    private static final String CREATE_CHANNELS = "CREATE TABLE catalog_channels ("
            + "_id INTEGER PRIMARY KEY, group_id INTEGER NOT NULL, "
            + "position INTEGER NOT NULL, number TEXT, name TEXT NOT NULL, "
            + "stream_id TEXT, ysp_pid TEXT, ysp_stream_id TEXT, "
            + "ysp_max_definition TEXT, epg_id TEXT, catalog_source INTEGER NOT NULL, "
            + "favorite_key TEXT)";
    private static final String CREATE_URLS = "CREATE TABLE catalog_urls ("
            + "channel_id INTEGER NOT NULL, position INTEGER NOT NULL, url TEXT NOT NULL, "
            + "PRIMARY KEY(channel_id, position))";
    private static final String CREATE_META = "CREATE TABLE catalog_meta ("
            + "name TEXT PRIMARY KEY, value TEXT NOT NULL)";

    ChannelCatalogStore(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase database) {
        database.execSQL(CREATE_GROUPS);
        database.execSQL(CREATE_CHANNELS);
        database.execSQL(CREATE_URLS);
        database.execSQL(CREATE_META);
        database.execSQL("CREATE INDEX channel_group_position "
                + "ON catalog_channels(group_id, position)");
        database.execSQL("CREATE INDEX url_channel_position "
                + "ON catalog_urls(channel_id, position)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase database, int oldVersion, int newVersion) {
        database.execSQL("DROP TABLE IF EXISTS catalog_urls");
        database.execSQL("DROP TABLE IF EXISTS catalog_channels");
        database.execSQL("DROP TABLE IF EXISTS catalog_groups");
        database.execSQL("DROP TABLE IF EXISTS catalog_meta");
        onCreate(database);
    }

    ChannelCatalog.Group[] load() {
        SQLiteDatabase database;
        try {
            database = getReadableDatabase();
        } catch (SQLiteException error) {
            Log.w(TAG, "Unable to open channel catalog", error);
            return null;
        }
        if (!isComplete(database)) {
            return null;
        }
        String sql = "SELECT g._id,g.title,g.source,c._id,c.number,c.name,c.stream_id,"
                + "c.ysp_pid,c.ysp_stream_id,c.ysp_max_definition,c.epg_id,"
                + "c.catalog_source,c.favorite_key,u.url "
                + "FROM catalog_groups g "
                + "LEFT JOIN catalog_channels c ON c.group_id=g._id "
                + "LEFT JOIN catalog_urls u ON u.channel_id=c._id "
                + "ORDER BY g.position,c.position,u.position";
        Cursor cursor = null;
        try {
            cursor = database.rawQuery(sql, null);
            List<ChannelCatalog.Group> groups = new ArrayList<ChannelCatalog.Group>();
            GroupBuilder group = null;
            ChannelBuilder channel = null;
            long groupId = Long.MIN_VALUE;
            long channelId = Long.MIN_VALUE;
            while (cursor.moveToNext()) {
                long nextGroupId = cursor.getLong(0);
                if (nextGroupId != groupId) {
                    if (group != null) {
                        group.add(channel);
                        groups.add(group.build());
                    }
                    groupId = nextGroupId;
                    channelId = Long.MIN_VALUE;
                    channel = null;
                    group = new GroupBuilder(cursor.getString(1), cursor.getInt(2));
                }
                if (cursor.isNull(3)) {
                    continue;
                }
                long nextChannelId = cursor.getLong(3);
                if (nextChannelId != channelId) {
                    if (group != null) {
                        group.add(channel);
                    }
                    channelId = nextChannelId;
                    channel = new ChannelBuilder(cursor);
                }
                if (channel != null && !cursor.isNull(13)) {
                    channel.urls.add(cursor.getString(13));
                }
            }
            if (group != null) {
                group.add(channel);
                groups.add(group.build());
            }
            return groups.toArray(new ChannelCatalog.Group[groups.size()]);
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to read channel catalog", error);
            return null;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    boolean hasFingerprint(String fingerprint) {
        return fingerprint != null && fingerprint.length() > 0
                && fingerprint.equals(metadataValue("fingerprint"));
    }

    boolean hasCatalog() {
        try {
            return isComplete(getReadableDatabase());
        } catch (SQLiteException ignored) {
            return false;
        }
    }

    boolean replace(ChannelCatalog.Group[] groups) {
        return replace(groups, null);
    }

    boolean replace(ChannelCatalog.Group[] groups, String fingerprint) {
        SQLiteDatabase database;
        try {
            database = getWritableDatabase();
        } catch (SQLiteException error) {
            Log.w(TAG, "Unable to open writable channel catalog", error);
            return false;
        }
        SQLiteStatement insertGroup = null;
        SQLiteStatement insertChannel = null;
        SQLiteStatement insertUrl = null;
        database.beginTransaction();
        try {
            database.delete("catalog_urls", null, null);
            database.delete("catalog_channels", null, null);
            database.delete("catalog_groups", null, null);
            database.delete("catalog_meta", null, null);
            insertGroup = database.compileStatement("INSERT INTO catalog_groups"
                    + "(_id,position,title,source) VALUES(?,?,?,?)");
            insertChannel = database.compileStatement("INSERT INTO catalog_channels"
                    + "(_id,group_id,position,number,name,stream_id,ysp_pid,ysp_stream_id,"
                    + "ysp_max_definition,epg_id,catalog_source,favorite_key) "
                    + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?)");
            insertUrl = database.compileStatement("INSERT INTO catalog_urls"
                    + "(channel_id,position,url) VALUES(?,?,?)");
            long groupId = 1L;
            long channelId = 1L;
            if (groups != null) {
                for (int groupPosition = 0; groupPosition < groups.length; groupPosition++) {
                    ChannelCatalog.Group group = groups[groupPosition];
                    insertGroup.clearBindings();
                    insertGroup.bindLong(1, groupId);
                    insertGroup.bindLong(2, groupPosition);
                    insertGroup.bindString(3, safe(group.title));
                    insertGroup.bindLong(4, group.source);
                    insertGroup.executeInsert();
                    for (int channelPosition = 0;
                            channelPosition < group.channels.length; channelPosition++) {
                        Channel channel = group.channels[channelPosition];
                        insertChannel.clearBindings();
                        insertChannel.bindLong(1, channelId);
                        insertChannel.bindLong(2, groupId);
                        insertChannel.bindLong(3, channelPosition);
                        bindText(insertChannel, 4, channel.number);
                        insertChannel.bindString(5, safe(channel.name));
                        bindText(insertChannel, 6, channel.streamId);
                        bindText(insertChannel, 7, channel.yangshipinPid);
                        bindText(insertChannel, 8, channel.yangshipinStreamId);
                        bindText(insertChannel, 9, channel.yangshipinMaxDefinition);
                        bindText(insertChannel, 10, channel.epgId);
                        insertChannel.bindLong(11, channel.catalogSource);
                        bindText(insertChannel, 12, channel.favoriteKey);
                        insertChannel.executeInsert();
                        for (int sourcePosition = 0;
                                sourcePosition < channel.urls.length; sourcePosition++) {
                            insertUrl.clearBindings();
                            insertUrl.bindLong(1, channelId);
                            insertUrl.bindLong(2, sourcePosition);
                            insertUrl.bindString(3, channel.urls[sourcePosition]);
                            insertUrl.executeInsert();
                        }
                        channelId++;
                    }
                    groupId++;
                }
            }
            database.execSQL("INSERT INTO catalog_meta(name,value) VALUES('complete','1')");
            if (fingerprint != null && fingerprint.length() > 0) {
                SQLiteStatement metadata = database.compileStatement(
                        "INSERT INTO catalog_meta(name,value) VALUES('fingerprint',?)");
                try {
                    metadata.bindString(1, fingerprint);
                    metadata.executeInsert();
                } finally {
                    metadata.close();
                }
            }
            database.setTransactionSuccessful();
            return true;
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to replace channel catalog", error);
            return false;
        } finally {
            if (insertGroup != null) {
                insertGroup.close();
            }
            if (insertChannel != null) {
                insertChannel.close();
            }
            if (insertUrl != null) {
                insertUrl.close();
            }
            database.endTransaction();
        }
    }

    private static boolean isComplete(SQLiteDatabase database) {
        Cursor cursor = null;
        try {
            cursor = database.rawQuery(
                    "SELECT value FROM catalog_meta WHERE name='complete'", null);
            return cursor.moveToFirst() && "1".equals(cursor.getString(0));
        } catch (SQLiteException ignored) {
            return false;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private String metadataValue(String name) {
        SQLiteDatabase database;
        try {
            database = getReadableDatabase();
        } catch (SQLiteException ignored) {
            return null;
        }
        Cursor cursor = null;
        try {
            cursor = database.rawQuery(
                    "SELECT value FROM catalog_meta WHERE name=?", new String[] { name });
            return cursor.moveToFirst() ? cursor.getString(0) : null;
        } catch (SQLiteException ignored) {
            return null;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private static void bindText(SQLiteStatement statement, int index, String value) {
        if (value == null) {
            statement.bindNull(index);
        } else {
            statement.bindString(index, value);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static final class GroupBuilder {
        final String title;
        final int source;
        final List<Channel> channels = new ArrayList<Channel>();

        GroupBuilder(String title, int source) {
            this.title = title;
            this.source = source;
        }

        void add(ChannelBuilder channel) {
            if (channel != null) {
                channels.add(channel.build());
            }
        }

        ChannelCatalog.Group build() {
            return new ChannelCatalog.Group(title, source,
                    channels.toArray(new Channel[channels.size()]));
        }
    }

    private static final class ChannelBuilder {
        final String number;
        final String name;
        final String streamId;
        final String yangshipinPid;
        final String yangshipinStreamId;
        final String yangshipinMaxDefinition;
        final String epgId;
        final int catalogSource;
        final String favoriteKey;
        final List<String> urls = new ArrayList<String>();

        ChannelBuilder(Cursor cursor) {
            number = cursor.getString(4);
            name = cursor.getString(5);
            streamId = cursor.getString(6);
            yangshipinPid = cursor.getString(7);
            yangshipinStreamId = cursor.getString(8);
            yangshipinMaxDefinition = cursor.getString(9);
            epgId = cursor.getString(10);
            catalogSource = cursor.getInt(11);
            favoriteKey = cursor.getString(12);
        }

        Channel build() {
            Channel result = new Channel(number, name, streamId,
                    urls.toArray(new String[urls.size()]), yangshipinPid,
                    yangshipinStreamId, yangshipinMaxDefinition, epgId);
            if (favoriteKey != null && favoriteKey.length() > 0) {
                return result.asFavorite(favoriteKey, catalogSource);
            }
            return catalogSource >= 0 ? result.withCatalogSource(catalogSource) : result;
        }
    }
}
