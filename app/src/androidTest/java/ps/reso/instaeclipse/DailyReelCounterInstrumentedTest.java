package ps.reso.instaeclipse;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import ps.reso.instaeclipse.mods.reels.DailyReelLimit;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DailyReelCounterInstrumentedTest {
    @Test public void counterRecordsWithEnforcementOffAndUsesSameCountWhenEnabled() {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences isolated = target.getSharedPreferences("test_daily_reel_usage", Context.MODE_PRIVATE);
        isolated.edit().clear().putInt("warnedThresholds", 3).commit();
        Context isolatedContext = new ContextWrapper(target) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) { return isolated; }
        };
        boolean originalEnabled = FeatureFlags.dailyReelLimitEnabled;
        boolean originalDisabled = FeatureFlags.disableReels;
        int originalLimit = FeatureFlags.dailyReelLimit;
        try {
            FeatureFlags.dailyReelLimitEnabled = false;
            FeatureFlags.disableReels = false;
            FeatureFlags.dailyReelLimit = 2;
            DailyReelLimit.init(isolatedContext);
            DailyReelLimit.recordView("main-reel");
            DailyReelLimit.recordView("friends-reel");
            DailyReelLimit.recordView("friends-reel");
            assertEquals(2, DailyReelLimit.watchedToday());
            assertEquals(2, isolated.getInt("count", -1));
            assertFalse(DailyReelLimit.shouldBlockRequests());
            assertFalse(DailyReelLimit.shouldBlockPlayback("next-reel"));
            FeatureFlags.dailyReelLimitEnabled = true;
            assertTrue(DailyReelLimit.shouldBlockRequests());
            assertFalse(DailyReelLimit.shouldBlockPlayback("friends-reel"));
            assertTrue(DailyReelLimit.shouldBlockPlayback("next-reel"));
            DailyReelLimit.recordView("next-reel");
            assertEquals(2, DailyReelLimit.watchedToday());
            FeatureFlags.disableReels = true;
            assertFalse(DailyReelLimit.shouldBlockRequests());
            assertFalse(DailyReelLimit.shouldBlockPlayback("next-reel"));
            assertEquals(-1, DailyReelLimit.recordView("next-reel"));
            assertEquals(3, DailyReelLimit.watchedToday());
            FeatureFlags.disableReels = false;
            FeatureFlags.dailyReelLimit = 14;
            assertEquals(10, DailyReelLimit.recordView("warning-reel"));
            assertEquals(-1, DailyReelLimit.recordView("warning-reel"));
            FeatureFlags.dailyReelLimit = 15;
            assertEquals(10, DailyReelLimit.recordView("another-warning"));
            FeatureFlags.dailyReelLimit = 11;
            assertEquals(5, DailyReelLimit.recordView("five-left"));
            FeatureFlags.dailyReelLimit = 12;
            assertEquals(5, DailyReelLimit.recordView("five-left-again"));
        } finally {
            FeatureFlags.dailyReelLimitEnabled = originalEnabled;
            FeatureFlags.disableReels = originalDisabled;
            FeatureFlags.dailyReelLimit = originalLimit;
            isolated.edit().clear().commit();
        }
    }
}
