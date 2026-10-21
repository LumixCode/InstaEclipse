package ps.reso.instaeclipse.mods.misc;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.List;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.Xposed.Module;
import ps.reso.instaeclipse.mods.feed.HideSuggestedFeedItemsHook;
import ps.reso.instaeclipse.mods.ui.UIHookManager;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;

/**
 * Like undo window (#235): a like is held for {@link #DELAY_MS} and only then sent. Meanwhile its
 * heart is blue ({@link LikeHeartTint}); when the like goes out it turns Instagram's red. Tapping
 * the heart again in that time takes the like back: neither the like nor that unlike is sent.
 *
 * Every post/reel like and unlike request goes through one builder (X.0E0b.A05 on 452,
 * X.0A0e.A05 on 451) — the feed double-tap/heart, reels and the other surfaces call it. Instagram
 * flips the media's local has_liked state BEFORE calling it, so "media reads as liked" means this
 * call is a like, and an unlike of a media whose like is still held is the take-back. Anchor:
 * "source_of_like" + "fetchLikeCount".
 *
 * <p>Ask before liking holds the heart button's tap before Instagram fills it (see
 * {@link #hookHeartButtons}); other likes (double tap) are held here at the sender.
 */
public class LikeUndoHook {

    private static final String TAG = "(InstaEclipse | LikeUndo): ";
    private static final String CACHE_KEY = "LikeUndoSender";
    private static final long DELAY_MS = 4000;
    /** A heart re-rendered right after the like (a new view) is found again once, this much later. */
    private static final long RETINT_MS = 300;
    /** The hooked method being called through on purpose, which its own hook lets pass. */
    private static final ThreadLocal<Member> REPLAYING = new ThreadLocal<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final PendingLikes<HeldLike> pending = new PendingLikes<>();

    /** A like waiting out its grace period, and its blue heart (main thread). */
    private static final class HeldLike {
        final Member method;
        final Object self;
        final Object[] args;
        final Object media;
        LikeHeartTint tint;
        Runnable fire;

        HeldLike(Member method, Object self, Object[] args, Object media) {
            this.method = method;
            this.self = self;
            this.args = args;
            this.media = media;
        }

        void clearTint() {
            if (tint != null) tint.clear();
            tint = null;
        }
    }

    public void install(DexKitBridge bridge) {
        Method target = DexKitCache.isCacheValid() ? DexKitCache.loadMethod(CACHE_KEY, Module.hostClassLoader) : null;
        if (target == null) {
            try {
                List<MethodData> found = DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                        .usingStrings("source_of_like", "fetchLikeCount")
                        .returnType("void")));
                for (MethodData md : found) {
                    Method m = md.getMethodInstance(Module.hostClassLoader);
                    for (Class<?> p : m.getParameterTypes()) {
                        if (p.getName().equals("com.instagram.feed.media.Media")) { target = m; break; }
                    }
                    if (target != null) break;
                }
                if (target == null) {
                    ModuleLog.line(TAG + "❌ like sender not found (" + found.size() + " candidates)");
                    return;
                }
                DexKitCache.saveMethod(CACHE_KEY, target);
            } catch (Throwable t) {
                ModuleLog.line(TAG + "❌ " + t);
                return;
            }
        }
        final Method sender = target;
        XposedBridge.hookMethod(sender, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (param.method.equals(REPLAYING.get())) return;
                if (!(FeatureFlags.likeUndo || FeatureFlags.confirmLikes) && pending.isEmpty()) return;
                Object media = mediaOf(param.args);
                if (media == null) return;
                if (!HideSuggestedFeedItemsHook.isLiked(media)) {
                    // An unlike. While its like is still held, neither of the two is sent.
                    HeldLike held = pending.cancel(media);
                    if (held == null) return; // the like went out: this unlike is real
                    param.setResult(null);
                    MAIN.post(() -> {
                        MAIN.removeCallbacks(held.fire);
                        held.clearTint();
                    });
                    ModuleLog.line(TAG + "like taken back (heart tapped again)");
                    return;
                }
                // A heart tap confirmed a moment ago: Instagram sends it now, unless Undo holds it.
                boolean confirmed = !FeatureFlags.confirmLikes || System.currentTimeMillis() <= confirmedUntil;
                if (confirmed && !FeatureFlags.likeUndo) return;
                // A like confirmed in the dialog came from the touch made before the dialog.
                LikeHeartTint.Origin confirmedFrom = confirmedOrigin;
                confirmedOrigin = null;
                LikeHeartTint.Origin origin = confirmed && FeatureFlags.confirmLikes && confirmedFrom != null
                        ? confirmedFrom : LikeHeartTint.Origin.capture(null);
                Activity act = origin.activityOr(UIHookManager.getCurrentActivity());
                if (act == null) return;
                param.setResult(null);
                Member method = param.method;
                Object self = param.thisObject;
                Object[] args = param.args.clone();
                if (!confirmed) MAIN.post(() -> confirm(act, () -> {
                    if (FeatureFlags.likeUndo) hold(act, method, self, args, media, origin);
                    else send(method, self, args);
                }));
                else hold(act, method, self, args, media, origin);
            }
        });
        ModuleLog.line(TAG + "✅ Hooked " + sender.getDeclaringClass().getName() + "." + sender.getName());
        FeatureStatusTracker.setHooked("LikeUndo");
        installed = true;
        onLikeUndoChanged();
        // View.performClick is a very hot framework method; hooking it app-wide triggers ART's JIT
        // SIGSEGV on IG 451+ / Android 13, so the heart tap hook is only installed when Ask before
        // liking is on (turning it on later holds heart taps after an Instagram restart; until
        // then the like entry and the sender below still ask).
        if (FeatureFlags.confirmLikes) hookHeartButtons();
        hookLikeEntry(sender);
        FeatureStatusTracker.setHooked("ConfirmLikes");
    }

    private static volatile boolean installed;

    /**
     * The undo window was turned on or off (in Instagram or from the companion): touches are
     * recorded from now on, so the very next like finds its heart and its activity. Ask before
     * liking needs a restart, so it is only read at install. Never without the sender hook.
     */
    public static void onLikeUndoChanged() {
        if (installed && (FeatureFlags.likeUndo || FeatureFlags.confirmLikes)) LikeHeartTint.trackTouches();
    }

    private static Object mediaOf(Object[] args) {
        for (Object a : args) {
            if (a != null && a.getClass().getName().equals("com.instagram.feed.media.Media")) return a;
        }
        return null;
    }

    /** Post like buttons (feed, carousel slide, reels): their heart shows "selected" once liked. */
    private static final String[] HEARTS = {"row_feed_button_like", "row_feed_media_per_media_like_button", "clips_ufi_like_button"};
    private static final java.util.Set<Integer> heartIds = new java.util.HashSet<>();
    private static volatile long confirmedUntil;
    /** Where the like confirmed last came from, for the blue heart once it reaches the sender. */
    private static volatile LikeHeartTint.Origin confirmedOrigin;
    private static boolean replayingTap;

    /**
     * Ask before liking holds the heart tap itself, before Instagram fills the heart, and replays it
     * on Like; the like then passes the sender for a moment. Double-tap likes have no button, so
     * they are still held at the sender (the heart may stay filled after Cancel).
     */
    private static void hookHeartButtons() {
        XposedBridge.hookAllMethods(View.class, "performClick", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (!FeatureFlags.confirmLikes || replayingTap) return;
                View v = (View) param.thisObject;
                if (heartIds.isEmpty()) {
                    for (String name : HEARTS) {
                        int id = v.getResources().getIdentifier(name, "id", v.getContext().getPackageName());
                        if (id != 0) heartIds.add(id);
                    }
                    if (heartIds.isEmpty()) heartIds.add(-1);
                }
                // The heart itself, or the unnamed touch area that wraps it (feed).
                View heart = heartIds.contains(v.getId()) ? v : null;
                if (heart == null && v instanceof ViewGroup g)
                    for (int i = 0; i < g.getChildCount() && heart == null; i++)
                        if (heartIds.contains(g.getChildAt(i).getId())) heart = g.getChildAt(i);
                if (heart == null || heart.isSelected()) return; // a filled heart: this is an unlike
                Activity act = activityOf(v.getContext());
                if (act == null) return;
                param.setResult(true);
                LikeHeartTint.Origin origin = LikeHeartTint.Origin.capture(heart);
                MAIN.post(() -> confirm(act, () -> {
                    confirmedOrigin = origin;
                    confirmedUntil = System.currentTimeMillis() + 3000;
                    replayingTap = true;
                    try { v.performClick(); } finally { replayingTap = false; }
                }));
            }
        });
    }

    /**
     * Ask before liking, for hearts that aren't Views (the Compose feed heart on 451): the like
     * entry (X.0A0e.A04 on 451, X.0E0b.A04 on 452; it takes the Activity and the Media, in the
     * sender's class) updates the post's like state and then calls the sender. Held here, before
     * the heart fills, so Cancel leaves it empty; Like replays it.
     */
    private static void hookLikeEntry(Method sender) {
        int hooked = 0;
        for (Method m : sender.getDeclaringClass().getDeclaredMethods()) {
            if (m.equals(sender) || m.getReturnType() != void.class
                    || java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
            boolean activity = false, media = false;
            for (Class<?> p : m.getParameterTypes()) {
                if (p == Activity.class) activity = true;
                if (p.getName().equals("com.instagram.feed.media.Media")) media = true;
            }
            if (!activity || !media) continue;
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    if (!FeatureFlags.confirmLikes || param.method.equals(REPLAYING.get())
                            || System.currentTimeMillis() <= confirmedUntil) return;
                    Object media = mediaOf(param.args);
                    if (media == null || HideSuggestedFeedItemsHook.isLiked(media)) return; // already liked: not a new like
                    LikeHeartTint.Origin origin = LikeHeartTint.Origin.capture(null);
                    // The entry is given the post's own activity (a post opened from a chat has its own).
                    Activity given = null;
                    for (Object arg : param.args) if (arg instanceof Activity x && !x.isFinishing()) given = x;
                    Activity act = given != null ? given : origin.activityOr(UIHookManager.getCurrentActivity());
                    if (act == null) return;
                    param.setResult(null);
                    Member method = param.method;
                    Object self = param.thisObject;
                    Object[] args = param.args.clone();
                    MAIN.post(() -> confirm(act, () -> {
                        confirmedOrigin = origin;
                        confirmedUntil = System.currentTimeMillis() + 3000;
                        send(method, self, args);
                    }));
                }
            });
            hooked++;
        }
        ModuleLog.line(TAG + "like entry hooks=" + hooked);
    }

    private static Activity activityOf(android.content.Context c) {
        while (c instanceof android.content.ContextWrapper w) {
            if (c instanceof Activity a) return a;
            c = w.getBaseContext();
        }
        return UIHookManager.getCurrentActivity();
    }

    /** Holds the like for DELAY_MS with its heart blue, then sends it; the heart turns red. */
    private static void hold(Activity act, Member method, Object self, Object[] args, Object media,
                             LikeHeartTint.Origin origin) {
        ModuleLog.line(TAG + "like held for " + DELAY_MS + " ms");
        HeldLike like = new HeldLike(method, self, args, media);
        like.fire = () -> {
            like.clearTint();
            if (pending.release(media, like)) send(method, self, args); // else taken back or replaced
        };
        // Held at once, on the hook's thread: an unlike right after it must find it.
        pending.hold(media, like);
        LikeHeartTint.trackTouches();
        Runnable start = () -> {
            if (!pending.isHeld(media, like)) return;
            like.tint = LikeHeartTint.show(act, origin);
            MAIN.postDelayed(like.fire, DELAY_MS);
            // Instagram may draw the liked heart in a new view: find it again once.
            MAIN.postDelayed(() -> {
                if (!pending.isHeld(media, like) || (like.tint != null && like.tint.isShown())) return;
                like.clearTint();
                like.tint = LikeHeartTint.show(act, origin);
            }, RETINT_MS);
        };
        // At once on the main thread: a double tap redraws the heart in this frame, red if posted.
        if (Looper.myLooper() == Looper.getMainLooper()) start.run();
        else MAIN.post(start);
    }

    /** Ask before liking: the like goes out only on Like; Cancel drops it. */
    private static void confirm(Activity act, Runnable onLike) {
        try {
            ps.reso.instaeclipse.utils.dialog.IeDialog dlg = new ps.reso.instaeclipse.utils.dialog.IeDialog(act)
                    .title(I18n.t(act, R.string.ig_confirm_like_title))
                    .message(I18n.t(act, R.string.ig_confirm_like_message));
            final boolean[] answered = {false};
            dlg.secondary(act.getString(android.R.string.cancel), () -> answered[0] = true);
            dlg.primary(I18n.t(act, R.string.ig_confirm_like_yes), () -> { answered[0] = true; onLike.run(); });
            dlg.onDismiss(() -> { if (!answered[0]) ModuleLog.line(TAG + "like not confirmed"); });
            dlg.show();
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ confirm: " + t);
        }
    }

    private static void send(Member method, Object self, Object[] args) {
        try {
            // Called through the hook (which lets it pass while replaying): invoking the original
            // directly isn't supported the same way on every framework (LSPatch). Only this method
            // passes: a like entry replayed here still reaches the sender's hold.
            REPLAYING.set(method);
            Method m = (Method) method;
            m.setAccessible(true);
            m.invoke(self, args);
            ModuleLog.line(TAG + "like sent");
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ send failed: " + t);
        } finally {
            REPLAYING.remove();
        }
    }
}
