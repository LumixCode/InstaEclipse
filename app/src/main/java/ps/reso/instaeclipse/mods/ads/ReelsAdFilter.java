package ps.reso.instaeclipse.mods.ads;

import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.SafeReflect;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.Found;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.FoundClass;

public final class ReelsAdFilter {

    private static final String TAG = "(InstaEclipse | AdBlocker): ";
    private static final String NOT_FOUND = "Ads between reels in the Reels tab may still show: the reels list was not found in this Instagram version";
    private static final String ORGANIC_ONLY_MARKER = "_ClipsSyncFlowUtil_maybeGenerateClipsItemListWithOrganicOnly";
    private static final String CLIPS_ITEM_MARKER = "ClipsItem(content=";
    private static final String ORGANIC_ONLY_KEY = "ReelsAdFilter_organicOnly_v1";
    private static final String IS_AD_KEY = "ReelsAdFilter_isAd_v1";
    private static final String TYPE_KEY = "ReelsAdFilter_type_v1";

    static final AtomicBoolean loggedFailure = new AtomicBoolean();

    private ReelsAdFilter() {}

    public static void install(ClassLoader classLoader) {
        try {
            if (DexKitCache.isCacheValid() && hookSaved(classLoader)) return;
            List<Found> targets = DexKitQueryCache.methods(organicOnlyQuery());
            Found target = targets.isEmpty() ? null : targets.get(0);
            Method isAdCheck = target == null ? null : findIsAdCheck(target.getDescriptor(), classLoader);
            Field type = isAdCheck == null ? null : findType(isAdCheck.getDeclaringClass(), classLoader);
            if (type == null) {
                ModuleLog.warn(TAG + NOT_FOUND);
                return;
            }
            Method organicOnly = target.getMethodInstance(classLoader);
            DexKitCache.saveMethod(ORGANIC_ONLY_KEY, organicOnly);
            DexKitCache.saveMethod(IS_AD_KEY, isAdCheck);
            DexKitCache.saveField(TYPE_KEY, type);
            hook(organicOnly, isAdCheck, type);
        } catch (Throwable t) {
            ModuleLog.warn(TAG + NOT_FOUND, t);
        }
    }

    static FindMethod organicOnlyQuery() {
        return FindMethod.create().matcher(MethodMatcher.create()
                .usingStrings(ORGANIC_ONLY_MARKER)
                .modifiers(Modifier.STATIC)
                .paramCount(3)
                .returnType("java.util.List"));
    }

    static FindMethod isAdCheckQuery(String organicOnly) {
        return FindMethod.create().matcher(MethodMatcher.create()
                .addCaller(organicOnly)
                .modifiers(Modifier.STATIC)
                .paramCount(1)
                .returnType("boolean"));
    }

    static FindClass clipsItemQuery() {
        return FindClass.create().matcher(ClassMatcher.create().usingStrings(CLIPS_ITEM_MARKER));
    }

    private static boolean hookSaved(ClassLoader classLoader) {
        Method organicOnly = DexKitCache.loadMethod(ORGANIC_ONLY_KEY, classLoader);
        Method isAdCheck = DexKitCache.loadMethod(IS_AD_KEY, classLoader);
        Field type = DexKitCache.loadField(TYPE_KEY, classLoader);
        if (organicOnly == null || isAdCheck == null || type == null) return false;
        hook(organicOnly, isAdCheck, type);
        return true;
    }

    private static Method findIsAdCheck(String organicOnly, ClassLoader classLoader) throws NoSuchMethodException {
        List<Found> calls = DexKitQueryCache.methods(isAdCheckQuery(organicOnly));
        Method check = enumCheck(calls, classLoader);
        if (check != null) return check;
        for (Found wrapper : calls) {
            check = enumCheck(DexKitQueryCache.invokesCached(wrapper), classLoader);
            if (check != null) return check;
        }
        return null;
    }

    private static Method enumCheck(List<Found> calls, ClassLoader classLoader) throws NoSuchMethodException {
        for (Found found : calls) {
            if (!found.isMethod() || !found.getReturnTypeName().equals("boolean")
                    || !found.getParamTypeNames().equals(List.of(found.getClassName()))) continue;
            Method check = found.getMethodInstance(classLoader);
            if (check.getDeclaringClass().isEnum() && Modifier.isStatic(check.getModifiers())) return check;
        }
        return null;
    }

    private static Field findType(Class<?> kind, ClassLoader classLoader) throws ClassNotFoundException {
        for (FoundClass item : DexKitQueryCache.classes(clipsItemQuery())) {
            for (Field field : SafeReflect.fields(item.getInstance(classLoader))) {
                if (!Modifier.isStatic(field.getModifiers()) && field.getType() == kind) return field;
            }
        }
        return null;
    }

    private static void hook(Method organicOnly, Method isAdCheck, Field type) {
        XposedBridge.hookMethod(organicOnly, callback(isAdCheck, type));
        ModuleLog.detail(TAG + "Reels-tab ad filter on " + organicOnly.getDeclaringClass().getName() + "." + organicOnly.getName());
    }

    static XC_MethodHook callback(Method isAdCheck, Field type) {
        isAdCheck.setAccessible(true);
        type.setAccessible(true);
        Predicate<Object> isAd = item -> isAd(item, isAdCheck, type);
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!FeatureFlags.isAdBlockEnabled || !(param.getResult() instanceof List<?> items)) return;
                List<?> kept = filter(items, isAd);
                if (kept != items) param.setResult(kept);
            }
        };
    }

    static <T> List<T> filter(List<T> items, Predicate<? super T> isAd) {
        if (isAd == null) {
            failed(null);
            return items;
        }
        if (items == null || items.isEmpty()) return items;
        try {
            List<T> kept = new ArrayList<>(items.size());
            for (T item : items) {
                if (!isAd.test(item)) kept.add(item);
            }
            return kept.size() == items.size() ? items : kept;
        } catch (Throwable t) {
            failed(t);
            return items;
        }
    }

    private static boolean isAd(Object item, Method isAdCheck, Field type) {
        if (!type.getDeclaringClass().isInstance(item)) return false;
        try {
            Object kind = type.get(item);
            return kind != null && Boolean.TRUE.equals(isAdCheck.invoke(null, kind));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void failed(Throwable t) {
        if (loggedFailure.getAndSet(true)) return;
        ModuleLog.warn(TAG + "Ads between reels in the Reels tab may still show: the ad check failed", t);
    }
}
