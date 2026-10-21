package ps.reso.instaeclipse.mods.ui;

import org.junit.Test;
import static org.junit.Assert.*;
import static ps.reso.instaeclipse.mods.ui.DirectLockSession.Screen.*;

public class DirectLockSessionTest {
    @Test public void inboxConversationAndBackNeedOnlyOneUnlock() {
        DirectLockSession s = new DirectLockSession();
        s.visit(INBOX, null, "owner", true);
        assertTrue(s.needsInboxLock(INBOX, true));
        s.unlockInbox();
        for (DirectLockSession.Screen screen : new DirectLockSession.Screen[]{UNKNOWN, THREAD, UNKNOWN, INBOX}) {
            s.visit(screen, screen == THREAD ? "chat" : null, "owner", true);
            assertFalse(s.needsInboxLock(screen, true));
        }
    }

    @Test public void relockAfterLeavingDirectRequiresNewUnlock() {
        DirectLockSession s = new DirectLockSession();
        s.visit(INBOX, null, "owner", true);
        s.unlockInbox();
        s.visit(OTHER, null, "owner", true);
        s.visit(INBOX, null, "owner", true);
        assertTrue(s.needsInboxLock(INBOX, true));
    }

    @Test public void disabledRelockKeepsGrantAcrossTabsAndBackground() {
        DirectLockSession s = new DirectLockSession();
        s.visit(INBOX, null, "owner", false);
        s.unlockInbox();
        s.visit(OTHER, null, "owner", false);
        s.background(false);
        s.visit(INBOX, null, "owner", false);
        assertFalse(s.needsInboxLock(INBOX, true));
    }

    @Test public void enabledRelockClearsGrantsInBackground() {
        DirectLockSession s = new DirectLockSession();
        s.unlockInbox();
        s.unlockThread("a");
        s.background(true);
        assertTrue(s.needsInboxLock(INBOX, true));
        assertTrue(s.needsThreadLock(THREAD, "a", true));
    }

    @Test public void chosenChatDoesNotLockInboxOrOtherChats() {
        DirectLockSession s = new DirectLockSession();
        assertFalse(s.needsThreadLock(INBOX, null, false));
        assertFalse(s.needsThreadLock(THREAD, "other", false));
        assertTrue(s.needsThreadLock(THREAD, "chosen", true));
        s.unlockThread("chosen");
        assertFalse(s.needsThreadLock(THREAD, "chosen", true));
        assertTrue(s.needsThreadLock(THREAD, "anotherChosen", true));
    }

    @Test public void selectedChatRelocksOnReentryButNeverPromptsOnInboxReturn() {
        DirectLockSession s = new DirectLockSession();
        s.visit(THREAD, "a", "owner", true);
        s.unlockThread("a");
        s.visit(UNKNOWN, null, "owner", true);
        assertFalse(s.needsThreadLock(THREAD, "a", true));
        s.visit(INBOX, null, "owner", true);
        assertFalse(s.needsThreadLock(INBOX, null, true));
        s.visit(THREAD, "a", "owner", true);
        assertTrue(s.needsThreadLock(THREAD, "a", true));
    }

    @Test public void changingAccountsRevokesGrantsEvenWithoutRelock() {
        DirectLockSession s = new DirectLockSession();
        s.visit(THREAD, "a", "one", false);
        s.unlockInbox();
        s.unlockThread("a");
        s.visit(THREAD, "a", "two", false);
        assertTrue(s.needsInboxLock(THREAD, true));
        assertTrue(s.needsThreadLock(THREAD, "a", true));
    }

    @Test public void directLinkToConversationRequiresInboxUnlock() {
        DirectLockSession s = new DirectLockSession();
        assertTrue(s.needsInboxLock(THREAD, true));
        assertFalse(s.needsInboxLock(THREAD, false));
    }

    @Test public void unresolvedConversationCannotReceiveAnUnlockGrant() {
        DirectLockSession s = new DirectLockSession();
        s.unlockThread(null);
        assertTrue(s.needsThreadLock(THREAD, null, true));
    }

    @Test public void directSearchDoesNotInvalidateInboxUnlock() {
        DirectLockSession s = new DirectLockSession();
        s.visit(INBOX, null, "owner", true);
        s.unlockInbox();
        s.visit(DIRECT, null, "owner", true);
        s.visit(INBOX, null, "owner", true);
        assertFalse(s.needsInboxLock(INBOX, true));
    }

    @Test public void lateAccountResolutionDoesNotPromptAgain() {
        DirectLockSession s = new DirectLockSession();
        s.visit(INBOX, null, null, true);
        s.unlockInbox();
        s.visit(THREAD, "a", "owner", true);
        assertFalse(s.needsInboxLock(THREAD, true));
    }
}
