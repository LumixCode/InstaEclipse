package ps.reso.instaeclipse.mods.extras;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;

import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.query.matchers.MethodsMatcher;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.Found;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.FoundClass;
import ps.reso.instaeclipse.utils.core.SafeReflect;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;

public class ReelsBlurBarsHook {

    private static final String TAG = "(IE|ReelsBlur) ";
    private static final String STATUS = "reelsBlurBars";
    private static final String CACHE_KEY = "Extras_ReelsVideoLayoutClass";
    private static final String MARKER = "android_purge_26_q2_LithoClipsViewerVideoLayout_init";
    private static final String COVER_GETTER = "getImagePlaceHolder";
    private static final String VIDEO_GETTER = "getVideoPlaceHolder";
    private static final String BITMAP_GETTER = "getBitmap";
    private static final int SCALE = 8;
    private static final float BLUR_RADIUS = 6f;
    private static final int SCRIM = 0x8C000000;

    private static volatile boolean attachFailed;

    public void install(ClassLoader cl) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            ModuleLog.detail(TAG + "skipped: blurring the bars needs Android 12 or newer");
            return;
        }
        try {
            Class<?> layout = layoutClass(cl);
            Method cover = layout == null ? null : coverGetter(layout);
            if (cover == null) {
                unavailable("no class with the video layout's marker or getters");
                return;
            }
            Method bitmap = bitmapGetter(cover.getReturnType());
            int hooked = XposedBridge.hookAllConstructors(layout, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!FeatureFlags.reelsBlurBars || param.hasThrowable()) return;
                    attach(param.thisObject, cover, bitmap);
                }
            }).size();
            if (hooked == 0) {
                unavailable(layout.getName() + " has no constructor to hook");
                return;
            }
            FeatureStatusTracker.setHooked(STATUS);
            ModuleLog.ok(TAG + "ready");
            ModuleLog.detail(TAG + "video layout " + layout.getName() + ", " + hooked + " constructor(s), cover bitmap getter "
                    + (bitmap != null ? "found" : "not found"));
        } catch (Throwable t) {
            ModuleLog.error(TAG + "Will not work: the blurred bars on Reels could not be set up", t);
            if (FeatureFlags.reelsBlurBars) FeatureStatusTracker.setBroken(STATUS);
        }
    }

    private static void unavailable(String detail) {
        ModuleLog.error(TAG + "Will not work: the Reels video area was not found in this Instagram version");
        ModuleLog.detail(TAG + detail);
        if (FeatureFlags.reelsBlurBars) FeatureStatusTracker.setBroken(STATUS);
    }

    private static Class<?> layoutClass(ClassLoader cl) {
        String cached = DexKitCache.isCacheValid() ? DexKitCache.loadString(CACHE_KEY) : null;
        Class<?> layout = cached == null ? null : videoLayout(cached, cl);
        if (layout != null) return layout;
        layout = byMarker(cl);
        if (layout == null) layout = byGetters(cl);
        if (layout != null) DexKitCache.saveString(CACHE_KEY, layout.getName());
        return layout;
    }

    private static Class<?> byMarker(ClassLoader cl) {
        for (Found found : ExtrasLookup.byStrings(MARKER)) {
            if (!found.isConstructor()) continue;
            Class<?> layout = videoLayout(found.getClassName(), cl);
            if (layout != null) return layout;
        }
        return null;
    }

    private static Class<?> byGetters(ClassLoader cl) {
        Class<?> only = null;
        int matches = 0;
        for (FoundClass found : DexKitQueryCache.classes(FindClass.create().matcher(ClassMatcher.create()
                .methods(MethodsMatcher.create()
                        .add(MethodMatcher.create().name(COVER_GETTER))
                        .add(MethodMatcher.create().name(VIDEO_GETTER)))))) {
            Class<?> layout = videoLayout(found.getName(), cl);
            if (layout == null) continue;
            only = layout;
            matches++;
        }
        ModuleLog.detail(TAG + "marker missing, " + matches + " class(es) match the video layout's shape");
        return matches == 1 ? only : null;
    }

    private static Class<?> videoLayout(String name, ClassLoader cl) {
        try {
            Class<?> c = Class.forName(name, false, cl);
            boolean usable = FrameLayout.class.isAssignableFrom(c) && !Modifier.isAbstract(c.getModifiers())
                    && SafeReflect.constructors(c).length > 0 && coverGetter(c) != null;
            return usable ? c : null;
        } catch (Throwable t) {
            ModuleLog.detail(TAG + name + " is not usable: " + t);
            return null;
        }
    }

    private static Method coverGetter(Class<?> layout) {
        try {
            Method m = layout.getMethod(COVER_GETTER);
            return ImageView.class.isAssignableFrom(m.getReturnType()) ? m : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method bitmapGetter(Class<?> coverType) {
        try {
            Method m = coverType.getMethod(BITMAP_GETTER);
            return m.getReturnType() == Bitmap.class && !Modifier.isStatic(m.getModifiers()) ? m : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void attach(Object created, Method coverGetter, Method bitmapGetter) {
        try {
            if (!(created instanceof FrameLayout host)) return;
            if (!(coverGetter.invoke(host) instanceof ImageView cover)) return;
            for (int i = host.getChildCount() - 1; i >= 0; i--) {
                if (host.getChildAt(i) instanceof Backdrop) return;
            }
            host.addView(new Backdrop(host.getContext(), cover, bitmapGetter));
        } catch (Throwable t) {
            if (attachFailed) return;
            attachFailed = true;
            ModuleLog.warn(TAG + "The bars around some reels stay black: the blurred cover could not be added");
            ModuleLog.detail(TAG + "attach failed: " + t);
        }
    }

    static final class Backdrop extends View implements ViewTreeObserver.OnPreDrawListener {

        private final ImageView cover;
        private final Method bitmapGetter;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Rect src = new Rect();
        private final Rect dst = new Rect();
        private ViewTreeObserver watched;
        private Bitmap shown;

        Backdrop(Context context, ImageView cover, Method bitmapGetter) {
            super(context);
            this.cover = cover;
            this.bitmapGetter = bitmapGetter;
            setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT, Gravity.TOP | Gravity.LEFT));
            setTranslationZ(-1f);
            setOutlineProvider(null);
            setPivotX(0f);
            setPivotY(0f);
            setScaleX(SCALE);
            setScaleY(SCALE);
            setClickable(false);
            setLongClickable(false);
            setFocusable(false);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRenderEffect(RenderEffect.createBlurEffect(BLUR_RADIUS, BLUR_RADIUS, Shader.TileMode.CLAMP));
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(part(MeasureSpec.getSize(widthMeasureSpec)), part(MeasureSpec.getSize(heightMeasureSpec)));
        }

        private static int part(int size) {
            return (size + SCALE - 1) / SCALE;
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            return false;
        }

        @Override
        public boolean dispatchGenericMotionEvent(MotionEvent event) {
            return false;
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            try {
                ViewTreeObserver observer = getViewTreeObserver();
                if (!observer.isAlive()) return;
                observer.addOnPreDrawListener(this);
                watched = observer;
            } catch (Throwable ignored) {
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            unwatch();
            shown = null;
            super.onDetachedFromWindow();
        }

        private void unwatch() {
            ViewTreeObserver observer = watched;
            watched = null;
            try {
                if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
                else getViewTreeObserver().removeOnPreDrawListener(this);
            } catch (Throwable ignored) {
            }
        }

        @Override
        public boolean onPreDraw() {
            try {
                if (isAttachedToWindow()) sync();
                else unwatch();
            } catch (Throwable ignored) {
            }
            return true;
        }

        private void sync() {
            boolean on = FeatureFlags.reelsBlurBars;
            Bitmap next = on ? coverBitmap() : null;
            Bitmap now = shown;
            if (next == now) return;
            if (next == null && on && !now.isRecycled()) return;
            shown = next;
            invalidate();
        }

        private Bitmap coverBitmap() {
            Drawable drawable = cover.getDrawable();
            if (drawable instanceof BitmapDrawable bd) {
                Bitmap b = bd.getBitmap();
                if (b != null && !b.isRecycled()) return b;
            }
            if (bitmapGetter == null) return null;
            try {
                return bitmapGetter.invoke(cover, (Object[]) null) instanceof Bitmap b && !b.isRecycled() ? b : null;
            } catch (Throwable t) {
                return null;
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            Bitmap b = shown;
            int w = getWidth();
            int h = getHeight();
            if (b == null || w <= 0 || h <= 0 || !FeatureFlags.reelsBlurBars || !canvas.isHardwareAccelerated()) return;
            try {
                if (b.isRecycled()) return;
                int bw = b.getWidth();
                int bh = b.getHeight();
                if (bw <= 0 || bh <= 0) return;
                if ((long) bw * h > (long) bh * w) {
                    int cw = (int) ((long) bh * w / h);
                    src.set((bw - cw) / 2, 0, (bw + cw) / 2, bh);
                } else {
                    int ch = (int) ((long) bw * h / w);
                    src.set(0, (bh - ch) / 2, bw, (bh + ch) / 2);
                }
                dst.set(0, 0, w, h);
                canvas.drawBitmap(b, src, dst, paint);
                canvas.drawColor(SCRIM);
            } catch (Throwable ignored) {
            }
        }
    }
}
