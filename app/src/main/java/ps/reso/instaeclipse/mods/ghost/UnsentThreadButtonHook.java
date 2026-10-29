package ps.reso.instaeclipse.mods.ghost;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.function.Supplier;

import ps.reso.instaeclipse.mods.ui.utils.LifecycleHookUtil;
import ps.reso.instaeclipse.utils.core.LegacyPaths;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.ghost.CurrentThreadTracker;
import ps.reso.instaeclipse.utils.ghost.UnsentIdentity;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public class UnsentThreadButtonHook {

    static volatile Supplier<String> openThread = CurrentThreadTracker::currentThreadId;

    private static final java.util.Set<View> pendingDecors =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private static int threadHeaderId;

    public void install(ClassLoader classLoader) {
        for (String act : new String[]{"com.instagram.modal.ModalActivity",
                "com.instagram.mainactivity.InstagramMainActivity"}) {
            try {
                LifecycleHookUtil.hookAfter(classLoader, act, "onResume", (activity, param) -> {
                    if (!enabled()) return;
                    activity.runOnUiThread(() -> registerListener(activity));
                });
            } catch (Throwable t) {
                ModuleLog.detail("(IE|UnsentBtn) hook " + act + " failed (chat tracker still used): " + t);
            }
        }
        CurrentThreadTracker.install(classLoader);
        ThreadHeaderInjector.install(classLoader);
        ThreadHeaderInjector.register("unsent", false, (activity, threadId) -> !enabled() || unsentEntry(activity, threadId));
        ThreadHeaderInjector.register("export", false, (activity, threadId) -> {
            if (enabled()) ChatExportHook.inject(activity);
            return true;
        });
        ThreadHeaderInjector.register("jump", false, (activity, threadId) ->
                !enabled() || !FeatureFlags.jumpToFirstMessage || JumpToFirstMessageHook.inject(activity));
        CurrentThreadTracker.addListener((activity, threadId) -> {
            if (enabled()) registerListener(activity);
        });
        ThreadHeaderMenu.installTapGesture(classLoader);
        ModuleLog.ok("(IE|UnsentBtn) ready");
    }

    private static boolean enabled() {
        return FeatureFlags.chatExport || FeatureFlags.jumpToFirstMessage
                || (FeatureFlags.keepUnsentMessages && FeatureFlags.showUnsentButton);
    }

    @SuppressLint("DiscouragedApi")
    private void ensureIds(Activity a) {
        if (threadHeaderId != 0) return;
        threadHeaderId = a.getResources().getIdentifier("direct_thread_header", "id", a.getPackageName());
    }

    static String openChat(Activity activity) {
        return LegacyPaths.has(LegacyPaths.HEADER) ? CurrentThreadTracker.currentThreadId()
                : CurrentThreadTracker.currentThreadId(activity);
    }

    private String threadTitle(View header) {
        try {
            if (!(header instanceof ViewGroup)) return null;
            java.util.List<String> texts = new java.util.ArrayList<>();
            collectTexts((ViewGroup) header, texts);
            for (String t : texts) if (t.matches("[a-zA-Z0-9._]{2,30}")) return t;
            return texts.isEmpty() ? null : texts.get(0);
        } catch (Throwable t) { return null; }
    }

    private void collectTexts(ViewGroup vg, java.util.List<String> out) {
        for (int i = 0; i < vg.getChildCount() && out.size() < 12; i++) {
            View c = vg.getChildAt(i);
            if (c instanceof android.widget.TextView) {
                CharSequence cs = ((android.widget.TextView) c).getText();
                if (cs != null && cs.toString().trim().length() > 0) out.add(cs.toString().trim());
            } else if (c instanceof ViewGroup) {
                collectTexts((ViewGroup) c, out);
            }
        }
    }

    private void registerListener(final Activity activity) {
        try {
            ensureIds(activity);
            if (tryInject(activity)) return;
            final View decor = activity.getWindow().getDecorView();
            if (!pendingDecors.add(decor)) return;
            decor.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override public void onGlobalLayout() {
                    if (ThreadHeaderInjector.handled(decor, "unsent", "export", "jump") || tryInject(activity)) {
                        pendingDecors.remove(decor);
                        try { decor.getViewTreeObserver().removeOnGlobalLayoutListener(this); } catch (Throwable ignored) {}
                    }
                }
            });
        } catch (Throwable t) {
            ModuleLog.warn("(IE|UnsentBtn) Could not add the chat menu on this screen: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private boolean tryInject(Activity activity) {
        if (!unsentEntry(activity, null)) return false;
        try {
            if (FeatureFlags.jumpToFirstMessage) JumpToFirstMessageHook.inject(activity);
            ChatExportHook.inject(activity);
            return true;
        } catch (Throwable t) {
            ModuleLog.detail("(IE|UnsentBtn) inject failed: " + t.getMessage());
            return false;
        }
    }

    private boolean unsentEntry(Activity activity, String shownThread) {
        try {
            ensureIds(activity);
            final View header = ThreadHeaderMenu.findShown(activity.getWindow().getDecorView(), threadHeaderId);
            if (header == null) return false;

            final String bound = shownThread != null ? shownThread : openChat(activity);
            final java.lang.ref.WeakReference<Activity> activityRef = new java.lang.ref.WeakReference<>(activity);
            final Supplier<String> thread = () -> {
                Activity act = activityRef.get();
                String threadId = act != null ? openChat(act) : null;
                if (threadId == null) threadId = bound;
                if (threadId == null && act != null) threadId = resolveFromIntent(act);
                return threadId;
            };
            openThread = thread;

            if (FeatureFlags.keepUnsentMessages && FeatureFlags.showUnsentButton
                    && !ThreadHeaderMenu.has(activity, "unsent")) {
                if (!ThreadHeaderMenu.add(activity, "unsent",
                        ps.reso.instaeclipse.utils.i18n.I18n.t(activity, ps.reso.instaeclipse.R.string.ig_unsent_messages), () -> {
                    String threadId = thread.get();
                    ModuleLog.detail("(IE|UnsentBtn) open resolved=" + (threadId != null));
                    ps.reso.instaeclipse.utils.dialog.DialogUtils.showThreadUnsent(activity, threadId, threadTitle(header),
                            () -> ThreadHeaderMenu.reopen(activity));
                })) return false;
                ModuleLog.probe("(IE|UnsentBtn) added to the thread menu");
            }
            return true;
        } catch (Throwable t) {
            ModuleLog.detail("(IE|UnsentBtn) inject failed: " + t.getMessage());
            return false;
        }
    }

    private String resolveFromIntent(Activity activity) {
        try {
            android.os.Bundle ex = activity.getIntent() != null ? activity.getIntent().getExtras() : null;
            if (ex == null) return null;
            for (String k : ex.keySet()) {
                Object dtk = scanForDirectThreadKey(ex.get(k), 0,
                        java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()), 4);
                if (dtk != null) { String id = UnsentIdentity.thread(dtk); if (id != null) return id; }
            }
            return null;
        } catch (Throwable t) { return null; }
    }

    private Object scanForDirectThreadKey(Object obj, int depth, java.util.Set<Object> seen, int maxDepth) {
        if (obj == null || depth > maxDepth || !seen.add(obj)) return null;
        String cn = obj.getClass().getName();
        if (cn.contains("DirectThreadKey")) return obj;
        boolean descendable = cn.startsWith("X.") || cn.startsWith("com.instagram.")
                || obj instanceof android.os.Bundle;
        if (!descendable) return null;
        if (obj instanceof android.os.Bundle) {
            try {
                android.os.Bundle b = (android.os.Bundle) obj;
                for (String k : b.keySet()) {
                    Object r = scanForDirectThreadKey(b.get(k), depth + 1, seen, maxDepth);
                    if (r != null) return r;
                }
            } catch (Throwable ignored) {}
            return null;
        }
        try {
            for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(c)) {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                    f.setAccessible(true);
                    Object v;
                    try { v = f.get(obj); } catch (Throwable e) { continue; }
                    if (v == null) continue;
                    if (v.getClass().getName().contains("DirectThreadKey")) return v;
                    Object r = scanForDirectThreadKey(v, depth + 1, seen, maxDepth);
                    if (r != null) return r;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
