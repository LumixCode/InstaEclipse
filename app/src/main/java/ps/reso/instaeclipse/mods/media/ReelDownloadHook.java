package ps.reso.instaeclipse.mods.media;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.Found;

public class ReelDownloadHook {

    private static Class<?> controllerClass;
    private static Method   hookMethod;

    private static Method buttonAdderMethod;
    private static Field  activityField;

    public void install(ClassLoader classLoader) {
        installReduceOptionsListPatch(classLoader);

        if (DexKitCache.isCacheValid()) {
            Method cached = DexKitCache.loadMethod("ReelDownload_v2", classLoader);
            if (cached != null) {
                controllerClass = cached.getDeclaringClass();
                hookMethod = cached;
                cached.setAccessible(true);
                FeatureStatusTracker.setHooked("ReelDownload");
                XposedBridge.hookMethod(cached, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                        if (!FeatureFlags.enableReelDownload) return;
                        onOptionsBuilt(param);
                    }
                });
                ModuleLog.detail("(IE|Reel) legacy reel menu hooked: " + hookMethod.getDeclaringClass().getName() + "." + hookMethod.getName());
                return;
            }
        }

        try {
            var methods = DexKitQueryCache.methods(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .usingStrings("ClipsOrganicMediaItemViewMoreOptionsController")));

            if (methods.isEmpty()) {
                ModuleLog.detail("(IE|Reel) ClipsOrganicMediaItemViewMoreOptionsController not found (older reel menu not in this build)");
                return;
            }

            // The controller string can be referenced from more than one method; collect every
            // distinct declaring class and pick whichever actually carries the options-builder.
            java.util.LinkedHashSet<Class<?>> candidates = new java.util.LinkedHashSet<>();
            for (var md : methods) {
                try { candidates.add(md.getMethodInstance(classLoader).getDeclaringClass()); }
                catch (Throwable ignored) {}
            }
            ModuleLog.detail("(IE|Reel) string matches=" + methods.size()
                    + " candidateClasses=" + candidates.size());

            // Options-builder: void(com.instagram.feed.media.Media, <ButtonAdder>), where the
            // ButtonAdder param exposes an (Context, OnClickListener, String, int) add method.
            Method target = null;
            for (Class<?> cand : candidates) {
                int voidMedia = 0;
                for (Method m : ps.reso.instaeclipse.utils.core.SafeReflect.methods(cand)) {
                    if (m.getReturnType() != void.class || Modifier.isStatic(m.getModifiers())) continue;
                    Class<?>[] params = m.getParameterTypes();
                    if (params.length < 2) continue;
                    if (!params[0].getName().equals("com.instagram.feed.media.Media")) continue;
                    if (params[1].isPrimitive() || params[1] == String.class) continue;
                    voidMedia++;
                    if (hasButtonAdderMethod(params[1])) { target = m; break; }
                }
                if (voidMedia > 0) {
                    ModuleLog.detail("(IE|Reel) candidate " + cand.getName()
                            + " void(Media,X)=" + voidMedia);
                }
                if (target != null) { controllerClass = cand; break; }
            }
            if (target == null) {
                ModuleLog.probe("(IE|Reel) legacy (Media, ButtonAdder)V hook not in this build (reel menu uses the dispatcher)");
                return;
            }

            target.setAccessible(true);
            hookMethod = target;
            DexKitCache.saveMethod("ReelDownload_v2", target);
            FeatureStatusTracker.setHooked("ReelDownload");

            XposedBridge.hookMethod(target, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    if (!FeatureFlags.enableReelDownload) return;
                    onOptionsBuilt(param);
                }
            });
            ModuleLog.detail("(IE|Reel) legacy reel menu hooked: " + hookMethod.getDeclaringClass().getName() + "." + hookMethod.getName());

        } catch (Throwable t) {
            ModuleLog.warn("(IE|Reel) The older reel menu may not show Download: " + t);
        }
    }

    /**
     * True when {@code adder} exposes the reel-menu button-adder method
     * {@code (Context, View.OnClickListener, String, int)} — the same shape
     * {@link #onOptionsBuilt} invokes. Identifies the correct options-builder overload
     * structurally, so it survives obfuscated renames across Instagram versions.
     */
    private static boolean hasButtonAdderMethod(Class<?> adder) {
        if (adder == null) return false;
        for (Method m : ps.reso.instaeclipse.utils.core.SafeReflect.methods(adder)) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 4
                    && Context.class.isAssignableFrom(p[0])
                    && View.OnClickListener.class.isAssignableFrom(p[1])
                    && p[2] == String.class
                    && p[3] == int.class) return true;
        }
        return false;
    }

    // ── Reduced options-list patch ──────────────────────────────────────────────
    //
    // IG's newer, simplified reel overflow menu builds its option list via one
    // method that returns a plain ArrayList<MediaOption$Option> (SAVE/UNSAVE,
    // PLAYBACK_CONTROLS, WHY_AM_I_SEEING_THIS, INTERESTED, NOT_INTERESTED,
    // TAG_OPTIONS, REPORT, REQUEST_COMMUNITY_NOTE, DEBUG_STICKER_TRANSLATION) —
    // DOWNLOAD was dropped entirely from this list, unlike the older/fuller
    // overflow-menu code path. Found via field-usage matching on two of its
    // distinctive enum references. Appending DOWNLOAD to the returned (mutable)
    // ArrayList lets it flow through the same generic per-option row builder
    // (LX/5RY;->A0Q -> LX/QIy;->A04) used for every other option here — same
    // shared row primitive the post menu uses, so PostDownloadContextMenuHook's
    // app-wide click-handler hook already covers whatever dispatches its click.
    private static void installReduceOptionsListPatch(ClassLoader classLoader) {
        try {
            Object downloadOption = null, copyLinkOption = null, infoOption = null, speedOption = null;
            Class<?> optionClass = classLoader.loadClass("com.instagram.feed.media.mediaoption.MediaOption$Option");
            for (Object v : (Object[]) optionClass.getMethod("values").invoke(null)) {
                String n = v.toString();
                if (n.equals(PostMenuRowsPolicy.DOWNLOAD)) downloadOption = v;
                else if (n.equals(PostMenuRowsPolicy.COPY_MEDIA_LINK_CARRIER)) copyLinkOption = v;
                else if (n.equals(PostMenuRowsPolicy.MEDIA_INFO_CARRIER)) infoOption = v;
                else if (n.equals(PostMenuRowsPolicy.SPEED_CARRIER)) speedOption = v;
            }
            if (downloadOption == null && copyLinkOption == null) {
                ModuleLog.error("(IE|Reel) Download and copy link will not appear in the reel menu: their menu entries were not found in this Instagram version");
                return;
            }
            final Object download = downloadOption;
            final Object copyLink = copyLinkOption;   // #117 — clicks handled by PostDownloadContextMenuHook
            final Object info = infoOption, speedRow = speedOption; // #231 / #247 — same click handler

            XC_MethodHook hook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    boolean wantDl = FeatureFlags.enableReelDownload && download != null;
                    boolean wantCl = FeatureFlags.copyMediaLink && copyLink != null;
                    boolean wantInfo = FeatureFlags.offerMediaInfo && info != null;
                    boolean wantSpeed = PlaybackSpeedHook.offered() && speedRow != null;
                    if (!wantDl && !wantCl && !wantInfo && !wantSpeed) return;
                    try {
                        Object result = param.getResult();
                        if (result instanceof List<?> list) {
                            @SuppressWarnings("unchecked")
                            List<Object> mutable = (List<Object>) list;
                            if (wantDl && !mutable.contains(download)) mutable.add(download);
                            if (wantCl && !mutable.contains(copyLink)) mutable.add(copyLink);
                            if (wantInfo && !mutable.contains(info)) mutable.add(info);
                            if (wantSpeed && !mutable.contains(speedRow) && !PlaybackScope.instagramOffersSpeed(mutable))
                                mutable.add(speedRow);
                        }
                    } catch (Throwable t) {
                        ModuleLog.warn("(IE|Reel) Could not add the rows to this reel menu: " + t);
                    }
                }
            };

            if (DexKitCache.isCacheValid()) {
                Method cached = DexKitCache.loadMethod("ReelOptionsListBuilder", classLoader);
                if (cached != null) {
                    XposedBridge.hookMethod(cached, hook);
                    FeatureStatusTracker.setHooked("ReelDownload");
                    return;
                }
            }

            String optionDesc = "Lcom/instagram/feed/media/mediaoption/MediaOption$Option;";
            var methods = DexKitQueryCache.methods(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .returnType("java.util.ArrayList")
                            .addUsingField(optionDesc + "->PLAYBACK_CONTROLS:" + optionDesc)
                            .addUsingField(optionDesc + "->UNSAVE:" + optionDesc)));

            if (methods.isEmpty()) {
                ModuleLog.warn("(IE|Reel) The reel menu may not show the added rows in this Instagram version");
                ModuleLog.detail("(IE|Reel) Reduced options-list builder not found");
                return;
            }

            Method target = methods.get(0).getMethodInstance(classLoader);
            target.setAccessible(true);
            XposedBridge.hookMethod(target, hook);
            DexKitCache.saveMethod("ReelOptionsListBuilder", target);
            FeatureStatusTracker.setHooked("ReelDownload");
            ModuleLog.ok("(IE|Reel) ready");
            ModuleLog.detail("(IE|Reel) Options-list patch hooked: " +
                    target.getDeclaringClass().getName() + "." + target.getName());

        } catch (Throwable t) {
            ModuleLog.error("(IE|Reel) Could not add rows to the reel menu", t);
        }
    }

    /**
     * Primary index resolver: walks the activity's live view hierarchy for a
     * ViewPager / ViewPager2 / ReboundViewPager / horizontal RecyclerView whose adapter
     * item count equals {@code carouselSize} and returns its current data index.
     * Multiple unrelated carousels can coincidentally share the same item count (e.g.
     * two feed posts both showing 4 photos) — trusting the first DFS hit in that case
     * previously misattributed the index to the wrong post. So every match is collected
     * and the result is only trusted when exactly one candidate matches; otherwise the
     * caller falls back to the data-layer field.
     *
     * @return current position [0, carouselSize), or -1 if not found / ambiguous
     */
    static int findCarouselIndexFromView(Context ctx, int carouselSize) {
        if (!(ctx instanceof Activity)) return -1;
        try {
            View root = ((Activity) ctx).getWindow().getDecorView();
            List<Integer> matches = new java.util.ArrayList<>();
            collectCarouselMatches(root, carouselSize, matches);
            return matches.size() == 1 ? matches.get(0) : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** Returns adapter item count, trying RecyclerView-style then PagerAdapter-style. */
    private static int adapterCount(Object adapter) {
        try { return (int) adapter.getClass().getMethod("getItemCount").invoke(adapter); } catch (Throwable ignored) {}
        try { return (int) adapter.getClass().getMethod("getCount").invoke(adapter); } catch (Throwable ignored) {}
        return -1;
    }

    /**
     * Recursive DFS over the view tree, collecting the resolved index of every carousel
     * whose adapter size matches — does not stop at the first hit. ViewPager / ViewPager2 /
     * ReboundViewPager are AndroidX / Instagram common-UI classes — stable names, no obfuscation.
     */
    private static void collectCarouselMatches(View view, int carouselSize, List<Integer> out) {
        String cn = view.getClass().getName();

        // ViewPager / ViewPager2 / ReboundViewPager and any subclass
        if (cn.contains("ViewPager")) {
            try {
                Object adapter = view.getClass().getMethod("getAdapter").invoke(view);
                if (adapter != null && adapterCount(adapter) == carouselSize) {
                    // Standard pagers: getCurrentItem()
                    // ReboundViewPager (Instagram looping carousel): getCurrentDataIndex()
                    for (String getter : new String[]{
                            "getCurrentItem", "getCurrentDataIndex",
                            "getCurrentWrappedDataIndex", "getCurrentRawDataIndex"}) {
                        try {
                            int cur = (int) view.getClass().getMethod(getter).invoke(view);
                            if (cur >= 0) { out.add(cur); break; }
                        } catch (NoSuchMethodException ignored) {}
                    }
                }
            } catch (Throwable ignored) {}
        }

        // Horizontal RecyclerView (carousel, not the vertical feed list)
        if (cn.contains("RecyclerView")) {
            try {
                Object adapter = view.getClass().getMethod("getAdapter").invoke(view);
                if (adapter != null && adapterCount(adapter) == carouselSize) {
                    Object lm = view.getClass().getMethod("getLayoutManager").invoke(view);
                    if (lm != null) {
                        try {
                            int orientation = (int) lm.getClass().getMethod("getOrientation").invoke(lm);
                            if (orientation != 0 /* HORIZONTAL */) lm = null;
                        } catch (Throwable ignored) {}
                        if (lm != null) {
                            Integer pos = null;
                            try {
                                int p = (int) lm.getClass()
                                        .getMethod("findFirstCompletelyVisibleItemPosition").invoke(lm);
                                if (p >= 0) pos = p;
                            } catch (Throwable ignored) {}
                            if (pos == null) {
                                try {
                                    int p = (int) lm.getClass()
                                            .getMethod("findFirstVisibleItemPosition").invoke(lm);
                                    if (p >= 0) pos = p;
                                } catch (Throwable ignored) {}
                            }
                            if (pos != null) out.add(pos);
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                collectCarouselMatches(vg.getChildAt(i), carouselSize, out);
            }
        }
    }

    private static void onOptionsBuilt(XC_MethodHook.MethodHookParam param) {
        try {
            Object controller  = param.thisObject;
            Object media       = param.args[0];
            Object buttonAdder = param.args[1];

            if (activityField == null) {
                for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(controller.getClass())) {
                    if (Activity.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        activityField = f;
                        break;
                    }
                }
            }
            if (activityField == null) {
                ModuleLog.warn("(IE|Reel) Could not add Download to this reel menu: the screen was not found");
                return;
            }

            Activity activity = (Activity) activityField.get(controller);
            if (activity == null) return;

            if (buttonAdderMethod == null) {
                for (Method m : ps.reso.instaeclipse.utils.core.SafeReflect.methods(buttonAdder.getClass())) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length != 4) continue;
                    if (!Context.class.isAssignableFrom(p[0])) continue;
                    if (!View.OnClickListener.class.isAssignableFrom(p[1])) continue;
                    if (p[2] != String.class) continue;
                    if (p[3] != int.class) continue;
                    m.setAccessible(true);
                    buttonAdderMethod = m;
                    break;
                }
            }
            if (buttonAdderMethod == null) {
                ModuleLog.warn("(IE|Reel) Could not add Download to this reel menu: the menu's add-row function was not found");
                return;
            }

            int icon = resolveDownloadIcon(activity);
            final Activity actCopy      = activity;
            final Object mediaCopy      = media;
            final Object controllerCopy = controller;

            buttonAdderMethod.invoke(buttonAdder, activity,
                    (View.OnClickListener) v -> startReelDownload(actCopy, mediaCopy, controllerCopy),
                    I18n.t(activity, R.string.ig_dl_title), icon);

        } catch (Throwable t) {
            ModuleLog.warn("(IE|Reel) Could not add Download to this reel menu: " + t);
        }
    }

    private static void startReelDownload(Context ctx, Object media, Object controller) {
        SelectedMediaDownloads.captureAndShowPost(ctx, media, "reel");
    }

    /** Reads the icon drawable ID from MediaOption$Option.DOWNLOAD enum value. */
    private static int resolveDownloadIcon(Context ctx) {
        try {
            Class<?> optionClass = ctx.getClassLoader()
                    .loadClass("com.instagram.feed.media.mediaoption.MediaOption$Option");
            for (Object val : (Object[]) optionClass.getMethod("values").invoke(null)) {
                if (val.toString().contains("DOWNLOAD")) {
                    Field f = val.getClass().getField("iconDrawable");
                    return (int) f.get(val);
                }
            }
        } catch (Throwable ignored) {}
        return 0;
    }
}
