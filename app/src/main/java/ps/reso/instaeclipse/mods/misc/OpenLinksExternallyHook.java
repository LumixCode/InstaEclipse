package ps.reso.instaeclipse.mods.misc;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public final class OpenLinksExternallyHook {
    private static final String CACHE_KEY = "OpenLinks_launcher_v1";

    public void install(ClassLoader loader) {
        int installed = 0;
        try {
            for (Method method : resolveLaunchers(loader)) {
                Class<?> owner = method.getDeclaringClass();
                Field url = uniqueFinalField(owner, String.class);
                Field context = uniqueFinalField(owner, Context.class);
                if (url == null || context == null) continue;
                int ownerIndex = -1;
                if (Modifier.isStatic(method.getModifiers())) {
                    Class<?>[] types = method.getParameterTypes();
                    for (int i = 0; i < types.length; i++) if (types[i] == owner) ownerIndex = i;
                    if (ownerIndex < 0) continue;
                }
                final int index = ownerIndex;
                final Object opened = openedResult(method.getReturnType());
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (!FeatureFlags.openLinksExternally) return;
                        try {
                            Object launcher = index < 0 ? param.thisObject : param.args[index];
                            if (launcher == null) return;
                            String target = ExternalLinkPolicy.browserUrl((String) url.get(launcher));
                            Context ctx = (Context) context.get(launcher);
                            if (target == null || ctx == null) return;
                            ctx.startActivity(browserIntent(ctx, target));
                            param.setResult(opened);
                        } catch (Throwable t) {
                            ModuleLog.warn("(IE|ExternalLinks) Could not open the link in your browser, so it opens in Instagram: "
                                    + t.getClass().getSimpleName());
                        }
                    }
                });
                installed++;
                ModuleLog.detail("(IE|ExternalLinks) resolved " + owner.getName() + "." + method.getName());
            }
        } catch (Throwable t) {
            ModuleLog.warn("(IE|ExternalLinks) Some links may still open inside Instagram; links that open a new screen still go to your browser", t);
        }
        if (installed > 0) FeatureStatusTracker.setHooked("OpenLinksExternally");
        else if (FeatureFlags.openLinksExternally) {
            ModuleLog.warn("(IE|ExternalLinks) Some links may still open inside Instagram; links that open a new screen still go to your browser");
            ModuleLog.detail("(IE|ExternalLinks) No in-app-browser launcher found; activity hook only");
        }
    }

    private static List<Method> resolveLaunchers(ClassLoader loader) throws NoSuchMethodException {
        List<Method> cached = DexKitCache.isCacheValid() ? DexKitCache.loadMethods(CACHE_KEY, loader) : null;
        if (cached != null) return cached;
        List<Method> methods = new ArrayList<>();
        for (var data : DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                .usingStrings("Tracking.ARG_CLICK_SOURCE", "in_app_browser_v2")))) {
            if (!data.isMethod()) continue;
            Method method = data.getMethodInstance(loader);
            if (openedResult(method.getReturnType()) != null) methods.add(method);
        }
        DexKitCache.saveMethods(CACHE_KEY, methods);
        return methods;
    }

    private static Object openedResult(Class<?> type) {
        if (type == boolean.class) return true;
        Object[] constants = type.getEnumConstants();
        if (constants == null) return null;
        for (Object constant : constants) {
            if ("EXTERNAL_BROWSER".equals(((Enum<?>) constant).name())) return constant;
        }
        return null;
    }

    static Intent browserIntent(Context context, String url) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        Intent selector = new Intent(Intent.ACTION_VIEW, Uri.parse("https://instaeclipse.invalid/"));
        selector.addCategory(Intent.CATEGORY_BROWSABLE);
        intent.setSelector(selector);
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    private static Field uniqueFinalField(Class<?> owner, Class<?> type) {
        Field result = null;
        for (Field field : owner.getDeclaredFields()) {
            int flags = field.getModifiers();
            if (field.getType() != type || Modifier.isStatic(flags) || !Modifier.isFinal(flags)) continue;
            if (result != null) return null;
            field.setAccessible(true);
            result = field;
        }
        return result;
    }
}
