package ps.reso.instaeclipse.mods.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
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

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.core.PasscodeCheck;
import ps.reso.instaeclipse.utils.core.PasscodeHasher;
import ps.reso.instaeclipse.utils.core.PasscodeLockout;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.toast.IeToast;
import ps.reso.instaeclipse.utils.ui.IeColors;
import ps.reso.instaeclipse.utils.ui.IePalette;

public class LockDirectMessagesHook {
    private static final String LOCKOUT_PREFS = "instaeclipse_lockout";
    private static final DirectLockSession session = new DirectLockSession();
    private static final java.util.Map<Activity, Gate> gates = new java.util.WeakHashMap<>();
    private static final java.util.Set<Activity> started =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private static final java.util.Set<Activity> resumed =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private static final java.util.Map<Activity, Boolean> recentsHidden = new java.util.WeakHashMap<>();
    private static final java.util.Map<View, Watch> watched = new java.util.WeakHashMap<>();
    private static boolean lifecycleRegistered;
    private static boolean appUnlocked;
    private static boolean settingsSeen, seenWholeApp, seenDirect, seenSpecific, seenAlways, seenPasscode;
    private static String seenThreads, seenSalt;
    private static String currentAccount;
    private static int inboxId, listId, threadId;
    private static long outsideDirectSince;
    private static final long STRAY_TAP_MS = 1500L;
    private static final long CLOSE_REPORT_MS = 300L;
    private static final long REPROMPT_SETTLE_MS = 200L;

    private static final class Gate {
        final android.app.Dialog dialog;
        final String scope;
        final java.util.concurrent.atomic.AtomicBoolean completed =
                new java.util.concurrent.atomic.AtomicBoolean();
        android.os.CancellationSignal biometric = new android.os.CancellationSignal();
        int promptId;
        long promptedAt;
        boolean promptOpen, focusLost;
        Runnable reprompt;

        Gate(android.app.Dialog dialog, String scope) {
            this.dialog = dialog;
            this.scope = scope;
        }
    }

    static final class GateThrottle {
        static final long INTERVAL_MS = 100L;
        static final long SETTLE_MS = 500L;
        private long lastCheck = -INTERVAL_MS;
        private long settleUntil;
        private boolean skipped;
        private boolean posted;

        void laidOut(long now) { settleUntil = now + SETTLE_MS; }

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

        boolean trailingDue(long now) {
            posted = false;
            if (!skipped) return false;
            lastCheck = now;
            skipped = false;
            return true;
        }
    }

    private static final class Watch implements android.view.ViewTreeObserver.OnGlobalLayoutListener,
            android.view.ViewTreeObserver.OnPreDrawListener, Runnable {
        final java.lang.ref.WeakReference<Activity> activity;
        final GateThrottle throttle = new GateThrottle();

        Watch(Activity activity) {
            this.activity = new java.lang.ref.WeakReference<>(activity);
        }

        @Override public void onGlobalLayout() {
            throttle.laidOut(android.os.SystemClock.uptimeMillis());
        }

        @Override public boolean onPreDraw() {
            try {
                Activity a = activity.get();
                if (a != null && resumed.contains(a)) {
                    long delay = throttle.preDraw(android.os.SystemClock.uptimeMillis());
                    if (delay == 0L) checkGate(a);
                    else if (delay > 0L) main().postDelayed(this, delay);
                }
            } catch (Throwable t) {
                ModuleLog.warn("(IE|LockDMs) Could not check whether to show the lock: "
                        + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
            return true;
        }

        @Override public void run() {
            try {
                Activity a = activity.get();
                if (throttle.trailingDue(android.os.SystemClock.uptimeMillis()) && a != null
                        && resumed.contains(a)) checkGate(a);
            } catch (Throwable t) {
                ModuleLog.warn("(IE|LockDMs) Could not check whether to show the lock: "
                        + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
    }

    public static void watchActivity(Activity activity) {
        if (activity == null) return;
        activity.runOnUiThread(() -> {
            registerLifecycle(activity);
            bindLockout(activity);
            started.add(activity);
            resumed.add(activity);
            ensureIds(activity);
            if (locksOn()) arm(activity);
            checkGate(activity);
            boolean passcodeSet = FeatureFlags.lockDirectPasscode != null && !FeatureFlags.lockDirectPasscode.isEmpty();
            if (passcodeSet && FeatureFlags.lockWholeApp) FeatureStatusTracker.setHooked("LockWholeApp");
            if (passcodeSet && FeatureFlags.lockDirectMessages) FeatureStatusTracker.setHooked("LockDirectMessages");
        });
    }

    public static void locksChanged() {
        main().post(() -> {
            for (Activity a : new java.util.ArrayList<>(resumed)) {
                if (a.isFinishing() || a.isDestroyed()) continue;
                if (locksOn()) arm(a);
                checkGate(a);
            }
        });
    }

    private static boolean locksOn() {
        return FeatureFlags.lockWholeApp || FeatureFlags.lockDirectMessages || FeatureFlags.lockSpecificMessages;
    }

    private static void arm(Activity a) {
        View decor = a.getWindow().getDecorView();
        if (watched.containsKey(decor)) return;
        Watch watch = new Watch(a);
        android.view.ViewTreeObserver observer = decor.getViewTreeObserver();
        observer.addOnGlobalLayoutListener(watch);
        observer.addOnPreDrawListener(watch);
        watched.put(decor, watch);
    }

    private static void disarm(Activity a) {
        View decor = a.getWindow() != null ? a.getWindow().peekDecorView() : null;
        Watch watch = decor != null ? watched.remove(decor) : null;
        if (watch == null) return;
        android.view.ViewTreeObserver observer = decor.getViewTreeObserver();
        observer.removeOnGlobalLayoutListener(watch);
        observer.removeOnPreDrawListener(watch);
        main().removeCallbacks(watch);
    }

    static boolean appUnlocked() {
        return appUnlocked;
    }

    static void graceChecked(Activity a, boolean passes) {
        if (passes) appUnlocked = true;
        if (resumed.contains(a) && !a.isFinishing() && !a.isDestroyed()) checkGate(a);
    }

    public static boolean locksNeedPasscode() {
        return ps.reso.instaeclipse.utils.core.PasscodeRules.locksNeedPasscode();
    }

    private static void registerLifecycle(Activity seed) {
        if (lifecycleRegistered) return;
        lifecycleRegistered = true;
        seed.getApplication().registerActivityLifecycleCallbacks(new android.app.Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(Activity a) { started.add(a); }
            @Override public void onActivityResumed(Activity a) {
                LockGrace.appResumed(a);
                watchActivity(a);
            }
            @Override public void onActivityPaused(Activity a) { resumed.remove(a); }
            @Override public void onActivityStopped(Activity a) {
                started.remove(a);
                dismissGate(a);
                if (!started.isEmpty() || a.isChangingConfigurations()) return;
                appUnlocked = false;
                LockGrace.appBackground();
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
        if (!locksOn() && !gates.containsKey(a)) {
            session.reset();
            appUnlocked = false;
            hideRecents(a, false);
            disarm(a);
            return;
        }
        try {
            boolean passcodeSet = FeatureFlags.lockDirectPasscode != null && !FeatureFlags.lockDirectPasscode.isEmpty();
            boolean appChanged = !settingsSeen || seenPasscode != passcodeSet
                    || !java.util.Objects.equals(seenSalt, FeatureFlags.lockDirectSalt)
                    || seenWholeApp != FeatureFlags.lockWholeApp;
            boolean changed = appChanged || seenDirect != FeatureFlags.lockDirectMessages
                    || seenSpecific != FeatureFlags.lockSpecificMessages || seenAlways != FeatureFlags.lockDirectAlways
                    || !java.util.Objects.equals(seenThreads, FeatureFlags.lockedDirectThreads);
            if (changed) {
                settingsSeen = true;
                seenPasscode = passcodeSet;
                seenSalt = FeatureFlags.lockDirectSalt;
                seenWholeApp = FeatureFlags.lockWholeApp;
                seenDirect = FeatureFlags.lockDirectMessages;
                seenSpecific = FeatureFlags.lockSpecificMessages;
                seenAlways = FeatureFlags.lockDirectAlways;
                seenThreads = FeatureFlags.lockedDirectThreads;
                session.reset();
            }
            if (appChanged) appUnlocked = false;
            DirectLockSession.Screen screen = screen(a);
            String id = screen == DirectLockSession.Screen.THREAD
                    ? ps.reso.instaeclipse.mods.ghost.HideChatsHook.currentThreadId(a) : null;
            String account = ps.reso.instaeclipse.mods.ghost.HideChatsHook.currentAccount(a);
            if (account != null && !account.equals(currentAccount)) {
                if (currentAccount != null) appUnlocked = false;
                currentAccount = account;
                Gate open = gates.get(a);
                if (open == null || !(open.scope.equals("hidden") || open.scope.equals("settings"))) dismissGate(a);
            }
            if (screen == DirectLockSession.Screen.OTHER) {
                long now = android.os.SystemClock.elapsedRealtime();
                if (outsideDirectSince == 0L) {
                    outsideDirectSince = now;
                    main().postDelayed(() -> { if (resumed.contains(a)) checkGate(a); }, 250L);
                }
                if (now - outsideDirectSince < 250L) screen = DirectLockSession.Screen.UNKNOWN;
            } else if (screen != DirectLockSession.Screen.UNKNOWN) outsideDirectSince = 0L;
            session.visit(screen, id, account, FeatureFlags.lockDirectAlways);
            boolean chatLocked = FeatureFlags.lockSpecificMessages && screen == DirectLockSession.Screen.THREAD
                    && (id == null ? !ps.reso.instaeclipse.utils.ghost.LockedThreads.isEmpty()
                    : ps.reso.instaeclipse.utils.ghost.LockedThreads.contains(id));
            hideRecents(a, LockRecentsPolicy.hides(android.os.Build.VERSION.SDK_INT, passcodeSet,
                    FeatureFlags.lockWholeApp, FeatureFlags.lockDirectMessages, FeatureFlags.lockSpecificMessages,
                    screen, chatLocked, recentsHidden(a)));
            if (!passcodeSet) {
                dismissGate(a);
                return;
            }
            String scope = null;
            String title = null;
            if (FeatureFlags.lockWholeApp && !appUnlocked) {
                if (LockGrace.holds(a)) return;
                scope = "app";
                title = I18n.t(a, R.string.ig_lock_open_app_prompt);
            } else if (session.needsInboxLock(screen, FeatureFlags.lockDirectMessages)) {
                scope = "inbox";
                title = I18n.t(a, R.string.ig_lock_open_dms_prompt);
            } else if (!FeatureFlags.lockDirectMessages && FeatureFlags.lockSpecificMessages
                    && session.needsThreadLock(screen, id, chatLocked)) {
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
            showAuthenticationOverlay(a, title, () -> {
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

    private static boolean recentsHidden(Activity a) {
        return Boolean.TRUE.equals(recentsHidden.get(a));
    }

    private static void hideRecents(Activity a, boolean hide) {
        if (recentsHidden(a) == hide) return;
        recentsHidden.put(a, hide);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) a.setRecentsScreenshotEnabled(!hide);
    }

    private static android.graphics.drawable.Drawable lockIcon(Activity a, int tint) {
        android.graphics.drawable.Drawable icon = ps.reso.instaeclipse.utils.dialog.IeDialog.igIcon(a,
                "instagram_lock_pano_outline_24", tint);
        return icon != null ? icon : ps.reso.instaeclipse.utils.core.ModuleResources.icon(a, R.drawable.ic_shield, tint);
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

    public static boolean authenticate(Activity a, String title, String scope, Runnable onSuccess) {
        if (a == null || onSuccess == null) return false;
        if (FeatureFlags.lockDirectPasscode == null || FeatureFlags.lockDirectPasscode.isEmpty()) {
            IeToast.makeText(a, I18n.t(a, R.string.ig_lock_set_passcode_first), Toast.LENGTH_SHORT).show();
            return false;
        }
        return showAuthenticationOverlay(a, title, onSuccess, scope,
                () -> resumed.contains(a) && !a.isFinishing(), () -> {});
    }

    private static boolean showAuthenticationOverlay(Activity a, String titleText,
                                                     Runnable onSuccess, String scope,
                                                     java.util.function.BooleanSupplier valid,
                                                     Runnable onCancel) {
        if (gates.containsKey(a) || a.isFinishing() || a.isDestroyed()) return false;
        bindLockout(a);
        arm(a);
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
                onCancel.run();
            }

            @Override public void onWindowFocusChanged(boolean hasFocus) {
                super.onWindowFocusChanged(hasFocus);
                Gate active = gates.get(a);
                if (active == null || active.dialog != this || !active.promptOpen) return;
                if (!hasFocus) {
                    active.focusLost = true;
                    return;
                }
                if (!active.focusLost) return;
                int id = active.promptId;
                main().postDelayed(() -> {
                    if (gates.get(a) != active || active.promptId != id || !active.promptOpen) return;
                    active.promptOpen = false;
                    repromptAfterStrayTap(active);
                }, CLOSE_REPORT_MS);
            }
        };
        Gate gate = new Gate(dialog, scope);
        IePalette palette = IePalette.of(a);
        FrameLayout overlay = new FrameLayout(a);
        overlay.setClickable(true);
        overlay.setBackgroundColor(palette.backdrop);
        overlay.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));

        android.widget.ScrollView scroller = new android.widget.ScrollView(a);
        FrameLayout.LayoutParams svLp = new FrameLayout.LayoutParams(-1, -1);
        scroller.setLayoutParams(svLp);
        scroller.setFillViewport(true);

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(a, 28);
        col.setPadding(pad, pad, pad, pad);
        android.graphics.drawable.GradientDrawable cardBg = new android.graphics.drawable.GradientDrawable();
        cardBg.setColor(palette.dialog);
        cardBg.setCornerRadius(dp(a, 22));
        cardBg.setStroke(Math.max(1, dp(a, 1)), palette.line);
        col.setBackground(cardBg);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(dp(a, 300), -2);
        clp.gravity = Gravity.CENTER;
        clp.topMargin = dp(a, 40);
        clp.bottomMargin = dp(a, 40);
        col.setLayoutParams(clp);

        android.widget.ImageView lock = new android.widget.ImageView(a);
        lock.setImageDrawable(lockIcon(a, palette.text));
        lock.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        lock.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams lockLp = new LinearLayout.LayoutParams(dp(a, 40), dp(a, 40));
        lockLp.gravity = Gravity.CENTER_HORIZONTAL;
        lockLp.bottomMargin = dp(a, 12);
        lock.setLayoutParams(lockLp);

        TextView title = new TextView(a);
        title.setText(titleText);
        title.setTextColor(palette.text);
        title.setTextSize(17);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(a, 18));

        final EditText code = new EditText(a);
        code.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        code.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        code.setSingleLine(true);
        code.setHint(I18n.t(a, R.string.ig_locked_coll_hint));
        code.setTextColor(palette.text);
        code.setHintTextColor(palette.sub);
        code.setGravity(Gravity.CENTER);
        code.setTextSize(20);
        code.setLetterSpacing(0.15f);
        code.setEms(6);
        code.setMinWidth(0);
        code.setMinimumWidth(0);
        android.graphics.drawable.GradientDrawable inBg = new android.graphics.drawable.GradientDrawable();
        inBg.setColor(palette.field);
        inBg.setCornerRadius(dp(a, 12));
        code.setBackground(inBg);
        int ip = dp(a, 12);
        code.setPadding(ip, dp(a, 10), ip, dp(a, 10));
        LinearLayout.LayoutParams codeLp = new LinearLayout.LayoutParams(-2, -2);
        codeLp.gravity = Gravity.CENTER_HORIZONTAL;
        code.setLayoutParams(codeLp);

        final TextView error = new TextView(a);
        error.setTextColor(IeColors.destructive(a, palette.dialog));
        error.setTextSize(13);
        error.setGravity(Gravity.CENTER);
        error.setPadding(0, dp(a, 8), 0, 0);
        error.setVisibility(View.GONE);

        Button unlock = new Button(a);
        unlock.setText(I18n.t(a, R.string.ig_lock_unlock));
        unlock.setAllCaps(false);
        unlock.setTextColor(palette.onAccent);
        unlock.setTextSize(16);
        android.graphics.drawable.GradientDrawable btnBg = new android.graphics.drawable.GradientDrawable();
        btnBg.setColor(palette.accent);
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
        final PasscodeCheck.Prompt checking = new PasscodeCheck.Prompt(
                () -> gates.get(a) == gate && !gate.completed.get(), busy -> PasscodeCheck.dim(unlock, busy));
        final Runnable attempt = () -> checking.check(code.getText().toString(), ok -> {
            if (ok) {
                succeed.run();
            } else {
                code.setText("");
                error.setText(failureText(a));
                error.setVisibility(View.VISIBLE);
            }
        });
        unlock.setOnClickListener(v -> attempt.run());
        code.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { attempt.run(); return true; }
            return false;
        });

        col.addView(lock);
        col.addView(title);
        col.addView(code);
        col.addView(error);
        col.addView(unlock);
        col.addView(forgotLink(a, palette, () -> {
            if (gates.get(a) != gate) return;
            hideKeyboard(a, code);
            dismissGate(a);
        }));
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
        window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(palette.backdrop));
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

        if (FeatureFlags.lockUseFingerprint && canAuthenticate(a)) {
            if (scope.equals("hidden")) {
                gate.reprompt = () -> {
                    android.view.Window w = dialog.getWindow();
                    if (gates.get(a) == gate && !gate.completed.get() && resumed.contains(a) && !a.isFinishing()
                            && w != null && w.getDecorView().hasWindowFocus()) {
                        promptGate(a, gate, titleText, succeed);
                    }
                };
            }
            promptGate(a, gate, titleText, succeed);
        }
        return true;
    }

    private static void promptGate(Activity a, Gate gate, String title, Runnable succeed) {
        gate.biometric.cancel();
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

    private static void repromptAfterStrayTap(Gate gate) {
        Runnable reprompt = gate.reprompt;
        if (reprompt == null || gate.completed.get()
                || android.os.SystemClock.uptimeMillis() - gate.promptedAt > STRAY_TAP_MS) return;
        gate.reprompt = null;
        ModuleLog.detail("(IE|LockDMs) biometric prompt closed right away: shown again if the gate is still in front");
        main().postDelayed(reprompt, REPROMPT_SETTLE_MS);
    }

    @SuppressLint("MissingPermission")
    static boolean canAuthenticate(Activity a) {
        try {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return false;
            android.hardware.biometrics.BiometricManager bm =
                    (android.hardware.biometrics.BiometricManager) a.getSystemService(Activity.BIOMETRIC_SERVICE);
            if (bm == null) return false;
            int res;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                res = bm.canAuthenticate(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK);
            } else {
                res = bm.canAuthenticate();
            }
            return res == android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS;
        } catch (Throwable t) {
            return false;
        }
    }

    @SuppressLint("MissingPermission")
    static void promptBiometric(Activity a, String title, Runnable onSuccess) {
        promptBiometric(a, title, new android.os.CancellationSignal(), onSuccess, null);
    }

    @SuppressLint("MissingPermission")
    private static void promptBiometric(Activity a, String title, android.os.CancellationSignal cancellation,
                                        Runnable onSuccess, java.util.function.Consumer<Boolean> onClosed) {
        try {
            android.hardware.biometrics.BiometricPrompt prompt =
                    new android.hardware.biometrics.BiometricPrompt.Builder(a)
                            .setTitle(title)
                            .setDescription(I18n.t(a, R.string.ig_lock_biometric_confirm))
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

    static TextView forgotLink(Activity a, IePalette palette, Runnable onRecovered) {
        TextView link = new TextView(a);
        link.setText(I18n.t(a, R.string.ig_lock_forgot));
        link.setTextColor(IePalette.readable(palette.accent, palette.dialog, IePalette.TEXT));
        link.setTextSize(14);
        link.setGravity(Gravity.CENTER);
        int p = dp(a, 12);
        link.setPadding(p, 0, p, 0);
        link.setMinHeight(dp(a, 48));
        link.setMinWidth(dp(a, 48));
        link.setOnClickListener(v -> forgotPasscode(a, onRecovered));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.topMargin = dp(a, 6);
        link.setLayoutParams(lp);
        return link;
    }

    static void forgotPasscode(Activity a, Runnable onRecovered) {
        boolean secure = false;
        try {
            android.app.KeyguardManager keyguard = a.getSystemService(android.app.KeyguardManager.class);
            secure = keyguard != null && keyguard.isDeviceSecure();
        } catch (Throwable t) {
            ModuleLog.warn("(IE|LockDMs) Could not check for a screen lock: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        showRecovery(a, PasscodeRecovery.path(android.os.Build.VERSION.SDK_INT, secure), onRecovered);
    }

    static void showRecovery(Activity a, PasscodeRecovery.Path path, Runnable onRecovered) {
        ps.reso.instaeclipse.utils.dialog.IeDialog d = new ps.reso.instaeclipse.utils.dialog.IeDialog(a)
                .title(I18n.t(a, R.string.ig_lock_forgot));
        if (PasscodeRecovery.asksForCredential(path)) {
            d.message(I18n.t(a, R.string.ig_lock_forgot_explainer));
            d.secondary(I18n.t(a, R.string.ig_dialog_cancel), null);
            d.primary(I18n.t(a, R.string.ig_lock_forgot_continue), () -> confirmCredential(a, path, onRecovered));
        } else {
            d.message(I18n.t(a, path == PasscodeRecovery.Path.NEEDS_ANDROID_11
                    ? R.string.ig_lock_forgot_needs_android_11 : R.string.ig_lock_forgot_needs_screen_lock)
                    + "\n\n" + I18n.t(a, R.string.ig_lock_forgot_last_resort));
            d.primary(I18n.t(a, R.string.ig_dialog_ok), null);
        }
        d.show();
    }

    @SuppressLint("MissingPermission")
    private static void confirmCredential(Activity a, PasscodeRecovery.Path path, Runnable onRecovered) {
        if (path != PasscodeRecovery.Path.DEVICE_CREDENTIAL
                || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return;
        try {
            new android.hardware.biometrics.BiometricPrompt.Builder(a)
                    .setTitle(I18n.t(a, R.string.ig_lock_forgot_prompt_title))
                    .setDescription(I18n.t(a, R.string.ig_lock_forgot_prompt_body))
                    .setAllowedAuthenticators(
                            android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                    .build().authenticate(new android.os.CancellationSignal(), a.getMainExecutor(),
                    new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                        @Override public void onAuthenticationSucceeded(
                                android.hardware.biometrics.BiometricPrompt.AuthenticationResult result) {
                            a.runOnUiThread(() -> recoveryEnded(a, PasscodeRecovery.Outcome.RECOVERED, null, onRecovered));
                        }

                        @Override public void onAuthenticationError(int errorCode, CharSequence errString) {
                            a.runOnUiThread(() -> recoveryEnded(a, PasscodeRecovery.error(errorCode), errString, onRecovered));
                        }
                    });
        } catch (Throwable t) {
            ModuleLog.warn("(IE|LockDMs) Could not ask for the screen lock: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void recoveryEnded(Activity a, PasscodeRecovery.Outcome outcome, CharSequence error,
                                      Runnable onRecovered) {
        if (PasscodeRecovery.removesPasscode(outcome)) {
            recovered(a, onRecovered);
        } else if (outcome == PasscodeRecovery.Outcome.NEEDS_SCREEN_LOCK) {
            showRecovery(a, PasscodeRecovery.Path.NEEDS_SCREEN_LOCK, onRecovered);
        } else if (outcome == PasscodeRecovery.Outcome.FAILED && error != null && error.length() > 0) {
            IeToast.makeText(a, error, Toast.LENGTH_SHORT).show();
        }
    }

    private static void recovered(Activity a, Runnable onRecovered) {
        ps.reso.instaeclipse.utils.dialog.LockFlows.removePasscode();
        ps.reso.instaeclipse.utils.core.SettingsManager.saveAllFlags();
        lockout().reset();
        locksChanged();
        if (onRecovered != null) onRecovered.run();
        ps.reso.instaeclipse.utils.dialog.IeDialog d = new ps.reso.instaeclipse.utils.dialog.IeDialog(a)
                .title(I18n.t(a, R.string.ig_lock_recovered_title))
                .message(I18n.t(a, R.string.ig_lock_recovered_body));
        d.secondary(I18n.t(a, R.string.ig_lock_not_now), null);
        d.primary(I18n.t(a, R.string.ig_lock_set_new_passcode),
                () -> ps.reso.instaeclipse.utils.dialog.LockFlows.setPasscode(a, null, null));
        d.show();
    }

    static int dp(Activity a, int v) { return Math.round(v * a.getResources().getDisplayMetrics().density); }

    static void hideKeyboard(Activity a, View focus) {
        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) a.getSystemService(Activity.INPUT_METHOD_SERVICE);
            if (imm != null && focus != null) imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
        } catch (Throwable ignored) {}
    }

    public static String hashPass(String passcode) {
        String salt = FeatureFlags.lockDirectSalt == null ? "" : FeatureFlags.lockDirectSalt;
        return PasscodeHasher.hash(passcode, salt);
    }

    private static volatile PasscodeLockout lockout;

    private static PasscodeLockout lockout() {
        PasscodeLockout bound = lockout;
        if (bound != null) return bound;
        synchronized (LockDirectMessagesHook.class) {
            if (lockout == null) bindLockout(ps.reso.instaeclipse.Xposed.Module.appContext());
            if (lockout == null) lockout = new PasscodeLockout(null, android.os.SystemClock::elapsedRealtime);
            return lockout;
        }
    }

    static void bindLockout(android.content.Context context) {
        if (lockout != null || context == null) return;
        synchronized (LockDirectMessagesHook.class) {
            if (lockout != null) return;
            android.content.Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
            lockout = new PasscodeLockout(app.getSharedPreferences(LOCKOUT_PREFS, android.content.Context.MODE_PRIVATE),
                    android.os.SystemClock::elapsedRealtime);
        }
    }

    public static int lockoutSecondsLeft() {
        return lockout().secondsLeft();
    }

    public static synchronized boolean checkPass(String passcode) {
        PasscodeLockout lockout = lockout();
        if (lockout.secondsLeft() > 0) return false;
        String salt = FeatureFlags.lockDirectSalt == null ? "" : FeatureFlags.lockDirectSalt;
        if (PasscodeHasher.verify(passcode, salt, FeatureFlags.lockDirectPasscode)) {
            lockout.succeeded();
            if (PasscodeHasher.needsUpgrade(FeatureFlags.lockDirectPasscode)) {
                FeatureFlags.lockDirectPasscode = PasscodeHasher.hash(passcode, salt);
                ps.reso.instaeclipse.utils.core.SettingsManager.saveAllFlags();
            }
            return true;
        }
        lockout.failed();
        return false;
    }

    public static String failureText(android.content.Context ctx) {
        int s = lockoutSecondsLeft();
        return s > 0 ? I18n.t(ctx, R.string.ig_lock_try_again_in, s) : I18n.t(ctx, R.string.ig_lock_wrong_passcode);
    }

    public static void newSalt() {
        FeatureFlags.lockDirectSalt = freshSalt();
    }

    public static String freshSalt() {
        byte[] b = new byte[16];
        new java.security.SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static android.os.Handler main() {
        return MainThread.HANDLER;
    }

    private static final class MainThread {
        static final android.os.Handler HANDLER = new android.os.Handler(android.os.Looper.getMainLooper());
    }
}
