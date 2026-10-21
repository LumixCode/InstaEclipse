package ps.reso.instaeclipse.mods.ghost;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class UnsentMediaIdentityTest {
    private static final class Identifier {
        final String server, client;
        Identifier(String server, String client) { this.server = server; this.client = client; }
    }
    private static class Message {
        final Identifier id;
        Message(String server, String client) { id = new Identifier(server, client); }
    }
    private static final class QuotedVoice extends Message {
        final Message quote = new Message("quoted", null);
        QuotedVoice() { super("primary", "client"); }
    }
    private static final class Portrait {
        final Message message = new Message("reel", "client-reel");
        final String mediaId = "unrelated-media-id";
    }
    private static final class Ambiguous {
        final Message first = new Message("first", null);
        final Message second = new Message("second", null);
    }

    @Test public void voiceUsesOwnIdentifierInsteadOfQuotedContent() throws Exception {
        List<String> ids = UnsentMediaIdentity.ids(new QuotedVoice(),
                UnsentMediaIdentity.path(QuotedVoice.class, Identifier.class));
        assertTrue(UnsentMediaIdentity.matches(List.of("primary"), ids));
        assertTrue(UnsentMediaIdentity.matches(List.of("client"), ids));
        assertFalse(UnsentMediaIdentity.matches(List.of("quoted"), ids));
    }

    @Test public void portraitUsesMessageIdentifierNotMediaIdentifier() throws Exception {
        List<String> ids = UnsentMediaIdentity.ids(new Portrait(),
                UnsentMediaIdentity.path(Portrait.class, Identifier.class));
        assertTrue(UnsentMediaIdentity.matches(List.of("reel"), ids));
        assertFalse(UnsentMediaIdentity.matches(List.of("unrelated-media-id"), ids));
    }

    @Test public void ambiguousNestedOwnersFailClosed() {
        assertNull(UnsentMediaIdentity.path(Ambiguous.class, Identifier.class));
    }

    @Test public void previewModelArgumentIsTheOnlyParameterCarryingAMessageIdentity() {
        // Mirrors a preview render(composer, modifier, ..., model, ..., ints) whose model index can drift.
        assertEquals(3, UnsentMediaIdentity.argument(new Class<?>[]{Object.class, Runnable.class, String.class,
                Portrait.class, int.class, boolean.class}, Identifier.class));
        assertEquals(0, UnsentMediaIdentity.argument(new Class<?>[]{Message.class, int.class}, Identifier.class));
    }

    @Test public void previewModelArgumentFailsClosedWhenAbsentOrAmbiguous() {
        assertEquals(-1, UnsentMediaIdentity.argument(new Class<?>[]{Object.class, int.class}, Identifier.class));
        assertEquals(-1, UnsentMediaIdentity.argument(new Class<?>[]{Portrait.class, Message.class}, Identifier.class));
        assertEquals(-1, UnsentMediaIdentity.argument(new Class<?>[]{Ambiguous.class}, Identifier.class));
    }

    @Test public void clearingAndRecyclingCannotMatchPreviousContent() {
        List<String> rendered = List.of("old-server", "old-client");
        assertTrue(UnsentMediaIdentity.matches(List.of("old-client"), rendered));
        assertFalse(UnsentMediaIdentity.matches(List.of(), rendered));
        assertFalse(UnsentMediaIdentity.matches(List.of("next-message"), rendered));
    }

    @Test public void pendingAliasStillMatchesAfterServerIdArrives() {
        List<String> bound = new ArrayList<>(List.of("client"));
        bound.add("server");
        assertTrue(UnsentMediaIdentity.matches(bound, List.of("server")));
        assertTrue(UnsentMediaIdentity.matches(bound, List.of("client")));
        assertFalse(UnsentMediaIdentity.matches(bound, List.of("other-server")));
    }

    @Test public void missingModelOrChangedSchemaNeverMatches() throws Exception {
        assertEquals(List.of(), UnsentMediaIdentity.ids(null,
                UnsentMediaIdentity.path(Message.class, Identifier.class)));
        assertEquals(List.of(), UnsentMediaIdentity.ids(new Object(),
                UnsentMediaIdentity.path(Message.class, Identifier.class)));
        assertNull(UnsentMediaIdentity.path(Object.class, Identifier.class));
        assertFalse(UnsentMediaIdentity.matches(List.of(""), List.of("")));
    }

    @Test public void laterRecompositionFindsExactlyOneBoundRowByAlias() {
        List<List<String>> rows = List.of(
                List.of("old-server", "old-client"),
                List.of("voice-server", "voice-client"),
                List.of("reel-server", "reel-client"));
        assertEquals(1, UnsentMediaIdentity.uniqueMatch(rows, List.of("voice-client")));
        assertEquals(2, UnsentMediaIdentity.uniqueMatch(rows, List.of("reel-server")));
        assertEquals(-1, UnsentMediaIdentity.uniqueMatch(rows, List.of("missing")));
    }

    @Test public void duplicateAliasesNeverSelectAnotherVisibleMessage() {
        List<List<String>> rows = List.of(List.of("shared"), List.of("shared"), List.of("other"));
        assertEquals(-1, UnsentMediaIdentity.uniqueMatch(rows, List.of("shared")));
        assertEquals(-1, UnsentMediaIdentity.uniqueMatch(rows, List.of()));
    }
}
