package ps.reso.instaeclipse.mods.misc;

import org.junit.Test;
import static org.junit.Assert.*;

public class CaptionTextPolicyTest {
    private static final String CAPTION =
            "Three wins in a row. @rider_one, take a bow 🎩👏\n\n#SundayRace🇮🇩 #Racing";

    @Test public void collapsedFeedCaptionAfterTheUsername() {
        assertTrue(CaptionTextPolicy.shows("race_team Three wins in a row. @rider_one, take a b… more", CAPTION));
    }

    @Test public void collapsedReelCaption() {
        assertTrue(CaptionTextPolicy.shows("Follow @brand_account — the next generation of …",
                "Follow @brand_account — the next generation of energy\n\n#ad"));
    }

    @Test public void expandedCaptionWithDifferentLineBreaks() {
        assertTrue(CaptionTextPolicy.shows("race_team Three wins in a row. @rider_one, take a bow 🎩👏 #SundayRace🇮🇩 #Racing",
                CAPTION));
    }

    @Test public void captionCollapsedAfterAShortFirstLine() {
        assertTrue(CaptionTextPolicy.shows("some_creator Wow… more", "Wow\n\nThis is the rest of the caption"));
    }

    @Test public void commentsOnThePostDoNotMatch() {
        assertFalse(CaptionTextPolicy.shows("someone Nice shot!", CAPTION));
        assertFalse(CaptionTextPolicy.shows("someone what a race today, unbelievable…", CAPTION));
    }

    @Test public void expandedReelCaptionShowsTheWholeCaption() {
        assertTrue(CaptionTextPolicy.showsWhole("⁦Follow @brand_account — the next generation of energy\n#ad⁩",
                "Follow @brand_account — the next generation of energy\n\n#ad"));
    }

    @Test public void invisibleMarksInsideTheShownCaptionStillMatch() {
        String caption = "Follow @brand_account — the next generation of energy\n\n#ad";
        String shown = "Follow ⁨@brand_account⁩ — the next​ generation of energy\n⁦#ad⁩ less";
        assertTrue(CaptionTextPolicy.showsWhole(shown, caption));
        assertTrue(CaptionTextPolicy.shows(shown, caption));
    }

    @Test public void collapsedOrDifferentCaptionIsNotWhole() {
        String caption = "Follow @brand_account — the next generation of energy\n\n#ad";
        assertFalse(CaptionTextPolicy.showsWhole("Follow @brand_account — the next generation of …", caption));
        assertFalse(CaptionTextPolicy.showsWhole("Follow @brand_account — the next generation of gaming", caption));
        assertFalse(CaptionTextPolicy.showsWhole(null, caption));
        assertFalse(CaptionTextPolicy.showsWhole("anything", " \n "));
    }

    @Test public void placeholderAndMissingTextDoNotMatch() {
        assertFalse(CaptionTextPolicy.shows("See more", "Short caption.."));
        assertFalse(CaptionTextPolicy.shows(null, CAPTION));
        assertFalse(CaptionTextPolicy.shows("race_team Hat-trick", null));
        assertFalse(CaptionTextPolicy.shows("race_team Hat-trick", " \n "));
    }
}
