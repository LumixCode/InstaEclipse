package ps.reso.instaeclipse.Xposed;

import android.app.Activity;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import ps.reso.instaeclipse.mods.extras.AirplaneModeHook;
import ps.reso.instaeclipse.mods.ghost.HideChatsHook;
import ps.reso.instaeclipse.mods.ghost.ui.GhostEmojiManager;
import ps.reso.instaeclipse.mods.misc.LikeUndoHook;
import ps.reso.instaeclipse.mods.ui.UIHookManager;
import ps.reso.instaeclipse.mods.ui.theme.IgThemeEngine;
import ps.reso.instaeclipse.mods.ui.theme.IgThemeHook;
import ps.reso.instaeclipse.mods.ui.theme.ThemeSettingsHelper;
import ps.reso.instaeclipse.utils.ghost.GhostModeUtils;
import ps.reso.instaeclipse.utils.media.StoryCache;

public final class SettingsEvents {

    private static final Set<String> GHOST_SWITCHES = new HashSet<>(Arrays.asList(
            "keepEphemeralMessages", "permanentViewMode", "allowScreenshots"));

    private SettingsEvents() {}

    public static void afterChange(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) return;
        boolean chats = false;
        boolean storyCache = false;
        boolean airplane = false;
        boolean likeUndo = false;
        boolean theme = false;
        boolean ghost = false;
        for (String key : keys) {
            if (key == null) continue;
            chats |= "hideSpecificChats".equals(key) || "showHideChatButton".equals(key);
            storyCache |= "storyCacheHours".equals(key);
            airplane |= "airplaneMode".equals(key);
            likeUndo |= "likeUndo".equals(key);
            theme |= ThemeSettingsHelper.isThemeKey(key);
            ghost |= isGhostKey(key);
        }
        if (chats) HideChatsHook.refreshVisibleButtons();
        if (storyCache) StoryCache.refresh();
        if (airplane) AirplaneModeHook.apply();
        if (likeUndo) LikeUndoHook.onLikeUndoChanged();
        if (theme) {
            IgThemeEngine.invalidate();
            IgThemeHook.refreshCurrentActivity();
        }
        if (ghost) {
            Activity activity = UIHookManager.getCurrentActivity();
            if (activity != null) GhostEmojiManager.addGhostEmojiNextToInbox(activity, GhostModeUtils.isGhostModeActive());
        }
    }

    static boolean isGhostKey(String key) {
        return key.startsWith("isGhost") || key.startsWith("quickToggle") || GHOST_SWITCHES.contains(key);
    }
}
