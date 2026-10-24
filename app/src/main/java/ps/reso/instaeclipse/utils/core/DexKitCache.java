package ps.reso.instaeclipse.utils.core;

import android.content.Context;
import android.content.SharedPreferences;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

import ps.reso.instaeclipse.BuildConfig;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.log.StartupTrace;

public class DexKitCache {

    static final String PREF_NAME = "instaeclipse_dexkit_cache";
    static final String QUERY_PREF_NAME = "instaeclipse_dexkit_queries";
    public static final String PRELOAD_SPAN = "dexkit.prefs";
    static final int BATCH_SIZE = 25;
    private static final String KEY_VER = "_v";
    private static final String QUERY_PREFIX = "s_q_";
    private static final String SEARCH_AGAIN = "(DexKitCache) Instagram or InstaEclipse changed since the last start, so Instagram's code is searched again (this start takes longer)";
    private static final Object REMOVED = new Object();

    public static final class Writes {
        public final long handSaves;
        public final long querySaves;
        public final long applies;

        Writes(long handSaves, long querySaves, long applies) {
            this.handSaves = handSaves;
            this.querySaves = querySaves;
            this.applies = applies;
        }
    }

    private static final class Store {
        final SharedPreferences prefs;
        final Map<String, Object> pending = new HashMap<>();
        SharedPreferences.Editor edits;
        int batched;
        long saves;

        Store(SharedPreferences prefs) {
            this.prefs = prefs;
        }
    }

    private static final Object LOCK = new Object();
    private static volatile Store hand;
    private static volatile Store queries;
    private static boolean batching;
    private static long applies;
    private static boolean cacheValid = false;
    private static boolean restored = false;

    public static void preload(Context context) {
        StartupTrace.Span span = StartupTrace.begin(PRELOAD_SPAN);
        try {
            for (String name : files()) context.getSharedPreferences(name, Context.MODE_PRIVATE);
        } finally {
            StartupTrace.end(span);
        }
    }

    public static void init(Context context, String handKey, String queryKey) {
        init(name -> context.getSharedPreferences(name, Context.MODE_PRIVATE), handKey, queryKey);
    }

    public static String queryKey(long igVersionCode, long igApkLength) {
        return igVersionCode + "|" + igApkLength + "|" + BuildConfig.DEXKIT_VERSION + "|" + DexKitQueryCache.QUERY_SCHEMA;
    }

    static List<String> files() {
        return LegacyPaths.has(LegacyPaths.CACHE) ? Collections.singletonList(PREF_NAME) : List.of(PREF_NAME, QUERY_PREF_NAME);
    }

    static void init(Function<String, SharedPreferences> open, String handKey, String queryKey) {
        SharedPreferences handPrefs = open.apply(PREF_NAME);
        if (LegacyPaths.has(LegacyPaths.CACHE)) init(handPrefs, handKey);
        else init(handPrefs, handKey, open.apply(QUERY_PREF_NAME), queryKey);
    }

    static void init(SharedPreferences preferences, String igVersion) {
        String stored = preferences.getString(KEY_VER, "");
        boolean kept = stored.equals(igVersion);
        if (kept) {
            ModuleLog.detail("(DexKitCache) Cache valid for IG " + igVersion);
        } else {
            reset(preferences, igVersion);
            ModuleLog.line(SEARCH_AGAIN);
            ModuleLog.detail("(DexKitCache) Version " + stored + " → " + igVersion + ", cache cleared");
        }
        Store store = new Store(preferences);
        start(store, store, kept);
    }

    static void init(SharedPreferences handPrefs, String handKey, SharedPreferences queryPrefs, String queryKey) {
        String storedHand = handPrefs.getString(KEY_VER, "");
        String storedQuery = queryPrefs.getString(KEY_VER, "");
        boolean handKept = storedHand.equals(handKey);
        boolean queriesKept = storedQuery.equals(queryKey);
        if (!handKept) reset(handPrefs, handKey);
        if (!queriesKept) reset(queryPrefs, queryKey);
        if (handKept && queriesKept) {
            ModuleLog.detail("(DexKitCache) Cache valid for IG " + handKey);
        } else if (queriesKept) {
            ModuleLog.detail("(DexKitCache) InstaEclipse changed since the last start; its saved searches of Instagram's code are kept");
        } else {
            ModuleLog.line(SEARCH_AGAIN);
        }
        if (!handKept) ModuleLog.detail("(DexKitCache) Version " + storedHand + " → " + handKey + ", own lookups cleared");
        if (!queriesKept) ModuleLog.detail("(DexKitCache) Search key " + storedQuery + " → " + queryKey + ", saved searches cleared");
        start(new Store(handPrefs), new Store(queryPrefs), queriesKept);
    }

    private static void reset(SharedPreferences preferences, String key) {
        preferences.edit().clear().putString(KEY_VER, key).apply();
        synchronized (LOCK) {
            applies++;
        }
    }

    private static void start(Store handStore, Store queryStore, boolean queriesKept) {
        synchronized (LOCK) {
            hand = handStore;
            queries = queryStore;
            batching = false;
        }
        restored = queriesKept;
        cacheValid = true;
    }

    public static boolean isCacheValid() {
        return cacheValid;
    }

    static boolean isRestored() {
        return restored;
    }

    public static void clearCache() {
        Store handStore = hand;
        Store queryStore = queries;
        if (handStore == null) return;
        synchronized (LOCK) {
            for (Store store : stores(handStore, queryStore)) {
                store.pending.clear();
                store.edits = null;
                store.batched = 0;
                store.prefs.edit().clear().apply();
                applies++;
            }
        }
        cacheValid = false;
        restored = false;
        ModuleLog.line("(DexKitCache) Cache cleared; Instagram's code is searched again on the next start");
    }

    public static void beginBatch() {
        if (LegacyPaths.has(LegacyPaths.CACHE)) return;
        synchronized (LOCK) {
            if (hand != null) batching = true;
        }
    }

    public static void flushBatch() {
        synchronized (LOCK) {
            Store handStore = hand;
            if (handStore != null) {
                for (Store store : stores(handStore, queries)) applyEdits(store);
            }
            batching = false;
        }
    }

    public static Writes writes() {
        synchronized (LOCK) {
            Store handStore = hand;
            Store queryStore = queries;
            long handSaves = handStore == null ? 0 : handStore.saves;
            long querySaves = queryStore == null || queryStore == handStore ? 0 : queryStore.saves;
            return new Writes(handSaves, querySaves, applies);
        }
    }

    private static List<Store> stores(Store handStore, Store queryStore) {
        return queryStore == null || queryStore == handStore ? Collections.singletonList(handStore) : List.of(handStore, queryStore);
    }

    private static void applyEdits(Store store) {
        if (store.edits == null) return;
        store.edits.apply();
        applies++;
        store.edits = null;
        store.pending.clear();
        store.batched = 0;
    }

    private static Store store(String storedKey) {
        return storedKey.startsWith(QUERY_PREFIX) ? queries : hand;
    }

    private static void save(String storedKey, Object value) {
        save(Collections.singletonMap(storedKey, value));
    }

    private static void save(Map<String, Object> values) {
        Store store = store(values.keySet().iterator().next());
        if (store == null) return;
        SharedPreferences.Editor editor;
        synchronized (LOCK) {
            store.saves++;
            if (batching) {
                if (store.edits == null) store.edits = store.prefs.edit();
                put(store.edits, values);
                for (Map.Entry<String, Object> e : values.entrySet()) {
                    store.pending.put(e.getKey(), e.getValue() == null ? REMOVED : e.getValue());
                }
                if (++store.batched >= BATCH_SIZE) applyEdits(store);
                return;
            }
            applies++;
        }
        editor = store.prefs.edit();
        put(editor, values);
        editor.apply();
    }

    private static void put(SharedPreferences.Editor editor, Map<String, Object> values) {
        for (Map.Entry<String, Object> e : values.entrySet()) {
            if (e.getValue() instanceof Integer) editor.putInt(e.getKey(), (Integer) e.getValue());
            else editor.putString(e.getKey(), (String) e.getValue());
        }
    }

    private static Object pending(Store store, String storedKey) {
        synchronized (LOCK) {
            return store.pending.get(storedKey);
        }
    }

    private static String savedString(String storedKey) {
        Store store = store(storedKey);
        if (store == null) return null;
        Object value = pending(store, storedKey);
        if (value == REMOVED) return null;
        if (value instanceof String) return (String) value;
        return store.prefs.getString(storedKey, null);
    }

    private static int savedInt(String storedKey) {
        Store store = store(storedKey);
        if (store == null) return -1;
        Object value = pending(store, storedKey);
        if (value == REMOVED) return -1;
        if (value instanceof Integer) return (Integer) value;
        return store.prefs.getInt(storedKey, -1);
    }

    public static void saveMethod(String key, Method m) {
        if (m == null) return;
        save("m_" + key, encode(m));
    }

    public static Method loadMethod(String key, ClassLoader loader) {
        String val = savedString(read("m_" + key));
        Method m = val != null ? decode(val, loader) : null;
        if (m != null) savedHit();
        return m;
    }

    public static void saveMethods(String key, List<Method> methods) {
        saveAll("mc_", "m_", key, methods, MemberDescriptors::encode);
    }

    public static List<Method> loadMethods(String key, ClassLoader loader) {
        return loadAll("mc_", "m_", key, loader, MemberDescriptors::decodeMethod);
    }

    public static void saveField(String key, Field f) {
        if (f == null) return;
        save("f_" + key, MemberDescriptors.encode(f));
    }

    public static Field loadField(String key, ClassLoader loader) {
        String val = savedString(read("f_" + key));
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
        if (c == null) return;
        save("c_" + key, MemberDescriptors.encode(c));
    }

    public static Constructor<?> loadConstructor(String key, ClassLoader loader) {
        String val = savedString(read("c_" + key));
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
        if (members == null) return;
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(countPrefix + key, members.size());
        for (int i = 0; i < members.size(); i++) {
            values.put(prefix + key + "_" + i, encoder.apply(members.get(i)));
        }
        save(values);
    }

    private static <T> List<T> loadAll(String countPrefix, String prefix, String key, ClassLoader loader,
                                       BiFunction<String, ClassLoader, T> decoder) {
        int count = savedInt(read(countPrefix + key));
        if (count < 0) return null;
        List<T> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String val = savedString(prefix + key + "_" + i);
            if (val == null) return null;
            T member = decoder.apply(val, loader);
            if (member == null) return null;
            result.add(member);
        }
        savedHit();
        return result;
    }

    public static void saveString(String key, String value) {
        save("s_" + key, value);
    }

    public static String loadString(String key) {
        String val = savedString(read("s_" + key));
        if (val != null) savedHit();
        return val;
    }

    public static String loadSaved(String key) {
        return savedString("s_" + key);
    }

    public static boolean has(String storedKey) {
        Store store = store(storedKey);
        if (store == null) return false;
        Object value = pending(store, storedKey);
        if (value == REMOVED) return false;
        return value != null || store.prefs.contains(storedKey);
    }

    public static void remove(String key) {
        save("s_" + key, null);
    }

    private static String read(String storedKey) {
        DexKitStats.keyRead(storedKey);
        return storedKey;
    }

    private static void savedHit() {
        if (cacheValid) DexKitStats.hit();
    }

    static String encode(Method m) {
        return MemberDescriptors.encode(m);
    }

    static Method decode(String encoded, ClassLoader loader) {
        return MemberDescriptors.decodeMethod(encoded, loader);
    }

    static String descriptor(Method m) {
        return MemberDescriptors.descriptor(m);
    }
}
