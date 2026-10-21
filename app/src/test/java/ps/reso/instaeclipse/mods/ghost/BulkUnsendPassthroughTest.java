package ps.reso.instaeclipse.mods.ghost;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

public class BulkUnsendPassthroughTest {
    /** Stands in for the removal's DirectThreadKey: never a String, so never compared. */
    private static final Object THREAD_KEY = new Object() {
        @Override public String toString() { return "mid.own"; }
    };

    private static Predicate<String> own(String... ids) {
        return Set.of(ids)::contains;
    }

    @Test public void matchesTheServerIdAndReturnsBothRemovalIds() {
        Object[] args = {THREAD_KEY, 3, "mid.own", "client-ctx", false};
        assertEquals(List.of("mid.own", "client-ctx"),
                KeepUnsentMessagesHook.bulkUnsendIds(args, own("mid.own")));
    }

    @Test public void matchesTheClientContextWhenTheServerIdIsMissing() {
        Object[] args = {THREAD_KEY, null, null, "client-ctx", true};
        assertEquals(List.of("client-ctx"),
                KeepUnsentMessagesHook.bulkUnsendIds(args, own("client-ctx")));
    }

    @Test public void ignoresTheThreadKeyAndNonStringArguments() {
        Object[] args = {THREAD_KEY, 123456789012345L, "mid.other", "", false};
        assertTrue(KeepUnsentMessagesHook.bulkUnsendIds(args, own("mid.own", "123456789012345")).isEmpty());
    }

    @Test public void unknownIdsAreCapturedAsUsual() {
        Object[] args = {THREAD_KEY, 1, "mid.received", "ctx.received", false};
        assertTrue(KeepUnsentMessagesHook.bulkUnsendIds(args, own("mid.own")).isEmpty());
        assertTrue(KeepUnsentMessagesHook.bulkUnsendIds(args, id -> false).isEmpty());
    }

    @Test public void emptyIdsNeverReachThePredicate() {
        Object[] args = {THREAD_KEY, 1, "", "mid.own", false};
        assertEquals(List.of("mid.own"), KeepUnsentMessagesHook.bulkUnsendIds(args, id -> {
            assertFalse("empty id tested", id.isEmpty());
            return id.equals("mid.own");
        }));
    }

    @Test public void noArgumentsMeansNoPassthrough() {
        assertTrue(KeepUnsentMessagesHook.bulkUnsendIds(null, own("mid.own")).isEmpty());
        assertTrue(KeepUnsentMessagesHook.bulkUnsendIds(new Object[0], own("mid.own")).isEmpty());
    }
}
