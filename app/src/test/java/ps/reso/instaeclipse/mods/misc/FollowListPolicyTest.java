package ps.reso.instaeclipse.mods.misc;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class FollowListPolicyTest {

    @Test public void duplicateRowsAreCountedOnceAndReported() {
        // Instagram appended an overlapping page: 6 rows, 4 accounts
        FollowListPolicy.Counts c = FollowListPolicy.count(Arrays.asList("1", "2", "3", "2", "3", "4"));
        assertEquals(6, c.rows);
        assertEquals(4, c.unique);
        assertEquals(2, c.duplicates);
        assertEquals(0, c.withoutId);
        assertEquals(Arrays.asList("1", "2", "3", "4"), c.ids);
    }

    @Test public void rowsWithoutAnIdAreCountedNotKept() {
        FollowListPolicy.Counts c = FollowListPolicy.count(Arrays.asList("1", null, "", "2"));
        assertEquals(4, c.rows);
        assertEquals(2, c.unique);
        assertEquals(2, c.withoutId);
        assertEquals(Arrays.asList("1", "2"), c.ids);
    }

    @Test public void theListThatGrewIsTheMainList() {
        // a 30-account side list vs the main list that got 3 pages appended (25 accounts so far)
        assertEquals(1, FollowListPolicy.mainList(new int[]{30, 25}, new int[]{0, 3}));
    }

    @Test public void withoutGrowthTheLargestListIsTheMainList() {
        assertEquals(0, FollowListPolicy.mainList(new int[]{40, 3}, new int[]{0, 0}));
        assertEquals(1, FollowListPolicy.mainList(new int[]{3, 40}, null));
        assertEquals(-1, FollowListPolicy.mainList(new int[0], new int[0]));
    }

    @Test public void anEndedListWithAccountsIsComplete() {
        assertEquals(FollowListPolicy.End.COMPLETE, FollowListPolicy.atEnd(450, 600, 30_000));
        assertEquals(FollowListPolicy.End.COMPLETE, FollowListPolicy.atEnd(12, -1, 0));
    }

    @Test public void anEmptyListIsOnlyCompleteWhenInstagramCountsZero() {
        assertEquals(FollowListPolicy.End.COMPLETE, FollowListPolicy.atEnd(0, 0, 0));
        assertEquals(FollowListPolicy.End.WAIT_FIRST_PAGE, FollowListPolicy.atEnd(0, 600, 1_000));
        assertEquals(FollowListPolicy.End.WAIT_FIRST_PAGE, FollowListPolicy.atEnd(0, -1, 1_000));
        // a counted list keeps waiting (its failed first page is retried); an uncounted one gives up
        assertEquals(FollowListPolicy.End.WAIT_FIRST_PAGE, FollowListPolicy.atEnd(0, 600, FollowListPolicy.FIRST_PAGE_MS));
        assertEquals(FollowListPolicy.End.INCOMPLETE, FollowListPolicy.atEnd(0, -1, FollowListPolicy.FIRST_PAGE_MS));
    }

    @Test public void retriesBackOffThenGiveUp() {
        long previous = 0;
        int attempt = 1;
        for (long d = FollowListPolicy.retryDelayMs(attempt); d > 0; d = FollowListPolicy.retryDelayMs(++attempt)) {
            assertTrue(d > previous);
            previous = d;
        }
        assertTrue(attempt > 3);
        assertEquals(-1, FollowListPolicy.retryDelayMs(0));
    }

    @Test public void shortOfExpectedOnlyWithAKnownCount() {
        assertTrue(FollowListPolicy.shortOfExpected(450, 600));
        assertFalse(FollowListPolicy.shortOfExpected(600, 600));
        assertFalse(FollowListPolicy.shortOfExpected(450, -1));
        assertFalse(FollowListPolicy.shortOfExpected(0, 0));
    }

    @Test public void aClearlyShortListStartsExtraPasses() {
        // a quarter of a 600-account list missing in the default order (repeats not counted then)
        assertTrue(FollowListPolicy.startExtraPasses(true, false, 450, 600, -1, -1));
        // a first pass that received every row but repeated a fifth of them
        assertTrue(FollowListPolicy.startExtraPasses(true, false, 480, 600, 600, 120));
    }

    @Test public void repeatsStartExtraPassesEvenWhenFewAreMissing() {
        // 2 repeats in 1000 rows: max(2, 1000 / 500) = 2, below the 5% rule
        assertTrue(FollowListPolicy.startExtraPasses(true, false, 998, 1000, 1000, 2));
        assertFalse(FollowListPolicy.startExtraPasses(true, false, 999, 1000, 1000, 1));
        // a large list needs received / 500 repeats
        assertFalse(FollowListPolicy.startExtraPasses(true, false, 3497, 3500, 3500, 3));
        assertTrue(FollowListPolicy.startExtraPasses(true, false, 3493, 3500, 3500, 7));
    }

    @Test public void noExtraPassesForAFewMissingAnUnfinishedOrAnUncountedList() {
        // one pass without repeats, a few short of the count
        assertFalse(FollowListPolicy.startExtraPasses(true, false, 1997, 2000, 1997, 0));
        // well under 5% missing (e.g. deactivated accounts) isn't worth listing the list again
        assertFalse(FollowListPolicy.startExtraPasses(true, false, 1990, 2000, -1, -1));
        assertFalse(FollowListPolicy.startExtraPasses(false, false, 300, 600, 600, 200));
        assertFalse(FollowListPolicy.startExtraPasses(true, false, 450, -1, -1, -1));
        assertFalse(FollowListPolicy.startExtraPasses(true, false, 600, 600, 600, 0));
        // exactly 5% missing still runs
        assertTrue(FollowListPolicy.startExtraPasses(true, false, 95, 100, -1, -1));
        assertFalse(FollowListPolicy.startExtraPasses(true, false, 96, 100, -1, -1));
    }

    @Test public void aLimitedListGetsNoExtraPasses() {
        // a capped list of ~50 of thousands: listing it again finds nothing, by design
        assertFalse(FollowListPolicy.startExtraPasses(true, true, 50, 120_000, 50, 0));
        assertFalse(FollowListPolicy.startExtraPasses(true, true, 480, 600, 600, 120));
    }

    @Test public void theRowsAPassReceivedRaiseTheTarget() {
        // Instagram's count lags by one: every pass received one row more than it counts
        assertEquals(600, FollowListPolicy.target(599, 600));
        assertEquals(600, FollowListPolicy.target(600, 500));
        assertEquals(600, FollowListPolicy.target(600, -1));
        assertEquals(-1, FollowListPolicy.target(-1, 0));
        assertEquals(500, FollowListPolicy.target(-1, 500));
        // listed all the count says, but one more row came: still short
        assertTrue(FollowListPolicy.anotherPass(2, 7, 599, FollowListPolicy.target(599, 600), 3, false, 600));
    }

    @Test public void theRowBudgetBoundsThePasses() {
        // 1120 rows: 7 passes are 7840 rows, within 8000
        assertTrue(FollowListPolicy.withinRowBudget(6, 1120));
        assertTrue(FollowListPolicy.anotherPass(6, 7, 1115, 1120, 2, false, 1120));
        // 1200 rows: a 7th pass would be 8400
        assertFalse(FollowListPolicy.anotherPass(6, 7, 1190, 1200, 2, false, 1200));
        // a 4500-row list gets no extra pass at all (2 passes are 9000 rows)
        assertFalse(FollowListPolicy.startExtraPasses(true, false, 3600, 4500, 4500, 900));
        assertTrue(FollowListPolicy.startExtraPasses(true, false, 3200, 4000, 4000, 800));
    }

    @Test public void extraPassesRelistTheSameOrderOnlyOnSomeoneElsesListWithCachesAndRefresh() {
        assertTrue(FollowListPolicy.relistPasses(false, true, true));
        // your own list: Instagram honours the order there, so the orders keep cycling
        assertFalse(FollowListPolicy.relistPasses(true, true, true));
        // no page caches: the same order would only show its cached pages again
        assertFalse(FollowListPolicy.relistPasses(false, false, true));
        // no refresh flag: the same order would come from Instagram's 1-day response cache
        assertFalse(FollowListPolicy.relistPasses(false, true, false));
    }

    @Test public void aRelistThatFindsFarFewerThanPredictedSwitchesOrder() {
        // pass 2: 120 of 600 still unlisted, the pass listed 500: 100 predicted, 85 found
        double predicted = FollowListPolicy.predictedNew(600, 480, 500);
        assertEquals(100.0, predicted, 0.1);
        assertFalse(FollowListPolicy.relistFellShort(predicted, 85));
        assertTrue(FollowListPolicy.relistFellShort(predicted, 40));
        // late passes predict little: no switch below 10 predicted
        double late = FollowListPolicy.predictedNew(600, 594, 500);
        assertTrue(late < FollowListPolicy.RELIST_MIN_PREDICTED);
        assertFalse(FollowListPolicy.relistFellShort(late, 0));
        assertEquals(0, FollowListPolicy.predictedNew(600, 600, 500), 0);
        assertEquals(0, FollowListPolicy.predictedNew(-1, 480, 500), 0);
        // a pass that fell short goes on in another order even when it added nothing
        assertTrue(FollowListPolicy.anotherPass(2, 7, 480, 600, 0, true, 600));
        assertFalse(FollowListPolicy.anotherPass(2, 7, 480, 600, 0, false, 600));
    }

    @Test public void aListThatLooksCappedCountsAsLimitedOnlyWithACountFourTimesLarger() {
        assertTrue(FollowListPolicy.limitedByShape(true, 2, 50, 120_000));
        assertTrue(FollowListPolicy.limitedByShape(true, 0, 49, 196)); // all on screen before the load
        // a profile with 48 followers also ends after 2 pages
        assertFalse(FollowListPolicy.limitedByShape(true, 2, 48, 48));
        assertFalse(FollowListPolicy.limitedByShape(true, 2, 50, 150));
        assertFalse(FollowListPolicy.limitedByShape(true, 3, 50, 120_000));
        assertFalse(FollowListPolicy.limitedByShape(true, 2, 61, 120_000));
        assertFalse(FollowListPolicy.limitedByShape(false, 2, 50, 120_000));
        assertFalse(FollowListPolicy.limitedByShape(true, 2, 0, 120_000));
        assertFalse(FollowListPolicy.limitedByShape(true, 2, 50, -1));
    }

    @Test public void passHitsAndTheChao1Estimate() {
        // 6 accounts over 3 passes: 3 listed once, 2 twice, 1 three times
        int[] f = FollowListPolicy.passHits(Arrays.asList(1, 1, 2, 3, 1, 2), 3);
        assertArrayEquals(new int[]{0, 3, 2, 1}, f);
        assertEquals(9 / 4.0, FollowListPolicy.missingEstimate(f[1], f[2], 3), 1e-9);
        // no account listed twice: f1 (f1 - 1) / 2
        assertEquals(3, FollowListPolicy.missingEstimate(3, 0, 2), 1e-9);
        assertEquals(0, FollowListPolicy.missingEstimate(0, 0, 4), 1e-9);
        // one pass: nothing to estimate
        assertEquals(-1, FollowListPolicy.missingEstimate(480, 0, 1), 0);
        assertArrayEquals(new int[]{0, 2}, FollowListPolicy.passHits(Arrays.asList(1, 1), 1));
    }

    private static FollowListPolicy.Failed failed(int status, String errorType, String message) {
        FollowListPolicy.Failed f = new FollowListPolicy.Failed();
        f.status = status;
        f.errorType = errorType;
        f.message = message;
        return f;
    }

    @Test public void failedPagesAreSortedStrictly() {
        FollowListPolicy.Failed net = new FollowListPolicy.Failed();
        net.exception = true;
        assertEquals(FollowListPolicy.Failure.NETWORK, FollowListPolicy.classify(net));
        assertEquals(FollowListPolicy.Failure.RATE_LIMIT, FollowListPolicy.classify(failed(429, null, null)));
        assertEquals(FollowListPolicy.Failure.RATE_LIMIT,
                FollowListPolicy.classify(failed(-1, null, "Please wait a few minutes before you try again.")));
        assertEquals(FollowListPolicy.Failure.RATE_LIMIT, FollowListPolicy.classify(failed(400, "rate_limit_error", null)));
        assertEquals(FollowListPolicy.Failure.CHALLENGE, FollowListPolicy.classify(failed(400, "checkpoint_challenge_required", "challenge_required")));
        assertEquals(FollowListPolicy.Failure.CHALLENGE, FollowListPolicy.classify(failed(400, null, "checkpoint_required")));
        assertEquals(FollowListPolicy.Failure.CHALLENGE, FollowListPolicy.classify(failed(403, null, "login_required")));
        FollowListPolicy.Failed url = failed(400, null, null);
        url.checkpointUrl = true;
        assertEquals(FollowListPolicy.Failure.CHALLENGE, FollowListPolicy.classify(url));
        FollowListPolicy.Failed challenge = failed(-1, null, null);
        challenge.challenge = true;
        assertEquals(FollowListPolicy.Failure.CHALLENGE, FollowListPolicy.classify(challenge));
        assertEquals(FollowListPolicy.Failure.ACTION_BLOCK, FollowListPolicy.classify(failed(400, null, "feedback_required")));
        // anything else is a server error, retried
        assertEquals(FollowListPolicy.Failure.SERVER, FollowListPolicy.classify(failed(500, null, "Internal error")));
        assertEquals(FollowListPolicy.Failure.SERVER, FollowListPolicy.classify(failed(-1, null, null)));
        assertTrue(FollowListPolicy.Failure.RATE_LIMIT.blocks());
        assertTrue(FollowListPolicy.Failure.CHALLENGE.blocks());
        assertTrue(FollowListPolicy.Failure.ACTION_BLOCK.blocks());
        assertFalse(FollowListPolicy.Failure.NETWORK.blocks());
        assertFalse(FollowListPolicy.Failure.SERVER.blocks());
    }

    @Test public void serverErrorsGetThreeRetriesBlocksNone() {
        assertTrue(FollowListPolicy.retryDelayMs(3, FollowListPolicy.Failure.SERVER) > 0);
        assertEquals(-1, FollowListPolicy.retryDelayMs(4, FollowListPolicy.Failure.SERVER));
        assertTrue(FollowListPolicy.retryDelayMs(6, FollowListPolicy.Failure.NETWORK) > 0);
        assertTrue(FollowListPolicy.retryDelayMs(6, null) > 0);
        assertEquals(-1, FollowListPolicy.retryDelayMs(7, null));
        assertEquals(-1, FollowListPolicy.retryDelayMs(1, FollowListPolicy.Failure.RATE_LIMIT));
        assertEquals(FollowListPolicy.retryDelayMs(2), FollowListPolicy.retryDelayMs(2, FollowListPolicy.Failure.SERVER));
    }

    @Test public void aBlockPausesLoadingForItsKind() {
        long now = 1_000_000L;
        assertEquals(now + 15 * 60_000L, FollowListPolicy.pauseUntil(FollowListPolicy.Failure.RATE_LIMIT, now));
        assertEquals(now + 24 * 60 * 60_000L, FollowListPolicy.pauseUntil(FollowListPolicy.Failure.ACTION_BLOCK, now));
        assertEquals(Long.MAX_VALUE, FollowListPolicy.pauseUntil(FollowListPolicy.Failure.CHALLENGE, now));
        assertEquals(0, FollowListPolicy.pauseUntil(FollowListPolicy.Failure.SERVER, now));
        FollowListPolicy.Block b = new FollowListPolicy.Block(FollowListPolicy.Failure.RATE_LIMIT, null, now,
                FollowListPolicy.pauseUntil(FollowListPolicy.Failure.RATE_LIMIT, now));
        assertTrue(b.pausing(now + 14 * 60_000L));
        assertFalse(b.pausing(now + 15 * 60_000L));
        assertTrue(new FollowListPolicy.Block(FollowListPolicy.Failure.CHALLENGE, null, now, Long.MAX_VALUE).pausing(Long.MAX_VALUE - 1));
    }

    @Test public void instagramsOwnWordsSkipBareCodes() {
        assertEquals("We restrict certain activity.", FollowListPolicy.shownMessage("feedback_required", "We restrict certain activity."));
        assertNull(FollowListPolicy.shownMessage("feedback_required", null));
        assertNull(FollowListPolicy.shownMessage("challenge_required", " "));
        assertNull(FollowListPolicy.shownMessage(null, null));
        assertEquals("Please wait a few minutes before you try again.",
                FollowListPolicy.shownMessage(" Please wait a few minutes before you try again. ", ""));
    }

    @Test public void extraPassesCycleTheOrdersFromTheOneAfterTheFirst() {
        // first pass in the default order: latest, earliest, default, latest
        assertEquals(FollowListPolicy.ORDER_LATEST, FollowListPolicy.passOrder(FollowListPolicy.ORDER_DEFAULT, 1));
        assertEquals(FollowListPolicy.ORDER_EARLIEST, FollowListPolicy.passOrder(FollowListPolicy.ORDER_DEFAULT, 2));
        assertEquals(FollowListPolicy.ORDER_DEFAULT, FollowListPolicy.passOrder(FollowListPolicy.ORDER_DEFAULT, 3));
        assertEquals(FollowListPolicy.ORDER_LATEST, FollowListPolicy.passOrder(FollowListPolicy.ORDER_DEFAULT, 4));
        // a list already sorted newest first goes on with oldest first, then the default order
        assertEquals(FollowListPolicy.ORDER_EARLIEST, FollowListPolicy.passOrder(FollowListPolicy.ORDER_LATEST, 1));
        assertEquals(FollowListPolicy.ORDER_DEFAULT, FollowListPolicy.passOrder(FollowListPolicy.ORDER_LATEST, 2));
        assertEquals(FollowListPolicy.ORDER_DEFAULT, FollowListPolicy.passOrder(FollowListPolicy.ORDER_EARLIEST, 1));
        // an order this policy doesn't know starts the cycle at its start
        assertEquals(FollowListPolicy.ORDER_LATEST, FollowListPolicy.passOrder("SOMETHING_NEW", 1));
    }

    @Test public void withoutRepeatsEveryOrderIsListedOnce() {
        String first = FollowListPolicy.ORDER_DEFAULT;
        java.util.Set<String> orders = new java.util.HashSet<>(Collections.singletonList(first));
        for (int n = 1; n < FollowListPolicy.MAX_PASSES_WITHOUT_REPEATS; n++)
            assertTrue(orders.add(FollowListPolicy.passOrder(first, n)));
    }

    @Test public void passesRunUntilTheCountIsReachedADryPassOrTheCap() {
        int max = FollowListPolicy.MAX_PASSES;
        // passes that still added some and are still short of the count: go on
        assertTrue(FollowListPolicy.anotherPass(2, max, 570, 600, 90, false, 600));
        assertTrue(FollowListPolicy.anotherPass(3, max, 585, 600, 15, false, 600));
        // a pass that added nothing ends the passes
        assertFalse(FollowListPolicy.anotherPass(3, max, 585, 600, 0, false, 600));
        // Instagram's count reached
        assertFalse(FollowListPolicy.anotherPass(2, max, 600, 600, 40, false, 600));
        // the cap: MAX_PASSES (7) passes in all, or
        // 3 when an order can't be listed twice
        assertEquals(7, max);
        assertTrue(FollowListPolicy.anotherPass(5, max, 596, 600, 3, false, 600));
        assertTrue(FollowListPolicy.anotherPass(6, max, 597, 600, 1, false, 600));
        assertFalse(FollowListPolicy.anotherPass(7, max, 598, 600, 1, false, 600));
        assertFalse(FollowListPolicy.anotherPass(3, FollowListPolicy.MAX_PASSES_WITHOUT_REPEATS, 585, 600, 15, false, 600));
    }

    @Test public void aPassTallyCountsNewAndRepeatedAccountsPerPage() {
        FollowListPolicy.PassTally t = new FollowListPolicy.PassTally();
        assertArrayEquals(new int[]{3, 0, 0, 0, 0, 0, 0}, t.page(Arrays.asList("1", "2", "3")));
        // the next page repeats two accounts of the first one (1 page back)
        assertArrayEquals(new int[]{1, 2, 0, 2, 0, 0, 0}, t.page(Arrays.asList("2", "4", "3", null)));
        assertEquals(2, t.pages);
        assertEquals(7, t.received);
        assertEquals(4, t.distinct());
        assertEquals(2, t.repeated);
        assertEquals(1, t.withoutId);
    }

    @Test public void aPassTallyTellsHowManyPagesBackARepeatWasLastSent() {
        FollowListPolicy.PassTally t = new FollowListPolicy.PassTally();
        t.page(Arrays.asList("a", "b", "c")); // page 0
        t.page(Arrays.asList("d", "e"));      // page 1
        t.page(Arrays.asList("f"));           // page 2
        t.page(Arrays.asList("g"));           // page 3
        t.page(Arrays.asList("h"));           // page 4
        t.page(Arrays.asList("i"));           // page 5
        // page 6: h 2 back, e 5 back, a 6 back, i 1 back, and i again on the same page
        int[] c = t.page(Arrays.asList("h", "e", "a", "i", "i", "new"));
        assertEquals(1, c[FollowListPolicy.P_NEW]);
        assertEquals(5, c[FollowListPolicy.P_REPEATED]);
        assertEquals(1, c[FollowListPolicy.P_BACK1]);
        assertEquals(1, c[FollowListPolicy.P_BACK2]);
        assertEquals(1, c[FollowListPolicy.P_BACK3_5]);
        assertEquals(1, c[FollowListPolicy.P_OLDER]);
        assertEquals(1, c[FollowListPolicy.P_SAME]);
        // a repeat counts from the last page that sent it: "a" now 1 back
        assertEquals(1, t.page(Arrays.asList("a"))[FollowListPolicy.P_BACK1]);
    }

    @Test public void aPassFromTheFirstPageEndsWithAccountsOrAPageElseGivesUp() {
        assertEquals(FollowListPolicy.End.COMPLETE, FollowListPolicy.passAtEnd(40, false, 0));
        // Instagram sent an empty page for that order: the pass is done, with nothing new
        assertEquals(FollowListPolicy.End.COMPLETE, FollowListPolicy.passAtEnd(0, true, 1_000));
        assertEquals(FollowListPolicy.End.WAIT_FIRST_PAGE, FollowListPolicy.passAtEnd(0, false, 1_000));
        assertEquals(FollowListPolicy.End.INCOMPLETE, FollowListPolicy.passAtEnd(0, false, FollowListPolicy.PASS_FIRST_PAGE_MS));
    }

    @Test public void onlyAccountsNotListedBeforeAreNew() {
        java.util.Set<String> known = new java.util.HashSet<>(Arrays.asList("1", "2", "3"));
        assertEquals(Arrays.asList("9", "4"), FollowListPolicy.newIds(known, Arrays.asList("3", "9", "1", "4")));
        assertTrue(FollowListPolicy.newIds(known, Arrays.asList("2", "1")).isEmpty());
    }

    @Test public void aLimitedListGetsTheLimitedNote() {
        assertEquals(FollowListPolicy.Note.LIMITED, FollowListPolicy.note(450, 600, true));
        assertEquals(FollowListPolicy.Note.LIMITED, FollowListPolicy.note(50, -1, true));
        assertEquals(FollowListPolicy.Note.SHORT, FollowListPolicy.note(450, 600, false));
        assertEquals(FollowListPolicy.Note.NONE, FollowListPolicy.note(600, 600, false));
        assertEquals(FollowListPolicy.Note.NONE, FollowListPolicy.note(450, -1, false));
    }

    @Test public void oneApplicableActionRunsDirectly() {
        List<FollowListPolicy.Action> a = FollowListPolicy.actions(true, false, true, true, false);
        assertEquals(Collections.singletonList(FollowListPolicy.Action.ANALYZE), a);
    }

    @Test public void historyOnlyOnYourOwnFollowersTab() {
        assertEquals(Arrays.asList(FollowListPolicy.Action.ANALYZE, FollowListPolicy.Action.EXPORT, FollowListPolicy.Action.HISTORY),
                FollowListPolicy.actions(true, true, true, true, true));
        assertEquals(Collections.singletonList(FollowListPolicy.Action.EXPORT),
                FollowListPolicy.actions(false, true, true, false, false));
        assertTrue(FollowListPolicy.actions(true, false, true, false, false).isEmpty());
    }
}
