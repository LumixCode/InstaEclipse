package ps.reso.instaeclipse.utils.media;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.mods.ui.UIHookManager;
import ps.reso.instaeclipse.utils.core.ModuleResources;
import ps.reso.instaeclipse.utils.core.SettingsManager;
import ps.reso.instaeclipse.utils.dialog.IeDialog;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.toast.IeToast;

public final class StoryCachePrompt {

    static final String ARMED = "__storyCachePromptArmed";
    static final long RESUME_DELAY_MS = 1500L;

    private StoryCachePrompt() {}

    static void arm(SharedPreferences prefs) {
        if (prefs != null && !prefs.getBoolean(ARMED, false)) prefs.edit().putBoolean(ARMED, true).apply();
    }

    static boolean due(SharedPreferences prefs, boolean on) {
        if (prefs == null) return false;
        if (on) {
            arm(prefs);
            return false;
        }
        return prefs.getBoolean(ARMED, false);
    }

    static boolean asks(boolean on, int count) {
        return !on && count > 0;
    }

    static boolean take(SharedPreferences prefs, boolean on, int count) {
        if (!due(prefs, on)) return false;
        prefs.edit().putBoolean(ARMED, false).apply();
        return asks(on, count);
    }

    public static void cachingChanged(boolean inMenu) {
        SharedPreferences prefs = SettingsManager.prefs();
        arm(prefs);
        if (inMenu && due(prefs, FeatureFlags.cacheStories)) ask(UIHookManager.topActivity(), false);
    }

    public static boolean pending() {
        return due(SettingsManager.prefs(), FeatureFlags.cacheStories);
    }

    public static void onMainResume(Activity activity) {
        if (!pending()) return;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (inFront(activity, true)) ask(activity, true);
        }, RESUME_DELAY_MS);
    }

    private static void ask(Activity activity, boolean needsFocus) {
        if (activity == null) return;
        StoryCache.countAsync(count -> {
            if (!inFront(activity, needsFocus)) return;
            if (take(SettingsManager.prefs(), FeatureFlags.cacheStories, count)) show(activity, count);
        });
    }

    private static boolean inFront(Activity activity, boolean needsFocus) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || activity.getWindow() == null) return false;
        return !needsFocus || (UIHookManager.getCurrentActivity() == activity && activity.hasWindowFocus());
    }

    static void show(Activity activity, int count) {
        try {
            IeDialog dialog = new IeDialog(activity)
                    .title(ModuleResources.get(activity).getQuantityString(R.plurals.ig_story_cache_delete_prompt, count, count))
                    .message(I18n.t(activity, R.string.ig_story_cache_delete_prompt_body));
            dialog.secondary(I18n.t(activity, R.string.ig_story_cache_keep), null);
            dialog.destructive(I18n.t(activity, R.string.ig_story_cache_delete), () -> StoryCache.clearAllAsync(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                IeToast.makeText(activity, I18n.t(activity, R.string.ig_story_cache_cleared), Toast.LENGTH_SHORT).show();
            }));
            dialog.show();
        } catch (Throwable t) {
            ModuleLog.warn("(IE|StoryCache) Could not ask whether to delete the cached stories", t);
        }
    }
}
