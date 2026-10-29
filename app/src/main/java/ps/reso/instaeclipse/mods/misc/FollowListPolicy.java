package ps.reso.instaeclipse.mods.misc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

final class FollowListPolicy {

    static final long STALL_MS = 8_000;
    static final long FIRST_PAGE_MS = 10_000;
    static final long REASK_MS = 1_000;
    private static final long[] RETRY_DELAYS_MS = {2_000, 5_000, 10_000, 20_000, 40_000, 60_000};
    static final int SERVER_RETRIES = 3;

    private FollowListPolicy() {}

    static long retryDelayMs(int attempt) {
        if (attempt < 1 || attempt > RETRY_DELAYS_MS.length) return -1;
        return RETRY_DELAYS_MS[attempt - 1];
    }

    static long retryDelayMs(int attempt, Failure cause) {
        if (cause != null && cause.blocks()) return -1;
        if (cause == Failure.SERVER && attempt > SERVER_RETRIES) return -1;
        return retryDelayMs(attempt);
    }

    enum End {
        COMPLETE,
        WAIT_FIRST_PAGE,
        INCOMPLETE
    }

    static End atEnd(int accounts, int expected, long waitedMs) {
        if (accounts > 0 || expected == 0) return End.COMPLETE;
        return expected < 0 && waitedMs >= FIRST_PAGE_MS ? End.INCOMPLETE : End.WAIT_FIRST_PAGE;
    }

    static boolean shortOfExpected(int listed, int expected) {
        return expected > 0 && listed < expected;
    }

    static final String ORDER_DEFAULT = "DEFAULT", ORDER_LATEST = "DATE_FOLLOWED_LATEST", ORDER_EARLIEST = "DATE_FOLLOWED_EARLIEST";
    static final int EXTRA_PASS_MIN_MISSING_PERCENT = 5;
    static final long PASS_FIRST_PAGE_MS = 20_000;
    static final int MAX_PASSES = 7;
    static final int MAX_PASSES_WITHOUT_REPEATS = 3;
    static final int ROW_BUDGET = 8000;
    private static final String[] PASS_CYCLE = {ORDER_LATEST, ORDER_EARLIEST, ORDER_DEFAULT};
    static final double RELIST_MIN_SHARE = 0.5, RELIST_MIN_PREDICTED = 10;

    static int target(int expected, int received) {
        return received > 0 ? Math.max(expected, received) : expected;
    }

    static boolean withinRowBudget(int passesRun, int rowsPerPass) {
        return (long) (passesRun + 1) * Math.max(0, rowsPerPass) <= ROW_BUDGET;
    }

    static boolean startExtraPasses(boolean complete, boolean limited, int listed, int expected, int received, int repeated) {
        if (!complete || limited || !withinRowBudget(1, Math.max(Math.max(received, expected), listed))) return false;
        int target = target(expected, received);
        if (!shortOfExpected(listed, target)) return false;
        boolean repeats = received > 0 && repeated >= Math.max(2, received / 500);
        return repeats || (long) (target - listed) * 100 >= (long) target * EXTRA_PASS_MIN_MISSING_PERCENT;
    }

    static boolean relistPasses(boolean ownList, boolean pageCaches, boolean refreshHooked) {
        return !ownList && pageCaches && refreshHooked;
    }

    static double predictedNew(int target, int listedBefore, int passListed) {
        if (target <= 0 || listedBefore >= target || passListed <= 0) return 0;
        return (double) (target - listedBefore) * passListed / target;
    }

    static boolean relistFellShort(double predicted, int added) {
        return predicted >= RELIST_MIN_PREDICTED && added < RELIST_MIN_SHARE * predicted;
    }

    static String passOrder(String firstOrder, int n) {
        int first = -1;
        for (int i = 0; i < PASS_CYCLE.length; i++) if (PASS_CYCLE[i].equals(firstOrder)) first = i;
        return PASS_CYCLE[Math.floorMod(first + n, PASS_CYCLE.length)];
    }

    static boolean anotherPass(int passesRun, int maxPasses, int listed, int target, int added, boolean fellShort, int rowsPerPass) {
        return passesRun < maxPasses && (added > 0 || fellShort) && shortOfExpected(listed, target)
                && withinRowBudget(passesRun, rowsPerPass);
    }

    static boolean limitedByShape(boolean complete, int pages, int listed, int expected) {
        return complete && pages <= 2 && listed > 0 && listed <= 60 && expected >= 4L * listed;
    }

    static int[] passHits(Collection<Integer> hits, int passes) {
        int[] f = new int[Math.max(1, passes) + 1];
        for (Integer h : hits) if (h != null && h >= 1 && h < f.length) f[h]++;
        return f;
    }

    static double missingEstimate(int f1, int f2, int passes) {
        if (passes < 2) return -1;
        if (f2 > 0) return (double) f1 * f1 / (2.0 * f2);
        return f1 * (f1 - 1) / 2.0;
    }

    enum Failure {
        NETWORK,
        SERVER,
        RATE_LIMIT,
        CHALLENGE,
        ACTION_BLOCK;

        boolean blocks() { return this == RATE_LIMIT || this == CHALLENGE || this == ACTION_BLOCK; }
    }

    static final class Failed {
        boolean exception;
        int status = -1;
        String message, errorType, feedbackMessage;
        boolean checkpointUrl, challenge, checkpointRequired, loginRequired;
    }

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

    static String shownMessage(String message, String feedbackMessage) {
        if (feedbackMessage != null && !feedbackMessage.trim().isEmpty()) return feedbackMessage.trim();
        if (message == null || message.trim().isEmpty() || message.trim().matches("[a-z_]+")) return null;
        return message.trim();
    }

    static final long RATE_LIMIT_PAUSE_MS = 15 * 60_000L, ACTION_BLOCK_PAUSE_MS = 24 * 60 * 60_000L;

    static long pauseUntil(Failure kind, long now) {
        return switch (kind) {
            case RATE_LIMIT -> now + RATE_LIMIT_PAUSE_MS;
            case ACTION_BLOCK -> now + ACTION_BLOCK_PAUSE_MS;
            case CHALLENGE -> Long.MAX_VALUE;
            default -> 0;
        };
    }

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

        boolean pausing(long now) { return until > now; }

        @Override public String toString() { return kind + (message != null ? " \"" + message + "\"" : ""); }
    }

    static End passAtEnd(int accounts, boolean pageSeen, long waitedMs) {
        if (accounts > 0 || pageSeen) return End.COMPLETE;
        return waitedMs >= PASS_FIRST_PAGE_MS ? End.INCOMPLETE : End.WAIT_FIRST_PAGE;
    }

    static List<String> newIds(Set<String> known, List<String> passIds) {
        List<String> out = new ArrayList<>();
        for (String id : passIds) if (!known.contains(id)) out.add(id);
        return out;
    }

    static final int P_NEW = 0, P_REPEATED = 1, P_SAME = 2, P_BACK1 = 3, P_BACK2 = 4, P_BACK3_5 = 5, P_OLDER = 6;

    static final class PassTally {
        int pages, received, repeated, withoutId;
        private final Map<String, Integer> lastPage = new HashMap<>();

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

        synchronized int distinct() { return lastPage.size(); }
    }

    enum Note {
        NONE,
        SHORT,
        LIMITED
    }

    static Note note(int listed, int expected, boolean limited) {
        if (limited) return Note.LIMITED;
        return shortOfExpected(listed, expected) ? Note.SHORT : Note.NONE;
    }

    static final class Counts {
        final int rows, unique, duplicates, withoutId;
        final List<String> ids;

        Counts(int rows, int unique, int duplicates, int withoutId, List<String> ids) {
            this.rows = rows;
            this.unique = unique;
            this.duplicates = duplicates;
            this.withoutId = withoutId;
            this.ids = ids;
        }
    }

    static final class IncrementalIds {
        private List<?> list;
        private Object first, last;
        private int read, rows, duplicates, withoutId;
        private final Set<String> seen = new HashSet<>();
        private final List<String> ids = new ArrayList<>();
        private Set<String> known;
        private int knownSize = -1, knownChecked, fresh;

        <T> void read(List<T> rowList, Function<? super T, String> idOf) {
            if (changed(rowList)) restart(rowList);
            for (int n = rowList.size(); read < n; read++) {
                T row = rowList.get(read);
                String id = idOf.apply(row);
                rows++;
                if (id == null || id.isEmpty()) withoutId++;
                else if (seen.add(id)) ids.add(id);
                else duplicates++;
                if (read == 0) first = row;
                last = row;
            }
        }

        private boolean changed(List<?> rowList) {
            if (rowList != list || rowList.size() < read) return true;
            return read > 0 && (rowList.get(0) != first || rowList.get(read - 1) != last);
        }

        private void restart(List<?> rowList) {
            list = rowList;
            first = last = null;
            read = rows = duplicates = withoutId = 0;
            seen.clear();
            ids.clear();
            knownChecked = fresh = 0;
        }

        int rows() { return rows; }

        int unique() { return ids.size(); }

        List<String> ids() { return Collections.unmodifiableList(ids); }

        int fresh(Set<String> knownIds) {
            if (knownIds != known || knownIds.size() != knownSize) {
                known = knownIds;
                knownSize = knownIds.size();
                knownChecked = fresh = 0;
            }
            for (int n = ids.size(); knownChecked < n; knownChecked++) if (!knownIds.contains(ids.get(knownChecked))) fresh++;
            return fresh;
        }

        Counts counts() {
            return new Counts(rows, ids.size(), duplicates, withoutId, new ArrayList<>(ids));
        }
    }

    static int mainList(int[] sizes, int[] growth) {
        int best = -1;
        for (int i = 0; i < sizes.length; i++) {
            if (best < 0) { best = i; continue; }
            int g = growth != null ? growth[i] : 0, bg = growth != null ? growth[best] : 0;
            if (g > bg || (g == bg && sizes[i] > sizes[best])) best = i;
        }
        return best;
    }

    enum Action { ANALYZE, CHANGELOG, EXPORT, HISTORY }

    static List<Action> menu(List<Action> actions, boolean changelog) {
        List<Action> out = new ArrayList<>();
        for (Action a : actions) {
            out.add(a);
            if (a == Action.ANALYZE && changelog) out.add(Action.CHANGELOG);
        }
        return out;
    }

    static boolean isFollowersType(String name) {
        return "FOLLOWERS".equals(name) || "SELF_FOLLOWERS".equals(name);
    }

    static boolean isFollowingType(String name) {
        return "FOLLOWING".equals(name) || "FOLLOWING_SIMPLIFIED".equals(name) || "SELF_FOLLOWING".equals(name);
    }

    static boolean determinateProgress(int pass, boolean limited, int expected) {
        return pass == 0 && !limited && expected > 0;
    }

    static List<Action> actions(boolean analyzer, boolean export, boolean history,
                                boolean profileKnown, boolean ownFollowers) {
        List<Action> out = new ArrayList<>();
        if (analyzer && profileKnown) out.add(Action.ANALYZE);
        if (export) out.add(Action.EXPORT);
        if (history && ownFollowers) out.add(Action.HISTORY);
        return out;
    }
}
