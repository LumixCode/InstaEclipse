package ps.reso.instaeclipse.mods.misc;

import android.app.Activity;
import android.graphics.ColorFilter;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.widget.ImageView;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicIntegerArray;

import ps.reso.instaeclipse.Xposed.Module;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.JitGuard;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;

/**
 * The blue heart of a like {@link LikeUndoHook} holds: blue from the hold until the like is sent,
 * when Instagram's own red shows, or taken back.
 *
 * <p>It is recolored in Instagram's own heart drawable, not on screen. The feed and the reels like
 * buttons (X.07Ab and X.099d on 451) draw the liked heart red in one of three ways, which
 * Instagram's server picks per account:
 * <ul>
 * <li>animated: a heart drawable (X.07Ay, in component X.019P) whose filled layer fades in over
 *     the outline and carries a red colour filter;</li>
 * <li>reels, not animated: a StateListDrawable whose selected state is the filled heart with the
 *     red filter (in image component X.06gJ);</li>
 * <li>feed, not animated: an icon component (X.07Ac) that wraps ufi_heart_icon and tints it red
 *     while the button is selected (one drawable for both looks, so it is recolored only while
 *     its like is held, after it is made its own: Instagram shares it unless it mutates it).</li>
 * </ul>
 * While a like is held, that red layer gets a blue colour filter, which also wins over a tint;
 * putting Instagram's filter back brings the red. Instagram keeps these drawables across
 * re-renders, so no re-render is needed either way.
 *
 * <p>The feed's Compose like row (Ufi, X.057g on 451) instead gives its liked heart (drawable
 * instagram_heart_filled_24) a Compose colour in an icon spec (X.064E). While a like is held that
 * colour is blue, and the composition reads a Compose state of ours; writing it when the like is
 * sent or taken back recomposes the row with Instagram's red.
 *
 * <p>Found from the reels button's "clips_ufi_like_button_component" tag: the animated heart is
 * the component it builds with a Drawable subclass and a size, every render that builds that
 * component is a like button, and the components those renders build with an ImageView.ScaleType
 * are the other two. A render's post is the one field path from it to its Media, matched by id.
 */
final class LikeHeartTint {
    private static final String TAG = "(InstaEclipse | LikeUndo): ";
    private static final String CACHE_KEY = "LikeHeartRenders";
    /** Instagram's own blue, for a like that is not sent yet. */
    static final int LIKE_BLUE = 0xFF0095F6;
    /** Instagram paints its heart with SRC_ATOP; the blue does the same. */
    private static final ColorFilter BLUE = new PorterDuffColorFilter(LIKE_BLUE, PorterDuff.Mode.SRC_ATOP);
    /** A touch older than this when the like comes did not cause it. */
    private static final long TOUCH_MAX_AGE_MS = 1500;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int[] SELECTED = {android.R.attr.state_selected};
    private static final int ANIMATED = 0, STATE_LIST = 1, ICON = 2, COMPOSE = 3;
    private static final String[] KINDS = {"animated", "state list", "icon", "compose"};

    /** The id of the post whose like button this thread renders right now. */
    private static final ThreadLocal<String> RENDERING = new ThreadLocal<>();
    /** Every heart Instagram built (the drawable it mounts), its red layer and post. Guarded by itself. */
    private static final Map<Drawable, Heart> hearts = new WeakHashMap<>();
    /** Icon components built for a liked post, until they make their drawable. Guarded by itself. */
    private static final Map<Object, String> icons = new WeakHashMap<>();
    /** The posts whose heart is blue now, by id, and their tint. Guarded by {@link #hearts}. */
    private static final Map<String, LikeHeartTint> blue = new HashMap<>();
    /** Instagram's own filter of each red layer while it is blue (main thread). */
    private static final Map<Drawable, ColorFilter> original = new WeakHashMap<>();
    /** Hearts built per way of drawing them, for the log. */
    private static final AtomicIntegerArray seen = new AtomicIntegerArray(KINDS.length);
    private static Field filled;
    private static Method mediaId;

    /** The id of the post whose Compose like row this thread composes right now. */
    private static final ThreadLocal<String> COMPOSING = new ThreadLocal<>();
    /** Compose states our compositions read, by post id. Guarded by {@link #hearts}. */
    private static final Map<String, Object> composeStates = new HashMap<>();
    private static Object blueColor;
    private static int heartFilled;
    private static Parcelable.Creator<?> stateCreator;
    private static Method stateGet, stateSet;

    // The last touch in an Instagram activity, recorded while the undo window or Ask before
    // liking is on: a like's dialog opens over the screen it was made in.
    private static volatile long touchTime;
    private static volatile WeakReference<Activity> touchActivity = new WeakReference<>(null);
    private static boolean tracking;

    /** A heart's red layer and the post it was built for (the drawable it mounts is the key). */
    private static final class Heart {
        final Drawable red;
        volatile String id;

        Heart(Drawable red, String id) {
            this.red = red;
            this.id = id;
        }
    }

    private final String id;
    /** The hearts this tint turned blue: the drawable Instagram mounts, and its red layer. */
    private final Map<Drawable, Drawable> painted = new IdentityHashMap<>();
    private boolean done;

    private LikeHeartTint(String id) {
        this.id = id;
    }

    /** Where a like came from: the activity of the touch just before it. */
    static final class Origin {
        final boolean touched;
        final WeakReference<Activity> activity;

        private Origin(boolean touched, Activity activity) {
            this.touched = touched;
            this.activity = new WeakReference<>(activity);
        }

        /** Now, on the thread Instagram likes on. */
        static Origin capture() {
            long age = SystemClock.uptimeMillis() - touchTime;
            return new Origin(touchTime != 0 && age >= 0 && age <= TOUCH_MAX_AGE_MS, touchActivity.get());
        }

        /**
         * The activity the like's touch landed in while it is still there, else {@code fallback}.
         * Posts and reels opened from a chat run in their own activity above the main one.
         */
        Activity activityOr(Activity fallback) {
            Activity touchedIn = touched ? activity.get() : null;
            return touchedIn != null && !touchedIn.isFinishing() && !touchedIn.isDestroyed() ? touchedIn : fallback;
        }
    }

    /** Records touches from now on (the Activity method every Instagram screen passes them to). */
    static synchronized void trackTouches() {
        if (tracking) return;
        tracking = true;
        try {
            XposedBridge.hookMethod(Activity.class.getMethod("dispatchTouchEvent", MotionEvent.class), new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    JitGuard.tick(param.method);
                    if (!(FeatureFlags.likeUndo || FeatureFlags.confirmLikes)
                            || !(param.args[0] instanceof MotionEvent e)) return;
                    int action = e.getActionMasked();
                    if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_UP) return;
                    touchTime = e.getEventTime();
                    if (touchActivity.get() != param.thisObject) {
                        touchActivity = new WeakReference<>((Activity) param.thisObject);
                    }
                }
            });
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ touch tracking: " + t);
        }
    }

    /** Hooks the like buttons' renders and the components they draw the heart with. */
    static void install(DexKitBridge bridge) {
        try {
            ClassLoader loader = Module.hostClassLoader;
            mediaId = Class.forName("com.instagram.feed.media.Media", false, loader).getMethod("getId");
            List<Method> renders = DexKitCache.isCacheValid() ? DexKitCache.loadMethods(CACHE_KEY, loader) : null;
            if (renders == null) {
                renders = likeRenders(bridge, loader);
                if (renders.isEmpty()) {
                    ModuleLog.line(TAG + "❌ like buttons not found");
                    return;
                }
                DexKitCache.saveMethods(CACHE_KEY, renders);
            }
            Set<Constructor<?>> animated = new LinkedHashSet<>(), images = new LinkedHashSet<>(), iconMakers = new LinkedHashSet<>();
            for (Method render : renders) {
                MethodData data = bridge.getMethodData(render);
                if (data == null) continue;
                for (MethodData call : data.getInvokes()) {
                    if (!call.isConstructor()) continue;
                    Constructor<?> c = call.getConstructorInstance(loader);
                    if (animatedHeart(c)) animated.add(c);
                    else if (paramIndex(c, ImageView.ScaleType.class) >= 0) {
                        if (paramIndex(c, Drawable.class) >= 0) images.add(c);
                        else if (lastIsBoolean(c)) iconMakers.add(c);
                    }
                }
            }
            if (!animated.isEmpty()) filled = filledLayer(bridge, animated.iterator().next().getParameterTypes()[1]);

            int hooked = 0;
            for (Method render : renders) {
                Field[] path = mediaPath(render.getDeclaringClass());
                if (path == null) {
                    ModuleLog.line(TAG + "⚠️ no Media in " + render.getDeclaringClass().getName());
                    continue;
                }
                XposedBridge.hookMethod(render, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        JitGuard.tick(param.method);
                        if (FeatureFlags.likeUndo) RENDERING.set(idOf(follow(param.thisObject, path)));
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        RENDERING.remove();
                    }
                });
                hooked++;
            }
            for (Constructor<?> c : animated) {
                XposedBridge.hookMethod(c, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        JitGuard.tick(param.method);
                        String id = RENDERING.get();
                        if (id != null && param.args[1] instanceof Drawable heart) built(id, heart, redLayer(heart), ANIMATED);
                    }
                });
            }
            for (Constructor<?> c : images) {
                int at = paramIndex(c, Drawable.class);
                XposedBridge.hookMethod(c, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        JitGuard.tick(param.method);
                        String id = RENDERING.get();
                        if (id == null || !(param.args[at] instanceof Drawable heart)) return;
                        boolean animatedHeart = filled != null && filled.getDeclaringClass().isInstance(heart);
                        if (!animatedHeart && !(heart instanceof StateListDrawable)) return;
                        built(id, heart, redLayer(heart), animatedHeart ? ANIMATED : STATE_LIST);
                    }
                });
            }
            for (Constructor<?> c : iconMakers) {
                XposedBridge.hookMethod(c, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        JitGuard.tick(param.method);
                        String id = RENDERING.get();
                        // One drawable for both looks: the view's selected state picks the red.
                        if (id == null) return;
                        synchronized (icons) {
                            icons.put(param.thisObject, id);
                        }
                    }
                });
                for (Method make : c.getDeclaringClass().getDeclaredMethods()) {
                    if (Modifier.isStatic(make.getModifiers()) || make.getParameterTypes().length != 1
                            || make.getReturnType() == void.class) continue;
                    XposedBridge.hookMethod(make, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            JitGuard.tick(param.method);
                            String id;
                            synchronized (icons) {
                                id = icons.get(param.thisObject);
                            }
                            if (id == null) return;
                            Drawable heart = drawableIn(param.getResult());
                            if (heart == null) return;
                            Drawable inner = drawableIn(heart); // what the icon's wrapper draws
                            built(id, heart, inner != null ? inner : heart, ICON);
                        }
                    });
                }
            }
            ModuleLog.line(TAG + "✅ heart hooked: " + hooked + "/" + renders.size() + " like button(s); animated "
                    + names(animated) + (filled == null ? " (no filled layer)" : " " + filled.getName())
                    + ", images " + names(images) + ", icons " + names(iconMakers));
        } catch (Throwable t) {
            ModuleLog.line(TAG + "❌ heart: " + t);
        }
        installCompose(bridge);
    }

    /** Hooks the feed's Compose like row: the composable, and the icon spec it builds the heart with. */
    private static void installCompose(DexKitBridge bridge) {
        try {
            ClassLoader loader = Module.hostClassLoader;
            MethodData ufi = null;
            for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                    .usingStrings("row.ufi.ui.Ufi (MediaUfiComposeBinder.kt:")))) {
                if (md.isMethod()) { ufi = md; break; }
            }
            if (ufi == null) {
                ModuleLog.line(TAG + "⚠️ Compose like row not found");
                return;
            }
            Method row = ufi.getMethodInstance(loader);
            // The row's UI state holds its Media.
            int stateAt = -1;
            Field media = null;
            Class<?>[] params = row.getParameterTypes();
            for (int i = 0; i < params.length && media == null; i++) {
                if (!params[i].getName().startsWith("X.")) continue;
                for (Field f : params[i].getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers()) && f.getType().getName().equals("com.instagram.feed.media.Media")) {
                        stateAt = i;
                        media = f;
                        break;
                    }
                }
            }
            // The icon spec: the constructor the row calls with a Compose colour (a class built from a long).
            Constructor<?> spec = null, color = null;
            int colorAt = -1;
            for (MethodData call : ufi.getInvokes()) {
                if (!call.isConstructor()) continue;
                Constructor<?> c = call.getConstructorInstance(loader);
                Class<?>[] p = c.getParameterTypes();
                if (p.length < 10) continue;
                for (int j = 0; j < p.length && spec == null; j++) {
                    if (!p[j].getName().startsWith("X.")) continue;
                    try {
                        color = p[j].getDeclaredConstructor(long.class);
                        spec = c;
                        colorAt = j;
                    } catch (NoSuchMethodException ignored) {}
                }
                if (spec != null) break;
            }
            if (media == null || spec == null) {
                ModuleLog.line(TAG + "⚠️ Compose like row: media " + (media != null) + ", icon spec " + (spec != null));
                return;
            }
            color.setAccessible(true);
            blueColor = color.newInstance(((long) LIKE_BLUE) << 32); // an sRGB Compose Color
            Class<?> state = Class.forName("androidx.compose.runtime.ParcelableSnapshotMutableState", false, loader);
            stateCreator = (Parcelable.Creator<?>) state.getField("CREATOR").get(null);
            stateGet = state.getMethod("getValue");
            for (Method m : state.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (!Modifier.isStatic(m.getModifiers()) && m.getReturnType() == void.class
                        && p.length == 1 && p[0] == Object.class) stateSet = m;
            }
            if (stateSet == null) {
                ModuleLog.line(TAG + "⚠️ Compose state setter not found");
                return;
            }
            media.setAccessible(true);
            final int at = stateAt, colorArg = colorAt;
            final Field[] mediaPath = {media};
            XposedBridge.hookMethod(row, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    JitGuard.tick(param.method);
                    if (FeatureFlags.likeUndo) COMPOSING.set(idOf(follow(param.args[at], mediaPath)));
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    COMPOSING.remove();
                }
            });
            XposedBridge.hookMethod(spec, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    JitGuard.tick(param.method);
                    String id = COMPOSING.get();
                    if (id == null || !filledHeart(param.args)) return;
                    if (seen.getAndIncrement(COMPOSE) == 0) ModuleLog.line(TAG + "heart drawn compose");
                    Object read;
                    synchronized (hearts) {
                        if (!blue.containsKey(id)) return;
                        read = composeStates.get(id);
                        if (read == null) {
                            composeStates.put(id, read = newState());
                            ModuleLog.line(TAG + "heart blue (compose)");
                        }
                    }
                    param.args[colorArg] = blueColor;
                    // Read here, inside the row's own composition: writing it recomposes the row.
                    try {
                        stateGet.invoke(read);
                    } catch (Throwable t) {
                        ModuleLog.line(TAG + "⚠️ Compose state: " + t);
                    }
                }
            });
            ModuleLog.line(TAG + "✅ Compose heart hooked: " + row.getDeclaringClass().getName() + "." + row.getName()
                    + ", icon " + spec.getDeclaringClass().getName());
        } catch (Throwable t) {
            ModuleLog.line(TAG + "❌ Compose heart: " + t);
        }
    }

    /** Whether an icon spec's arguments draw the liked (filled) heart. */
    private static boolean filledHeart(Object[] args) {
        if (heartFilled == 0) {
            heartFilled = ps.reso.instaeclipse.utils.ui.ResIds.drawable(
                    ps.reso.instaeclipse.xposedcompat.AndroidAppHelper.currentApplication(), "instagram_heart_filled_24");
            if (heartFilled == 0) return false;
        }
        for (Object a : args) if (a instanceof Integer i && i == heartFilled) return true;
        return false;
    }

    /** A Compose state of our own, made the way Android restores one (structural equality). */
    private static Object newState() {
        Parcel p = Parcel.obtain();
        try {
            p.writeValue(0);
            p.writeInt(1);
            p.setDataPosition(0);
            return stateCreator.createFromParcel(p);
        } finally {
            p.recycle();
        }
    }

    /** The like buttons' renders: every caller of the animated heart the reels button builds. */
    private static List<Method> likeRenders(DexKitBridge bridge, ClassLoader loader) throws NoSuchMethodException {
        List<Method> renders = new ArrayList<>();
        for (MethodData reels : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                .usingStrings("clips_ufi_like_button_component").paramCount(1)))) {
            if (!reels.isMethod()) continue;
            for (MethodData call : reels.getInvokes()) {
                if (!call.isConstructor() || !animatedHeart(call.getConstructorInstance(loader))) continue;
                for (MethodData caller : call.getCallers()) {
                    if (caller.isMethod()) renders.add(caller.getMethodInstance(loader));
                }
                return renders;
            }
        }
        return renders;
    }

    /** The animated heart component's constructor: (modifier, a Drawable subclass, size). */
    private static boolean animatedHeart(Constructor<?> c) {
        Class<?>[] p = c.getParameterTypes();
        return p.length == 3 && p[2] == long.class && p[1] != Drawable.class && Drawable.class.isAssignableFrom(p[1]);
    }

    private static int paramIndex(Constructor<?> c, Class<?> type) {
        Class<?>[] p = c.getParameterTypes();
        for (int i = 0; i < p.length; i++) if (p[i] == type) return i;
        return -1;
    }

    private static boolean lastIsBoolean(Constructor<?> c) {
        Class<?>[] p = c.getParameterTypes();
        return p.length > 0 && p[p.length - 1] == boolean.class;
    }

    private static String names(Set<Constructor<?>> made) {
        List<String> names = new ArrayList<>();
        for (Constructor<?> c : made) names.add(c.getDeclaringClass().getName());
        return names.toString();
    }

    /**
     * The animated heart's filled layer: of its two Drawable fields, the one its size does not come
     * from (the outline sets the size and is always drawn; the filled heart fades in over it).
     */
    private static Field filledLayer(DexKitBridge bridge, Class<?> heart) throws Throwable {
        List<Field> drawables = new ArrayList<>();
        for (Field f : heart.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) && f.getType() == Drawable.class) drawables.add(f);
        }
        if (drawables.size() != 2) return null;
        MethodData size = bridge.getMethodData(heart.getDeclaredMethod("getIntrinsicWidth"));
        if (size == null) return null;
        Field outline = null;
        for (var use : size.getUsingFields()) {
            if (!use.getUsingType().isRead()) continue;
            Field f = use.getField().getFieldInstance(Module.hostClassLoader);
            if (drawables.contains(f)) outline = f;
        }
        if (outline == null) return null;
        Field layer = drawables.get(0).equals(outline) ? drawables.get(1) : drawables.get(0);
        layer.setAccessible(true);
        return layer;
    }

    /**
     * The layer of {@code heart} that Instagram paints red: the animated heart's filled layer, or a
     * state list's selected state; null if none.
     */
    private static Drawable redLayer(Drawable heart) {
        try {
            if (filled != null && filled.getDeclaringClass().isInstance(heart)) return (Drawable) filled.get(heart);
            if (heart instanceof StateListDrawable states) {
                if (Build.VERSION.SDK_INT < 29 || states.getStateCount() < 2) return null;
                int i = states.findStateDrawableIndex(SELECTED);
                return i >= 0 && states.getStateSet(i).length > 0 ? states.getStateDrawable(i) : null;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** The first Drawable an object of Instagram's holds in a field (a component's or a wrapper's). */
    private static Drawable drawableIn(Object owner) {
        for (Class<?> c = owner == null ? null : owner.getClass(); c != null && c.getName().startsWith("X."); c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || !Drawable.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    if (f.get(owner) instanceof Drawable d && d != owner) return d;
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    /** The fields from a render's component to its Media: the shortest path through Instagram's own types. */
    static Field[] mediaPath(Class<?> render) {
        List<Class<?>> types = new ArrayList<>(List.of(render));
        List<List<Field>> paths = new ArrayList<>(List.of(List.of()));
        Set<Class<?>> visited = new java.util.HashSet<>(types);
        for (int depth = 0; depth < 3 && !types.isEmpty(); depth++) {
            List<Class<?>> nextTypes = new ArrayList<>();
            List<List<Field>> nextPaths = new ArrayList<>();
            List<List<Field>> found = new ArrayList<>();
            for (int i = 0; i < types.size(); i++) {
                for (Field f : types.get(i).getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    List<Field> path = new ArrayList<>(paths.get(i));
                    path.add(f);
                    if (f.getType().getName().equals("com.instagram.feed.media.Media")) {
                        found.add(path);
                    } else if (f.getType().getName().startsWith("X.") && visited.add(f.getType())) {
                        nextTypes.add(f.getType());
                        nextPaths.add(path);
                    }
                }
            }
            if (found.size() > 1) return null; // two posts: whose heart it is can't be told
            if (found.size() == 1) {
                for (Field f : found.get(0)) f.setAccessible(true);
                return found.get(0).toArray(new Field[0]);
            }
            types = nextTypes;
            paths = nextPaths;
        }
        return null;
    }

    private static Object follow(Object from, Field[] path) {
        try {
            for (Field f : path) {
                if (from == null) return null;
                from = f.get(from);
            }
            return from;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String idOf(Object media) {
        try {
            return media == null || mediaId == null ? null : (String) mediaId.invoke(media);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Instagram built (or rebuilt) {@code heart} for post {@code id}'s like button, on any thread. */
    private static void built(String id, Drawable heart, Drawable red, int kind) {
        if (red == null) return;
        if (seen.getAndIncrement(kind) == 0) {
            ModuleLog.line(TAG + "heart drawn " + KINDS[kind] + " (" + heart.getClass().getName() + ")");
        }
        LikeHeartTint tint;
        synchronized (hearts) {
            Heart known = hearts.get(heart);
            if (known != null && known.red == red) known.id = id;
            else hearts.put(heart, new Heart(red, id));
            if (blue.isEmpty()) return; // nothing is blue, so nothing to paint or put back
            tint = blue.get(id);
        }
        MAIN.post(() -> {
            // A heart recycled for another post must not stay blue.
            for (LikeHeartTint other : blueTints()) {
                if (!other.id.equals(id)) other.unpaint(heart);
            }
            if (tint != null) tint.paint(heart, red);
        });
    }

    private static List<LikeHeartTint> blueTints() {
        synchronized (hearts) {
            return new ArrayList<>(blue.values());
        }
    }

    /**
     * Turns {@code media}'s heart blue until {@link #clear()}; null when the post has no id. Called
     * as soon as the like is held, on any thread, so a heart Instagram redraws in this frame is
     * already blue. Hearts it builds for the post meanwhile turn blue too; a tint already on it is
     * taken over.
     */
    static LikeHeartTint show(Object media) {
        String id = idOf(media);
        if (id == null) {
            ModuleLog.line(TAG + "heart: post without id");
            return null;
        }
        LikeHeartTint tint = new LikeHeartTint(id);
        Map<Drawable, Drawable> found = new IdentityHashMap<>();
        LikeHeartTint old;
        synchronized (hearts) {
            old = blue.put(id, tint);
            for (Map.Entry<Drawable, Heart> e : hearts.entrySet()) {
                if (id.equals(e.getValue().id)) found.put(e.getKey(), e.getValue().red);
            }
        }
        LikeHeartTint previous = old;
        Runnable paint = () -> {
            if (previous != null) {
                tint.painted.putAll(previous.painted);
                previous.painted.clear();
                previous.done = true;
            }
            for (Map.Entry<Drawable, Drawable> e : found.entrySet()) tint.paint(e.getKey(), e.getValue());
        };
        if (Looper.myLooper() == Looper.getMainLooper()) paint.run();
        else MAIN.post(paint);
        ModuleLog.line(TAG + "heart blue (" + found.size() + " drawn; seen animated " + seen.get(ANIMATED)
                + ", state list " + seen.get(STATE_LIST) + ", icon " + seen.get(ICON) + ", compose " + seen.get(COMPOSE) + ")");
        return tint;
    }

    /** Instagram's own heart again (main thread; once); a Compose row recomposes with it. */
    void clear() {
        if (done) return;
        done = true;
        Object recompose;
        synchronized (hearts) {
            if (blue.get(id) == this) blue.remove(id);
            recompose = blue.containsKey(id) ? null : composeStates.remove(id);
        }
        for (Drawable heart : new ArrayList<>(painted.keySet())) unpaint(heart);
        if (recompose == null) return;
        try {
            stateSet.invoke(recompose, 1);
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ Compose state: " + t);
        }
    }

    private void paint(Drawable heart, Drawable red) {
        if (done || painted.containsKey(heart)) return;
        painted.put(heart, red);
        try {
            // Its own state first: a drawable from a resource shares its filter with every copy.
            red.mutate();
            if (!original.containsKey(red)) original.put(red, red.getColorFilter());
            red.setColorFilter(BLUE);
            heart.invalidateSelf();
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ heart: " + t);
        }
    }

    private void unpaint(Drawable heart) {
        Drawable red = painted.remove(heart);
        if (red == null || !original.containsKey(red)) return;
        try {
            red.setColorFilter(original.remove(red));
            heart.invalidateSelf();
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ heart reset: " + t);
        }
    }
}
