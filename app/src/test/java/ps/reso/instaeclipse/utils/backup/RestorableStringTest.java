package ps.reso.instaeclipse.utils.backup;

import org.junit.Test;

import static org.junit.Assert.*;

/** Text settings a companion restore applies, checked as SettingsBackupManager.fromJson checks them (M2). */
public class RestorableStringTest {

    @Test public void defaultsOfEveryCheckedKeyAreRestorable() {
        for (String key : new String[]{"startupTab", "customShareDomain", "exactTimestampFormat", "spoofDevice",
                "storyRingColor", "spoofAvatar"}) {
            assertEquals(key, "", SettingsBackupManager.restorableString(key, ""));
        }
        assertEquals("0.0", SettingsBackupManager.restorableString("spoofLat", "0.0"));
        assertEquals("0.0", SettingsBackupManager.restorableString("spoofLng", "0.0"));
    }

    @Test public void choiceSettingsAcceptOnlyKnownValues() {
        assertEquals("CLIPS", SettingsBackupManager.restorableString("startupTab", "CLIPS"));
        assertNull(SettingsBackupManager.restorableString("startupTab", "clips"));
        assertEquals("pixel", SettingsBackupManager.restorableString("spoofDevice", "pixel"));
        assertNull(SettingsBackupManager.restorableString("spoofDevice", "iphone"));
    }

    @Test public void shareDomainIsNormalizedAndInstagramRefused() {
        assertEquals("example.com", SettingsBackupManager.restorableString("customShareDomain", " https://Example.com/p/x "));
        assertNull(SettingsBackupManager.restorableString("customShareDomain", "instagram.com"));
        assertNull(SettingsBackupManager.restorableString("customShareDomain", "not a domain"));
    }

    @Test public void formatsPicturesAndCoordinatesAreValidated() {
        assertEquals("yyyy-MM-dd HH:mm", SettingsBackupManager.restorableString("exactTimestampFormat", "yyyy-MM-dd HH:mm"));
        assertNull(SettingsBackupManager.restorableString("exactTimestampFormat", "qqq"));
        assertEquals("QUJD", SettingsBackupManager.restorableString("spoofAvatar", "QUJD"));
        assertNull(SettingsBackupManager.restorableString("spoofAvatar", "not base64!"));
        assertEquals("48.8584", SettingsBackupManager.restorableString("spoofLat", "48.8584"));
        assertEquals("-122.4", SettingsBackupManager.restorableString("spoofLng", "-122.4"));
        assertNull(SettingsBackupManager.restorableString("spoofLat", "north"));
        assertNull(SettingsBackupManager.restorableString("spoofLng", "NaN"));
    }

    @Test public void uncheckedTextKeepsItsValueAndNullIsRefused() {
        assertEquals("My bio", SettingsBackupManager.restorableString("spoofBio", "My bio"));
        assertEquals("clips_tab,feed_tab", SettingsBackupManager.restorableString("navTabOrder", "clips_tab,feed_tab"));
        assertNull(SettingsBackupManager.restorableString("spoofBio", null));
        assertNull(SettingsBackupManager.restorableString(null, "x"));
    }
}
