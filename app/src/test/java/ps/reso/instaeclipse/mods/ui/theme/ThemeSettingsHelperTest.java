package ps.reso.instaeclipse.mods.ui.theme;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ThemeSettingsHelperTest {
    @Test public void onlyThemePrefsRepaintInstagram() {
        for (String key : new String[]{
                "customThemeEnabled", "themePresetId", "themePaletteJson", "themeLightPresetId"}) {
            assertTrue(key, ThemeSettingsHelper.isThemeKey(key));
        }
        // Every other synced setting used to recreate Instagram's screen while the theme was on.
        for (String key : new String[]{"isGhostTyping", "dailyReelLimit", "spoofLat", "", null}) {
            assertFalse(String.valueOf(key), ThemeSettingsHelper.isThemeKey(key));
        }
    }
}
