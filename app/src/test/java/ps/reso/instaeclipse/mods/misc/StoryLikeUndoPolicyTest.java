package ps.reso.instaeclipse.mods.misc;

import org.junit.Test;
import static org.junit.Assert.*;
import static ps.reso.instaeclipse.mods.misc.StoryLikeUndoPolicy.Action.*;

public class StoryLikeUndoPolicyTest {
    @Test public void aLikeIsHeldWhileTheWindowIsOn() {
        assertEquals(HOLD, StoryLikeUndoPolicy.decide(true, true, false, false));
    }

    @Test public void aLikeGoesOutAtOnceWithTheWindowOff() {
        assertEquals(PASS, StoryLikeUndoPolicy.decide(false, true, false, false));
    }

    @Test public void unlikingAHeldLikeTakesItBack() {
        assertEquals(TAKE_BACK, StoryLikeUndoPolicy.decide(true, false, false, true));
        assertEquals("turning the window off keeps the take-back of a like it held",
                TAKE_BACK, StoryLikeUndoPolicy.decide(false, false, false, true));
    }

    @Test public void unlikingASentLikeIsARealUnlike() {
        assertEquals(PASS, StoryLikeUndoPolicy.decide(true, false, false, false));
    }

    @Test public void superlikesAreNeverHeld() {
        assertEquals(PASS, StoryLikeUndoPolicy.decide(true, true, true, false));
        assertEquals("a superlike replaces a plain like still waiting",
                SUPERSEDE, StoryLikeUndoPolicy.decide(true, true, true, true));
    }

    @Test public void aLikeWithTheWindowOffReplacesOneStillHeld() {
        assertEquals(SUPERSEDE, StoryLikeUndoPolicy.decide(false, true, false, true));
    }

    @Test public void heldStoryLikesAreFoundByMediaId() {
        PendingLikes<String> likes = PendingLikes.byId();
        // The liked check is handed other objects for the same story: equal ids are one story.
        likes.hold(new String("3456_789"), "like");
        assertTrue(likes.contains(new String("3456_789")));
        assertTrue(likes.isHeld("3456_789", "like"));
        assertEquals("like", likes.cancel(new String("3456_789")));
        assertFalse(likes.contains("3456_789"));
        assertTrue(likes.isEmpty());
    }
}
