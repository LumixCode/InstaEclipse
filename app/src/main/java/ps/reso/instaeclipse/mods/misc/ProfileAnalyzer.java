package ps.reso.instaeclipse.mods.misc;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.dialog.IeDialog;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.toast.IeToast;
import ps.reso.instaeclipse.utils.users.WatchlistStore;

/**
 * Profile analyzer: watch another profile's Followers and Following lists for changes.
 *
 * <p>"Analyze profile" in a profile's ⋮ menu (or in the tools button of a profile's follow list
 * screen, FollowListToolsIcon) starts an analysis of that profile. From the menu, the profile
 * header's followers count is tapped to open the follow list screen. A driver then waits for the
 * profile's Followers tab to be on screen and loads it to the end with IG's own pager
 * (FollowListLoader, the same as Export CSV), switches to the Following tab (the
 * UnifiedFollowFragment's ViewPager2) and loads it too. Only when both lists loaded to the end
 * (every extra pass included) are they compared with the last snapshot (WatchlistStore, with how
 * each load went: passes, repeats, Instagram's count, limits) and the result shown, with
 * Instagram's own counts next to what it listed when it listed fewer (it leaves out e.g.
 * deactivated or hidden accounts), how a list was compared when it wasn't compared as usual, and
 * how many accounts weren't listed this time (not counted as gone yet). A private account you
 * don't follow has no list to open, so the analysis says so instead. When Instagram refuses a load
 * (rate limit, challenge, action block) the analysis ends at once, the other list isn't loaded and
 * nothing is saved; while loading lists pauses after such a block, the ⋮ menu's "Analyze profile"
 * warns first (FollowListLoader.pause). Saved checks are shown by the changelog (ProfileChangelog),
 * also from the result and the Watchlist.
 */
public final class ProfileAnalyzer {

    private static final String TAG = "(IE|ProfileAnalyzer) ";
    private static final int GREEN = 0xFF34C759, RED = 0xFFFF453A;
    /** how often the driver looks for the profile's list while it isn't loading one */
    private static final long POLL_MS = 300;
    private static final Handler H = new Handler(Looper.getMainLooper());

    /** One analysis in progress. */
    private static final class Session {
        final String id;
        String username, self;
        final boolean fromMenu;
        final long started = SystemClock.uptimeMillis();
        long lastSeen, warmSince, nextTabAt;
        boolean seen, busy, hinted;
        int tabTries;
        Map<String, String[]> followers, following;
        /** how each list's load went (passes, repeats, Instagram's count, limited, hidden accounts) */
        WatchlistStore.ListCheck followersLoad, followingLoad;
        Context ctx;

        Session(String id, String username, boolean fromMenu) {
            this.id = id;
            this.username = username;
            this.fromMenu = fromMenu;
        }

        String name() { return username != null ? username : id; }
    }

    private static volatile Session session;

    private ProfileAnalyzer() {}

    // ---- entry points ----

    /** "Analyze profile" in the ⋮ menu of {@code username}'s profile ({@code user}: its User model). */
    public static void start(Activity a, Object user, String username) {
        if (!FeatureFlags.profileAnalyzer || a == null) return;
        String id = user != null ? FollowListExportHook.idOf(user) : null;
        if (id == null) {
            IeToast.makeText(a, I18n.t(a, R.string.ig_pa_no_id), Toast.LENGTH_SHORT).show();
            return;
        }
        Context app = a.getApplicationContext();
        String self = WatchlistStore.self(app);
        // loading lists pauses after Instagram blocked a load: warn, run only on "Load anyway"
        FollowListPolicy.Block paused = FollowListLoader.pause(a, self);
        if (paused != null) {
            ModuleLog.detail(TAG + "analyze from menu during the pause after " + paused.kind + ": warned first");
            FollowListLoader.warnPaused(a, paused, () -> startNow(a, app, self, id, username));
            return;
        }
        startNow(a, app, self, id, username);
    }

    private static void startNow(Activity a, Context app, String self, String id, String username) {
        if (self != null) new Thread(() -> WatchlistStore.add(app, self, id, username)).start();
        Session s = begin(a, id, username, true);
        if (!openFollowers(a)) {
            ModuleLog.warn(TAG + "Could not open the followers list of @" + username + " (its followers count is not shown or cannot be tapped)");
            end(s);
            cantOpen(a, s.name());
        }
    }

    private static Session begin(Context ctx, String id, String username, boolean fromMenu) {
        Session s = new Session(id, username, fromMenu);
        s.ctx = ctx;
        session = s;
        ModuleLog.line(TAG + "Analyzing @" + username + " (" + id + ")" + (fromMenu ? ", started from the profile menu" : ", started from the list"));
        H.postDelayed(() -> drive(s), fromMenu ? 900 : 0);
        return s;
    }

    private static void end(Session s) {
        if (session == s) session = null;
    }

    /** "Analyze profile" in the follow list screen's tools button ({@code info}: the tab on screen). */
    static void startFromList(View v, FollowListExportHook.ListInfo info) {
        if (!FeatureFlags.profileAnalyzer) return;
        if (info == null) {
            IeToast.makeText(v.getContext(), I18n.t(v.getContext(), R.string.ig_pa_wrong_tab), Toast.LENGTH_SHORT).show();
            return;
        }
        Session cur = session;
        if (cur != null && cur.id.equals(info.owner)) return; // already analyzing this profile
        String name = info.username != null ? info.username : titleOf(v);
        if (name == null && info.self != null) {
            WatchlistStore.Entry e = WatchlistStore.find(WatchlistStore.loadIndex(v.getContext().getApplicationContext(), info.self), info.owner);
            if (e != null) name = e.username;
        }
        begin(v.getContext(), info.owner, name, false);
    }

    // ---- the driver: wait for the profile's lists, load each to the end ----

    private static void drive(Session s) {
        if (session != s || s.busy) return;
        long now = SystemClock.uptimeMillis();
        if (now - s.started > 15 * 60_000L) { end(s); return; }
        Object[] vis = FollowListExportHook.visibleList();
        FollowListExportHook.ListInfo info = vis != null ? FollowListExportHook.listInfo(vis[0]) : null;
        if (info == null || !s.id.equals(info.owner)) {
            if (!s.seen && now - s.started > 9000) {
                // the list never opened: a private profile you don't follow, or the tap missed
                end(s);
                if (s.fromMenu) cantOpen(s.ctx, s.name());
                return;
            }
            if (s.seen && now - s.lastSeen > 20000) {
                end(s);
                toast(s.ctx, I18n.t(s.ctx, R.string.ig_pa_left));
                return;
            }
            H.postDelayed(() -> drive(s), POLL_MS);
            return;
        }
        View root = (View) vis[1];
        s.ctx = root.getContext();
        s.seen = true;
        s.lastSeen = now;
        if (s.self == null) s.self = info.self;
        if (s.username == null) s.username = info.username != null ? info.username : titleOf(root);

        String type = info.type != null && missing(s, info.type) ? info.type : null;
        if (type == null) {
            // a tab that isn't needed (mutual, suggested, or the list already loaded): go to the missing one
            String want = s.followers == null ? FollowListExportHook.FOLLOWERS : FollowListExportHook.FOLLOWING;
            if (now >= s.nextTabAt) {
                if (s.tabTries < 3 && FollowListExportHook.showTab(vis[0], want)) {
                    s.tabTries++;
                    s.nextTabAt = now + 1500;
                } else if (!s.hinted) {
                    s.hinted = true;
                    toast(s.ctx, I18n.t(s.ctx, R.string.ig_pa_switch_tab, label(s.ctx, want)));
                }
            }
            H.postDelayed(() -> drive(s), POLL_MS);
            return;
        }

        // the tab may still be fetching its first page: wait until accounts are in (or a short while;
        // the loader itself also waits for a first page and retries a failed one)
        Object frag = vis[0];
        if (FollowListLoader.accountsShown(root) == 0) {
            if (s.warmSince == 0) s.warmSince = now;
            if (now - s.warmSince < 12000) { H.postDelayed(() -> drive(s), POLL_MS); return; }
        }
        s.warmSince = 0;
        s.busy = true;
        String title = I18n.t(s.ctx, R.string.ig_pa_loading_title, s.name(), label(s.ctx, type));
        if (!FollowListLoader.loadAll(root, frag, title, r -> loaded(s, type, r))) {
            s.busy = false; // another list is loading (an export): try again shortly
            H.postDelayed(() -> drive(s), 1000);
        }
    }

    private static boolean missing(Session s, String type) {
        return FollowListExportHook.FOLLOWERS.equals(type) ? s.followers == null : s.following == null;
    }

    private static void loaded(Session s, String type, FollowListLoader.Result r) {
        s.busy = false;
        if (session != s) return;
        // Instagram refused the load (rate limit, challenge, action block): the analysis ends here,
        // the other list isn't loaded and nothing is saved
        if (r.blocked != null) {
            end(s);
            ModuleLog.warn(TAG + "Analysis stopped: Instagram blocked loading the " + type + " list (" + r.blocked + "), nothing was saved");
            FollowListLoader.showBlocked(s.ctx, r.blocked);
            return;
        }
        Map<String, String[]> users = new LinkedHashMap<>();
        if (r.complete) {
            for (Map.Entry<String, String[]> e : r.users.entrySet())
                if (!e.getKey().equals(s.id)) users.put(e.getKey(), e.getValue());
        }
        ModuleLog.line(TAG + type + " loaded: " + users.size() + (r.expected >= 0 ? " of " + r.expected : "")
                + (r.complete ? "" : " (incomplete)") + (r.interrupted ? " (an extra pass ended early)" : ""));
        // an empty list only counts when Instagram's own count says 0 (the loader checked it); an
        // extra pass that ended early (stopped, left the list, a failed page) leaves a list as
        // incomplete as a first pass that did, so nothing is saved either
        if (!r.complete || r.interrupted || (users.isEmpty() && r.expected != 0)) {
            end(s);
            new IeDialog(s.ctx).title("@" + s.name()).message(I18n.t(s.ctx, R.string.ig_pa_partial)).show();
            return;
        }
        if (FollowListExportHook.FOLLOWERS.equals(type)) {
            s.followers = users;
            s.followersLoad = load(r);
        } else {
            s.following = users;
            s.followingLoad = load(r);
        }
        s.tabTries = 0;
        s.hinted = false;
        s.nextTabAt = 0;
        if (s.followers != null && s.following != null) {
            end(s);
            finish(s);
            return;
        }
        String next = s.followers == null ? FollowListExportHook.FOLLOWERS : FollowListExportHook.FOLLOWING;
        toast(s.ctx, I18n.t(s.ctx, R.string.ig_pa_next, users.size(), label(s.ctx, type), label(s.ctx, next)));
        s.lastSeen = SystemClock.uptimeMillis();
        H.post(() -> drive(s));
    }

    /** How a list's load went, as the watchlist keeps it with the check. */
    private static WatchlistStore.ListCheck load(FollowListLoader.Result r) {
        WatchlistStore.ListCheck q = new WatchlistStore.ListCheck();
        q.received = r.received;
        q.repeated = r.repeated;
        q.passes = r.passes;
        q.lastPassAdded = r.lastPassAdded;
        q.f1 = r.f1;
        q.f2 = r.f2;
        q.estMissing = r.estMissing;
        q.expected = r.expected;
        q.hidden = Math.max(0, r.hiddenFollowing);
        q.limited = r.limited;
        return q;
    }

    private static void finish(Session s) {
        Context ctx = s.ctx;
        Context app = ctx.getApplicationContext();
        new Thread(() -> {
            String self = s.self != null ? s.self : WatchlistStore.self(app);
            if (self == null) self = "0";
            WatchlistStore.Profile p = WatchlistStore.loadProfile(app, self, s.id);
            if (s.username != null) p.username = s.username;
            // what this analysis is compared with (apply replaces it)
            String before = p.lastCheck > 0
                    ? " since " + logTime(p.lastCheck) + " (then " + p.followers.size() + "/" + p.following.size()
                    + " listed, " + p.missingFollowers.size() + "/" + p.missingFollowing.size() + " not listed, Instagram "
                    + countThen(p, p.followersState, p.baseFollowers) + "/" + countThen(p, p.followingState, p.baseFollowing) + ")"
                    : "";
            long now = System.currentTimeMillis();
            WatchlistStore.Result r = WatchlistStore.apply(p, s.followers, s.following, s.followersLoad, s.followingLoad, now);
            String error = null;
            try {
                WatchlistStore.saveProfile(app, self, p);
                List<WatchlistStore.Entry> index = WatchlistStore.loadIndex(app, self);
                WatchlistStore.touch(index, p, now);
                WatchlistStore.saveIndex(app, self, index);
            } catch (Throwable t) {
                error = t.toString();
                ModuleLog.error(TAG + "Could not save the analysis", t);
            }
            ModuleLog.line(TAG + "@" + s.name() + ": " + r.followers + " followers, " + r.following + " following, "
                    + r.added.size() + " changes" + (r.first ? " (first)" : "")
                    + " (gained " + r.followersList.gained + uncertain(r.followersList) + ", lost " + r.followersList.gone
                    + ", B followed " + r.followingList.gained + uncertain(r.followingList) + ", B unfollowed " + r.followingList.gone
                    + "; not listed " + r.followersList.notListed + "/" + r.followingList.notListed + ")"
                    + before + ", Instagram now " + r.followersList.expected + "/" + r.followingList.expected);
            ModuleLog.detail(TAG + listLine(FollowListExportHook.FOLLOWERS, r.followersList));
            ModuleLog.detail(TAG + listLine(FollowListExportHook.FOLLOWING, r.followingList));
            final String err = error, me = self;
            H.post(() -> showResult(ctx, me, p, r, err));
        }).start();
    }

    /** Instagram's count for a saved list when it was listed: its own, else the header's at the
     *  last analysis; "?" when neither is known. */
    private static String countThen(WatchlistStore.Profile p, WatchlistStore.ListState st, int header) {
        if (st.expected >= 0) return String.valueOf(st.expected);
        return p.baseAt > 0 && p.baseAt == p.lastCheck && header >= 0 ? String.valueOf(header) : "?";
    }

    private static String uncertain(WatchlistStore.ListCheck q) {
        return q.uncertain > 0 ? " (" + q.uncertain + " uncertain)" : "";
    }

    /** One list's load and comparison, for the log. */
    static String listLine(String type, WatchlistStore.ListCheck q) {
        StringBuilder sb = new StringBuilder(type).append(": received ").append(q.received).append(", repeated ").append(q.repeated)
                .append(", distinct ").append(q.distinct).append(", passes ").append(q.passes);
        if (q.lastPassAdded >= 0) sb.append(", last pass +").append(q.lastPassAdded);
        if (q.passes > 1) sb.append(", f1 ").append(q.f1).append(", f2 ").append(q.f2)
                .append(String.format(java.util.Locale.ROOT, ", est. missing %.1f", q.estMissing));
        sb.append(", Instagram ").append(q.expected);
        if (q.hidden > 0) sb.append(" (hides ").append(q.hidden).append(')');
        if (q.limited) sb.append(", limited");
        sb.append(": ").append(q.complete ? "complete" : "incomplete").append(" | ").append(q.outcome);
        if (q.prevListed >= 0) sb.append(" (saved ").append(q.prevListed).append(" of ").append(q.prevCount).append(')');
        sb.append(": gained ").append(q.gained).append(" (uncertain ").append(q.uncertain).append("), gone ").append(q.gone)
                .append(", not listed ").append(q.notListed).append(" (new ").append(q.newlyMissing).append("), relisted ")
                .append(q.relisted);
        if (q.filled > 0) sb.append(", filled in ").append(q.filled);
        return sb.toString();
    }

    private static String logTime(long ts) {
        return new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.ROOT).format(new Date(ts));
    }

    // ---- opening the profile's followers list from its header ----

    /** Taps the profile header's followers count (Instagram opens the follow list screen). */
    @SuppressLint("DiscouragedApi")
    private static boolean openFollowers(Activity a) {
        try {
            android.content.res.Resources res = a.getResources();
            String pkg = a.getPackageName();
            View decor = a.getWindow().getDecorView();
            for (String n : new String[]{"profile_header_followers_stacked_familiar", "profile_header_familiar_followers_value",
                    "profile_header_familiar_followers_label", "row_profile_header_followers_container",
                    "row_profile_header_textview_followers_count"}) {
                int id = res.getIdentifier(n, "id", pkg);
                View v = id != 0 ? decor.findViewById(id) : null;
                if (v != null && v.isShown() && tapUp(v)) { ModuleLog.detail(TAG + "opened followers via " + n); return true; }
            }
            // any shown view whose id names the followers count
            ArrayDeque<View> st = new ArrayDeque<>();
            st.push(decor);
            while (!st.isEmpty()) {
                View v = st.pop();
                if (!v.isShown()) continue;
                if (v.getId() != View.NO_ID && v.getId() > 0x7f000000) {
                    try {
                        String n = res.getResourceEntryName(v.getId());
                        if (n.contains("followers") && !n.contains("follow_context") && tapUp(v)) {
                            ModuleLog.detail(TAG + "opened followers via " + n);
                            return true;
                        }
                    } catch (Throwable ignored) {}
                }
                if (v instanceof ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) st.push(g.getChildAt(i));
            }
        } catch (Throwable t) {
            ModuleLog.detail(TAG + "open followers: " + t);
        }
        return false;
    }

    /** Clicks the nearest view (itself or a parent, a few levels up) that has a click listener. */
    private static boolean tapUp(View v) {
        View c = v;
        for (int i = 0; i < 4 && c != null; i++, c = c.getParent() instanceof View p ? p : null)
            if (c.isClickable() && c.hasOnClickListeners() && c.isEnabled()) return c.performClick();
        return false;
    }

    private static void cantOpen(Context ctx, String name) {
        try {
            new IeDialog(ctx).title(I18n.t(ctx, R.string.ig_pa_analyze))
                    .message(I18n.t(ctx, R.string.ig_pa_cant_open, name)).show();
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "Could not show why the analysis could not start", t);
        }
    }

    // ---- result, watchlist ----

    /** The result's sections of changes, in the order shown (not-listed accounts are a count in
     *  its text; the changelog lists them). */
    private static final ProfileChangelogPolicy.Section[] RESULT_SECTIONS = {
            ProfileChangelogPolicy.Section.B_FOLLOWED, ProfileChangelogPolicy.Section.B_FOLLOWED_MAYBE,
            ProfileChangelogPolicy.Section.B_UNFOLLOWED, ProfileChangelogPolicy.Section.GAINED,
            ProfileChangelogPolicy.Section.GAINED_MAYBE, ProfileChangelogPolicy.Section.LOST};

    private static void showResult(Context ctx, String self, WatchlistStore.Profile p, WatchlistStore.Result r, String error) {
        try {
            DateFormat df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
            String name = p.username != null ? p.username : p.id;
            IeDialog dlg = new IeDialog(ctx).title("@" + name);
            String head;
            if (r.first) head = I18n.t(ctx, R.string.ig_pa_first, r.followers, r.following);
            else {
                head = I18n.t(ctx, R.string.ig_pa_since, df.format(new Date(r.previousCheck)))
                        + "\n" + I18n.t(ctx, R.string.ig_pa_counts, r.followers, r.following);
                if (r.added.isEmpty()) head += "\n\n" + I18n.t(ctx, R.string.ig_pa_no_changes);
            }
            // what Instagram listed short of its counts, how a list was compared when not as usual,
            // and how many accounts weren't listed this time
            String notes = resultNotes(ctx, r);
            if (!notes.isEmpty()) head += "\n" + notes;
            if (error != null) head += "\n⚠️ " + error;
            dlg.message(head);
            if (!r.first && !r.added.isEmpty()) {
                for (ProfileChangelogPolicy.Section s : RESULT_SECTIONS) {
                    List<WatchlistStore.Event> events = new java.util.ArrayList<>();
                    for (WatchlistStore.Event e : r.added) if (ProfileChangelogPolicy.section(e) == s) events.add(e);
                    section(dlg, ctx, I18n.t(ctx, sectionTitle(s), name, events.size()), events,
                            s.gain() ? GREEN : s.loss() ? RED : dlg.sub);
                }
                hint(dlg, ctx);
            }
            dlg.secondary(I18n.t(ctx, R.string.ig_pa_changelog), () -> ProfileChangelog.show(ctx, self, p.id, name));
            dlg.show();
        } catch (Throwable t) {
            ModuleLog.error(TAG + "Could not show the analysis result", t);
        }
    }

    /** A section's heading text (args: the profile's username, the count). */
    static int sectionTitle(ProfileChangelogPolicy.Section s) {
        return switch (s) {
            case GAINED -> R.string.ig_pa_sec_gained;
            case GAINED_MAYBE -> R.string.ig_pa_sec_gained_maybe;
            case LOST -> R.string.ig_pa_sec_lost;
            case NOT_LISTED -> R.string.ig_pa_sec_not_listed;
            case B_FOLLOWED -> R.string.ig_pa_sec_b_followed;
            case B_FOLLOWED_MAYBE -> R.string.ig_pa_sec_b_followed_maybe;
            case B_UNFOLLOWED -> R.string.ig_pa_sec_b_unfollowed;
            case B_NOT_LISTED -> R.string.ig_pa_sec_b_not_listed;
        };
    }

    private static void section(IeDialog dlg, Context ctx, String title, List<WatchlistStore.Event> events, int color) {
        if (events.isEmpty()) return;
        TextView h = new TextView(dlg.ctx);
        h.setText(title);
        h.setTextColor(color);
        h.setTextSize(14);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setPadding(0, dlg.dp(8), 0, dlg.dp(2));
        dlg.view(h);
        LinearLayout rows = new LinearLayout(dlg.ctx);
        rows.setOrientation(LinearLayout.VERTICAL);
        int shown = 0;
        for (WatchlistStore.Event e : events) {
            if (shown++ >= 300) break;
            SpannableStringBuilder sb = new SpannableStringBuilder("@").append(e.username != null ? e.username : e.id);
            sb.setSpan(new StyleSpan(Typeface.BOLD), 0, sb.length(), 0);
            if (e.fullName != null && !e.fullName.isEmpty()) {
                int st = sb.length();
                sb.append("  ").append(e.fullName);
                sb.setSpan(new ForegroundColorSpan(dlg.sub), st, sb.length(), 0);
            }
            rows.addView(userRow(dlg, ctx, sb, e.username));
        }
        dlg.view(rows);
    }

    /** A user row: tap opens the profile in Instagram, hold copies the username. */
    private static TextView userRow(IeDialog dlg, Context ctx, CharSequence text, String username) {
        TextView row = new TextView(dlg.ctx);
        row.setText(text);
        row.setTextColor(dlg.text);
        row.setTextSize(14);
        row.setPadding(0, dlg.dp(5), 0, dlg.dp(5));
        if (username != null) {
            row.setOnClickListener(v -> openProfile(ctx, username));
            row.setOnLongClickListener(v -> { copy(ctx, username); return true; });
        }
        return row;
    }

    private static void hint(IeDialog dlg, Context ctx) {
        TextView note = new TextView(dlg.ctx);
        note.setText(I18n.t(ctx, R.string.ig_pa_tap_hint) + "\n" + I18n.t(ctx, R.string.ig_followers_history_note));
        note.setTextColor(dlg.sub);
        note.setTextSize(12);
        dlg.view(note);
    }

    /** The in-app menu's Watchlist: watched profiles with their last analysis; tap opens the changelog. */
    public static void showWatchlist(Context ctx) {
        Context app = ctx.getApplicationContext();
        new Thread(() -> {
            String self = WatchlistStore.self(app);
            List<WatchlistStore.Entry> index = self != null ? WatchlistStore.loadIndex(app, self) : new java.util.ArrayList<>();
            H.post(() -> showWatchlist(ctx, self, index));
        }).start();
    }

    private static void showWatchlist(Context ctx, String self, List<WatchlistStore.Entry> index) {
        try {
            DateFormat df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
            IeDialog dlg = new IeDialog(ctx).title(I18n.t(ctx, R.string.ig_pa_watchlist));
            if (index.isEmpty()) {
                dlg.message(I18n.t(ctx, R.string.ig_pa_watch_empty));
                dlg.show();
                return;
            }
            dlg.message(I18n.t(ctx, R.string.ig_pa_watch_hint));
            for (WatchlistStore.Entry e : index) {
                String name = e.username != null ? e.username : e.id;
                SpannableStringBuilder sb = new SpannableStringBuilder("@" + name);
                sb.setSpan(new StyleSpan(Typeface.BOLD), 0, sb.length(), 0);
                int st = sb.length();
                sb.append("\n").append(e.lastCheck > 0
                        ? I18n.t(ctx, R.string.ig_pa_watch_row, e.followers, e.following, df.format(new Date(e.lastCheck)))
                        : I18n.t(ctx, R.string.ig_pa_never));
                if (e.events > 0) sb.append(" · ").append(I18n.t(ctx, R.string.ig_pa_watch_changes, e.events));
                sb.setSpan(new ForegroundColorSpan(dlg.sub), st, sb.length(), 0);
                sb.setSpan(new RelativeSizeSpan(0.85f), st, sb.length(), 0);
                TextView row = new TextView(dlg.ctx);
                row.setText(sb);
                row.setTextColor(dlg.text);
                row.setTextSize(15);
                row.setPadding(dlg.dp(14), dlg.dp(10), dlg.dp(14), dlg.dp(10));
                row.setBackground(dlg.cardBg());
                row.setOnClickListener(v -> ProfileChangelog.show(ctx, self, e.id, e.username));
                dlg.view(row);
            }
            dlg.show();
        } catch (Throwable t) {
            ModuleLog.error(TAG + "Could not show the watchlist", t);
        }
    }

    // ---- helpers ----

    static String label(Context ctx, String type) {
        return I18n.t(ctx, FollowListExportHook.FOLLOWERS.equals(type) ? R.string.ig_pa_followers : R.string.ig_pa_following);
    }

    /**
     * The lines about what Instagram listed, each starting with a line break ("" when there is
     * nothing to say): "Instagram listed X of N" for each list it listed short, then a note per list
     * it flagged as limited, and the deactivated / hidden accounts note when a short list wasn't.
     */
    static String listedNotes(Context ctx, String[] types, int[] listed, int[] expected, boolean[] limited) {
        StringBuilder lines = new StringBuilder(), notes = new StringBuilder();
        boolean shortNote = false;
        for (int i = 0; i < types.length; i++) {
            if (FollowListPolicy.shortOfExpected(listed[i], expected[i]))
                lines.append('\n').append(I18n.t(ctx, R.string.ig_pa_listed, label(ctx, types[i]), listed[i], expected[i]));
            switch (FollowListPolicy.note(listed[i], expected[i], limited[i])) {
                case LIMITED -> notes.append('\n').append(I18n.t(ctx, R.string.ig_pa_limited_note, label(ctx, types[i])));
                case SHORT -> shortNote = true;
                default -> {}
            }
        }
        if (shortNote) notes.append('\n').append(I18n.t(ctx, R.string.ig_pa_listed_note));
        return lines.toString() + notes;
    }

    /**
     * The analyzer result's lines, each starting with a line break ("" when there is nothing to
     * say): "Instagram listed X of N" for each list it listed short, how a list was compared when
     * not as usual and the accounts Instagram hides ({@link #compareNotes}), the deactivated /
     * hidden accounts note when a list is short by more than the accounts Instagram says it hides
     * (and isn't limited), then per list how many accounts weren't listed this time (not counted
     * as gone yet).
     */
    static String resultNotes(Context ctx, WatchlistStore.Result r) {
        String[] types = {FollowListExportHook.FOLLOWERS, FollowListExportHook.FOLLOWING};
        int[] listed = {r.followers, r.following};
        WatchlistStore.ListCheck[] q = {r.followersList, r.followingList};
        StringBuilder sb = new StringBuilder();
        boolean shortNote = false;
        for (int i = 0; i < types.length; i++) {
            int expected = q[i] != null ? q[i].expected : -1, hidden = q[i] != null ? q[i].hidden : 0;
            if (FollowListPolicy.shortOfExpected(listed[i], expected))
                sb.append('\n').append(I18n.t(ctx, R.string.ig_pa_listed, label(ctx, types[i]), listed[i], expected));
            if (FollowListPolicy.note(listed[i] + hidden, expected, q[i] != null && q[i].limited) == FollowListPolicy.Note.SHORT)
                shortNote = true;
        }
        sb.append(compareNotes(ctx, r.followersList, r.followingList));
        if (shortNote) sb.append('\n').append(I18n.t(ctx, R.string.ig_pa_listed_note));
        for (int i = 0; i < types.length; i++)
            if (q[i] != null && q[i].notListed > 0)
                sb.append('\n').append(I18n.t(ctx, R.string.ig_pa_not_listed, label(ctx, types[i]), q[i].notListed));
        return sb.toString();
    }

    /**
     * Per list (null: not recorded), each starting with a line break: how it was compared when not
     * as usual (a new baseline because the saved list was clearly less complete, the saved list
     * kept because this load was, a list Instagram limits: additions only or nothing compared, a
     * baseline after a limited list) and how many accounts Instagram says it hides from it.
     */
    static String compareNotes(Context ctx, WatchlistStore.ListCheck followers, WatchlistStore.ListCheck following) {
        StringBuilder sb = new StringBuilder();
        String[] types = {FollowListExportHook.FOLLOWERS, FollowListExportHook.FOLLOWING};
        WatchlistStore.ListCheck[] q = {followers, following};
        for (int i = 0; i < types.length; i++) {
            if (q[i] == null) continue;
            String label = label(ctx, types[i]);
            if (q[i].outcome != null) {
                String note = switch (q[i].outcome) {
                    case REBASELINED -> I18n.t(ctx, R.string.ig_pa_rebaselined, label, q[i].prevListed, q[i].prevCount);
                    case KEPT_SAVED -> I18n.t(ctx, R.string.ig_pa_kept_saved, label);
                    case ADDITIONS -> I18n.t(ctx, R.string.ig_pa_limited_adds, label);
                    case NOT_COMPARED -> I18n.t(ctx, R.string.ig_pa_limited_none, label);
                    case BASELINE -> I18n.t(ctx, R.string.ig_pa_new_baseline, label);
                    default -> null;
                };
                if (note != null) sb.append('\n').append(note);
            }
            if (q[i].hidden > 0) sb.append('\n').append(I18n.t(ctx, R.string.ig_pa_hidden_note, label, q[i].hidden));
        }
        return sb.toString();
    }

    /** The follow list screen's title (the profile's username). */
    @SuppressLint("DiscouragedApi")
    static String titleOf(View v) {
        try {
            int id = v.getResources().getIdentifier("action_bar_title", "id", v.getContext().getPackageName());
            View t = id != 0 ? v.getRootView().findViewById(id) : null;
            if (t instanceof TextView tv && tv.getText() != null) {
                String s = tv.getText().toString().trim();
                if (s.matches("[A-Za-z0-9._]{1,30}")) return s;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static void openProfile(Context ctx, String username) {
        try {
            ctx.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com/" + username + "/"))
                    .setPackage(ctx.getPackageName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
            ModuleLog.error(TAG + "Could not open the profile: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void copy(Context ctx, String text) {
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText("InstaEclipse", text));
        toast(ctx, I18n.t(ctx, R.string.ig_toast_comment_copied));
    }

    private static void toast(Context ctx, String text) {
        try { IeToast.makeText(ctx, text, Toast.LENGTH_LONG).show(); } catch (Throwable ignored) {}
    }
}
