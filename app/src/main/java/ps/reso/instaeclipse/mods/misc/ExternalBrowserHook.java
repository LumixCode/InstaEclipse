package ps.reso.instaeclipse.mods.misc;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Open links in the external browser instead of Instagram's in-app browser (#229).
 *
 * IG opens web links with an explicit Intent to its in-app browser activity
 * (com.instagram.inappbrowser.fragments.BrowserLiteInMainProcessIGActivity on 451, or
 * com.facebook.browser.lite.BrowserLiteActivity) carrying the URL as the Intent data. Every in-process
 * activity start goes through android.app.Instrumentation#execStartActivity, so we hook that
 * (framework API — no IG class names or DexKit needed). For an in-app-browser Intent whose URL
 * ExternalLinkPolicy sends out, a browser-only Intent is started first and IG's launch is dropped
 * only once Android accepted ours, so a device without a browser keeps the in-app browser.
 * The fragment-hosted in-app browser never starts an activity; OpenLinksExternallyHook covers it.
 * Gated on FeatureFlags.openLinksExternally.
 */
public class ExternalBrowserHook {

    public void install() {
        try {
            int n = XposedBridge.hookAllMethods(Instrumentation.class, "execStartActivity", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    if (!FeatureFlags.openLinksExternally) return;
                    for (Object a : param.args) {
                        if (a instanceof Intent intent) {
                            redirect(param, intent);
                            return;
                        }
                    }
                }
            }).size();
            FeatureStatusTracker.setHooked("OpenLinksExternally");
            ModuleLog.line("(IE|ExtBrowser) ✅ hooked execStartActivity x" + n);
        } catch (Throwable t) {
            ModuleLog.line("(IE|ExtBrowser) ❌ install: " + t);
        }
    }

    private static void redirect(XC_MethodHook.MethodHookParam param, Intent intent) {
        try {
            ComponentName cn = intent.getComponent();
            if (cn == null || !isInAppBrowser(cn.getClassName())) return;
            Uri data = intent.getData();
            String target = ExternalLinkPolicy.browserUrl(data == null ? null : data.toString());
            if (target == null) return;
            if (param.args.length == 0 || !(param.args[0] instanceof Context who)) return;
            // The browser Intent has no component, so its own pass through this hook is ignored.
            who.startActivity(OpenLinksExternallyHook.browserIntent(who, target));
            // execStartActivity returns null when no ActivityMonitor intercepts the start.
            param.setResult(null);
            ModuleLog.probe("(IE|ExtBrowser) opening externally: " + Uri.parse(target).getHost());
        } catch (Throwable t) {
            ModuleLog.line("(IE|ExtBrowser) browser launch unavailable: " + t.getClass().getSimpleName());
        }
    }

    private static boolean isInAppBrowser(String cls) {
        return cls.startsWith("com.instagram.inappbrowser.")
                || cls.startsWith("com.facebook.browser.lite.");
    }
}
