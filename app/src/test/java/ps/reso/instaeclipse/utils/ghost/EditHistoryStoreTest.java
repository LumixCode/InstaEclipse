package ps.reso.instaeclipse.utils.ghost;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class EditHistoryStoreTest {

    private static final List<String> IDS = Arrays.asList("item1", "cc1");

    private static String texts(List<EditHistoryStore.Version> versions) {
        StringBuilder b = new StringBuilder();
        for (EditHistoryStore.Version v : versions) b.append(b.length() == 0 ? "" : ",").append(v.text);
        return b.toString();
    }

    @Test
    public void firstEditKeepsTheOriginalWithItsSendTime() {
        EditHistoryStore store = new EditHistoryStore(10, 5);
        assertTrue(store.recordEdit("acc", "t1", IDS, "hi", "hello", true, 1000, 5000));
        List<EditHistoryStore.Version> v = store.versions("acc", "t1", IDS);
        assertEquals("hi,hello", texts(v));
        assertTrue(v.get(0).original);
        assertEquals(1000, v.get(0).time);
        assertFalse(v.get(0).noticed);
        assertEquals(5000, v.get(1).time);
        assertTrue(v.get(1).noticed);
    }

    @Test
    public void replacedTextOfAnEditedMessageHasNoKnownTime() {
        EditHistoryStore store = new EditHistoryStore(10, 5);
        store.recordEdit("acc", "t1", IDS, "hi", "hello", false, 1000, 5000);
        EditHistoryStore.Version first = store.versions("acc", "t1", IDS).get(0);
        assertFalse(first.original);
        assertEquals(0, first.time);
    }

    @Test
    public void theSameEditSeenTwiceIsStoredOnceAndLearnsTheAlias() {
        EditHistoryStore store = new EditHistoryStore(10, 5);
        store.recordEdit("acc", "t1", Collections.singletonList("cc1"), "hi", "hello", true, 1000, 5000);
        assertTrue(store.recordEdit("acc", "t1", IDS, "hi", "hello", true, 1000, 6000)); // new alias only
        assertFalse(store.recordEdit("acc", "t1", IDS, "hi", "hello", true, 1000, 7000));
        assertEquals("hi,hello", texts(store.versions("acc", "t1", Collections.singletonList("item1"))));
    }

    @Test
    public void chainedEditsAppendAndAMissedOneLeavesAGap() {
        EditHistoryStore store = new EditHistoryStore(10, 9);
        store.recordEdit("acc", "t1", IDS, "a", "b", true, 1, 2);
        store.recordEdit("acc", "t1", IDS, "b", "c", false, 1, 3);
        store.recordEdit("acc", "t1", IDS, "x", "y", false, 1, 4); // "c" -> "x" was never seen
        List<EditHistoryStore.Version> v = store.versions("acc", "t1", IDS);
        assertEquals("a,b,c,x,y", texts(v));
        assertEquals(0, v.get(3).time);
        assertFalse(v.get(3).original);
    }

    @Test
    public void emptyOrUnchangedTextsAndMissingIdsAreIgnored() {
        EditHistoryStore store = new EditHistoryStore(10, 5);
        assertFalse(store.recordEdit("acc", "t1", IDS, "", "x", true, 1, 2));
        assertFalse(store.recordEdit("acc", "t1", IDS, "x", null, true, 1, 2));
        assertFalse(store.recordEdit("acc", "t1", IDS, "x", "x", true, 1, 2));
        assertFalse(store.recordEdit("acc", "t1", Arrays.asList("", null), "x", "y", true, 1, 2));
        assertEquals(0, store.size());
    }

    @Test
    public void anotherAccountOrThreadNeverMatches() {
        EditHistoryStore store = new EditHistoryStore(10, 5);
        store.recordEdit("acc", "t1", IDS, "hi", "hello", true, 1, 2);
        assertTrue(store.versions("other", "t1", IDS).isEmpty());
        assertTrue(store.versions("acc", "t2", IDS).isEmpty());
        assertEquals(2, store.versions("acc", null, IDS).size());
    }

    @Test
    public void anInPlaceEditWithoutAccountIsClaimedByTheTap() {
        EditHistoryStore store = new EditHistoryStore(10, 5);
        store.recordEdit(null, null, IDS, "hi", "hello", true, 1, 2);
        assertEquals(2, store.versions("acc", "t1", IDS).size());
        assertTrue(store.mergeServer("acc", "t1", IDS, Collections.emptyList(), false, 3));
        assertFalse(store.mergeServer("acc", "t1", IDS, Collections.emptyList(), false, 4));
        assertTrue(store.versions("other", "t1", IDS).isEmpty());
        assertTrue(store.versions("acc", "t2", IDS).isEmpty());
    }

    @Test
    public void leastRecentlyChangedMessageIsDroppedFirst() {
        EditHistoryStore store = new EditHistoryStore(2, 5);
        store.recordEdit("acc", "t1", Collections.singletonList("m1"), "a", "b", true, 1, 1);
        store.recordEdit("acc", "t1", Collections.singletonList("m2"), "a", "b", true, 1, 2);
        store.recordEdit("acc", "t1", Collections.singletonList("m1"), "b", "c", false, 1, 3); // m1 changed again
        store.recordEdit("acc", "t1", Collections.singletonList("m3"), "a", "b", true, 1, 4);
        assertEquals(2, store.size());
        assertTrue(store.versions("acc", "t1", Collections.singletonList("m2")).isEmpty());
        assertEquals("a,b,c", texts(store.versions("acc", "t1", Collections.singletonList("m1"))));
    }

    @Test
    public void versionBoundKeepsTheOriginal() {
        EditHistoryStore store = new EditHistoryStore(10, 3);
        store.recordEdit("acc", "t1", IDS, "hi", "a", true, 1, 2);
        store.recordEdit("acc", "t1", IDS, "a", "b", false, 1, 3);
        store.recordEdit("acc", "t1", IDS, "b", "c", false, 1, 4);
        List<EditHistoryStore.Version> v = store.versions("acc", "t1", IDS);
        assertEquals("hi,b,c", texts(v));
        assertTrue(v.get(0).original);
    }

    @Test
    public void longTextsAreCutAndStillMatch() {
        EditHistoryStore store = new EditHistoryStore(10, 5);
        char[] chars = new char[EditHistoryStore.MAX_CHARS + 50];
        Arrays.fill(chars, 'x');
        String longText = new String(chars);
        store.recordEdit("acc", "t1", IDS, "short", longText, true, 1, 2);
        assertEquals(EditHistoryStore.MAX_CHARS, store.versions("acc", "t1", IDS).get(1).text.length());
        assertFalse(store.recordEdit("acc", "t1", IDS, "short", longText, true, 1, 3)); // same edit again
    }

    @Test
    public void serverHistoryAloneCreatesTheEntry() {
        EditHistoryStore store = new EditHistoryStore(10, 5);
        List<EditHistoryStore.Version> server = Arrays.asList(
                new EditHistoryStore.Version("first", 10, false, false),
                new EditHistoryStore.Version("second", 20, false, false));
        assertTrue(store.mergeServer("acc", "t1", IDS, server, true, 99));
        List<EditHistoryStore.Version> v = store.versions("acc", "t1", IDS);
        assertEquals("first,second", texts(v));
        assertTrue(v.get(0).original);
        assertFalse(v.get(1).original);
        assertFalse(store.mergeServer("acc", "t1", IDS, server, true, 100)); // nothing new
    }

    @Test
    public void incompleteServerHistoryDoesNotClaimTheOriginal() {
        EditHistoryStore store = new EditHistoryStore(10, 5);
        store.mergeServer("acc", "t1", IDS,
                Collections.singletonList(new EditHistoryStore.Version("second", 20, false, false)), false, 99);
        assertFalse(store.versions("acc", "t1", IDS).get(0).original);
    }

    @Test
    public void serverHistoryFillsTheGapBeforeWhatWasSeenHere() {
        EditHistoryStore store = new EditHistoryStore(10, 5);
        store.recordEdit("acc", "t1", IDS, "second", "third", false, 1, 500); // saw only the 2nd edit
        List<EditHistoryStore.Version> server = Arrays.asList(
                new EditHistoryStore.Version("first", 10, false, false),
                new EditHistoryStore.Version("second", 20, false, false));
        assertTrue(store.mergeServer("acc", "t1", IDS, server, true, 600));
        List<EditHistoryStore.Version> v = store.versions("acc", "t1", IDS);
        assertEquals("first,second,third", texts(v));
        assertTrue(v.get(0).original);
        assertEquals(20, v.get(1).time); // Instagram's time replaces the unknown one
        assertEquals(500, v.get(2).time);
        assertTrue(v.get(2).noticed);
    }

    @Test
    public void aLocalVersionMissingFromTheServerGoesByItsTime() {
        List<EditHistoryStore.Version> merged = EditHistoryStore.merge(
                Arrays.asList(new EditHistoryStore.Version("a", 10, true, false),
                        new EditHistoryStore.Version("c", 30, false, false)),
                Collections.singletonList(new EditHistoryStore.Version("b", 20, false, true)));
        assertEquals("a,b,c", texts(merged));
    }
}
