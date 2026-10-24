package ps.reso.instaeclipse.mods.media;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.annotation.RequiresApi;
import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import ps.reso.instaeclipse.utils.download.DownloadEngine;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;

/** Publishes only engine-verified files. Failed/cancelled copies remove their own partial output. */
final class DownloadPublisher {
    static final class Saved {
        final String displayPath;
        final String name;

        Saved(String displayPath, String name) {
            this.displayPath = displayPath;
            this.name = name;
        }
    }

    static Saved save(Context ctx, File source, String filename, String mime, String owner,
            DownloadEngine.Cancellation cancellation) throws Exception {
        return save(ctx, source, filename, mime, owner, FeatureFlags.downloaderCustomPath,
                FeatureFlags.downloaderUsernameFolder, cancellation);
    }

    /** Companion settings come from its cache; loading a host hook class is never required. */
    static void saveFrame(Context ctx, File source, String filename, String owner,
            DownloadEngine.Cancellation cancellation) throws Exception {
        SharedPreferences settings = ps.reso.instaeclipse.utils.core.CommonUtils.companionCache(ctx);
        String uri = settings.getString("downloaderCustomUri", "");
        boolean usernameFolder = settings.getBoolean("downloaderUsernameFolder", false);
        if (!uri.isEmpty()) {
            saveToSaf(ctx, source, filename, "image/png", uri, owner, usernameFolder, cancellation);
            return;
        }
        save(ctx, source, filename, "image/png", owner,
                settings.getString("downloaderCustomPath", ""), usernameFolder, cancellation);
    }

    static Saved save(Context ctx, File source, String filename, String mime, String owner,
            String customPath, boolean usernameFolder, DownloadEngine.Cancellation cancellation) throws Exception {
        DownloadFiles.check(cancellation);
        if (!customPath.isEmpty() && !customPath.startsWith("content://")) {
            try {
                return saveRaw(ctx, source, new File(customPath), filename, mime, owner, usernameFolder, cancellation);
            } catch (IOException failure) { DownloadFiles.check(cancellation); }
        }
        String root = externalRoot();
        if (Build.VERSION.SDK_INT < 29)
            return saveRaw(ctx, source, new File(root, MediaStoreTarget.DEFAULT_FOLDER),
                    filename, mime, owner, usernameFolder, cancellation);
        MediaStoreTarget target = MediaStoreTarget.of(customPath, root, DownloadFiles.segment(owner, 64),
                usernameFolder, mime);
        Uri uri = insertPending(ctx, filename, mime, target);
        boolean published = false;
        try {
            try (OutputStream out = ctx.getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IOException("Cannot write gallery item");
                DownloadFiles.copy(source, out, cancellation);
            }
            DownloadFiles.check(cancellation);
            publish(ctx, uri);
            published = true;
        } finally {
            if (!published) ctx.getContentResolver().delete(uri, null, null);
        }
        return new Saved(target.displayPath, displayName(ctx, uri, filename));
    }

    static OutputStream openStream(Context ctx, String name, String mime, String folder) throws IOException {
        String customPath = FeatureFlags.downloaderCustomPath;
        String root = externalRoot();
        if (!customPath.isEmpty() && !customPath.startsWith("content://") && !customPath.startsWith(root + "/")) {
            try {
                return openRaw(new File(customPath, folder), name);
            } catch (IOException ignored) {}
        }
        MediaStoreTarget target = MediaStoreTarget.of(customPath, root, folder, true, null);
        if (Build.VERSION.SDK_INT < 29) return openRaw(new File(root, target.relativePath), name);
        Uri uri = insertPending(ctx, name, mime, target);
        OutputStream out;
        try {
            out = ctx.getContentResolver().openOutputStream(uri);
        } catch (IOException | RuntimeException failure) {
            ctx.getContentResolver().delete(uri, null, null);
            throw failure;
        }
        if (out == null) {
            ctx.getContentResolver().delete(uri, null, null);
            throw new IOException("Cannot write gallery item");
        }
        return new FilterOutputStream(out) {
            private boolean published;

            @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                out.write(bytes, offset, length);
            }

            @Override public void close() throws IOException {
                super.close();
                if (published) return;
                published = true;
                publish(ctx, uri);
            }
        };
    }

    private static OutputStream openRaw(File dir, String name) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create download folder");
        return new FileOutputStream(new File(dir, name));
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private static Uri insertPending(Context ctx, String name, String mime, MediaStoreTarget target)
            throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, target.relativePath);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = ctx.getContentResolver().insert(collection(target.collection), values);
        if (uri == null) throw new IOException("Cannot create gallery item");
        return uri;
    }

    private static void publish(Context ctx, Uri uri) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.IS_PENDING, 0);
        if (ctx.getContentResolver().update(uri, values, null, null) != 1)
            throw new IOException("Cannot publish gallery item");
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private static Uri collection(MediaStoreTarget.Collection collection) {
        return switch (collection) {
            case IMAGES -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            case VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
            case AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            case DOWNLOADS -> MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        };
    }

    private static String displayName(Context ctx, Uri uri, String fallback) {
        try (Cursor cursor = ctx.getContentResolver().query(uri,
                new String[] {MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && cursor.getString(0) != null) return cursor.getString(0);
        } catch (RuntimeException ignored) {}
        return fallback;
    }

    private static Saved saveRaw(Context ctx, File source, File dir, String filename, String mime,
            String owner, boolean usernameFolder, DownloadEngine.Cancellation cancellation) throws IOException {
        if (usernameFolder && owner != null && !owner.isEmpty())
            dir = new File(dir, DownloadFiles.segment(owner, 64));
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create download folder");
        File temp = File.createTempFile(".ie_pending_", ".tmp", dir);
        try {
            try (FileOutputStream out = new FileOutputStream(temp)) {
                DownloadFiles.copy(source, out, cancellation);
                out.getFD().sync();
            }
            DownloadFiles.check(cancellation);
            // Reserve a new destination so a failed save never overwrites an existing download.
            File target = new File(dir, filename);
            int suffix = 0;
            while (!target.createNewFile()) {
                if (++suffix > 1000) throw new IOException("Too many duplicate filenames");
                int dot = filename.lastIndexOf('.');
                target = new File(dir, (dot < 0 ? filename : filename.substring(0, dot)) + "_" + suffix
                        + (dot < 0 ? "" : filename.substring(dot)));
            }
            if (!temp.renameTo(target)) {
                target.delete();
                throw new IOException("Cannot publish downloaded file");
            }
            MediaScannerConnection.scanFile(ctx, new String[]{target.getAbsolutePath()}, new String[]{mime}, null);
            return new Saved(displayPath(dir), target.getName());
        } finally { temp.delete(); }
    }

    private static String displayPath(File dir) {
        String root = externalRoot();
        String path = dir.getAbsolutePath();
        return path.startsWith(root + "/") ? path.substring(root.length() + 1) : path;
    }

    private static String externalRoot() {
        return Environment.getExternalStorageDirectory().getAbsolutePath();
    }

    static boolean reachesChosenFolder(String customPath, String mime) {
        return MediaStoreTarget.reachesChosenFolder(customPath, externalRoot(), mime);
    }

    static Uri saveToSaf(Context ctx, File source, String filename, String mime, String saveUri,
            String owner, boolean usernameFolder, DownloadEngine.Cancellation cancellation) throws Exception {
        DownloadFiles.check(cancellation);
        DocumentFile dir = DocumentFile.fromTreeUri(ctx, Uri.parse(saveUri));
        if (dir == null || !dir.canWrite())
            throw new IOException("SAF folder not writable — was the permission revoked?");
        if (usernameFolder && owner != null && !owner.isEmpty()) {
            String folder = DownloadFiles.segment(owner, 64);
            DocumentFile sub = dir.findFile(folder);
            if (sub == null || !sub.isDirectory()) sub = dir.createDirectory(folder);
            if (sub == null) throw new IOException("Cannot create username sub-folder");
            dir = sub;
        }
        DocumentFile file = dir.createFile(mime, filename);
        if (file == null) throw new IOException("SAF createFile returned null");
        boolean complete = false;
        try {
            try (OutputStream out = ctx.getContentResolver().openOutputStream(file.getUri())) {
                if (out == null) throw new IOException("SAF openOutputStream returned null");
                DownloadFiles.copy(source, out, cancellation);
            }
            DownloadFiles.check(cancellation);
            long size = file.length();
            if (size > 0 && size != source.length()) throw new IOException("Incomplete SAF copy");
            complete = true;
            return file.getUri();
        } finally {
            if (!complete) file.delete();
        }
    }

}
