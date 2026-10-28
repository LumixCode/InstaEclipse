package ps.reso.instaeclipse.utils.core;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

public final class CachedLookup {

    static final String MISSING = "missing";

    public interface Search<T> { T find() throws Throwable; }

    private CachedLookup() {}

    public static List<Method> methods(String key, ClassLoader loader, Search<List<Method>> search) throws Throwable {
        if (DexKitCache.isCacheValid()) {
            if (MISSING.equals(DexKitCache.loadString(key))) return Collections.emptyList();
            List<Method> cached = DexKitCache.loadMethods(key, loader);
            if (cached != null) return cached;
        }
        List<Method> found = search.find();
        if (found.isEmpty()) DexKitCache.saveString(key, MISSING);
        else DexKitCache.saveMethods(key, found);
        return found;
    }

    public static Method method(String key, ClassLoader loader, Search<Method> search) throws Throwable {
        if (DexKitCache.isCacheValid()) {
            if (MISSING.equals(DexKitCache.loadString(key))) return null;
            Method cached = DexKitCache.loadMethod(key, loader);
            if (cached != null) return cached;
        }
        Method found = search.find();
        if (found == null) DexKitCache.saveString(key, MISSING);
        else DexKitCache.saveMethod(key, found);
        return found;
    }

    public static Field field(String key, ClassLoader loader, Search<Field> search) throws Throwable {
        String cached = DexKitCache.isCacheValid() ? DexKitCache.loadString(key) : null;
        if (MISSING.equals(cached)) return null;
        if (cached != null) {
            int split = cached.lastIndexOf('#');
            try {
                Field field = Class.forName(cached.substring(0, split), false, loader)
                        .getDeclaredField(cached.substring(split + 1));
                field.setAccessible(true);
                return field;
            } catch (ReflectiveOperationException | RuntimeException ignored) {}
        }
        Field found = search.find();
        DexKitCache.saveString(key, found == null ? MISSING
                : found.getDeclaringClass().getName() + "#" + found.getName());
        return found;
    }
}
