package ps.reso.instaeclipse.mods.ui.theme;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class ThemeColorSlotTest {
    // Every color name IgThemeEngine/IgColorRemapEngine map, with its IG 451 value (aapt dump of the
    // reference APK; igds_primary_background at night, emphasized_action_color's default state).
    private static final Object[][] IG451 = {
            {"bds_black", 0xFF000000}, {"igds_prism_black", 0xFF0C1014}, {"bds_white", 0xFFFFFFFF},
            {"igds_prism_gray_00", 0xFFF8F9F9}, {"bds_grey_0", 0xFFF5F5F5}, {"bds_grey_1", 0xFFEFEFEF},
            {"bds_grey_2", 0xFFDBDBDB}, {"bds_grey_3", 0xFFC7C7C7}, {"bds_grey_4", 0xFFA8A8A8},
            {"bds_grey_6", 0xFF737373}, {"bds_grey_7", 0xFF555555}, {"bds_grey_8", 0xFF363636},
            {"bds_grey_9", 0xFF262626}, {"bds_grey_10", 0xFF1A1A1A}, {"bds_grey_11", 0xFFBCC0C4},
            {"bds_grey_12", 0xFF233038}, {"bds_grey_16", 0xFF1E1E1E}, {"bds_grey_18", 0xFFF3F5F7},
            {"bds_grey_21", 0xFF32353E}, {"bds_grey_22", 0xFF1C1D22}, {"bds_grey_24", 0xFF212328},
            {"igds_prism_gray_08", 0xFF2B3036}, {"igds_prism_gray_10", 0xFF212328},
            {"emphasized_action_color", 0xFF0095F6}, {"badge_color", 0xFF0095F6},
            {"igds_prism_indigo_1000", 0xFF4A5DF9}, {"bds_blue_1", 0xFFE0F1FF}, {"bds_blue_2", 0xFFB3DBFF},
            {"bds_red_5", 0xFFFF3040}, {"bds_red_6", 0xFFDB2634}, {"igds_primary_background", 0xFF000000},
            {"bottom_sheet_undo_redo_color", 0xFFFFFFFF},
    };

    private static String slot(String name) {
        int slot = IgThemeEngine.slotForColorName(name);
        return slot < 0 ? "unmapped" : IgThemePalette.SLOT_KEYS[slot];
    }

    @Test public void primaryBackgroundIsTheBackgroundNotTheSurface() {
        // @bds_white, or @bds_black at night: the base page color, same slot as bds_black.
        assertEquals(IgThemePalette.SLOT_BACKGROUND, slot("igds_primary_background"));
        assertEquals(IgThemePalette.SLOT_BACKGROUND, slot("bds_black"));
        assertEquals(IgThemePalette.SLOT_BACKGROUND, slot("igds_prism_black"));
    }

    @Test public void darkGreyBackgroundsAreSurfacesNotSecondaryText() {
        // Tertiary, reaction, intent-card and prompt-suggestion backgrounds, and IG's elevated and
        // secondary backgrounds: "grey_1"/"grey_2" substrings used to send the first five to text.
        for (String name : new String[]{"bds_grey_12", "bds_grey_16", "bds_grey_21", "bds_grey_22", "bds_grey_24",
                "bds_grey_9", "bds_grey_10", "igds_prism_gray_10", "igds_prism_gray_08", "bds_grey_8", "bds_grey_7"}) {
            assertEquals(name, IgThemePalette.SLOT_SURFACE, slot(name));
        }
    }

    @Test public void lightGreysStayText() {
        for (String name : new String[]{"bds_grey_1", "bds_grey_2", "bds_grey_3", "bds_grey_4", "bds_grey_6",
                "bds_grey_11", "bds_grey_18"}) {
            assertEquals(name, IgThemePalette.SLOT_SECONDARY_TEXT, slot(name));
        }
        for (String name : new String[]{"bds_grey_0", "igds_prism_gray_00", "bds_white"}) {
            assertEquals(name, IgThemePalette.SLOT_PRIMARY_TEXT, slot(name));
        }
    }

    @Test public void everyListedNameIsMapped() {
        for (Object[] row : IG451) assertNotEquals((String) row[0], "unmapped", slot((String) row[0]));
        assertEquals(IgThemePalette.SLOT_BUTTON, slot("badge_color"));
        assertEquals(slot("bds_white"), slot("bottom_sheet_undo_redo_color"));
    }

    @Test public void noDarkIgGreyLandsOnATextSlotAndNoLightOneOnAPaperSlot() {
        for (Object[] row : IG451) {
            String name = (String) row[0];
            int c = (Integer) row[1];
            int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
            if (Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) >= 28) continue; // not a grey
            double lum = (r * 0.299d + g * 0.587d + b * 0.114d) / 255.0d;
            String slot = slot(name);
            boolean paper = slot.equals(IgThemePalette.SLOT_BACKGROUND) || slot.equals(IgThemePalette.SLOT_SURFACE);
            boolean ink = slot.equals(IgThemePalette.SLOT_PRIMARY_TEXT) || slot.equals(IgThemePalette.SLOT_SECONDARY_TEXT);
            if (lum < 0.25d) assertTrue(name + " -> " + slot, paper);
            if (lum > 0.6d) assertTrue(name + " -> " + slot, ink);
        }
    }
}
