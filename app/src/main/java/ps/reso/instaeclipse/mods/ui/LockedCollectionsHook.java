package ps.reso.instaeclipse.mods.ui;

import android.app.Activity;
import android.os.Bundle;
import android.os.Parcelable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.util.LinkedHashSet;
import java.util.Set;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.core.PasscodeCheck;
import ps.reso.instaeclipse.utils.core.SettingsManager;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.ui.ResIds;

public class LockedCollectionsHook {
    private static final String TAG = "(IE|LockedColl) ";
    private static final String BTN_TAG = "ie_lock_collection_btn";
    private static final String ARG_PREFIX = "SaveFragment.ARGUMENT_SAVED_";
    private static final PromptGuard<Activity> prompt = new PromptGuard<>(a -> !a.isFinishing() && !a.isDestroyed());
    private static java.lang.ref.WeakReference<View> active;
    private static String activeId;
    private static final Set<View> watchedBars = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private static final Set<View> queuedBars = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private static final java.util.Map<View, Object> hidden = new java.util.WeakHashMap<>();
    private static final Set<Object> unlockedFrags = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private static Field argumentsField;
    private static Field parentField;
    private static java.lang.reflect.Method viewMethod;

    public void install(ClassLoader cl) {
        try {
            Class<?> fragment = cl.loadClass("androidx.fragment.app.Fragment");
            argumentsField = fragmentField(fragment, "mArguments");
            parentField = fragmentField(fragment, "mParentFragment");
            viewMethod = fragment.getMethod("getView");
            XposedHelpers.findAndHookMethod(fragment, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!FeatureFlags.lockSavedCollections) return;
                    Bundle args = arguments(param.thisObject);
                    String id = args != null ? collectionId(args) : null;
                    View root;
                    try { root = (View) viewMethod.invoke(param.thisObject); } catch (Throwable t) { return; }
                    if (id == null) {
                        if (root != null && active != null) root.postDelayed(() -> removeIfLeft(activityOf(root)), 300);
                        return;
                    }
                    Activity a = activityOf(root);
                    if (a == null) return;
                    if (isLocked(id) && hasPasscode() && !isUnlocked(param.thisObject)) gate(a, param.thisObject, root);
                    active = new java.lang.ref.WeakReference<>(root);
                    activeId = id;
                    root.postDelayed(() -> addButton(a, id), 400);
                }
            });
            FeatureStatusTracker.setHooked("LockSavedCollections");
            ModuleLog.ok(TAG + "ready");
        } catch (Throwable t) {
            ModuleLog.error(TAG + "Will not work: setup failed", t);
        }
    }

    private static boolean isUnlocked(Object frag) {
        try {
            Field pf = parentField;
            for (Object f = frag; f != null; f = pf != null ? pf.get(f) : null) if (unlockedFrags.contains(f)) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    private static Bundle arguments(Object frag) {
        try {
            return argumentsField != null ? (Bundle) argumentsField.get(frag) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Field fragmentField(Class<?> fragment, String name) {
        try {
            Field f = fragment.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "Could not find Fragment." + name + ": " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return null;
        }
    }

    private static String collectionId(Bundle args) {
        try {
            boolean saved = false;
            for (String k : args.keySet()) if (k.startsWith(ARG_PREFIX)) { saved = true; break; }
            if (!saved) return null;
            Object id = args.get(ARG_PREFIX + "COLLECTION_ID");
            if (id instanceof String s && !s.isEmpty()) return s;
            for (String k : args.keySet()) {
                Object v = args.get(k);
                if (v instanceof Parcelable p && p.getClass().getName().endsWith("SavedCollection")) {
                    for (Class<?> c = p.getClass(); c != null && c != Object.class; c = c.getSuperclass())
                        for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(c)) {
                            if (f.getType() != String.class) continue;
                            f.setAccessible(true);
                            Object s = f.get(p);
                            if (s instanceof String str && !str.isEmpty() && str.matches("[0-9A-Z_]+")) return str;
                        }
                }
            }
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "Could not tell which collection is open; it may open without the lock: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        return null;
    }

    private static Set<String> locked() {
        Set<String> s = new LinkedHashSet<>();
        for (String id : FeatureFlags.lockedCollections.split(",")) if (!id.trim().isEmpty()) s.add(id.trim());
        return s;
    }

    private static boolean isLocked(String id) {
        return locked().contains(id);
    }

    private static boolean hasPasscode() {
        return FeatureFlags.lockDirectPasscode != null && !FeatureFlags.lockDirectPasscode.isEmpty();
    }

    private static void removeIfLeft(Activity a) {
        try {
            if (a == null) return;
            View r = active != null ? active.get() : null;
            if (r != null && r.isShown()) return;
            int barId = ResIds.id(a, "action_bar");
            View bar = barId != 0 ? a.findViewById(barId) : null;
            View btn = bar != null ? bar.findViewWithTag(BTN_TAG) : null;
            if (btn != null && btn.getParent() instanceof ViewGroup p) p.removeView(btn);
        } catch (Throwable ignored) {}
    }

    private static void addButton(Activity a, String id) {
        try {
            int barId = ResIds.id(a, "action_bar");
            View bar = barId != 0 ? a.findViewById(barId) : null;
            if (!(bar instanceof ViewGroup g)) return;
            if (watchedBars.add(g))
                g.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                    View r = active != null ? active.get() : null;
                    if (r == null || !r.isShown()) {
                        View stale = g.findViewWithTag(BTN_TAG);
                        if (stale != null && stale.getParent() instanceof ViewGroup p) p.post(() -> p.removeView(stale));
                        return;
                    }
                    if (!FeatureFlags.lockSavedCollections || activeId == null) return;
                    if (g.findViewWithTag(BTN_TAG) == null && queuedBars.add(g))
                        g.post(() -> {
                            queuedBars.remove(g);
                            addButton(a, activeId);
                        });
                });
            View old = g.findViewWithTag(BTN_TAG);
            if (old != null && old.getParent() instanceof ViewGroup p) p.removeView(old);
            ViewGroup host = g;
            View options = null;
            for (int i = g.getChildCount() - 1; i >= 0 && options == null; i--)
                if (g.getChildAt(i).isClickable() && g.getChildAt(i).getVisibility() == View.VISIBLE) options = g.getChildAt(i);
            boolean rtl = g.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            if (options == null || options.getWidth() == 0
                    || (rtl ? options.getRight() > g.getWidth() / 2 : options.getLeft() < g.getWidth() / 2)) return;
            android.widget.ImageView btn = new android.widget.ImageView(a);
            btn.setTag(BTN_TAG);
            btn.setScaleType(android.widget.ImageView.ScaleType.CENTER);
            ps.reso.instaeclipse.utils.ui.IgTint.copy(options, btn);
            showState(a, btn, isLocked(id));
            btn.setContentDescription(I18n.t(a, isLocked(id) ? R.string.ig_locked_coll_unlock : R.string.ig_locked_coll_lock));
            int w = options.getWidth() > 0 ? options.getWidth() : LockDirectMessagesHook.dp(a, 48);
            ViewGroup.LayoutParams lp = options.getLayoutParams();
            ViewGroup.LayoutParams mine;
            try {
                mine = lp.getClass().getConstructor(lp.getClass()).newInstance(lp);
            } catch (Throwable t) {
                mine = new ViewGroup.LayoutParams(w, ViewGroup.LayoutParams.MATCH_PARENT);
            }
            mine.width = w;
            btn.setLayoutParams(mine);
            btn.setOnClickListener(v -> toggle(a, id, btn));
            host.addView(btn, host.indexOfChild(options));
            final View opt = options;
            final int shift = w;
            btn.post(() -> {
                if (Math.abs(btn.getLeft() - opt.getLeft()) < 4) btn.setTranslationX(-shift);
            });
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "Could not add the lock button to this collection: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void showState(Activity a, android.widget.ImageView btn, boolean locked) {
        int icon = ResIds.drawable(a, locked ? "instagram_lock_pano_filled_24" : "instagram_lock_pano_outline_24");
        if (icon != 0) btn.setImageResource(icon);
        btn.setContentDescription(I18n.t(a, locked ? R.string.ig_locked_coll_unlock : R.string.ig_locked_coll_lock));
    }

    private static void toggle(Activity a, String id, android.widget.ImageView btn) {
        Runnable flip = () -> {
            Set<String> s = locked();
            boolean nowLocked = !s.remove(id);
            if (nowLocked) s.add(id);
            FeatureFlags.lockedCollections = String.join(",", s);
            SettingsManager.saveAllFlags();
            showState(a, btn, nowLocked);
            ps.reso.instaeclipse.utils.toast.IeToast.makeText(a, I18n.t(a, nowLocked ? R.string.ig_locked_coll_locked : R.string.ig_locked_coll_unlocked),
                    Toast.LENGTH_SHORT).show();
        };
        if (!hasPasscode()) askForPasscode(a, () -> {
            if (!isLocked(id)) flip.run();
        });
        else if (isLocked(id)) ask(a, flip, null, null);
        else flip.run();
    }

    private static void askForPasscode(Activity a, Runnable onSaved) {
        ps.reso.instaeclipse.utils.dialog.IeDialog d = new ps.reso.instaeclipse.utils.dialog.IeDialog(a)
                .title(I18n.t(a, R.string.ig_locked_coll_lock))
                .message(I18n.t(a, R.string.ig_locked_coll_set_passcode_body));
        d.secondary(I18n.t(a, R.string.ig_dialog_cancel), null);
        d.primary(I18n.t(a, R.string.ig_lock_set_passcode),
                () -> ps.reso.instaeclipse.utils.dialog.LockFlows.setPasscode(a, onSaved, null));
        d.show();
    }

    private static void gate(Activity a, Object frag, View root) {
        root.setAlpha(0f);
        hidden.put(root, frag);
        if (!prompt.begin(a)) return;
        Runnable open = () -> {
            prompt.end(a);
            for (java.util.Map.Entry<View, Object> e : hidden.entrySet()) {
                e.getKey().setAlpha(1f);
                unlockedFrags.add(e.getValue());
            }
            hidden.clear();
        };
        ask(a, open, () -> {
            prompt.end(a);
            hidden.clear();
            a.onBackPressed();
        }, open);
    }

    private static void ask(Activity a, Runnable onOk, Runnable onCancel, Runnable onRecovered) {
        LockDirectMessagesHook.bindLockout(a);
        final ps.reso.instaeclipse.utils.dialog.IeDialog d = new ps.reso.instaeclipse.utils.dialog.IeDialog(a)
                .title(I18n.t(a, R.string.ig_locked_coll_title))
                .cancelable(false);
        final EditText code = d.input(I18n.t(a, R.string.ig_locked_coll_hint));
        code.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        code.setGravity(Gravity.CENTER);
        code.setTextSize(22);
        code.setLetterSpacing(0.4f);
        final boolean[] done = {false};
        Runnable success = () -> {
            if (done[0]) return;
            done[0] = true;
            LockDirectMessagesHook.hideKeyboard(a, code);
            d.dismiss();
            if (onOk != null) onOk.run();
        };
        Runnable cancel = () -> {
            LockDirectMessagesHook.hideKeyboard(a, code);
            if (onCancel != null) onCancel.run();
        };
        d.view(LockDirectMessagesHook.forgotLink(a, ps.reso.instaeclipse.utils.ui.IePalette.of(a), () -> {
            if (done[0]) return;
            done[0] = true;
            LockDirectMessagesHook.hideKeyboard(a, code);
            d.dismiss();
            if (onRecovered != null) onRecovered.run();
        }));
        d.secondary(I18n.t(a, R.string.ig_dialog_cancel), cancel);
        d.onClose(cancel);
        final boolean[] closed = {false};
        d.onDismiss(() -> closed[0] = true);
        final View[] openButton = new View[1];
        final PasscodeCheck.Prompt checking = new PasscodeCheck.Prompt(() -> !closed[0] && !done[0],
                busy -> PasscodeCheck.dim(openButton[0], busy));
        openButton[0] = d.primaryKeepOpen(I18n.t(a, R.string.ig_locked_coll_open), () ->
                checking.check(code.getText().toString(), ok -> {
                    if (ok) success.run();
                    else { code.setText(""); code.setError(LockDirectMessagesHook.failureText(a)); }
                }));
        d.show();
        if (FeatureFlags.lockUseFingerprint && LockDirectMessagesHook.canAuthenticate(a))
            LockDirectMessagesHook.promptBiometric(a, I18n.t(a, R.string.ig_locked_coll_title), success);
    }

    private static Activity activityOf(View v) {
        if (v == null) return null;
        for (android.content.Context k = v.getContext(); k != null; k = k instanceof android.content.ContextWrapper w ? w.getBaseContext() : null)
            if (k instanceof Activity a) return a;
        return null;
    }
}
