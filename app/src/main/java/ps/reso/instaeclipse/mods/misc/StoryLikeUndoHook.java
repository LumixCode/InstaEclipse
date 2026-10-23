package ps.reso.instaeclipse.mods.misc;

import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import ps.reso.instaeclipse.Xposed.Module;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.JitGuard;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;

/**
 * Undo window for story likes, like {@link LikeUndoHook}'s for posts (same setting, same wait): a
 * story like is held for {@link LikeUndoHook#DELAY_MS} with its heart blue, then sent through
 * Instagram's own path and the heart is Instagram's red. Tapping the heart again in that time takes
 * it back: neither the like nor the unlike is sent. A like still held when the story moves on or
 * the viewer closes is sent when its wait ends.
 *
 * <p>Stories like through their own pipeline on 451 (the MVVM story viewer). The footer's heart
 * (StoryLikeButtonViewComponent, X.02E2: an ImageView, id toolbar_like_button) asks its view model
 * (X.01IO) to toggle; the view model asks the pending-story-likes store (X.00lL,
 * "pending_story_likes") whether the story is liked and calls StoryLikeRepository's like
 * (X.01I8.A0O: source, Media, LIKED or NOT_LIKED, superlike count). That call flips the story's
 * local like state and launches performStoryLike, which files the like in the store and sends it
 * (send_story_like). The whole call is held, so nothing of the like exists while it waits: no
 * request, and no record a store flush or a restart could send. Meanwhile the store's check answers
 * "liked" for it, so the heart shows liked and a second tap is an unlike, which takes it back.
 * Superlikes (a count of hearts) are not held.
 *
 * <p>Anchors: the performStoryLike coroutine's "on_story_like_success" + "transient_retry_queued"
 * (the repository call is what builds it), the store's one boolean method using "send_story_like",
 * and the button's "StoryLikeButtonViewComponent" name (its bind is its one (Object)V method; the
 * state it binds carries the story's media id). Sponsored stories still like through the old
 * viewer's path (X.046n, SponsoredStoriesLikeButtonBinder) and are not held.
 */
public class StoryLikeUndoHook {

    private static final String TAG = "(InstaEclipse | LikeUndo): ";
    private static final String CACHE_KEY = "StoryLikeUndo_v1";
    private static final String MEDIA = "com.instagram.feed.media.Media";
    /** The hooked method being called through on purpose, which its own hook lets pass. */
    private static final ThreadLocal<Member> REPLAYING = new ThreadLocal<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Held story likes by media id: the store's check is given other objects for the same story. */
    private static final PendingLikes<HeldLike> pending = PendingLikes.byId();

    private static Class<?> mediaClass;
    private static Method mediaId;
    /** Per class, the field holding the Media of a liked-check argument (empty: none). */
    private static final Map<Class<?>, Field[]> treeMedia = new ConcurrentHashMap<>();
    /** The bound state's media id field, for its class. */
    private static volatile Field stateId;

    /** A like waiting out its grace period, and its blue heart (main thread). */
    private static final class HeldLike {
        final Member method;
        final Object self;
        final Object[] args;
        volatile LikeHeartTint tint;
        Runnable fire;

        HeldLike(Member method, Object self, Object[] args) {
            this.method = method;
            this.self = self;
            this.args = args;
        }

        void clearTint() {
            if (tint != null) tint.clear();
            tint = null;
        }
    }

    public void install(DexKitBridge bridge) {
        ClassLoader loader = Module.hostClassLoader;
        List<Method> found = DexKitCache.isCacheValid() ? DexKitCache.loadMethods(CACHE_KEY, loader) : null;
        try {
            mediaClass = Class.forName(MEDIA, false, loader);
            mediaId = mediaClass.getMethod("getId");
            if (found == null || found.size() != 3) {
                found = find(bridge, loader);
                DexKitCache.saveMethods(CACHE_KEY, found);
            }
        } catch (Throwable t) {
            ModuleLog.error(TAG + "Could not turn on like undo for stories", t);
            return;
        }
        Method like = found.get(0), check = found.get(1), bind = found.get(2);
        try {
            hookLike(like);
            hookCheck(check);
            hookButton(bind);
        } catch (Throwable t) {
            ModuleLog.error(TAG + "Could not turn on like undo for stories", t);
            return;
        }
        ModuleLog.ok(TAG + "Ready for story likes");
        ModuleLog.detail(TAG + "story likes hooked: " + name(like) + ", liked check " + name(check) + ", button " + name(bind));
        FeatureStatusTracker.setHooked("StoryLikeUndo");
    }

    /** The repository's like call, the store's liked check and the like button's bind, in that order. */
    private static List<Method> find(DexKitBridge bridge, ClassLoader loader) throws Throwable {
        // StoryLikeRepository.performStoryLike: the repository's like call is what builds it.
        MethodData perform = only(DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                .name("invokeSuspend").usingStrings("on_story_like_success", "transient_retry_queued"))), "story like coroutine");
        Method like = null;
        for (MethodData init : perform.getDeclaredClass().getMethods()) {
            if (!init.isConstructor()) continue;
            for (MethodData caller : init.getCallers()) {
                if (!caller.isMethod() || caller.getClassName().equals(perform.getClassName())) continue;
                Method m = caller.getMethodInstance(loader);
                if (m.getReturnType() != void.class || paramAt(m, mediaClass) < 0 || stateAt(m) < 0) continue;
                if (like != null && !like.equals(m)) throw new IllegalStateException("two story like calls: " + name(like) + ", " + name(m));
                like = m;
            }
        }
        if (like == null) throw new IllegalStateException("story like call not found");
        // The pending-story-likes store's "is this story liked" check.
        MethodData check = only(DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                .usingStrings("send_story_like").returnType("boolean").paramCount(1))), "story liked check");
        // The like button: its name, and its bind (its one (Object)V method).
        MethodData named = only(DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                .usingStrings("StoryLikeButtonViewComponent").returnType("java.lang.String").paramCount(0))), "story like button");
        Method bind = null;
        for (Method m : named.getMethodInstance(loader).getDeclaringClass().getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers()) || m.getReturnType() != void.class
                    || m.getParameterTypes().length != 1 || m.getParameterTypes()[0] != Object.class) continue;
            if (bind != null) throw new IllegalStateException("two story like button binds");
            bind = m;
        }
        if (bind == null) throw new IllegalStateException("story like button bind not found");
        List<Method> methods = new ArrayList<>();
        methods.add(like);
        methods.add(check.getMethodInstance(loader));
        methods.add(bind);
        return methods;
    }

    private static MethodData only(List<MethodData> found, String what) {
        List<MethodData> methods = new ArrayList<>();
        for (MethodData md : found) if (md.isMethod()) methods.add(md);
        if (methods.size() != 1) throw new IllegalStateException(what + ": " + methods.size() + " candidates");
        return methods.get(0);
    }

    /** Holds likes, takes them back on an unlike while they wait. */
    private static void hookLike(Method like) {
        final int mediaAt = paramAt(like, mediaClass), stateAt = stateAt(like),
                countAt = paramAt(like, Integer.class), sourceAt = enumAt(like, "SUPERLIKE");
        if (mediaAt < 0 || stateAt < 0) throw new IllegalStateException("story like call without Media/LIKED: " + like);
        XposedBridge.hookMethod(like, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                JitGuard.tick(param.method);
                if (param.method.equals(REPLAYING.get())) return;
                if (!FeatureFlags.likeUndo && pending.isEmpty()) return;
                String id = idOf(param.args[mediaAt]);
                if (id == null) return;
                boolean liked = param.args[stateAt] instanceof Enum<?> e && "LIKED".equals(e.name());
                boolean superlike = (countAt >= 0 && param.args[countAt] != null)
                        || (sourceAt >= 0 && param.args[sourceAt] instanceof Enum<?> s && "SUPERLIKE".equals(s.name()));
                switch (StoryLikeUndoPolicy.decide(FeatureFlags.likeUndo, liked, superlike, pending.contains(id))) {
                    case HOLD:
                        param.setResult(null);
                        hold(param.method, param.thisObject, param.args.clone(), id);
                        break;
                    case TAKE_BACK:
                        if (drop(id)) {
                            param.setResult(null);
                            ModuleLog.line(TAG + "Story like taken back (heart tapped again)");
                        }
                        break;
                    case SUPERSEDE:
                        if (drop(id)) ModuleLog.detail(TAG + "held story like replaced by this one");
                        break;
                    default:
                        break;
                }
            }
        });
    }

    /**
     * The store's check answers "liked" for a held like: the heart shows it liked, and the button's
     * next tap is an unlike (which takes it back).
     */
    private static void hookCheck(Method check) {
        XposedBridge.hookMethod(check, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                JitGuard.tick(param.method);
                if (pending.isEmpty() || param.hasThrowable() || Boolean.TRUE.equals(param.getResult())) return;
                String id = idOf(mediaIn(param.args[0]));
                if (id != null && pending.contains(id)) param.setResult(true);
            }
        });
    }

    /** Each bind of the like button: its heart is blue while its story's like is held. */
    private static void hookButton(Method bind) throws NoSuchFieldException {
        Field button = null;
        for (Field f : bind.getDeclaringClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || !ImageView.class.isAssignableFrom(f.getType())) continue;
            if (button != null) throw new NoSuchFieldException("two like button views in " + bind.getDeclaringClass().getName());
            button = f;
        }
        if (button == null) throw new NoSuchFieldException("no like button view in " + bind.getDeclaringClass().getName());
        button.setAccessible(true);
        final Field view = button;
        XposedBridge.hookMethod(bind, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                JitGuard.tick(param.method);
                if (pending.isEmpty()) return;
                try {
                    ImageView heart = (ImageView) view.get(param.thisObject);
                    if (heart == null) return;
                    String id = idOfState(param.args[0]);
                    if (Looper.myLooper() == Looper.getMainLooper()) LikeHeartTint.storyHeart(id, heart);
                    else MAIN.post(() -> LikeHeartTint.storyHeart(id, heart));
                } catch (Throwable t) {
                    ModuleLog.detail(TAG + "story heart: " + t);
                }
            }
        });
    }

    /** Holds the like for DELAY_MS with its heart blue, then sends it; the heart turns red. */
    private static void hold(Member method, Object self, Object[] args, String id) {
        ModuleLog.detail(TAG + "story like held for " + LikeUndoHook.DELAY_MS + " ms");
        HeldLike like = new HeldLike(method, self, args);
        like.fire = () -> {
            like.clearTint();
            if (pending.release(id, like)) send(like); // else taken back or replaced
        };
        // Held at once, on the hook's thread: Instagram's next liked check (a redraw of the heart,
        // or the next tap) must already find it.
        pending.hold(id, like);
        like.tint = LikeHeartTint.showById(id);
        Runnable start = () -> {
            if (pending.isHeld(id, like)) MAIN.postDelayed(like.fire, LikeUndoHook.DELAY_MS);
        };
        if (Looper.myLooper() == Looper.getMainLooper()) start.run();
        else MAIN.post(start);
        // Safety net, as for post likes: a story like still held after its window goes out now.
        MAIN.postDelayed(() -> {
            if (!pending.isHeld(id, like)) return;
            ModuleLog.warn(TAG + "A held story like was still waiting after " + LikeUndoHook.DELAY_MS + " ms, so it was sent now");
            like.fire.run();
        }, LikeUndoHook.DELAY_MS + LikeHeartTint.EXPIRE_GRACE_MS);
    }

    /** Ends the wait of the like held for {@code id} without sending it; false when none is held. */
    private static boolean drop(String id) {
        HeldLike held = pending.cancel(id);
        if (held == null) return false;
        MAIN.post(() -> {
            MAIN.removeCallbacks(held.fire);
            held.clearTint();
        });
        return true;
    }

    private static void send(HeldLike like) {
        try {
            // Called through the hook (which lets it pass while replaying), as LikeUndoHook does.
            REPLAYING.set(like.method);
            Method m = (Method) like.method;
            m.setAccessible(true);
            m.invoke(like.self, like.args);
            ModuleLog.detail(TAG + "story like sent");
        } catch (Throwable t) {
            ModuleLog.error(TAG + "Could not send the story like: " + t);
        } finally {
            REPLAYING.remove();
        }
    }

    private static String idOf(Object media) {
        try {
            return mediaClass != null && mediaClass.isInstance(media) ? (String) mediaId.invoke(media) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** The Media a liked-check argument wraps (the story viewer hands it the story's Media). */
    private static Object mediaIn(Object tree) {
        if (tree == null) return null;
        if (mediaClass.isInstance(tree)) return tree;
        Field[] f = treeMedia.get(tree.getClass());
        if (f == null) {
            Field found = mediaField(tree.getClass());
            f = found == null ? new Field[0] : new Field[]{found};
            treeMedia.put(tree.getClass(), f);
        }
        if (f.length == 0) return null;
        try {
            Object media = f[0].get(tree);
            return mediaClass.isInstance(media) ? media : null; // the sponsored viewer passes another tree
        } catch (Throwable t) {
            return null;
        }
    }

    /** The field of {@code type} (or its superclasses) typed as something a Media is (its data tree); null if none. */
    private static Field mediaField(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                Class<?> t = field.getType();
                if (Modifier.isStatic(field.getModifiers()) || t == Object.class || !t.isAssignableFrom(mediaClass)) continue;
                field.setAccessible(true);
                return field;
            }
        }
        return null;
    }

    /** The story a like button state is for: its one String field, the media id. */
    private static String idOfState(Object state) {
        if (state == null) return null;
        Field f = stateId;
        if (f == null || f.getDeclaringClass() != state.getClass()) {
            f = null;
            for (Field field : state.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) continue;
                if (f != null) return null; // two strings: which is the story can't be told
                f = field;
            }
            if (f == null) return null;
            f.setAccessible(true);
            stateId = f;
        }
        try {
            return (String) f.get(state);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int paramAt(Method m, Class<?> type) {
        Class<?>[] p = m.getParameterTypes();
        for (int i = 0; i < p.length; i++) if (p[i] == type) return i;
        return -1;
    }

    /** The LIKED / NOT_LIKED argument. */
    private static int stateAt(Method m) {
        return enumAt(m, "NOT_LIKED");
    }

    /** The enum argument that has a constant named {@code constant}; -1 if none. */
    private static int enumAt(Method m, String constant) {
        Class<?>[] p = m.getParameterTypes();
        for (int i = 0; i < p.length; i++) {
            if (!p[i].isEnum()) continue;
            for (Object c : p[i].getEnumConstants()) {
                if (constant.equals(((Enum<?>) c).name())) return i;
            }
        }
        return -1;
    }

    private static String name(Method m) {
        return m.getDeclaringClass().getName() + "." + m.getName();
    }
}
