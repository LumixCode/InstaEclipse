package ps.reso.instaeclipse.mods.feed;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.ui.ResIds;

public final class RoundedFeedPostsHook {

    private static final String MEDIA_FRAME = "com.instagram.ui.widget.framelayout.MediaFrameLayout";

    private RoundedFeedPostsHook() {}

    public static void install(ClassLoader cl) {
        try {
            Class<?> frame = cl.loadClass(MEDIA_FRAME);
            View.OnLayoutChangeListener onLayout = (v, l, t, r, b, ol, ot, or, ob) -> apply(v);
            XposedBridge.hookMethod(frame.getDeclaredConstructor(Context.class, AttributeSet.class, int.class), new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.thisObject instanceof View v && v.getClass() == frame) v.addOnLayoutChangeListener(onLayout);
                }
            });
            FeatureStatusTracker.setHooked("RoundedFeedPosts");
            ModuleLog.ok("(IE|RoundedPosts) ready");
            ModuleLog.detail("(IE|RoundedPosts) hooked " + MEDIA_FRAME);
        } catch (Throwable t) {
            ModuleLog.error("(IE|RoundedPosts) Will not work: setup failed", t);
        }
    }

    private static View target(View frame) {
        int videoContainerId = ResIds.id(frame.getContext(), "video_container");
        View best = frame;
        android.view.ViewParent p = frame.getParent();
        for (int depth = 0; p instanceof View pv && depth < 4; depth++, p = pv.getParent()) {
            if (videoContainerId != 0 && pv.getId() == videoContainerId
                    && pv.getWidth() == frame.getWidth() && pv.getHeight() == frame.getHeight()) best = pv;
        }
        return best;
    }

    private static boolean isPostMedia(View frame) {
        int id = frame.getId();
        if (id == View.NO_ID) return false;
        Context context = frame.getContext();
        return id == ResIds.id(context, "media_group") || id == ResIds.id(context, "carousel_media_group");
    }

    private static void apply(View frame) {
        try {
            int dp = FeatureFlags.feedCornerRadius;
            if (dp <= 0 && !OutlineRestore.applied(frame)) return;
            if (!isPostMedia(frame)) return;
            View v = target(frame);
            OutlineRestore.update(frame, v, dp * v.getResources().getDisplayMetrics().density);
        } catch (Throwable ignored) {}
    }
}
