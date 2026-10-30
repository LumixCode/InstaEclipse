package ps.reso.instaeclipse.mods.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.Found;

public class HideNotesTrayHook {

    private static final String TAG = "(InstaEclipse | NotesTray): ";

    public void install() {
        try {
            int n = 0;
            for (Found md : DexKitQueryCache.hookable(DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                    .usingStrings("NotesTrayComposeItem.<init>.<anonymous> (NotesTrayComposeItem.kt"))),
                    ps.reso.instaeclipse.Xposed.Module.hostClassLoader)) {
                if (md.isConstructor() || md.isStaticInitializer()) continue;
                java.lang.reflect.Method m = md.getMethodInstance(ps.reso.instaeclipse.Xposed.Module.hostClassLoader);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (FeatureFlags.hideNotesTray) param.setResult(null);
                    }
                });
                n++;
                ModuleLog.detail(TAG + "compose tray item hooked: " + md.getClassName() + "." + md.getName());
            }
            if (n > 0) FeatureStatusTracker.setHooked("HideNotesTray");
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "The notes tray may still show in the inbox: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        try {
            Member ctor = null;
            Method factory = null;
            for (Found md : DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                    .usingStrings("NotesTrayAdapter")))) {
                if (md.isMethod()) {
                    Method m = md.getMethodInstance(ps.reso.instaeclipse.Xposed.Module.hostClassLoader);
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 2 && p[0] == LayoutInflater.class && p[1] == ViewGroup.class) factory = m;
                    continue;
                }
                if (!md.isConstructor()) continue;
                Constructor<?> c = md.getConstructorInstance(ps.reso.instaeclipse.Xposed.Module.hostClassLoader);
                for (Class<?> p : c.getParameterTypes()) {
                    if (p == View.class) { ctor = c; break; }
                }
                if (ctor != null) break;
            }
            if (ctor == null && factory != null) {
                XposedBridge.hookMethod(factory, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!FeatureFlags.hideNotesTray) return;
                        View root = holderRoot(param.getResult());
                        if (root != null) keepCollapsed(root);
                    }
                });
                ModuleLog.ok(TAG + "ready");
                ModuleLog.detail(TAG + "hooked " + factory.getDeclaringClass().getName() + "." + factory.getName());
                FeatureStatusTracker.setHooked("HideNotesTray");
                return;
            }
            if (ctor == null) {
                ModuleLog.warn(TAG + "The notes tray may still show in the inbox (tray list item not found in this Instagram version)");
                return;
            }
            XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!FeatureFlags.hideNotesTray) return;
                    for (Object a : param.args) {
                        if (a instanceof View v && !(a instanceof android.view.LayoutInflater)) {
                            keepCollapsed(v);
                            break;
                        }
                    }
                }
            });
            ModuleLog.ok(TAG + "ready");
            ModuleLog.detail(TAG + "hooked " + ctor.getDeclaringClass().getName() + ".<init>");
            FeatureStatusTracker.setHooked("HideNotesTray");
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "The notes tray may still show in the inbox: setup failed", t);
        }
    }

    private static View holderRoot(Object holder) throws IllegalAccessException {
        Field root = null;
        for (Class<?> c = holder == null ? null : holder.getClass(); c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                int flags = f.getModifiers();
                if (f.getType() == View.class && Modifier.isFinal(flags) && !Modifier.isStatic(flags)) root = f;
            }
        }
        if (root == null) return null;
        root.setAccessible(true);
        return (View) root.get(holder);
    }

    private static void keepCollapsed(View v) {
        if (v == null) return;
        collapse(v);
        v.addOnLayoutChangeListener((w, l, t, r, b, ol, ot, or, ob) -> {
            if (FeatureFlags.hideNotesTray && (w.getVisibility() != View.GONE || b - t > 0)) w.post(() -> collapse(w));
        });
    }

    private static void collapse(View v) {
        v.setVisibility(View.GONE);
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp != null && (lp.height != 0 || lp.width != 0)) {
            lp.height = 0;
            v.setLayoutParams(lp);
        }
    }
}
