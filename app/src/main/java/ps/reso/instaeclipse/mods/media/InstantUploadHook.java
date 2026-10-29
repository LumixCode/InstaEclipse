package ps.reso.instaeclipse.mods.media;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.WindowInsets;
import android.net.Uri;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public class InstantUploadHook {

    private static final String VM_CLASS =
            "com.instagram.quicksnap.camera.domain.QuickSnapCameraViewModel";
    private static final int PICK_REQUEST = 0x1E5A;

    static volatile Bitmap pendingBitmap;
    private static volatile long pendingSetAt = 0;
    private static volatile java.lang.ref.WeakReference<Activity> pendingHost;
    private static final long PENDING_TTL_MS = 5 * 60 * 1000;
    private static final Runnable EXPIRE_PICK = InstantUploadHook::dropPick;
    private static FrameLayout chip;
    private static View preview;
    private static FrameLayout chipBtn;
    private static View chipIcon;
    private static ImageView chipThumb;
    private static View chipBadge;
    private static Activity chipHost;
    private static boolean lifecycleRegistered = false;
    private static volatile boolean pickerOpen = false;

    public void install(ClassLoader cl) {
        hookSwap(cl);
        hookCameraOpen(cl);
        hookPickerResult();
    }

    private void hookSwap(ClassLoader cl) {
        try {
            Class<?> vm = XposedHelpers.findClass(VM_CLASS, cl);
            SwapBinding binding = SwapBinding.resolve(vm, android.content.Context.class, Bitmap.class);
            if (binding == null) {
                ModuleLog.error("(IE|InstantUpload) Could not turn on sending gallery photos as Instants: the camera's photo handoff was not found in this Instagram version");
                ModuleLog.detail("(IE|InstantUpload) photo handoff unresolved or ambiguous");
                if (FeatureFlags.uploadInstants) FeatureStatusTracker.setBroken("UploadInstants");
                return;
            }
            XC_MethodHook swap = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (!binding.accepts(p.args)) return;
                        Bitmap selected = pendingBitmap;
                        Activity host = pickHost();
                        if (selected != null && (System.currentTimeMillis() - pendingSetAt > PENDING_TTL_MS
                                || host == null || host.isDestroyed())) {
                            clearPick();
                            FeedVideoDownloadHook.mainHandler.post(InstantUploadHook::removeChip);
                            selected = null;
                        }
                        if (FeatureFlags.uploadInstants && selected != null) {
                            Bitmap secondary = null;
                            if (binding.needsSecondary(p.args)) {
                                Bitmap.Config config = selected.getConfig();
                                secondary = selected.copy(config != null ? config : Bitmap.Config.ARGB_8888, false);
                            }
                            if (!binding.replace(p.args, selected, secondary)) return;
                            if (pendingBitmap == selected) clearPick();
                            FeedVideoDownloadHook.mainHandler.post(InstantUploadHook::removeChip);
                            ModuleLog.line("(IE|InstantUpload) Sent the gallery photo as this Instant");
                        }
                    } catch (Throwable t) {
                        ModuleLog.error("(IE|InstantUpload) Could not use the gallery photo for this Instant", t);
                    }
                }
            };
            binding.method.setAccessible(true);
            XposedBridge.hookMethod(binding.method, swap);
            FeatureStatusTracker.setHooked("UploadInstants");
            ModuleLog.ok("(IE|InstantUpload) ready");
            ModuleLog.detail("(IE|InstantUpload) capture handler hooked: " + binding.method.getName());
        } catch (Throwable t) {
            ModuleLog.error("(IE|InstantUpload) Could not turn on sending gallery photos as Instants: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            if (FeatureFlags.uploadInstants) FeatureStatusTracker.setBroken("UploadInstants");
        }
    }

    static final class SwapBinding {
        final Method method;
        final int secondaryBitmapIndex;
        private final Class<?> bitmapType;
        private final Class<?> vmType;
        private final int vmIndex;

        private SwapBinding(Method method, Class<?> bitmapType, int secondaryBitmapIndex, int vmIndex) {
            this.method = method;
            this.bitmapType = bitmapType;
            this.vmType = method.getDeclaringClass();
            this.vmIndex = vmIndex;
            this.secondaryBitmapIndex = secondaryBitmapIndex;
        }

        static SwapBinding resolve(Class<?> vm, Class<?> contextType, Class<?> bitmapType) {
            SwapBinding found = null;
            for (Method method : ps.reso.instaeclipse.utils.core.SafeReflect.methods(vm)) {
                if (!Modifier.isStatic(method.getModifiers()) || method.getReturnType() != Object.class) continue;
                Class<?>[] p = method.getParameterTypes();
                if (p.length < 4 || p[0] != contextType || p[1] != bitmapType) continue;
                boolean legacy = p.length == 9 && p[2] == bitmapType && p[3] == vm
                        && modelType(p[4], bitmapType) && p[5] == String.class && p[6] == String.class
                        && modelType(p[7], bitmapType) && p[8] == long.class;
                boolean modern = modelType(p[2], bitmapType) && p[2] != vm;
                int bitmaps = 0, owners = 0, ownerIndex = -1;
                for (int i = 0; i < p.length; i++) {
                    Class<?> type = p[i];
                    if (type == bitmapType) bitmaps++;
                    if (type == vm) { owners++; ownerIndex = i; }
                    if (type == java.io.File.class) modern = false;
                }
                legacy &= bitmaps == 2 && owners == 1;
                modern &= bitmaps == 1 && owners == 1;
                if (!legacy && !modern) continue;
                if (found != null) return null;
                found = new SwapBinding(method, bitmapType, legacy ? 2 : -1, legacy ? 3 : ownerIndex);
            }
            return found;
        }

        private static boolean modelType(Class<?> type, Class<?> bitmapType) {
            return !type.isPrimitive() && type != bitmapType && type != String.class
                    && type != Object.class && type != java.io.File.class;
        }

        boolean accepts(Object[] args) {
            return args != null && args.length == method.getParameterCount()
                    && bitmapType.isInstance(args[1]) && vmType.isInstance(args[vmIndex])
                    && (secondaryBitmapIndex < 0 || args[secondaryBitmapIndex] == null
                    || bitmapType.isInstance(args[secondaryBitmapIndex]));
        }

        boolean needsSecondary(Object[] args) {
            return accepts(args) && secondaryBitmapIndex >= 0 && args[secondaryBitmapIndex] != null;
        }

        boolean replace(Object[] args, Object selected, Object secondary) {
            if (!accepts(args) || !bitmapType.isInstance(selected)
                    || (needsSecondary(args) && !bitmapType.isInstance(secondary))) return false;
            boolean replaceSecondary = needsSecondary(args);
            args[1] = selected;
            if (replaceSecondary) args[secondaryBitmapIndex] = secondary;
            return true;
        }
    }

    private void hookCameraOpen(ClassLoader cl) {
        registerLifecycle(cl);
        try {
            Class<?> vm = XposedHelpers.findClass(VM_CLASS, cl);
            XC_MethodHook onActive = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    quickSnapActiveAt = android.os.SystemClock.uptimeMillis();
                    if (!FeatureFlags.uploadInstants) return;
                    Activity act = currentActivity();
                    if (act != null) FeedVideoDownloadHook.mainHandler.post(() -> injectChip(act));
                }
            };
            int n = 0;
            for (Method m : ps.reso.instaeclipse.utils.core.SafeReflect.methods(vm)) {
                if (Modifier.isStatic(m.getModifiers())) continue;
                if (m.getParameterCount() != 0) continue;
                if (m.getReturnType() != void.class) continue;
                if (m.isSynthetic() || m.isBridge()) continue;
                try { XposedBridge.hookMethod(m, onActive); n++; } catch (Throwable ignored) {}
            }
            ModuleLog.detail("(IE|InstantUpload) camera-open hooked (" + n + " VM methods)");
        } catch (Throwable t) {
            ModuleLog.error("(IE|InstantUpload) The gallery button will not appear on the Instants camera: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private void registerLifecycle(ClassLoader cl) {
        registerLifecycle(0);
    }

    private static void registerLifecycle(int attempt) {
        if (lifecycleRegistered) return;
        try {
            android.app.Application app = (android.app.Application)
                    ps.reso.instaeclipse.xposedcompat.AndroidAppHelper.currentApplication();
            if (app == null) {
                if (attempt < 30) FeedVideoDownloadHook.mainHandler.postDelayed(() -> registerLifecycle(attempt + 1), 1000);
                else ModuleLog.warn("(IE|InstantUpload) The gallery button may stay on screen after leaving the camera: Instagram was not ready in time");
                return;
            }
            app.registerActivityLifecycleCallbacks(new Lifecycle());
            lifecycleRegistered = true;
            ModuleLog.detail("(IE|InstantUpload) lifecycle registered");
        } catch (Throwable t) {
            ModuleLog.warn("(IE|InstantUpload) The gallery button may stay on screen after leaving the camera: " + t);
        }
    }

    static final class Lifecycle implements android.app.Application.ActivityLifecycleCallbacks {
        @Override public void onActivityPaused(Activity a) {
            if (a == watchHost && !pickerOpen) stopWatch();
            if (a == chipHost && !pickerOpen) removeChip();
            if (a == pickHost() && !pickerOpen) dropPick();
        }
        @Override public void onActivityStopped(Activity a) {
            if (a == chipHost && !pickerOpen) removeChip();
            if (a == pickHost() && !pickerOpen) dropPick();
        }
        @Override public void onActivityDestroyed(Activity a) {
            if (a == watchHost) stopWatch();
            if (a == chipHost) { pickerOpen = false; removeChip(); }
            if (a == pickHost()) dropPick();
        }
        @Override public void onActivityResumed(Activity a) {
            if (chip != null && a != chipHost) removeChip();
            if (pendingBitmap != null && a != pickHost()) dropPick();
            if (FeatureFlags.uploadInstants && a.getClass().getName().contains("ModalActivity")) startWatch(a);
        }
        @Override public void onActivityCreated(Activity a, android.os.Bundle b) {}
        @Override public void onActivityStarted(Activity a) {}
        @Override public void onActivitySaveInstanceState(Activity a, android.os.Bundle b) {}
    }

    static void armPick(Bitmap bmp, Activity host) {
        pendingSetAt = System.currentTimeMillis();
        pendingHost = new java.lang.ref.WeakReference<>(host);
        pendingBitmap = bmp;
        FeedVideoDownloadHook.mainHandler.removeCallbacks(EXPIRE_PICK);
        FeedVideoDownloadHook.mainHandler.postDelayed(EXPIRE_PICK, PENDING_TTL_MS);
    }

    private static Activity pickHost() {
        java.lang.ref.WeakReference<Activity> host = pendingHost;
        return host != null ? host.get() : null;
    }

    static void clearPick() {
        pendingBitmap = null;
        pendingHost = null;
        pendingSetAt = 0;
        FeedVideoDownloadHook.mainHandler.removeCallbacks(EXPIRE_PICK);
    }

    private static void dropPick() {
        clearPick();
        removeChip();
    }

    private static void markChipReady(Activity act, Bitmap bmp) {
        try {
            View decor = act.getWindow().getDecorView();
            removePreview();
            if (decor instanceof ViewGroup) {
                TextView hint = new TextView(act);
                hint.setText(I18n.t(act, R.string.ig_instant_upload_chip_ready));
                hint.setTextColor(Color.WHITE);
                hint.setTextSize(13);
                hint.setSingleLine(true);
                hint.setGravity(Gravity.CENTER_VERTICAL);
                int padH = dp(act, 12), padV = dp(act, 6);
                hint.setPaddingRelative(dp(act, 8), padV, padH, padV);
                Drawable check = moduleDrawable(act, R.drawable.ic_check_circle);
                if (check != null) {
                    check = check.mutate();
                    check.setTint(GREEN);
                    int s = dp(act, 16);
                    check.setBounds(0, 0, s, s);
                    hint.setCompoundDrawablesRelative(check, null, null, null);
                    hint.setCompoundDrawablePadding(dp(act, 6));
                }
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(Color.parseColor("#A6000000"));
                bg.setStroke(dp(act, 1), Color.parseColor("#26FFFFFF"));
                bg.setCornerRadius(dp(act, 16));
                hint.setBackground(bg);
                hint.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                lp.bottomMargin = navInset(act, decor) + dp(act, 198);
                hint.setLayoutParams(lp);
                hint.setVisibility(chip != null ? chip.getVisibility() : View.VISIBLE);
                hint.setAlpha(0f);
                ((ViewGroup) decor).addView(hint);
                hint.animate().alpha(1f).setDuration(180).start();
                preview = hint;
            }
            applyChipState(act, bmp);
            if (chip != null) chip.bringToFront();
        } catch (Throwable ignored) {}
    }

    private static final int GREEN = Color.parseColor("#30D158");

    private static void applyChipState(Activity act, Bitmap bmp) {
        if (chip == null || chipBtn == null) return;
        boolean ready = bmp != null;
        GradientDrawable disc = new GradientDrawable();
        disc.setShape(GradientDrawable.OVAL);
        disc.setColor(Color.parseColor("#33FFFFFF"));
        disc.setStroke(dp(act, ready ? 2 : 1), ready ? Color.WHITE : Color.parseColor("#26FFFFFF"));
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(Color.WHITE);
        chipBtn.setBackground(new RippleDrawable(
                ColorStateList.valueOf(Color.parseColor("#40FFFFFF")), disc, mask));
        if (chipThumb != null) {
            chipThumb.setImageBitmap(ready ? bmp : null);
            chipThumb.setVisibility(ready ? View.VISIBLE : View.GONE);
        }
        if (chipIcon != null) chipIcon.setVisibility(ready ? View.GONE : View.VISIBLE);
        if (chipBadge != null) {
            chipBadge.setVisibility(ready ? View.VISIBLE : View.GONE);
            if (ready) {
                chipBadge.setScaleX(0.4f);
                chipBadge.setScaleY(0.4f);
                chipBadge.animate().scaleX(1f).scaleY(1f).setDuration(200)
                        .setInterpolator(new android.view.animation.OvershootInterpolator()).start();
            }
        }
        chipBtn.setContentDescription(I18n.t(act,
                ready ? R.string.ig_instant_upload_chip_ready : R.string.ig_instant_upload_chip));
    }

    private static void removePreview() {
        try {
            if (preview != null && preview.getParent() instanceof ViewGroup)
                ((ViewGroup) preview.getParent()).removeView(preview);
        } catch (Throwable ignored) {}
        preview = null;
    }

    private static Drawable moduleDrawable(Activity act, int id) {
        try {
            return I18n.moduleResources(act).getDrawable(id, null);
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressLint({"DiscouragedApi", "InternalInsetResource"})
    private static int navInset(Activity act, View decor) {
        try {
            WindowInsets wi = decor.getRootWindowInsets();
            if (wi != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    return wi.getInsets(WindowInsets.Type.navigationBars()).bottom;
                return wi.getSystemWindowInsetBottom();
            }
        } catch (Throwable ignored) {}
        try {
            int id = act.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
            if (id != 0) return act.getResources().getDimensionPixelSize(id);
        } catch (Throwable ignored) {}
        return dp(act, 48);
    }

    private static void removeChip() {
        try {
            if (chip != null && chip.getParent() instanceof ViewGroup)
                ((ViewGroup) chip.getParent()).removeView(chip);
        } catch (Throwable ignored) {}
        try {
            if (preview != null && preview.getParent() instanceof ViewGroup)
                ((ViewGroup) preview.getParent()).removeView(preview);
        } catch (Throwable ignored) {}
        chip = null;
        preview = null;
        chipBtn = null;
        chipIcon = null;
        chipThumb = null;
        chipBadge = null;
        chipHost = null;
    }

    private static void injectChip(Activity act) {
        try {
            if (!act.getClass().getName().contains("ModalActivity")) return;
            if (chip != null && chipHost == act) return;
            removeChip();

            View decor = act.getWindow().getDecorView();
            if (!(decor instanceof ViewGroup)) return;

            int btnSize = dp(act, 48), badgeSize = dp(act, 18), box = btnSize + dp(act, 6);
            FrameLayout c = new FrameLayout(act);
            c.setClipChildren(false);
            c.setClipToPadding(false);

            FrameLayout btn = new FrameLayout(act);
            btn.setLayoutParams(new FrameLayout.LayoutParams(btnSize, btnSize, Gravity.CENTER));
            btn.setClickable(true);
            btn.setFocusable(true);
            btn.setOnClickListener(v -> launchPicker(act));
            btn.setOnTouchListener((v, e) -> {
                int a = e.getActionMasked();
                if (a == android.view.MotionEvent.ACTION_DOWN)
                    v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(90).start();
                else if (a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL)
                    v.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
                return false;
            });

            ImageView thumb = new ImageView(act);
            thumb.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            int ring = dp(act, 2);
            thumb.setPadding(ring, ring, ring, ring);
            thumb.setCropToPadding(true);
            thumb.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override public void getOutline(View v, android.graphics.Outline o) {
                    int r = dp(act, 2);
                    o.setOval(r, r, v.getWidth() - r, v.getHeight() - r);
                }
            });
            thumb.setClipToOutline(true);
            thumb.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            thumb.setVisibility(View.GONE);
            btn.addView(thumb);

            View icon;
            Drawable glyph = moduleDrawable(act, R.drawable.ic_gallery);
            if (glyph != null) {
                ImageView iv = new ImageView(act);
                iv.setImageDrawable(glyph);
                iv.setImageTintList(ColorStateList.valueOf(Color.WHITE));
                int s = dp(act, 24);
                iv.setLayoutParams(new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
                icon = iv;
            } else {
                TextView tv = new TextView(act);
                tv.setText("🖼");
                tv.setTextSize(18);
                tv.setLayoutParams(new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
                icon = tv;
            }
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            btn.addView(icon);
            c.addView(btn);

            ImageView badge = new ImageView(act);
            Drawable check = moduleDrawable(act, R.drawable.ic_check_circle);
            if (check != null) badge.setImageDrawable(check);
            badge.setImageTintList(ColorStateList.valueOf(GREEN));
            GradientDrawable badgeBg = new GradientDrawable();
            badgeBg.setShape(GradientDrawable.OVAL);
            badgeBg.setColor(Color.WHITE);
            badgeBg.setStroke(dp(act, 1), Color.BLACK);
            badge.setBackground(badgeBg);
            badge.setLayoutParams(new FrameLayout.LayoutParams(badgeSize, badgeSize, Gravity.TOP | Gravity.END));
            badge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            badge.setVisibility(View.GONE);
            c.addView(badge);

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(box, box);
            lp.gravity = Gravity.BOTTOM | Gravity.LEFT;
            int width = decor.getWidth() > 0 ? decor.getWidth() : act.getResources().getDisplayMetrics().widthPixels;
            lp.leftMargin = Math.max(dp(act, 8), width / 2 - dp(act, 106) - box / 2);
            lp.bottomMargin = navInset(act, decor) + dp(act, 40) - box / 2;
            c.setLayoutParams(lp);

            c.setAlpha(0f);
            ((ViewGroup) decor).addView(c);
            c.animate().alpha(1f).setDuration(160).start();
            chip = c;
            chipBtn = btn;
            chipIcon = icon;
            chipThumb = thumb;
            chipBadge = badge;
            chipHost = act;
            applyChipState(act, null);
            watchCamera();
            Bitmap pick = pendingBitmap;
            if (pick != null && pickHost() == act) markChipReady(act, pick);
            ModuleLog.detail("(IE|InstantUpload) gallery chip injected");
        } catch (Throwable t) {
            ModuleLog.error("(IE|InstantUpload) Could not show the gallery button on the camera: " + t);
        }
    }

    private static final long WATCH_MS = 300;
    private static int cameraViewId;
    private static Activity watchHost;
    private static volatile long quickSnapActiveAt;
    private static volatile long watchStartedAt;
    private static final Runnable WATCH = new Runnable() {
        @Override public void run() {
            Activity host = watchHost;
            if (host == null || !FeatureFlags.uploadInstants) return;
            boolean hasCam = hasCameraView(host);
            boolean on = hasCam && (quickSnapActiveAt >= watchStartedAt - 2000 || isQuickSnapCamera(host))
                    && cameraOnScreen(host);
            if (on && (chip == null || chipHost != host)) injectChip(host);
            if (chip != null && chipHost == host) {
                int vis = on || pickerOpen ? View.VISIBLE : View.GONE;
                if (chip.getVisibility() != vis) chip.setVisibility(vis);
                if (preview != null && preview.getVisibility() != vis) preview.setVisibility(vis);
            }
            FeedVideoDownloadHook.mainHandler.postDelayed(this, hasCam ? WATCH_MS : 1000);
        }
    };

    private static void startWatch(Activity act) {
        if (act != watchHost) watchStartedAt = android.os.SystemClock.uptimeMillis();
        watchHost = act;
        FeedVideoDownloadHook.mainHandler.removeCallbacks(WATCH);
        FeedVideoDownloadHook.mainHandler.post(WATCH);
    }

    private static void stopWatch() {
        watchHost = null;
        FeedVideoDownloadHook.mainHandler.removeCallbacks(WATCH);
    }

    private static void watchCamera() {
        if (chipHost != null && watchHost != chipHost) startWatch(chipHost);
    }

    @SuppressLint("DiscouragedApi")
    private static boolean cameraOnScreen(Activity act) {
        try {
            if (cameraViewId == 0) cameraViewId = act.getResources().getIdentifier("camera_view", "id", act.getPackageName());
            View cam = cameraViewId == 0 ? null : act.getWindow().getDecorView().findViewById(cameraViewId);
            if (cam == null) return true;
            if (!cam.isShown() || cam.getWidth() == 0) return false;
            int[] at = new int[2];
            cam.getLocationOnScreen(at);
            return Math.abs(at[0]) < cam.getWidth() / 2;
        } catch (Throwable t) {
            return true;
        }
    }

    private static final java.util.Map<Activity, Boolean> quickSnapHosts = new java.util.WeakHashMap<>();
    private static final java.util.Map<Activity, Long> quickSnapMisses = new java.util.WeakHashMap<>();

    private static boolean isQuickSnapCamera(Activity act) {
        if (Boolean.TRUE.equals(quickSnapHosts.get(act))) return true;
        long now = android.os.SystemClock.uptimeMillis();
        if (!CameraCheck.due(quickSnapMisses.get(act), now, quickSnapActiveAt)) return false;
        boolean found = hasQuickSnapView(act.getWindow().getDecorView(), 0);
        if (found) {
            quickSnapHosts.put(act, true);
            quickSnapMisses.remove(act);
        } else {
            quickSnapMisses.put(act, now);
        }
        return found;
    }

    static final class CameraCheck {
        static final long RECHECK_MS = 1000;

        private CameraCheck() {}

        static boolean due(Long missedAt, long now, long viewModelAt) {
            return missedAt == null || now - missedAt >= RECHECK_MS || viewModelAt > missedAt;
        }
    }

    private static boolean hasQuickSnapView(View v, int depth) {
        if (v.getClass().getName().startsWith("com.instagram.quicksnap.camera.ui.")) return true;
        if (depth > 40 || !(v instanceof android.view.ViewGroup g)) return false;
        for (int i = 0; i < g.getChildCount(); i++) if (hasQuickSnapView(g.getChildAt(i), depth + 1)) return true;
        return false;
    }

    @SuppressLint("DiscouragedApi")
    private static boolean hasCameraView(Activity act) {
        try {
            if (cameraViewId == 0) cameraViewId = act.getResources().getIdentifier("camera_view", "id", act.getPackageName());
            return cameraViewId != 0 && act.getWindow().getDecorView().findViewById(cameraViewId) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void launchPicker(Activity act) {
        try {
            pickerOpen = true;
            act.startActivityForResult(InstantPicker.intent(Build.VERSION.SDK_INT, InstantPicker.extension()), PICK_REQUEST);
        } catch (Throwable t) {
            pickerOpen = false;
            ps.reso.instaeclipse.utils.toast.IeToast.makeText(act, I18n.t(act, R.string.ig_instant_upload_fail), Toast.LENGTH_SHORT).show();
        }
    }

    private static void restoreChipAfterCancel(Activity act) {
        clearPick();
        removePreview();
        if (chip == null || chipHost != act || chip.getParent() == null) {
            removeChip();
            injectChip(act);
            return;
        }
        applyChipState(act, null);
        chip.bringToFront();
    }

    private void hookPickerResult() {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onActivityResult",
                    int.class, int.class, Intent.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                int req = (int) p.args[0];
                                int res = (int) p.args[1];
                                if (req != PICK_REQUEST) return;
                                pickerOpen = false;
                                Activity act = (Activity) p.thisObject;
                                Uri uri = res == Activity.RESULT_OK && p.args[2] != null
                                        ? ((Intent) p.args[2]).getData() : null;
                                if (uri == null) {
                                    restoreChipAfterCancel(act);
                                    return;
                                }
                                FeedVideoDownloadHook.executor.execute(() -> {
                                    Bitmap bmp = decode(act, uri);
                                    FeedVideoDownloadHook.mainHandler.post(() -> usePick(act, bmp));
                                });
                            } catch (Throwable t) {
                                ModuleLog.error("(IE|InstantUpload) Could not use the picked photo", t);
                            }
                        }
                    });
            ModuleLog.detail("(IE|InstantUpload) picker-result hooked");
        } catch (Throwable t) {
            ModuleLog.error("(IE|InstantUpload) Photos picked from the gallery will not reach the camera: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void usePick(Activity act, Bitmap bmp) {
        try {
            if (act.isFinishing() || act.isDestroyed()) return;
            if (bmp == null) {
                restoreChipAfterCancel(act);
                ps.reso.instaeclipse.utils.toast.IeToast.makeText(act, I18n.t(act, R.string.ig_instant_upload_fail),
                        Toast.LENGTH_SHORT).show();
                return;
            }
            armPick(bmp, act);
            if (chip == null || chipHost != act || chip.getParent() == null) {
                removeChip();
                injectChip(act);
            }
            markChipReady(act, bmp);
            ps.reso.instaeclipse.utils.toast.IeToast.makeText(act, I18n.t(act, R.string.ig_instant_upload_ready),
                    Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            ModuleLog.error("(IE|InstantUpload) Could not use the picked photo", t);
        }
    }

    private static final int MAX_EDGE = 1440;

    private static Bitmap decode(Activity act, Uri uri) {
        try {
            ImageDecoder.Source src = ImageDecoder.createSource(act.getContentResolver(), uri);
            return ImageDecoder.decodeBitmap(src, (decoder, info, s) -> {
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                decoder.setMutableRequired(false);
                int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                int longEdge = Math.max(w, h);
                if (longEdge > MAX_EDGE) {
                    float scale = (float) MAX_EDGE / longEdge;
                    decoder.setTargetSize(Math.round(w * scale), Math.round(h * scale));
                }
            });
        } catch (Throwable t) {
            ModuleLog.error("(IE|InstantUpload) Could not read the picked photo: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return null;
        }
    }

    private static int dp(Activity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }

    @SuppressWarnings("unchecked")
    static Activity currentActivity() {
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Object at = atClass.getMethod("currentActivityThread").invoke(null);
            java.lang.reflect.Field f = atClass.getDeclaredField("mActivities");
            f.setAccessible(true);
            java.util.Map<Object, Object> acts = (java.util.Map<Object, Object>) f.get(at);
            if (acts == null) return null;
            for (Object record : acts.values()) {
                Class<?> rc = record.getClass();
                java.lang.reflect.Field paused = rc.getDeclaredField("paused");
                paused.setAccessible(true);
                if (Boolean.FALSE.equals(paused.get(record))) {
                    java.lang.reflect.Field af = rc.getDeclaredField("activity");
                    af.setAccessible(true);
                    return (Activity) af.get(record);
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
