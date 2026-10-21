package ps.reso.instaeclipse.mods.misc;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static ps.reso.instaeclipse.mods.misc.ProfileFollowStatus.Status.*;

public class FollowingFollowStatusTest {
    @Test public void ownFollowingListShowsBothKnownDirections() {
        assertEquals(MUTUAL, status("me", "me", "FOLLOWING", true, true, true));
        assertEquals(DOES_NOT_FOLLOW, status("me", "me", "FOLLOWING", false, false, true));
        assertEquals(FOLLOWS_YOU, status("me", "me", "SELF_FOLLOWING", true, null, null));
        assertEquals(MUTUAL, status("me", "me", "FOLLOWING_SIMPLIFIED", null, true, true));
    }

    @Test public void otherProfilesAndOtherListTypesRemainUntouched() {
        assertEquals(HIDDEN, status("me", "someone_else", "FOLLOWING", false, false, true));
        for (String type : new String[]{"FOLLOWERS", "SELF_FOLLOWERS", "MUTUAL", "SIMILAR",
                "GROUP_FOLLOWING", "UNKNOWN", "SEARCH", null}) {
            assertEquals(HIDDEN, status("me", "me", type, false, false, true));
        }
    }

    @Test public void unknownOrConflictingMetadataNeverClaimsSomeoneDoesNotFollow() {
        assertEquals(HIDDEN, status("me", "me", "FOLLOWING", null, null, true));
        assertEquals(HIDDEN, status("me", "me", "FOLLOWING", true, false, true));
        assertEquals(HIDDEN, status("me", "me", "FOLLOWING", false, true, true));
    }

    @Test public void eitherNullableProjectionCanSupplyExplicitNegative() {
        assertEquals(DOES_NOT_FOLLOW, status("me", "me", "FOLLOWING", null, false, true));
        assertEquals(DOES_NOT_FOLLOW, status("me", "me", "FOLLOWING", false, null, null));
    }

    @Test public void accountSwitchAndMissingOwnershipCannotReuseLabels() {
        assertEquals(MUTUAL, status("first", "first", "FOLLOWING", true, true, true));
        assertEquals(HIDDEN, status("second", "first", "FOLLOWING", true, true, true));
        assertEquals(HIDDEN, status("second", null, "FOLLOWING", true, true, true));
        assertEquals(HIDDEN, status(null, null, "FOLLOWING", true, true, true));
        assertEquals(HIDDEN, status("", "", "FOLLOWING", true, true, true));
        assertEquals(HIDDEN, status("second", "second", "FOLLOWING", null, null, true));
    }

    @Test public void disabledSelfAndKnownUnfollowedRowsHaveNoLabel() {
        assertEquals(HIDDEN, FollowingFollowStatus.from(false, "me", "me", "FOLLOWING", "other", false, false, true));
        assertEquals(HIDDEN, FollowingFollowStatus.from(true, "me", "me", "FOLLOWING", "me", false, false, true));
        assertEquals(HIDDEN, FollowingFollowStatus.from(true, "me", "me", "FOLLOWING", null, false, false, true));
        assertEquals(HIDDEN, status("me", "me", "FOLLOWING", true, true, false));
    }

    private static ProfileFollowStatus.Status status(String viewer, String owner, String type,
                                                     Boolean direct, Boolean relationship, Boolean following) {
        return FollowingFollowStatus.from(true, viewer, owner, type, "other", direct, relationship, following);
    }
}
