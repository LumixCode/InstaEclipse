package ps.reso.instaeclipse.mods.extras;

import java.lang.reflect.Method;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.Found;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public class AccountLimitHook {

    public void install(ClassLoader cl) {
        Method check = ExtrasLookup.method("Extras_AccountLimit", "unlimitedAccounts", FeatureFlags.unlimitedAccounts, cl, () -> {
            for (Found owner : ExtrasLookup.byStrings("account_switcher_max_limit_reached")) {
                Method m = ExtrasLookup.firstStaticInvoke(owner, cl, c -> c.getReturnType() == boolean.class
                        && c.getParameterCount() == 2 && c.getParameterTypes()[1] == int.class);
                if (m != null) return m;
            }
            return null;
        });
        if (check == null) return;
        XposedBridge.hookMethod(check, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (FeatureFlags.unlimitedAccounts) param.setResult(true);
            }
        });
        FeatureStatusTracker.setHooked("unlimitedAccounts");
        ModuleLog.ok("(InstaEclipse | UnlimitedAccounts): ready");
        ModuleLog.detail("(InstaEclipse | UnlimitedAccounts): hooked " + check.getDeclaringClass().getName() + "." + check.getName());
    }
}
