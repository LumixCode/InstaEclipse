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

import ps.reso.instaeclipse.utils.feature.FeatureManager;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.media.StoryCacheRetention;

/**
 * Instagram side of the settings sync. Instagram -> companion: {@link #pushAsync}. The rest of
 * this class is the companion -> Instagram half, for changes the live broadcast
 * missed: Instagram was not running, or was frozen in the background when the companion sent
 * them.
 *
 * <p>The companion stamps every setting it sends with {@code __ts_<key>} in its
 * {@code instaeclipse_cache} prefs (see {@link CommonUtils#broadcastToInstagram}), which the
 * hooked app reads through the framework's remote preferences (libxposed API 102, see
 * {@link RemotePrefsMirror}) where it can, and otherwise gets back as the pending values in the
 * companion provider's reply to every {@link #pushAsync} (embedded LSPatch, companion not bound).
 * Only values that match a known setting's type ({@link ps.reso.instaeclipse.utils.ipc.ModuleReplies#isPreferenceValue})
 * are applied. On startup and whenever an activity resumes,
 * a companion value is applied when its stamp is newer than both the last companion value
 * applied for that key ({@code __applied_<key>}) and the last change made to that key from
 * inside Instagram ({@code __igts_<key>}, written by {@link SettingsManager#saveAllFlags}), so an
 * older companion value never overwrites a newer in-app change.
 */
public final class CompanionSync {

    static final String TS = "__ts_";
    static final String APPLIED = "__applied_";
    static final String IG_TS = "__igts_";
    private static final long MIN_INTERVAL_MS = 1500;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "InstaEclipse-Sync");
        t.setDaemon(true);
        return t;
    });

    private static long lastPull;
    private static boolean emptyLogged;

    private CompanionSync() {}

    /**
     * Pull on a background thread (activity resume). The live side effects the broadcast receiver
     * runs (theme repaint, chat buttons, airplane mode...) follow on the main thread.
     */
    public static void pullAsync(Context context) {
        Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        WORKER.execute(() -> {
            List<String> applied = applyNewer(app);
            if (applied.isEmpty()) return;
            new Handler(Looper.getMainLooper()).post(() -> {
                for (String key : applied) ps.reso.instaeclipse.Xposed.Module.afterCompanionWrite(key);
            });
        });
    }

    /**
     * Applies newer companion settings at startup; returns true when something changed. No live
     * side effects: nothing is on screen yet and the hooks install after this.
     */
    public static boolean pull(Context context) {
        return !applyNewer(context).isEmpty();
    }

    /** Applies newer companion settings; returns the keys it applied. */
    private static synchronized List<String> applyNewer(Context context) {
        List<String> applied = new ArrayList<>();
        long now = SystemClock.elapsedRealtime();
        if (lastPull != 0 && now - lastPull < MIN_INTERVAL_MS) return applied;
        lastPull = now;
        try {
            // Small, throttled, off the main thread: always re-read.
            Map<String, ?> all = ps.reso.instaeclipse.xposedcompat.CompanionPrefs.all();
            if (all.isEmpty() && !emptyLogged) {
                // Remote preferences unavailable (LSPatch local mode, companion not bound yet):
                // only the provider reply in push() brings companion changes back then.
                emptyLogged = true;
                ModuleLog.detail("(InstaEclipse) Sync: companion remote preferences empty or unavailable");
            }
            return applyFrom(context, all);
        } catch (Throwable t) {
            ModuleLog.warn("(InstaEclipse) Sync: could not read settings changed in the InstaEclipse app: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Applies the companion values in {@code all} (settings plus their {@code __ts_} stamps) that
     * are newer than anything Instagram applied or changed itself; returns the keys it applied.
     */
    private static synchronized List<String> applyFrom(Context context, Map<String, ?> all) {
        List<String> applied = new ArrayList<>();
        if (all == null || all.isEmpty()) return applied;
        try {
            SharedPreferences ig = context.getSharedPreferences("instaeclipse_prefs", Context.MODE_PRIVATE);
            Plan plan = plan(ig.getAll(), all);
            for (Map.Entry<String, SettingsManager.Refusal> r : plan.reasons.entrySet()) {
                SettingsManager.logRefusal(r.getKey(), all.get(r.getKey()), r.getValue());
            }
            if (plan.appliedStamps.isEmpty()) return applied;
            SharedPreferences.Editor e = ig.edit();
            plan.writeTo(e);
            e.commit();
            if (plan.applied.isEmpty()) return applied;
            SettingsManager.loadAllFlags(context);
            FeatureManager.refreshFeatureStatus();
            ModuleLog.line("(InstaEclipse) Sync: applied " + plan.applied.size() + " setting(s) changed in the companion app");
            return plan.applied;
        } catch (Throwable t) {
            ModuleLog.warn("(InstaEclipse) Sync: could not apply settings changed in the InstaEclipse app: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return new ArrayList<>();
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
            if (ts <= stamp(ig, APPLIED + key) || ts <= stamp(ig, IG_TS + key)) continue;
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
            if (v.equals(ig.get(key))) continue;
            if ("storyCacheHours".equals(key)) v = StoryCacheRetention.normalize((Integer) v);
            plan.writes.put(key, v);
            plan.applied.add(key);
        }
        return plan;
    }

    private static long stamp(Map<String, ?> ig, String key) {
        Object v = ig.get(key);
        return v instanceof Long ? (Long) v : 0L;
    }

    /**
     * Instagram -> companion: pushes Instagram's settings (with their __igts_ / __applied_
     * stamps) to the companion's {@link PrefsSyncProvider}, which keeps any newer companion
     * change (see {@link PrefsMerge}). Called at startup and after every in-app change, so the
     * companion shows the same values without having to reach a live Instagram process.
     * Back-to-back requests are coalesced into one push.
     */
    public static void pushAsync(Context context) {
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
            // The reply carries companion changes made while Instagram wasn't running. The pull
            // can't see them when the framework has no remote preferences (embedded LSPatch).
            if (r == null || r.size() <= 1 || !isCompanionProvider(context)) return;
            Map<String, Object> pending = new java.util.HashMap<>();
            for (String k : r.keySet()) {
                if (!PrefsSyncProvider.RESULT_UPDATED.equals(k)) pending.put(k, r.get(k));
            }
            List<String> applied = applyFrom(context, pending);
            if (applied.isEmpty()) return;
            new Handler(Looper.getMainLooper()).post(() -> {
                for (String key : applied) ps.reso.instaeclipse.Xposed.Module.afterCompanionWrite(key);
            });
        } catch (Throwable t) {
            // Companion not installed / too old (no provider): the ACTION_REQUEST_PREFS reply
            // still syncs when the companion is opened while Instagram is running.
            ModuleLog.detail("(InstaEclipse) Sync: companion push failed: " + t);
        }
    }

    /**
     * Settings are taken from the provider only when its app is the real companion: the same
     * bar the sync broadcast receiver sets (holding the permission proves nothing when the
     * companion is absent, since any app can then declare it).
     */
    private static boolean isCompanionProvider(Context context) {
        android.content.pm.PackageManager pm = context.getPackageManager();
        android.content.pm.ProviderInfo pi = pm.resolveContentProvider(PrefsSyncProvider.AUTHORITY, 0);
        return pi != null && CommonUtils.MY_PACKAGE_NAME.equals(pi.packageName)
                && IpcSecurity.isCompanionPermission(context);
    }

    /** Live broadcast applied: remember its stamp so the next pull doesn't apply it again. */
    public static void markApplied(Context context, String key, long ts) {
        if (key == null || ts <= 0) return;
        context.getSharedPreferences("instaeclipse_prefs", Context.MODE_PRIVATE)
                .edit().putLong(APPLIED + key, ts).apply();
    }
}
