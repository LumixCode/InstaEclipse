package ps.reso.instaeclipse.mods.misc;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

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
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.Found;
import ps.reso.instaeclipse.utils.toast.IeToast;

public class LikeUndoHook {

    private static final String TAG = "(InstaEclipse | LikeUndo): ";
    private static final String CACHE_KEY = "LikeUndoSender";
    static final long DELAY_MS = 4000;
    private static final ThreadLocal<Member> REPLAYING = new ThreadLocal<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final PendingLikes<HeldLike> pending = new PendingLikes<>();

    private static final class HeldLike {
        final Member method;
        final Object self;
        final Object[] args;
        final Object media;
        volatile LikeHeartTint tint;
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

    public void install() {
        Method target = DexKitCache.isCacheValid() ? DexKitCache.loadMethod(CACHE_KEY, Module.hostClassLoader) : null;
        if (target == null) {
            try {
                List<Found> found = DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                        .usingStrings("source_of_like", "fetchLikeCount")
                        .returnType("void")));
                for (Found md : found) {
                    Method m = md.getMethodInstance(Module.hostClassLoader);
                    for (Class<?> p : m.getParameterTypes()) {
                        if (p.getName().equals("com.instagram.feed.media.Media")) { target = m; break; }
                    }
                    if (target != null) break;
                }
                if (target == null) {
                    ModuleLog.error(TAG + "Could not turn on like undo: the like action was not found in this Instagram version");
                    ModuleLog.detail(TAG + "like sender not found (" + found.size() + " candidates)");
                    return;
                }
                DexKitCache.saveMethod(CACHE_KEY, target);
            } catch (Throwable t) {
                ModuleLog.error(TAG + "Could not turn on like undo", t);
                return;
            }
        }
        final Method sender = target;
        XposedBridge.hookMethod(sender, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.method.equals(REPLAYING.get())) return;
                if (!(FeatureFlags.likeUndo || FeatureFlags.confirmLikes) && pending.isEmpty()) return;
                Object media = mediaOf(param.args);
                if (media == null) return;
                if (!HideSuggestedFeedItemsHook.isLiked(media)) {
                    HeldLike held = pending.cancel(media);
                    if (held == null) return;
                    param.setResult(null);
                    MAIN.post(() -> {
                        MAIN.removeCallbacks(held.fire);
                        held.clearTint();
                    });
                    ModuleLog.line(TAG + "Like taken back (heart tapped again)");
                    return;
                }
                boolean confirmed = !FeatureFlags.confirmLikes || confirmedLike(media);
                if (confirmed && !FeatureFlags.likeUndo) return;
                LikeHeartTint.Origin confirmedFrom = confirmedOrigin;
                confirmedOrigin = null;
                LikeHeartTint.Origin origin = confirmed && FeatureFlags.confirmLikes && confirmedFrom != null
                        ? confirmedFrom : LikeHeartTint.Origin.capture();
                Activity act = origin.activityOr(UIHookManager.getCurrentActivity());
                if (act == null) return;
                param.setResult(null);
                Member method = param.method;
                Object self = param.thisObject;
                Object[] args = param.args.clone();
                if (!confirmed) MAIN.post(() -> confirm(act, () -> {
                    if (FeatureFlags.likeUndo) hold(method, self, args, media);
                    else send(method, self, args);
                }, true));
                else hold(method, self, args, media);
            }
        });
        ModuleLog.ok(TAG + "Ready");
        ModuleLog.detail(TAG + "Hooked " + sender.getDeclaringClass().getName() + "." + sender.getName());
        boolean ready = HideSuggestedFeedItemsHook.hasLikedReady();
        if (!ready) ModuleLog.error(TAG + "Like undo and Ask before liking will not work: the like status was not found in this Instagram version");
        report("LikeUndo", FeatureFlags.likeUndo, ready);
        installed = true;
        onLikeUndoChanged();
        if (FeatureFlags.likeUndo) LikeHeartTint.install();
        if (FeatureFlags.confirmLikes) hookHeartButtons();
        hookLikeEntry(sender);
        report("ConfirmLikes", FeatureFlags.confirmLikes, ready);
    }

    private static void report(String feature, boolean on, boolean ready) {
        if (ready) FeatureStatusTracker.setHooked(feature);
        else if (on) FeatureStatusTracker.setBroken(feature);
    }

    private static volatile boolean installed;

    public static void onLikeUndoChanged() {
        if (installed && (FeatureFlags.likeUndo || FeatureFlags.confirmLikes)) LikeHeartTint.trackTouches();
    }

    private static Object mediaOf(Object[] args) {
        for (Object a : args) {
            if (a != null && a.getClass().getName().equals("com.instagram.feed.media.Media")) return a;
        }
        return null;
    }

    private static final String[] HEARTS = {"row_feed_button_like", "row_feed_media_per_media_like_button"};
    private static final java.util.Set<Integer> heartIds = new java.util.HashSet<>();
    private static volatile ConfirmWindow confirmedWindow;
    private static volatile LikeHeartTint.Origin confirmedOrigin;
    private static boolean replayingTap;

    private static void hookHeartButtons() {
        XposedBridge.hookAllMethods(View.class, "performClick", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!FeatureFlags.confirmLikes || replayingTap) return;
                View v = (View) param.thisObject;
                if (heartIds.isEmpty()) {
                    for (String name : HEARTS) {
                        int id = v.getResources().getIdentifier(name, "id", v.getContext().getPackageName());
                        if (id != 0) heartIds.add(id);
                    }
                    if (heartIds.isEmpty()) heartIds.add(-1);
                }
                View heart = heartIds.contains(v.getId()) ? v : null;
                if (heart == null && v instanceof ViewGroup g)
                    for (int i = 0; i < g.getChildCount() && heart == null; i++)
                        if (heartIds.contains(g.getChildAt(i).getId())) heart = g.getChildAt(i);
                if (heart == null || heart.isSelected()) return;
                Activity act = activityOf(v.getContext());
                if (act == null) return;
                param.setResult(true);
                LikeHeartTint.Origin origin = LikeHeartTint.Origin.capture();
                MAIN.post(() -> confirm(act, () -> {
                    confirmedOrigin = origin;
                    confirmedWindow = ConfirmWindow.forNextLike(System.currentTimeMillis());
                    replayingTap = true;
                    try { v.performClick(); } finally { replayingTap = false; }
                }, false));
            }
        });
    }

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
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!FeatureFlags.confirmLikes || param.method.equals(REPLAYING.get())) return;
                    Object media = mediaOf(param.args);
                    if (media == null || HideSuggestedFeedItemsHook.isLiked(media) || confirmedLike(media)) return;
                    LikeHeartTint.Origin origin = LikeHeartTint.Origin.capture();
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
                        confirmedWindow = ConfirmWindow.forMedia(media, System.currentTimeMillis());
                        send(method, self, args);
                    }, false));
                }
            });
            hooked++;
        }
        ModuleLog.detail(TAG + "like entry hooks=" + hooked);
    }

    private static boolean confirmedLike(Object media) {
        ConfirmWindow window = confirmedWindow;
        if (window == null || !window.covers(media, System.currentTimeMillis())) return false;
        confirmedWindow = window.boundTo(media);
        return true;
    }

    private static Activity activityOf(android.content.Context c) {
        while (c instanceof android.content.ContextWrapper w) {
            if (c instanceof Activity a) return a;
            c = w.getBaseContext();
        }
        return UIHookManager.getCurrentActivity();
    }

    private static void hold(Member method, Object self, Object[] args, Object media) {
        ModuleLog.detail(TAG + "like held for " + DELAY_MS + " ms");
        HeldLike like = new HeldLike(method, self, args, media);
        like.fire = () -> {
            like.clearTint();
            if (pending.release(media, like)) send(method, self, args);
        };
        pending.hold(media, like);
        like.tint = LikeHeartTint.show(media);
        LikeHeartTint.trackTouches();
        Runnable start = () -> {
            if (pending.isHeld(media, like)) MAIN.postDelayed(like.fire, DELAY_MS);
        };
        if (Looper.myLooper() == Looper.getMainLooper()) start.run();
        else MAIN.post(start);
        MAIN.postDelayed(() -> {
            if (!pending.isHeld(media, like)) return;
            ModuleLog.warn(TAG + "A held like was still waiting after " + DELAY_MS + " ms, so it was sent now");
            like.fire.run();
        }, DELAY_MS + LikeHeartTint.EXPIRE_GRACE_MS);
    }

    private static void confirm(Activity act, Runnable onLike, boolean sayNotSent) {
        try {
            Context host = UIHookManager.dialogHost(act);
            ps.reso.instaeclipse.utils.dialog.IeDialog dlg = new ps.reso.instaeclipse.utils.dialog.IeDialog(host)
                    .title(I18n.t(host, R.string.ig_confirm_like_title))
                    .message(I18n.t(host, R.string.ig_confirm_like_message));
            final boolean[] liked = {false};
            dlg.secondary(host.getString(android.R.string.cancel), null);
            dlg.primary(I18n.t(host, R.string.ig_confirm_like_yes), () -> { liked[0] = true; onLike.run(); });
            dlg.onDismiss(() -> {
                if (liked[0]) return;
                ModuleLog.detail(TAG + "like not confirmed");
                if (sayNotSent) IeToast.makeText(host, I18n.t(host, R.string.ig_toast_like_not_sent), Toast.LENGTH_SHORT).show();
            });
            dlg.show();
        } catch (Throwable t) {
            ModuleLog.error(TAG + "Could not show the like confirmation, so the like was not sent", t);
        }
    }

    private static void send(Member method, Object self, Object[] args) {
        try {
            REPLAYING.set(method);
            Method m = (Method) method;
            m.setAccessible(true);
            m.invoke(self, args);
            ModuleLog.detail(TAG + "like sent");
        } catch (Throwable t) {
            ModuleLog.error(TAG + "Could not send the like: " + t);
        } finally {
            REPLAYING.remove();
        }
    }
}
