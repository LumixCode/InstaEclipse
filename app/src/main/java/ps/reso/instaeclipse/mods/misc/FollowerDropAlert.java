package ps.reso.instaeclipse.mods.misc;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.Date;
import java.util.List;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.mods.ui.UIHookManager;
import ps.reso.instaeclipse.utils.dialog.IeDialog;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.text.TimestampFormat;
import ps.reso.instaeclipse.utils.users.WatchlistStore;
import ps.reso.instaeclipse.xposedcompat.AndroidAppHelper;

public final class FollowerDropAlert {

    private static final String TAG = "(IE|FollowerAlert) ";
    private static final String STATUS = "FollowerDropAlert";
    private static final String PREFS = "instaeclipse_follower_alert";
    private static final String DROP_AT = "drop_";
    private static final String REMINDER_AT = "reminder_";
    private static final long SETTLE_MS = 4000L;
    private static final long NOTICE_VALID_MS = 10 * 60_000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object LOCK = new Object();

    private static final class Saved {
        final String self;
        final long version, lastCheck;
        final int countAtCheck, listed;
        final List<WatchlistStore.Event> events;

        Saved(String self, long version, WatchlistStore.Profile profile) {
            this.self = self;
            this.version = version;
            this.lastCheck = profile.lastCheck;
            this.countAtCheck = profile.followersState.expected;
            this.listed = profile.followers.size();
            this.events = profile.events;
        }
    }

    private static final class Notice {
        final FollowerDropPolicy.Kind kind;
        final String self;
        final int count, then;
        final boolean counted;
        final long lastCheck, version, readyAt;
        final int[] week;

        Notice(FollowerDropPolicy.Kind kind, String self, int count, int then, Saved last, int[] week) {
            this.kind = kind;
            this.self = self;
            this.count = count;
            this.then = then;
            this.counted = last.countAtCheck >= 0;
            this.lastCheck = last.lastCheck;
            this.version = last.version;
            this.readyAt = SystemClock.elapsedRealtime();
            this.week = week;
        }
    }

    private static String latestSelf;
    private static int latestCount;
    private static boolean scheduled;
    private static long nextCheckAt;
    private static volatile Saved saved;
    private static volatile Notice pending;

    private FollowerDropAlert() {}

    private static boolean on() {
        return FeatureFlags.followerDropAlert && FeatureFlags.profileAnalyzer;
    }

    static void attached(boolean headerCounts) {
        if (headerCounts) {
            FeatureStatusTracker.setHooked(STATUS);
            if (on()) ModuleLog.ok(TAG + "Ready");
        } else if (on()) {
            FeatureStatusTracker.setBroken(STATUS);
            ModuleLog.warn(TAG + "Will not work: the follower count on your profile was not found in this Instagram version,"
                    + " so a drop can't be noticed");
        }
    }

    static void observe(Context context, String profile, String viewer, int count) {
        try {
            if (!on() || profile == null || count < 0 || resting()) return;
            if (FeatureFlags.profileSpoofer && FeatureFlags.spoofFollowers >= 0) return;
            Context app = context.getApplicationContext();
            String self = viewer != null ? viewer : WatchlistStore.self(app);
            if (profile.equals(self)) seen(app, self, count);
        } catch (Throwable t) {
            ModuleLog.detail(TAG + "header count not read: " + t);
        }
    }

    public static void observeReal(String self, Object count) {
        try {
            if (!on() || self == null || !(count instanceof Number real) || resting()) return;
            if (real.longValue() <= 0 || real.longValue() > Integer.MAX_VALUE) return;
            Context app = AndroidAppHelper.currentApplication();
            if (app != null) seen(app, self, real.intValue());
        } catch (Throwable t) {
            ModuleLog.detail(TAG + "real count not read: " + t);
        }
    }

    private static boolean resting() {
        synchronized (LOCK) {
            return !scheduled && SystemClock.elapsedRealtime() < nextCheckAt;
        }
    }

    private static void seen(Context app, String self, int count) {
        synchronized (LOCK) {
            latestSelf = self;
            latestCount = count;
            if (scheduled) return;
            scheduled = true;
        }
        MAIN.postDelayed(() -> settle(app), SETTLE_MS);
    }

    private static void settle(Context app) {
        String self;
        int count;
        synchronized (LOCK) {
            scheduled = false;
            nextCheckAt = SystemClock.elapsedRealtime() + FollowerDropPolicy.CHECK_EVERY_MS;
            self = latestSelf;
            count = latestCount;
        }
        new Thread(() -> evaluate(app, self, count), "IE-FollowerAlert").start();
    }

    private static void evaluate(Context app, String self, int count) {
        try {
            if (!on() || self == null) return;
            long now = System.currentTimeMillis();
            SharedPreferences prefs = prefs(app);
            Saved last = saved(app, self);
            int then = FollowerDropPolicy.then(last.countAtCheck, last.listed);
            FollowerDropPolicy.Kind kind = FollowerDropPolicy.kind(count, then, last.lastCheck, now,
                    prefs.getLong(DROP_AT + self, 0), prefs.getLong(REMINDER_AT + self, 0));
            ModuleLog.detail(TAG + "Instagram shows " + count + " followers, the last check"
                    + (last.lastCheck > 0 ? " had " + then + (last.countAtCheck >= 0 ? " counted" : " listed") : " is missing")
                    + ": " + kind);
            if (kind == FollowerDropPolicy.Kind.NONE) return;
            pending = new Notice(kind, self, count, then, last, FollowerDropPolicy.week(last.events, now));
            ModuleLog.line(TAG + (kind == FollowerDropPolicy.Kind.DROP
                    ? "Your follower count is " + (then - count) + " lower than at your last check"
                    : "Your last follower check is more than a week old"));
            UIHookManager.followerNoticeReady();
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "Could not compare your follower count with your last check", t);
        }
    }

    private static Saved saved(Context app, String self) {
        long version = WatchlistStore.indexVersion();
        Saved known = saved;
        if (known != null && known.version == version && known.self.equals(self)) return known;
        known = new Saved(self, version, WatchlistStore.loadProfile(app, self, self));
        saved = known;
        return known;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static Notice current() {
        Notice notice = pending;
        if (notice == null) return null;
        if (notice.version == WatchlistStore.indexVersion()
                && SystemClock.elapsedRealtime() - notice.readyAt <= NOTICE_VALID_MS) return notice;
        pending = null;
        return null;
    }

    public static boolean pending() {
        return on() && current() != null;
    }

    public static boolean show(Activity activity, Runnable onClosed) {
        Notice notice = on() ? current() : null;
        if (notice == null) return false;
        try {
            if (!notice.self.equals(WatchlistStore.self(activity))) {
                pending = null;
                return false;
            }
            IeDialog dialog = new IeDialog(activity).title(I18n.t(activity, R.string.ig_pa_followers))
                    .message(text(activity, notice));
            dialog.secondary(I18n.t(activity, R.string.ig_pa_changelog),
                    () -> ProfileChangelog.show(activity, notice.self, notice.self, null));
            dialog.primary(I18n.t(activity, R.string.ig_dialog_ok), null);
            dialog.onDismiss(onClosed);
            if (!dialog.show().isShowing()) return false;
            pending = null;
            prefs(activity).edit().putLong((notice.kind == FollowerDropPolicy.Kind.DROP ? DROP_AT : REMINDER_AT)
                    + notice.self, System.currentTimeMillis()).apply();
            return true;
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "Could not show the follower notice", t);
            return false;
        }
    }

    private static String text(Context context, Notice notice) {
        String checked = TimestampFormat.dialogDateFormat(context).format(new Date(notice.lastCheck));
        StringBuilder text = new StringBuilder(notice.kind == FollowerDropPolicy.Kind.DROP
                ? I18n.t(context, R.string.ig_follower_drop_notice, notice.then - notice.count, checked)
                : I18n.t(context, R.string.ig_follower_reminder_notice, checked));
        if (notice.counted && notice.count != notice.then) {
            text.append('\n').append(I18n.t(context, R.string.ig_follower_counts_notice, notice.then, notice.count));
        }
        if (notice.week[0] + notice.week[1] > 0) {
            text.append('\n').append(I18n.t(context, R.string.ig_follower_week_notice, notice.week[0], notice.week[1]));
        }
        return text.append("\n\n").append(I18n.t(context, R.string.cat_profileAnalyzerHelp)).toString();
    }
}
