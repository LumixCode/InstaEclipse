package ps.reso.instaeclipse.mods.misc;

import org.junit.Test;
import static org.junit.Assert.*;

public class PendingLikesTest {
    @Test public void heldLikeIsReleasedOnceWhenItsTimeIsUp() {
        PendingLikes<String> likes = new PendingLikes<>();
        Object media = new Object();
        likes.hold(media, "like");
        assertTrue(likes.isHeld(media, "like"));
        assertTrue(likes.release(media, "like"));
        assertFalse(likes.release(media, "like"));
        assertTrue(likes.isEmpty());
    }

    @Test public void unlikeDuringTheWaitCancelsTheLike() {
        PendingLikes<String> likes = new PendingLikes<>();
        Object media = new Object();
        likes.hold(media, "like");
        assertEquals("like", likes.cancel(media));
        assertFalse(likes.isHeld(media, "like"));
        assertFalse("a taken-back like is never sent", likes.release(media, "like"));
    }

    @Test public void unlikeAfterTheLikeWentOutFindsNothing() {
        PendingLikes<String> likes = new PendingLikes<>();
        Object media = new Object();
        likes.hold(media, "like");
        likes.release(media, "like");
        assertNull("the unlike is real and must be sent", likes.cancel(media));
    }

    @Test public void likeAgainAfterTakingItBackStartsANewWait() {
        PendingLikes<String> likes = new PendingLikes<>();
        Object media = new Object();
        likes.hold(media, "first");
        likes.cancel(media);
        likes.hold(media, "second");
        assertFalse(likes.release(media, "first"));
        assertTrue(likes.release(media, "second"));
    }

    @Test public void postsAreKeptApartByTheirMediaObject() {
        PendingLikes<String> likes = new PendingLikes<>();
        // Equal but distinct objects are different posts: identity, not equals().
        Object a = new String("media"), b = new String("media");
        likes.hold(a, "a");
        likes.hold(b, "b");
        assertEquals("a", likes.cancel(a));
        assertTrue(likes.isHeld(b, "b"));
        assertFalse(likes.contains(new String("media")));
    }
}
