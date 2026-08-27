package xiao.bu.tv;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Read-only, narrowly scoped provider used to hand a downloaded APK to the installer. */
public final class ApkFileProvider extends ContentProvider {
    private static final String PATH_UPDATES = "updates";

    static File updateDirectory(Context context) {
        File base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (base == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            base = context.getCacheDir();
        }
        return base == null ? null : new File(base, PATH_UPDATES);
    }

    static Uri uriForFile(Context context, File file) throws IOException {
        File directory = updateDirectory(context);
        if (directory == null || !directory.getCanonicalFile().equals(
                file.getCanonicalFile().getParentFile()) || !isAllowedName(file.getName())) {
            throw new FileNotFoundException("APK is outside the update directory");
        }
        return new Uri.Builder()
                .scheme("content")
                .authority(context.getPackageName() + ".apk")
                .appendPath(PATH_UPDATES)
                .appendPath(file.getName())
                .build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        File file;
        try {
            file = fileForUri(uri);
        } catch (FileNotFoundException error) {
            return null;
        }
        String[] columns = projection == null
                ? new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) {
            if (OpenableColumns.DISPLAY_NAME.equals(column)) {
                row.add(file.getName());
            } else if (OpenableColumns.SIZE.equals(column)) {
                row.add(file.length());
            } else {
                row.add(null);
            }
        }
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new FileNotFoundException("Provider is read-only");
        }
        return ParcelFileDescriptor.open(fileForUri(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Provider is read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Provider is read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Provider is read-only");
    }

    private File fileForUri(Uri uri) throws FileNotFoundException {
        if (uri == null || uri.getPathSegments().size() != 2
                || !PATH_UPDATES.equals(uri.getPathSegments().get(0))) {
            throw new FileNotFoundException("Invalid update URI");
        }
        String name = uri.getPathSegments().get(1);
        if (!isAllowedName(name)) {
            throw new FileNotFoundException("Invalid APK name");
        }
        File directory = updateDirectory(getContext());
        if (directory == null) {
            throw new FileNotFoundException("Update directory is unavailable");
        }
        File file = new File(directory, name);
        try {
            if (!directory.getCanonicalFile().equals(file.getCanonicalFile().getParentFile())
                    || !file.isFile()) {
                throw new FileNotFoundException("Update APK does not exist");
            }
        } catch (IOException error) {
            throw new FileNotFoundException("Invalid update path");
        }
        return file;
    }

    private static boolean isAllowedName(String name) {
        return name != null && name.matches("nTv(?:64)?-[0-9]+\\.apk");
    }
}
