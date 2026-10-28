package ps.reso.instaeclipse.utils.ui;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

import ps.reso.instaeclipse.utils.feature.SpoofLastSeenNotice;

public final class NoticeQueue<S> {

    public static final class Notice<S> {
        public final String id;
        public final int priority;
        private final Predicate<S> condition;

        public Notice(String id, int priority, Predicate<S> condition) {
            this.id = id;
            this.priority = priority;
            this.condition = condition;
        }
    }

    private final List<Notice<S>> waiting = new ArrayList<>();
    private final Set<String> done = new HashSet<>();
    private String open;

    public synchronized boolean offer(Notice<S> notice) {
        if (notice == null || notice.id == null || done.contains(notice.id)) return false;
        drop(notice.id);
        int at = 0;
        while (at < waiting.size() && waiting.get(at).priority <= notice.priority) at++;
        waiting.add(at, notice);
        return true;
    }

    public synchronized Notice<S> next(S state) {
        if (open != null) return null;
        Iterator<Notice<S>> it = waiting.iterator();
        while (it.hasNext()) {
            Notice<S> notice = it.next();
            if (notice.condition != null && notice.condition.test(state)) return notice;
            it.remove();
        }
        return null;
    }

    public synchronized void shown(String id) {
        if (id == null) return;
        done.add(id);
        drop(id);
    }

    public synchronized void opened(String id) {
        shown(id);
        if (id != null) open = id;
    }

    public synchronized void closed(String id) {
        if (id != null && id.equals(open)) open = null;
    }

    public synchronized void dismissed(String id) {
        shown(id);
        closed(id);
    }

    public synchronized boolean isOpen(String id) {
        return id != null && id.equals(open);
    }

    public synchronized boolean finished(String id) {
        return done.contains(id);
    }

    public synchronized void reset() {
        waiting.clear();
        done.clear();
        open = null;
    }

    private void drop(String id) {
        waiting.removeIf(n -> n.id.equals(id));
    }

    public static final class WhatChanged {

        public static final String ID = "whatChanged";
        public static final String SEEN = "__whatChangedSeen";
        public static final String FROM = "__whatChangedFrom";
        public static final String APP_SEEN = "whatChangedSeen";
        public static final String APP_FROM = "whatChangedFrom";
        public static final String FOLLOWERS_TRACE = "__followersHistoryWasOn";
        public static final String HISTORY_FILE_PREFIX = "ie_followers_history_";
        public static final String UPGRADE = "upgrade";
        public static final String FRESH = "fresh";
        private static final String APPLIED = "__applied_";

        public enum Line { MENU, SPOOF_LAST_SEEN, FOLLOWERS_HISTORY }

        private WhatChanged() {}

        static boolean earlierSettings(Map<String, ?> stored) {
            if (stored == null) return false;
            for (String key : stored.keySet()) {
                if (key == null || key.startsWith("__")) continue;
                if (!stored.containsKey(APPLIED + key)) return true;
            }
            return false;
        }

        static boolean followersHistory(Map<String, ?> stored, BooleanSupplier historyFile) {
            if (stored != null && (Boolean.TRUE.equals(stored.get("followersHistory"))
                    || Boolean.TRUE.equals(stored.get(FOLLOWERS_TRACE)))) return true;
            return historyFile != null && historyFile.getAsBoolean();
        }

        static boolean upgraded(Map<String, ?> stored, BooleanSupplier historyFile) {
            return earlierSettings(stored) || SpoofLastSeenNotice.shouldShow(stored) || followersHistory(stored, historyFile);
        }

        public static String from(SharedPreferences verdicts, String key, Supplier<Map<String, ?>> stored,
                                  BooleanSupplier historyFile) {
            if (verdicts == null) return null;
            String from = verdicts.getString(key, null);
            if (from != null) return from;
            from = upgraded(stored != null ? stored.get() : null, historyFile) ? UPGRADE : FRESH;
            verdicts.edit().putString(key, from).apply();
            return from;
        }

        public static boolean due(String from, boolean seen) {
            return UPGRADE.equals(from) && !seen;
        }

        public static List<Line> lines(Map<String, ?> stored, BooleanSupplier historyFile) {
            List<Line> out = new ArrayList<>();
            out.add(Line.MENU);
            if (SpoofLastSeenNotice.shouldShow(stored)) out.add(Line.SPOOF_LAST_SEEN);
            if (followersHistory(stored, historyFile)) out.add(Line.FOLLOWERS_HISTORY);
            return out;
        }

        public static boolean historyFile(String[] names) {
            if (names == null) return false;
            for (String name : names) if (name != null && name.startsWith(HISTORY_FILE_PREFIX)) return true;
            return false;
        }
    }
}
