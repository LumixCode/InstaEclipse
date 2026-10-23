package ps.reso.instaeclipse.utils.feature;

import android.content.Context;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import ps.reso.instaeclipse.utils.i18n.I18n;

public class FeatureStatusTracker {
    private static final Map<String, Boolean> features = Collections.synchronizedMap(new HashMap<>());
    private static final Map<String, Integer> labels   = Collections.synchronizedMap(new HashMap<>());

    // Names whose hook has installed. Kept independently of `features` so that a later
    // setEnabled() (e.g. the companion-sync re-runs refreshFeatureStatus after hooks are
    // already installed) can't wipe the hooked state back to a ❌ in the load toast. Xposed
    // hooks persist for the process, so once hooked, a feature stays hooked.
    private static final Set<String> hooked = Collections.synchronizedSet(new HashSet<>());

    // Names whose hook FAILED to resolve/install (feature is enabled but not working). Surfaced in
    // the load toast as a red mark instead of the feature silently reading "enabled".
    private static final Set<String> broken = Collections.synchronizedSet(new HashSet<>());

    // Told about every change (on the changing thread), so an open menu can count hooks live.
    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public static void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public static void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    private static void changed() {
        for (Runnable listener : listeners) listener.run();
    }

    public static void setEnabled(String name, int labelResId) {
        features.put(name, hooked.contains(name));   // preserve hooked state across re-registration
        labels.put(name, labelResId);
        changed();
    }

    /** Mark an enabled feature as broken (its hook could not be resolved/installed). Registers a
     *  label so it still shows up in the toast even if setEnabled was never called for it. */
    public static void setBroken(String name, int labelResId) {
        broken.add(name);
        labels.put(name, labelResId);
        features.put(name, false);
        changed();
    }

    /** Mark broken reusing whatever label was already registered (label comes from FeatureManager).
     *  Call this from a hook's "could not resolve" path, guarded by the feature's own flag so a
     *  disabled feature never shows as broken. */
    public static void setBroken(String name) {
        broken.add(name);
        features.put(name, false);
        changed();
    }

    public static boolean isBroken(String name) {
        return broken.contains(name) && !hooked.contains(name);
    }

    public static void setDisabled(String name) {
        features.remove(name);
        labels.remove(name);
        changed();
    }

    public static void setHooked(String name) {
        hooked.add(name);
        broken.remove(name); // a successful hook clears any earlier broken mark
        if (features.containsKey(name)) {
            features.put(name, true);
        }
        changed();
    }

    public static String getLabel(Context ctx, String key) {
        Integer resId = labels.get(key);
        return resId != null ? I18n.t(ctx, resId) : key;
    }

    public static Map<String, Boolean> getStatus() {
        return features;
    }

    public static boolean hasEnabledFeatures() {
        return !features.isEmpty();
    }

    /**
     * One plain summary for the Logs tab once every hook has been set up: the Instagram version,
     * how many features are turned on, and by name the ones that could not be loaded. Features
     * that never report back (they hook lazily) are listed only in the technical details.
     */
    public static void logSummary(Context ctx) {
        try {
            String version = "?";
            try {
                version = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
            } catch (Throwable ignored) {}
            java.util.List<String> failed = new java.util.ArrayList<>();
            java.util.List<String> unconfirmed = new java.util.ArrayList<>();
            int on;
            synchronized (features) {
                on = features.size();
                for (Map.Entry<String, Boolean> e : features.entrySet()) {
                    if (Boolean.TRUE.equals(e.getValue())) continue;
                    if (isBroken(e.getKey())) failed.add(getLabel(ctx, e.getKey()));
                    else unconfirmed.add(e.getKey());
                }
            }
            String head = "(InstaEclipse) Running in Instagram " + version + " with " + on + " features turned on";
            if (failed.isEmpty()) {
                ps.reso.instaeclipse.utils.log.ModuleLog.ok(head + "; none failed to load");
            } else {
                ps.reso.instaeclipse.utils.log.ModuleLog.ok(head);
                ps.reso.instaeclipse.utils.log.ModuleLog.error("(InstaEclipse) Not working in this Instagram version: "
                        + String.join(", ", failed));
            }
            if (!unconfirmed.isEmpty()) {
                ps.reso.instaeclipse.utils.log.ModuleLog.detail("(InstaEclipse) Turned on, not confirmed by their hooks yet: "
                        + String.join(", ", unconfirmed));
            }
        } catch (Throwable t) {
            ps.reso.instaeclipse.utils.log.ModuleLog.detail("(InstaEclipse) Feature summary unavailable: " + t);
        }
    }
}
