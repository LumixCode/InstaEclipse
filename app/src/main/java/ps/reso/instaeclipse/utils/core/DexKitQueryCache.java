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

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import ps.reso.instaeclipse.utils.log.ModuleLog;

public final class DexKitQueryCache {

    static final int QUERY_SCHEMA = 1;
    static final String NO_BRIDGE = "Instagram's code can't be searched right now";

    public static final class Found {
        private final String descriptor;
        private final String className;
        private final String name;
        private final String sign;
        private final List<String> types;
        private volatile MethodData data;

        Found(String descriptor) {
            int arrow = descriptor.indexOf(";->");
            int open = arrow < 0 ? -1 : descriptor.indexOf('(', arrow + 3);
            List<String> names = open < 0 ? null : MemberDescriptors.typeNames(descriptor.substring(open));
            if (!descriptor.startsWith("L") || arrow < 2 || open <= arrow + 3 || names == null) {
                throw new IllegalArgumentException("Not a method descriptor: " + descriptor);
            }
            this.descriptor = descriptor;
            this.className = descriptor.substring(1, arrow).replace('/', '.');
            this.name = descriptor.substring(arrow + 3, open);
            this.sign = descriptor.substring(open);
            this.types = Collections.unmodifiableList(names);
        }

        Found(MethodData data) {
            this(data.getDescriptor());
            this.data = data;
        }

        public String getDescriptor() {
            return descriptor;
        }

        public String getClassName() {
            return className;
        }

        public String getDeclaredClassName() {
            return className;
        }

        public String getName() {
            return name;
        }

        public boolean isConstructor() {
            return "<init>".equals(name);
        }

        public boolean isStaticInitializer() {
            return "<clinit>".equals(name);
        }

        public boolean isMethod() {
            return !isConstructor() && !isStaticInitializer();
        }

        public List<String> getParamTypeNames() {
            return types.subList(0, types.size() - 1);
        }

        public int getParamCount() {
            return types.size() - 1;
        }

        public String getReturnTypeName() {
            return types.get(types.size() - 1);
        }

        public Method getMethodInstance(ClassLoader loader) throws NoSuchMethodException {
            Method m = isMethod() ? MemberDescriptors.decodeMethod(className + '\u0000' + name + '\u0000' + sign, loader) : null;
            if (m == null) throw new NoSuchMethodException(descriptor);
            return m;
        }

        public Constructor<?> getConstructorInstance(ClassLoader loader) throws NoSuchMethodException {
            Constructor<?> c = isConstructor() ? MemberDescriptors.decodeConstructor(className, sign, loader) : null;
            if (c == null) throw new NoSuchMethodException(descriptor);
            return c;
        }

        public MethodData data() {
            MethodData d = data;
            if (d != null) return d;
            DexKitStats.nativeCalls(1);
            d = bridge().getMethodData(descriptor);
            if (d == null) throw new IllegalStateException("Not in Instagram's code: " + descriptor);
            data = d;
            return d;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Found && descriptor.equals(((Found) o).descriptor);
        }

        @Override
        public int hashCode() {
            return descriptor.hashCode();
        }

        @Override
        public String toString() {
            return descriptor;
        }
    }

    public static final class FoundClass {
        private final String descriptor;
        private final String name;
        private volatile ClassData data;

        FoundClass(String descriptor) {
            if (descriptor.length() < 3 || descriptor.charAt(0) != 'L' || descriptor.indexOf(';') != descriptor.length() - 1) {
                throw new IllegalArgumentException("Not a class descriptor: " + descriptor);
            }
            this.descriptor = descriptor;
            this.name = MemberDescriptors.typeName(descriptor);
        }

        FoundClass(ClassData data) {
            this(data.getDescriptor());
            this.data = data;
        }

        public String getDescriptor() {
            return descriptor;
        }

        public String getName() {
            return name;
        }

        public Class<?> getInstance(ClassLoader loader) throws ClassNotFoundException {
            return Class.forName(name, false, loader);
        }

        public ClassData data() {
            ClassData d = data;
            if (d != null) return d;
            DexKitStats.nativeCalls(1);
            d = bridge().getClassData(descriptor);
            if (d == null) throw new IllegalStateException("Not in Instagram's code: " + descriptor);
            data = d;
            return d;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof FoundClass && descriptor.equals(((FoundClass) o).descriptor);
        }

        @Override
        public int hashCode() {
            return descriptor.hashCode();
        }

        @Override
        public String toString() {
            return descriptor;
        }
    }

    private DexKitQueryCache() {}

    public static List<Found> methods(FindMethod query) {
        return methods(query, false);
    }

    public static List<Found> methodsIfReady(FindMethod query) {
        return methods(query, true);
    }

    private static List<Found> methods(FindMethod query, boolean ifReady) {
        String key = key("fm", query);
        String cached = load(key);
        if (cached != null) {
            List<Found> hit = foundMethods(cached);
            if (hit != null) {
                DexKitStats.hit();
                return hit;
            }
        }
        DexKitBridge bridge = ifReady ? ModuleBridge.request() : bridge();
        if (bridge == null) return null;
        long start = System.nanoTime();
        MethodDataList result = bridge.findMethod(query);
        searched(key, start);
        if (key != null) DexKitCache.saveString(key, joinMethods(result));
        return wrap(result);
    }

    public static List<FoundClass> classes(FindClass query) {
        String key = key("fc", query);
        String cached = load(key);
        if (cached != null) {
            List<FoundClass> hit = foundClasses(cached);
            if (hit != null) {
                DexKitStats.hit();
                return hit;
            }
        }
        DexKitBridge bridge = bridge();
        long start = System.nanoTime();
        ClassDataList result = bridge.findClass(query);
        searched(key, start);
        List<String> descriptors = new ArrayList<>();
        List<FoundClass> out = new ArrayList<>();
        for (ClassData c : result) {
            descriptors.add(c.getDescriptor());
            out.add(new FoundClass(c));
        }
        if (key != null) DexKitCache.saveString(key, encodeResult(descriptors));
        return out;
    }

    public static List<Found> callers(Found method) {
        String key = "q_cl_" + method.getDescriptor();
        String cached = load(key);
        if (cached != null) {
            List<Found> hit = foundMethods(cached);
            if (hit != null) {
                DexKitStats.hit();
                return hit;
            }
        }
        MethodData data = method.data();
        long start = System.nanoTime();
        MethodDataList result = data.getCallers();
        searched(key, start);
        DexKitCache.saveString(key, joinMethods(result));
        return wrap(result);
    }

    public static MethodDataList callersUncached(Found method) {
        return callersUncached(method.data());
    }

    public static MethodDataList invokes(Found method) {
        return invokes(method.data());
    }

    public static List<UsingFieldData> usingFields(Found method) {
        return usingFields(method.data());
    }

    public static MethodData methodData(Method method) {
        DexKitStats.nativeCalls(1);
        return bridge().getMethodData(method);
    }

    public static MethodData methodData(Constructor<?> constructor) {
        DexKitStats.nativeCalls(1);
        return bridge().getMethodData(constructor);
    }

    public static MethodData methodData(String descriptor) {
        DexKitStats.nativeCalls(1);
        return bridge().getMethodData(descriptor);
    }

    static DexKitBridge bridge() {
        DexKitBridge bridge = ModuleBridge.get();
        if (bridge == null) throw new IllegalStateException(NO_BRIDGE);
        return bridge;
    }

    public static List<Found> foundMethods(String cached) {
        List<Found> list = new ArrayList<>();
        try {
            for (String desc : decodeResult(cached)) list.add(new Found(desc));
        } catch (IllegalArgumentException e) {
            return null;
        }
        return list;
    }

    public static List<FoundClass> foundClasses(String cached) {
        List<FoundClass> list = new ArrayList<>();
        try {
            for (String desc : decodeResult(cached)) list.add(new FoundClass(desc));
        } catch (IllegalArgumentException e) {
            return null;
        }
        return list;
    }

    private static List<Found> wrap(List<MethodData> result) {
        List<Found> out = new ArrayList<>(result.size());
        for (MethodData m : result) out.add(new Found(m));
        return out;
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
}
