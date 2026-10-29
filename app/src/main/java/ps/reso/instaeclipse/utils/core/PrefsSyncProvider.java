package ps.reso.instaeclipse.utils.core;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

public class PrefsSyncProvider extends ContentProvider {

    public static final String AUTHORITY = CommonUtils.MY_PACKAGE_NAME + ".prefsync";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY);
    public static final String METHOD_PUSH = "push";
    public static final String RESULT_UPDATED = "updated";
    private static final int MAX_ENTRIES = 2000;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Nullable
    @Override
    public Bundle call(@NonNull String method, @Nullable String arg, @Nullable Bundle extras) {
        if (!METHOD_PUSH.equals(method) || extras == null) return null;
        Context ctx = getContext();
        if (IpcSecurity.supportedCallerPackage(ctx, Binder.getCallingUid()) == null) {
            throw new SecurityException("caller is not a supported Instagram package");
        }
        if (extras.size() > MAX_ENTRIES) return null;

        Map<String, Object> incoming = new HashMap<>();
        for (String key : extras.keySet()) {
            Object v = extras.get(key);
            if (PrefsMerge.isSharedSetting(key, v) || PrefsMerge.isSharedStamp(key, v)) incoming.put(key, v);
        }

        int updated;
        Map<String, Object> pending;
        synchronized (PrefsSyncProvider.class) {
            SharedPreferences cache = CommonUtils.companionCache(ctx);
            Map<String, Object> changes = PrefsMerge.select(incoming, cache.getAll());
            updated = changes.size();
            if (updated > 0) {
                SharedPreferences.Editor e = cache.edit();
                for (Map.Entry<String, Object> c : changes.entrySet()) {
                    Object v = c.getValue();
                    if (v instanceof Boolean) e.putBoolean(c.getKey(), (Boolean) v);
                    else if (v instanceof Integer) e.putInt(c.getKey(), (Integer) v);
                    else if (v instanceof String) e.putString(c.getKey(), (String) v);
                }
                e.commit();
            }
            pending = PrefsMerge.pending(cache.getAll(), incoming);
        }
        Bundle result = new Bundle();
        result.putInt(RESULT_UPDATED, updated);
        for (Map.Entry<String, Object> p : pending.entrySet()) {
            Object v = p.getValue();
            if (v instanceof Long) result.putLong(p.getKey(), (Long) v);
            else if (v instanceof Boolean) result.putBoolean(p.getKey(), (Boolean) v);
            else if (v instanceof Integer) result.putInt(p.getKey(), (Integer) v);
            else if (v instanceof String) result.putString(p.getKey(), (String) v);
        }
        return result;
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection,
                        @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        return null;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return null;
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        return null;
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection,
                      @Nullable String[] selectionArgs) {
        return 0;
    }
}
