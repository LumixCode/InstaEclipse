package ps.reso.instaeclipse.mods.media;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** The restore merge rules of DataBackup: the unsent log by message, hidden chats and names by id. */
public class DataBackupMergeTest {

    private static DataBackup.UnsentMerge.Row body(String tag, long t, String account, String thread,
                                                   String id, String text, String... aliases) {
        return new DataBackup.UnsentMerge.Row(tag, t, account, thread, id, text, false, true, false, Arrays.asList(aliases));
    }

    private static DataBackup.UnsentMerge.Row placeholder(String tag, long t, String account, String thread,
                                                          String id, boolean logOnly, String... aliases) {
        return new DataBackup.UnsentMerge.Row(tag, t, account, thread, id,
                logOnly ? "[Unsent message]" : "[Retained message]", false, false, logOnly, Arrays.asList(aliases));
    }

    private static DataBackup.UnsentMerge.Row cleared(String tag, long t, String account, String thread,
                                                      String id, String... aliases) {
        return new DataBackup.UnsentMerge.Row(tag, t, account, thread, id, "", true, false, false, Arrays.asList(aliases));
    }

    private static List<DataBackup.UnsentMerge.Row> merge(List<DataBackup.UnsentMerge.Row> phone,
                                                         List<DataBackup.UnsentMerge.Row> backup) {
        return DataBackup.UnsentMerge.merge(phone, backup, 1000);
    }

    @Test public void clearedOnPhoneStaysClearedWhenTheBackupHasTheBody() {
        List<DataBackup.UnsentMerge.Row> out = merge(
                List.of(cleared("phone", 10, "A", "T", "m1", "m1", "c1")),
                List.of(body("backup", 10, "A", "T", "m1", "hello", "m1")));
        assertEquals(1, out.size());
        assertTrue(out.get(0).cleared);
        assertEquals("phone", out.get(0).source);
        assertEquals(Arrays.asList("m1", "c1"), new ArrayList<>(out.get(0).ids));
    }

    @Test public void clearedInTheBackupClearsThePhoneBody() {
        List<DataBackup.UnsentMerge.Row> out = merge(
                List.of(body("phone", 10, "A", "T", "m1", "hello", "m1")),
                List.of(cleared("backup", 10, "A", "T", "m1", "m1", "c1")));
        assertEquals(1, out.size());
        assertTrue(out.get(0).cleared);
        assertEquals("backup", out.get(0).source);
        assertFalse(out.get(0).logOnly);
    }

    @Test public void bodyReplacesAPlaceholderAndKeepsLogOnlyAndBothIds() {
        List<DataBackup.UnsentMerge.Row> out = merge(
                List.of(placeholder("phone", 10, "A", "T", "m1", true, "m1", "c1")),
                List.of(body("backup", 10, "A", "T", "m1", "hello", "m1", "s1")));
        assertEquals(1, out.size());
        assertEquals("backup", out.get(0).source);
        assertEquals("hello", out.get(0).text);
        assertTrue(out.get(0).logOnly);
        assertEquals(Arrays.asList("m1", "s1", "c1"), new ArrayList<>(out.get(0).ids));
    }

    @Test public void aMessageKeyedByAnotherAliasIsStillOneEntry() {
        List<DataBackup.UnsentMerge.Row> out = merge(
                List.of(body("phone", 10, "A", "T", "client", "hello", "client", "server")),
                List.of(cleared("backup", 10, "A", "T", "server", "server")));
        assertEquals(1, out.size());
        assertTrue(out.get(0).cleared);
        assertTrue(out.get(0).ids.containsAll(Arrays.asList("client", "server")));
    }

    @Test public void anAliasSharedByTwoMessagesJoinsNeither() {
        List<DataBackup.UnsentMerge.Row> out = merge(
                List.of(body("one", 10, "A", "T", "m1", "first", "m1", "x"),
                        body("two", 11, "A", "T", "m2", "second", "m2", "y")),
                List.of(body("backup", 12, "A", "T", "z", "third", "z", "x", "y")));
        assertEquals(3, out.size());
    }

    @Test public void equalRowsKeepThePhoneCopy() {
        List<DataBackup.UnsentMerge.Row> out = merge(
                List.of(body("phone", 10, "A", "T", "m1", "hello", "m1")),
                List.of(body("backup", 10, "A", "T", "m1", "hello", "m1")));
        assertEquals(1, out.size());
        assertEquals("phone", out.get(0).source);
    }

    @Test public void sameIdInAnotherAccountOrThreadIsAnotherMessage() {
        List<DataBackup.UnsentMerge.Row> out = merge(
                List.of(body("a", 10, "A", "T", "m1", "hello", "m1")),
                List.of(body("b", 11, "B", "T", "m1", "hello", "m1"),
                        body("c", 12, "A", "U", "m1", "hello", "m1")));
        assertEquals(3, out.size());
    }

    @Test public void idlessRowsFromOlderBuildsMatchOnTimeThreadAndText() {
        List<DataBackup.UnsentMerge.Row> out = merge(
                List.of(body("phone", 10, "", "T", "", "hello")),
                List.of(body("same", 10, "", "T", "", "hello"),
                        body("otherText", 10, "", "T", "", "bye"),
                        body("otherTime", 11, "", "T", "", "hello")));
        assertEquals(3, out.size());
        assertEquals("phone", out.get(0).source);
        assertTrue(out.get(0).ids.isEmpty());
    }

    @Test public void resultIsOldestFirstAndKeepsTheNewestWithinTheCap() {
        List<DataBackup.UnsentMerge.Row> phone = new ArrayList<>();
        List<DataBackup.UnsentMerge.Row> backup = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            DataBackup.UnsentMerge.Row row = body("r" + i, 100 - i, "A", "T", "m" + i, "text" + i, "m" + i);
            (i % 2 == 0 ? phone : backup).add(row);
        }
        List<DataBackup.UnsentMerge.Row> out = DataBackup.UnsentMerge.merge(phone, backup, 4);
        assertEquals(4, out.size());
        assertEquals(Arrays.asList("r3", "r2", "r1", "r0"),
                Arrays.asList(out.get(0).source, out.get(1).source, out.get(2).source, out.get(3).source));
    }

    @Test public void emptyOrMissingSidesAreFine() {
        assertTrue(DataBackup.UnsentMerge.merge(null, Collections.emptyList(), 1000).isEmpty());
        DataBackup.UnsentMerge.Row row = body("only", 1, "A", "T", "m1", "hello", "m1");
        assertSame("only", merge(Collections.emptyList(), List.of(row)).get(0).source);
    }

    @Test public void hiddenChatsAndNamesKeepEveryPhoneEntryAndItsValue() {
        Map<String, Object> phone = new LinkedHashMap<>();
        phone.put("t1", "Phone title");
        phone.put("t2", "");
        Map<String, Object> backup = new LinkedHashMap<>();
        backup.put("t1", "Old title");
        backup.put("t3", "Only in backup");
        Map<String, Object> out = DataBackup.unionKeepCurrent(backup, phone);
        assertEquals(Arrays.asList("t3", "t1", "t2"), new ArrayList<>(out.keySet()));
        assertEquals("Phone title", out.get("t1"));
        assertEquals("", out.get("t2"));
        assertEquals("Only in backup", out.get("t3"));
    }
}
