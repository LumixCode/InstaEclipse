package ps.reso.instaeclipse.mods.ads;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class TrackingParamStripperTest {

    @Test
    public void keepsCarouselIndexWhileDroppingTracking() {
        // #252: sharing slide 2 of a carousel must keep img_index.
        assertEquals("https://www.instagram.com/p/ABC123/?img_index=2",
                TrackingParamStripper.strip("https://www.instagram.com/p/ABC123/?img_index=2&stkn=xyz"));
    }

    @Test
    public void dropsShareLinkId() {
        assertEquals("https://www.instagram.com/p/AbCdEfGhIjK/",
                TrackingParamStripper.strip("https://www.instagram.com/p/AbCdEfGhIjK/?psln=cHNzeXhlMDJsYm5k"));
    }

    @Test
    public void removesWholeQueryWhenOnlyTrackingParams() {
        assertEquals("https://www.instagram.com/reel/XYZ/",
                TrackingParamStripper.strip("https://www.instagram.com/reel/XYZ/?igsh=abc&utm_source=ig_web_copy_link"));
    }

    @Test
    public void removesFourLetterPolymorphicTrackingParams() {
        assertEquals("https://www.instagram.com/reel/XYZ/",
                TrackingParamStripper.strip("https://www.instagram.com/reel/XYZ/?abcd=abc"));
        assertEquals("https://www.instagram.com/reel/XYZ/",
                TrackingParamStripper.strip("https://www.instagram.com/reel/XYZ/?xtok=abc"));
    }

    @Test
    public void removesFourLetterPolymorphicTrackingParamsKeepingCarouselIndex() {
        assertEquals("https://www.instagram.com/reel/XYZ/?img_index=2",
                TrackingParamStripper.strip("https://www.instagram.com/reel/XYZ/?img_index=2&xtok=abc"));
        assertEquals("https://www.instagram.com/reel/XYZ/?img_index=2",
                TrackingParamStripper.strip("https://www.instagram.com/reel/XYZ/?xtok=abc&img_index=2"));
    }

    @Test
    public void keepsOrderOfRemainingParamsAndFragment() {
        assertEquals("https://instagram.com/p/A/?hl=en&img_index=2#c",
                TrackingParamStripper.strip("https://instagram.com/p/A/?igshid=1&hl=en&utm_medium=x&img_index=2#c"));
    }

    @Test
    public void dropsRandomlyNamedShareIdOnPostsAndReels() {
        // Instagram renames the per-share id on every share (igsh, psln, mdkz, ...).
        assertEquals("https://www.instagram.com/p/AbCdEfGhIjK/",
                TrackingParamStripper.strip("https://www.instagram.com/p/AbCdEfGhIjK/?mdkz=dGVzdF9zaGFyZV9pZA=="));
        assertEquals("https://www.instagram.com/reel/AbCdEfGhIjK/",
                TrackingParamStripper.strip("https://www.instagram.com/reel/AbCdEfGhIjK/?Qwer=abc123"));
        assertEquals("https://www.instagram.com/reels/AbCdEfGhIjK/?img_index=1",
                TrackingParamStripper.strip("https://www.instagram.com/reels/AbCdEfGhIjK/?xkqa=Zz09&img_index=1"));
    }

    @Test
    public void dropsRandomlyNamedShareIdOnProfilesStoriesAndHighlights() {
        assertEquals("https://www.instagram.com/someone",
                TrackingParamStripper.strip("https://www.instagram.com/someone?utm_source=qr&abcd=MXd4"));
        assertEquals("https://instagram.com/stories/someone/3456/",
                TrackingParamStripper.strip("https://instagram.com/stories/someone/3456/?wxyz=MXd4"));
        assertEquals("https://www.instagram.com/s/aGlnaGxpZ2h0OjE3/",
                TrackingParamStripper.strip("https://www.instagram.com/s/aGlnaGxpZ2h0OjE3/?story_media_id=1_2&qpwo=MXd4"));
        assertEquals("instagram.com/p/A/",
                TrackingParamStripper.strip("instagram.com/p/A/?mdkz=MXd4"));
        assertEquals("https://m.instagram.com/p/A/",
                TrackingParamStripper.strip("https://m.instagram.com/p/A/?mdkz=MXd4"));
    }

    @Test
    public void keepsParamsThatChangeWhatTheLinkOpens() {
        assertEquals("https://www.instagram.com/p/X/?comment_id=17890123456789012",
                TrackingParamStripper.strip("https://www.instagram.com/p/X/?comment_id=17890123456789012&mdkz=MXd4"));
        assertNull(TrackingParamStripper.strip(
                "https://www.instagram.com/reel/X/?comment_id=17890123456789012&open_comments=true"));
        assertEquals("https://www.instagram.com/explore/search/keyword/?q=sunset",
                TrackingParamStripper.strip("https://www.instagram.com/explore/search/keyword/?q=sunset&mdkz=MXd4"));
        assertNull(TrackingParamStripper.strip("https://www.instagram.com/_n/reels_audio_page?audio_id=123456"));
        assertNull(TrackingParamStripper.strip("https://www.instagram.com/p/A/?img_index=2#comments"));
        assertEquals("https://WWW.Instagram.com/P/A/?IMG_INDEX=2",
                TrackingParamStripper.strip("https://WWW.Instagram.com/P/A/?MDKZ=MXd4&IMG_INDEX=2"));
    }

    @Test
    public void dropsIgsiOutsideShareLinks() {
        assertEquals("https://www.instagram.com/accounts/login/?next=%2Fp%2FA%2F",
                TrackingParamStripper.strip("https://www.instagram.com/accounts/login/?next=%2Fp%2FA%2F&igsi=MWNr"));
    }

    @Test
    public void leavesOtherDomainsAndClosingPunctuationAlone() {
        assertNull(TrackingParamStripper.strip("https://notinstagram.com/p/x/?page=2"));
        assertNull(TrackingParamStripper.strip("https://help.instagram.com/581066165581870?helpref=faq_content"));
        assertEquals("(see https://www.instagram.com/p/A/).",
                TrackingParamStripper.strip("(see https://www.instagram.com/p/A/?mdkz=MXd4)."));
        assertEquals("Look https://www.instagram.com/p/A/?img_index=2 wow",
                TrackingParamStripper.strip("Look https://www.instagram.com/p/A/?mdkz=MXd4&img_index=2 wow"));
    }

    @Test
    public void keepsUnknownParamsOutsideShareLinks() {
        // The link shim's u= is the destination; app pages may carry real input.
        assertNull(TrackingParamStripper.strip("https://l.instagram.com/?u=https%3A%2F%2Fexample.com&e=AT0"));
        assertEquals("https://l.instagram.com/?u=https%3A%2F%2Fexample.com&e=AT0",
                TrackingParamStripper.strip("https://l.instagram.com/?u=https%3A%2F%2Fexample.com&igsh=1&e=AT0"));
        assertEquals("https://www.instagram.com/accounts/login/?next=%2Fp%2FA%2F",
                TrackingParamStripper.strip("https://www.instagram.com/accounts/login/?next=%2Fp%2FA%2F&igsh=1"));
    }

    @Test
    public void cleansLinkInsideShareText() {
        assertEquals("Look at this https://www.instagram.com/p/A/?img_index=3 wow",
                TrackingParamStripper.strip("Look at this https://www.instagram.com/p/A/?igsh=q&img_index=3 wow"));
    }

    @Test
    public void returnsNullWhenNothingToStrip() {
        assertNull(TrackingParamStripper.strip("https://www.instagram.com/p/A/?img_index=2"));
        assertNull(TrackingParamStripper.strip("https://www.instagram.com/p/A/"));
        assertNull(TrackingParamStripper.strip("https://example.com/?utm_source=x"));
        assertNull(TrackingParamStripper.strip(null));
    }

    @Test
    public void tracksParamKeysCaseInsensitively() {
        assertEquals("https://www.instagram.com/p/A/",
                TrackingParamStripper.strip("https://www.instagram.com/p/A/?IGSH=abc"));
    }
}
