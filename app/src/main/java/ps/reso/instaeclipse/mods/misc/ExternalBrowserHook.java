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

public class ExternalBrowserHook {

    public void install() {
        try {
            int n = XposedBridge.hookAllMethods(Instrumentation.class, "execStartActivity", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
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
            ModuleLog.ok("(IE|ExtBrowser) Ready");
            ModuleLog.detail("(IE|ExtBrowser) hooked execStartActivity x" + n);
        } catch (Throwable t) {
            ModuleLog.error("(IE|ExtBrowser) Could not turn on opening links in your browser", t);
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
            who.startActivity(OpenLinksExternallyHook.browserIntent(who, target));
            param.setResult(null);
            ModuleLog.probe("(IE|ExtBrowser) opening externally: " + Uri.parse(target).getHost());
        } catch (Throwable t) {
            ModuleLog.warn("(IE|ExtBrowser) Could not open the link in your browser, so it opens in Instagram: " + t.getClass().getSimpleName());
        }
    }

    private static boolean isInAppBrowser(String cls) {
        return cls.startsWith("com.instagram.inappbrowser.")
                || cls.startsWith("com.facebook.browser.lite.");
    }
}
