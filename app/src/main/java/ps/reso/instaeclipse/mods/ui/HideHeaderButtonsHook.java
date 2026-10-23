package ps.reso.instaeclipse.mods.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.JitGuard;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Hides two header buttons that have no view id of their own:
 *  - the Threads button in a profile's action bar (right_action_bar_buttons), and
 *  - the Blend button in a DM thread header (header_right_buttons).
 * They are matched by their content description ("…Threads…" / "Blend…": product names that
 * Instagram keeps untranslated), inside those containers only. A layout listener on each
 * activity's window re-hides them whenever Instagram rebuilds the header.
 *
 * Your own profile's Threads button also goes with "Hide Threads suggestions", and is stopped
 * before it is built: Instagram adds it (IG 451: model X.090q in X.0L2A.A15, and the older
 * action-bar path X.0AU8.Ae6 with id profile_header_barcelona_app_switcher_button) only when one
 * gate returns true, X.0AZB.A06(UserSession, boolean isSelf, boolean)Z. Anchor: the gate's class owns
 * the only 6-parameter boolean method that uses "com.instagram.barcelona" (X.0AZB.A02, the
 * "Threads installed" check), and the gate is that class's only instance method
 * (UserSession, boolean, boolean) -> boolean. Forcing it false leaves the other buttons alone
 * (create and the menu are added after it either way) and skips the badge tooltip with it.
 * Fallback for a build where the gate is missing or inlined: after the unobfuscated, final
 * com.instagram.profile.actionbar.ProfileActionBar binds its buttons (its only 6-parameter void
 * method, which empties both containers and adds a fresh ImageView per button), its "Threads"
 * child is hidden before it is drawn.
 */
public class HideHeaderButtonsHook {
    private static final String TAG = "(IE|HideButtons) ";
    // v1: X.0AZB.A06 on IG 451 (X.0EHO.A04 on 446).
    private static final String CACHE_GATE = "HideThreads_profileGate_v1";
    private static final String USER_SESSION = "com.instagram.common.session.UserSession";
    private static final String PROFILE_ACTION_BAR = "com.instagram.profile.actionbar.ProfileActionBar";
    private static final Set<View> watched = Collections.newSetFromMap(new WeakHashMap<>());
    private static int profileBarId, profileBarLeftId, legacyThreadsId, dmHeaderId;
    private static volatile boolean gateHooked, gateLogged, barLogged;

    public void install(DexKitBridge bridge, ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) { JitGuard.tick(param.method);
                    if (!scanThreads() && !FeatureFlags.hideDmBlend) return;
                    View decor = ((Activity) param.thisObject).getWindow().getDecorView();
                    if (!watched.add(decor)) return;
                    decor.getViewTreeObserver().addOnGlobalLayoutListener(() -> scan(decor));
                    scan(decor);
                }
            });
            FeatureStatusTracker.setHooked("HideThreadsButton");
            FeatureStatusTracker.setHooked("HideDmBlend");
            ModuleLog.ok(TAG + "ready");
        } catch (Throwable t) {
            ModuleLog.error(TAG + "Hiding the Threads and Blend buttons will not work: setup failed", t);
        }
        // Reflection only, so it is in place before the DexKit lookup of the gate.
        hookProfileActionBar(cl);
        hookThreadsGate(bridge, cl);
    }

    private static boolean hideOwnProfileThreads() {
        return ThreadsButtonPolicy.hideOwnProfileButton(FeatureFlags.hideThreadsButton, FeatureFlags.hideThreadsSuggestions);
    }

    private static boolean scanThreads() {
        return ThreadsButtonPolicy.scanWindow(FeatureFlags.hideThreadsButton, FeatureFlags.hideThreadsSuggestions, gateHooked);
    }

    private static void hookThreadsGate(DexKitBridge bridge, ClassLoader cl) {
        try {
            Method gate = DexKitCache.isCacheValid() ? DexKitCache.loadMethod(CACHE_GATE, cl) : null;
            if (gate == null) {
                gate = findThreadsGate(bridge, cl);
                if (gate == null) return;
                DexKitCache.saveMethod(CACHE_GATE, gate);
            }
            XposedBridge.hookMethod(gate, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) { JitGuard.tick(param.method);
                    if (!hideOwnProfileThreads()) return;
                    param.setResult(false);
                    if (!gateLogged && Boolean.TRUE.equals(param.args[1])) {
                        gateLogged = true;
                        ModuleLog.detail(TAG + "own-profile Threads button gate -> false");
                    }
                }
            });
            gateHooked = true;
            ModuleLog.detail(TAG + "Threads gate hooked: " + gate.getDeclaringClass().getName() + "." + gate.getName());
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "Using the backup way to hide the Threads button on your profile: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static Method findThreadsGate(DexKitBridge bridge, ClassLoader cl) {
        if (bridge == null) return null;
        List<MethodData> found = DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                .usingStrings("com.instagram.barcelona")
                .returnType("boolean")
                .paramCount(6)));
        if (found.size() != 1) {
            ModuleLog.warn(TAG + "Using the backup way to hide the Threads button on your profile (not found in this Instagram version)");
            ModuleLog.detail(TAG + "Threads gate anchor " + (found.isEmpty() ? "not found" : "ambiguous (" + found.size() + ")"));
            return null;
        }
        Class<?> owner;
        try {
            owner = found.get(0).getMethodInstance(cl).getDeclaringClass();
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "Using the backup way to hide the Threads button on your profile: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            return null;
        }
        Method gate = ThreadsButtonPolicy.gate(owner, USER_SESSION);
        if (gate == null) ModuleLog.warn(TAG + "Using the backup way to hide the Threads button on your profile (not found in this Instagram version)");
        if (gate == null) ModuleLog.detail(TAG + "no single (UserSession, boolean, boolean) gate in " + owner.getName());
        return gate;
    }

    private static void hookProfileActionBar(ClassLoader cl) {
        try {
            Method bind = ThreadsButtonPolicy.bindMethod(Class.forName(PROFILE_ACTION_BAR, false, cl));
            if (bind == null) {
                ModuleLog.detail(TAG + "ProfileActionBar bind method not found (backup for your own profile's Threads button)");
                return;
            }
            XposedBridge.hookMethod(bind, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) { JitGuard.tick(param.method);
                    if (!hideOwnProfileThreads() || !(param.thisObject instanceof View bar)) return;
                    try {
                        resolveIds(bar);
                        int hidden = (profileBarId != 0 ? hideIn(bar.findViewById(profileBarId), "Threads", false) : 0)
                                + (profileBarLeftId != 0 ? hideIn(bar.findViewById(profileBarLeftId), "Threads", false) : 0);
                        if (hidden > 0 && !barLogged) {
                            barLogged = true;
                            ModuleLog.detail(TAG + "hid the Threads button in ProfileActionBar (gate hooked: " + gateHooked + ")");
                        }
                    } catch (Throwable ignored) {}
                }
            });
            ModuleLog.detail(TAG + "ProfileActionBar fallback hooked: " + bind.getName());
        } catch (Throwable t) {
            ModuleLog.detail(TAG + "ProfileActionBar fallback unavailable: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    @SuppressLint("DiscouragedApi")
    private static void resolveIds(View v) {
        if (profileBarId != 0 && dmHeaderId != 0) return;
        String pkg = v.getContext().getPackageName();
        profileBarId = v.getResources().getIdentifier("right_action_bar_buttons", "id", pkg);
        profileBarLeftId = v.getResources().getIdentifier("left_action_bar_buttons", "id", pkg);
        legacyThreadsId = v.getResources().getIdentifier("profile_header_barcelona_app_switcher_button", "id", pkg);
        dmHeaderId = v.getResources().getIdentifier("header_right_buttons", "id", pkg);
    }

    private static void scan(View decor) {
        try {
            resolveIds(decor);
            if (scanThreads()) {
                if (profileBarId != 0) hideIn(decor.findViewById(profileBarId), "Threads", false);
                // The older action-bar path's button has an id, and IG sets it VISIBLE again later.
                View legacy = legacyThreadsId != 0 ? decor.findViewById(legacyThreadsId) : null;
                if (legacy != null && legacy.getVisibility() != View.GONE) legacy.setVisibility(View.GONE);
            }
            if (FeatureFlags.hideDmBlend && dmHeaderId != 0)
                hideIn(decor.findViewById(dmHeaderId), "Blend", true);
        } catch (Throwable ignored) {}
    }

    private static int hideIn(View container, String word, boolean prefix) {
        if (!(container instanceof ViewGroup g)) return 0;
        int hidden = 0;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            CharSequence d = c.getContentDescription();
            if (d == null || c.getVisibility() == View.GONE) continue;
            String s = d.toString();
            if (prefix ? s.startsWith(word) : s.contains(word)) {
                c.setVisibility(View.GONE);
                hidden++;
            }
        }
        return hidden;
    }
}
