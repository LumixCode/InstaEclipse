package ps.reso.instaeclipse.utils.ghost;

import android.app.Activity;
import android.content.Context;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.mods.ghost.ui.GhostEmojiManager;
import ps.reso.instaeclipse.mods.ui.UIHookManager;
import ps.reso.instaeclipse.utils.core.SettingsManager;
import ps.reso.instaeclipse.utils.dialog.DialogUtils;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.i18n.I18n;

public class GhostModeUtils {

    private record Option(BooleanSupplier selected, BooleanSupplier on, Consumer<Boolean> set, int name) {}

    private static final Option[] OPTIONS = {
            new Option(() -> FeatureFlags.quickToggleSeen, () -> FeatureFlags.isGhostSeen, v -> FeatureFlags.isGhostSeen = v,
                    R.string.ig_quick_toggle_name_seen),
            new Option(() -> FeatureFlags.quickToggleTyping, () -> FeatureFlags.isGhostTyping, v -> FeatureFlags.isGhostTyping = v,
                    R.string.ig_quick_toggle_name_typing),
            new Option(() -> FeatureFlags.quickToggleScreenshot, () -> FeatureFlags.isGhostScreenshot, v -> FeatureFlags.isGhostScreenshot = v,
                    R.string.ig_quick_toggle_name_screenshot_alerts),
            new Option(() -> FeatureFlags.quickToggleViewOnce, () -> FeatureFlags.isGhostViewOnce, v -> FeatureFlags.isGhostViewOnce = v,
                    R.string.ig_quick_toggle_name_view_once),
            new Option(() -> FeatureFlags.quickToggleStory, () -> FeatureFlags.isGhostStory, v -> FeatureFlags.isGhostStory = v,
                    R.string.ig_quick_toggle_name_stories),
            new Option(() -> FeatureFlags.quickToggleLive, () -> FeatureFlags.isGhostLive, v -> FeatureFlags.isGhostLive = v,
                    R.string.ig_quick_toggle_name_live),
            new Option(() -> FeatureFlags.quickToggleEphemeral, () -> FeatureFlags.keepEphemeralMessages, v -> FeatureFlags.keepEphemeralMessages = v,
                    R.string.ig_quick_toggle_name_disappearing),
            new Option(() -> FeatureFlags.quickTogglePermanentView, () -> FeatureFlags.permanentViewMode, v -> FeatureFlags.permanentViewMode = v,
                    R.string.ig_quick_toggle_name_permanent_view),
            new Option(() -> FeatureFlags.quickToggleAllowScreenshots, () -> FeatureFlags.allowScreenshots, v -> FeatureFlags.allowScreenshots = v,
                    R.string.ig_quick_toggle_name_screenshots),
    };

    public static boolean isGhostModeActive() {
        for (Option option : OPTIONS) {
            if (option.selected().getAsBoolean() && option.on().getAsBoolean()) return true;
        }
        return false;
    }


    public static void toggleSelectedGhostOptions(Context context) {
        boolean[] chosen = new boolean[OPTIONS.length];
        boolean[] on = new boolean[OPTIONS.length];
        for (int i = 0; i < OPTIONS.length; i++) {
            chosen[i] = OPTIONS[i].selected().getAsBoolean();
            on[i] = OPTIONS[i].on().getAsBoolean();
        }

        if (QuickToggleSummary.opensPicker(chosen)) {
            Activity activity = UIHookManager.getCurrentActivity();
            if (activity != null) {
                GhostEmojiManager.addGhostEmojiNextToInbox(activity, false);
            }
            DialogUtils.showGhostQuickTogglePicker(context instanceof Activity || activity == null ? context : activity);
            return;
        }

        boolean newState = QuickToggleSummary.turnsOn(chosen, on);
        List<String> names = new ArrayList<>();
        for (int i = 0; i < OPTIONS.length; i++) {
            if (!chosen[i]) continue;
            OPTIONS[i].set().accept(newState);
            names.add(I18n.t(context, OPTIONS[i].name()));
        }

        SettingsManager.saveAllFlags();

        Activity activity = UIHookManager.getCurrentActivity();
        if (activity != null) {
            GhostEmojiManager.addGhostEmojiNextToInbox(activity, newState);
        }

        ps.reso.instaeclipse.utils.toast.IeToast.makeText(context, QuickToggleSummary.message(newState, names,
                I18n.t(context, R.string.ig_toast_ghost_on), I18n.t(context, R.string.ig_toast_ghost_off)),
                Toast.LENGTH_SHORT).show();
    }
}
