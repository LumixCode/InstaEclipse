package ps.reso.instaeclipse.utils.feature;

import android.content.Context;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import ps.reso.instaeclipse.utils.i18n.I18n;

public class FeatureStatusTracker {
    private static final Map<String, Boolean> features = Collections.synchronizedMap(new HashMap<>());
    private static final Map<String, Integer> labels   = Collections.synchronizedMap(new HashMap<>());

    private static final Set<String> hooked = Collections.synchronizedSet(new HashSet<>());

    private static final Set<String> broken = Collections.synchronizedSet(new HashSet<>());

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
        features.put(name, hooked.contains(name));
        labels.put(name, labelResId);
        changed();
    }

    public static void setBroken(String name, int labelResId) {
        broken.add(name);
        labels.put(name, labelResId);
        features.put(name, false);
        changed();
    }

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
        if (hooked.contains(name) && !broken.contains(name) && !Boolean.FALSE.equals(features.get(name))) return;
        hooked.add(name);
        broken.remove(name);
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
        synchronized (features) {
            return new LinkedHashMap<>(features);
        }
    }

    public static boolean hasEnabledFeatures() {
        return !features.isEmpty();
    }

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
