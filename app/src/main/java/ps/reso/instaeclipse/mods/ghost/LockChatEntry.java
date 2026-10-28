package ps.reso.instaeclipse.mods.ghost;

import ps.reso.instaeclipse.R;

public final class LockChatEntry {

    public enum Step { SET_PASSCODE, LOCK, AUTHENTICATE, UNLOCK }

    public final boolean shown;
    public final boolean locked;
    public final boolean enablesFeature;
    public final int label;
    public final Step step;

    private LockChatEntry(boolean shown, boolean locked, boolean enablesFeature, int label, Step step) {
        this.shown = shown;
        this.locked = locked;
        this.enablesFeature = enablesFeature;
        this.label = label;
        this.step = step;
    }

    public static LockChatEntry of(boolean passcodeSet, boolean listed, boolean featureOn, boolean menuInUse) {
        boolean shown = menuInUse || passcodeSet || featureOn || listed;
        if (listed && featureOn) {
            return new LockChatEntry(shown, true, false, R.string.ig_lock_chat_unlock,
                    passcodeSet ? Step.AUTHENTICATE : Step.UNLOCK);
        }
        return new LockChatEntry(shown, false, !featureOn, R.string.ig_lock_chat,
                passcodeSet ? Step.LOCK : Step.SET_PASSCODE);
    }
}
