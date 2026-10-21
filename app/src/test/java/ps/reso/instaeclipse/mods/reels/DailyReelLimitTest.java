package ps.reso.instaeclipse.mods.reels;

import org.junit.Test;

import ps.reso.instaeclipse.utils.feature.FeatureFlags;

import static org.junit.Assert.*;

public class DailyReelLimitTest {
    @Test public void manualDisableAlwaysOverridesDailyEnforcement() {
        boolean enabled = FeatureFlags.dailyReelLimitEnabled;
        boolean disabled = FeatureFlags.disableReels;
        boolean dm = FeatureFlags.disableReelsExceptDM;
        try {
            for (boolean daily : new boolean[]{false, true}) {
                for (boolean manual : new boolean[]{false, true}) {
                    for (boolean exceptDM : new boolean[]{false, true}) {
                        FeatureFlags.dailyReelLimitEnabled = daily;
                        FeatureFlags.disableReels = manual;
                        FeatureFlags.disableReelsExceptDM = exceptDM;
                        assertEquals(daily && !manual, DailyReelLimit.isActive());
                    }
                }
            }
        } finally {
            FeatureFlags.dailyReelLimitEnabled = enabled;
            FeatureFlags.disableReels = disabled;
            FeatureFlags.disableReelsExceptDM = dm;
        }
    }
}
