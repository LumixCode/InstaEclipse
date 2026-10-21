package ps.reso.instaeclipse.mods.ui.theme;

import ps.reso.instaeclipse.utils.feature.FeatureFlags;

/**
 * Pure helpers for resolving the effective palette from a preset id / custom JSON pair.
 * Preset id 0 means "use the custom JSON palette"; any other id looks up a built-in preset.
 */
public final class ThemeSettingsHelper {

    public static final int CUSTOM_PRESET_ID = 0;

    private ThemeSettingsHelper() {}

    /** The prefs the palette is resolved from; no other synced setting can change the theme. */
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
