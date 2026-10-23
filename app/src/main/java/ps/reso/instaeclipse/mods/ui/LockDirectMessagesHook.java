package ps.reso.instaeclipse.mods.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.core.PasscodeHasher;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.toast.IeToast;

/** Gates the app, a Direct visit, or selected conversations with one shared credential
 *  (PBKDF2, see {@link PasscodeHasher}; five wrong tries lock entry for 30 s). */
public class LockDirectMessagesHook {
    private static final String OVERLAY_TAG = "ie_dm_lock";
    private static final DirectLockSession session = new DirectLockSession();
    private static final java.util.Map<Activity, Gate> gates = new java.util.WeakHashMap<>();
    private static final java.util.Set<Activity> started =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private static final java.util.Set<Activity> resumed =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private static final java.util.Set<View> watched =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private static boolean lifecycleRegistered;
    private static boolean appUnlocked;
    private static String previousSettings;
    private static String previousAppSettings;
    private static String currentAccount;
    private static int inboxId, listId, threadId;
    private static long outsideDirectSince;
    private static final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    /**
     * The hidden-inbox gate opens on the third of several quick taps, and the system biometric
     * prompt closes itself on any tap outside its panel: a fourth tap can close it as it appears.
     * That gate's prompt still comes up at once, and comes back once if it closes this soon after
     * being asked for. SystemUI plays its 250 ms show and 350 ms hide animations before it reports
     * the tap, so the window is well past their sum.
     */
    private static final long STRAY_TAP_MS = 1500L;
    /**
     * After the prompt's window is gone, how long to wait for its close to be reported. Android 11
     * reports nothing for a tap during the show animation, so the gate's window getting focus back
     * is the only sign there.
     */
    private static final long CLOSE_REPORT_MS = 300L;
    /** How long a re-prompt waits for window focus to settle before checking that the gate has it. */
    private static final long REPROMPT_SETTLE_MS = 200L;

    private static final class Gate {
        final android.app.Dialog dialog;
        final String scope;
        final java.util.concurrent.atomic.AtomicBoolean completed =
                new java.util.concurrent.atomic.AtomicBoolean();
        /** The current biometric prompt's; each prompt gets its own (main thread). */
        android.os.CancellationSignal biometric = new android.os.CancellationSignal();
        // The current prompt (main thread): its number, when it was asked for, whether it is still
        // up as far as the gate knows, whether the gate's window lost focus to it, and how to show
        // it once more after a stray tap (null: never).
        int promptId;
        long promptedAt;
        boolean promptOpen, focusLost;
        Runnable reprompt;

        Gate(android.app.Dialog dialog, String scope) {
            this.dialog = dialog;
            this.scope = scope;
        }
    }

    /**
     * Which of a window's frames run the gate check (main thread only). The check resolves the
     * visible screen by reflection, so steady frames (scrolling, video) run it at most once per
     * INTERVAL_MS, the last one as a posted check. Opening a screen always lays it out, and for
     * SETTLE_MS after a layout pass every frame checks, as before this throttle, while the new
     * screen's transition and lifecycle settle. watchActivity (activity resume and the Direct
     * inbox/thread onResume) still checks at once.
     */
    static final class GateThrottle {
        static final long INTERVAL_MS = 100L;
        static final long SETTLE_MS = 500L;
        private long lastCheck = -INTERVAL_MS;
        private long settleUntil;
        private boolean skipped;
        private boolean posted;

        void laidOut(long now) { settleUntil = now + SETTLE_MS; }

        /** 0: check in this frame; above 0: post the trailing check that much later; below 0: it is posted. */
        long preDraw(long now) {
            if (now < settleUntil || now - lastCheck >= INTERVAL_MS) {
                lastCheck = now;
                skipped = false;
                return 0L;
            }
            skipped = true;
            if (posted) return -1L;
            posted = true;
            return lastCheck + INTERVAL_MS - now;
        }

        /** The posted check is due unless a frame checked after the last skipped one. */
        boolean trailingDue(long now) {
            posted = false;
            if (!skipped) return false;
            lastCheck = now;
            skipped = false;
            return true;
        }
    }

    public void install(ClassLoader loader) {
        try {
            XposedHelpers.findAndHookMethod("com.instagram.modal.ModalActivity", loader,
                    "onResume", new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                            watchActivity((Activity) param.thisObject);
                        }
                    });
        } catch (Throwable t) {
            ModuleLog.warn("(IE|LockDMs) The lock may not cover chats opened in pop-up screens: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    public static void watchActivity(Activity activity) {
        if (activity == null) return;
        activity.runOnUiThread(() -> {
            registerLifecycle(activity);
            started.add(activity);
            resumed.add(activity);
            ensureIds(activity);
            View decor = activity.getWindow().getDecorView();
            if (watched.add(decor)) {
                GateThrottle throttle = new GateThrottle();
                Runnable trailing = () -> {
                    try {
                        if (throttle.trailingDue(android.os.SystemClock.uptimeMillis())
                                && resumed.contains(activity)) checkGate(activity);
                    } catch (Throwable t) {
                        ModuleLog.warn("(IE|LockDMs) Could not check whether to show the lock: "
                                + t.getClass().getSimpleName() + ": " + t.getMessage());
                    }
                };
                android.view.ViewTreeObserver observer = decor.getViewTreeObserver();
                // Runs before the same frame's pre-draw: a new screen is always laid out.
                observer.addOnGlobalLayoutListener(() -> throttle.laidOut(android.os.SystemClock.uptimeMillis()));
                observer.addOnPreDrawListener(() -> {
                    try {
                        if (resumed.contains(activity)) {
                            long delay = throttle.preDraw(android.os.SystemClock.uptimeMillis());
                            if (delay == 0L) checkGate(activity);
                            else if (delay > 0L) main.postDelayed(trailing, delay);
                        }
                    } catch (Throwable t) {
                        ModuleLog.warn("(IE|LockDMs) Could not check whether to show the lock: "
                                + t.getClass().getSimpleName() + ": " + t.getMessage());
                    }
                    return true;
                });
            }
            checkGate(activity);
            // Both gates run from here: the whole-app one and the Direct one keep their own status.
            if (FeatureFlags.lockWholeApp) FeatureStatusTracker.setHooked("LockWholeApp");
            if (FeatureFlags.lockDirectMessages) FeatureStatusTracker.setHooked("LockDirectMessages");
        });
    }

    private static void registerLifecycle(Activity seed) {
        if (lifecycleRegistered) return;
        lifecycleRegistered = true;
        seed.getApplication().registerActivityLifecycleCallbacks(new android.app.Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(Activity a) { started.add(a); }
            @Override public void onActivityResumed(Activity a) { watchActivity(a); }
            @Override public void onActivityPaused(Activity a) { resumed.remove(a); }
            @Override public void onActivityStopped(Activity a) {
                started.remove(a);
                dismissGate(a);
                if (!started.isEmpty() || a.isChangingConfigurations()) return;
                appUnlocked = false;
                session.background(FeatureFlags.lockDirectAlways);
                outsideDirectSince = 0L;
            }
            @Override public void onActivityDestroyed(Activity a) {
                resumed.remove(a);
                started.remove(a);
                dismissGate(a);
            }
            @Override public void onActivityCreated(Activity a, android.os.Bundle state) {}
            @Override public void onActivitySaveInstanceState(Activity a, android.os.Bundle state) {}
        });
    }

    @SuppressLint("DiscouragedApi")
    private static void ensureIds(Activity a) {
        if (inboxId != 0) return;
        inboxId = a.getResources().getIdentifier("direct_inbox_action_bar", "id", a.getPackageName());
        listId = a.getResources().getIdentifier("inbox_refreshable_thread_list_recyclerview", "id", a.getPackageName());
        threadId = a.getResources().getIdentifier("direct_thread_header", "id", a.getPackageName());
    }

    private static boolean shown(View view, int id) {
        if (view == null || id == 0 || !view.isShown()) return false;
        if (view.getId() == id) return true;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                if (shown(group.getChildAt(i), id)) return true;
            }
        }
        return false;
    }

    private static DirectLockSession.Screen screen(Activity a) {
        String module = ps.reso.instaeclipse.mods.ghost.HideChatsHook.visibleModule(a);
        if ("direct_inbox".equals(module)) return DirectLockSession.Screen.INBOX;
        if ("direct_thread".equals(module)) return DirectLockSession.Screen.THREAD;
        if (module.startsWith("direct_")) return DirectLockSession.Screen.DIRECT;
        if (!module.isEmpty()) return DirectLockSession.Screen.OTHER;
        View decor = a.getWindow().getDecorView();
        if (shown(decor, threadId)) return DirectLockSession.Screen.THREAD;
        if (shown(decor, inboxId) || shown(decor, listId)) return DirectLockSession.Screen.INBOX;
        return DirectLockSession.Screen.UNKNOWN;
    }

    private static void checkGate(Activity a) {
        if (a.isFinishing() || a.isDestroyed()) return;
        if (!FeatureFlags.lockWholeApp && !FeatureFlags.lockDirectMessages
                && !FeatureFlags.lockSpecificMessages && !gates.containsKey(a)) {
            session.reset();
            appUnlocked = false;
            return;
        }
        try {
            // The salt, not the hash: the first unlock re-hashes a legacy passcode (PBKDF2), which
            // must not reset the session and prompt again. A new passcode always gets a new salt.
            String credential = FeatureFlags.lockDirectSalt + ":"
                    + (FeatureFlags.lockDirectPasscode == null || FeatureFlags.lockDirectPasscode.isEmpty());
            String settings = FeatureFlags.lockWholeApp + ":" + FeatureFlags.lockDirectMessages
                    + ":" + FeatureFlags.lockSpecificMessages + ":" + FeatureFlags.lockDirectAlways
                    + ":" + FeatureFlags.lockedDirectThreads + ":" + credential;
            if (!settings.equals(previousSettings)) {
                previousSettings = settings;
                session.reset();
            }
            String appSettings = FeatureFlags.lockWholeApp + ":" + credential;
            if (!appSettings.equals(previousAppSettings)) {
                previousAppSettings = appSettings;
                appUnlocked = false;
            }
            DirectLockSession.Screen screen = screen(a);
            String id = screen == DirectLockSession.Screen.THREAD
                    ? ps.reso.instaeclipse.mods.ghost.HideChatsHook.currentThreadId(a) : null;
            String account = ps.reso.instaeclipse.mods.ghost.HideChatsHook.currentAccount(a);
            if (account != null && !account.equals(currentAccount)) {
                if (currentAccount != null) appUnlocked = false;
                currentAccount = account;
                // A hidden-chats or settings gate was opened from this account's own screen and covers
                // it, so no switch can happen under it. With the DM locks off the account is first
                // read only once such a gate is up: dismissing it here closed the triple-tap prompt
                // on its first frame.
                Gate open = gates.get(a);
                if (open == null || !(open.scope.equals("hidden") || open.scope.equals("settings"))) dismissGate(a);
            }
            // A navigation animation can briefly expose the underlying feed between two Direct fragments.
            if (screen == DirectLockSession.Screen.OTHER) {
                long now = android.os.SystemClock.elapsedRealtime();
                if (outsideDirectSince == 0L) {
                    outsideDirectSince = now;
                    main.postDelayed(() -> { if (resumed.contains(a)) checkGate(a); }, 250L);
                }
                if (now - outsideDirectSince < 250L) screen = DirectLockSession.Screen.UNKNOWN;
            } else if (screen != DirectLockSession.Screen.UNKNOWN) outsideDirectSince = 0L;
            session.visit(screen, id, account, FeatureFlags.lockDirectAlways);
            if (FeatureFlags.lockDirectPasscode == null || FeatureFlags.lockDirectPasscode.isEmpty()) {
                dismissGate(a);
                return;
            }
            String scope = null;
            String title = null;
            if (FeatureFlags.lockWholeApp && !appUnlocked) {
                scope = "app";
                title = I18n.t(a, R.string.ig_lock_open_app_prompt);
            } else if (session.needsInboxLock(screen, FeatureFlags.lockDirectMessages)) {
                scope = "inbox";
                title = I18n.t(a, R.string.ig_lock_open_dms_prompt);
            } else if (!FeatureFlags.lockDirectMessages && FeatureFlags.lockSpecificMessages
                    && session.needsThreadLock(screen, id,
                    id == null ? !ps.reso.instaeclipse.utils.ghost.LockedThreads.all().isEmpty()
                            : ps.reso.instaeclipse.utils.ghost.LockedThreads.contains(id))) {
                scope = "thread:" + id;
                title = I18n.t(a, R.string.ig_lock_open_chat_prompt);
            }
            Gate current = gates.get(a);
            if (current != null && (current.scope.equals("hidden") || current.scope.equals("settings"))) return;
            if (current != null && java.util.Objects.equals(current.scope, scope)) return;
            if (screen == DirectLockSession.Screen.UNKNOWN && current != null && scope == null) return;
            dismissGate(a);
            if (scope == null) return;
            final String requestedScope = scope;
            final String requestedThread = id;
            final String requestedAccount = account;
            showAuthenticationOverlay(a, title, I18n.t(a, R.string.ig_lock_biometric_title), () -> {
                if ("app".equals(requestedScope)) {
                    appUnlocked = true;
                    session.unlockInbox();
                    session.unlockThread(requestedThread);
                } else if ("inbox".equals(requestedScope)) session.unlockInbox();
                else session.unlockThread(requestedThread);
            }, requestedScope, () -> {
                DirectLockSession.Screen live = screen(a);
                String liveAccount = ps.reso.instaeclipse.mods.ghost.HideChatsHook.currentAccount(a);
                if (!java.util.Objects.equals(requestedAccount, liveAccount)) return false;
                if (requestedScope.equals("app")) return true;
                if (requestedScope.equals("inbox")) return live == DirectLockSession.Screen.INBOX
                        || live == DirectLockSession.Screen.THREAD || live == DirectLockSession.Screen.DIRECT;
                return requestedThread != null && live == DirectLockSession.Screen.THREAD
                        && requestedThread.equals(ps.reso.instaeclipse.mods.ghost.HideChatsHook.currentThreadId(a));
            }, () -> {
                if (requestedScope.equals("app")) a.moveTaskToBack(true);
                else a.onBackPressed();
            });
        } catch (Throwable t) {
            ModuleLog.error("(IE|LockDMs) Could not show the lock screen: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void dismissGate(Activity a) {
        Gate gate = gates.remove(a);
        if (gate == null) return;
        gate.completed.set(true);
        gate.biometric.cancel();
        gate.dialog.dismiss();
    }

    public static boolean authenticateForHiddenChats(Activity a, Runnable onSuccess) {
        return a != null && authenticate(a, I18n.t(a, R.string.ig_lock_hidden_chats_prompt), "hidden", onSuccess);
    }

    /** Full-screen passcode/biometric gate for a settings or hidden-inbox action; false when no
     *  passcode is set (the user is told) or a gate is already showing. */
    public static boolean authenticate(Activity a, String title, String scope, Runnable onSuccess) {
        if (a == null || onSuccess == null) return false;
        if (FeatureFlags.lockDirectPasscode == null || FeatureFlags.lockDirectPasscode.isEmpty()) {
            IeToast.makeText(a, I18n.t(a, R.string.ig_lock_set_passcode_first), Toast.LENGTH_SHORT).show();
            return false;
        }
        return showAuthenticationOverlay(a, title, I18n.t(a, R.string.ig_lock_biometric_title), onSuccess, scope,
                () -> resumed.contains(a) && !a.isFinishing(), () -> {});
    }

    private static boolean showAuthenticationOverlay(Activity a, String titleText,
                                                     String biometricTitle,
                                                     Runnable onSuccess, String scope,
                                                     java.util.function.BooleanSupplier valid,
                                                     Runnable onCancel) {
        if (gates.containsKey(a) || a.isFinishing() || a.isDestroyed()) return false;
        android.app.Dialog dialog = new android.app.Dialog(a, android.R.style.Theme_Material_NoActionBar) {
            @Override public void cancel() {
                if (scope.equals("hidden") || scope.equals("settings")) {
                    super.cancel();
                    return;
                }
                Gate active = gates.get(a);
                if (active == null || active.dialog != this
                        || !active.completed.compareAndSet(false, true)) return;
                active.biometric.cancel();
                // Keep the opaque window until navigation has actually left the locked screen.
                onCancel.run();
            }

            @Override public void onWindowFocusChanged(boolean hasFocus) {
                super.onWindowFocusChanged(hasFocus);
                // The prompt takes focus while it is up; focus back while it should be up means its
                // window is gone. Its close report, when there is one, comes within CLOSE_REPORT_MS.
                Gate active = gates.get(a);
                if (active == null || active.dialog != this || !active.promptOpen) return;
                if (!hasFocus) {
                    active.focusLost = true;
                    return;
                }
                if (!active.focusLost) return;
                int id = active.promptId;
                main.postDelayed(() -> {
                    if (gates.get(a) != active || active.promptId != id || !active.promptOpen) return;
                    active.promptOpen = false;
                    repromptAfterStrayTap(active);
                }, CLOSE_REPORT_MS);
            }
        };
        Gate gate = new Gate(dialog, scope);
        FrameLayout overlay = new FrameLayout(a);
        overlay.setTag(OVERLAY_TAG);
        overlay.setClickable(true);
        overlay.setBackgroundColor(Color.parseColor("#121214"));
        overlay.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));

        // Scrollable so the card is always reachable even on short screens with the keyboard up.
        android.widget.ScrollView scroller = new android.widget.ScrollView(a);
        FrameLayout.LayoutParams svLp = new FrameLayout.LayoutParams(-1, -1);
        scroller.setLayoutParams(svLp);
        scroller.setFillViewport(true);

        // Rounded card holding the prompt.
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(a, 28);
        col.setPadding(pad, pad, pad, pad);
        android.graphics.drawable.GradientDrawable cardBg = new android.graphics.drawable.GradientDrawable();
        cardBg.setColor(Color.parseColor("#1E1E20"));
        cardBg.setCornerRadius(dp(a, 22));
        col.setBackground(cardBg);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(dp(a, 300), -2);
        clp.gravity = Gravity.CENTER;
        clp.topMargin = dp(a, 40);
        clp.bottomMargin = dp(a, 40);
        col.setLayoutParams(clp);

        TextView lock = new TextView(a);
        lock.setText("🔒");
        lock.setTextSize(34);
        lock.setGravity(Gravity.CENTER);
        lock.setPadding(0, 0, 0, dp(a, 8));

        TextView title = new TextView(a);
        title.setText(titleText);
        title.setTextColor(Color.WHITE);
        title.setTextSize(17);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(a, 18));

        final EditText code = new EditText(a);
        code.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        code.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        code.setSingleLine(true);
        code.setHint(I18n.t(a, R.string.ig_locked_coll_hint));
        code.setTextColor(Color.WHITE);
        code.setHintTextColor(Color.parseColor("#8E8E93"));
        code.setGravity(Gravity.CENTER);
        code.setTextSize(20);
        code.setLetterSpacing(0.15f);
        // Size the box to a few digits and centre it — no wide empty passcode bar.
        code.setEms(6);
        code.setMinWidth(0);
        code.setMinimumWidth(0);
        android.graphics.drawable.GradientDrawable inBg = new android.graphics.drawable.GradientDrawable();
        inBg.setColor(Color.parseColor("#2C2C2E"));
        inBg.setCornerRadius(dp(a, 12));
        code.setBackground(inBg);
        int ip = dp(a, 12);
        code.setPadding(ip, dp(a, 10), ip, dp(a, 10));
        LinearLayout.LayoutParams codeLp = new LinearLayout.LayoutParams(-2, -2); // wrap: hugs the digits
        codeLp.gravity = Gravity.CENTER_HORIZONTAL;
        code.setLayoutParams(codeLp);

        final TextView error = new TextView(a);
        error.setTextColor(Color.parseColor("#FF6B6B"));
        error.setTextSize(13);
        error.setGravity(Gravity.CENTER);
        error.setPadding(0, dp(a, 8), 0, 0);
        error.setVisibility(View.GONE);

        Button unlock = new Button(a);
        unlock.setText(I18n.t(a, R.string.ig_lock_unlock));
        unlock.setAllCaps(false);
        unlock.setTextColor(Color.WHITE);
        unlock.setTextSize(16);
        android.graphics.drawable.GradientDrawable btnBg = new android.graphics.drawable.GradientDrawable();
        btnBg.setColor(Color.parseColor("#0A84FF"));
        btnBg.setCornerRadius(dp(a, 12));
        unlock.setBackground(btnBg);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(-1, dp(a, 46));
        btnLp.topMargin = dp(a, 16);
        unlock.setLayoutParams(btnLp);

        final Runnable succeed = () -> {
            if (gates.get(a) != gate || !valid.getAsBoolean()
                    || !gate.completed.compareAndSet(false, true)) return;
            onSuccess.run();
            hideKeyboard(a, code);
            gates.remove(a);
            gate.biometric.cancel();
            dialog.dismiss();
        };
        final Runnable attempt = () -> {
            if (checkPass(code.getText().toString())) {
                succeed.run();
            } else {
                code.setText("");
                error.setText(failureText(a));
                error.setVisibility(View.VISIBLE);
            }
        };
        unlock.setOnClickListener(v -> attempt.run());
        // Keyboard "Done" also submits — so the unlock never depends on a button the keyboard hides.
        code.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { attempt.run(); return true; }
            return false;
        });

        col.addView(lock);
        col.addView(title);
        col.addView(code);
        col.addView(error);
        col.addView(unlock);
        // Wrapper fills the viewport (via fillViewport) and centers the card — so the card itself
        // stays at its content height instead of the ScrollView stretching it to full screen.
        FrameLayout inner = new FrameLayout(a);
        inner.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        inner.addView(col);
        scroller.addView(inner);
        overlay.addView(scroller);
        dialog.setContentView(overlay);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnCancelListener(ignored -> {
            if (!gate.completed.compareAndSet(false, true)) return;
            gates.remove(a);
            gate.biometric.cancel();
            hideKeyboard(a, code);
            onCancel.run();
        });
        android.view.Window window = dialog.getWindow();
        if (window == null) return false;
        window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.rgb(18, 18, 20)));
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        window.setDimAmount(1f);
        gates.put(a, gate);
        try {
            dialog.show();
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        } catch (Throwable t) {
            gates.remove(a);
            throw t;
        }

        // Biometric unlock: if the device has an enrolled fingerprint/face and the user hasn't
        // disabled it, prompt automatically. Success unlocks exactly like a correct passcode; the
        // passcode field stays as the fallback (the prompt's negative button just dismisses it).
        if (FeatureFlags.lockUseFingerprint && canAuthenticate(a)) {
            if (scope.equals("hidden")) {
                gate.reprompt = () -> {
                    // Home or Recents closes the prompt as a user cancel too: not over the launcher.
                    android.view.Window w = dialog.getWindow();
                    if (gates.get(a) == gate && !gate.completed.get() && resumed.contains(a) && !a.isFinishing()
                            && w != null && w.getDecorView().hasWindowFocus()) {
                        promptGate(a, gate, biometricTitle, succeed);
                    }
                };
            }
            promptGate(a, gate, biometricTitle, succeed);
        }
        return true;
    }

    /** Shows the gate's biometric prompt, a new one in place of any earlier one. */
    private static void promptGate(Activity a, Gate gate, String title, Runnable succeed) {
        gate.biometric.cancel(); // ends an earlier prompt Android 11 closed without a word
        gate.biometric = new android.os.CancellationSignal();
        int id = ++gate.promptId;
        gate.promptedAt = android.os.SystemClock.uptimeMillis();
        gate.promptOpen = true;
        gate.focusLost = false;
        promptBiometric(a, title, gate.biometric, succeed, byUser -> {
            if (gate.promptId != id || !gate.promptOpen) return;
            gate.promptOpen = false;
            if (byUser) repromptAfterStrayTap(gate);
        });
    }

    /** A gesture gate's prompt the user closed this soon was hit by a tap of the gesture: once more. */
    private static void repromptAfterStrayTap(Gate gate) {
        Runnable reprompt = gate.reprompt;
        if (reprompt == null || gate.completed.get()
                || android.os.SystemClock.uptimeMillis() - gate.promptedAt > STRAY_TAP_MS) return;
        gate.reprompt = null;
        ModuleLog.detail("(IE|LockDMs) biometric prompt closed right away: shown again if the gate is still in front");
        // A moment later, once focus has settled: back on the gate after a stray tap, elsewhere
        // after Home or Recents.
        main.postDelayed(reprompt, REPROMPT_SETTLE_MS);
    }

    /** True when the device has biometric hardware with something enrolled. */
    @SuppressLint("MissingPermission") // runs in Instagram, which holds USE_BIOMETRIC
    static boolean canAuthenticate(Activity a) {
        try {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return false;
            android.hardware.biometrics.BiometricManager bm =
                    (android.hardware.biometrics.BiometricManager) a.getSystemService(Activity.BIOMETRIC_SERVICE);
            if (bm == null) return false;
            int res;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                // No-arg canAuthenticate() is deprecated on R+ and can misreport; pass authenticators.
                res = bm.canAuthenticate(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK);
            } else {
                res = bm.canAuthenticate();
            }
            return res == android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Biometric prompt for callers without a gate of their own (LockedCollectionsHook). */
    @SuppressLint("MissingPermission") // runs in Instagram, which holds USE_BIOMETRIC
    static void promptBiometric(Activity a, Runnable onSuccess) {
        promptBiometric(a, I18n.t(a, R.string.ig_lock_biometric_title), new android.os.CancellationSignal(), onSuccess, null);
    }

    /**
     * Shows the system biometric prompt; runs onSuccess on the UI thread when auth succeeds, and
     * onClosed when it ends without: true when the user closed it (a tap outside it, or back),
     * false for "Use passcode" or an error such as too many tries.
     */
    @SuppressLint("MissingPermission") // runs in Instagram, which holds USE_BIOMETRIC
    private static void promptBiometric(Activity a, String title, android.os.CancellationSignal cancellation,
                                        Runnable onSuccess, java.util.function.Consumer<Boolean> onClosed) {
        try {
            // I18n reads the module's own resources: a.getString(R.string...) would look in
            // Instagram's table, where our ids mean nothing.
            android.hardware.biometrics.BiometricPrompt prompt =
                    new android.hardware.biometrics.BiometricPrompt.Builder(a)
                            .setTitle(title)
                            .setDescription(I18n.t(a, R.string.ig_lock_biometric_description))
                            .setNegativeButton(I18n.t(a, R.string.ig_lock_biometric_use_passcode), a.getMainExecutor(), (d, w) -> {
                                if (onClosed != null) onClosed.accept(false);
                            })
                            .build();
            prompt.authenticate(cancellation, a.getMainExecutor(),
                    new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                        @Override public void onAuthenticationSucceeded(
                                android.hardware.biometrics.BiometricPrompt.AuthenticationResult result) {
                            a.runOnUiThread(onSuccess);
                        }

                        @Override public void onAuthenticationError(int errorCode, CharSequence errString) {
                            if (onClosed != null) a.runOnUiThread(() -> onClosed.accept(errorCode
                                    == android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED));
                        }
                    });
        } catch (Throwable t) {
            ModuleLog.warn("(IE|LockDMs) Could not show the fingerprint prompt: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    static int dp(Activity a, int v) { return Math.round(v * a.getResources().getDisplayMetrics().density); }

    /** Dismiss the soft keyboard after a successful unlock so it doesn't linger over the content. */
    static void hideKeyboard(Activity a, View focus) {
        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) a.getSystemService(Activity.INPUT_METHOD_SERVICE);
            if (imm != null && focus != null) imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
        } catch (Throwable ignored) {}
    }

    /** SHA-256 hex of the input. */
    public static String sha256(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) { return s; }
    }

    /** Hash for a NEW passcode: PBKDF2 with the per-install salt (see {@link PasscodeHasher}). */
    public static String hashPass(String passcode) {
        String salt = FeatureFlags.lockDirectSalt == null ? "" : FeatureFlags.lockDirectSalt;
        return PasscodeHasher.hash(passcode, salt);
    }

    private static final int MAX_FAILURES = 5;
    private static final long LOCKOUT_MS = 30_000;
    private static int failures;
    private static long lockedUntil;

    /** Seconds left in the lockout after too many wrong passcodes, or 0. */
    public static int lockoutSecondsLeft() {
        long left = lockedUntil - android.os.SystemClock.elapsedRealtime();
        return left > 0 ? (int) ((left + 999) / 1000) : 0;
    }

    /**
     * Checks a passcode against the stored hash (PBKDF2, or the older salted SHA-256, which is
     * re-hashed with PBKDF2 on the first successful unlock). Five wrong tries lock entry for 30 s;
     * during the lockout every attempt fails without being checked.
     */
    public static synchronized boolean checkPass(String passcode) {
        if (lockoutSecondsLeft() > 0) return false;
        String salt = FeatureFlags.lockDirectSalt == null ? "" : FeatureFlags.lockDirectSalt;
        if (PasscodeHasher.verify(passcode, salt, FeatureFlags.lockDirectPasscode)) {
            failures = 0;
            if (PasscodeHasher.needsUpgrade(FeatureFlags.lockDirectPasscode)) {
                FeatureFlags.lockDirectPasscode = PasscodeHasher.hash(passcode, salt);
                ps.reso.instaeclipse.utils.core.SettingsManager.saveAllFlags();
            }
            return true;
        }
        if (++failures >= MAX_FAILURES) {
            failures = 0;
            lockedUntil = android.os.SystemClock.elapsedRealtime() + LOCKOUT_MS;
        }
        return false;
    }

    /** Message for a failed attempt: the lockout countdown when one is running. */
    public static String failureText(android.content.Context ctx) {
        int s = lockoutSecondsLeft();
        return s > 0 ? I18n.t(ctx, R.string.ig_lock_try_again_in, s) : I18n.t(ctx, R.string.ig_lock_wrong_passcode);
    }

    /** Generate + store a fresh random salt (call right before hashing a NEW passcode). */
    public static void newSalt() {
        byte[] b = new byte[16];
        new java.security.SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        FeatureFlags.lockDirectSalt = sb.toString();
    }
}
