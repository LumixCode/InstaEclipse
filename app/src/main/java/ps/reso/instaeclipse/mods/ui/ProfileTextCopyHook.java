package ps.reso.instaeclipse.mods.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.widget.TextView;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.HookFrames;
import ps.reso.instaeclipse.utils.core.JitGuard;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.text.CopyFeedback;
import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;

/**
 * Hold a profile's username or bio to copy it (profileMenuTools; this replaced the ⋮ menu's Copy
 * username / Copy bio rows). The copy feedback is the caption's: the hold's haptic tick, the
 * clipboard, and a toast only before Android 13 (CopyFeedback).
 *
 * <p>Username: a profile's action bar is com.instagram.profile.actionbar.ProfileActionBar (R8
 * keeps that name; its only void method taking an Activity, A00 on 451, binds it). The title
 * (action_bar_title) sits in action_bar_username_container, the part of the bar that takes taps:
 * its binder ("ProfileActionBarViewBinder", 0Abv.A04 on 451) gives the container a click listener
 * (the account switcher on your own profile) and nothing sets a long-click listener on it. So a
 * long-click listener goes on the container on every bind; a tap still does what it did, and a
 * handled long click skips the tap and plays the View's own haptic tick.
 *
 * <p>Bio: the header's category, bio, links and address are one Compose view,
 * profile_user_info_compose_view ("ProfileUserInfoViewBinder.setupSharedComposeView"). The bio in
 * it (ProfileBioComponent / ProfileExpandableText) is a clickable box (it expands "… more") around
 * text with @mention and #hashtag links; those composables only use single-click modifiers on 451,
 * none with a long press. Touches on that view's AndroidComposeView are watched, as
 * CaptionCopyHook watches captions. When a press is held, the accessibility nodes under the finger
 * (Compose has no views to look at) tell whether it is on the bio: their text has to show the bio
 * of the profile's User model (ProfileMenuHook's "biography" getter) or, if the model has none or
 * it isn't what is shown, the bio the header was built with (the ProfileBioComponent UI state, as
 * in ProfileSpoofHook). That full bio is copied and the release becomes a cancel, so it neither
 * opens a link nor expands the bio. A tap, or a hold anywhere else, goes through untouched.
 */
public final class ProfileTextCopyHook {

    private static final String TAG = "(IE|ProfileCopy) ";
    private static final String ACTION_BAR = "com.instagram.profile.actionbar.ProfileActionBar";
    private static final String COMPOSE_VIEW = "androidx.compose.ui.platform.AndroidComposeView";
    private static final String BIO_COMPOSABLE = "ProfileBioComponent (ProfileBioComponent.kt";
    private static final String BIO_STATE_KEY = "ProfileTextCopyBioState";
    private static final String RESTORE_UP = "ie_profile_copy_restore_up";
    private static final int MAX_NODES = 400;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static int titleId = -1, containerId, userInfoId = -1;
    private static Method childIdMethod;

    // One hold at a time; touch events arrive on the main thread.
    private static WeakReference<View> pressed = new WeakReference<>(null);
    private static Runnable pendingCopy;
    private static boolean copied;
    private static float downX, downY;

    /** Bios profile headers were built with, by username, most recently built last. */
    private static final Map<String, String> HEADER_BIOS = new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 16;
        }
    };

    public void install(DexKitBridge bridge, ClassLoader cl) {
        int title = hookTitle(cl);
        boolean bio = hookBioTouches(cl);
        String header = hookHeaderBios(bridge, cl);
        if (title > 0 || bio) FeatureStatusTracker.setHooked("ProfileMenuTools");
        if (title > 0 && bio) ModuleLog.ok(TAG + "hold to copy ready");
        else if (title > 0) ModuleLog.warn(TAG + "Hold to copy works for the username only; copying the bio is not available in this Instagram version");
        else if (bio) ModuleLog.warn(TAG + "Hold to copy works for the bio only; copying the username is not available in this Instagram version");
        else ModuleLog.error(TAG + "Hold to copy will not work in this Instagram version");
        ModuleLog.detail(TAG + "hold-to-copy: username bind hooks=" + title
                + ", bio touches=" + bio + ", header bio=" + header);
    }

    // ── Username ─────────────────────────────────────────────────────────────

    private static int hookTitle(ClassLoader cl) {
        try {
            Class<?> bar = XposedHelpers.findClass(ACTION_BAR, cl);
            XC_MethodHook bind = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    JitGuard.tick(param.method);
                    if (param.thisObject instanceof View v) attachTitle(v);
                }
            };
            int hooked = 0;
            for (Method m : bar.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers()) || m.getReturnType() != void.class
                        || !Arrays.asList(m.getParameterTypes()).contains(Activity.class)) continue;
                XposedBridge.hookMethod(m, bind);
                hooked++;
            }
            if (hooked == 0) ModuleLog.detail(TAG + "username: no bind method on " + ACTION_BAR);
            return hooked;
        } catch (Throwable t) {
            ModuleLog.detail(TAG + "username: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return 0;
        }
    }

    /** Title containers that have our listener. */
    private static final Set<View> TITLES = Collections.newSetFromMap(new java.util.WeakHashMap<>());

    /** Puts the hold listener on the bar's title container (or takes it off when the feature is off). */
    @SuppressLint("DiscouragedApi")
    private static void attachTitle(View bar) {
        try {
            if (titleId == -1) {
                String pkg = bar.getContext().getPackageName();
                containerId = bar.getResources().getIdentifier("action_bar_username_container", "id", pkg);
                titleId = bar.getResources().getIdentifier("action_bar_title", "id", pkg);
            }
            View title = titleId != 0 ? bar.findViewById(titleId) : null;
            if (!(title instanceof TextView)) return;
            View container = containerId != 0 ? bar.findViewById(containerId) : null;
            View target = container != null ? container : title;
            if (FeatureFlags.profileMenuTools) {
                target.setOnLongClickListener(HOLD_TITLE);
                TITLES.add(target);
            } else if (TITLES.remove(target)) {
                // Instagram gives it no long-click listener of its own: back to how it was.
                target.setOnLongClickListener(null);
                target.setLongClickable(false);
            }
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "Could not set up hold to copy on this username: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static final View.OnLongClickListener HOLD_TITLE = v -> {
        if (!FeatureFlags.profileMenuTools) return false;
        View title = titleId > 0 ? v.findViewById(titleId) : null;
        String name = title instanceof TextView tv ? ProfileTextPolicy.username(tv.getText()) : null;
        if (name == null) return false;
        try {
            CopyFeedback.copy(v.getContext(), "username", name, R.string.ig_toast_comment_copied);
            ModuleLog.line(TAG + "Username copied");
            return true; // a handled long click plays the View's haptic tick and skips the tap
        } catch (Throwable t) {
            ModuleLog.error(TAG + "Could not copy the username: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    };

    // ── Bio ──────────────────────────────────────────────────────────────────

    private static boolean hookBioTouches(ClassLoader cl) {
        try {
            // The compat lookup also walks superclasses: bind only the class's own override.
            Class<?> cls = XposedHelpers.findClass(COMPOSE_VIEW, cl);
            Method dispatch = XposedHelpers.findMethodExact(cls, "dispatchTouchEvent", MotionEvent.class);
            if (dispatch.getDeclaringClass() != cls) {
                throw new NoSuchMethodException("dispatchTouchEvent is inherited from " + dispatch.getDeclaringClass().getName());
            }
            XposedBridge.hookMethod(dispatch, TOUCH);
            return true;
        } catch (Throwable t) {
            ModuleLog.detail(TAG + "bio touches: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    private static final XC_MethodHook TOUCH = new XC_MethodHook() {
        private final HookFrames.Slot frames = new HookFrames.Slot();

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            JitGuard.tick(param.method);
            HookFrames.Frame frame = frames.enter();
            View view = (View) param.thisObject;
            MotionEvent event = (MotionEvent) param.args[0];
            boolean ours = view == pressed.get();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> press(view, event);
                case MotionEvent.ACTION_MOVE -> { if (ours && !copied && moved(view, event)) cancel(); }
                case MotionEvent.ACTION_POINTER_DOWN -> { if (ours && !copied) cancel(); }
                case MotionEvent.ACTION_UP -> {
                    if (!ours) return;
                    boolean wasCopied = copied;
                    cancel();
                    // Finish a copied hold as a cancel, so the release doesn't also open a link
                    // or expand the bio.
                    if (wasCopied) {
                        event.setAction(MotionEvent.ACTION_CANCEL);
                        frame.put(RESTORE_UP, Boolean.TRUE);
                    }
                }
                case MotionEvent.ACTION_CANCEL -> { if (ours) cancel(); }
                default -> { }
            }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            JitGuard.tick(param.method);
            HookFrames.Frame frame = frames.exit();
            MotionEvent event = (MotionEvent) param.args[0];
            if (Boolean.TRUE.equals(frame.get(RESTORE_UP))) {
                event.setAction(MotionEvent.ACTION_UP);
            } else if (event.getActionMasked() == MotionEvent.ACTION_DOWN && param.thisObject == pressed.get()
                    && !Boolean.TRUE.equals(param.getResult())) {
                // Nothing in Compose took the press, so the rest of the gesture (its release
                // included) goes elsewhere: don't time a hold that can't be seen ending.
                cancel();
            }
        }
    };

    private static void press(View view, MotionEvent event) {
        cancel();
        if (!FeatureFlags.profileMenuTools || !inUserInfo(view)) return;
        pressed = new WeakReference<>(view);
        downX = event.getX();
        downY = event.getY();
        pendingCopy = () -> {
            pendingCopy = null;
            if (pressed.get() != view || !view.isAttachedToWindow()) return;
            int[] at = new int[2];
            view.getLocationOnScreen(at);
            String bio = heldBio(view, at[0] + Math.round(downX), at[1] + Math.round(downY));
            if (bio == null) return;
            copied = true;
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            try {
                CopyFeedback.copy(view.getContext(), "bio", bio, R.string.ig_toast_comment_copied);
            } catch (Throwable t) {
                ModuleLog.error(TAG + "Could not copy the bio: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        };
        MAIN.postDelayed(pendingCopy, ViewConfiguration.getLongPressTimeout());
    }

    private static void cancel() {
        if (pendingCopy != null) MAIN.removeCallbacks(pendingCopy);
        pendingCopy = null;
        pressed = new WeakReference<>(null);
        copied = false;
    }

    private static boolean moved(View view, MotionEvent event) {
        int slop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
        return Math.abs(event.getX() - downX) > slop || Math.abs(event.getY() - downY) > slop;
    }

    /** The Compose view of a profile header's user info (category, bio, links, address). */
    @SuppressLint("DiscouragedApi")
    private static boolean inUserInfo(View view) {
        if (userInfoId == -1) {
            userInfoId = view.getResources().getIdentifier("profile_user_info_compose_view", "id",
                    view.getContext().getPackageName());
        }
        if (userInfoId == 0) return false;
        View current = view;
        for (int depth = 0; depth < 4 && current != null; depth++) {
            if (current.getId() == userInfoId) return true;
            current = current.getParent() instanceof View parent ? parent : null;
        }
        return false;
    }

    /** The bio to copy for a hold at this screen point, or null when the point isn't on the bio. */
    private static String heldBio(View compose, int x, int y) {
        List<String> held = heldTexts(compose, x, y);
        if (held.isEmpty()) return null;
        Activity a = ProfileMenuHook.activityOf(compose.getContext());
        String username = ProfileMenuHook.profileUsername(a);
        String model = username != null ? ProfileMenuHook.bioOf(a, username) : null;
        List<String> header = headerBios(username);
        String bio = ProfileTextPolicy.bioFor(held, model, header);
        if (bio == null) {
            ModuleLog.detail(TAG + "hold not on the bio (texts=" + held.size() + " model=" + (model != null)
                    + " header=" + header.size() + ")");
        } else {
            String source = model != null && bio.equals(model.trim()) ? "user model" : "header";
            ModuleLog.line(TAG + "Bio copied (" + bio.length() + " characters)");
            ModuleLog.detail(TAG + "bio source: " + source);
        }
        return bio;
    }

    /**
     * Texts of the accessibility nodes under a screen point, innermost first: Compose draws the
     * bio, so its node tree (what TalkBack reads) is where the text and its bounds are.
     */
    @SuppressLint("SoonBlockedPrivateApi") // guarded: a failure only means no copy
    private static List<String> heldTexts(View compose, int x, int y) {
        List<Object[]> hits = new ArrayList<>(); // {depth, text}
        try {
            AccessibilityNodeProvider provider = compose.getAccessibilityNodeProvider();
            if (provider == null) {
                ModuleLog.warn(TAG + "Could not read the bio under your finger, so it was not copied");
                ModuleLog.detail(TAG + "bio: the user info has no accessibility provider");
                return new ArrayList<>();
            }
            if (childIdMethod == null) {
                childIdMethod = AccessibilityNodeInfo.class.getDeclaredMethod("getChildId", int.class);
                childIdMethod.setAccessible(true);
            }
            Rect bounds = new Rect();
            ArrayDeque<int[]> queue = new ArrayDeque<>(); // {virtual view id, depth}
            queue.add(new int[]{View.NO_ID, 0});
            Set<Integer> seen = new HashSet<>();
            int nodes = 0;
            while (!queue.isEmpty() && nodes < MAX_NODES) {
                int[] next = queue.poll();
                if (!seen.add(next[0])) continue;
                AccessibilityNodeInfo node = provider.createAccessibilityNodeInfo(next[0]);
                if (node == null) continue;
                nodes++;
                node.getBoundsInScreen(bounds);
                CharSequence text = node.getText() != null ? node.getText() : node.getContentDescription();
                if (next[0] != View.NO_ID && text != null && text.length() > 0 && bounds.contains(x, y)) {
                    hits.add(new Object[]{next[1], text.toString()});
                }
                for (int i = 0; i < node.getChildCount(); i++) {
                    queue.add(new int[]{(int) (((long) childIdMethod.invoke(node, i)) >> 32), next[1] + 1});
                }
            }
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "Could not read the bio under your finger: " + (t.getCause() != null ? t.getCause() : t));
        }
        hits.sort((p, q) -> Integer.compare((int) q[0], (int) p[0]));
        List<String> texts = new ArrayList<>(hits.size());
        for (Object[] hit : hits) texts.add((String) hit[1]);
        return texts;
    }

    // ── The bio each header was built with ───────────────────────────────────

    /** The open profile's header bio first, then the others, most recently built first. */
    private static List<String> headerBios(String username) {
        List<String> out = new ArrayList<>();
        synchronized (HEADER_BIOS) {
            String own = username != null ? HEADER_BIOS.get(username) : null;
            if (own != null) out.add(own);
            List<String> rest = new ArrayList<>(HEADER_BIOS.values());
            Collections.reverse(rest);
            for (String bio : rest) if (!out.contains(bio)) out.add(bio);
        }
        return out;
    }

    /**
     * Remembers the bio each profile header is built with. The header binder (09ZU.A06 on 451)
     * builds the ProfileBioComponent UI state (0AnT on 451) from the profile's own GraphQL model
     * (biography_with_entities.raw_text): the type of a ProfileBioComponent parameter whose
     * constructor's first three String parameters are the id, username and bio, the class and
     * argument map ProfileSpoofHook uses. Read only.
     */
    private static String hookHeaderBios(DexKitBridge bridge, ClassLoader cl) {
        try {
            Class<?> state = null;
            String cached = DexKitCache.isCacheValid() ? DexKitCache.loadString(BIO_STATE_KEY) : null;
            if (cached != null && !cached.isEmpty()) {
                try { state = Class.forName(cached, false, cl); } catch (Throwable ignored) {}
            }
            if (state == null) {
                for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                        .usingStrings(List.of(BIO_COMPOSABLE), StringMatchType.Contains, false)))) {
                    Method m;
                    try { m = md.getMethodInstance(cl); } catch (Throwable t) { continue; }
                    for (Class<?> p : m.getParameterTypes()) {
                        if (bioConstructor(p) != null) { state = p; break; }
                    }
                    if (state != null) break;
                }
                if (state != null) DexKitCache.saveString(BIO_STATE_KEY, state.getName());
            }
            Constructor<?> ctor = state != null ? bioConstructor(state) : null;
            if (ctor == null) return "not found";
            Class<?>[] types = ctor.getParameterTypes();
            List<Integer> strings = new ArrayList<>();
            for (int i = 0; i < types.length; i++) if (types[i] == String.class) strings.add(i);
            final int usernameArg = strings.get(1), bioArg = strings.get(2);
            XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    JitGuard.tick(param.method);
                    if (!FeatureFlags.profileMenuTools) return;
                    if (param.args[usernameArg] instanceof String user && param.args[bioArg] instanceof String bio
                            && !bio.trim().isEmpty()) {
                        synchronized (HEADER_BIOS) {
                            HEADER_BIOS.remove(user); // re-inserted as the newest
                            HEADER_BIOS.put(user, bio);
                        }
                    }
                }
            });
            return state.getName() + " args " + usernameArg + "/" + bioArg;
        } catch (Throwable t) {
            return String.valueOf(t);
        }
    }

    /** The bio UI state's constructor: at least 4 String and 5 boolean parameters. */
    private static Constructor<?> bioConstructor(Class<?> c) {
        if (c.isPrimitive() || c.isInterface() || c.getName().startsWith("java") || c.getName().startsWith("android")) return null;
        for (Constructor<?> k : c.getDeclaredConstructors()) {
            int strings = 0, booleans = 0;
            for (Class<?> p : k.getParameterTypes()) {
                if (p == String.class) strings++;
                else if (p == boolean.class) booleans++;
            }
            if (strings >= 4 && booleans >= 5) return k;
        }
        return null;
    }
}
