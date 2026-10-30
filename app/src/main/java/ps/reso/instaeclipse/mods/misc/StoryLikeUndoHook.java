package ps.reso.instaeclipse.mods.misc;

import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import ps.reso.instaeclipse.Xposed.Module;
import ps.reso.instaeclipse.mods.ghost.ManualStorySeenHook;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.Found;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.ghost.UnsentIdentity;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;

public class StoryLikeUndoHook {

    private static final String TAG = "(InstaEclipse | LikeUndo): ";
    private static final String CACHE_KEY = "StoryLikeUndo_v1";
    private static final String WRITE_KEY = "StoryLikeWrite_v1";
    private static final String MEDIA = "com.instagram.feed.media.Media";
    private static final ThreadLocal<Member> REPLAYING = new ThreadLocal<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final PendingLikes<HeldLike> pending = PendingLikes.byId();
    private static final Set<String> keptRed = ConcurrentHashMap.newKeySet();

    private static Class<?> mediaClass;
    private static Method mediaId;
    private static final Map<Class<?>, Field[]> treeMedia = new ConcurrentHashMap<>();
    private static volatile Field stateId;

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

    public void install() {
        ClassLoader loader = Module.hostClassLoader;
        List<Method> found = DexKitCache.isCacheValid() ? DexKitCache.loadMethods(CACHE_KEY, loader) : null;
        try {
            mediaClass = Class.forName(MEDIA, false, loader);
            mediaId = mediaClass.getMethod("getId");
            if (found == null || found.size() != 3) {
                found = find(loader);
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
        try {
            Method queue = queueOf(check);
            hookQueue(queue);
            ModuleLog.detail(TAG + "saved story likes hooked: " + name(queue));
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "Liked stories may look unliked after a restart: " + t);
            return;
        }
        try {
            Method write = likeWrite(loader, like);
            hookWrite(write);
            ModuleLog.detail(TAG + "failed story likes hooked: " + name(write));
        } catch (Throwable t) {
            ModuleLog.detail(TAG + "A story like that fails may stay red for up to a day: " + t);
        }
    }

    private static Found performCoroutine() {
        return only(DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                .name("invokeSuspend").usingStrings("on_story_like_success", "transient_retry_queued"))), "story like coroutine");
    }

    private static List<Method> find(ClassLoader loader) throws Throwable {
        Found perform = performCoroutine();
        Method like = null;
        for (MethodData init : perform.data().getDeclaredClass().getMethods()) {
            if (!init.isConstructor()) continue;
            for (MethodData caller : DexKitQueryCache.callersUncached(init)) {
                if (!caller.isMethod() || caller.getClassName().equals(perform.getClassName())) continue;
                Method m = caller.getMethodInstance(loader);
                if (m.getReturnType() != void.class || paramAt(m, mediaClass) < 0 || stateAt(m) < 0) continue;
                if (like != null && !like.equals(m)) throw new IllegalStateException("two story like calls: " + name(like) + ", " + name(m));
                like = m;
            }
        }
        if (like == null) throw new IllegalStateException("story like call not found");
        Found check = only(DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                .usingStrings("send_story_like").returnType("boolean").paramCount(1))), "story liked check");
        Found named = only(DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
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

    private static Method queueOf(Method check) {
        Method queue = null;
        for (Method m : check.getDeclaringClass().getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers()) || stateAt(m) < 0 || paramAt(m, String.class) < 0) continue;
            if (queue != null) throw new IllegalStateException("two story like queues: " + name(queue) + ", " + name(m));
            queue = m;
        }
        if (queue == null) throw new IllegalStateException("story like queue not found");
        return queue;
    }

    private static Method likeWrite(ClassLoader loader, Method like) throws Throwable {
        List<Method> cached = DexKitCache.isCacheValid() ? DexKitCache.loadMethods(WRITE_KEY, loader) : null;
        if (cached != null && cached.size() == 1) return cached.get(0);
        String state = like.getParameterTypes()[stateAt(like)].getName();
        Method write = null;
        for (MethodData call : DexKitQueryCache.invokes(performCoroutine())) {
            if (!call.isMethod() || !"void".equals(call.getReturnTypeName())) continue;
            List<String> types = call.getParamTypeNames();
            if (types.size() != 2 || !MEDIA.equals(types.get(0)) || !state.equals(types.get(1))) continue;
            Method m = call.getMethodInstance(loader);
            if (!Modifier.isStatic(m.getModifiers())) continue;
            if (write != null && !write.equals(m)) throw new IllegalStateException("two story like writes: " + name(write) + ", " + name(m));
            write = m;
        }
        if (write == null) throw new IllegalStateException("story like write not found");
        DexKitCache.saveMethods(WRITE_KEY, Collections.singletonList(write));
        return write;
    }

    private static Found only(List<Found> found, String what) {
        List<Found> methods = new ArrayList<>();
        for (Found md : found) if (md.isMethod()) methods.add(md);
        if (methods.size() != 1) throw new IllegalStateException(what + ": " + methods.size() + " candidates");
        return methods.get(0);
    }

    private static void hookLike(Method like) {
        final int mediaAt = paramAt(like, mediaClass), stateAt = stateAt(like),
                countAt = paramAt(like, Integer.class), sourceAt = enumAt(like, "SUPERLIKE");
        if (mediaAt < 0 || stateAt < 0) throw new IllegalStateException("story like call without Media/LIKED: " + like);
        XposedBridge.hookMethod(like, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.method.equals(REPLAYING.get())) return;
                if (!FeatureFlags.likeUndo && pending.isEmpty()) return;
                String id = idOf(param.args[mediaAt]);
                if (id == null) return;
                boolean liked = isLiked(param.args[stateAt]);
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

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!ManualStorySeenHook.autoEnabled() || param.hasThrowable()
                        || param.method.equals(REPLAYING.get()) || !isLiked(param.args[stateAt])) return;
                String id = idOf(param.args[mediaAt]);
                if (id != null && !pending.contains(id)) ManualStorySeenHook.interacted(id, "like");
            }
        });
    }

    private static void hookCheck(Method check) {
        XposedBridge.hookMethod(check, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable() || Boolean.TRUE.equals(param.getResult())) return;
                try {
                    String id = idOf(mediaIn(param.args[0]));
                    if (id == null) return;
                    if (pending.contains(id)) {
                        param.setResult(true);
                    } else if (StoryLikeMemory.has(UnsentIdentity.account(param.thisObject), id)) {
                        param.setResult(true);
                        if (keptRed.add(id)) ModuleLog.detail(TAG + "story heart kept red from the saved like (Instagram's copy said not liked)");
                    }
                } catch (Throwable t) {
                    ModuleLog.detail(TAG + "story liked check: " + t);
                }
            }
        });
    }

    private static void hookQueue(Method queue) {
        final int stateAt = stateAt(queue), idAt = paramAt(queue, String.class);
        XposedBridge.hookMethod(queue, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable() || !(param.args[idAt] instanceof String id)) return;
                try {
                    if (isLiked(param.args[stateAt])) {
                        StoryLikeMemory.remember(UnsentIdentity.account(param.thisObject), id);
                        ModuleLog.detail(TAG + "story like saved on this device");
                    } else {
                        forget(id);
                    }
                } catch (Throwable t) {
                    ModuleLog.detail(TAG + "story like queue: " + t);
                }
            }
        });
    }

    private static void hookWrite(Method write) {
        final int mediaAt = paramAt(write, mediaClass), stateAt = stateAt(write);
        XposedBridge.hookMethod(write, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable() || isLiked(param.args[stateAt])) return;
                try {
                    String id = idOf(param.args[mediaAt]);
                    if (id != null) forget(id);
                } catch (Throwable t) {
                    ModuleLog.detail(TAG + "story like write: " + t);
                }
            }
        });
    }

    private static void forget(String id) {
        if (StoryLikeMemory.forget(id)) ModuleLog.detail(TAG + "saved story like cleared (unliked or the like failed)");
    }

    private static boolean isLiked(Object state) {
        return state instanceof Enum<?> e && "LIKED".equals(e.name());
    }

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

    private static void hold(Member method, Object self, Object[] args, String id) {
        ModuleLog.detail(TAG + "story like held for " + LikeUndoHook.DELAY_MS + " ms");
        HeldLike like = new HeldLike(method, self, args);
        like.fire = () -> {
            like.clearTint();
            if (!pending.release(id, like)) return;
            ManualStorySeenHook.interacted(id, "held like");
            send(like);
        };
        pending.hold(id, like);
        like.tint = LikeHeartTint.showById(id);
        Runnable start = () -> {
            if (pending.isHeld(id, like)) MAIN.postDelayed(like.fire, LikeUndoHook.DELAY_MS);
        };
        if (Looper.myLooper() == Looper.getMainLooper()) start.run();
        else MAIN.post(start);
        MAIN.postDelayed(() -> {
            if (!pending.isHeld(id, like)) return;
            ModuleLog.warn(TAG + "A held story like was still waiting after " + LikeUndoHook.DELAY_MS + " ms, so it was sent now");
            like.fire.run();
        }, LikeUndoHook.DELAY_MS + LikeHeartTint.EXPIRE_GRACE_MS);
    }

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
            return mediaClass.isInstance(media) ? media : null;
        } catch (Throwable t) {
            return null;
        }
    }

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

    private static String idOfState(Object state) {
        if (state == null) return null;
        Field f = stateId;
        if (f == null || f.getDeclaringClass() != state.getClass()) {
            f = null;
            for (Field field : state.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) continue;
                if (f != null) return null;
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

    private static int stateAt(Method m) {
        return enumAt(m, "NOT_LIKED");
    }

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
