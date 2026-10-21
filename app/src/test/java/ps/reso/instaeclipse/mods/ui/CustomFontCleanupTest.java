package ps.reso.instaeclipse.mods.ui;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.*;

public class CustomFontCleanupTest {
    @Rule public TemporaryFolder dir = new TemporaryFolder();

    private File file(String name, long modified) throws IOException {
        File f = dir.newFile(name);
        assertTrue(f.setLastModified(modified));
        return f;
    }

    @Test public void deletesOnlyOlderCopiesOfTheSameFont() throws IOException {
        File legacy = file("ie_custom_font.ttf", 1_700_000_000_000L);
        File leftoverCopy = file("ie_custom_font8123.tmp", 1_700_000_100_000L);
        File current = file("ie_custom_font_1700000200000.ttf", 1_700_000_200_000L);
        File newer = file("ie_custom_font_1700000300000.ttf", 1_700_000_300_000L);
        File emoji = file("ie_custom_emoji_1700000000000.ttf", 1_700_000_000_000L);
        File other = file("ie_unsent_log.json", 1_700_000_000_000L);

        assertEquals(2, CustomFontHook.deleteSuperseded("ie_custom_font", current.getAbsolutePath()));
        assertFalse(legacy.exists());
        assertFalse(leftoverCopy.exists());
        assertTrue(current.exists());
        assertTrue(newer.exists());
        assertTrue(emoji.exists());
        assertTrue(other.exists());
    }

    @Test public void legacyFixedNameStaysWhileItIsTheActiveFont() throws IOException {
        File legacy = file("ie_custom_emoji.ttf", 1_700_000_000_000L);
        assertEquals(0, CustomFontHook.deleteSuperseded("ie_custom_emoji", legacy.getAbsolutePath()));
        assertTrue(legacy.exists());
    }

    @Test public void missingOrUnsetActiveFontDeletesNothing() throws IOException {
        File old = file("ie_custom_font_1700000000000.ttf", 1_700_000_000_000L);
        File gone = new File(dir.getRoot(), "ie_custom_font_1700000200000.ttf");
        assertEquals(0, CustomFontHook.deleteSuperseded("ie_custom_font", gone.getAbsolutePath()));
        assertEquals(0, CustomFontHook.deleteSuperseded("ie_custom_font", ""));
        assertEquals(0, CustomFontHook.deleteSuperseded("ie_custom_font", null));
        assertTrue(old.exists());
    }
}
