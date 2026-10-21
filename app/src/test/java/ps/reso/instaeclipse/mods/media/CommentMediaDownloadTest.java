package ps.reso.instaeclipse.mods.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class CommentMediaDownloadTest {

    private static final String CDN = "https://scontent-cph2-1.cdninstagram.com/v/t51.29350-15/fixed_height.gif?oh=1&oe=2";
    private static final String GIPHY = "https://media2.giphy.com/media/3oge84qhopFbFFkwec/200.gif";

    @Test
    public void instagramsCopyBeatsGiphysInEitherOrder() {
        assertEquals(CDN, CommentMediaDownload.pickGifUrl(List.of(GIPHY, CDN)));
        assertEquals(CDN, CommentMediaDownload.pickGifUrl(List.of(CDN, GIPHY)));
    }

    @Test
    public void giphyIsUsedWhenItIsTheOnlyAddressTheEngineAccepts() {
        assertEquals(GIPHY, CommentMediaDownload.pickGifUrl(List.of(GIPHY)));
        assertEquals(GIPHY, CommentMediaDownload.pickGifUrl(
                List.of("https://example.com/x.gif", "https://giphy.com.evil.com/x.gif", GIPHY)));
    }

    @Test
    public void withoutAnAcceptedAddressTheFirstIsKeptForASaveInsideInstagram() {
        String other = "https://example.com/x.gif";
        assertEquals(other, CommentMediaDownload.pickGifUrl(List.of(other, "https://notgiphy.com/x.gif")));
        assertNull(CommentMediaDownload.pickGifUrl(Collections.emptyList()));
    }
}
