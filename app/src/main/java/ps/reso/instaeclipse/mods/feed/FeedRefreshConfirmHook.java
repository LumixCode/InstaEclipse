package ps.reso.instaeclipse.mods.feed;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.view.View;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.dialog.IeDialog;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public final class FeedRefreshConfirmHook {

    private static final String TAG = "(IE|RefreshConfirm) ";
    private static final String CONTAINER = "com.instagram.ui.widget.refresh.RefreshableNestedScrollingParent";

    private static int trayId = -1;

    private FeedRefreshConfirmHook() {}

    public static void install(ClassLoader cl) {
        try {
            Class<?> container = cl.loadClass(CONTAINER);
            Method set = null;
            for (Method m : ps.reso.instaeclipse.utils.core.SafeReflect.methods(container)) {
                if (m.getName().equals("setListener") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].isInterface() && ps.reso.instaeclipse.utils.core.SafeReflect.methods(m.getParameterTypes()[0]).length == 1) {
                    set = m;
                }
            }
            if (set == null) {
                ModuleLog.error(TAG + "Will not work: the pull-to-refresh code was not found in this Instagram version");
                ModuleLog.detail(TAG + "setListener not found");
                return;
            }
            final Method setRefreshing = container.getMethod("setRefreshing", boolean.class);
            final Class<?> type = set.getParameterTypes()[0];
            java.lang.reflect.Field field = null;
            for (java.lang.reflect.Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(container)) if (f.getType() == type) field = f;
            if (field == null) {
                ModuleLog.error(TAG + "Will not work: the pull-to-refresh code was not found in this Instagram version");
                ModuleLog.detail(TAG + "listener field not found");
                return;
            }
            field.setAccessible(true);
            final java.lang.reflect.Field listenerField = field;
            XposedBridge.hookMethod(container.getMethod("onNestedScrollAccepted", View.class, View.class, int.class), new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        View view = (View) param.thisObject;
                        Object listener = listenerField.get(view);
                        if (listener == null || Proxy.isProxyClass(listener.getClass())) return;
                        listenerField.set(view, wrap(type, listener, view, setRefreshing));
                    } catch (Throwable ignored) {}
                }
            });
            FeatureStatusTracker.setHooked("ConfirmFeedRefresh");
            ModuleLog.ok(TAG + "ready");
            ModuleLog.detail(TAG + "hooked " + CONTAINER);
        } catch (Throwable t) {
            ModuleLog.error(TAG + "Will not work: setup failed", t);
        }
    }

    private static Object wrap(Class<?> type, Object listener, View view, Method setRefreshing) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                if (method.getName().equals("equals")) return proxy == args[0];
                if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                return "IeRefreshConfirm";
            }
            if (FeatureFlags.confirmFeedRefresh && isHomeFeed(view)) {
                ask(view, () -> invoke(method, listener, args), () -> {
                    try { setRefreshing.invoke(view, false); } catch (Throwable ignored) {}
                });
                return null;
            }
            return invoke(method, listener, args);
        });
    }

    private static Object invoke(Method m, Object target, Object[] args) {
        try {
            return m.invoke(target, args);
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "Pull to refresh failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return null;
        }
    }

    @SuppressLint("DiscouragedApi")
    private static boolean isHomeFeed(View container) {
        if (trayId == -1) {
            trayId = container.getResources().getIdentifier("reels_tray_container", "id", container.getContext().getPackageName());
        }
        return trayId != 0 && container.findViewById(trayId) != null;
    }

    private static void ask(View view, Runnable refresh, Runnable cancel) {
        Context ctx = view.getContext();
        for (Context c = ctx; c instanceof android.content.ContextWrapper w; c = w.getBaseContext()) {
            if (c instanceof Activity) { ctx = c; break; }
        }
        try {
            final boolean[] refreshed = {false};
            IeDialog dlg = new IeDialog(ctx).title(I18n.t(ctx, R.string.ig_confirm_refresh_question))
                    .onDismiss(() -> { if (!refreshed[0]) cancel.run(); });
            dlg.secondary(ctx.getString(android.R.string.cancel), null);
            dlg.primary(I18n.t(ctx, R.string.ig_confirm_refresh_action), () -> {
                refreshed[0] = true;
                refresh.run();
            });
            dlg.show();
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "Could not show the confirmation, so the feed refreshed without asking: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            refresh.run();
        }
    }
}
