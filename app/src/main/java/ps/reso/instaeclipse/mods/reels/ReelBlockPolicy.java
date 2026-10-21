package ps.reso.instaeclipse.mods.reels;

import ps.reso.instaeclipse.utils.feature.FeatureFlags;

/** One precedence model for request, playback, cover text and DM-scroll decisions. */
public final class ReelBlockPolicy {
    public enum Reason { NONE, DISABLE_REELS, NIGHTTIME, DAILY_LIMIT }

    private ReelBlockPolicy() {}

    public static Reason requestReason() {
        return chooseRequestReason(FeatureFlags.disableReels, NightReelSchedule.isActive(),
                DailyReelLimit.shouldBlockRequests());
    }

    static Reason playbackReason(ReelViewerSession session) {
        boolean dmReel = session != null && session.isDmReel();
        return choosePlaybackReason(dmReel, FeatureFlags.disableReels,
                FeatureFlags.disableReelsExceptDM, NightReelSchedule.isActive(),
                FeatureFlags.nightReelBlockAllowDM,
                DailyReelLimit.shouldBlockPlayback(session == null ? null : session.currentMediaId),
                FeatureFlags.dailyReelLimitAllowDM);
    }

    public static boolean requestAllowsDm(Reason reason) {
        return switch (reason) {
            case DISABLE_REELS -> FeatureFlags.disableReelsExceptDM;
            case NIGHTTIME -> FeatureFlags.nightReelBlockAllowDM;
            case DAILY_LIMIT -> FeatureFlags.dailyReelLimitAllowDM;
            default -> false;
        };
    }

    static boolean shouldLockScrolling(ReelViewerSession session) {
        if (session == null) return false;
        Reason reason = requestReason();
        // Preserve the existing full-disable DM exception. Quiet hours and the daily cap exempt
        // only the message-opened reel, so their pager is locked before recommendations appear.
        if (reason == Reason.DISABLE_REELS) return false;
        return session.shouldLockScrolling(reason != Reason.NONE, requestAllowsDm(reason));
    }

    static Reason chooseRequestReason(boolean disabled, boolean nighttime, boolean dailyLimit) {
        if (disabled) return Reason.DISABLE_REELS;
        if (nighttime) return Reason.NIGHTTIME;
        if (dailyLimit) return Reason.DAILY_LIMIT;
        return Reason.NONE;
    }

    static Reason choosePlaybackReason(boolean dmReel, boolean disabled, boolean disabledAllowsDm,
                                       boolean nighttime, boolean nighttimeAllowsDm,
                                       boolean dailyLimit, boolean dailyAllowsDm) {
        Reason reason = chooseRequestReason(disabled, nighttime, dailyLimit);
        if (!dmReel) return reason;
        boolean allowed = switch (reason) {
            case DISABLE_REELS -> disabledAllowsDm;
            case NIGHTTIME -> nighttimeAllowsDm;
            case DAILY_LIMIT -> dailyAllowsDm;
            default -> false;
        };
        return allowed ? Reason.NONE : reason;
    }
}
