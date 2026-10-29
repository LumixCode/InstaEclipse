package ps.reso.instaeclipse.mods.ads;

import android.content.ClipData;
import android.content.Intent;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;

public class TrackingLinkDisable {

    public void disableTrackingLinks(ClassLoader classLoader) throws Throwable {
        FeatureStatusTracker.setHooked("DisableTrackingLinks");

        Class<?> clipboardManagerClass = XposedHelpers.findClass("android.content.ClipboardManager", classLoader);
        XposedHelpers.findAndHookMethod(clipboardManagerClass, "setPrimaryClip",
                Class.forName("android.content.ClipData"), new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!active()) return;
                        ClipData clipData = (ClipData) param.args[0];
                        if (clipData == null || clipData.getItemCount() == 0) return;
                        ClipData.Item item = clipData.getItemAt(0);
                        if (item == null || item.getText() == null) return;
                        ps.reso.instaeclipse.utils.log.ModuleLog.probe("(IE|Track) setPrimaryClip intercepted");
                        String cleaned = rewrite(item.getText().toString());
                        if (cleaned != null) param.args[0] = ClipData.newPlainText("URL", cleaned);
                    }
                });

        XC_MethodHook putExtraHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!active()) return;
                if (!(param.args[0] instanceof String) || param.args[1] == null) return;
                if (!Intent.EXTRA_TEXT.equals(param.args[0])) return;
                ps.reso.instaeclipse.utils.log.ModuleLog.probe("(IE|Track) EXTRA_TEXT intercepted");
                String cleaned = rewrite(param.args[1].toString());
                if (cleaned != null) param.args[1] = cleaned;
            }
        };
        try {
            XposedHelpers.findAndHookMethod(Intent.class, "putExtra", String.class, String.class, putExtraHook);
        } catch (Throwable ignored) {}
        try {
            XposedHelpers.findAndHookMethod(Intent.class, "putExtra", String.class, CharSequence.class, putExtraHook);
        } catch (Throwable ignored) {}

        XC_MethodHook chooserHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!active()) return;
                if (param.args.length == 0 || !(param.args[0] instanceof Intent target)) return;
                sanitizeIntentText(target);
            }
        };
        try {
            XposedHelpers.findAndHookMethod(Intent.class, "createChooser",
                    Intent.class, CharSequence.class, chooserHook);
        } catch (Throwable ignored) {}
        try {
            XposedHelpers.findAndHookMethod(Intent.class, "createChooser",
                    Intent.class, CharSequence.class, android.content.IntentSender.class, chooserHook);
        } catch (Throwable ignored) {}
    }

    private static void sanitizeIntentText(Intent target) {
        try {
            CharSequence text = target.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (text != null) {
                ps.reso.instaeclipse.utils.log.ModuleLog.probe("(IE|Track) chooser EXTRA_TEXT intercepted");
                String cleaned = rewrite(text.toString());
                if (cleaned != null) target.putExtra(Intent.EXTRA_TEXT, cleaned);
            }
            android.content.ClipData cd = target.getClipData();
            if (cd != null && cd.getItemCount() > 0 && cd.getItemAt(0).getText() != null) {
                String cleaned = rewrite(cd.getItemAt(0).getText().toString());
                if (cleaned != null) target.setClipData(android.content.ClipData.newPlainText("URL", cleaned));
            }
        } catch (Throwable ignored) {}
    }

    static boolean active() {
        String domain = FeatureFlags.customShareDomain;
        return FeatureFlags.disableTrackingLinks || (domain != null && !domain.isEmpty());
    }

    static String rewrite(String text) {
        if (text == null) return null;
        String out = text;
        if (FeatureFlags.disableTrackingLinks) {
            String stripped = TrackingParamStripper.strip(out);
            if (stripped != null) out = stripped;
        }
        out = ShareDomain.apply(out, FeatureFlags.customShareDomain);
        return out.equals(text) ? null : out;
    }
}
