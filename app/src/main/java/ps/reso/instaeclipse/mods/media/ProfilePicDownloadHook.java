package ps.reso.instaeclipse.mods.media;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.mods.feed.ZoomableImageView;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.download.DownloadEngine;
import ps.reso.instaeclipse.utils.download.DownloadMessages;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.Found;
import ps.reso.instaeclipse.utils.ui.ResIds;
import ps.reso.instaeclipse.utils.ui.ViewAttachHooks;

/**
 * Profile Picture Downloader
 *
 * Strategy:
 *   Hook View.onAttachedToWindow() globally, filter for "expanded_profile_pic" by resource name
 *   (cached as an int ID after first resolution). When found, attach a long-press listener that
 *   opens the picture full-screen and square (pinch to zoom) with a Download button.
 *
 *   Full size: the circle shows a small copy (ImageUrl from IgImageView). The viewed profile's User
 *   (UserDetailFragment -> UserDetailViewModel -> User, tracked on resume) carries the original as
 *   hd_profile_pic_url_info (User ()ProfilePicUrlInfo using that string; ProfilePicUrlInfo keeps a
 *   plain getUrl()). It's used when it is the same picture (same file name as the circle's URL),
 *   otherwise the circle's URL is kept.
 *
 * Gated by FeatureFlags.enableProfileDownload.
 */
public class ProfilePicDownloadHook {

    private static final class Main {
        static final Handler HANDLER = new Handler(Looper.getMainLooper());
    }

    // ── Install ───────────────────────────────────────────────────────────────

    private static final String FRAGMENT = "com.instagram.profile.fragment.UserDetailFragment";
    private static final String VIEW_MODEL = "com.instagram.profile.fragment.UserDetailViewModel";
    private static final String USER = "com.instagram.user.model.User";
    private static volatile Method hdGetter;
    private static volatile WeakReference<Object> lastProfile;

    public static void install(ClassLoader cl) {
        resolveHdGetter(cl);
        try {
            XposedHelpers.findAndHookMethod(cl.loadClass("androidx.fragment.app.Fragment"), "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    if (!FeatureFlags.enableProfileDownload) return;
                    for (Class<?> c = param.thisObject.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                        if (c.getName().equals(FRAGMENT)) {
                            lastProfile = new WeakReference<>(param.thisObject);
                            return;
                        }
                    }
                }
            });
        } catch (Throwable t) {
            ModuleLog.warn("(IE|ProfileDL) Full-size profile pictures are not available; the displayed picture is used instead: " + t);
        }
        install();
    }

    private static void resolveHdGetter(ClassLoader cl) {
        try {
            Method m = DexKitCache.isCacheValid() ? DexKitCache.loadMethod("ProfilePic_hdGetter", cl) : null;
            if (m == null) {
                List<Found> found = DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                        .declaredClass(USER)
                        .usingEqStrings(List.of("hd_profile_pic_url_info"))
                        .returnType("com.instagram.api.schemas.ProfilePicUrlInfo")
                        .paramCount(0)));
                if (found.size() != 1) {
                    ModuleLog.warn("(IE|ProfileDL) Full-size profile pictures are not available in this Instagram version; the displayed picture is used instead");
                    ModuleLog.detail("(IE|ProfileDL) hd_profile_pic_url_info getter: " + found.size() + " matches");
                    return;
                }
                m = found.get(0).getMethodInstance(cl);
                DexKitCache.saveMethod("ProfilePic_hdGetter", m);
            }
            m.setAccessible(true);
            hdGetter = m;
            ModuleLog.ok("(IE|ProfileDL) ready (full-size pictures available)");
            ModuleLog.detail("(IE|ProfileDL) HD getter User." + m.getName());
        } catch (Throwable t) {
            ModuleLog.warn("(IE|ProfileDL) Full-size profile pictures are not available; the displayed picture is used instead: " + t);
        }
    }

    /** The viewed profile's original picture when it is the same image as displayedUrl, else null. */
    private static String hdUrl(String displayedUrl) {
        try {
            Method getter = hdGetter;
            WeakReference<Object> ref = lastProfile;
            Object frag = ref != null ? ref.get() : null;
            if (getter == null || frag == null) return null;
            Object vm = fieldOfType(frag, VIEW_MODEL);
            Object user = vm != null ? fieldOfType(vm, USER) : null;
            Object info = user != null ? getter.invoke(user) : null;
            if (info == null) return null;
            Object url = info.getClass().getMethod("getUrl").invoke(info);
            if (!(url instanceof String hd) || !hd.startsWith("http")) return null;
            if (!fileName(hd).equals(fileName(displayedUrl))) {
                ModuleLog.detail("(IE|ProfileDL) HD picture belongs to another profile, keeping the displayed one");
                return null;
            }
            return hd;
        } catch (Throwable t) {
            ModuleLog.detail("(IE|ProfileDL) HD url, keeping the displayed one: " + t);
            return null;
        }
    }

    private static String fileName(String url) {
        String path = Uri.parse(url).getPath();
        return path == null ? "" : path.substring(path.lastIndexOf('/') + 1);
    }

    private static Object fieldOfType(Object o, String type) throws IllegalAccessException {
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(c)) {
                if (!f.getType().getName().equals(type)) continue;
                f.setAccessible(true);
                Object v = f.get(o);
                if (v != null) return v;
            }
        }
        return null;
    }

    public static void install() {
        try {
            hookExpandedPicture();
        } catch (Throwable t) {
            if (FeatureFlags.enableProfileDownload) {
                FeatureStatusTracker.setBroken("ProfileDownload", R.string.ig_dialog_downloader_profiles);
            }
            throw t;
        }
        if (FeatureFlags.enableProfileDownload) {
            FeatureStatusTracker.setEnabled("ProfileDownload", R.string.ig_dialog_downloader_profiles);
            FeatureStatusTracker.setHooked("ProfileDownload");
        }
    }

    private static void hookExpandedPicture() {
        ViewAttachHooks.add("ProfilePic", v -> {
            if (!FeatureFlags.enableProfileDownload) return;
            if (isExpandedPicture(v.getContext(), v.getId())) injectLongPress(v);
        });
    }

    public static boolean isExpandedPicture(Context ctx, int vid) {
        if (vid == View.NO_ID || (vid >>> 24) == 0) return false;
        int id = ResIds.id(ctx, "expanded_profile_pic");
        return id != 0 && vid == id;
    }

    // ── UI injection ──────────────────────────────────────────────────────────

    private static void injectLongPress(View view) {
        try {
            view.setOnLongClickListener(v -> {
                // Resolve activity lazily at tap time — context is valid at this point
                Context ctx = v.getContext();
                Activity activity = activityFromContext(ctx);

                String url = extractUrl(v);
                if (url == null) {
                    ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_profile_pic_url_not_found), Toast.LENGTH_SHORT).show();
                    ModuleLog.error("(IE|ProfileDL) Could not open the profile picture: its link was not found");
                    return true;
                }
                String username = activity != null ? extractUsername(activity) : null;
                String hd = hdUrl(url);
                ModuleLog.detail("(IE|ProfileDL) viewer: " + (hd != null ? "HD original" : "displayed copy"));
                showViewer(ctx, hd != null ? hd : url, username);
                return true;
            });

        } catch (Throwable t) {
            ModuleLog.error("(IE|ProfileDL) Holding the profile picture will not open it: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    // ── Viewer ────────────────────────────────────────────────────────────────

    public static android.app.Dialog showViewer(Context ctx, String url, String username) {
        android.app.Dialog dialog = new android.app.Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        FrameLayout root = new FrameLayout(ctx);
        root.setBackgroundColor(Color.BLACK);

        ZoomableImageView image = new ZoomableImageView(ctx);
        image.setOnDismissRequest(dialog::dismiss);
        root.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ProgressBar spinner = new ProgressBar(ctx);
        root.addView(spinner, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        float dp = ctx.getResources().getDisplayMetrics().density;
        TextView size = new TextView(ctx);
        size.setTextColor(0xB3FFFFFF);
        size.setTextSize(12);
        FrameLayout.LayoutParams sizeLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        sizeLp.topMargin = (int) (40 * dp);
        root.addView(size, sizeLp);

        TextView download = new TextView(ctx);
        download.setText(I18n.t(ctx, R.string.ig_download_choice_title));
        download.setTextColor(Color.WHITE);
        download.setTextSize(15);
        download.setGravity(Gravity.CENTER);
        int ph = (int) (28 * dp), pv = (int) (12 * dp);
        download.setPadding(ph, pv, ph, pv);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(0x33FFFFFF);
        pill.setCornerRadius(24 * dp);
        download.setBackground(pill);
        FrameLayout.LayoutParams dlLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        dlLp.bottomMargin = (int) (56 * dp);
        root.addView(download, dlLp);

        AtomicReference<File> temp = new AtomicReference<>();
        AtomicReference<String> type = new AtomicReference<>();
        AtomicBoolean closed = new AtomicBoolean();
        AtomicBoolean finished = new AtomicBoolean();
        DownloadEngine.Cancellation cancel = new DownloadEngine.Cancellation();
        download.setOnClickListener(v -> {
            switch (ProfilePicSave.decide(FeedVideoDownloadHook.hasCompanionDestination(), temp.get() != null)) {
                case COMPANION -> {
                    dialog.dismiss();
                    save(ctx, url, username);
                }
                case PUBLISH -> {
                    File file = temp.getAndSet(null);
                    dialog.dismiss();
                    publish(ctx, file, type.get(), username);
                }
                case NOTHING -> {
                    if (finished.get()) ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx,
                            I18n.t(ctx, R.string.ig_profile_pic_load_failed), Toast.LENGTH_SHORT).show();
                }
            }
        });
        dialog.setOnDismissListener(d -> {
            closed.set(true);
            cancel.cancel();
            delete(temp.getAndSet(null));
        });

        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        dialog.show();

        FeedVideoDownloadHook.executor.submit(() -> {
            File file = null;
            String mime = null;
            Bitmap bmp = null;
            try {
                file = File.createTempFile("ie_pp_", ".bin", ctx.getCacheDir());
                mime = DownloadEngine.fetch(ctx, url, file, "image/jpeg", cancel, null);
                bmp = BitmapFactory.decodeFile(file.getPath());
            } catch (Throwable t) {
                ModuleLog.error("(IE|ProfileDL) Could not load the profile picture: " + t.getClass().getSimpleName()
                        + (t instanceof DownloadEngine.Failure f ? " " + f.status : ""));
            }
            if (mime != null) {
                type.set(mime);
                temp.set(file);
                if (closed.get()) delete(temp.getAndSet(null));
            } else {
                delete(file);
            }
            final Bitmap b = bmp;
            Main.HANDLER.post(() -> {
                finished.set(true);
                spinner.setVisibility(View.GONE);
                if (b == null) {
                    size.setText(I18n.t(ctx, R.string.ig_profile_pic_load_failed));
                    return;
                }
                image.setImageBitmap(b);
                size.setText(b.getWidth() + " × " + b.getHeight());
            });
        });
        return dialog;
    }

    private static void delete(File file) {
        if (file != null) file.delete();
    }

    private static void save(Context ctx, String url, String username) {
        String filename = FeedVideoDownloadHook.buildFilename(username, "profile", null, false);
        ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_downloading_profile_pic), Toast.LENGTH_SHORT).show();
        FeedVideoDownloadHook.executor.submit(() -> {
            try {
                boolean delegated = FeedVideoDownloadHook.downloadAndSave(ctx, url, filename, false, username);
                if (!delegated) saved(ctx);
            } catch (Throwable e) {
                failed(ctx, e);
            }
        });
    }

    private static void publish(Context ctx, File file, String mime, String username) {
        String filename = FeedVideoDownloadHook.buildFilename(username, "profile", null, false);
        FeedVideoDownloadHook.executor.submit(() -> {
            try {
                MediaTypeDetector.Result detected = MediaTypeDetector.verified(mime, filename);
                DownloadPublisher.save(ctx, file, detected.filename, detected.mimeType, username,
                        new DownloadEngine.Cancellation());
                saved(ctx);
            } catch (Throwable e) {
                failed(ctx, e);
            } finally {
                delete(file);
            }
        });
    }

    private static void saved(Context ctx) {
        Main.HANDLER.post(() -> ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx,
                I18n.t(ctx, R.string.ig_toast_profile_pic_saved), Toast.LENGTH_SHORT).show());
    }

    private static void failed(Context ctx, Throwable e) {
        ModuleLog.error("(IE|ProfileDL) Could not save the profile picture: " + e.getClass().getSimpleName());
        Main.HANDLER.post(() -> ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx,
                DownloadMessages.failure(ctx, e), Toast.LENGTH_SHORT).show());
    }

    // ── URL extraction ────────────────────────────────────────────────────────

    private static final String IMAGE_URL = "com.instagram.common.typedurl.ImageUrl";

    /**
     * Extracts the image URL from the profile pic view (CircularImageView extends IgImageView).
     * IgImageView keeps the non-obfuscated getter getTypedUrl() (the displayed ImageUrl); if a
     * build drops it, scans the hierarchy for ImageUrl-typed fields (A0E/A0D/A0e on 452) by TYPE,
     * never by the obfuscated field letter. ImageUrl itself is a non-obfuscated interface.
     */
    private static String extractUrl(View view) {
        try {
            java.lang.reflect.Method getter = findMethod(view.getClass(), "getTypedUrl");
            if (getter != null) {
                String url = urlOf(getter.invoke(view));
                if (url != null) return url;
            }
        } catch (Throwable ignored) {}

        try {
            String url = getUrlFromImageUrlFields(view);
            if (url != null) return url;
        } catch (Throwable ignored) {}

        // Fallback: tag-based URI
        try {
            Object tag = view.getTag();
            if (tag instanceof Uri) return tag.toString();
            if (tag instanceof String s && s.startsWith("http")) return s;
        } catch (Throwable ignored) {}

        ModuleLog.detail("(IE|ProfileDL) all URL strategies failed for " + view.getClass().getName());
        return null;
    }

    /**
     * Walks the class hierarchy and reads every field typed as ImageUrl, returning the first one
     * whose getUrl() yields an http(s) URL. getDeclaredFields() order is unspecified, but all the
     * candidates point at the same displayed picture (main / tracking / fallback copy).
     */
    private static String getUrlFromImageUrlFields(View view) {
        for (Class<?> cls = view.getClass(); cls != null && cls != Object.class; cls = cls.getSuperclass()) {
            for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(cls)) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (!IMAGE_URL.equals(f.getType().getName())) continue;
                try {
                    f.setAccessible(true);
                    String url = urlOf(f.get(view));
                    if (url != null) return url;
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    /** ImageUrl.getUrl() if it is an http(s) URL, else null. */
    private static String urlOf(Object imageUrl) throws Throwable {
        if (imageUrl == null) return null;
        Object result = imageUrl.getClass().getMethod("getUrl").invoke(imageUrl);
        return result instanceof String s && s.startsWith("http") ? s : null;
    }

    private static java.lang.reflect.Method findMethod(Class<?> cls, String name) {
        for (; cls != null && cls != Object.class; cls = cls.getSuperclass()) {
            try {
                java.lang.reflect.Method m = cls.getDeclaredMethod(name);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {}
        }
        return null;
    }

    // ── Username extraction ───────────────────────────────────────────────────

    @SuppressLint("DiscouragedApi")
    private static String extractUsername(Activity activity) {
        try {
            android.app.ActionBar ab = activity.getActionBar();
            if (ab != null && ab.getTitle() != null) {
                String t = ab.getTitle().toString().trim();
                if (looksLikeUsername(t)) return t;
            }
        } catch (Throwable ignored) {}

        try {
            int titleId = activity.getResources()
                    .getIdentifier("action_bar_title", "id", activity.getPackageName());
            if (titleId != 0) {
                android.widget.TextView tv = activity.findViewById(titleId);
                if (tv != null) {
                    String t = tv.getText().toString().trim();
                    if (looksLikeUsername(t)) return t;
                }
            }
        } catch (Throwable ignored) {}

        try {
            CharSequence t = activity.getTitle();
            if (t != null && looksLikeUsername(t.toString().trim())) return t.toString().trim();
        } catch (Throwable ignored) {}

        return null;
    }

    private static boolean looksLikeUsername(String s) {
        return s != null && s.length() >= 1 && s.length() <= 30
                && s.matches("[a-zA-Z0-9._]+")
                && !s.matches("\\d+");
    }

    // ── Context → Activity ────────────────────────────────────────────────────

    private static Activity activityFromContext(Context ctx) {
        while (ctx instanceof ContextWrapper) {
            if (ctx instanceof Activity) return (Activity) ctx;
            ctx = ((ContextWrapper) ctx).getBaseContext();
        }
        return null;
    }
}
