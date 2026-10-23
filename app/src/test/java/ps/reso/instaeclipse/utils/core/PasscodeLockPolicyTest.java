package ps.reso.instaeclipse.utils.core;

import org.junit.Test;

import static org.junit.Assert.*;

/** Companion writes may turn a passcode lock on, never off while a passcode is set. */
public class PasscodeLockPolicyTest {
    private static final String[] LOCKS = {
            "lockWholeApp", "lockDirectMessages", "lockSpecificMessages", "lockDirectAlways", "lockSavedCollections"};

    @Test public void withPasscodeLocksCanBeTurnedOnButNotOff() {
        for (String key : LOCKS) {
            assertTrue(key, SettingsManager.isPasscodeLock(key));
            assertTrue(key, SettingsManager.isLockedByPasscode(key, false, "hash"));
            assertFalse(key, SettingsManager.isLockedByPasscode(key, true, "hash"));
            // A wrong-typed or missing value is not "on" either.
            assertTrue(key, SettingsManager.isLockedByPasscode(key, "false", "hash"));
            assertTrue(key, SettingsManager.isLockedByPasscode(key, null, "hash"));
        }
    }

    @Test public void withPasscodeLockedCollectionsAreFrozen() {
        assertTrue(SettingsManager.isLockedByPasscode("lockedCollections", "", "hash"));
        assertTrue(SettingsManager.isLockedByPasscode("lockedCollections", "17841400000000001", "hash"));
        assertFalse(SettingsManager.isPasscodeLock("lockedCollections"));
    }

    @Test public void withoutPasscodeNothingIsLocked() {
        for (Object passcode : new Object[]{null, "", 1234, true}) {
            for (String key : LOCKS) assertFalse(key, SettingsManager.isLockedByPasscode(key, false, passcode));
            assertFalse(SettingsManager.isLockedByPasscode("lockedCollections", "", passcode));
        }
    }

    @Test public void otherSettingsAreNotPasscodeLocks() {
        for (String key : new String[]{"lockUseFingerprint", "hideSpecificChats", "isGhostSeen", "lockDirectPasscode"}) {
            assertFalse(key, SettingsManager.isPasscodeLock(key));
            assertFalse(key, SettingsManager.isLockedByPasscode(key, false, "hash"));
        }
        assertFalse(SettingsManager.isPasscodeLock(null));
        assertFalse(SettingsManager.isLockedByPasscode(null, false, "hash"));
    }
}
