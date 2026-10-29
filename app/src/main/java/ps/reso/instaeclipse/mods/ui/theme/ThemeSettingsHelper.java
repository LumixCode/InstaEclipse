package ps.reso.instaeclipse.mods.ui.theme;

import ps.reso.instaeclipse.utils.feature.FeatureFlags;

public final class ThemeSettingsHelper {

    public static final int CUSTOM_PRESET_ID = 0;

    private ThemeSettingsHelper() {}

    public static boolean isThemeKey(String key) {
        return "customThemeEnabled".equals(key) || "themePresetId".equals(key)
                || "themePaletteJson".equals(key) || "themeLightPresetId".equals(key);
    }

    public static boolean isCustomMode(int presetId) {
        return presetId == CUSTOM_PRESET_ID;
    }

    public static IgThemePalette resolveEffectivePalette() {
        return resolveEffectivePalette(FeatureFlags.themePresetId, FeatureFlags.themePaletteJson);
    }

    public static IgThemePalette resolveEffectivePalette(int presetId, String paletteJson) {
        if (presetId > 0) return ThemePresets.getById(presetId).palette.copy();
        return IgThemePalette.fromJson(paletteJson);
    }
}
