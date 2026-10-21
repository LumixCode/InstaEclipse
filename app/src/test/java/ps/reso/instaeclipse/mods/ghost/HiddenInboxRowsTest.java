package ps.reso.instaeclipse.mods.ghost;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

public class HiddenInboxRowsTest {
    private record Row(String id, String title) {}

    private final HiddenInboxRows<Row> inbox = new HiddenInboxRows<>();
    private final Set<String> hidden = new HashSet<>(Arrays.asList("a", "b", "c"));
    private final List<Row> source = Arrays.asList(
            new Row("a", "Instagram user"), new Row("normal", "Visible"),
            new Row("b", "Instagram user"), new Row("c", "Instagram user"));

    private List<String> ids(boolean hiddenOnly) {
        return inbox.visible(hidden, true, hiddenOnly, Row::id).stream()
                .map(Row::id).collect(Collectors.toList());
    }

    @Test public void allThreeHiddenChatsSurviveRepeatedRoundTripsWithDuplicateNames() {
        inbox.replace(source);
        for (int i = 0; i < 20; i++) {
            assertEquals(Collections.singletonList("normal"), ids(false));
            assertEquals(Arrays.asList("a", "b", "c"), ids(true));
        }
        assertEquals(4, source.size());
    }

    @Test public void reusedNativeListReplacesTheSnapshotOnEverySubmission() {
        List<Row> reused = new ArrayList<>(source.subList(0, 3));
        inbox.replace(reused);
        assertEquals(Arrays.asList("a", "b"), ids(true));
        reused.add(source.get(3));
        inbox.replace(reused);
        assertEquals(Arrays.asList("a", "b", "c"), ids(true));
    }

    @Test public void newSubmissionUpdatesOrderAndRemovesDeletedRows() {
        inbox.replace(source);
        ids(true);
        inbox.replace(Arrays.asList(source.get(3), source.get(1), source.get(0)));
        assertEquals(Arrays.asList("c", "a"), ids(true));
        assertEquals(Collections.singletonList("normal"), ids(false));
    }

    @Test public void emptySubmissionClearsStaleRowsInBothModes() {
        inbox.replace(source);
        inbox.replace(Collections.emptyList());
        assertTrue(ids(true).isEmpty());
        assertTrue(ids(false).isEmpty());
    }

    @Test public void changesToNativeOrDisplayedListCannotDestroyTheSnapshot() {
        List<Row> nativeRows = new ArrayList<>(source);
        inbox.replace(nativeRows);
        nativeRows.clear();
        inbox.visible(hidden, true, true, Row::id).clear();
        inbox.visible(hidden, true, false, Row::id).clear();
        assertEquals(Arrays.asList("a", "b", "c"), ids(true));
        assertEquals(Collections.singletonList("normal"), ids(false));
    }

    @Test public void hideAndUnhideUseCurrentSelectionWithoutAResubmission() {
        inbox.replace(source);
        hidden.remove("b");
        assertEquals(Arrays.asList("a", "c"), ids(true));
        assertEquals(Arrays.asList("normal", "b"), ids(false));
        hidden.add("normal");
        assertEquals(Arrays.asList("a", "normal", "c"), ids(true));
        assertEquals(Collections.singletonList("b"), ids(false));
    }

    @Test public void featureOffRestoresEveryRowEvenAfterHiddenMode() {
        inbox.replace(source);
        ids(true);
        List<Row> disabled = inbox.visible(hidden, false, true, Row::id);
        assertEquals(source, disabled);
        disabled.clear();
        assertEquals(source, inbox.visible(hidden, false, false, Row::id));
    }

    @Test public void differentAccountOrFolderDoesNotReplaceAnotherInbox() {
        inbox.replace(source);
        HiddenInboxRows<Row> other = new HiddenInboxRows<>();
        for (int i = 0; i < 10; i++) {
            other.replace(Collections.singletonList(new Row("other", "Other inbox")));
            other.visible(hidden, true, i % 2 == 0, Row::id);
        }
        assertEquals(Arrays.asList("a", "b", "c"), ids(true));
        assertEquals(Collections.singletonList("normal"), ids(false));
    }

    @Test public void unknownIdentityIsNotShownInAuthenticatedHiddenView() {
        Row unknown = new Row(null, "Instagram user");
        inbox.replace(Arrays.asList(source.get(0), unknown));
        assertEquals(Collections.singletonList("a"), ids(true));
        assertEquals(Collections.singletonList(unknown), inbox.visible(hidden, true, false, Row::id));
    }

    @Test public void fallbackCanFilterImmutableInputWithoutChangingIt() {
        List<Row> immutable = Collections.unmodifiableList(source);
        assertEquals(Collections.singletonList(source.get(1)),
                HiddenInboxRows.select(immutable, hidden, false, Row::id));
        assertEquals(4, immutable.size());
    }
}
