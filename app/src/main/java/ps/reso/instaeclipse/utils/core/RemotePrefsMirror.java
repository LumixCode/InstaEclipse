package ps.reso.instaeclipse.utils.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.os.IInterface;
import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;
import ps.reso.instaeclipse.utils.ipc.FrameworkServiceProvider;

/**
 * Modern flavor (libxposed API 102): mirrors the companion's "instaeclipse_cache" prefs into the
 * framework's remote preferences group of the same name, which the hooked app reads through
 * {@code getRemotePreferences} ({@code ModernBackend#companionPrefs}) on a cold start. A full copy
 * when the framework service connects, then every change as it happens. The existing settings
 * code keeps writing the local prefs as before.
 */
public final class RemotePrefsMirror {

    private static final String TAG = "IE|RemotePrefs";
    private static final String GROUP = "instaeclipse_cache";

    private static SharedPreferences local;
    private static volatile SharedPreferences remote;
    // the service the mirror writes to; guarded by RemotePrefsMirror.class
    private static XposedService bound;
    private static int boundUid = FrameworkServiceProvider.UNKNOWN_UID;

    // held strongly: SharedPreferences keeps its listeners weakly
    private static final SharedPreferences.OnSharedPreferenceChangeListener ON_CHANGE = (prefs, key) -> {
        SharedPreferences r = remote;
        if (r == null) return;
        try {
            if (key == null) copyAll(prefs, r); // the local file was cleared
            else copyKey(prefs, r, key);
        } catch (Throwable t) {
            Log.w(TAG, "mirror " + key + ": " + t);
        }
    };

    private RemotePrefsMirror() {}

    public static synchronized void start(Context context) {
        if (local != null) return;
        local = CommonUtils.companionCache(context);
        local.registerOnSharedPreferenceChangeListener(ON_CHANGE);
        XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
            @Override
            public void onServiceBind(@NonNull XposedService service) {
                synchronized (RemotePrefsMirror.class) {
                    int uid = FrameworkServiceProvider.senderUid(binderOf(service));
                    if (!accepts(bound == null ? null : boundUid, uid)) {
                        Log.i(TAG, "ignored another service binder while connected");
                        return;
                    }
                    try {
                        SharedPreferences r = service.getRemotePreferences(GROUP);
                        copyAll(local, r);
                        if (bound != null) Log.i(TAG, "service binder from uid " + boundUid + " replaced by one from uid " + uid);
                        bound = service;
                        boundUid = uid;
                        remote = r;
                        Log.i(TAG, "connected to " + service.getFrameworkName() + " " + service.getFrameworkVersion()
                                + " (API " + service.getApiVersion() + "), mirrored " + local.getAll().size() + " values");
                    } catch (Throwable t) {
                        Log.w(TAG, "remote preferences unavailable: " + t);
                    }
                }
            }

            @Override
            public void onServiceDied(@NonNull XposedService service) {
                synchronized (RemotePrefsMirror.class) {
                    if (service != bound) return;
                    bound = null;
                    remote = null;
                }
            }
        });
    }

    static boolean accepts(Integer boundSenderUid, int senderUid) {
        return boundSenderUid == null || (privileged(senderUid) && !privileged(boundSenderUid));
    }

    private static boolean privileged(int uid) {
        return uid == 0 || uid == 1000;
    }

    private static IBinder binderOf(XposedService service) {
        try {
            Method asInterface = XposedService.class.getDeclaredMethod("asInterface");
            asInterface.setAccessible(true);
            Object binderInterface = asInterface.invoke(service);
            return binderInterface instanceof IInterface i ? i.asBinder() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void copyAll(SharedPreferences from, SharedPreferences to) {
        SharedPreferences.Editor e = to.edit();
        e.clear();
        for (Map.Entry<String, ?> entry : from.getAll().entrySet()) put(e, entry.getKey(), entry.getValue());
        e.apply();
    }

    private static void copyKey(SharedPreferences from, SharedPreferences to, String key) {
        SharedPreferences.Editor e = to.edit();
        Object v = from.getAll().get(key);
        if (v == null) e.remove(key);
        else put(e, key, v);
        e.apply();
    }

    @SuppressWarnings("unchecked")
    private static void put(SharedPreferences.Editor e, String key, Object v) {
        if (v instanceof String s) e.putString(key, s);
        else if (v instanceof Boolean b) e.putBoolean(key, b);
        else if (v instanceof Integer i) e.putInt(key, i);
        else if (v instanceof Long l) e.putLong(key, l);
        else if (v instanceof Float f) e.putFloat(key, f);
        else if (v instanceof Set<?> set) e.putStringSet(key, (Set<String>) set);
    }
}
