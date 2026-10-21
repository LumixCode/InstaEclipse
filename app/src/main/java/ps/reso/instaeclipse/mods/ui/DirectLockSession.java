package ps.reso.instaeclipse.mods.ui;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Unlock grants follow navigation boundaries, never an intermediate layout with no header. */
public final class DirectLockSession {
    public enum Screen { UNKNOWN, OTHER, INBOX, THREAD, DIRECT }

    private boolean inboxUnlocked;
    private final Set<String> unlockedThreads = new HashSet<>();
    private String account;
    private String activeThread;

    public void visit(Screen screen, String thread, String user, boolean relock) {
        if (user != null && !Objects.equals(account, user)) {
            if (account != null) reset();
            account = user;
        }
        if (screen == Screen.UNKNOWN) return;
        if (relock) {
            if (screen == Screen.OTHER) reset();
            if (screen == Screen.INBOX || screen == Screen.OTHER
                    || (screen == Screen.THREAD && thread != null && !thread.equals(activeThread))) {
                if (activeThread != null) unlockedThreads.remove(activeThread);
            }
        }
        if (screen == Screen.THREAD && thread != null) activeThread = thread;
        else if (screen == Screen.INBOX || screen == Screen.OTHER) activeThread = null;
    }

    public boolean needsInboxLock(Screen screen, boolean enabled) {
        return enabled && !inboxUnlocked && (screen == Screen.INBOX
                || screen == Screen.THREAD || screen == Screen.DIRECT);
    }

    public boolean needsThreadLock(Screen screen, String thread, boolean selected) {
        return screen == Screen.THREAD && selected
                && (thread == null || !unlockedThreads.contains(thread));
    }

    public void unlockInbox() { inboxUnlocked = true; }

    public void unlockThread(String thread) {
        if (thread != null) unlockedThreads.add(thread);
    }

    public void background(boolean relock) {
        if (relock) reset();
    }

    public void reset() {
        inboxUnlocked = false;
        unlockedThreads.clear();
        activeThread = null;
    }
}
