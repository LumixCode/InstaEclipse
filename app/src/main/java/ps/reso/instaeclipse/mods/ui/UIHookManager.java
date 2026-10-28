package ps.reso.instaeclipse.mods.ui;

import static ps.reso.instaeclipse.mods.ghost.ui.GhostEmojiManager.addGhostEmojiNextToInbox;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;


import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.mods.devops.config.ConfigManager;
import ps.reso.instaeclipse.mods.ui.utils.LifecycleHookUtil;
import ps.reso.instaeclipse.utils.core.SettingsManager;
import ps.reso.instaeclipse.utils.dialog.DialogUtils;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.ghost.GhostModeUtils;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.toast.CustomToast;
import ps.reso.instaeclipse.utils.ui.Gestures;
import ps.reso.instaeclipse.utils.ui.TopActivityTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.log.StartupTrace;

public class UIHookManager {

    private static final String INSTAGRAM_MAIN_ACTIVITY = "com.instagram.mainactivity.InstagramMainActivity";

    @SuppressLint("StaticFieldLeak")
    private static Activity currentActivity;
    private static final TopActivityTracker<Activity> topActivities =
            new TopActivityTracker<>(a -> a.isFinishing() || a.isDestroyed());
    private static volatile boolean trackingActivities;
    private static Gestures.HintRule gestureHint = new Gestures.HintRule();

    public static Activity getCurrentActivity() {
        return currentActivity;
    }

    public static Activity topActivity() {
        Activity top = topActivities.top();
        return top != null && !top.isFinishing() && !top.isDestroyed() ? top : currentActivity;
    }

    public static android.content.Context dialogHost(android.content.Context ctx) {
        android.content.Context c = ctx;
        while (c instanceof android.content.ContextWrapper) {
            if (c instanceof Activity a) {
                if (!a.isFinishing()) return a;
                break;
            }
            c = ((android.content.ContextWrapper) c).getBaseContext();
        }
        Activity top = topActivity();
        return top != null && !top.isFinishing() ? top : ctx;
    }

    private static synchronized void trackActivities(Activity activity, boolean resumed) {
        if (trackingActivities) return;
        trackingActivities = true;
        try {
            activity.getApplication().registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityResumed(Activity a) { topActivities.onResumed(a); }
                @Override public void onActivityPaused(Activity a) { topActivities.onPaused(a); }
                @Override public void onActivityDestroyed(Activity a) { topActivities.onDestroyed(a); }
                @Override public void onActivityCreated(Activity a, Bundle b) {}
                @Override public void onActivityStarted(Activity a) {}
                @Override public void onActivityStopped(Activity a) {}
                @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            });
            if (resumed) topActivities.onResumed(activity);
        } catch (Throwable t) {
            ModuleLog.detail("(InstaEclipse) dialogs open on the main screen only: " + t);
        }
    }

    /**
     * Activities that already have a pending OnGlobalLayoutListener registered.
     * Used only to prevent duplicate listener registrations when the search view
     * is not yet visible at setup time.
     */
    private static final java.util.WeakHashMap<Activity, Boolean> sGlobalListenerPending =
            new java.util.WeakHashMap<>();
    /**
     * Tracks whether the GlobalLayoutListener path has completed for an activity,
     * so we don't keep registering new listeners on every resume after search is found.
     */
    private static final java.util.WeakHashMap<Activity, Boolean> sSearchWiringDone =
            new java.util.WeakHashMap<>();

    // Resource IDs are constant for a given IG install — cache them statically.
    private static volatile int sSearchTabId = 0;
    private static volatile int sActionBarEndId = 0;
    private static volatile int sInboxButtonId = 0;
    private static volatile int sDirectTabId = 0;

    @SuppressLint("DiscouragedApi")
    private static void ensureIdsCached(Activity activity) {
        if (sSearchTabId != 0 && sActionBarEndId != 0
                && sInboxButtonId != 0 && sDirectTabId != 0) return;
        String pkg = activity.getPackageName();
        android.content.res.Resources res = activity.getResources();
        if (sSearchTabId == 0)
            sSearchTabId = res.getIdentifier("search_tab", "id", pkg);
        if (sActionBarEndId == 0)
            sActionBarEndId = res.getIdentifier("action_bar_end_action_buttons", "id", pkg);
        if (sInboxButtonId == 0)
            sInboxButtonId = res.getIdentifier("action_bar_inbox_button", "id", pkg);
        if (sDirectTabId == 0)
            sDirectTabId = res.getIdentifier("direct_tab", "id", pkg);
    }

    public static void setupHooks(Activity activity, boolean isMain) {
        // Ghost emoji visibility must update on every resume (reflects current ghost state).
        addGhostEmojiNextToInbox(activity, GhostModeUtils.isGhostModeActive());

        // Lock DMs: arm the inbox passcode watcher on the main activity (its onCreate is
        // obfuscated, so a direct hook fails — this runs from the module's resolved main hook).
        ps.reso.instaeclipse.mods.ui.LockDirectMessagesHook.watchActivity(activity);
        ps.reso.instaeclipse.mods.ghost.HideChatsHook.watchActivity(activity);

        // Remove Meta AI: collapse the "About this reel" Content Deep Dive section (AI summary +
        // "Ask Meta AI…" composer) inside the reel/post action sheet. It is a Litho-rendered
        // section not reachable via the option/builder hooks, so we strip it at the view level.
        ps.reso.instaeclipse.mods.ui.RemoveMetaAIHook.watchActionSheet(activity);

        // Distraction Free: collapse the story tray / Reels tab at the view level (the network drop
        // alone leaves IG's cached stories/reels visible on IG 447.0.0.39+).
        ps.reso.instaeclipse.mods.ui.DistractionFreeUIHook.watchActivity(activity);
        ps.reso.instaeclipse.mods.ui.StoryTrayHook.watchActivity(activity);

        // Cache resource IDs once per IG install (string table lookup is non-trivial).
        ensureIdsCached(activity);

        // Always re-apply the search long-press listener. Instagram may overwrite it after
        // a config change (e.g. when InstaEclipse settings are toggled), so we cannot skip
        // this on resume — we just avoid the expensive getIdentifier() call via the cache.
        boolean anySearchFound = false;
        if (sSearchTabId != 0) {
            View v = activity.findViewById(sSearchTabId);
            if (v != null) anySearchFound = processSearchView(activity, v, "search_tab");
        }
        if (!anySearchFound && sActionBarEndId != 0) {
            View v = activity.findViewById(sActionBarEndId);
            if (v != null) anySearchFound = processSearchView(activity, v, "action_bar_end_action_buttons");
        }

        // Register at most ONE GlobalLayoutListener per activity to retry search wiring
        // when the view isn't inflated yet. Skip if we already found search, if a listener
        // is already pending, or if the listener already completed successfully.
        if (isMain && !anySearchFound
                && !Boolean.TRUE.equals(sGlobalListenerPending.get(activity))
                && !Boolean.TRUE.equals(sSearchWiringDone.get(activity))) {
            sGlobalListenerPending.put(activity, true);
            final View decorView = activity.getWindow().getDecorView();
            decorView.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    boolean found = false;
                    if (sSearchTabId != 0) {
                        View lateView = activity.findViewById(sSearchTabId);
                        if (lateView != null) found = processSearchView(activity, lateView, "search_tab");
                    }
                    if (!found && sActionBarEndId != 0) {
                        View lateView = activity.findViewById(sActionBarEndId);
                        if (lateView != null) found = processSearchView(activity, lateView, "action_bar_end_action_buttons");
                    }
                    if (found) {
                        decorView.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        sGlobalListenerPending.remove(activity);
                        sSearchWiringDone.put(activity, true);
                    }
                }
            });
        }

    }

    public static void setupCurrentScreen() {
        new Handler(Looper.getMainLooper()).post(() -> {
            Activity activity = currentActivity;
            if (activity == null || activity.isFinishing()) return;
            try {
                setupHooks(activity, true);
            } catch (Exception e) {
                ModuleLog.warn("(InstaEclipse) Could not set up InstaEclipse's buttons on this screen: "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        });
    }

    public void mainActivity(ClassLoader classLoader) {
        // Hook onCreate / onResume of Instagram Main.
        // IG 448+ no longer declares these overrides on InstagramMainActivity (they live in the
        // shared base com.instagram.base.activity.IgFragmentActivity, and the activity only has
        // obfuscated delegates like A1p(Bundle, X)). The old DexKit lookup (declaredClass +
        // "onCreate"/(Bundle)V, then a "public void() with >=20 opcodes" guess for onResume) found
        // nothing there, so setupHooks() — the settings entry, ghost emoji, Distraction-Free view
        // hooks, feature toast — never ran from the main activity. LifecycleHookUtil hooks the
        // class that actually declares the method and filters by instance, so it resolves on old
        // builds (declared override, same as before) and new ones alike.
        try {
            String where = LifecycleHookUtil.hookAfter(classLoader, INSTAGRAM_MAIN_ACTIVITY, "onCreate",
                    (activity, param) -> onMainActivityCreated(activity), Bundle.class);
            ModuleLog.detail("(InstaEclipse): main onCreate hooked via " + where);
        } catch (Throwable t) {
            ModuleLog.error("(InstaEclipse): Could not attach to Instagram's main screen; the InstaEclipse menu shortcut, ghost mode icon and feature summary may not show", t);
        }

        try {
            String where = LifecycleHookUtil.hookAfter(classLoader, INSTAGRAM_MAIN_ACTIVITY, "onResume",
                    (activity, param) -> {
                        StartupTrace.watchFirstFrame();
                        currentActivity = activity;
                        trackActivities(activity, true);
                        activity.runOnUiThread(() -> {
                            try {
                                setupHooks(activity, true);
                            } catch (Exception e) {
                                ModuleLog.warn("(InstaEclipse) Could not set up InstaEclipse's buttons on this screen: "
                                        + e.getClass().getSimpleName() + ": " + e.getMessage());
                            }
                            armGestureHint(activity);
                            ps.reso.instaeclipse.utils.dialog.RestartPrompt.onMainResume(activity);
                        });
                    });
            ModuleLog.detail("(InstaEclipse): main onResume hooked via " + where);
        } catch (Throwable t) {
            ModuleLog.error("(InstaEclipse): Could not attach to Instagram's main screen; the InstaEclipse menu shortcut and ghost mode toggle may not work", t);
        }

        // Hook View.performLongClick — inbox long-press override.
        // setOnLongClickListener is unreliable when Instagram has a parent-level touch
        // interceptor or a custom view that overrides long-press dispatch. Hooking
        // performLongClick() fires BEFORE any listener/interceptor chain and lets us
        // fully own the event by returning true via setResult.
        // This only fires when the user actually long-presses something — not a hot path.
        try {
            XposedHelpers.findAndHookMethod(View.class, "performLongClick", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    if (sInboxButtonId == 0 && sDirectTabId == 0) return;
                    View view = (View) param.thisObject;
                    int id = view.getId();
                    if (id != sInboxButtonId && id != sDirectTabId) return;
                    Activity activity = currentActivity;
                    if (activity == null) return;
                    GhostModeUtils.toggleSelectedGhostOptions(activity);
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    param.setResult(true); // consume — skip Instagram's handler entirely
                }
            });
        } catch (Throwable t) {
            ModuleLog.warn("(InstaEclipse) Holding the inbox icon may not toggle ghost mode: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }

        // Hook onResume - Modal
        try {
            LifecycleHookUtil.hookAfter(classLoader, "com.instagram.modal.ModalActivity", "onResume",
                    (activity, param) -> activity.runOnUiThread(() -> {
                        try {
                            setupHooks(activity, false);
                        } catch (Exception e) {
                            ModuleLog.warn("(InstaEclipse) Could not set up InstaEclipse's buttons on this screen: "
                                    + e.getClass().getSimpleName() + ": " + e.getMessage());
                        }
                    }));
        } catch (Throwable t) {
            ModuleLog.warn("(InstaEclipse): InstaEclipse's buttons may be missing on pop-up screens: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }

        FancyBioFontHook.install(classLoader);
    }

    static void armGestureHint(Activity activity) {
        if (!gestureHint.arm()) return;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                boolean inFront = currentActivity == activity && !activity.isFinishing() && !activity.isDestroyed()
                        && activity.hasWindowFocus();
                if (gestureHint.fire(Gestures.hintShown(activity), inFront)) DialogUtils.showGesturesHint(activity);
            } catch (Exception e) {
                ModuleLog.warn("(InstaEclipse) Could not show the gestures hint: "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }, Gestures.HINT_DELAY_MS);
    }

    private static void onMainActivityCreated(Activity activity) {
        currentActivity = activity;
        trackActivities(activity, false);

        // Use runOnUiThread to ensure we are touching the UI safely
        activity.runOnUiThread(() -> {
            try {
                // 1. Initialize Hooks
                setupHooks(activity, true);

                // 2. Delay UI injections slightly.
                // Instagram's Main is complex; the Inbox/UI might not be inflated immediately.
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    try {
                        // Add the Ghost Emoji next to Inbox
                        addGhostEmojiNextToInbox(activity, GhostModeUtils.isGhostModeActive());

                        // 3. Show Success Toast
                        if (FeatureFlags.showFeatureToasts && !CustomToast.toastShown) {
                            CustomToast.toastShown = true;

                            // Pass the raw KEY -> hooked map; showFeatureGrid resolves
                            // each key's label and groups it into a category itself.
                            java.util.LinkedHashMap<String, Boolean> status =
                                    new java.util.LinkedHashMap<>(FeatureStatusTracker.getStatus());
                            CustomToast.showFeatureGrid(activity.getApplicationContext(),
                                    I18n.t(activity, R.string.ig_toast_features_loaded), status);
                        }
                    } catch (Exception innerE) {
                        ModuleLog.warn("(InstaEclipse): Could not add the ghost mode icon or the feature summary: "
                                + innerE.getClass().getSimpleName() + ": " + innerE.getMessage());
                    }
                }, 1500); // 1.5s delay to let the UI settle

            } catch (Exception e) {
                ModuleLog.warn("(InstaEclipse): Could not set up InstaEclipse on the main screen: "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        });
    }

    private static void applySearchHook(Activity activity, View v) {
        v.setOnLongClickListener(view -> {
            DialogUtils.showEclipseOptionsDialog(activity);
            return true;
        });
    }

    private static boolean processSearchView(Activity activity, View view, String id) {
        if (id.equals("action_bar_end_action_buttons") && view instanceof ViewGroup container) {
            int wired = 0;
            for (int i = 0; i < container.getChildCount(); i++) {
                View child = container.getChildAt(i);
                if (SearchTabMatch.isSearchChild(entryName(child), child.getContentDescription())) {
                    applySearchHook(activity, child);
                    wired++;
                }
            }
            return SearchTabMatch.found(true, true, wired);
        }
        applySearchHook(activity, view);
        return SearchTabMatch.found(false, true, 1);
    }

    private static String entryName(View view) {
        int id = view.getId();
        if (id == View.NO_ID) return null;
        try {
            return view.getResources().getResourceEntryName(id);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Registers a broadcast receiver in the Instagram process to handle config imports. */
    public static void registerConfigImportReceiver(android.content.Context context) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context ctx, Intent intent) {
                String json = intent.getStringExtra("json_content");
                try { ps.reso.instaeclipse.utils.ipc.BrokerPayload.validateConfig(json); }
                catch (IllegalArgumentException malformed) {
                    ModuleLog.error("(IE|Config) Settings import rejected: the file is invalid or too large");
                    confirm(intent, "Configuration is invalid or too large");
                    return;
                }
                if (json != null && !json.isEmpty()) {
                    ConfigManager.importConfigFromJson(ctx, json, error -> confirm(intent, error));
                }
            }
        };
        ps.reso.instaeclipse.utils.core.IpcSecurity.registerCompanionOnlyReceiver(context, receiver,
                new IntentFilter("ps.reso.instaeclipse.ACTION_IMPORT_CONFIG"));
    }

    /** Registers a receiver in the Instagram process to restore settings from a backup JSON. */
    public static void registerSettingsRestoreReceiver(android.content.Context context) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context ctx, Intent intent) {
                String json = intent.getStringExtra("json_content");
                try { ps.reso.instaeclipse.utils.ipc.BrokerPayload.validateConfig(json); }
                catch (IllegalArgumentException malformed) {
                    ModuleLog.error("(IE|Config) Settings restore rejected: the backup is invalid or too large");
                    confirm(intent, "Backup is invalid or too large");
                    return;
                }
                if (json == null || json.isEmpty()) return;
                new Thread(() -> {
                    try {
                        ps.reso.instaeclipse.utils.backup.SettingsBackupManager.Result result =
                                ps.reso.instaeclipse.utils.backup.SettingsBackupManager.restore(json);
                        SettingsManager.saveAllFlags();
                        confirm(intent, null);
                        Handler mainHandler = new Handler(Looper.getMainLooper());
                        mainHandler.post(() -> {
                            ps.reso.instaeclipse.Xposed.SettingsEvents.afterChange(result.changedKeys);
                            if (result.skipped > 0) {
                                ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx.getApplicationContext(),
                                        "⚠️ " + I18n.t(ctx, R.string.ie_toast_restored_some_skipped, result.skipped),
                                        Toast.LENGTH_LONG).show();
                            } else {
                                ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx.getApplicationContext(),
                                        "✅ " + I18n.t(ctx, R.string.ig_toast_settings_restored), Toast.LENGTH_SHORT).show();
                            }
                        });
                    } catch (Exception e) {
                        confirm(intent, "Backup could not be restored");
                        Handler mainHandler = new Handler(Looper.getMainLooper());
                        mainHandler.post(() -> ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx.getApplicationContext(),
                                "❌ " + I18n.t(ctx, R.string.ig_toast_restore_failed, e.getMessage()), Toast.LENGTH_LONG).show());
                    }
                }).start();
            }
        };
        try {
            ps.reso.instaeclipse.utils.core.IpcSecurity.registerCompanionOnlyReceiver(context, receiver,
                    new IntentFilter("ps.reso.instaeclipse.ACTION_RESTORE_SETTINGS"));
            } catch (Throwable e) {
            ModuleLog.error("(InstaEclipse | RestoreReceiver): Restoring a settings backup into Instagram will not work: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static void confirm(Intent request, String error) {
        Intent reply = new Intent();
        ps.reso.instaeclipse.utils.core.IpcSecurity.echoNonce(request, reply);
        if (error != null) reply.putExtra("error", error);
        ps.reso.instaeclipse.utils.ipc.ModuleReplies.reply(request, reply.getExtras());
    }

}
