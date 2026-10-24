package ps.reso.instaeclipse.utils.core;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.base.BaseFinder;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.ClassDataList;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;
import org.luckypray.dexkit.result.UsingFieldData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Remembers DexKit query results for the running Instagram version (#285).
 *
 * A {@code findMethod}/{@code findClass} scan walks the whole APK (~90 ms each on a mid-range
 * phone), and the hooks run about a hundred of them while Instagram's main thread waits in
 * {@code Application.attach}. The same query on the same APK always returns the same members, so
 * the descriptors found on the first launch are stored in {@link DexKitCache} under a hash of the
 * query's serialized form, and later launches rebuild the result with
 * {@link DexKitBridge#getMethodData(String)} / {@link DexKitBridge#getClassData(String)}, which are
 * direct lookups. The result is real DexKit data, so callers keep using it as before.
 */
public final class DexKitQueryCache {

    static final int QUERY_SCHEMA = 1;

    private DexKitQueryCache() {}

    /** {@code bridge.findMethod(query)}, answered from the cache when this query ran before. */
    public static MethodDataList findMethod(DexKitBridge bridge, FindMethod query) {
        String key = key("fm", query);
        String cached = load(key);
        if (cached != null) {
            MethodDataList hit = methods(bridge, cached);
            if (hit != null) {
                DexKitStats.hit();
                return hit;
            }
        }
        long start = System.nanoTime();
        MethodDataList result = bridge.findMethod(query);
        searched(key, start);
        if (key != null) DexKitCache.saveString(key, joinMethods(result));
        return result;
    }

    /** {@code bridge.findClass(query)}, answered from the cache when this query ran before. */
    public static ClassDataList findClass(DexKitBridge bridge, FindClass query) {
        String key = key("fc", query);
        String cached = load(key);
        if (cached != null) {
            ClassDataList hit = classes(bridge, cached);
            if (hit != null) {
                DexKitStats.hit();
                return hit;
            }
        }
        long start = System.nanoTime();
        ClassDataList result = bridge.findClass(query);
        searched(key, start);
        if (key != null) {
            List<String> descriptors = new ArrayList<>();
            for (ClassData c : result) descriptors.add(c.getDescriptor());
            DexKitCache.saveString(key, encodeResult(descriptors));
        }
        return result;
    }

    /** {@code method.getCallers()}: a cross-reference scan of the whole APK on a miss. */
    public static MethodDataList callers(DexKitBridge bridge, MethodData method) {
        String key = "q_cl_" + method.getDescriptor();
        String cached = load(key);
        if (cached != null) {
            MethodDataList hit = methods(bridge, cached);
            if (hit != null) {
                DexKitStats.hit();
                return hit;
            }
        }
        long start = System.nanoTime();
        MethodDataList result = method.getCallers();
        searched(key, start);
        DexKitCache.saveString(key, joinMethods(result));
        return result;
    }

    public static MethodDataList callersUncached(MethodData method) {
        DexKitStats.nativeCalls(1);
        return method.getCallers();
    }

    public static MethodDataList invokes(MethodData method) {
        DexKitStats.nativeCalls(1);
        return method.getInvokes();
    }

    public static List<UsingFieldData> usingFields(MethodData method) {
        DexKitStats.nativeCalls(1);
        return method.getUsingFields();
    }

    public static MethodData methodData(DexKitBridge bridge, String descriptor) {
        DexKitStats.nativeCalls(1);
        return bridge.getMethodData(descriptor);
    }

    private static String load(String key) {
        DexKitStats.keyRead(key);
        if (key == null) return null;
        return DexKitCache.loadSaved(key);
    }

    private static void searched(String key, long startNanos) {
        long nanos = System.nanoTime() - startNanos;
        DexKitStats.miss(nanos);
        if (DexKitCache.isRestored()) {
            ModuleLog.detail("(DexKitCache) Searched Instagram's code again although the saved lookups are valid: "
                    + key + " in " + DexKitStats.currentStep() + " (" + nanos / 1_000_000L + " ms)");
        }
    }

    /** Hash of the query as DexKit serializes it; null when it cannot be built. */
    static String key(String kind, BaseFinder query) {
        try {
            return "q_" + kind + "_" + query.hashKey();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String joinMethods(List<MethodData> methods) {
        List<String> descriptors = new ArrayList<>();
        for (MethodData m : methods) descriptors.add(m.getDescriptor());
        return encodeResult(descriptors);
    }

    static String encodeResult(List<String> descriptors) {
        return String.join("\n", descriptors);
    }

    static List<String> decodeResult(String cached) {
        return cached.isEmpty() ? Collections.emptyList() : Arrays.asList(cached.split("\n"));
    }

    /** Null when a stored descriptor no longer resolves (treated as a miss). */
    private static MethodDataList methods(DexKitBridge bridge, String cached) {
        MethodDataList list = new MethodDataList();
        try {
            for (String desc : decodeResult(cached)) {
                DexKitStats.nativeCalls(1);
                MethodData m = bridge.getMethodData(desc);
                if (m == null) return null;
                list.add(m);
            }
        } catch (Throwable t) {
            return null;
        }
        return list;
    }

    private static ClassDataList classes(DexKitBridge bridge, String cached) {
        ClassDataList list = new ClassDataList();
        try {
            for (String desc : decodeResult(cached)) {
                DexKitStats.nativeCalls(1);
                ClassData c = bridge.getClassData(desc);
                if (c == null) return null;
                list.add(c);
            }
        } catch (Throwable t) {
            return null;
        }
        return list;
    }
}
