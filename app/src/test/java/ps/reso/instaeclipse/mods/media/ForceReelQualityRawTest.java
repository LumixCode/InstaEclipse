package ps.reso.instaeclipse.mods.media;

import org.junit.After;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class ForceReelQualityRawTest {
    private static final List<String> ALL = List.of("1080p", "720p", "480p");

    /** The hooked video_versions getter: the forced single rendition unless RAW is set. */
    private static List<String> videoVersions() {
        return ForceReelQualityHook.RAW.get() != null ? ALL : Collections.singletonList("720p");
    }

    @After public void clear() { ForceReelQualityHook.RAW.remove(); }

    @Test public void nestedReadKeepsTheOuterScopeRaw() throws Exception {
        // SelectedMediaAdapter.capture -> originalVideo -> collectUrlsFromVideoVersionsMethod,
        // then the snapshot's own video_versions read in the same outer scope.
        List<String> renditions = ForceReelQualityHook.raw(() -> {
            assertEquals(ALL, ForceReelQualityHook.raw(ForceReelQualityRawTest::videoVersions));
            return videoVersions();
        });
        assertEquals(ALL, renditions);
        assertNull(ForceReelQualityHook.RAW.get());
        assertEquals(List.of("720p"), videoVersions());
    }

    @Test public void failingReadRestoresTheCallersValue() {
        ForceReelQualityHook.RAW.set(Boolean.TRUE);
        try {
            ForceReelQualityHook.raw(() -> { throw new IllegalStateException("fixture"); });
            fail();
        } catch (Exception expected) { assertEquals("fixture", expected.getMessage()); }
        assertSame(Boolean.TRUE, ForceReelQualityHook.RAW.get());
        ForceReelQualityHook.RAW.remove();
        try { ForceReelQualityHook.raw(() -> { throw new IllegalStateException("fixture"); }); }
        catch (Exception ignored) { }
        assertNull(ForceReelQualityHook.RAW.get());
    }
}
