package ps.reso.instaeclipse.mods.extras;

import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.matchers.ClassMatcher;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.Found;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.FoundClass;
import ps.reso.instaeclipse.utils.core.HookTargetGuard;
import ps.reso.instaeclipse.utils.core.SafeReflect;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;

public final class ReelsOverlay {

    private static final String TAG = "(IE|ReelsOverlay) ";
    private static final String CACHE = "ReelsOverlay_";
    private static final String PILL = "android_purge_26_q3_OrganicSecondaryAttributionComponent_render";
    private static final String PILL_TYPE_PATH = CACHE + "pillType";
    private static final List<String> PILL_TYPE_NAMES = Arrays.asList("SEE_MORE_PROMPTS", "STORIES_TEMPLATE", "TEMPLATE");
    private static final String MARKER_PREFIX = "android_purge_";

    private ReelsOverlay() {}

    public static int hide(ClassLoader cl, BooleanSupplier on, String... markers) {
        XC_MethodHook drawNothing = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (on.getAsBoolean() && !param.hasThrowable()) param.setResult(null);
                } catch (Throwable ignored) {}
            }
        };
        int hidden = 0;
        for (String marker : markers) {
            Method render = render(cl, marker);
            if (render != null && hook(render, drawNothing)) hidden++;
        }
        return hidden;
    }

    public static boolean hidePills(ClassLoader cl, BooleanSupplier on, String... types) {
        Method render = render(cl, PILL);
        Field[] path = render == null ? null : typePath(cl, render.getDeclaringClass());
        if (path == null) return false;
        Set<String> hidden = new HashSet<>(Arrays.asList(types));
        return hook(render, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (!on.getAsBoolean() || param.hasThrowable() || param.getResult() == null) return;
                    String type = type(param.thisObject, path);
                    if (type != null && hidden.contains(type)) param.setResult(null);
                } catch (Throwable ignored) {}
            }
        });
    }

    static boolean owns(List<String> strings, String marker) {
        if (strings == null) return false;
        String suffix = marker.substring(marker.lastIndexOf('_'));
        boolean own = false;
        for (String s : strings) {
            if (s == null || !s.startsWith(MARKER_PREFIX) || !s.endsWith(suffix)) continue;
            if (!s.equals(marker)) return false;
            own = true;
        }
        return own;
    }

    private static Method render(ClassLoader cl, String marker) {
        Method render = ExtrasLookup.method(CACHE + marker, null, false, cl, () -> {
            for (Found md : ExtrasLookup.byStrings(marker)) {
                if (!md.isMethod()) continue;
                Method m = md.getMethodInstance(cl);
                if (ownRender(m) && owns(md.data().getUsingStrings(), marker)) return m;
                ModuleLog.detail(TAG + "skipped " + md.getClassName() + "." + md.getName() + ": not this part's own render");
            }
            return null;
        });
        if (render == null || ownRender(render)) return render;
        ModuleLog.detail(TAG + "skipped " + render.getDeclaringClass().getName() + "." + render.getName() + ": not this part's own render");
        return null;
    }

    private static boolean ownRender(Method m) {
        Class<?> owner = m.getDeclaringClass();
        return HookTargetGuard.ownClass(owner) && !Modifier.isStatic(m.getModifiers())
                && m.getParameterCount() == 1 && m.getReturnType() != Object.class
                && m.getReturnType().isAssignableFrom(owner);
    }

    private static boolean hook(Method render, XC_MethodHook hook) {
        String name = render.getDeclaringClass().getName() + "." + render.getName();
        try {
            XposedBridge.hookMethod(render, hook);
            ModuleLog.detail(TAG + "render " + name);
            return true;
        } catch (Throwable t) {
            ModuleLog.detail(TAG + "could not hook " + name + ": " + t);
            return false;
        }
    }

    private static String type(Object component, Field[] path) throws IllegalAccessException {
        Object value = component;
        for (Field field : path) {
            if (value == null) return null;
            value = field.get(value);
        }
        return value instanceof Enum<?> ? ((Enum<?>) value).name() : null;
    }

    private static Field[] typePath(ClassLoader cl, Class<?> component) {
        try {
            List<Field> path = DexKitCache.isCacheValid() ? DexKitCache.loadFields(PILL_TYPE_PATH, cl) : null;
            if (path == null) {
                path = findTypePath(cl, component);
                DexKitCache.saveFields(PILL_TYPE_PATH, path);
            }
            if (path.isEmpty() || path.get(0).getDeclaringClass() != component) return null;
            for (Field field : path) field.setAccessible(true);
            return path.toArray(new Field[0]);
        } catch (Throwable t) {
            ModuleLog.detail(TAG + "pill type lookup failed: " + t);
            return null;
        }
    }

    private static List<Field> findTypePath(ClassLoader cl, Class<?> component) {
        Class<?> type = pillType(cl);
        List<List<Field>> paths = new ArrayList<>();
        if (type != null) {
            for (Field outer : instanceFields(component)) {
                Class<?> holder = typeOf(outer);
                if (holder == type) {
                    paths.add(List.of(outer));
                } else if (holdsData(holder)) {
                    for (Field inner : instanceFields(holder)) {
                        if (typeOf(inner) == type) paths.add(List.of(outer, inner));
                    }
                }
            }
        }
        ModuleLog.detail(TAG + "pill type " + (type == null ? "not found"
                : type.getName() + ": " + paths.size() + " path(s) from " + component.getName()));
        return paths.size() == 1 ? paths.get(0) : new ArrayList<>();
    }

    private static Class<?> pillType(ClassLoader cl) {
        Class<?> type = null;
        for (FoundClass found : DexKitQueryCache.classes(FindClass.create().matcher(
                ClassMatcher.create().usingEqStrings(PILL_TYPE_NAMES)))) {
            Class<?> candidate;
            try {
                candidate = found.getInstance(cl);
            } catch (Throwable t) {
                continue;
            }
            if (!candidate.isEnum() || !constants(candidate).containsAll(PILL_TYPE_NAMES)) continue;
            if (type != null) return null;
            type = candidate;
        }
        return type;
    }

    private static Set<String> constants(Class<?> type) {
        Set<String> names = new HashSet<>();
        Object[] values = type.getEnumConstants();
        if (values == null) return names;
        for (Object value : values) names.add(((Enum<?>) value).name());
        return names;
    }

    private static List<Field> instanceFields(Class<?> c) {
        List<Field> out = new ArrayList<>();
        for (Field field : SafeReflect.fields(c)) {
            if (!Modifier.isStatic(field.getModifiers())) out.add(field);
        }
        return out;
    }

    private static Class<?> typeOf(Field field) {
        try {
            return field.getType();
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean holdsData(Class<?> c) {
        if (c == null || c.isPrimitive() || c.isArray() || c.isInterface() || c.isEnum()) return false;
        String name = c.getName();
        return !name.startsWith("java.") && !name.startsWith("android") && !name.startsWith("kotlin");
    }
}
