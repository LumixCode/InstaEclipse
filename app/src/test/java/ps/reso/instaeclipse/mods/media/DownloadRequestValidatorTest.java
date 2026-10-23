package ps.reso.instaeclipse.mods.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DownloadRequestValidatorTest {

    @Test
    public void acceptsInstagramCdnHttps() {
        assertTrue(DownloadRequestValidator.isAllowedMediaUrl("https://scontent-cph2-1.cdninstagram.com/v/t51/x.jpg?a=1"));
        assertTrue(DownloadRequestValidator.isAllowedMediaUrl("https://video.fcph1-1.fna.fbcdn.net/o1/v/t2/x.mp4"));
        assertTrue(DownloadRequestValidator.isAllowedMediaUrl("https://lookaside.fbsbx.com/ig_messaging_cdn/?asset_id=1"));
    }

    @Test
    public void acceptsGiphyHttpsForGifComments() {
        assertTrue(DownloadRequestValidator.isAllowedMediaUrl("https://media2.giphy.com/media/3oge84qhopFbFFkwec/200.gif"));
        assertFalse(DownloadRequestValidator.isAllowedMediaUrl("http://media.giphy.com/media/x/200.gif"));
        assertFalse(DownloadRequestValidator.isAllowedMediaUrl("https://giphy.com.evil.com/x.gif"));
        assertFalse(DownloadRequestValidator.isAllowedMediaUrl("https://notgiphy.com/x.gif"));
    }

    @Test
    public void rejectsOtherHostsAndSchemes() {
        assertFalse(DownloadRequestValidator.isAllowedMediaUrl("http://scontent.cdninstagram.com/x.jpg"));
        assertFalse(DownloadRequestValidator.isAllowedMediaUrl("https://evil.com/cdninstagram.com/x.jpg"));
        assertFalse(DownloadRequestValidator.isAllowedMediaUrl("https://cdninstagram.com.evil.com/x.jpg"));
        assertFalse(DownloadRequestValidator.isAllowedMediaUrl("https://user@evil.cdninstagram.com/x.jpg"));
        assertFalse(DownloadRequestValidator.isAllowedMediaUrl("file:///sdcard/x.jpg"));
        assertFalse(DownloadRequestValidator.isAllowedMediaUrl(null));
    }

    // Cases from the former DownloadSaveServiceUrlTest.
    @Test
    public void metaCdnAllowlistEdgeCases() {
        for (String url : new String[]{
                "https://scontent-ams2-1.cdninstagram.com/v/t51.2885-15/1.jpg?oh=a&oe=b",
                "https://instagram.fams1-2.fna.fbcdn.net/o1/v/t16/f2/m86/video.mp4",
                "https://cdn.fbsbx.com/v/t59.3654-21/audio.mp4",
                "https://CDNINSTAGRAM.COM/x.jpg"}) {
            assertTrue(url, DownloadRequestValidator.isAllowedMediaUrl(url));
        }
        for (String url : new String[]{
                "", "not a url", "/sdcard/x.jpg",
                "https://evil.test/?u=cdninstagram.com",            // CDN name only in the query
                "https://evilfbcdn.net/x.jpg",                      // not a subdomain
                "https://user@scontent.cdninstagram.com/x.jpg",     // credentials in authority
                "https://192.168.0.1/x.jpg"}) {
            assertFalse(url, DownloadRequestValidator.isAllowedMediaUrl(url));
        }
    }

    @Test
    public void fileNamesStayInsideTheFolder() {
        String name = DownloadRequestValidator.sanitizeFileName("../../etc/passwd", "x");
        assertFalse(name.contains("/") || name.startsWith("."));
        assertEquals("fallback", DownloadRequestValidator.sanitizeFileName("...", "fallback"));
        assertEquals("user_post_1of2.jpg", DownloadRequestValidator.sanitizeFileName("user_post_1of2.jpg", "x"));
    }

    @Test
    public void subdirSegmentsAreCleaned() {
        assertEquals("Chats/a T", DownloadRequestValidator.sanitizeSubdir("Chats/a T"));
        assertEquals("Chats/x", DownloadRequestValidator.sanitizeSubdir("../Chats/./x"));
        assertNull(DownloadRequestValidator.sanitizeSubdir("../.."));
    }
}
