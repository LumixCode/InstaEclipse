package ps.reso.instaeclipse.utils.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import ps.reso.instaeclipse.Xposed.SettingsEvents;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.media.StoryCacheRetention;

public final class CompanionSync {

    static final String TS = "__ts_";
    static final String APPLIED = "__applied_";
    static final String IG_TS = "__igts_";

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "InstaEclipse-Sync");
        t.setDaemon(true);
        return t;
    });

    private static final PullThrottle THROTTLE = new PullThrottle();
    private static boolean emptyLogged;
    private static volatile boolean remoteAvailable;
    private static volatile int startupApplied;
    static volatile Consumer<Context> pusher = CompanionSync::queuePush;
    static volatile Supplier<Map<String, ?>> remote = ps.reso.instaeclipse.xposedcompat.CompanionPrefs::all;
    static volatile LongSupplier clock = SystemClock::elapsedRealtime;

    private CompanionSync() {}

    public static void pullAsync(Context context) {
        Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        WORKER.execute(() -> {
            List<String> applied = applyNewer(app, false);
            if (applied.isEmpty()) return;
            new Handler(Looper.getMainLooper()).post(() -> SettingsEvents.afterChange(applied));
        });
    }

    public static boolean pull(Context context) {
        int applied = applyNewer(context, true).size();
        startupApplied += applied;
        return applied > 0;
    }

    public static int startupApplied() {
        return startupApplied;
    }

    public static boolean remotePrefsAvailable() {
        return remoteAvailable;
    }

    public static synchronized void resetForTest() {
        THROTTLE.reset();
        emptyLogged = false;
        remoteAvailable = false;
        startupApplied = 0;
    }

    static synchronized List<String> applyNewer(Context context, boolean startup) {
        long now = clock.getAsLong();
        if (!THROTTLE.due(now)) return new ArrayList<>();
        try {
            Map<String, ?> all = remote.get();
            THROTTLE.read(now, all.isEmpty());
            if (startup && !all.isEmpty()) remoteAvailable = true;
            if (all.isEmpty() && !emptyLogged) {
                emptyLogged = true;
                ModuleLog.detail("(InstaEclipse) Sync: companion remote preferences empty or unavailable");
            }
            if (all.isEmpty()) return new ArrayList<>();
            Map<String, ?> instagram = context.getSharedPreferences("instaeclipse_prefs", Context.MODE_PRIVATE).getAll();
            return applyFrom(context, PrefsMerge.pending(all, instagram));
        } catch (Throwable t) {
            ModuleLog.warn("(InstaEclipse) Sync: could not read settings changed in the InstaEclipse app: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return new ArrayList<>();
        }
    }

    private static List<String> applyFrom(Context context, Map<String, ?> all) {
        if (all == null || all.isEmpty()) return new ArrayList<>();
        try {
            return applyEntries(context, all, false).applied;
        } catch (Throwable t) {
            ModuleLog.warn("(InstaEclipse) Sync: could not apply settings changed in the InstaEclipse app: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return new ArrayList<>();
        }
    }

    public static List<String> applyLive(Context context, Map<String, ?> values, long ts) {
        try {
            long stamp = ts > 0 ? ts : System.currentTimeMillis();
            Map<String, Object> incoming = new LinkedHashMap<>();
            for (Map.Entry<String, ?> entry : values.entrySet()) {
                incoming.put(entry.getKey(), entry.getValue());
                incoming.put(TS + entry.getKey(), stamp);
            }
            Plan plan = applyEntries(context, incoming, true);
            if (!plan.refused.isEmpty() || !plan.igNewer.isEmpty()) pushAsync(context);
            return plan.applied;
        } catch (Throwable t) {
            ModuleLog.warn("(InstaEclipse) Sync: could not apply settings changed in the InstaEclipse app: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return new ArrayList<>();
        }
    }

    static Plan applyEntries(Context context, Map<String, ?> incoming, boolean durable) {
        synchronized (SettingsManager.LOCK) {
            SharedPreferences ig = context.getSharedPreferences("instaeclipse_prefs", Context.MODE_PRIVATE);
            Plan plan = plan(ig.getAll(), incoming);
            for (Map.Entry<String, SettingsManager.Refusal> r : plan.reasons.entrySet()) {
                SettingsManager.logRefusal(r.getKey(), incoming.get(r.getKey()), r.getValue());
            }
            if (!plan.appliedStamps.isEmpty()) {
                SharedPreferences.Editor e = ig.edit();
                plan.writeTo(e);
                if (durable && Looper.myLooper() != Looper.getMainLooper()) e.commit();
                else e.apply();
            }
            if (!plan.applied.isEmpty()) {
                SettingsManager.loadAllFlags(context);
                ModuleLog.line("(InstaEclipse) Sync: applied " + plan.applied.size() + " setting(s) changed in the companion app: "
                        + String.join(", ", plan.applied));
            }
            return plan;
        }
    }

    static final class Plan {
        final Map<String, Object> writes = new LinkedHashMap<>();
        final Map<String, Long> appliedStamps = new LinkedHashMap<>();
        final List<String> applied = new ArrayList<>();
        final List<String> refused = new ArrayList<>();
        final Map<String, SettingsManager.Refusal> reasons = new LinkedHashMap<>();
        final List<String> igNewer = new ArrayList<>();

        void writeTo(SharedPreferences.Editor e) {
            for (Map.Entry<String, Long> stamp : appliedStamps.entrySet()) e.putLong(APPLIED + stamp.getKey(), stamp.getValue());
            for (Map.Entry<String, Object> write : writes.entrySet()) {
                Object v = write.getValue();
                if (v instanceof Boolean) e.putBoolean(write.getKey(), (Boolean) v);
                else if (v instanceof Integer) e.putInt(write.getKey(), (Integer) v);
                else e.putString(write.getKey(), (String) v);
            }
        }
    }

    static Plan plan(Map<String, ?> ig, Map<String, ?> incoming) {
        Plan plan = new Plan();
        if (incoming == null) return plan;
        for (Map.Entry<String, ?> entry : incoming.entrySet()) {
            String tsKey = entry.getKey();
            if (!tsKey.startsWith(TS) || !(entry.getValue() instanceof Long)) continue;
            String key = tsKey.substring(TS.length());
            long ts = (Long) entry.getValue();
            if (ts <= stamp(ig, APPLIED + key)) continue;
            if (ts <= stamp(ig, IG_TS + key)) {
                plan.igNewer.add(key);
                continue;
            }
            Object v = incoming.get(key);
            if (!(v instanceof Boolean || v instanceof Integer || v instanceof String)) continue;
            plan.appliedStamps.put(key, ts);
            if (!ps.reso.instaeclipse.utils.ipc.ModuleReplies.isPreferenceValue(key, v)) {
                plan.refused.add(key);
                continue;
            }
            SettingsManager.Refusal reason = SettingsManager.refusalReason(ig, key, v);
            if (reason != SettingsManager.Refusal.NONE) {
                plan.refused.add(key);
                plan.reasons.put(key, reason);
                continue;
            }
            if ("storyCacheHours".equals(key)) v = StoryCacheRetention.normalize((Integer) v);
            if (v.equals(ig.get(key))) continue;
            plan.writes.put(key, v);
            plan.applied.add(key);
        }
        return plan;
    }

    private static long stamp(Map<String, ?> ig, String key) {
        Object v = ig.get(key);
        return v instanceof Long ? (Long) v : 0L;
    }

    public static void pushAsync(Context context) {
        pusher.accept(context);
    }

    public static void overridePushForTest(Consumer<Context> testPusher) {
        pusher = testPusher != null ? testPusher : CompanionSync::queuePush;
    }

    private static void queuePush(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        if (!PUSH_PENDING.compareAndSet(false, true)) return;
        WORKER.execute(() -> {
            PUSH_PENDING.set(false);
            push(app);
        });
    }

    private static final java.util.concurrent.atomic.AtomicBoolean PUSH_PENDING =
            new java.util.concurrent.atomic.AtomicBoolean();

    private static void push(Context context) {
        try {
            if (!isCompanionProvider(context)) {
                ModuleLog.detail("(InstaEclipse) Sync: the InstaEclipse app's settings provider is not available, so nothing was pushed");
                return;
            }
            Map<String, ?> all = context.getSharedPreferences("instaeclipse_prefs", Context.MODE_PRIVATE).getAll();
            android.os.Bundle b = new android.os.Bundle();
            for (Map.Entry<String, ?> entry : all.entrySet()) {
                String k = entry.getKey();
                Object v = entry.getValue();
                if (PrefsMerge.isSharedStamp(k, v)) b.putLong(k, (Long) v);
                else if (!PrefsMerge.isSharedSetting(k, v)) continue;
                else if (v instanceof Boolean) b.putBoolean(k, (Boolean) v);
                else if (v instanceof Integer) b.putInt(k, (Integer) v);
                else b.putString(k, (String) v);
            }
            android.os.Bundle r = context.getContentResolver()
                    .call(PrefsSyncProvider.URI, PrefsSyncProvider.METHOD_PUSH, null, b);
            int n = r != null ? r.getInt(PrefsSyncProvider.RESULT_UPDATED, 0) : 0;
            if (n > 0) ModuleLog.line("(InstaEclipse) Sync: updated " + n + " setting(s) in the companion app");
            if (r == null || r.size() <= 1) return;
            Map<String, Object> pending = new java.util.HashMap<>();
            for (String k : r.keySet()) {
                if (!PrefsSyncProvider.RESULT_UPDATED.equals(k)) pending.put(k, r.get(k));
            }
            List<String> applied = applyFrom(context, pending);
            if (applied.isEmpty()) return;
            new Handler(Looper.getMainLooper()).post(() -> SettingsEvents.afterChange(applied));
        } catch (Throwable t) {
            ModuleLog.detail("(InstaEclipse) Sync: companion push failed: " + t);
        }
    }

    private static boolean isCompanionProvider(Context context) {
        android.content.pm.PackageManager pm = context.getPackageManager();
        android.content.pm.ProviderInfo pi = pm.resolveContentProvider(PrefsSyncProvider.AUTHORITY, 0);
        return pi != null && CommonUtils.MY_PACKAGE_NAME.equals(pi.packageName)
                && IpcSecurity.isCompanionPermission(context);
    }

    public static void markApplied(Context context, String key, long ts) {
        if (key == null || ts <= 0) return;
        context.getSharedPreferences("instaeclipse_prefs", Context.MODE_PRIVATE)
                .edit().putLong(APPLIED + key, ts).apply();
    }
}
