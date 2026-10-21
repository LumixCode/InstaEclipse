package ps.reso.instaeclipse.mods.ghost;

import org.junit.Test;
import static org.junit.Assert.*;

public class HeaderTapZoneTest {
    // 1080 px wide header: name drawn at 304..390, call buttons from 900.
    @Test public void ltrSpanRunsFromAfterTheNameToTheCallButtons() {
        assertArrayEquals(new int[]{414, 900}, HeaderTapZone.span(304, 390, 0, 900, false, 24, 72));
    }

    @Test public void rtlSpanIsMirrored() {
        // Mirrored: call buttons end at 180, name drawn at 690..776.
        assertArrayEquals(new int[]{180, 666}, HeaderTapZone.span(690, 776, 180, 1080, true, 24, 72));
    }

    @Test public void withoutCallButtonsTheSpanReachesTheHeaderEnd() {
        assertArrayEquals(new int[]{414, 1080}, HeaderTapZone.span(304, 390, 0, 1080, false, 24, 72));
    }

    @Test public void longNameLeavesNoSpan() {
        assertNull(HeaderTapZone.span(304, 870, 0, 900, false, 24, 72));
        assertNull(HeaderTapZone.span(210, 776, 180, 1080, true, 24, 72));
    }

    @Test public void spanOfExactlyMinWidthIsKept() {
        assertArrayEquals(new int[]{414, 486}, HeaderTapZone.span(304, 390, 0, 486, false, 24, 72));
        assertNull(HeaderTapZone.span(304, 390, 0, 485, false, 24, 72));
    }

    @Test public void subtitleDrawnByABackgroundDoesNotDefineTheName() {
        // A sample 1:1 chat header: name box 304..776 (472 wide), title text 304..510,
        // header_subtitle (a plain View) 304..776, call buttons from 807, density 2.625.
        assertFalse(HeaderTapZone.boundsShowName(false, 472, 472));
        assertArrayEquals(new int[]{531, 807}, HeaderTapZone.span(304, 510, 0, 807, false, 21, 63));
        // Counting the subtitle's bounds left no zone at all.
        assertNull(HeaderTapZone.span(304, 776, 0, 807, false, 21, 63));
    }

    @Test public void textImagesAndNarrowViewsKeepTheirBounds() {
        assertTrue(HeaderTapZone.boundsShowName(true, 472, 472)); // a TextView (text measured) or an ImageView
        assertTrue(HeaderTapZone.boundsShowName(false, 40, 472)); // e.g. a status dot beside the name
    }
}
