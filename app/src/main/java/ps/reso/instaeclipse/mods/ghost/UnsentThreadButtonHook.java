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
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.ghost.CurrentThreadTracker;
import ps.reso.instaeclipse.utils.ghost.UnsentIdentity;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Puts the open chat's actions into the shared module menu in the DM thread header
 * (ThreadHeaderMenu): "Unsent messages" (this chat's UnsentLog entries, so they survive refresh
 * and restart), plus the Export and Jump-to-first entries, which have no header hook of their own.
 * The header is a classic Android View action bar, so entries are added from a one-shot
 * global-layout listener (a thread opened as a fragment fires no activity onResume).
 * Retention and the Unsent entry are separate preferences; Export does not need either.
 */
public class UnsentThreadButtonHook {

    /** Resolves the thread whose header carries the module menu (set when the menu is filled). */
    static volatile Supplier<String> openThread = CurrentThreadTracker::currentThreadId;

    private static final java.util.Set<View> pendingDecors =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private static int threadHeaderId, tagKeyId;

    public void install(ClassLoader classLoader) {
        // InstagramMainActivity declares no onResume on 446+ (IgFragmentActivity does), so a plain
        // findAndHookMethod throws there; LifecycleHookUtil hooks the declaring class instead.
        for (String act : new String[]{"com.instagram.modal.ModalActivity",
                "com.instagram.mainactivity.InstagramMainActivity"}) {
            try {
                LifecycleHookUtil.hookAfter(classLoader, act, "onResume", (activity, param) -> {
                    if (!enabled()) return;
                    activity.runOnUiThread(() -> registerListener(activity));
                });
            } catch (Throwable t) {
                ModuleLog.line("(IE|UnsentBtn) ⚠️ hook " + act + ": " + t);
            }
        }
        // Thread opened as a fragment inside the main activity fires no activity onResume.
        CurrentThreadTracker.install(classLoader);
        CurrentThreadTracker.addListener((activity, threadId) -> {
            if (enabled()) registerListener(activity);
        });
        // This hook fills the header menu, so it also owns the gesture that opens a hidden one.
        ThreadHeaderMenu.installTapGesture(classLoader);
        ModuleLog.line("(IE|UnsentBtn) ✅ installed");
    }

    /** Export is injected only from here, so it must not depend on the unsent preferences. */
    private static boolean enabled() {
        return FeatureFlags.chatExport || FeatureFlags.jumpToFirstMessage
                || (FeatureFlags.keepUnsentMessages && FeatureFlags.showUnsentButton);
    }

    @SuppressLint("DiscouragedApi")
    private void ensureIds(Activity a) {
        if (threadHeaderId != 0) return;
        String pkg = a.getPackageName();
        android.content.res.Resources r = a.getResources();
        threadHeaderId = r.getIdentifier("direct_thread_header", "id", pkg);
        tagKeyId       = r.getIdentifier("direct_thread_view_layout_tag_key", "id", pkg);
    }

    /** Best-effort chat name for the viewer title — prefer the @username (thread subtitle),
     *  fall back to the display-name title. */
    private String threadTitle(View header) {
        // The thread_title/thread_subtitle ids resolve but findViewById returns null here, so scan
        // the known-present header subtree for its TextViews and pick the @username (a handle-like
        // token with no spaces), falling back to the first visible name, else null.
        try {
            if (!(header instanceof ViewGroup)) return null;
            java.util.List<String> texts = new java.util.ArrayList<>();
            collectTexts((ViewGroup) header, texts);
            for (String t : texts) if (t.matches("[a-zA-Z0-9._]{2,30}")) return t; // handle-like
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
            // One pending listener per window: registerListener runs on every activity resume and
            // every thread-fragment resume, and listeners used to pile up on the main activity.
            if (!pendingDecors.add(decor)) return;
            decor.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override public void onGlobalLayout() {
                    if (tryInject(activity)) {
                        pendingDecors.remove(decor);
                        try { decor.getViewTreeObserver().removeOnGlobalLayoutListener(this); } catch (Throwable ignored) {}
                    }
                }
            });
        } catch (Throwable t) {
            ModuleLog.line("(IE|UnsentBtn) ⚠️ register: " + t.getMessage());
        }
    }

    /** Returns true once the chat header is on screen and its entries are in place (the listener detaches). */
    private boolean tryInject(Activity activity) {
        try {
            // Only inside a DM thread: the thread header must be on screen (not a hidden cached chat).
            final View header = ThreadHeaderMenu.findShown(activity.getWindow().getDecorView(), threadHeaderId);
            if (header == null) return false;

            // Capture the thread id AT INJECTION (thread just opened, before any refresh churn),
            // bound to THIS header — a stable per-thread fallback if the live read fails.
            final String bound = CurrentThreadTracker.currentThreadId();
            // Only sources tied to THIS open chat: the resumed thread fragment, this header's own
            // tags, the id bound when this header was filled, and the launching intent. No scan of
            // the whole activity graph and no "last opened thread" global: in the main activity
            // those return another chat (inbox rows, the previous chat) and leaked its unsent
            // messages and exports into this one. Unknown stays null; callers never widen it.
            // Weak: the supplier is also kept in the static openThread, which must not pin a closed chat.
            final java.lang.ref.WeakReference<View> headerRef = new java.lang.ref.WeakReference<>(header);
            final java.lang.ref.WeakReference<Activity> activityRef = new java.lang.ref.WeakReference<>(activity);
            final Supplier<String> thread = () -> {
                String threadId = CurrentThreadTracker.currentThreadId();
                View h = headerRef.get();
                if (threadId == null && h != null) threadId = resolveThreadId(h);
                if (threadId == null) threadId = bound;
                Activity act = activityRef.get();
                if (threadId == null && act != null) threadId = resolveFromIntent(act);
                return threadId;
            };
            // Shared with the other header entries (chat export) so they act on the same thread.
            openThread = thread;

            if (FeatureFlags.keepUnsentMessages && FeatureFlags.showUnsentButton
                    && !ThreadHeaderMenu.has(activity, "unsent")) {
                if (!ThreadHeaderMenu.add(activity, "unsent",
                        ps.reso.instaeclipse.utils.i18n.I18n.t(activity, ps.reso.instaeclipse.R.string.ig_unsent_messages), () -> {
                    String threadId = thread.get();
                    ModuleLog.line("(IE|UnsentBtn) open resolved=" + (threadId != null));
                    // Back from the list returns to this chat's menu, not the module's main menu.
                    ps.reso.instaeclipse.utils.dialog.DialogUtils.showThreadUnsent(activity, threadId, threadTitle(header),
                            () -> ThreadHeaderMenu.reopen(activity));
                })) return false;
                ModuleLog.line("(IE|UnsentBtn) ✅ added to the thread menu");
            }
            if (FeatureFlags.jumpToFirstMessage) JumpToFirstMessageHook.inject(activity);
            ChatExportHook.inject(activity);
            return true;
        } catch (Throwable t) {
            ModuleLog.line("(IE|UnsentBtn) ⚠️ inject: " + t.getMessage());
            return false;
        }
    }

    /** The thread key the chat's own activity was launched with (a chat opened in ModalActivity,
     *  e.g. from a notification or link). The main activity's intent never carries one. */
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

    /** Find a DirectThreadKey instance in obj's field graph (depth-limited, package-pruned). */
    private Object scanForDirectThreadKey(Object obj, int depth, java.util.Set<Object> seen, int maxDepth) {
        if (obj == null || depth > maxDepth || !seen.add(obj)) return null;
        String cn = obj.getClass().getName();
        if (cn.contains("DirectThreadKey")) return obj;
        // Only descend through IG/obfuscated objects and android Bundles; prune framework/JDK.
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

    /** PROBE: walk the header + ancestors, read the thread tag key and any DirectThreadKey, log all. */
    private String resolveThreadId(View header) {
        String found = null;
        View v = header;
        int up = 0;
        while (v != null && up++ < 8) {
            try {
                if (tagKeyId != 0) {
                    Object t = v.getTag(tagKeyId);
                    if (t != null) {
                        ModuleLog.probe("(IE|UnsentBtn|PROBE) tagKey on " + v.getClass().getSimpleName()
                                + " = " + t.getClass().getName());
                        String id = threadIdFromAny(t);
                        if (id != null && found == null) found = id;
                    }
                }
                Object plain = v.getTag();
                if (plain != null) {
                    String id = threadIdFromAny(plain);
                    if (id != null && found == null) found = id;
                }
            } catch (Throwable ignored) {}
            v = (v.getParent() instanceof View) ? (View) v.getParent() : null;
        }
        return found;
    }

    /** If obj is (or contains) a DirectThreadKey, use the same canonical id as the retained log. */
    private String threadIdFromAny(Object obj) {
        if (obj == null) return null;
        String cn = obj.getClass().getName();
        if (cn.contains("DirectThreadKey")) return UnsentIdentity.thread(obj);
        // shallow: some tags wrap the key
        try {
            for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(obj.getClass())) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (f.getType().getName().contains("DirectThreadKey")) {
                    f.setAccessible(true);
                    Object k = f.get(obj);
                    if (k != null) return UnsentIdentity.thread(k);
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
