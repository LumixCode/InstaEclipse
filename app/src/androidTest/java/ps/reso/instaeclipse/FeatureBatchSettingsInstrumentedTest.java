package ps.reso.instaeclipse;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

import ps.reso.instaeclipse.utils.backup.SettingsBackupManager;
import ps.reso.instaeclipse.utils.core.SettingsManager;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureManager;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class FeatureBatchSettingsInstrumentedTest {
    private static final String[] KEYS = {
            "enableVoiceDownload", "showUnsentIndicator", "exactTimestamps",
            "showFollowStatusLabel", "showFollowingFollowStatus", "cacheStories",
            "openLinksExternally", "hideShareCreateGroup", "manualStorySeen", "unlockPlusBenefits",
            "hideChatMenuButton"
    };
    private final Map<Field, Object> originalFlags = new LinkedHashMap<>();
    private Field preferencesField;
    private Object originalPreferences;
    private SharedPreferences preferences;
    private Context context;

    @Before public void isolateSettings() throws Exception {
        for (Field field : FeatureFlags.class.getFields()) originalFlags.put(field, field.get(null));
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        preferences = target.getSharedPreferences("test_feature_batch_settings", Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
        context = new ContextWrapper(target) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return preferences;
            }
        };
        preferencesField = SettingsManager.class.getDeclaredField("prefs");
        preferencesField.setAccessible(true);
        originalPreferences = preferencesField.get(null);
        preferencesField.set(null, null);
        SettingsManager.init(context);
        FeatureFlags.isExtremeMode = false;
    }

    @After public void restoreSettings() throws Exception {
        preferencesField.set(null, originalPreferences);
        for (Map.Entry<Field, Object> entry : originalFlags.entrySet()) entry.getKey().set(null, entry.getValue());
        FeatureManager.refreshFeatureStatus();
        preferences.edit().clear().commit();
    }

    @Test public void eachSettingPersistsBothOnAndOff() throws Exception {
        for (boolean expected : new boolean[]{true, false}) {
            setBatch(expected);
            SettingsManager.saveAllFlags();
            for (String key : KEYS) {
                assertTrue(key, preferences.contains(key));
                assertEquals(key, expected, preferences.getBoolean(key, !expected));
            }
            setBatch(!expected);
            SettingsManager.loadAllFlags(context);
            assertBatch(expected);
        }
    }

    @Test public void existingInstallsDefaultNewFeaturesOff() throws Exception {
        preferences.edit().putBoolean("showFollowerToast", true).commit();
        setBatch(true);
        SettingsManager.loadAllFlags(context);
        assertBatch(false);
        assertTrue(FeatureFlags.showFollowerToast);
        assertTrue(FeatureFlags.showUnsentButton);
        assertTrue(FeatureFlags.keepUnsentInChat);
        assertFalse(FeatureFlags.nightReelBlockEnabled);
        assertTrue(FeatureFlags.nightReelBlockAllowDM);
        assertEquals(22 * 60, FeatureFlags.nightReelBlockStartMinutes);
        assertEquals(7 * 60, FeatureFlags.nightReelBlockEndMinutes);
    }

    @Test public void legacyReelQualityToggleMigratesWithoutInterruptingOtherSettings() {
        preferences.edit().putBoolean("forceReelQuality", true)
                .putBoolean("showFollowingFollowStatus", true).commit();
        SettingsManager.loadAllFlags(context);
        assertEquals(1080, FeatureFlags.forceReelQuality);
        assertEquals(1080, preferences.getInt("forceReelQuality", -1));
        assertTrue(FeatureFlags.showFollowingFollowStatus);

        preferences.edit().putBoolean("forceReelQuality", false).commit();
        SettingsManager.loadAllFlags(context);
        assertEquals(0, FeatureFlags.forceReelQuality);
        assertEquals(0, preferences.getInt("forceReelQuality", -1));
    }

    @Test public void reelQualityMigrationPreservesIntegersAndRecoversInvalidTypes() {
        for (int quality : new int[]{0, 720, 1080, Integer.MAX_VALUE}) {
            preferences.edit().putInt("forceReelQuality", quality).commit();
            SettingsManager.loadAllFlags(context);
            assertEquals(quality, FeatureFlags.forceReelQuality);
            assertEquals(quality, preferences.getInt("forceReelQuality", -1));
        }
        preferences.edit().putString("forceReelQuality", "invalid").commit();
        SettingsManager.loadAllFlags(context);
        assertEquals(0, FeatureFlags.forceReelQuality);
        assertEquals(0, preferences.getInt("forceReelQuality", -1));

        preferences.edit().remove("forceReelQuality").commit();
        SettingsManager.loadAllFlags(context);
        assertEquals(0, FeatureFlags.forceReelQuality);
        assertFalse(preferences.contains("forceReelQuality"));
    }

    @Test public void backupRoundTripAndOlderPartialBackupsPreserveChoices() throws Exception {
        for (boolean expected : new boolean[]{true, false}) {
            setBatch(expected);
            String backup = SettingsBackupManager.toJson();
            JSONObject settings = new JSONObject(backup).getJSONObject("settings");
            for (String key : KEYS) assertEquals(key, expected, settings.getBoolean(key));
            setBatch(!expected);
            SettingsBackupManager.fromJson(backup);
            assertBatch(expected);
        }
        setBatch(true);
        SettingsBackupManager.fromJson("{\"settings\":{\"showFollowerToast\":false}}");
        assertBatch(true);
        assertFalse(FeatureFlags.showFollowerToast);
        SettingsBackupManager.fromJson("{\"enableVoiceDownload\":false}");
        assertFalse(FeatureFlags.enableVoiceDownload);
        assertTrue(FeatureFlags.manualStorySeen);
    }

    @Test public void createGroupChoiceRemainsEditableDuringExtremeMode() throws Exception {
        FeatureFlags.hideShareCreateGroup = true;
        FeatureFlags.isExtremeMode = true;
        SettingsBackupManager.fromJson("{\"hideShareCreateGroup\":false}");
        assertFalse(FeatureFlags.hideShareCreateGroup);
        // Backups from earlier builds still carry the old key.
        SettingsBackupManager.fromJson("{\"hideCreateGroupButton\":true}");
        assertTrue(FeatureFlags.hideShareCreateGroup);
        FeatureFlags.dailyReelLimitEnabled = true;
        SettingsBackupManager.fromJson("{\"dailyReelLimitEnabled\":false}");
        assertTrue(FeatureFlags.dailyReelLimitEnabled);
    }

    @Test public void legacyCreateGroupKeyMigratesOnceAndNeverOverridesTheNewKey() {
        preferences.edit().putBoolean("hideCreateGroupButton", true).commit();
        SettingsManager.loadAllFlags(context);
        assertTrue(FeatureFlags.hideShareCreateGroup);
        assertTrue(preferences.getBoolean("hideShareCreateGroup", false));
        assertFalse(preferences.contains("hideCreateGroupButton"));

        preferences.edit().putBoolean("hideShareCreateGroup", false)
                .putBoolean("hideCreateGroupButton", true).commit();
        SettingsManager.loadAllFlags(context);
        assertFalse(FeatureFlags.hideShareCreateGroup);
    }

    @Test public void oldLocationPromptSwitchFoldsIntoHideOnboardingPrompts() throws Exception {
        preferences.edit().putBoolean("blockLocationPrompts", true)
                .putBoolean("hideOnboardingPrompts", false).commit();
        SettingsManager.loadAllFlags(context);
        assertTrue(FeatureFlags.hideOnboardingPrompts);
        assertTrue(preferences.getBoolean("hideOnboardingPrompts", false));
        assertFalse(preferences.contains("blockLocationPrompts"));

        preferences.edit().putBoolean("blockLocationPrompts", false)
                .putBoolean("hideOnboardingPrompts", false).commit();
        SettingsManager.loadAllFlags(context);
        assertFalse(FeatureFlags.hideOnboardingPrompts);
        assertFalse(preferences.contains("blockLocationPrompts"));

        // A wrong-typed leftover must not break the load.
        preferences.edit().putString("blockLocationPrompts", "true").commit();
        SettingsManager.loadAllFlags(context);
        assertFalse(FeatureFlags.hideOnboardingPrompts);
        assertFalse(preferences.contains("blockLocationPrompts"));

        // Backups fold the old key like the prefs: either switch on keeps it on. A backup of the
        // build before the fold carries both keys, with the Extras switch off by default.
        SettingsBackupManager.fromJson("{\"settings\":{\"blockLocationPrompts\":true}}");
        assertTrue(FeatureFlags.hideOnboardingPrompts);
        FeatureFlags.hideOnboardingPrompts = false;
        SettingsBackupManager.fromJson("{\"settings\":{\"blockLocationPrompts\":true,"
                + "\"hideOnboardingPrompts\":false}}");
        assertTrue(FeatureFlags.hideOnboardingPrompts);
        SettingsBackupManager.fromJson("{\"settings\":{\"blockLocationPrompts\":false,"
                + "\"hideOnboardingPrompts\":true}}");
        assertTrue(FeatureFlags.hideOnboardingPrompts);
        SettingsBackupManager.fromJson("{\"settings\":{\"blockLocationPrompts\":false,"
                + "\"hideOnboardingPrompts\":false}}");
        assertFalse(FeatureFlags.hideOnboardingPrompts);
        // The companion drops the old key from its cache, so its later backups never carry it.
        assertTrue(ps.reso.instaeclipse.utils.core.CommonUtils.STALE_CACHE_KEYS.contains("blockLocationPrompts"));
        assertFalse(new JSONObject(SettingsBackupManager.toJson()).getJSONObject("settings")
                .has("blockLocationPrompts"));
    }

    @Test public void storyRetentionPersistsBacksUpAndNormalizesInvalidValues() throws Exception {
        assertEquals(24, FeatureFlags.storyCacheHours);
        FeatureFlags.cacheStories = true;
        FeatureFlags.storyCacheHours = 168;
        SettingsManager.saveAllFlags();
        FeatureFlags.storyCacheHours = 6;
        SettingsManager.loadAllFlags(context);
        assertEquals(168, FeatureFlags.storyCacheHours);
        assertTrue(FeatureFlags.cacheStories);

        String backup = SettingsBackupManager.toJson();
        FeatureFlags.storyCacheHours = 1;
        SettingsBackupManager.fromJson(backup);
        assertEquals(168, FeatureFlags.storyCacheHours);
        SettingsBackupManager.fromJson("{\"cacheStories\":false}");
        assertEquals(168, FeatureFlags.storyCacheHours);
        assertFalse(FeatureFlags.cacheStories);

        FeatureFlags.isExtremeMode = true;
        SettingsBackupManager.fromJson("{\"storyCacheHours\":720}");
        assertEquals(720, FeatureFlags.storyCacheHours);
        for (String invalid : new String[]{"0", "721", "-1", "\"invalid\""}) {
            SettingsBackupManager.fromJson("{\"storyCacheHours\":" + invalid + "}");
            assertEquals(24, FeatureFlags.storyCacheHours);
        }
        preferences.edit().putInt("storyCacheHours", Integer.MAX_VALUE).commit();
        SettingsManager.loadAllFlags(context);
        assertEquals(24, FeatureFlags.storyCacheHours);
        preferences.edit().putBoolean("storyCacheHours", true).commit();
        SettingsManager.loadAllFlags(context);
        assertEquals(24, FeatureFlags.storyCacheHours);
    }

    @Test public void unsentChildChoicesPersistWithoutDisablingMessageRetention() throws Exception {
        FeatureFlags.keepUnsentMessages = true;
        FeatureFlags.showUnsentButton = false;
        FeatureFlags.keepUnsentInChat = false;
        SettingsManager.saveAllFlags();
        FeatureFlags.keepUnsentMessages = false;
        FeatureFlags.showUnsentButton = true;
        FeatureFlags.keepUnsentInChat = true;
        SettingsManager.loadAllFlags(context);
        assertTrue(FeatureFlags.keepUnsentMessages);
        assertFalse(FeatureFlags.showUnsentButton);
        assertFalse(FeatureFlags.keepUnsentInChat);
        String backup = SettingsBackupManager.toJson();
        FeatureFlags.showUnsentButton = true;
        FeatureFlags.keepUnsentInChat = true;
        SettingsBackupManager.fromJson(backup);
        assertFalse(FeatureFlags.showUnsentButton);
        assertFalse(FeatureFlags.keepUnsentInChat);
        SettingsBackupManager.fromJson("{\"settings\":{\"keepUnsentMessages\":false}}");
        assertFalse(FeatureFlags.showUnsentButton);
        assertFalse(FeatureFlags.keepUnsentInChat);
    }

    @Test public void hideChatEyeChoicePersistsAndBacksUpIndependently() throws Exception {
        FeatureFlags.hideSpecificChats = true;
        FeatureFlags.showHideChatButton = false;
        SettingsManager.saveAllFlags();
        FeatureFlags.hideSpecificChats = false;
        FeatureFlags.showHideChatButton = true;
        SettingsManager.loadAllFlags(context);
        assertTrue(FeatureFlags.hideSpecificChats);
        assertFalse(FeatureFlags.showHideChatButton);

        String backup = SettingsBackupManager.toJson();
        FeatureFlags.hideSpecificChats = false;
        FeatureFlags.showHideChatButton = true;
        SettingsBackupManager.fromJson(backup);
        assertTrue(FeatureFlags.hideSpecificChats);
        assertFalse(FeatureFlags.showHideChatButton);

        preferences.edit().clear().commit();
        SettingsManager.loadAllFlags(context);
        assertFalse(FeatureFlags.hideSpecificChats);
        assertTrue(FeatureFlags.showHideChatButton);
    }

    @Test public void selectedLocksPersistByIdEvenWithDuplicateOrChangedNames() {
        FeatureFlags.lockDirectMessages = false;
        FeatureFlags.lockSpecificMessages = true;
        FeatureFlags.lockDirectAlways = true;
        FeatureFlags.lockedDirectThreads = "{}";
        ps.reso.instaeclipse.utils.ghost.LockedThreads.set("first", "Instagram user", true);
        ps.reso.instaeclipse.utils.ghost.LockedThreads.set("second", "Instagram user", true);
        FeatureFlags.lockSpecificMessages = false;
        FeatureFlags.lockDirectAlways = false;
        FeatureFlags.lockedDirectThreads = "{}";
        SettingsManager.loadAllFlags(context);
        assertTrue(FeatureFlags.lockSpecificMessages);
        assertTrue(FeatureFlags.lockDirectAlways);
        assertFalse(FeatureFlags.lockDirectMessages);
        assertTrue(ps.reso.instaeclipse.utils.ghost.LockedThreads.contains("first"));
        assertTrue(ps.reso.instaeclipse.utils.ghost.LockedThreads.contains("second"));
        ps.reso.instaeclipse.utils.ghost.LockedThreads.set("first", "Renamed user", false);
        assertFalse(ps.reso.instaeclipse.utils.ghost.LockedThreads.contains("first"));
        assertTrue(ps.reso.instaeclipse.utils.ghost.LockedThreads.contains("second"));

        preferences.edit().clear().commit();
        SettingsManager.loadAllFlags(context);
        assertFalse(FeatureFlags.lockSpecificMessages);
        assertTrue(ps.reso.instaeclipse.utils.ghost.LockedThreads.all().isEmpty());
    }

    @Test public void nighttimeSchedulePersistsBacksUpAndLocksWithExtremeMode() throws Exception {
        FeatureFlags.nightReelBlockEnabled = true;
        FeatureFlags.nightReelBlockAllowDM = false;
        FeatureFlags.nightReelBlockStartMinutes = 23 * 60 + 15;
        FeatureFlags.nightReelBlockEndMinutes = 6 * 60 + 45;
        SettingsManager.saveAllFlags();
        assertTrue(preferences.getBoolean("nightReelBlockEnabled", false));
        assertFalse(preferences.getBoolean("nightReelBlockAllowDM", true));
        assertEquals(23 * 60 + 15, preferences.getInt("nightReelBlockStartMinutes", -1));
        assertEquals(6 * 60 + 45, preferences.getInt("nightReelBlockEndMinutes", -1));

        String backup = SettingsBackupManager.toJson();
        FeatureFlags.nightReelBlockEnabled = false;
        FeatureFlags.nightReelBlockAllowDM = true;
        FeatureFlags.nightReelBlockStartMinutes = 0;
        FeatureFlags.nightReelBlockEndMinutes = 1;
        SettingsBackupManager.fromJson(backup);
        assertTrue(FeatureFlags.nightReelBlockEnabled);
        assertFalse(FeatureFlags.nightReelBlockAllowDM);
        assertEquals(23 * 60 + 15, FeatureFlags.nightReelBlockStartMinutes);
        assertEquals(6 * 60 + 45, FeatureFlags.nightReelBlockEndMinutes);

        SettingsBackupManager.fromJson("{\"settings\":{\"nightReelBlockStartMinutes\":300,"
                + "\"nightReelBlockEndMinutes\":300}}");
        assertEquals(23 * 60 + 15, FeatureFlags.nightReelBlockStartMinutes);
        assertEquals(6 * 60 + 45, FeatureFlags.nightReelBlockEndMinutes);
        preferences.edit().putInt("nightReelBlockStartMinutes", 300)
                .putInt("nightReelBlockEndMinutes", 300).commit();
        SettingsManager.loadAllFlags(context);
        assertEquals(22 * 60, FeatureFlags.nightReelBlockStartMinutes);
        assertEquals(7 * 60, FeatureFlags.nightReelBlockEndMinutes);
        SettingsBackupManager.fromJson(backup);

        FeatureFlags.isExtremeMode = true;
        SettingsBackupManager.fromJson("{\"settings\":{\"nightReelBlockEnabled\":false,"
                + "\"nightReelBlockAllowDM\":true,\"nightReelBlockStartMinutes\":60,"
                + "\"nightReelBlockEndMinutes\":120}}");
        assertTrue(FeatureFlags.nightReelBlockEnabled);
        assertFalse(FeatureFlags.nightReelBlockAllowDM);
        assertEquals(23 * 60 + 15, FeatureFlags.nightReelBlockStartMinutes);
        assertEquals(6 * 60 + 45, FeatureFlags.nightReelBlockEndMinutes);
    }

    private static void setBatch(boolean value) throws Exception {
        for (String key : KEYS) FeatureFlags.class.getField(key).setBoolean(null, value);
    }

    private static void assertBatch(boolean expected) throws Exception {
        for (String key : KEYS) assertEquals(key, expected, FeatureFlags.class.getField(key).getBoolean(null));
    }
}
