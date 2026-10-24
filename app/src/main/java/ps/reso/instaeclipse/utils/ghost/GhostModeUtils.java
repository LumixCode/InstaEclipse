package ps.reso.instaeclipse.utils.ghost;

import android.app.Activity;
import android.content.Context;
import android.widget.Toast;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.mods.ghost.ui.GhostEmojiManager;
import ps.reso.instaeclipse.mods.ui.UIHookManager;
import ps.reso.instaeclipse.utils.core.SettingsManager;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.i18n.I18n;

public class GhostModeUtils {

    private record Option(BooleanSupplier selected, BooleanSupplier on, Consumer<Boolean> set) {}

    private static final Option[] OPTIONS = {
            new Option(() -> FeatureFlags.quickToggleSeen, () -> FeatureFlags.isGhostSeen, v -> FeatureFlags.isGhostSeen = v),
            new Option(() -> FeatureFlags.quickToggleTyping, () -> FeatureFlags.isGhostTyping, v -> FeatureFlags.isGhostTyping = v),
            new Option(() -> FeatureFlags.quickToggleScreenshot, () -> FeatureFlags.isGhostScreenshot, v -> FeatureFlags.isGhostScreenshot = v),
            new Option(() -> FeatureFlags.quickToggleViewOnce, () -> FeatureFlags.isGhostViewOnce, v -> FeatureFlags.isGhostViewOnce = v),
            new Option(() -> FeatureFlags.quickToggleStory, () -> FeatureFlags.isGhostStory, v -> FeatureFlags.isGhostStory = v),
            new Option(() -> FeatureFlags.quickToggleLive, () -> FeatureFlags.isGhostLive, v -> FeatureFlags.isGhostLive = v),
            new Option(() -> FeatureFlags.quickToggleEphemeral, () -> FeatureFlags.keepEphemeralMessages, v -> FeatureFlags.keepEphemeralMessages = v),
            new Option(() -> FeatureFlags.quickTogglePermanentView, () -> FeatureFlags.permanentViewMode, v -> FeatureFlags.permanentViewMode = v),
            new Option(() -> FeatureFlags.quickToggleAllowScreenshots, () -> FeatureFlags.allowScreenshots, v -> FeatureFlags.allowScreenshots = v),
    };

    public static boolean isGhostModeActive() {
        for (Option option : OPTIONS) {
            if (option.selected().getAsBoolean() && option.on().getAsBoolean()) return true;
        }
        return false;
    }


    public static void toggleSelectedGhostOptions(Context context) {
        boolean anySelected = false;
        boolean shouldDisable = false;
        for (Option option : OPTIONS) {
            if (!option.selected().getAsBoolean()) continue;
            anySelected = true;
            if (option.on().getAsBoolean()) shouldDisable = true;
        }

        if (!anySelected) {
            Activity activity = UIHookManager.getCurrentActivity();
            if (activity != null) {
                GhostEmojiManager.addGhostEmojiNextToInbox(activity, false);
            }
            ps.reso.instaeclipse.utils.toast.IeToast.makeText(context, "❗ " + I18n.t(context, R.string.ig_toast_ghost_no_options), Toast.LENGTH_SHORT).show();
            return; // Nothing to do
        }

        boolean newState = !shouldDisable; // true = enable, false = disable

        for (Option option : OPTIONS) {
            if (option.selected().getAsBoolean()) option.set().accept(newState);
        }

        // Save changes
        SettingsManager.saveAllFlags();

        Activity activity = UIHookManager.getCurrentActivity();
        if (activity != null) {
            GhostEmojiManager.addGhostEmojiNextToInbox(activity, newState); // true = show ghost, false = hide
        }


        // Toast
        if (newState) {
            ps.reso.instaeclipse.utils.toast.IeToast.makeText(context, "👻 " + I18n.t(context, R.string.ig_toast_ghost_enabled), Toast.LENGTH_SHORT).show();
        } else {
            ps.reso.instaeclipse.utils.toast.IeToast.makeText(context, "❌ " + I18n.t(context, R.string.ig_toast_ghost_disabled), Toast.LENGTH_SHORT).show();
        }
    }
}
