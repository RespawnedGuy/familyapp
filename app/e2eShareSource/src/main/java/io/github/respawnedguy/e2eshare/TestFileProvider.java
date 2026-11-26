package io.github.respawnedguy.e2eshare;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Serves the test files. Paths: /ok (real PNG, 64 x 48), /big (21 MB, claims image/jpeg),
 * /html (text/html), /fake (HTML bytes that claim to be image/jpeg).
 */
public class TestFileProvider extends ContentProvider {
    static final String AUTHORITY = "io.github.respawnedguy.e2eshare.files";
    private static final int BIG_BYTES = 21 * 1024 * 1024;
    private static final byte[] HTML = "<html><body>Kein Bild</body></html>".getBytes(StandardCharsets.UTF_8);

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return "html".equals(uri.getLastPathSegment()) ? "text/html"
            : "ok".equals(uri.getLastPathSegment()) ? "image/png" : "image/jpeg";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String kind = String.valueOf(uri.getLastPathSegment());
        File file = new File(getContext().getCacheDir(), "share-" + kind);
        try (OutputStream out = new FileOutputStream(file)) {
            switch (kind) {
                case "ok": {
                    Bitmap bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888);
                    bitmap.eraseColor(Color.rgb(30, 120, 200));
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                    break;
                }
                case "big": {
                    byte[] chunk = new byte[1024 * 1024];
                    for (int i = 0; i < BIG_BYTES / chunk.length; i++) out.write(chunk);
                    break;
                }
                case "html":
                case "fake":
                    out.write(HTML);
                    break;
                default:
                    throw new FileNotFoundException(kind);
            }
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
