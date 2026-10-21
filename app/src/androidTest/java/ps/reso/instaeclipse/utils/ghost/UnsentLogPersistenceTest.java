package ps.reso.instaeclipse.utils.ghost;

import android.util.AtomicFile;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import org.junit.runner.RunWith;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class UnsentLogPersistenceTest {
    private final Map<Field, Object> originalFields = new LinkedHashMap<>();
    private List<UnsentLog.Entry> liveCache;
    private List<UnsentLog.Entry> originalCache;
    private AtomicFile storage;
    private File testDirectory;
    private final List<AtomicFile> temporaryFiles = new ArrayList<>();

    @SuppressWarnings("unchecked")
    @Before public void isolateLog() throws Exception {
        for (String name : List.of("storage", "loaded", "retryLoadAfter", "currentAccount", "deletions", "clearedIds", "revisionBase")) {
            Field field = field(name);
            originalFields.put(field, field.get(null));
        }
        liveCache = (List<UnsentLog.Entry>) field("cache").get(null);
        originalCache = new ArrayList<>(liveCache);
        File cacheDirectory = InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir();
        testDirectory = new File(cacheDirectory, "ie-unsent-tests-" + java.util.UUID.randomUUID());
        if (!testDirectory.mkdirs()) throw new IOException("Cannot create test directory: " + testDirectory);
        storage = temporaryFile();
        UnsentLog.write(storage, List.of(), new UnsentMessageIndex(1000), new UnsentMessageIndex(1000));
        field("storage").set(null, storage);
        field("currentAccount").set(null, null);
        field("revisionBase").setLong(null, 0);
        restartLog();
    }

    @After public void restoreLog() throws Exception {
        try {
            if (liveCache != null && originalCache != null) {
                liveCache.clear();
                liveCache.addAll(originalCache);
            }
            for (Map.Entry<Field, Object> entry : originalFields.entrySet()) entry.getKey().set(null, entry.getValue());
        } finally {
            for (AtomicFile file : temporaryFiles) file.delete();
            if (testDirectory != null && testDirectory.exists()) {
                assertTrue("Test directory must be empty after cleanup", testDirectory.delete());
            }
        }
    }

    @Test public void lateServerAliasSurvivesReloadWithoutAnyLiveRemovalState() throws Exception {
        AtomicFile file = temporaryFile();
        try {
            UnsentMessageIndex live = new UnsentMessageIndex(1000);
            live.recordAliases("alice", "chat", List.of("client"));
            List<UnsentLog.Entry> entries = List.of(new UnsentLog.Entry(123, "alice", "chat", "client", "", "hello"));
            UnsentLog.write(file, entries, live, new UnsentMessageIndex(1000));
            live.recordAliases("alice", "chat", List.of("client", "server"));
            UnsentLog.write(file, entries, live, new UnsentMessageIndex(1000));

            UnsentMessageIndex restarted = new UnsentMessageIndex(1000);
            List<UnsentLog.Entry> restored = UnsentLog.read(file, restarted, new UnsentMessageIndex(1000));
            assertEquals(1, restored.size());
            assertEquals("hello", restored.get(0).text);
            assertEquals("client", restarted.find("alice", "chat", List.of("server")).message);
            assertNull(restarted.find("bob", "chat", List.of("server")));
            assertNull(restarted.find("alice", "other-chat", List.of("server")));
        } finally { file.delete(); }
    }

    @Test public void interruptedReplacementKeepsThePreviousDeletionLog() throws Exception {
        AtomicFile file = temporaryFile();
        try {
            UnsentMessageIndex live = new UnsentMessageIndex(1000);
            live.record("alice", "chat", "server");
            UnsentLog.write(file, List.of(new UnsentLog.Entry(123, "alice", "chat", "server", "", "hello")), live, new UnsentMessageIndex(1000));
            // Model process death after truncating the pending write, before finishWrite.
            try (FileOutputStream incomplete = file.startWrite()) {
                incomplete.write("[{".getBytes(StandardCharsets.UTF_8));
            }
            UnsentMessageIndex restarted = new UnsentMessageIndex(1000);
            assertEquals("hello", UnsentLog.read(file, restarted, new UnsentMessageIndex(1000)).get(0).text);
            assertTrue(restarted.contains("alice", "chat", "server"));
        } finally { file.delete(); }
    }

    @Test public void previousJsonFormatKeepsAliasesAndDoesNotAssignLegacyEntriesAnAccount() throws Exception {
        AtomicFile file = temporaryFile();
        try {
            String json = "[{\"t\":1,\"a\":\"alice\",\"th\":\"chat\",\"id\":\"client\","
                    + "\"ids\":[\"client\",\"server\"],\"m\":\"hello\"},"
                    + "{\"t\":2,\"th\":\"chat\",\"id\":\"legacy\",\"m\":\"private\"}]";
            try (FileOutputStream out = new FileOutputStream(file.getBaseFile())) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            }
            UnsentMessageIndex restarted = new UnsentMessageIndex(1000);
            List<UnsentLog.Entry> entries = UnsentLog.read(file, restarted, new UnsentMessageIndex(1000));
            assertEquals(2, entries.size());
            assertTrue(entries.get(0).hasBody);
            assertFalse(entries.get(0).cleared);
            assertTrue(restarted.contains("alice", "chat", "server"));
            assertEquals("", entries.get(1).account);
            assertFalse(restarted.contains("alice", "chat", "legacy"));
            UnsentLog.write(file, entries, restarted, new UnsentMessageIndex(1000));
            assertEquals(2, UnsentLog.read(file, new UnsentMessageIndex(1000), new UnsentMessageIndex(1000)).size());
        } finally { file.delete(); }
    }

    @Test public void clearingThreadErasesContentButKeepsScopedAliasesAcrossRestart() throws Exception {
        UnsentLog.add("alice", "chat", List.of("client", "server"), "Alice secret sender", "Alice secret body");
        UnsentLog.add("alice", "other-chat", List.of("client", "server"), "Other sender", "Other body");
        UnsentLog.add("bob", "chat", List.of("client", "server"), "Bob sender", "Bob body");
        assertEquals("alice", UnsentLog.markCleared("alice", "chat"));
        assertNull(UnsentLog.findUnsent("alice", "chat", List.of("client", "server")));
        assertTrue(UnsentLog.wasUnsent("alice", "other-chat", "server"));
        assertTrue(UnsentLog.wasUnsent("bob", "chat", "server"));
        JSONArray json = new JSONArray(new String(storage.readFully(), StandardCharsets.UTF_8));
        JSONObject cleared = json.getJSONObject(0);
        assertTrue(cleared.getBoolean("cleared"));
        assertFalse(cleared.has("s"));
        assertFalse(cleared.has("m"));
        assertFalse(cleared.has("body"));
        assertEquals(3, json.length());
        assertEquals("Other body", json.getJSONObject(1).getString("m"));
        assertEquals("Bob body", json.getJSONObject(2).getString("m"));

        restartLog();
        assertNotNull(UnsentLog.findCleared("alice", "chat", List.of("server")));
        assertNull(UnsentLog.findUnsent("alice", "chat", List.of("server")));
        assertNull(UnsentLog.findCleared("bob", "chat", List.of("server")));
        assertNull(UnsentLog.findCleared("alice", "other-chat", List.of("server")));
        UnsentLog.Entry entry = liveCache.get(0);
        assertTrue(entry.cleared);
        assertFalse(entry.hasBody);
        assertEquals("", entry.sender);
        assertEquals("", entry.text);
        assertEquals(List.of(List.of("client", "server")), UnsentLog.getClearedIds("alice", "chat"));
        List<List<String>> snapshot = UnsentLog.getClearedIds("alice", "chat");
        snapshot.get(0).clear();
        assertEquals(List.of(List.of("client", "server")), UnsentLog.getClearedIds("alice", "chat"));
    }

    @Test public void repeatedCaptureAndLateAliasesCannotRestoreClearedMessageContent() throws Exception {
        UnsentLog.add("alice", "chat", List.of("client", "server"), "sender", "deleted content");
        UnsentLog.markCleared("alice", "chat");
        restartLog();
        UnsentLog.add("alice", "chat", List.of("server", "late-server"), "sender again", "restored content");
        UnsentLog.add("alice", "chat", "client", "sender again", "restored content");
        restartLog();
        assertNotNull(UnsentLog.findCleared("alice", "chat", List.of("late-server")));
        assertNull(UnsentLog.findUnsent("alice", "chat", List.of("client", "server", "late-server")));
        JSONArray json = new JSONArray(new String(storage.readFully(), StandardCharsets.UTF_8));
        assertEquals(1, json.length());
        assertFalse(json.getJSONObject(0).has("m"));
        assertFalse(json.getJSONObject(0).has("s"));
    }

    @Test public void clearingAccountLeavesOtherAccountsAndRejectsMissingIdentity() throws Exception {
        UnsentLog.add("alice", "chat", "same", "", "alice");
        UnsentLog.add("alice", "other-chat", "same", "", "alice elsewhere");
        UnsentLog.add("bob", "chat", "same", "", "bob");
        assertNull(UnsentLog.clear(null));
        assertNull(UnsentLog.clearThread(null, "chat"));
        assertNull(UnsentLog.markCleared("alice", ""));
        assertTrue(UnsentLog.wasUnsent("alice", "chat", "same"));
        assertEquals("alice", UnsentLog.markCleared("alice", null));
        restartLog();
        assertNotNull(UnsentLog.findCleared("alice", "chat", List.of("same")));
        assertNotNull(UnsentLog.findCleared("alice", "other-chat", List.of("same")));
        assertTrue(UnsentLog.wasUnsent("bob", "chat", "same"));
        assertNull(UnsentLog.findCleared(null, "chat", List.of("same")));
        assertNull(UnsentLog.findCleared("alice", null, List.of("same")));
        assertNull(UnsentLog.findCleared("alice", "", List.of("same")));
        assertTrue(UnsentLog.getClearedIds("alice", null).isEmpty());
        assertTrue(UnsentLog.getClearedIds(null, "chat").isEmpty());
        assertTrue(UnsentLog.getClearedIds("bob", "chat").isEmpty());
    }

    @Test public void logOnlyMarkerSurvivesRestartAndOnlyKeptMessagesNeedANativeClear() throws Exception {
        UnsentLog.add("alice", "log-chat", List.of("logged"), "", "logged body", true);
        UnsentLog.add("alice", "kept-chat", List.of("kept"), "", "kept body");
        assertFalse(UnsentLog.hasRetained("alice", "log-chat"));
        assertTrue(UnsentLog.hasRetained("alice", "kept-chat"));
        assertFalse(UnsentLog.hasRetained("bob", null));
        restartLog();
        assertFalse(UnsentLog.hasRetained("alice", "log-chat"));
        assertTrue(UnsentLog.hasRetained("alice", null));
        assertEquals("alice", UnsentLog.markCleared("alice", "kept-chat"));
        assertFalse(UnsentLog.hasRetained("alice", null));
    }

    @Test public void ambiguousAliasesDoNotResurrectOrMergeClearedMessages() throws Exception {
        UnsentLog.add("alice", "chat", "first", "", "first content");
        UnsentLog.add("alice", "chat", "second", "", "second content");
        UnsentLog.markCleared("alice", "chat");
        UnsentLog.add("alice", "chat", List.of("first", "second", "unknown"), "", "content");
        assertNull(UnsentLog.findUnsent("alice", "chat", List.of("first", "second", "unknown")));
        assertNull(UnsentLog.findCleared("alice", "chat", List.of("unknown")));
        assertEquals(List.of(List.of("first"), List.of("second")), UnsentLog.getClearedIds("alice", "chat"));
        UnsentLog.add("alice", "chat", "active", "", "active content");
        UnsentLog.add("alice", "chat", List.of("first", "active", "unknown"), "", "content");
        assertTrue(UnsentLog.wasUnsent("alice", "chat", "active"));
        assertNull(UnsentLog.findCleared("alice", "chat", List.of("active", "unknown")));
        assertEquals(List.of(List.of("first"), List.of("second")), UnsentLog.getClearedIds("alice", "chat"));
    }

    @Test public void failedClearLeavesTheFileContentIndexesAndRevisionUnchanged() throws Exception {
        UnsentLog.add("alice", "chat", List.of("client", "server"), "sender", "body");
        String before = new String(storage.readFully(), StandardCharsets.UTF_8);
        long revision = UnsentLog.revision();
        AtomicFile failing = new AtomicFile(storage.getBaseFile()) {
            @Override public FileOutputStream startWrite() throws IOException {
                throw new IOException("Storage unavailable");
            }
        };
        field("storage").set(null, failing);
        assertNull(UnsentLog.markCleared("alice", "chat"));
        assertEquals(revision, UnsentLog.revision());
        assertTrue(UnsentLog.wasUnsent("alice", "chat", "server"));
        assertNull(UnsentLog.findCleared("alice", "chat", List.of("server")));
        assertEquals("body", liveCache.get(0).text);
        assertEquals("sender", liveCache.get(0).sender);
        assertEquals(before, new String(storage.readFully(), StandardCharsets.UTF_8));
        field("storage").set(null, storage);
        assertEquals("alice", UnsentLog.markCleared("alice", "chat"));
        assertTrue(UnsentLog.revision() > revision);
    }

    @Test public void successfulReloadAndClearAlwaysAdvanceTheObservedRevision() throws Exception {
        UnsentLog.add("alice", "chat", "first", "", "first");
        UnsentLog.add("alice", "chat", "second", "", "second");
        long beforeReload = UnsentLog.revision();
        field("loaded").setBoolean(null, false);
        long afterReload = UnsentLog.revision();
        assertTrue(afterReload > beforeReload);
        UnsentLog.markCleared("alice", "chat");
        long afterClear = UnsentLog.revision();
        assertTrue(afterClear > afterReload);
        field("loaded").setBoolean(null, false);
        assertTrue(UnsentLog.revision() > afterClear);
        assertNull(UnsentLog.findUnsent("alice", "chat", List.of("first", "second")));
        assertEquals(2, UnsentLog.getClearedIds("alice", "chat").size());
    }

    @Test public void combinedCacheEvictionDropsTheCorrespondingActiveAndClearedIndexes() throws Exception {
        UnsentMessageIndex index = new UnsentMessageIndex(1000);
        List<UnsentLog.Entry> entries = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            String thread = i == 1 ? "clear-chat" : "chat";
            String id = "message-" + i;
            index.recordAliases("alice", thread, List.of(id, "alias-" + i));
            entries.add(new UnsentLog.Entry(i, "alice", thread, id, "", "body"));
        }
        UnsentLog.write(storage, entries, index, new UnsentMessageIndex(1000));
        UnsentLog.markCleared("alice", "clear-chat");
        assertNotNull(UnsentLog.findCleared("alice", "clear-chat", List.of("alias-1")));
        UnsentLog.add("alice", "chat", "new-1", "", "new body");
        assertFalse(UnsentLog.wasUnsent("alice", "chat", "alias-0"));
        assertNotNull(UnsentLog.findCleared("alice", "clear-chat", List.of("alias-1")));
        UnsentLog.add("alice", "chat", "new-2", "", "new body");
        assertNull(UnsentLog.findCleared("alice", "clear-chat", List.of("alias-1")));
        restartLog();
        assertFalse(UnsentLog.wasUnsent("alice", "chat", "alias-0"));
        assertNull(UnsentLog.findCleared("alice", "clear-chat", List.of("alias-1")));
        assertTrue(UnsentLog.wasUnsent("alice", "chat", "alias-2"));
        assertEquals(1000, liveCache.size());
    }

    @Test public void malformedReloadDoesNotPublishPartialIndexesOrOverwriteTheFile() throws Exception {
        UnsentLog.add("alice", "chat", "active", "", "body");
        UnsentLog.add("alice", "cleared-chat", "cleared", "", "deleted body");
        UnsentLog.markCleared("alice", "cleared-chat");
        Object activeBefore = field("deletions").get(null);
        Object clearedBefore = field("clearedIds").get(null);
        String malformed = "[{\"a\":\"alice\",\"th\":\"chat\",\"id\":\"partial\"},42]";
        try (FileOutputStream out = new FileOutputStream(storage.getBaseFile())) {
            out.write(malformed.getBytes(StandardCharsets.UTF_8));
        }
        field("loaded").setBoolean(null, false);
        UnsentLog.add("alice", "chat", "new", "", "new body");
        assertSame(activeBefore, field("deletions").get(null));
        assertSame(clearedBefore, field("clearedIds").get(null));
        assertEquals(2, liveCache.size());
        assertFalse(((UnsentMessageIndex) activeBefore).contains("alice", "chat", "partial"));
        assertNull(UnsentLog.markCleared("alice", "chat"));
        assertEquals(malformed, new String(storage.readFully(), StandardCharsets.UTF_8));
    }

    private void restartLog() throws Exception {
        liveCache.clear();
        field("loaded").setBoolean(null, false);
        field("retryLoadAfter").setLong(null, 0);
        field("deletions").set(null, new UnsentMessageIndex(1000));
        field("clearedIds").set(null, new UnsentMessageIndex(1000));
    }

    private static Field field(String name) throws Exception {
        Field field = UnsentLog.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private AtomicFile temporaryFile() throws Exception {
        AtomicFile file = new AtomicFile(File.createTempFile("unsent-restart-", ".json", testDirectory));
        temporaryFiles.add(file);
        return file;
    }
}
