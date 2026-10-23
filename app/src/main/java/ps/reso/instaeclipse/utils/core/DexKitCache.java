package ps.reso.instaeclipse.utils.core;

import android.content.Context;
import android.content.SharedPreferences;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Caches DexKit-resolved method signatures in SharedPreferences, keyed by
 * Instagram's version code. On the first run (or after an IG update), DexKit
 * runs as normal and saves each resolved {@link Method} descriptor. On every
 * subsequent launch of the same IG version, the saved descriptor is used to
 * look up the {@link Method} via reflection — skipping DexKit entirely.
 *
 * <h3>Usage pattern (single method)</h3>
 * <pre>
 *   if (DexKitCache.isCacheValid()) {
 *       Method m = DexKitCache.loadMethod("MyHook", classLoader);
 *       if (m != null) { hookIt(m); return; }
 *   }
 *   // DexKit path
 *   Method m = findViaDexKit(bridge);
 *   if (m != null) {
 *       DexKitCache.saveMethod("MyHook", m);
 *       hookIt(m);
 *   }
 * </pre>
 *
 * <h3>Separator</h3>
 * Method descriptors are encoded as {@code className + NUL + methodName + NUL + descriptor}.
 * The NUL character ({@code \u0000}) never appears in class/method names or JVM descriptors.
 */
public class DexKitCache {

    private static final String PREF_NAME  = "instaeclipse_dexkit_cache";
    private static final String KEY_VER    = "_v";

    private static SharedPreferences prefs;
    private static boolean cacheValid = false;
    private static boolean restored = false;

    // ── Lifecycle ────────────────────────────────────────────────────────────

    /**
     * Must be called once per app-attach, before any hooks run.
     * Compares {@code igVersion} (e.g. the IG long version code as a string)
     * with the stored version; clears the cache when they differ.
     */
    public static void init(Context context, String igVersion) {
        init(context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE), igVersion);
    }

    static void init(SharedPreferences preferences, String igVersion) {
        prefs = preferences;
        String stored = prefs.getString(KEY_VER, "");
        restored = stored.equals(igVersion);
        if (restored) {
            ModuleLog.detail("(DexKitCache) Cache valid for IG " + igVersion);
        } else {
            prefs.edit().clear().putString(KEY_VER, igVersion).apply();
            ModuleLog.line("(DexKitCache) Instagram or InstaEclipse changed since the last start, so Instagram's code is searched again (this start takes longer)");
            ModuleLog.detail("(DexKitCache) Version " + stored + " → " + igVersion + ", cache cleared");
        }
        cacheValid = true;
    }

    /** Returns {@code true} when the stored cache was built for the currently-running IG version. */
    public static boolean isCacheValid() {
        return cacheValid;
    }

    static boolean isRestored() {
        return restored;
    }

    /**
     * Wipes all cached descriptors and marks the cache invalid for this session.
     * DexKit will re-run its full search on the next Instagram restart.
     */
    public static void clearCache() {
        if (prefs == null) return;
        prefs.edit().clear().apply();
        cacheValid = false;
        restored = false;
        ModuleLog.line("(DexKitCache) Cache cleared; Instagram's code is searched again on the next start");
    }

    // ── Single method ────────────────────────────────────────────────────────

    public static void saveMethod(String key, Method m) {
        if (prefs == null || m == null) return;
        prefs.edit().putString("m_" + key, encode(m)).apply();
    }

    /** Returns {@code null} on any decode failure (treat as a cache miss). */
    public static Method loadMethod(String key, ClassLoader loader) {
        if (prefs == null) return null;
        String val = prefs.getString("m_" + key, null);
        Method m = val != null ? decode(val, loader) : null;
        if (m != null) savedHit();
        return m;
    }

    // ── Multiple methods ─────────────────────────────────────────────────────

    public static void saveMethods(String key, List<Method> methods) {
        saveAll("mc_", "m_", key, methods, MemberDescriptors::encode);
    }

    /**
     * Returns the cached list, or {@code null} if any entry is missing / cannot
     * be decoded (so the whole DexKit search is re-run in that case).
     */
    public static List<Method> loadMethods(String key, ClassLoader loader) {
        return loadAll("mc_", "m_", key, loader, MemberDescriptors::decodeMethod);
    }

    public static void saveField(String key, Field f) {
        if (prefs == null || f == null) return;
        prefs.edit().putString("f_" + key, MemberDescriptors.encode(f)).apply();
    }

    public static Field loadField(String key, ClassLoader loader) {
        if (prefs == null) return null;
        String val = prefs.getString("f_" + key, null);
        Field f = val != null ? MemberDescriptors.decodeField(val, loader) : null;
        if (f != null) savedHit();
        return f;
    }

    public static void saveFields(String key, List<Field> fields) {
        saveAll("fc_", "f_", key, fields, MemberDescriptors::encode);
    }

    public static List<Field> loadFields(String key, ClassLoader loader) {
        return loadAll("fc_", "f_", key, loader, MemberDescriptors::decodeField);
    }

    public static void saveConstructor(String key, Constructor<?> c) {
        if (prefs == null || c == null) return;
        prefs.edit().putString("c_" + key, MemberDescriptors.encode(c)).apply();
    }

    public static Constructor<?> loadConstructor(String key, ClassLoader loader) {
        if (prefs == null) return null;
        String val = prefs.getString("c_" + key, null);
        Constructor<?> c = val != null ? MemberDescriptors.decodeConstructor(val, loader) : null;
        if (c != null) savedHit();
        return c;
    }

    public static void saveConstructors(String key, List<Constructor<?>> constructors) {
        saveAll("cc_", "c_", key, constructors, MemberDescriptors::encode);
    }

    public static List<Constructor<?>> loadConstructors(String key, ClassLoader loader) {
        return loadAll("cc_", "c_", key, loader, MemberDescriptors::decodeConstructor);
    }

    private static <T> void saveAll(String countPrefix, String prefix, String key, List<T> members,
                                    Function<T, String> encoder) {
        if (prefs == null || members == null) return;
        SharedPreferences.Editor ed = prefs.edit();
        ed.putInt(countPrefix + key, members.size());
        for (int i = 0; i < members.size(); i++) {
            ed.putString(prefix + key + "_" + i, encoder.apply(members.get(i)));
        }
        ed.apply();
    }

    private static <T> List<T> loadAll(String countPrefix, String prefix, String key, ClassLoader loader,
                                       BiFunction<String, ClassLoader, T> decoder) {
        if (prefs == null) return null;
        int count = prefs.getInt(countPrefix + key, -1);
        if (count < 0) return null;
        List<T> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String val = prefs.getString(prefix + key + "_" + i, null);
            if (val == null) return null;
            T member = decoder.apply(val, loader);
            if (member == null) return null;
            result.add(member);
        }
        savedHit();
        return result;
    }

    // ── Arbitrary strings (class names, etc.) ────────────────────────────────

    public static void saveString(String key, String value) {
        if (prefs == null) return;
        prefs.edit().putString("s_" + key, value).apply();
    }

    public static String loadString(String key) {
        String val = loadSaved(key);
        if (val != null) savedHit();
        return val;
    }

    static String loadSaved(String key) {
        if (prefs == null) return null;
        return prefs.getString("s_" + key, null);
    }

    private static void savedHit() {
        if (cacheValid) DexKitStats.hit();
    }

    // ── Encoding / Decoding ──────────────────────────────────────────────────

    private static String encode(Method m) {
        return MemberDescriptors.encode(m);
    }

    private static Method decode(String encoded, ClassLoader loader) {
        return MemberDescriptors.decodeMethod(encoded, loader);
    }
}
