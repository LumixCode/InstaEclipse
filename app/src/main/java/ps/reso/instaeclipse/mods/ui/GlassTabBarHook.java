package ps.reso.instaeclipse.mods.ui;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import androidx.annotation.RequiresApi;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.UsingType;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import ps.reso.instaeclipse.mods.ui.theme.IgColorRemapEngine;
import ps.reso.instaeclipse.mods.ui.theme.IgThemeEngine;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.Found;
import ps.reso.instaeclipse.utils.core.SafeReflect;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.ui.ResIds;
import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;

public final class GlassTabBarHook {

    private static final String TAG = "(IE|GlassTabBar) ";
    private static final String STATUS = "GlassTabBar";
    private static final String MAIN_ACTIVITY = "com.instagram.mainactivity.InstagramMainActivity";
    private static final String SESSION = "com.instagram.common.session.UserSession";
    private static final String APPLY_ANCHOR = "MainTabUtil.applyTabBarStyle";
    private static final String FLOATING = "FLOATING";
    private static final String ASKS_SESSION = "(Lcom/instagram/common/session/UserSession;)Z";
    private static final String INSET_SHAPE = "(Landroid/content/Context;Lcom/instagram/common/session/UserSession;)I";
    private static final String CACHE_GATE = "GlassTabBar_gate";
    private static final String CACHE_LAYOUT = "GlassTabBar_layout";
    private static final String NOT_FOUND =
            "Will not work: Instagram's floating tab bar was not found in this Instagram version, so its normal bar stays";

    private static volatile boolean gateHooked;
    private static volatile boolean confirmed;
    private static volatile boolean glassOff;
    private static volatile Class<?> pillClass;
    private static volatile List<Field> pillPaints;
    private static boolean pillTried;
    private static boolean lifecycleWatched;
    private static volatile boolean layoutNoted;
    private static volatile Verdict verdict;

    private static final Map<Activity, Watch> watches = new WeakHashMap<>();
    private static final Map<View, Glass> glasses = new WeakHashMap<>();

    private record Gate(Method method, List<Method> layoutChecks) {}

    private record Verdict(WeakReference<Object> session, boolean floats) {}

    public void install(ClassLoader cl) {
        if (!FeatureFlags.glassTabBar) return;
        try {
            Class<?> session = Class.forName(SESSION, false, cl);
            Gate gate = cached(cl, session);
            if (gate == null) {
                gate = find(cl, session);
                if (gate == null) {
                    FeatureStatusTracker.setBroken(STATUS);
                    return;
                }
                DexKitCache.saveMethod(CACHE_GATE, gate.method());
                DexKitCache.saveMethods(CACHE_LAYOUT, gate.layoutChecks());
            }
            hookGate(gate);
            gateHooked = true;
            ModuleLog.ok(TAG + "ready");
            ModuleLog.detail(TAG + "floating gate " + gate.method().getDeclaringClass().getName() + "."
                    + gate.method().getName() + ", layout checks " + gate.layoutChecks().size());
            if (UIHookManager.getCurrentActivity() != null) {
                ModuleLog.line(TAG + "Set up after the main screen opened: the glass bar shows from the next Instagram start");
            }
        } catch (Throwable t) {
            FeatureStatusTracker.setBroken(STATUS);
            ModuleLog.warn(TAG + "Will not work: the search for Instagram's floating tab bar failed, so its normal bar stays");
            ModuleLog.detail(TAG + "install: " + t);
        }
    }

    private static Gate cached(ClassLoader cl, Class<?> session) {
        if (!DexKitCache.isCacheValid()) return null;
        Method gate = DexKitCache.loadMethod(CACHE_GATE, cl);
        List<Method> checks = DexKitCache.loadMethods(CACHE_LAYOUT, cl);
        if (gate == null || checks == null || !asksSession(gate, session) || !hasInset(gate.getDeclaringClass(), session)) {
            return null;
        }
        for (Method check : checks) if (!asksSession(check, session)) return null;
        return new Gate(gate, checks);
    }

    private static Gate find(ClassLoader cl, Class<?> session) throws Throwable {
        Method apply = applyMethod(cl, session);
        if (apply == null) return null;
        Class<?> util = apply.getDeclaringClass();
        Class<?> style = apply.getParameterTypes()[2];
        if (!hasConstant(style, FLOATING)) return missing("tab bar styles in " + style.getName() + " have no " + FLOATING);
        Field stored = styleField(util, style);
        if (stored == null) return null;
        String storedDesc = descriptor(util) + "->" + stored.getName() + ":" + descriptor(style);
        List<Found> writers = DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                .declaredClass(MAIN_ACTIVITY).addUsingField(storedDesc, UsingType.Write)));
        if (writers.isEmpty()) return missing("the main screen never stores " + storedDesc);
        Map<String, MethodData> asked = null;
        for (Found writer : writers) {
            Map<String, MethodData> here = new LinkedHashMap<>();
            for (MethodData call : DexKitQueryCache.invokes(writer)) {
                if (asksSession(call)) here.put(call.getDescriptor(), call);
            }
            if (asked == null) asked = here;
            else asked.keySet().retainAll(here.keySet());
        }
        List<MethodData> gates = new ArrayList<>();
        for (MethodData call : asked.values()) if (insetAsks(call)) gates.add(call);
        if (gates.size() != 1) return missing("floating gate candidates: " + gates.size() + " " + asked.keySet());
        MethodData found = gates.get(0);
        Method gate = found.getMethodInstance(cl);
        if (!asksSession(gate, session) || !hasInset(gate.getDeclaringClass(), session)) {
            return missing("floating gate has another shape: " + found.getDescriptor());
        }
        List<Method> checks = new ArrayList<>();
        for (MethodData call : DexKitQueryCache.invokes(found)) {
            if (!asksSession(call) || call.getClassName().equals(found.getClassName())) continue;
            Method check = call.getMethodInstance(cl);
            if (!asksSession(check, session) || checks.contains(check)) continue;
            check.setAccessible(true);
            checks.add(check);
        }
        return new Gate(gate, checks);
    }

    private static Method applyMethod(ClassLoader cl, Class<?> session) throws Throwable {
        Method apply = null;
        for (Found found : DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                .usingStrings(APPLY_ANCHOR)))) {
            if (!found.isMethod() || found.getParamCount() != 3) continue;
            Method m = found.getMethodInstance(cl);
            Class<?>[] p = m.getParameterTypes();
            if (!Modifier.isStatic(m.getModifiers()) || !Activity.class.isAssignableFrom(p[0]) || p[1] != session
                    || !p[2].isEnum()) continue;
            if (apply != null) return missing("more than one method logs " + APPLY_ANCHOR);
            apply = m;
        }
        if (apply == null) return missing("no method logs " + APPLY_ANCHOR);
        return apply;
    }

    private static Field styleField(Class<?> util, Class<?> style) {
        Field stored = null;
        for (Field f : SafeReflect.fields(util)) {
            if (!Modifier.isStatic(f.getModifiers()) || Modifier.isFinal(f.getModifiers()) || f.getType() != style) continue;
            if (stored != null) return missing("more than one stored tab bar style in " + util.getName());
            stored = f;
        }
        if (stored == null) return missing("no stored tab bar style in " + util.getName());
        return stored;
    }

    private static <T> T missing(String detail) {
        ModuleLog.warn(TAG + NOT_FOUND);
        ModuleLog.detail(TAG + detail);
        return null;
    }

    private static boolean hasConstant(Class<?> type, String name) {
        Object[] constants = type.getEnumConstants();
        if (constants == null) return false;
        for (Object constant : constants) if (name.equals(((Enum<?>) constant).name())) return true;
        return false;
    }

    private static String descriptor(Class<?> c) {
        return "L" + c.getName().replace('.', '/') + ";";
    }

    private static boolean asksSession(MethodData call) {
        return call.isMethod() && (call.getModifiers() & Modifier.STATIC) != 0 && call.getDescriptor().endsWith(ASKS_SESSION);
    }

    private static boolean asksSession(Method m, Class<?> session) {
        Class<?>[] p = m.getParameterTypes();
        return Modifier.isStatic(m.getModifiers()) && m.getReturnType() == boolean.class && p.length == 1 && p[0] == session;
    }

    private static boolean insetAsks(MethodData gate) {
        for (MethodData caller : DexKitQueryCache.callersUncached(gate)) {
            if (caller.isMethod() && caller.getClassName().equals(gate.getClassName())
                    && caller.getDescriptor().endsWith(INSET_SHAPE)) return true;
        }
        return false;
    }

    private static boolean hasInset(Class<?> c, Class<?> session) {
        for (Method m : SafeReflect.methods(c)) {
            Class<?>[] p = m.getParameterTypes();
            if (Modifier.isStatic(m.getModifiers()) && m.getReturnType() == int.class && p.length == 2
                    && p[0] == Context.class && p[1] == session) return true;
        }
        return false;
    }

    private static void hookGate(Gate gate) {
        XposedBridge.hookMethod(gate.method(), new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!FeatureFlags.glassTabBar) return;
                try {
                    if (param.hasThrowable()) return;
                    if (!Boolean.TRUE.equals(param.getResult())) {
                        if (!floats(gate.layoutChecks(), param.args[0])) return;
                        param.setResult(true);
                    }
                    confirm();
                } catch (Throwable t) {
                    ModuleLog.detail(TAG + "gate: " + t);
                }
            }
        });
    }

    private static boolean floats(List<Method> layoutChecks, Object session) {
        if (session == null) return false;
        Verdict last = verdict;
        if (last != null && last.session().get() == session) return last.floats();
        boolean kept = false;
        try {
            for (Method check : layoutChecks) kept |= Boolean.TRUE.equals(check.invoke(null, session));
        } catch (Throwable t) {
            kept = true;
            ModuleLog.detail(TAG + "layout check: " + t);
        }
        verdict = new Verdict(new WeakReference<>(session), !kept);
        if (kept && !layoutNoted) {
            layoutNoted = true;
            FeatureStatusTracker.setBroken(STATUS);
            ModuleLog.warn(TAG + "Stays off: Instagram does not offer its floating tab bar with this account's home layout, so its normal bar stays");
        }
        return !kept;
    }

    private static void confirm() {
        if (confirmed) return;
        confirmed = true;
        FeatureStatusTracker.setHooked(STATUS);
        ModuleLog.detail(TAG + "Instagram's floating tab bar is on for this start");
    }

    public static void watchActivity(Activity activity) {
        if (!FeatureFlags.glassTabBar || !gateHooked || glassOff || activity == null) return;
        try {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            Watch watch = watches.get(activity);
            if (watch == null) {
                int barId = ResIds.id(activity, "tab_bar");
                View decor = activity.getWindow() != null ? activity.getWindow().getDecorView() : null;
                if (barId == 0 || decor == null) {
                    if (barId == 0) turnOff("no tab_bar view id", null);
                    return;
                }
                watchLifecycle(activity);
                watch = new Watch(decor, barId);
                watches.put(activity, watch);
                watch.attach();
            }
            watch.refresh(true);
        } catch (Throwable t) {
            turnOff("watch", t);
        }
    }

    private static void watchLifecycle(Activity activity) {
        if (lifecycleWatched) return;
        activity.getApplication().registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityDestroyed(Activity a) { unwatch(a); }
            @Override public void onActivityCreated(Activity a, Bundle b) {}
            @Override public void onActivityStarted(Activity a) {}
            @Override public void onActivityResumed(Activity a) {}
            @Override public void onActivityPaused(Activity a) {}
            @Override public void onActivityStopped(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
        });
        lifecycleWatched = true;
    }

    private static void unwatch(Activity activity) {
        try {
            Watch watch = watches.remove(activity);
            if (watch != null) watch.detach();
        } catch (Throwable t) {
            ModuleLog.detail(TAG + "unwatch: " + t);
        }
    }

    private static void turnOff(String where, Throwable t) {
        if (glassOff) return;
        glassOff = true;
        ModuleLog.warn(TAG + "The glass look is off until Instagram restarts; Instagram's own floating bar is used instead");
        ModuleLog.detail(TAG + where + (t != null ? ": " + t : ""));
    }

    private static final class Watch implements ViewTreeObserver.OnScrollChangedListener,
            ViewTreeObserver.OnGlobalLayoutListener {
        private final WeakReference<View> decor;
        private final int barId;
        private WeakReference<View> bar = new WeakReference<>(null);

        Watch(View decor, int barId) {
            this.decor = new WeakReference<>(decor);
            this.barId = barId;
        }

        void attach() {
            View root = decor.get();
            if (root == null) return;
            ViewTreeObserver observer = root.getViewTreeObserver();
            observer.addOnScrollChangedListener(this);
            observer.addOnGlobalLayoutListener(this);
        }

        void detach() {
            View root = decor.get();
            ViewTreeObserver observer = root != null ? root.getViewTreeObserver() : null;
            if (observer != null && observer.isAlive()) {
                observer.removeOnScrollChangedListener(this);
                observer.removeOnGlobalLayoutListener(this);
            }
            View b = bar.get();
            if (b != null) release(b);
        }

        @Override
        public void onScrollChanged() {
            refresh(false);
        }

        @Override
        public void onGlobalLayout() {
            refresh(true);
        }

        void refresh(boolean layout) {
            if (!FeatureFlags.glassTabBar || glassOff) return;
            try {
                View b = bar.get();
                if (layout && (b == null || !b.isAttachedToWindow())) {
                    View root = decor.get();
                    b = root != null ? root.findViewById(barId) : null;
                    bar = new WeakReference<>(b);
                }
                if (b == null) return;
                if (layout) hookPill(b);
                if (blurs(b)) b.invalidate();
            } catch (Throwable t) {
                turnOff("refresh", t);
            }
        }
    }

    private static boolean blurs(View bar) {
        Class<?> pill = pillClass;
        if (pill == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !bar.isHardwareAccelerated() || !bar.isShown()) {
            return false;
        }
        return bar.getBackground() instanceof InsetDrawable inset && inset.getDrawable() != null
                && inset.getDrawable().getClass() == pill;
    }

    private static void hookPill(View bar) {
        if (pillTried || !(bar.getBackground() instanceof InsetDrawable inset)) return;
        Drawable pill = inset.getDrawable();
        if (pill == null || pill.getClass().getName().startsWith("android.")) return;
        pillTried = true;
        Class<?> c = pill.getClass();
        try {
            Method draw = c.getDeclaredMethod("draw", Canvas.class);
            List<Field> paints = new ArrayList<>();
            for (Field f : SafeReflect.fields(c)) {
                if (f.getType() != Paint.class || Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                paints.add(f);
            }
            if (paints.isEmpty()) throw new NoSuchFieldException("no paint in " + c.getName());
            pillPaints = paints;
            XposedBridge.hookMethod(draw, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!FeatureFlags.glassTabBar || glassOff) return;
                    try {
                        if (paint((Drawable) param.thisObject, (Canvas) param.args[0])) param.setResult(null);
                    } catch (Throwable t) {
                        turnOff("draw", t);
                    }
                }
            });
            pillClass = c;
            ModuleLog.detail(TAG + "pill drawable " + c.getName() + " (" + paints.size() + " paints)");
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "The glass look is not available in this Instagram version; Instagram's own floating bar is used instead");
            ModuleLog.detail(TAG + "pill " + c.getName() + ": " + t);
        }
    }

    private static boolean paint(Drawable pill, Canvas canvas) throws IllegalAccessException {
        View bar = hostBar(pill);
        if (bar == null) return false;
        Rect bounds = pill.getBounds();
        if (bounds.isEmpty()) return false;
        Integer color = fillColor(pill);
        if (color == null) return false;
        Glass glass;
        synchronized (glasses) {
            glass = glasses.get(bar);
            if (glass == null) {
                glass = new Glass(bar.getResources().getDisplayMetrics().density);
                glasses.put(bar, glass);
            }
        }
        glass.draw(canvas, bounds, bar, color);
        return true;
    }

    private static void release(View bar) {
        Glass glass;
        synchronized (glasses) {
            glass = glasses.remove(bar);
        }
        if (glass != null) glass.release();
    }

    private static View hostBar(Drawable pill) {
        Drawable.Callback host = pill.getCallback();
        if (host instanceof Drawable wrapper) host = wrapper.getCallback();
        return host instanceof View v && ResIds.is(v, "tab_bar") ? v : null;
    }

    private static Integer fillColor(Drawable pill) throws IllegalAccessException {
        List<Field> paints = pillPaints;
        if (paints == null) return null;
        Integer color = null;
        for (Field f : paints) {
            Paint p = (Paint) f.get(pill);
            if (p == null) continue;
            boolean black = (p.getColor() | 0xFF000000) == Color.BLACK;
            if (color == null || !black) color = p.getColor();
            if (!black) break;
        }
        if (color == null) return null;
        return IgThemeEngine.isActive() ? IgColorRemapEngine.remap(color) : color;
    }

    private static final class Glass {
        private final float density;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private Blur blur;
        private boolean drawing;

        Glass(float density) {
            this.density = density;
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(Math.max(1f, density * 0.75f));
        }

        void draw(Canvas canvas, Rect bounds, View bar, int color) {
            rect.set(bounds);
            float radius = rect.height() / 2f;
            boolean dark = luminance(color) < 0.5f;
            boolean blurred = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && canvas.isHardwareAccelerated() && !drawing) {
                drawing = true;
                try {
                    if (blur == null) blur = new Blur(density);
                    blurred = blur.draw(canvas, bounds, bar, color | 0xFF000000, radius);
                } finally {
                    drawing = false;
                }
            }
            float alpha = blurred ? (dark ? 0.55f : 0.68f) : 0.86f;
            fill.setColor((color & 0x00FFFFFF) | (Math.round(Color.alpha(color) * alpha) << 24));
            canvas.drawRoundRect(rect, radius, radius, fill);
            edge.setColor(dark ? 0x26FFFFFF : 0x14000000);
            float inset = edge.getStrokeWidth() / 2f;
            rect.inset(inset, inset);
            canvas.drawRoundRect(rect, radius - inset, radius - inset, edge);
        }

        void release() {
            if (blur != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) blur.release();
        }

        private static float luminance(int color) {
            return (0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color)) / 255f;
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private static final class Blur {
        private final RenderNode clip = new RenderNode("ie_glass_clip");
        private final RenderNode content = new RenderNode("ie_glass_blur");
        private final Outline outline = new Outline();

        Blur(float density) {
            float radius = 20f * density;
            content.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
            clip.setClipToOutline(true);
        }

        boolean draw(Canvas canvas, Rect bounds, View bar, int opaque, float radius) {
            if (!(bar.getParent() instanceof ViewGroup parent)) return false;
            int w = bounds.width(), h = bounds.height();
            content.setPosition(0, 0, w, h);
            RecordingCanvas behind = content.beginRecording(w, h);
            try {
                behind.drawColor(opaque);
                float left = bar.getLeft() + bar.getTranslationX() + bounds.left;
                float top = bar.getTop() + bar.getTranslationY() + bounds.top;
                int shadowId = ResIds.id(bar.getContext(), "tab_bar_shadow");
                int count = parent.indexOfChild(bar);
                for (int i = 0; i < count; i++) {
                    View v = parent.getChildAt(i);
                    if (v.getVisibility() != View.VISIBLE || v.getAlpha() <= 0f) continue;
                    if (shadowId != 0 && v.getId() == shadowId) continue;
                    float dx = v.getLeft() + v.getTranslationX() - left;
                    float dy = v.getTop() + v.getTranslationY() - top;
                    if (dx >= w || dy >= h || dx + v.getWidth() <= 0 || dy + v.getHeight() <= 0) continue;
                    int save = behind.save();
                    behind.translate(dx - v.getScrollX(), dy - v.getScrollY());
                    v.draw(behind);
                    behind.restoreToCount(save);
                }
            } finally {
                content.endRecording();
            }
            clip.setPosition(bounds.left, bounds.top, bounds.right, bounds.bottom);
            outline.setRoundRect(0, 0, w, h, radius);
            clip.setOutline(outline);
            RecordingCanvas rounded = clip.beginRecording(w, h);
            try {
                rounded.drawRenderNode(content);
            } finally {
                clip.endRecording();
            }
            canvas.drawRenderNode(clip);
            return true;
        }

        void release() {
            content.discardDisplayList();
            clip.discardDisplayList();
        }
    }
}
