package ps.reso.instaeclipse.utils.ui;

import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;

import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;

/**
 * ViewPager2's user-input ("swipe enabled") flag, resolved without hardcoded obfuscated names.
 * IG's R8 build keeps the androidx.viewpager2.widget.ViewPager2 class name, but some builds drop
 * setUserInputEnabled() and the backing {@code mUserInputEnabled} field is renamed (A09 on 452).
 * Shared by DistractionFreeUIHook (main tab pager) and ReelViewerHook (Reels pager).
 */
public final class ViewPager2Input {

    private ViewPager2Input() {}

    private static final String VIEWPAGER2 = "androidx.viewpager2.widget.ViewPager2";
    private static final String CACHE_PAGER_INPUT = "ViewPager2_userInputEnabled";

    private static Field userInputField;
    private static boolean userInputFieldTried;

    /**
     * ViewPager2's {@code mUserInputEnabled}. Its inner RecyclerView reads it in
     * onInterceptTouchEvent, so DexKit gives the exact boolean field that method reads.
     * Needs a live pager (the inner RecyclerView class is taken from its children).
     */
    public static synchronized Field field(View pager) {
        if (userInputFieldTried) return userInputField;
        userInputFieldTried = true;
        try {
            Class<?> vp = viewPager2Class(pager);
            if (vp == null) return null;
            try {
                Field f = vp.getDeclaredField("mUserInputEnabled"); // unminified builds
                f.setAccessible(true);
                return userInputField = f;
            } catch (NoSuchFieldException ignored) {}
            String cached = DexKitCache.isCacheValid() ? DexKitCache.loadString(CACHE_PAGER_INPUT) : null;
            if (cached != null) {
                try {
                    Field f = vp.getDeclaredField(cached);
                    f.setAccessible(true);
                    return userInputField = f;
                } catch (NoSuchFieldException ignored) {} // stale cache: rediscover below
            }
            Class<?> inner = null;
            if (pager instanceof ViewGroup g) {
                for (int i = 0; i < g.getChildCount() && inner == null; i++) {
                    Class<?> c = g.getChildAt(i).getClass();
                    for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(c)) if (f.getType() == vp) { inner = c; break; }
                }
            }
            org.luckypray.dexkit.DexKitBridge bridge = ps.reso.instaeclipse.Xposed.Module.dexKitBridge;
            if (inner == null || bridge == null) return null;
            for (org.luckypray.dexkit.result.MethodData md : DexKitQueryCache.findMethod(bridge, org.luckypray.dexkit.query.FindMethod.create()
                    .matcher(org.luckypray.dexkit.query.matchers.MethodMatcher.create()
                            .declaredClass(inner.getName()).name("onInterceptTouchEvent")))) {
                for (org.luckypray.dexkit.result.UsingFieldData uf : md.getUsingFields()) {
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

    private static Class<?> viewPager2Class(View pager) {
        Class<?> vp = pager == null ? null : pager.getClass();
        while (vp != null && !vp.getName().equals(VIEWPAGER2)) vp = vp.getSuperclass();
        return vp;
    }
}
