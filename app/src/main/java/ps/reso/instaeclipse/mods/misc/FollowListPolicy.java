package ps.reso.instaeclipse.mods.misc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure decisions for loading a follow list to the end (FollowListLoader: retries, extra passes,
 * limited lists, sorting failed pages and the pause after a block) and for the follow list
 * screen's tools button (FollowListToolsIcon), kept apart so they run as JVM unit tests.
 */
final class FollowListPolicy {

    /** No new page (and no new account) for this long after asking for one: the page failed. */
    static final long STALL_MS = 8_000;
    /** How long an empty list without a known count may wait for its first page. */
    static final long FIRST_PAGE_MS = 10_000;
    /** Ask Instagram's pager again after this long without a new page (its own guard skips a
     *  call while a request is in flight, so asking twice never sends two requests). */
    static final long REASK_MS = 1_000;
    /** Waits before each retry of a failed page; the list counts as unfinished after the last. */
    private static final long[] RETRY_DELAYS_MS = {2_000, 5_000, 10_000, 20_000, 40_000, 60_000};
    /** Retries of a page Instagram's server answered with an error that isn't a block. */
    static final int SERVER_RETRIES = 3;

    private FollowListPolicy() {}

    /** Wait before retry number {@code attempt} (1-based), or -1 when retries are used up. */
    static long retryDelayMs(int attempt) {
        if (attempt < 1 || attempt > RETRY_DELAYS_MS.length) return -1;
        return RETRY_DELAYS_MS[attempt - 1];
    }

    /** The same, after a failure of kind {@code cause} (null: unknown, e.g. no page for a while):
     *  a server error gets SERVER_RETRIES at most, a block none. */
    static long retryDelayMs(int attempt, Failure cause) {
        if (cause != null && cause.blocks()) return -1;
        if (cause == Failure.SERVER && attempt > SERVER_RETRIES) return -1;
        return retryDelayMs(attempt);
    }

    /** What a load does when Instagram's pager has no next page (its next max_id is null). */
    enum End {
        /** the list is loaded to the end */
        COMPLETE,
        /** nothing loaded yet: the first page may still be on its way (failed pages are retried) */
        WAIT_FIRST_PAGE,
        /** nothing loaded, no count to confirm an empty list, and no first page came: unfinished */
        INCOMPLETE
    }

    /**
     * {@code accounts}: accounts loaded so far; {@code expected}: Instagram's count for this list
     * (-1 when unknown); {@code waitedMs}: time since the load started. An empty list is only
     * complete when Instagram's count says 0.
     */
    static End atEnd(int accounts, int expected, long waitedMs) {
        if (accounts > 0 || expected == 0) return End.COMPLETE;
        return expected < 0 && waitedMs >= FIRST_PAGE_MS ? End.INCOMPLETE : End.WAIT_FIRST_PAGE;
    }

    /** True when Instagram listed fewer accounts than the count on the list's tab. */
    static boolean shortOfExpected(int listed, int expected) {
        return expected > 0 && listed < expected;
    }

    // ---- extra passes ----

    /** Instagram's follow list sort orders: the constant names of its order enum (IG 451 X.0NIL,
     *  names kept), whose String values ("", "date_followed_latest", ...) it sends as "order". */
    static final String ORDER_DEFAULT = "DEFAULT", ORDER_LATEST = "DATE_FOLLOWED_LATEST", ORDER_EARLIEST = "DATE_FOLLOWED_EARLIEST";
    /** Extra passes also start when at least this share (%) of the target is missing (see
     *  startExtraPasses): a few deactivated accounts aren't worth listing the whole list again. */
    static final int EXTRA_PASS_MIN_MISSING_PERCENT = 5;
    /** How long a pass that lists from the first page waits for that page. Nothing can ask for it
     *  again: Instagram's own "fetch the next page" does nothing while a new order's first page is pending. */
    static final long PASS_FIRST_PAGE_MS = 20_000;
    /** Passes in all, the first one included. */
    static final int MAX_PASSES = 7;
    /** ... when an order can't be listed twice (its pages would come from Instagram's per-order page
     *  cache instead of its server): the first order and the two others once each. */
    static final int MAX_PASSES_WITHOUT_REPEATS = 3;
    /** Extra passes stop before the rows Instagram sends in all passes ((passes + 1) × rows per pass)
     *  would go over this: a judgement call that keeps large lists from being listed 7 times and
     *  bounds how much account data one load pulls. */
    static final int ROW_BUDGET = 8000;
    /** The orders extra passes cycle through, from the one after the first pass's order. */
    private static final String[] PASS_CYCLE = {ORDER_LATEST, ORDER_EARLIEST, ORDER_DEFAULT};
    /** A pass listing the first pass's order again that adds less than this share of the accounts
     *  predicted for it (with at least RELIST_MIN_PREDICTED predicted) makes the next passes switch
     *  order: Instagram may have answered the identical requests from a short-lived cache. */
    static final double RELIST_MIN_SHARE = 0.5, RELIST_MIN_PREDICTED = 10;

    /** The count extra passes aim for: Instagram's count, or the rows it sent in one pass when that's
     *  more ({@code received} &lt;= 0: unknown); its count can lag the list by a few for hours. */
    static int target(int expected, int received) {
        return received > 0 ? Math.max(expected, received) : expected;
    }

    /** One more pass after {@code passesRun} stays within ROW_BUDGET ({@code rowsPerPass}: the rows
     *  Instagram sends for this list in one pass). */
    static boolean withinRowBudget(int passesRun, int rowsPerPass) {
        return (long) (passesRun + 1) * Math.max(0, rowsPerPass) <= ROW_BUDGET;
    }

    /**
     * Extra passes start after a complete first pass of a list Instagram didn't flag as limited
     * (it shows only part of such a list, by design, so listing it again finds nothing): when
     * Instagram repeated accounts within the pass (each repeat took the place of an account it
     * skipped: {@code repeated} of {@code received} rows, at least max(2, received / 500)), or when
     * the pass listed clearly fewer accounts than the target (EXTRA_PASS_MIN_MISSING_PERCENT; kept
     * for pages whose repeats weren't counted, e.g. without the page hook). Never over ROW_BUDGET.
     */
    static boolean startExtraPasses(boolean complete, boolean limited, int listed, int expected, int received, int repeated) {
        if (!complete || limited || !withinRowBudget(1, Math.max(Math.max(received, expected), listed))) return false;
        int target = target(expected, received);
        if (!shortOfExpected(listed, target)) return false;
        boolean repeats = received > 0 && repeated >= Math.max(2, received / 500);
        return repeats || (long) (target - listed) * 100 >= (long) target * EXTRA_PASS_MIN_MISSING_PERCENT;
    }

    /**
     * Extra passes list the list again in the first pass's own order (its page caches emptied, the
     * order set again) rather than in Instagram's other orders: on someone else's list, where
     * Instagram's server ignores the order (each pass in any order repeats ~20% of its rows, also
     * twice in one order: instagrapi #2857) and its app never sends one; only when the page caches
     * can be emptied and the page requests skip Instagram's response cache (else the same order
     * would show the same pages again). Your own lists keep cycling the orders, which Instagram
     * honours there.
     */
    static boolean relistPasses(boolean ownList, boolean pageCaches, boolean refreshHooked) {
        return !ownList && pageCaches && refreshHooked;
    }

    /** New accounts a pass is expected to add when each pass lists a random part of the list: the
     *  accounts not listed yet ({@code target} - {@code listedBefore}) × the share this pass listed. */
    static double predictedNew(int target, int listedBefore, int passListed) {
        if (target <= 0 || listedBefore >= target || passListed <= 0) return 0;
        return (double) (target - listedBefore) * passListed / target;
    }

    /** A pass in the first pass's order again fell short of {@code predicted} (RELIST_MIN_SHARE). */
    static boolean relistFellShort(double predicted, int added) {
        return predicted >= RELIST_MIN_PREDICTED && added < RELIST_MIN_SHARE * predicted;
    }

    /**
     * The order of extra pass {@code n} (1-based) after a first pass in {@code firstOrder}: the
     * cycle date followed latest, earliest, default, starting after the first pass's order (so two
     * passes in a row never share an order). An unknown first order starts the cycle at its start.
     */
    static String passOrder(String firstOrder, int n) {
        int first = -1;
        for (int i = 0; i < PASS_CYCLE.length; i++) if (PASS_CYCLE[i].equals(firstOrder)) first = i;
        return PASS_CYCLE[Math.floorMod(first + n, PASS_CYCLE.length)];
    }

    /**
     * Whether another extra pass runs after {@code passesRun} passes (the first one included) of at
     * most {@code maxPasses}: while the accounts of all passes ({@code listed}) are still fewer than
     * the target and the last pass added some ({@code added}), or fell short of its prediction
     * ({@code fellShort}: the next pass switches order); a pass that adds none ends them. Never over
     * ROW_BUDGET ({@code rowsPerPass}).
     */
    static boolean anotherPass(int passesRun, int maxPasses, int listed, int target, int added, boolean fellShort, int rowsPerPass) {
        return passesRun < maxPasses && (added > 0 || fellShort) && shortOfExpected(listed, target)
                && withinRowBudget(passesRun, rowsPerPass);
    }

    /**
     * A list that looks capped although its limit flag couldn't be read (only used then): complete
     * after at most 2 pages, at most 60 accounts, and Instagram counting at least 4 times as many
     * (a profile with 48 followers also ends after 2 pages).
     */
    static boolean limitedByShape(boolean complete, int pages, int listed, int expected) {
        return complete && pages <= 2 && listed > 0 && listed <= 60 && expected >= 4L * listed;
    }

    /** How many accounts were listed by exactly k of the passes: {@code out[k]}, k = 1..passes
     *  ({@code hits}: per account, the passes that listed it). */
    static int[] passHits(Collection<Integer> hits, int passes) {
        int[] f = new int[Math.max(1, passes) + 1];
        for (Integer h : hits) if (h != null && h >= 1 && h < f.length) f[h]++;
        return f;
    }

    /**
     * Chao1's estimate of the accounts no pass listed yet, from those listed by exactly one pass
     * ({@code f1}) and exactly two ({@code f2}): f1² / (2 f2), or f1 (f1 - 1) / 2 when f2 is 0.
     * -1 with fewer than 2 passes. A lower bound when some accounts are listed less often than
     * others; logged, not used to stop the passes.
     */
    static double missingEstimate(int f1, int f2, int passes) {
        if (passes < 2) return -1;
        if (f2 > 0) return (double) f1 * f1 / (2.0 * f2);
        return f1 * (f1 - 1) / 2.0;
    }

    // ---- failed page requests: blocks ----

    /** Why Instagram's pager reported a failed page (its failure callback). */
    enum Failure {
        /** no answer from the server (the request threw) */
        NETWORK,
        /** the server answered with an error that isn't one of the blocks below */
        SERVER,
        /** too many requests (HTTP 429, rate_limit_error, "Please wait a few minutes") */
        RATE_LIMIT,
        /** the account must be confirmed (checkpoint / challenge / login required) */
        CHALLENGE,
        /** feedback_required: Instagram blocks the action for a while */
        ACTION_BLOCK;

        /** The load stops at once, without retries, and loading lists pauses for a while. */
        boolean blocks() { return this == RATE_LIMIT || this == CHALLENGE || this == ACTION_BLOCK; }
    }

    /** What a failed page's response said (read from Instagram's error model, X.0CdQ). */
    static final class Failed {
        /** the request threw instead of getting an answer */
        boolean exception;
        /** the HTTP status, -1 when unknown */
        int status = -1;
        String message, errorType, feedbackMessage;
        /** checkpoint_url set, challenge set, checkpoint_required / login_required true */
        boolean checkpointUrl, challenge, checkpointRequired, loginRequired;
    }

    /** Sorts a failed page strictly: anything not clearly a block is SERVER (retried). */
    static Failure classify(Failed f) {
        if (f.exception) return Failure.NETWORK;
        String text = lower(f.errorType) + " " + lower(f.message);
        if (f.checkpointUrl || f.challenge || f.checkpointRequired || f.loginRequired || text.contains("checkpoint_required")
                || text.contains("challenge_required") || text.contains("login_required")) return Failure.CHALLENGE;
        if (text.contains("feedback_required")) return Failure.ACTION_BLOCK;
        if (f.status == 429 || text.contains("rate_limit_error") || text.contains("wait a few minutes")) return Failure.RATE_LIMIT;
        return Failure.SERVER;
    }

    private static String lower(String s) { return s != null ? s.toLowerCase(java.util.Locale.ROOT) : ""; }

    /** What to show as Instagram's own words: its feedback message, else its message unless that is
     *  a bare code such as "feedback_required"; null when there is none. */
    static String shownMessage(String message, String feedbackMessage) {
        if (feedbackMessage != null && !feedbackMessage.trim().isEmpty()) return feedbackMessage.trim();
        if (message == null || message.trim().isEmpty() || message.trim().matches("[a-z_]+")) return null;
        return message.trim();
    }

    /** How long loading lists pauses after a block. */
    static final long RATE_LIMIT_PAUSE_MS = 15 * 60_000L, ACTION_BLOCK_PAUSE_MS = 24 * 60 * 60_000L;

    /** Until when loading lists pauses after a block of kind {@code kind} at {@code now}: about 15
     *  min after a rate limit, 24 h after an action block, until a list loads fine again after a
     *  challenge (Long.MAX_VALUE); 0 for no pause. */
    static long pauseUntil(Failure kind, long now) {
        return switch (kind) {
            case RATE_LIMIT -> now + RATE_LIMIT_PAUSE_MS;
            case ACTION_BLOCK -> now + ACTION_BLOCK_PAUSE_MS;
            case CHALLENGE -> Long.MAX_VALUE;
            default -> 0;
        };
    }

    /** A load Instagram refused: the kind, Instagram's own words (or null), when, and the pause's end. */
    static final class Block {
        final Failure kind;
        final String message;
        final long at, until;

        Block(Failure kind, String message, long at, long until) {
            this.kind = kind;
            this.message = message;
            this.at = at;
            this.until = until;
        }

        /** The pause after it still runs at {@code now}. */
        boolean pausing(long now) { return until > now; }

        @Override public String toString() { return kind + (message != null ? " \"" + message + "\"" : ""); }
    }

    /**
     * What a pass that lists from the first page (an extra pass, or the first pass started over)
     * does when Instagram's pager has no next page: {@code accounts} listed in this pass,
     * {@code pageSeen} Instagram delivered a page of this order (it may be empty), {@code waitedMs}
     * since the pass started.
     */
    static End passAtEnd(int accounts, boolean pageSeen, long waitedMs) {
        if (accounts > 0 || pageSeen) return End.COMPLETE;
        return waitedMs >= PASS_FIRST_PAGE_MS ? End.INCOMPLETE : End.WAIT_FIRST_PAGE;
    }

    /** The accounts of a pass ({@code passIds}, unique, in Instagram's order) not listed before. */
    static List<String> newIds(Set<String> known, List<String> passIds) {
        List<String> out = new ArrayList<>();
        for (String id : passIds) if (!known.contains(id)) out.add(id);
        return out;
    }

    /** PassTally.page's result: accounts new in this pass, repeated ones, and the repeats by how many
     *  pages back the last page that sent them was (the same page, 1, 2, 3..5, more). */
    static final int P_NEW = 0, P_REPEATED = 1, P_SAME = 2, P_BACK1 = 3, P_BACK2 = 4, P_BACK3_5 = 5, P_OLDER = 6;

    /** The pages Instagram sent in one pass, as they came (diagnostics): accounts received, how
     *  many were new in this pass and how many it had already sent in an earlier page of it. */
    static final class PassTally {
        int pages, received, repeated, withoutId;
        /** id → the last page of this pass (0-based) that sent it */
        private final Map<String, Integer> lastPage = new HashMap<>();

        /** Adds a page ({@code pageIds}: one per account, null without an id); returns its counts
         *  (P_NEW .. P_OLDER). */
        synchronized int[] page(List<String> pageIds) {
            int[] c = new int[P_OLDER + 1];
            int at = pages;
            for (String id : pageIds) {
                if (id == null || id.isEmpty()) { withoutId++; continue; }
                Integer last = lastPage.put(id, at);
                if (last == null) { c[P_NEW]++; continue; }
                c[P_REPEATED]++;
                int back = at - last;
                c[back <= 0 ? P_SAME : back == 1 ? P_BACK1 : back == 2 ? P_BACK2 : back <= 5 ? P_BACK3_5 : P_OLDER]++;
            }
            pages++;
            received += pageIds.size();
            repeated += c[P_REPEATED];
            return c;
        }

        /** Different accounts received in this pass. */
        synchronized int distinct() { return lastPage.size(); }
    }

    /** Which note a loaded list gets next to Instagram's count. */
    enum Note {
        /** Instagram listed every account it counts (or its count is unknown) */
        NONE,
        /** fewer than its count, e.g. deactivated or hidden accounts */
        SHORT,
        /** Instagram flagged the list as limited (should_limit_list_of_followers / _followings on a page) */
        LIMITED
    }

    static Note note(int listed, int expected, boolean limited) {
        if (limited) return Note.LIMITED;
        return shortOfExpected(listed, expected) ? Note.SHORT : Note.NONE;
    }

    /** How a list's rows break down: one id per row in Instagram's order, null for a row without one. */
    static final class Counts {
        final int rows, unique, duplicates, withoutId;
        final List<String> ids; // unique ids, first occurrence order

        Counts(int rows, int unique, int duplicates, int withoutId, List<String> ids) {
            this.rows = rows;
            this.unique = unique;
            this.duplicates = duplicates;
            this.withoutId = withoutId;
            this.ids = ids;
        }
    }

    static Counts count(List<String> rowIds) {
        Set<String> seen = new LinkedHashSet<>();
        int dup = 0, noId = 0;
        for (String id : rowIds) {
            if (id == null || id.isEmpty()) noId++;
            else if (!seen.add(id)) dup++;
        }
        return new Counts(rowIds.size(), seen.size(), dup, noId, new ArrayList<>(seen));
    }

    /**
     * Which of the adapter's account lists is the list itself: the one that grew while pages came
     * in (Instagram appends every page to it), else the largest. -1 when there is none.
     * {@code growth[i]}: how many times list i got longer during this load.
     */
    static int mainList(int[] sizes, int[] growth) {
        int best = -1;
        for (int i = 0; i < sizes.length; i++) {
            if (best < 0) { best = i; continue; }
            int g = growth != null ? growth[i] : 0, bg = growth != null ? growth[best] : 0;
            if (g > bg || (g == bg && sizes[i] > sizes[best])) best = i;
        }
        return best;
    }

    /** The tools button's actions, in menu order. */
    enum Action { ANALYZE, EXPORT, HISTORY }

    /**
     * The enabled actions that apply to the tab on screen: Analyze when the profile behind the
     * list is known, Export CSV on any list, Followers history only on your own Followers tab.
     */
    static List<Action> actions(boolean analyzer, boolean export, boolean history,
                                boolean profileKnown, boolean ownFollowers) {
        List<Action> out = new ArrayList<>();
        if (analyzer && profileKnown) out.add(Action.ANALYZE);
        if (export) out.add(Action.EXPORT);
        if (history && ownFollowers) out.add(Action.HISTORY);
        return out;
    }
}
