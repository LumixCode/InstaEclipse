package ps.reso.instaeclipse.mods.ghost;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import ps.reso.instaeclipse.utils.ghost.EditHistoryStore;

public class EditHistoryTimelineTest {

    private static EditHistoryStore.Version v(String text, long time, boolean original) {
        return new EditHistoryStore.Version(text, time, original, false);
    }

    @Test
    public void oldestFirstWithTheCurrentTextLast() {
        List<EditHistoryTimeline.Row> rows = EditHistoryTimeline.rows(
                Arrays.asList(v("hi", 1, true), v("hello", 2, false), v("hey", 3, false)), "hey");
        assertEquals(3, rows.size());
        assertEquals(EditHistoryTimeline.Kind.ORIGINAL, rows.get(0).kind);
        assertEquals(EditHistoryTimeline.Kind.EARLIER, rows.get(1).kind);
        assertEquals(EditHistoryTimeline.Kind.CURRENT, rows.get(2).kind);
        assertEquals(3, rows.get(2).time);
        assertEquals(2, EditHistoryTimeline.earlier(rows));
    }

    @Test
    public void aCurrentTextNotYetStoredFollowsWithUnknownTime() {
        List<EditHistoryTimeline.Row> rows = EditHistoryTimeline.rows(
                Arrays.asList(v("first", 10, true), v("second", 20, false)), "third");
        assertEquals(3, rows.size());
        assertEquals("second", rows.get(1).text);
        assertEquals(EditHistoryTimeline.Kind.EARLIER, rows.get(1).kind);
        assertEquals("third", rows.get(2).text);
        assertEquals(EditHistoryTimeline.Kind.CURRENT, rows.get(2).kind);
        assertEquals(0, rows.get(2).time);
    }

    @Test
    public void withoutTheMessageTheNewestStoredVersionIsCurrent() {
        List<EditHistoryTimeline.Row> rows = EditHistoryTimeline.rows(
                Arrays.asList(v("a", 1, true), v("b", 2, false)), null);
        assertEquals(2, rows.size());
        assertEquals(EditHistoryTimeline.Kind.CURRENT, rows.get(1).kind);
        assertEquals(1, EditHistoryTimeline.earlier(rows));
    }

    @Test
    public void nothingSavedLeavesNoEarlierVersion() {
        List<EditHistoryTimeline.Row> rows = EditHistoryTimeline.rows(Collections.emptyList(), "now");
        assertEquals(1, rows.size());
        assertEquals(0, EditHistoryTimeline.earlier(rows));
        assertEquals(0, EditHistoryTimeline.earlier(EditHistoryTimeline.rows(Collections.emptyList(), null)));
    }

    @Test
    public void aRevertedTextStaysAnEarlierVersionToo() {
        List<EditHistoryTimeline.Row> rows = EditHistoryTimeline.rows(
                Arrays.asList(v("a", 1, true), v("b", 2, false)), "a");
        assertEquals(3, rows.size());
        assertEquals(EditHistoryTimeline.Kind.ORIGINAL, rows.get(0).kind);
        assertEquals(EditHistoryTimeline.Kind.CURRENT, rows.get(2).kind);
    }

    @Test
    public void aCutStoredCopyShowsTheFullCurrentText() {
        char[] chars = new char[EditHistoryStore.MAX_CHARS + 10];
        Arrays.fill(chars, 'y');
        String full = new String(chars);
        List<EditHistoryTimeline.Row> rows = EditHistoryTimeline.rows(
                Arrays.asList(v("a", 1, true), v(EditHistoryStore.cut(full), 2, false)), full);
        assertEquals(2, rows.size());
        assertEquals(full, rows.get(1).text);
    }

    @Test
    public void missingCountsOnlyKnownEditsWithoutAVersion() {
        assertEquals(2, EditHistoryTimeline.missing(3, 1));
        assertEquals(0, EditHistoryTimeline.missing(1, 2));
        assertEquals(0, EditHistoryTimeline.missing(-1, 1));
    }

    @Test
    public void serverHistoryIsCompleteWhenItCoversEveryEdit() {
        assertTrue(EditHistoryTimeline.complete(2, 2));
        assertFalse(EditHistoryTimeline.complete(2, 1));
        assertFalse(EditHistoryTimeline.complete(0, 0));
        assertFalse(EditHistoryTimeline.complete(-1, 3));
    }

    @Test
    public void timestampsBecomeMilliseconds() {
        assertEquals(1_760_000_000_000L, EditHistoryTimeline.toMillis(1_760_000_000_000_000L));
        assertEquals(1_760_000_000_000L, EditHistoryTimeline.toMillis(1_760_000_000_000L));
        assertEquals(1_760_000_000_000L, EditHistoryTimeline.toMillis(1_760_000_000L));
        assertEquals(0, EditHistoryTimeline.toMillis(0));
        assertEquals(0, EditHistoryTimeline.toMillis(-5));
    }
}
