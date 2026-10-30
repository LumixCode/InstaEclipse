package ps.reso.instaeclipse.Xposed;

import android.app.Activity;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import ps.reso.instaeclipse.mods.extras.AirplaneModeHook;
import ps.reso.instaeclipse.mods.ghost.BulkUnsend;
import ps.reso.instaeclipse.mods.ghost.HideChatsHook;
import ps.reso.instaeclipse.mods.ghost.ui.GhostEmojiManager;
import ps.reso.instaeclipse.mods.misc.LikeUndoHook;
import ps.reso.instaeclipse.mods.ui.FancyBioFontHook;
import ps.reso.instaeclipse.mods.ui.LockDirectMessagesHook;
import ps.reso.instaeclipse.mods.ui.UIHookManager;
import ps.reso.instaeclipse.mods.ui.theme.IgThemeEngine;
import ps.reso.instaeclipse.mods.ui.theme.IgThemeHook;
import ps.reso.instaeclipse.mods.ui.theme.ThemeSettingsHelper;
import ps.reso.instaeclipse.utils.core.SettingsManager;
import ps.reso.instaeclipse.utils.dialog.IeSheet;
import ps.reso.instaeclipse.utils.dialog.RestartPrompt;
import ps.reso.instaeclipse.utils.feature.RestartPending;
import ps.reso.instaeclipse.utils.feature.RestartPromptRule;
import ps.reso.instaeclipse.utils.ghost.GhostModeUtils;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.media.StoryCache;
import ps.reso.instaeclipse.utils.media.StoryCachePrompt;

public final class SettingsEvents {

    private static final Set<String> GHOST_KEYS = keys(
            "isGhostSeen", "isGhostTyping", "isGhostStory", "isGhostLive", "isGhostScreenshot", "isGhostViewOnce",
            "quickToggleSeen", "quickToggleTyping", "quickToggleScreenshot", "quickToggleViewOnce",
            "quickToggleStory", "quickToggleLive", "quickToggleEphemeral", "quickTogglePermanentView",
            "quickToggleAllowScreenshots", "keepEphemeralMessages", "permanentViewMode", "allowScreenshots");
    private static final Set<String> CHAT_KEYS = keys("hideSpecificChats", "showHideChatButton");
    private static final Set<String> STORY_CACHE_KEYS = keys("cacheStories", "storyCacheHours");
    private static final Set<String> THEME_KEYS =
            keys("customThemeEnabled", "themePresetId", "themePaletteJson", "themeLightPresetId");
    private static final Set<String> LOCK_KEYS = keys("lockWholeApp", "lockDirectMessages", "lockSpecificMessages",
            "lockDirectAlways", "lockedDirectThreads", "lockDirectPasscode", "lockDirectSalt");

    public static final Set<String> SIDE_EFFECT_KEYS = union(GHOST_KEYS, CHAT_KEYS, STORY_CACHE_KEYS, THEME_KEYS,
            keys("airplaneMode", "likeUndo", "bulkUnsend"));

    private SettingsEvents() {}

    public static boolean afterChange(String key) {
        return afterChange(Collections.singletonList(key));
    }

    public static boolean afterChange(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) return false;
        boolean chats = false;
        boolean storyCache = false;
        boolean caching = false;
        boolean airplane = false;
        boolean likeUndo = false;
        boolean theme = false;
        boolean ghost = false;
        boolean bulkUnsend = false;
        boolean locks = false;
        boolean fancyBio = false;
        boolean restart = false;
        for (String key : keys) {
            if (key == null) continue;
            chats |= CHAT_KEYS.contains(key);
            storyCache |= STORY_CACHE_KEYS.contains(key);
            caching |= "cacheStories".equals(key);
            airplane |= "airplaneMode".equals(key);
            likeUndo |= "likeUndo".equals(key);
            theme |= ThemeSettingsHelper.isThemeKey(key);
            ghost |= GHOST_KEYS.contains(key);
            bulkUnsend |= "bulkUnsend".equals(key);
            locks |= LOCK_KEYS.contains(key);
            fancyBio |= "fancyBioFont".equals(key);
            restart |= RestartPending.captured(key);
        }
        boolean recreating = false;
        if (chats) HideChatsHook.refreshVisibleButtons();
        if (storyCache) StoryCache.refreshAsync();
        if (caching) StoryCachePrompt.cachingChanged(IeSheet.showing() != null);
        if (airplane) AirplaneModeHook.apply();
        if (likeUndo) LikeUndoHook.onLikeUndoChanged();
        if (bulkUnsend) BulkUnsend.switched();
        if (locks) LockDirectMessagesHook.locksChanged();
        if (fancyBio) FancyBioFontHook.switched();
        if (theme) {
            IgThemeEngine.invalidate();
            recreating = IgThemeHook.refreshCurrentActivity();
        }
        if (ghost) {
            Activity activity = UIHookManager.getCurrentActivity();
            if (activity != null) GhostEmojiManager.addGhostEmojiNextToInbox(activity, GhostModeUtils.isGhostModeActive());
        }
        if (restart) promptRestart(keys, recreating);
        return recreating;
    }

    private static void promptRestart(Collection<String> applied, boolean recreating) {
        SettingsManager.publishRestartPending();
        Set<String> pending = RestartPending.pending();
        RestartPrompt.retain(pending);
        List<String> prompt = RestartPromptRule.toPrompt(RestartPending.affected(applied), pending, RestartPrompt.shown());
        if (prompt.isEmpty()) {
            ModuleLog.detail("(IE|Restart) No restart prompt for " + applied + ", pending: " + RestartPromptRule.format(pending));
            return;
        }
        ModuleLog.detail("(IE|Restart) Restart prompt for " + RestartPromptRule.format(prompt));
        RestartPrompt.show(recreating ? null : UIHookManager.topActivity(), prompt);
    }

    private static Set<String> keys(String... keys) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(keys)));
    }

    @SafeVarargs
    private static Set<String> union(Set<String>... sets) {
        Set<String> out = new LinkedHashSet<>();
        for (Set<String> s : sets) out.addAll(s);
        return Collections.unmodifiableSet(out);
    }
}
