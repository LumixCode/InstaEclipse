package ps.reso.instaeclipse.mods.misc;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.dialog.IeDialog;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.text.CopyFeedback;
import ps.reso.instaeclipse.utils.toast.IeToast;
import ps.reso.instaeclipse.utils.users.WatchlistStore;

/**
 * A watched profile's changelog, the only view of what the profile analyzer saved: "Changelog" in
 * the follow list screen's tools menu (FollowListToolsIcon) once the analyzer saved a check of that
 * profile, the analyzer result's Changelog button and a profile's row in the in-app Watchlist.
 *
 * <p>Every saved check, newest first (ProfileChangelogPolicy): when it ran, what it was compared
 * with, how many accounts each list had and Instagram's own counts (where saved), notes on how a
 * list was compared (a new baseline, a kept list, a limited or partly hidden list), then the
 * accounts that newly followed / stopped following the profile and that it started / stopped
 * following. New accounts that may only have been missed by the check before are in their own
 * section, and accounts a check didn't list that don't count as gone yet are shown in grey ("not
 * listed in this analysis, unconfirmed"); a loss confirmed after a while says when the account was
 * last listed. Rows are added a page at a time ("Show more"), so thousands of changes open quickly.
 * Tap an account to open it in Instagram (its own https://www.instagram.com/&lt;username&gt;/ link,
 * handled by Instagram), hold it to copy the username.
 *
 * <p>A check with changes offers "Mark these changes as unreliable (keep the lists)": after the
 * user confirms, its changes leave the log (WatchlistStore.markUnreliable); the saved lists stay,
 * an older list is never restored. The dialog also removes the profile from the watchlist and
 * opens it.
 */
final class ProfileChangelog {

    private static final String TAG = "(IE|Changelog) ";
    /** as the analyzer's result */
    private static final int GREEN = 0xFF34C759, RED = 0xFFFF453A;
    private static final Handler H = new Handler(Looper.getMainLooper());

    private ProfileChangelog() {}

    /** When the watchlist of {@code self} last saved a check of {@code id} (0: never). Reads a file. */
    static long lastCheck(Context app, String self, String id) {
        WatchlistStore.Entry e = WatchlistStore.find(WatchlistStore.loadIndex(app, self), id);
        return e != null ? e.lastCheck : 0;
    }

    /** Opens the changelog of profile {@code id} from the watchlist of {@code self}; {@code name}:
     *  its username when the profile file has none. */
    static void show(Context ctx, String self, String id, String name) {
        Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                WatchlistStore.Profile p = WatchlistStore.loadProfile(app, self, id);
                List<ProfileChangelogPolicy.Group> groups = ProfileChangelogPolicy.groups(p, WatchlistStore.maxEvents());
                List<ProfileChangelogPolicy.Row> rows = ProfileChangelogPolicy.rows(groups);
                ModuleLog.detail(TAG + id + ": " + groups.size() + " checks, " + WatchlistStore.changeCount(p.events)
                        + " changes, " + p.events.size() + " entries, " + rows.size() + " rows");
                H.post(() -> show(ctx, self, p, groups, rows, name));
            } catch (Throwable t) {
                ModuleLog.error(TAG + "Could not open the changelog", t);
            }
        }, "ie-changelog").start();
    }

    private static void show(Context ctx, String self, WatchlistStore.Profile p, List<ProfileChangelogPolicy.Group> groups,
                             List<ProfileChangelogPolicy.Row> rows, String fallbackName) {
        try {
            String name = p.username != null ? p.username : fallbackName != null ? fallbackName : p.id;
            IeDialog dlg = new IeDialog(ctx).title("@" + name + " · " + I18n.t(ctx, R.string.ig_pa_changelog));
            if (groups.isEmpty()) {
                dlg.message(I18n.t(ctx, R.string.ig_pa_cl_empty));
            } else {
                dlg.message(I18n.t(ctx, R.string.ig_pa_cl_summary, groups.size(), WatchlistStore.changeCount(p.events)));
                LinearLayout list = new LinearLayout(dlg.ctx);
                list.setOrientation(LinearLayout.VERTICAL);
                dlg.view(list);
                Page page = new Page(dlg, ctx, self, p, list, rows, name, fallbackName);
                page.append(0);
                TextView hint = new TextView(dlg.ctx);
                hint.setText(I18n.t(ctx, R.string.ig_pa_tap_hint) + "\n" + I18n.t(ctx, R.string.ig_followers_history_note));
                hint.setTextColor(dlg.sub);
                hint.setTextSize(12);
                dlg.view(hint);
            }
            Context app = ctx.getApplicationContext();
            dlg.secondary(I18n.t(ctx, R.string.ig_pa_remove), () -> new Thread(() -> {
                WatchlistStore.remove(app, self, p.id);
                ModuleLog.line(TAG + "Removed " + p.id + " from the watchlist");
                H.post(() -> toast(ctx, I18n.t(ctx, R.string.ig_pa_removed, name)));
            }).start());
            if (p.username != null) dlg.primary(I18n.t(ctx, R.string.ig_pa_open_profile), () -> openProfile(ctx, p.username));
            dlg.show();
        } catch (Throwable t) {
            ModuleLog.error(TAG + "Could not show the changelog", t);
        }
    }

    /** Adds the rows a page at a time, with "Show more" at the end while rows are left. */
    private static final class Page {
        final IeDialog dlg;
        final Context ctx;
        final String self, fallbackName;
        final WatchlistStore.Profile p;
        final LinearLayout list;
        final List<ProfileChangelogPolicy.Row> rows;
        final String name;
        final DateFormat df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);

        Page(IeDialog dlg, Context ctx, String self, WatchlistStore.Profile p, LinearLayout list,
             List<ProfileChangelogPolicy.Row> rows, String name, String fallbackName) {
            this.dlg = dlg;
            this.ctx = ctx;
            this.self = self;
            this.p = p;
            this.list = list;
            this.rows = rows;
            this.name = name;
            this.fallbackName = fallbackName;
        }

        void append(int from) {
            int end = ProfileChangelogPolicy.pageEnd(rows, from, ProfileChangelogPolicy.PAGE_ROWS);
            for (int i = from; i < end; i++) {
                ProfileChangelogPolicy.Row r = rows.get(i);
                list.addView(switch (r.kind) {
                    case CHECK -> check(r.group, i == 0);
                    case SECTION -> section(r.group, r.section);
                    case ACCOUNT -> account(r.group, r.section, r.event);
                });
            }
            if (end >= rows.size()) return;
            TextView more = new TextView(dlg.ctx);
            more.setText(I18n.t(ctx, R.string.ig_pa_cl_more));
            more.setTextColor(dlg.accent);
            more.setTextSize(14);
            more.setTypeface(Typeface.DEFAULT_BOLD);
            more.setGravity(Gravity.CENTER);
            more.setPadding(dlg.dp(14), dlg.dp(11), dlg.dp(14), dlg.dp(11));
            more.setBackground(dlg.cardBg());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dlg.dp(12);
            more.setOnClickListener(v -> {
                list.removeView(more);
                append(end);
            });
            list.addView(more, lp);
        }

        /** A check's heading: when, what it was compared with, the counts, notes, and the
         *  "mark unreliable" action while it has changes. */
        View check(ProfileChangelogPolicy.Group g, boolean top) {
            SpannableStringBuilder sb = new SpannableStringBuilder(I18n.t(ctx, R.string.ig_pa_analysis_of, df.format(new Date(g.ts))));
            sb.setSpan(new StyleSpan(Typeface.BOLD), 0, sb.length(), 0);
            int st = sb.length();
            if (g.first) sb.append('\n').append(I18n.t(ctx, R.string.ig_pa_cl_first));
            else if (g.since > 0) sb.append('\n').append(I18n.t(ctx, R.string.ig_pa_since, df.format(new Date(g.since))));
            boolean counts = false;
            if (g.followers >= 0) {
                sb.append('\n').append(countLine(R.string.ig_pa_followers, g.followers, g.igFollowers));
                counts = true;
            }
            if (g.following >= 0) {
                sb.append('\n').append(countLine(R.string.ig_pa_following, g.following, g.igFollowing));
                counts = true;
            }
            if (!counts) sb.append('\n').append(I18n.t(ctx, R.string.ig_pa_cl_counts_missing));
            String notes = ProfileAnalyzer.compareNotes(ctx, g.followersList, g.followingList);
            if (!notes.isEmpty()) sb.append(notes);
            if (g.unreliable) sb.append('\n').append(I18n.t(ctx, R.string.ig_pa_cl_unreliable));
            else if (g.partial) sb.append('\n').append(I18n.t(ctx, R.string.ig_pa_cl_partial, WatchlistStore.maxEvents()));
            else if (g.kept() == 0 && !g.first)
                sb.append('\n').append(I18n.t(ctx, g.recorded ? R.string.ig_pa_cl_no_changes : R.string.ig_pa_cl_none_kept));
            sb.setSpan(new ForegroundColorSpan(dlg.sub), st, sb.length(), 0);
            sb.setSpan(new RelativeSizeSpan(0.87f), st, sb.length(), 0);
            TextView t = new TextView(dlg.ctx);
            t.setText(sb);
            t.setTextColor(dlg.text);
            t.setTextSize(15);
            t.setLineSpacing(2 * dlg.d, 1f);

            LinearLayout card = new LinearLayout(dlg.ctx);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dlg.dp(14), dlg.dp(10), dlg.dp(14), dlg.dp(10));
            card.setBackground(dlg.cardBg());
            card.addView(t);
            if (ProfileChangelogPolicy.canMarkUnreliable(g)) {
                TextView mark = new TextView(dlg.ctx);
                mark.setText(I18n.t(ctx, R.string.ig_pa_cl_unreliable_action));
                mark.setTextColor(dlg.accent);
                mark.setTextSize(13);
                mark.setTypeface(Typeface.DEFAULT_BOLD);
                mark.setPadding(0, dlg.dp(8), 0, dlg.dp(2));
                mark.setOnClickListener(v -> confirmUnreliable(g));
                card.addView(mark);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = top ? 0 : dlg.dp(14);
            card.setLayoutParams(lp);
            return card;
        }

        /** Asks before removing a check's changes; on "Remove changes" they leave the log (the
         *  lists stay) and the changelog opens again. */
        void confirmUnreliable(ProfileChangelogPolicy.Group g) {
            int n = g.kept();
            IeDialog c = new IeDialog(ctx).title(I18n.t(ctx, R.string.ig_pa_analysis_of, df.format(new Date(g.ts))))
                    .message(I18n.t(ctx, R.string.ig_pa_cl_unreliable_confirm, n));
            c.secondary(I18n.t(ctx, R.string.ig_dialog_cancel), () -> {});
            c.primary(I18n.t(ctx, R.string.ig_pa_cl_unreliable_remove), () -> {
                Context app = ctx.getApplicationContext();
                new Thread(() -> {
                    int removed = WatchlistStore.markUnreliable(app, self, p.id, g.ts);
                    ModuleLog.line(TAG + (removed >= 0 ? p.id + ": changes of the analysis at " + g.ts + " marked unreliable, "
                            + removed + " removed, lists kept" : "❌ Could not mark the analysis of " + p.id + " at " + g.ts + " unreliable (save failed)"));
                    H.post(() -> {
                        if (removed < 0) return;
                        toast(ctx, I18n.t(ctx, R.string.ig_pa_cl_unreliable));
                        dlg.dismiss();
                        show(ctx, self, p.id, fallbackName);
                    });
                }, "ie-changelog").start();
            });
            c.show();
        }

        String countLine(int label, int listed, int instagram) {
            String ig = instagram >= 0 ? String.valueOf(instagram) : I18n.t(ctx, R.string.ig_pa_cl_not_recorded);
            return I18n.t(ctx, R.string.ig_pa_cl_list, I18n.t(ctx, label), listed, ig);
        }

        /** A section's heading in the analyzer result's style: green for gains, red for losses,
         *  grey for what isn't confirmed. */
        View section(ProfileChangelogPolicy.Group g, ProfileChangelogPolicy.Section s) {
            TextView h = new TextView(dlg.ctx);
            h.setText(I18n.t(ctx, ProfileAnalyzer.sectionTitle(s), name, g.of(s).size()));
            h.setTextColor(s.gain() ? GREEN : s.loss() ? RED : dlg.sub);
            h.setTextSize(14);
            h.setTypeface(Typeface.DEFAULT_BOLD);
            h.setPadding(dlg.dp(2), dlg.dp(10), 0, dlg.dp(2));
            return h;
        }

        /** "@username  Full name": tap opens the profile in Instagram, hold copies the username.
         *  Without a username the account's id is shown (hold copies it). A loss or not-listed
         *  account that wasn't listed for a while also says when it was last listed. */
        View account(ProfileChangelogPolicy.Group g, ProfileChangelogPolicy.Section s, WatchlistStore.Event e) {
            boolean named = e.username != null && !e.username.isEmpty();
            SpannableStringBuilder sb = new SpannableStringBuilder(named ? "@" + e.username : "id " + e.id);
            sb.setSpan(new StyleSpan(Typeface.BOLD), 0, sb.length(), 0);
            if (e.fullName != null && !e.fullName.isEmpty()) {
                int st = sb.length();
                sb.append("  ").append(e.fullName);
                sb.setSpan(new ForegroundColorSpan(dlg.sub), st, sb.length(), 0);
            }
            if (ProfileChangelogPolicy.showsLastListed(s, e, g)) {
                int st = sb.length();
                sb.append('\n').append(I18n.t(ctx, R.string.ig_pa_cl_last_listed, df.format(new Date(e.since))));
                sb.setSpan(new ForegroundColorSpan(dlg.sub), st, sb.length(), 0);
                sb.setSpan(new RelativeSizeSpan(0.85f), st, sb.length(), 0);
            }
            TextView row = new TextView(dlg.ctx);
            row.setText(sb);
            row.setTextColor(s.gain() || s.loss() ? dlg.text : dlg.sub);
            row.setTextSize(14);
            row.setPadding(dlg.dp(2), dlg.dp(5), 0, dlg.dp(5));
            String copy = named ? e.username : e.id;
            if (named) row.setOnClickListener(v -> openProfile(ctx, e.username));
            if (copy != null && !copy.isEmpty()) row.setOnLongClickListener(v -> {
                CopyFeedback.copy(ctx, "username", copy, R.string.ig_toast_comment_copied);
                return true;
            });
            return row;
        }
    }

    /** Instagram's own profile link, handled by Instagram itself. */
    private static void openProfile(Context ctx, String username) {
        try {
            ctx.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com/" + Uri.encode(username) + "/"))
                    .setPackage(ctx.getPackageName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
            ModuleLog.error(TAG + "Could not open the profile: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void toast(Context ctx, String text) {
        try { IeToast.makeText(ctx, text, Toast.LENGTH_LONG).show(); } catch (Throwable ignored) {}
    }
}
