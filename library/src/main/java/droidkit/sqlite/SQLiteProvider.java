package droidkit.sqlite;

import android.content.ContentProvider;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.OperationApplicationException;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.provider.BaseColumns;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import droidkit.io.IOUtils;
import droidkit.util.Dynamic;
import droidkit.util.DynamicException;

/**
 * @author Daniel Serdyukov
 */
public class SQLiteProvider extends ContentProvider {

    public static final String SCHEME = "content";

    public static final String DATABASE = "application.db";

    public static final String WHERE_ID_EQ = BaseColumns._ID + " = ?";

    static final String GROUP_BY = "groupBy";

    static final String HAVING = "having";

    static final String LIMIT = "limit";

    private static final int URI_MATCH_ALL = 1;

    private static final int URI_MATCH_ID = 2;

    private static final int DATABASE_VERSION = 1;

    private static final String MIME_DIR = "vnd.android.cursor.dir/";

    private static final String MIME_ITEM = "vnd.android.cursor.item/";

    private static final Map<Uri, String> TABLE_NAMES = new ConcurrentHashMap<>();

    private static final Map<Uri, Uri> BASE_URIS = new ConcurrentHashMap<>();

    private static final String SQLITE_SCHEMA_IMPL = "droidkit.sqlite.SQLiteSchemaImpl";

    private SQLiteHelper mHelper;

    private static int matchUri(@NonNull Uri uri) {
        final List<String> pathSegments = uri.getPathSegments();
        final int pathSegmentsSize = pathSegments.size();
        if (pathSegmentsSize == 1) {
            return URI_MATCH_ALL;
        } else if (pathSegmentsSize == 2 &&
                TextUtils.isDigitsOnly(pathSegments.get(1))) {
            return URI_MATCH_ID;
        }
        throw new SQLiteException("Unknown uri '" + uri + "'");
    }

    @Override
    public void attachInfo(Context context, ProviderInfo info) {
        super.attachInfo(context, info);
        SQLite.attach(info);
    }

    @Override
    public boolean onCreate() {
        try {
            mHelper = new SQLiteHelper(getContext(), getDatabaseName(), getDatabaseVersion(),
                    Dynamic.<SQLiteSchema>init(SQLITE_SCHEMA_IMPL));
        } catch (DynamicException e) {
            throw new SQLiteException(e);
        }
        return true;
    }

    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] columns, @Nullable String where,
                        @Nullable String[] whereArgs, @Nullable String orderBy) {
        final int match = matchUri(uri);
        final String tableName = getTableName(uri);
        final SQLiteDatabase db = mHelper.getReadableDatabase();
        final Cursor cursor;
        if (match == URI_MATCH_ID) {
            cursor = db.query(tableName, columns, BaseColumns._ID + "=?",
                    new String[]{uri.getLastPathSegment()}, null, null, orderBy);
        } else {
            cursor = db.query(tableName, columns, where, whereArgs, uri.getQueryParameter(GROUP_BY),
                    uri.getQueryParameter(HAVING), orderBy, uri.getQueryParameter(LIMIT));
        }
        cursor.setNotificationUri(getContext().getContentResolver(), uri);
        return cursor;
    }

    @Override
    public String getType(@NonNull Uri uri) {
        if (matchUri(uri) == URI_MATCH_ID) {
            return MIME_ITEM + getTableName(uri);
        }
        return MIME_DIR + getTableName(uri);
    }

    @Override
    public Uri insert(@NonNull Uri uri, @NonNull ContentValues values) {
        final SQLiteDatabase db = mHelper.getWritableDatabase();
        if (db.inTransaction()) {
            db.insert(getTableName(uri), BaseColumns._ID, values);
            return uri;
        }
        final int match = matchUri(uri);
        final long rowId = db.insert(getTableName(uri), BaseColumns._ID, values);
        if (match == URI_MATCH_ID) {
            onInsert(getBaseUri(uri), rowId);
            return uri;
        } else {
            onInsert(uri, rowId);
            return ContentUris.withAppendedId(uri, rowId);
        }
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String where, @Nullable String[] whereArgs) {
        final String tableName = getTableName(uri);
        final SQLiteDatabase db = mHelper.getWritableDatabase();
        if (db.inTransaction()) {
            return db.delete(tableName, where, whereArgs);
        }
        final int match = matchUri(uri);
        final int affectedRows;
        final Uri baseUri;
        if (match == URI_MATCH_ID) {
            baseUri = getBaseUri(uri);
            affectedRows = db.delete(tableName, WHERE_ID_EQ, new String[]{uri.getLastPathSegment()});
        } else {
            baseUri = uri;
            affectedRows = db.delete(tableName, where, whereArgs);
        }
        if (affectedRows > 0) {
            onDelete(baseUri, affectedRows);
        }
        return affectedRows;
    }

    @Override
    public int update(@NonNull Uri uri, @NonNull ContentValues values, @Nullable String where,
                      @Nullable String[] whereArgs) {
        final String tableName = getTableName(uri);
        final SQLiteDatabase db = mHelper.getWritableDatabase();
        if (db.inTransaction()) {
            return db.update(tableName, values, where, whereArgs);
        }
        final int match = matchUri(uri);
        final int affectedRows;
        final Uri baseUri;
        if (match == URI_MATCH_ID) {
            baseUri = getBaseUri(uri);
            affectedRows = db.update(tableName, values, WHERE_ID_EQ, new String[]{uri.getLastPathSegment()});
        } else {
            baseUri = uri;
            affectedRows = db.update(tableName, values, where, whereArgs);
        }
        if (affectedRows > 0) {
            onDelete(baseUri, affectedRows);
        }
        return affectedRows;
    }

    @Override
    public int bulkInsert(@NonNull Uri uri, @NonNull ContentValues[] values) {
        if (matchUri(uri) == URI_MATCH_ALL) {
            final SQLiteDatabase db = mHelper.getWritableDatabase();
            db.beginTransactionNonExclusive();
            try {
                final int insertedRows = super.bulkInsert(uri, values);
                db.setTransactionSuccessful();
                if (insertedRows > 0) {
                    onChange(uri, insertedRows);
                }
                return insertedRows;
            } finally {
                db.endTransaction();
            }
        }
        throw new SQLiteException("Unable to bulkInsert into " + uri);
    }

    @Override
    public ContentProviderResult[] applyBatch(@NonNull ArrayList<ContentProviderOperation> operations)
            throws OperationApplicationException {
        final SQLiteDatabase db = mHelper.getWritableDatabase();
        db.beginTransactionNonExclusive();
        try {
            final int opSize = operations.size();
            final ContentProviderResult[] results = new ContentProviderResult[opSize];
            final Set<Uri> notifyUris = new HashSet<>(opSize);
            for (int i = 0; i < opSize; ++i) {
                final ContentProviderResult result = operations.get(i).apply(this, results, i);
                if (result.uri != null) {
                    if (matchUri(result.uri) == URI_MATCH_ID) {
                        notifyUris.add(getBaseUri(result.uri));
                    } else {
                        notifyUris.add(result.uri);
                    }
                }
            }
            db.setTransactionSuccessful();
            for (final Uri baseUri : notifyUris) {
                onChange(baseUri, opSize);
            }
            return results;
        } finally {
            db.endTransaction();
        }
    }

    @Nullable
    protected String getDatabaseName() {
        return DATABASE;
    }

    protected int getDatabaseVersion() {
        return DATABASE_VERSION;
    }

    @SuppressWarnings("unused")
    protected void onChange(@NonNull Uri baseUri, int affectedRows) {
        getContext().getContentResolver().notifyChange(baseUri, null, false);
    }

    @SuppressWarnings("unused")
    protected void onInsert(@NonNull Uri baseUri, long rowid) {
        onChange(baseUri, 1);
    }

    @SuppressWarnings("unused")
    protected void onUpdate(@NonNull Uri baseUri, int affectedRows) {
        onChange(baseUri, affectedRows);
    }

    @SuppressWarnings("unused")
    protected void onDelete(@NonNull Uri baseUri, int affectedRows) {
        onChange(baseUri, affectedRows);
    }

    void clearDatabase() {
        final Cursor cursor = mHelper.getReadableDatabase().rawQuery("SELECT name FROM sqlite_master" +
                " WHERE type='table'" +
                " AND name <> 'android_metadata'", null);
        final List<String> tables = new ArrayList<>();
        try {
            if (cursor.moveToFirst()) {
                do {
                    tables.add(cursor.getString(0));
                } while (cursor.moveToNext());
            }
        } finally {
            IOUtils.closeQuietly(cursor);
        }
        final SQLiteDatabase db = mHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            for (final String table : tables) {
                db.execSQL("DELETE FROM " + table + ";");
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    @NonNull
    private String getTableName(@NonNull Uri uri) {
        String tableName = TABLE_NAMES.get(uri);
        if (tableName == null) {
            tableName = uri.getPathSegments().get(0);
            TABLE_NAMES.put(uri, tableName);
        }
        return tableName;
    }

    @NonNull
    private Uri getBaseUri(@NonNull Uri uri) {
        Uri baseUri = BASE_URIS.get(uri);
        if (baseUri == null) {
            baseUri = new Uri.Builder()
                    .scheme(uri.getScheme())
                    .authority(uri.getAuthority())
                    .appendPath(getTableName(uri))
                    .build();
            BASE_URIS.put(uri, baseUri);
        }
        return baseUri;
    }

}
