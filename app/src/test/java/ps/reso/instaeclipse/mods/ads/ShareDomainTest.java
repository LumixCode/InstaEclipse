package ps.reso.instaeclipse.mods.ads;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class ShareDomainTest {

    @Test
    public void acceptsEmbedMirrorsThatEndInInstagramCom() {
        // The dialogs hint ddinstagram.com; kk/uu/vx mirrors share the suffix.
        assertEquals("ddinstagram.com", ShareDomain.normalize("ddinstagram.com"));
        assertEquals("kkinstagram.com", ShareDomain.normalize("https://KKinstagram.com/"));
        assertEquals("vxinstagram.com", ShareDomain.normalize("vxinstagram.com/p/abc"));
    }

    @Test
    public void rejectsInstagramItself() {
        assertNull(ShareDomain.normalize("instagram.com"));
        assertNull(ShareDomain.normalize("https://www.instagram.com/"));
        assertNull(ShareDomain.normalize("l.instagram.com"));
    }

    @Test
    public void emptyTurnsTheFeatureOff() {
        assertEquals("", ShareDomain.normalize("  "));
        assertEquals("", ShareDomain.normalize(null));
    }
}
