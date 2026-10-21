package ps.reso.instaeclipse.utils.ghost;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class UnsentMessageIndexTest {
    @Test public void serverAndClientIdsMatchOneCanonicalDeletion() {
        UnsentMessageIndex index = new UnsentMessageIndex(10);
        UnsentMessageIndex.Key key = index.recordAliases("alice", "chat", List.of("server", "client"));
        assertEquals("server", key.message);
        assertEquals(key, index.find("alice", "chat", List.of("server", "client")));
        assertEquals(key, index.find("alice", "chat", List.of("client")));
        assertEquals(key, index.find("alice", null, List.of("server", "client")));
        assertTrue(index.contains("alice", "chat", "client"));
        assertFalse(index.record("alice", "chat", "client"));
    }

    @Test public void learningServerIdKeepsExistingClientCanonicalKey() {
        UnsentMessageIndex index = new UnsentMessageIndex(10);
        UnsentMessageIndex.Key key = index.recordAliases("alice", "chat", List.of("client"));
        long revision = index.revision();
        assertEquals(key, index.recordAliases("alice", "chat", List.of("server", "client")));
        assertEquals(revision + 1, index.revision());
        assertEquals(key, index.find("alice", "chat", List.of("server")));
        assertEquals(List.of("client", "server"), index.aliases(key));
    }

    @Test public void ambiguousAliasPairDoesNotMergeDistinctDeletions() {
        UnsentMessageIndex index = new UnsentMessageIndex(10);
        UnsentMessageIndex.Key first = index.recordAliases("alice", "chat", List.of("first"));
        UnsentMessageIndex.Key second = index.recordAliases("alice", "chat", List.of("second"));
        long revision = index.revision();
        assertNull(index.recordAliases("alice", "chat", List.of("first", "second", "unknown")));
        assertNull(index.find("alice", "chat", List.of("first", "second")));
        assertEquals(List.of("first"), index.aliases(first));
        assertEquals(List.of("second"), index.aliases(second));
        assertFalse(index.contains("alice", "chat", "unknown"));
        assertEquals(revision, index.revision());
    }

    @Test public void aliasesStayWithinTheirAccountAndThread() {
        UnsentMessageIndex index = new UnsentMessageIndex(10);
        UnsentMessageIndex.Key alice = index.recordAliases("alice", "chat-1", List.of("server", "client"));
        UnsentMessageIndex.Key bob = index.recordAliases("bob", "chat-1", List.of("server", "bob-client"));
        UnsentMessageIndex.Key otherThread = index.recordAliases("alice", "chat-2", List.of("server", "other-client"));
        assertEquals(alice, index.find("alice", "chat-1", List.of("server")));
        assertEquals(bob, index.find("bob", "chat-1", List.of("server")));
        assertEquals(otherThread, index.find("alice", "chat-2", List.of("server")));
        assertNull(index.find("alice", null, List.of("server")));
        assertNull(index.find("bob", "chat-1", List.of("client")));
        assertNull(index.find("alice", "chat-2", List.of("client")));
    }

    @Test public void capacityCountsDeletionsAndEvictsEveryAliasTogether() {
        UnsentMessageIndex index = new UnsentMessageIndex(2);
        UnsentMessageIndex.Key first = index.recordAliases("alice", "chat", List.of("s1", "c1"));
        index.recordAliases("alice", "chat", List.of("s2", "c2"));
        index.recordAliases("alice", "chat", List.of("s2", "c2", "alternate2"));
        assertTrue(index.contains("alice", "chat", "s1"));
        index.recordAliases("alice", "chat", List.of("s3", "c3"));
        assertFalse(index.contains("alice", "chat", "s1"));
        assertFalse(index.contains("alice", "chat", "c1"));
        assertTrue(index.aliases(first).isEmpty());
        assertTrue(index.contains("alice", "chat", "alternate2"));
    }

    @Test public void aliasSnapshotCannotMutateTheIndex() {
        UnsentMessageIndex index = new UnsentMessageIndex(10);
        UnsentMessageIndex.Key key = index.recordAliases("alice", "chat", List.of("server", "client"));
        List<String> snapshot = index.aliases(key);
        snapshot.clear();
        snapshot.add("foreign");
        assertEquals(List.of("server", "client"), index.aliases(key));
        assertFalse(index.contains("alice", "chat", "foreign"));
    }

    @Test public void emptyAliasesOrMissingOwnerCannotCreateADeletion() {
        UnsentMessageIndex index = new UnsentMessageIndex(10);
        assertNull(index.recordAliases(null, "chat", List.of("client")));
        assertNull(index.recordAliases("alice", "", List.of("client")));
        assertNull(index.recordAliases("alice", "chat", null));
        assertNull(index.recordAliases("alice", "chat", List.of()));
        assertNull(index.recordAliases("alice", "chat", java.util.Arrays.asList(null, "")));
        assertTrue(index.isEmpty());
        assertEquals(0, index.revision());
        assertNotNull(index.recordAliases("alice", "chat", java.util.Arrays.asList(null, "", "client")));
    }

    @Test public void duplicateAliasesDoNotChangeRevision() {
        UnsentMessageIndex index = new UnsentMessageIndex(10);
        UnsentMessageIndex.Key key = index.recordAliases("alice", "chat", List.of("server", "client"));
        long revision = index.revision();
        assertEquals(key, index.recordAliases("alice", "chat", List.of("client", "server", "client")));
        assertFalse(index.record("alice", "chat", "client"));
        assertEquals(revision, index.revision());
    }

    @Test public void clearingAThreadRemovesItsAliasesOnly() {
        UnsentMessageIndex index = new UnsentMessageIndex(10);
        UnsentMessageIndex.Key first = index.recordAliases("alice", "chat-1", List.of("server", "client"));
        index.recordAliases("alice", "chat-2", List.of("server", "client"));
        index.clear("alice", "chat-1");
        assertNull(index.find("alice", "chat-1", List.of("server", "client")));
        assertTrue(index.aliases(first).isEmpty());
        assertEquals("chat-2", index.find("alice", null, List.of("client")).thread);
    }

    @Test public void removingCanonicalKeyDropsAllAliasesWithoutTouchingOtherScopes() {
        UnsentMessageIndex index = new UnsentMessageIndex(10);
        UnsentMessageIndex.Key removed = index.recordAliases("alice", "chat", List.of("client", "server"));
        index.recordAliases("alice", "other-chat", List.of("client", "server"));
        index.recordAliases("bob", "chat", List.of("client", "server"));
        long revision = index.revision();
        assertTrue(index.remove(removed));
        assertEquals(revision + 1, index.revision());
        assertNull(index.find("alice", "chat", List.of("client", "server")));
        assertTrue(index.aliases(removed).isEmpty());
        assertTrue(index.contains("alice", "other-chat", "server"));
        assertTrue(index.contains("bob", "chat", "server"));
    }

    @Test public void removalRequiresTheCanonicalKeyAndDoesNotChangeRevisionForMissingKeys() {
        UnsentMessageIndex index = new UnsentMessageIndex(10);
        UnsentMessageIndex.Key canonical = index.recordAliases("alice", "chat", List.of("client", "server"));
        long revision = index.revision();
        assertFalse(index.remove(new UnsentMessageIndex.Key("alice", "chat", "server")));
        assertFalse(index.remove(new UnsentMessageIndex.Key("alice", "chat", "missing")));
        assertFalse(index.remove(new UnsentMessageIndex.Key(null, "chat", "client")));
        assertFalse(index.remove(null));
        assertEquals(revision, index.revision());
        assertEquals(canonical, index.find("alice", "chat", List.of("server")));
        assertTrue(index.remove(canonical));
        assertFalse(index.remove(canonical));
        assertEquals(revision + 1, index.revision());
    }

    @Test public void receivingOrDisplayingAnIdDoesNotMarkItUnsent() {
        UnsentMessageIndex index = new UnsentMessageIndex(10);
        assertNull(index.find("alice", "chat", List.of("message")));
        assertFalse(index.contains("alice", "chat", "message"));
        index.record("alice", "chat", "message");
        assertNotNull(index.find("alice", "chat", List.of("message")));
    }

    @Test public void sameIdInOtherAccountOrThreadIsNotMarked() {
        UnsentMessageIndex index = new UnsentMessageIndex(10);
        index.record("alice", "chat-1", "message");
        assertNull(index.find("bob", "chat-1", List.of("message")));
        assertNull(index.find("alice", "chat-2", List.of("message")));
    }

    @Test public void missingIdentityIsNeverRecorded() {
        UnsentMessageIndex index = new UnsentMessageIndex(10);
        assertFalse(index.record(null, "chat", "message"));
        assertFalse(index.record("alice", "", "message"));
        assertFalse(index.record("alice", "chat", ""));
        assertTrue(index.isEmpty());
    }

    @Test public void missingThreadNeedsUniqueAccountScopedDeletion() {
        UnsentMessageIndex index = new UnsentMessageIndex(10);
        index.record("alice", "chat-1", "message");
        assertEquals("chat-1", index.find("alice", null, List.of("message")).thread);
        index.record("alice", "chat-2", "message");
        assertNull(index.find("alice", null, List.of("message")));
    }

    @Test public void repeatedEventsDoNotEvictAnOlderDistinctMessage() {
        UnsentMessageIndex index = new UnsentMessageIndex(2);
        index.record("alice", "chat", "first");
        index.record("alice", "chat", "second");
        assertFalse(index.record("alice", "chat", "second"));
        assertTrue(index.contains("alice", "chat", "first"));
        index.record("alice", "chat", "third");
        assertFalse(index.contains("alice", "chat", "first"));
        assertTrue(index.contains("alice", "chat", "third"));
    }

    @Test public void clearingThreadAndAccountDoNotTouchOtherOwners() {
        UnsentMessageIndex index = new UnsentMessageIndex(10);
        index.record("alice", "chat-1", "message");
        index.record("alice", "chat-2", "message");
        index.record("bob", "chat-1", "message");
        index.clear("alice", "chat-1");
        assertFalse(index.contains("alice", "chat-1", "message"));
        assertTrue(index.contains("alice", "chat-2", "message"));
        index.clear("alice", null);
        assertFalse(index.contains("alice", "chat-2", "message"));
        assertTrue(index.contains("bob", "chat-1", "message"));
    }

    @Test public void revisionChangesOnlyWhenDeletionKnowledgeChanges() {
        UnsentMessageIndex index = new UnsentMessageIndex(10);
        index.record("alice", "chat", "message");
        long revision = index.revision();
        index.record("alice", "chat", "message");
        index.clear("bob", null);
        assertEquals(revision, index.revision());
        index.clear("alice", null);
        assertTrue(index.revision() > revision);
    }
}
