package ps.reso.instaeclipse.mods.feed;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.ui.ResIds;

/**
 * Adds a long-press-to-zoom overlay to feed photos (issue #174) so photos can be examined
 * full-screen without navigating away to the post's profile/permalink view — Instagram's
 * classic feed row (com.instagram.feed.widget.IgProgressImageView) has no zoom machinery of
 * its own, so this hook grabs the currently-displayed Drawable and shows it in a
 * self-contained pinch/pan zoom overlay (ZoomableImageView) rather than trying to graft in
 * Instagram's own zoom classes (used for profile-picture/Stories zoom), which are tightly
 * coupled to their own render/animation callbacks.
 */
public class FeedPhotoZoomHook {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final String[] VIEW_CLASSES = {
            "com.instagram.common.ui.widget.imageview.IgProgressImageView",
    };

    private static final int FEED_ROW_TAG = 0x7e1e0290;
    private static final long ANIMATION_MS = 150;
    private static final float OPEN_SCALE = 0.92f;

    public void install(ClassLoader classLoader) {
        try {
            ViewBinding binding = ViewBinding.resolve(classLoader, VIEW_CLASSES);
            if (binding == null) {
                ModuleLog.error("(IE|PhotoZoom) Will not work: the feed image view was not found in this Instagram version");
                if (FeatureFlags.enablePhotoZoom) FeatureStatusTracker.setBroken("PhotoZoom");
                return;
            }

            // Hooked at onAttachedToWindow, not the constructor: the view has no parent yet
            // at construction time, so the feed-row ancestor check below would always fail.
            XposedBridge.hookMethod(binding.attach, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    // setOnLongClickListener() has a side effect beyond registering the
                    // callback: it also flips the view's LONG_CLICKABLE flag, which changes
                    // Android's internal tap-vs-long-press disambiguation timing for that
                    // view. Attaching it unconditionally to every IgProgressImageView
                    // instance app-wide was altering touch dispatch on every image view
                    // regardless of whether Photo Zoom was even enabled or whether the view
                    // was actually a feed post — causing intermittent dropped taps on
                    // story-advance and DM media opens. Gate on BOTH the feature flag and a
                    // feed-row identity check BEFORE attaching, not just inside the callback,
                    // so the flag is left untouched everywhere else.
                    if (!FeatureFlags.enablePhotoZoom) return;
                    try {
                        View view = (View) param.thisObject;
                        if (!isInsideFeedRow(view)) return;
                        view.setOnLongClickListener(v -> {
                            if (!FeatureFlags.enablePhotoZoom || !isInsideFeedRow(v)) return false;
                            try {
                                Object imgViewObj = binding.image.invoke(v);
                                if (!(imgViewObj instanceof ImageView)) return false;
                                Bitmap snapshot = sharpBitmap((ImageView) imgViewObj, binding.bitmap);
                                if (snapshot == null) return false;
                                showZoomOverlay(v.getContext(), snapshot);
                                return true;
                            } catch (Throwable t) {
                                ModuleLog.error("(IE|PhotoZoom) Could not open the zoom view: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                                return false;
                            }
                        });
                    } catch (Throwable t) {
                        ModuleLog.warn("(IE|PhotoZoom) Could not add zoom to a feed photo: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                    }
                }
            });

            FeatureStatusTracker.setHooked("PhotoZoom");
            ModuleLog.ok("(IE|PhotoZoom) ready");
            ModuleLog.detail("(IE|PhotoZoom) hook installed on " + binding.attach.getDeclaringClass().getName());
        } catch (Throwable t) {
            ModuleLog.error("(IE|PhotoZoom) Will not work: setup failed", t);
            if (FeatureFlags.enablePhotoZoom) FeatureStatusTracker.setBroken("PhotoZoom");
        }
    }

    /** Resolve a typed child and an owned lifecycle before changing any host listener. */
    static final class ViewBinding {
        final Method attach;
        final Method image;
        final Method bitmap;

        private ViewBinding(Method attach, Method image) {
            this.attach = attach;
            this.image = image;
            this.bitmap = bitmapGetter(image.getReturnType());
        }

        private static Method bitmapGetter(Class<?> type) {
            try {
                Method getter = type.getMethod("getBitmap");
                if (Modifier.isStatic(getter.getModifiers()) || getter.getReturnType() != Bitmap.class) return null;
                getter.setAccessible(true);
                return getter;
            } catch (ReflectiveOperationException | LinkageError e) {
                return null;
            }
        }

        static ViewBinding resolve(ClassLoader loader, String... names) {
            for (String name : names) {
                try {
                    Class<?> type = loader.loadClass(name);
                    if (!ViewGroup.class.isAssignableFrom(type)) continue;
                    Method attach = type.getDeclaredMethod("onAttachedToWindow");
                    Method image = type.getMethod("getIgImageView");
                    if (attach.getReturnType() != void.class
                            || Modifier.isStatic(attach.getModifiers())
                            || Modifier.isAbstract(attach.getModifiers())
                            || Modifier.isStatic(image.getModifiers())
                            || !ImageView.class.isAssignableFrom(image.getReturnType())) continue;
                    attach.setAccessible(true);
                    image.setAccessible(true);
                    return new ViewBinding(attach, image);
                } catch (ReflectiveOperationException | LinkageError ignored) {
                }
            }
            return null;
        }
    }

    /**
     * True only when {@code view} sits inside a classic feed post row — verified by finding
     * "row_feed_button_like" somewhere in the same row's view tree. Walks a bounded number of
     * ancestors (the row root is typically a few levels up) and, at each level, searches that
     * ancestor's own subtree (siblings like the like/save buttons, not just further ancestors).
     */
    private static boolean isInsideFeedRow(View view) {
        if (view.getTag(FEED_ROW_TAG) == Boolean.TRUE) return true;
        int likeButtonId = ResIds.id(view.getContext(), "row_feed_button_like");
        if (likeButtonId == 0) return false;

        ViewParent parent = view.getParent();
        for (int depth = 0; depth < 6 && parent instanceof ViewGroup group; depth++) {
            if (containsDescendantWithId(group, likeButtonId, 4)) {
                view.setTag(FEED_ROW_TAG, Boolean.TRUE);
                return true;
            }
            parent = group.getParent();
        }
        return false;
    }

    private static boolean containsDescendantWithId(ViewGroup group, int targetId, int depth) {
        if (depth < 0) return false;
        int count = group.getChildCount();
        for (int i = 0; i < count; i++) {
            View child = group.getChildAt(i);
            if (child.getId() == targetId) return true;
            if (child instanceof ViewGroup childGroup && containsDescendantWithId(childGroup, targetId, depth - 1)) {
                return true;
            }
        }
        return false;
    }

    private static void showZoomOverlay(Context ctx, Bitmap bitmap) {
        MAIN.post(() -> {
            try {
                show(ctx, bitmap);
            } catch (Throwable t) {
                ModuleLog.error("(IE|PhotoZoom) Could not open the zoom view: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        });
    }

    public static Dialog show(Context ctx, Bitmap bitmap) {
        Dialog dialog = new Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        float dp = ctx.getResources().getDisplayMetrics().density;

        FrameLayout root = new FrameLayout(ctx);
        ColorDrawable backdrop = new ColorDrawable(Color.BLACK);
        root.setBackground(backdrop);

        ZoomableImageView zoomView = new ZoomableImageView(ctx);
        zoomView.setImageBitmap(bitmap);
        zoomView.setContentDescription(I18n.t(ctx, R.string.ig_feat_photo_zoom));
        root.addView(zoomView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ImageView close = new ImageView(ctx);
        close.setImageDrawable(closeIcon(ctx));
        close.setScaleType(ImageView.ScaleType.CENTER);
        close.setContentDescription(I18n.t(ctx, R.string.ig_dialog_close));
        int size = Math.round(48 * dp);
        FrameLayout.LayoutParams closeLp = new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.START);
        int margin = Math.round(8 * dp);
        closeLp.setMargins(margin, margin, margin, margin);
        root.addView(close, closeLp);

        Runnable dismiss = () -> {
            root.animate().alpha(0f).setDuration(ANIMATION_MS).withEndAction(dialog::dismiss).start();
            zoomView.animate().scaleX(OPEN_SCALE).scaleY(OPEN_SCALE).setDuration(ANIMATION_MS).start();
        };
        close.setOnClickListener(v -> dismiss.run());
        zoomView.setOnDismissRequest(dismiss);
        zoomView.setOnSwipeDown(new ZoomableImageView.SwipeListener() {
            @Override public void onSwipe(float fraction) {
                float shown = 1f - Math.min(1f, fraction * 2f);
                backdrop.setAlpha(Math.round(255 * shown));
                close.setAlpha(shown);
            }

            @Override public void onDismiss() {
                dismiss.run();
            }
        });

        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        }
        root.setAlpha(0f);
        zoomView.setScaleX(OPEN_SCALE);
        zoomView.setScaleY(OPEN_SCALE);
        dialog.show();
        root.animate().alpha(1f).setDuration(ANIMATION_MS).start();
        zoomView.animate().scaleX(1f).scaleY(1f).setDuration(ANIMATION_MS).start();
        return dialog;
    }

    private static Drawable closeIcon(Context ctx) {
        Drawable icon = null;
        int id = ResIds.drawable(ctx, "instagram_x_pano_outline_24");
        if (id != 0) {
            try {
                icon = ctx.getDrawable(id);
            } catch (Throwable ignored) {
            }
        }
        if (icon == null) icon = ctx.getDrawable(android.R.drawable.ic_menu_close_clear_cancel);
        if (icon != null) {
            icon = icon.mutate();
            icon.setTint(Color.WHITE);
        }
        return icon;
    }

    private static Bitmap sharpBitmap(ImageView image, Method bitmapGetter) {
        if (bitmapGetter != null) {
            try {
                Object value = bitmapGetter.invoke(image);
                if (value instanceof Bitmap shown && !shown.isRecycled()
                        && shown.getWidth() > 0 && shown.getHeight() > 0) {
                    Bitmap copy = shown.copy(Bitmap.Config.ARGB_8888, false);
                    if (copy != null) return copy;
                }
            } catch (Throwable t) {
                ModuleLog.detail("(IE|PhotoZoom) full image unavailable, using the shown view: " + t.getClass().getSimpleName());
            }
        }
        return viewToBitmap(image);
    }

    // Instagram's feed image view is backed by a Fresco-style drawable that manages its own
    // internal scaling and ignores ImageView's matrix/scaleType, so re-drawing the Drawable
    // directly onto a bare canvas didn't work. Screenshotting the actual View instead captures
    // exactly what's on screen, regardless of how the underlying drawable renders itself.
    private static Bitmap viewToBitmap(View v) {
        int w = v.getWidth(), h = v.getHeight();
        if (w <= 0 || h <= 0) return null;
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        v.draw(canvas);
        return bitmap;
    }
}
