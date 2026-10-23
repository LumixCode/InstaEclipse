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

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.JitGuard;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Opens links externally at IG's in-app-browser launcher. On IG 446 it can host the browser as a
 * fragment (sheet, overlay, side panel, back stack) that never starts an activity, so
 * ExternalBrowserHook alone would miss those links.
 */
public final class OpenLinksExternallyHook {
    private static final String CACHE_KEY = "OpenLinks_launcher_v1";

    public void install(DexKitBridge bridge, ClassLoader loader) {
        int installed = 0;
        try {
            for (Method method : resolveLaunchers(bridge, loader)) {
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
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        JitGuard.tick(param.method);
                        if (!FeatureFlags.openLinksExternally) return;
                        try {
                            Object launcher = index < 0 ? param.thisObject : param.args[index];
                            if (launcher == null) return;
                            String target = ExternalLinkPolicy.browserUrl((String) url.get(launcher));
                            Context ctx = (Context) context.get(launcher);
                            if (target == null || ctx == null) return;
                            ctx.startActivity(browserIntent(ctx, target));
                            // Suppress the in-app browser only after Android accepted the launch.
                            param.setResult(true);
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
        // ExternalBrowserHook shares this status key and still covers activity launches, so a
        // miss here must not mark the feature broken over its working hook.
        if (installed > 0) FeatureStatusTracker.setHooked("OpenLinksExternally");
        else if (FeatureFlags.openLinksExternally) {
            ModuleLog.warn("(IE|ExternalLinks) Some links may still open inside Instagram; links that open a new screen still go to your browser");
            ModuleLog.detail("(IE|ExternalLinks) No in-app-browser launcher found; activity hook only");
        }
    }

    private static List<Method> resolveLaunchers(DexKitBridge bridge, ClassLoader loader) throws NoSuchMethodException {
        List<Method> cached = DexKitCache.isCacheValid() ? DexKitCache.loadMethods(CACHE_KEY, loader) : null;
        if (cached != null) return cached;
        List<Method> methods = new ArrayList<>();
        // IG 451 moved "TrackingInfo.ARG_MODULE_NAME" into a shared string-pool class; the launcher
        // (446 X.0Pi6.A0G, 451 X.0kfN.A0G) still uses both of these itself.
        for (var data : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                .usingStrings("Tracking.ARG_CLICK_SOURCE", "in_app_browser_v2")
                .returnType("boolean")))) {
            methods.add(data.getMethodInstance(loader));
        }
        DexKitCache.saveMethods(CACHE_KEY, methods);
        return methods;
    }

    static Intent browserIntent(Context context, String url) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        // Resolve a generic web URL to a browser, while delivering the actual URL as intent data.
        // This selector is never loaded; it avoids routing a verified app link back into Instagram
        // and works without changing Instagram's package-visibility manifest.
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
