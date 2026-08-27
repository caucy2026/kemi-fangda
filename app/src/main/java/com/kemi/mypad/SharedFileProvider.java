package com.kemi.mypad;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Locale;

public final class SharedFileProvider extends ContentProvider {
    public static Uri uriFor(File file) {
        return new Uri.Builder()
                .scheme("content")
                .authority("com.kemi.mypad.files")
                .appendPath("open")
                .appendPath(file.getName())
                .appendQueryParameter("path", file.getAbsolutePath())
                .build();
    }

    @Override public boolean onCreate() { return true; }

    @Override
    public String getType(Uri uri) {
        File file = checkedFile(uri);
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) {
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                    name.substring(dot + 1).toLowerCase(Locale.ROOT));
            if (mime != null) return mime;
        }
        return "application/octet-stream";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        File file = checkedFile(uri);
        String[] columns = projection != null ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) {
            if (OpenableColumns.DISPLAY_NAME.equals(column)) row.add(file.getName());
            else if (OpenableColumns.SIZE.equals(column)) row.add(file.length());
            else row.add(null);
        }
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = checkedFile(uri);
        if (!file.isFile()) throw new FileNotFoundException(file.getAbsolutePath());
        int accessMode = mode != null && mode.contains("w")
                ? ParcelFileDescriptor.MODE_READ_WRITE
                : ParcelFileDescriptor.MODE_READ_ONLY;
        return ParcelFileDescriptor.open(file, accessMode);
    }

    private File checkedFile(Uri uri) {
        String raw = uri.getQueryParameter("path");
        if (raw == null) throw new SecurityException("Missing path");
        try {
            File file = new File(raw).getCanonicalFile();
            String path = file.getPath();
            if (!(path.startsWith("/storage/") || path.startsWith("/sdcard/"))) {
                throw new SecurityException("Path is outside shared storage");
            }
            return file;
        } catch (IOException error) {
            throw new SecurityException("Invalid path", error);
        }
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
}
