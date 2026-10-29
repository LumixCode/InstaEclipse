package ps.reso.instaeclipse.utils.ui;

import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.Found;
import ps.reso.instaeclipse.utils.core.ModuleBridge;

public final class ViewPager2Input {

    private ViewPager2Input() {}

    private static final String VIEWPAGER2 = "androidx.viewpager2.widget.ViewPager2";
    private static final String CACHE_PAGER_INPUT = "ViewPager2_userInputEnabled";
    private static final String CACHE_SETTER_INPUT = "ReelPagerInput_v1";

    private static Field userInputField;
    private static boolean userInputFieldTried;

    public static synchronized Field field(View pager) {
        if (userInputFieldTried) return userInputField;
        userInputFieldTried = true;
        try {
            Class<?> vp = viewPager2Class(pager);
            if (vp == null) return null;
            try {
                Field f = vp.getDeclaredField("mUserInputEnabled");
                f.setAccessible(true);
                return userInputField = f;
            } catch (NoSuchFieldException ignored) {}
            String cached = DexKitCache.isCacheValid() ? DexKitCache.loadString(CACHE_PAGER_INPUT) : null;
            if (cached != null) {
                try {
                    Field f = vp.getDeclaredField(cached);
                    f.setAccessible(true);
                    return userInputField = f;
                } catch (NoSuchFieldException ignored) {}
            }
            Class<?> inner = null;
            if (pager instanceof ViewGroup g) {
                for (int i = 0; i < g.getChildCount() && inner == null; i++) {
                    Class<?> c = g.getChildAt(i).getClass();
                    for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(c)) if (f.getType() == vp) { inner = c; break; }
                }
            }
            if (inner == null) return null;
            if (ModuleBridge.request() == null) {
                userInputFieldTried = false;
                return null;
            }
            for (Found md : DexKitQueryCache.methods(org.luckypray.dexkit.query.FindMethod.create()
                    .matcher(org.luckypray.dexkit.query.matchers.MethodMatcher.create()
                            .declaredClass(inner.getName()).name("onInterceptTouchEvent")))) {
                for (org.luckypray.dexkit.result.UsingFieldData uf : DexKitQueryCache.usingFields(md)) {
                    org.luckypray.dexkit.result.FieldData fd = uf.getField();
                    if (fd.getDeclaredClassName().equals(vp.getName()) && fd.getTypeName().equals("boolean")) {
                        Field f = vp.getDeclaredField(fd.getFieldName());
                        f.setAccessible(true);
                        DexKitCache.saveString(CACHE_PAGER_INPUT, f.getName());
                        ModuleLog.detail("(IE|ViewPager2) user-input field = " + f.getName());
                        return userInputField = f;
                    }
                }
            }
            ModuleLog.warn("(IE|ViewPager2) Swiping between pages cannot be locked: the swipe switch was not found in this Instagram version");
        } catch (Throwable t) {
            ModuleLog.warn("(IE|ViewPager2) Swiping between pages cannot be locked: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        return null;
    }

    public static Field setterInputField(ClassLoader loader, Class<?> pagerClass, Method setter)
            throws ReflectiveOperationException {
        String cached = DexKitCache.isCacheValid() ? DexKitCache.loadString(CACHE_SETTER_INPUT) : null;
        if (cached != null) {
            try {
                Field field = pagerClass.getDeclaredField(cached);
                if (field.getType() == boolean.class) {
                    field.setAccessible(true);
                    return field;
                }
            } catch (NoSuchFieldException ignored) {}
        }
        Field inputField = null;
        for (var use : DexKitQueryCache.methodData(setter).getUsingFields()) {
            Field field = use.getField().getFieldInstance(loader);
            if (!use.getUsingType().isWrite() || field.getType() != boolean.class
                    || field.getDeclaringClass() != pagerClass || Modifier.isStatic(field.getModifiers())) continue;
            if (inputField != null) throw new IllegalStateException("Ambiguous pager input flag");
            inputField = field;
        }
        if (inputField == null) throw new NoSuchFieldException("Pager input flag");
        inputField.setAccessible(true);
        DexKitCache.saveString(CACHE_SETTER_INPUT, inputField.getName());
        return inputField;
    }

    private static Class<?> viewPager2Class(View pager) {
        Class<?> vp = pager == null ? null : pager.getClass();
        while (vp != null && !vp.getName().equals(VIEWPAGER2)) vp = vp.getSuperclass();
        return vp;
    }
}
