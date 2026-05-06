package com.example.realtimetranscriber;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

public class ShareAttachmentProvider extends ContentProvider {
    private static final String SHARE_DIRECTORY_NAME = "shared_transcripts";

    static File getShareDirectory(Context context) {
        return new File(context.getCacheDir(), SHARE_DIRECTORY_NAME);
    }

    static Uri buildUri(Context context, String fileName) {
        return new Uri.Builder()
                .scheme("content")
                .authority(context.getPackageName() + ".shareprovider")
                .appendPath(fileName)
                .build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return "text/plain";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File file = resolveSharedFile(uri);
        if (file == null || !file.exists()) {
            return null;
        }
        MatrixCursor cursor = new MatrixCursor(new String[]{
                OpenableColumns.DISPLAY_NAME,
                OpenableColumns.SIZE
        });
        cursor.addRow(new Object[]{file.getName(), file.length()});
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new FileNotFoundException("Only read access is supported");
        }
        File file = resolveSharedFile(uri);
        if (file == null || !file.exists()) {
            throw new FileNotFoundException("Shared attachment not found");
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Insert is not supported");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Delete is not supported");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Update is not supported");
    }

    private File resolveSharedFile(Uri uri) {
        Context context = getContext();
        if (context == null) {
            return null;
        }
        if (!"content".equals(uri.getScheme())) {
            return null;
        }
        if (!(context.getPackageName() + ".shareprovider").equals(uri.getAuthority())) {
            return null;
        }
        if (uri.getPathSegments().size() != 1) {
            return null;
        }

        File shareDirectory = getShareDirectory(context);
        File file = new File(shareDirectory, uri.getLastPathSegment());
        try {
            String shareDirectoryPath = shareDirectory.getCanonicalPath();
            String filePath = file.getCanonicalPath();
            if (!filePath.startsWith(shareDirectoryPath + File.separator)) {
                return null;
            }
        } catch (IOException exception) {
            return null;
        }
        return file;
    }
}
