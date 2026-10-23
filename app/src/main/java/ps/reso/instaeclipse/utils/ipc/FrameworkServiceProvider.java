package ps.reso.instaeclipse.utils.ipc;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.libxposed.service.XposedProvider;

public final class FrameworkServiceProvider extends ContentProvider {

    private static final String TAG = "IE|RemotePrefs";
    private static final String SEND_BINDER = "SendBinder";
    private static final String BINDER = "binder";
    public static final int UNKNOWN_UID = -1;

    private static final class Sender {
        final IBinder binder;
        final int uid;

        Sender(IBinder binder, int uid) {
            this.binder = binder;
            this.uid = uid;
        }
    }

    private static volatile Sender lastSender;

    private volatile XposedProvider delegate;

    public static int senderUid(IBinder binder) {
        Sender sender = lastSender;
        return sender != null && binder != null && sender.binder == binder ? sender.uid : UNKNOWN_UID;
    }

    @Override
    public boolean onCreate() {
        XposedProvider provider = new XposedProvider();
        provider.attachInfo(getContext(), null);
        delegate = provider;
        return true;
    }

    @Nullable
    @Override
    public Bundle call(@NonNull String method, @Nullable String arg, @Nullable Bundle extras) {
        if (SEND_BINDER.equals(method) && extras != null) {
            IBinder binder = extras.getBinder(BINDER);
            if (binder != null) record(binder, Binder.getCallingUid());
        }
        return delegate.call(method, arg, extras);
    }

    private void record(IBinder binder, int uid) {
        lastSender = new Sender(binder, uid);
        String packages = "?";
        try {
            Context context = getContext();
            String[] names = context == null ? null : context.getPackageManager().getPackagesForUid(uid);
            if (names != null && names.length > 0) packages = String.join(",", names);
        } catch (Throwable ignored) {
        }
        Log.i(TAG, "Framework service from uid " + uid + " (" + packages + ")");
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection,
                        @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        return delegate.query(uri, projection, selection, selectionArgs, sortOrder);
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return delegate.getType(uri);
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        return delegate.insert(uri, values);
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        return delegate.delete(uri, selection, selectionArgs);
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection,
                      @Nullable String[] selectionArgs) {
        return delegate.update(uri, values, selection, selectionArgs);
    }
}
