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

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.service.XposedProvider;

public final class FrameworkServiceProvider extends ContentProvider {

    private static final String TAG = "IE|RemotePrefs";
    private static final String SEND_BINDER = "SendBinder";
    private static final String BINDER = "binder";
    public static final int UNKNOWN_UID = -1;

    private static final Map<IBinder, Integer> SENDERS = Collections.synchronizedMap(new WeakHashMap<>());

    private volatile XposedProvider delegate;

    public static int senderUid(IBinder binder) {
        if (binder == null) return UNKNOWN_UID;
        Integer uid = SENDERS.get(binder);
        return uid == null ? UNKNOWN_UID : uid;
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
        SENDERS.put(binder, uid);
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
