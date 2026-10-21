package ps.reso.instaeclipse.mods.ghost;

/**
 * The hidden-inbox triple tap in the Direct inbox header's top-left corner, as a pure state
 * machine over one activity's touch stream (HideChatsHook feeds it from dispatchTouchEvent and
 * decides only on ACTION_DOWN whether a touch is in the corner). Three taps, each within
 * {@link #MAX_GAP_MS} of the one before, open the passcode prompt. For {@link #GUARD_MS} after
 * that, touches that start in the corner are swallowed: an extra tap would otherwise reach
 * Instagram before the prompt's window is up, or the hidden inbox's back control after a quick
 * biometric unlock. Taps before the third pass through: the corner is empty header space.
 */
final class InboxTapGesture {
    /** Longest pause between two taps of the triple tap (Android's double tap allows 300 ms). */
    static final long MAX_GAP_MS = 500L;
    /** How long after the third tap touches in the corner are dropped. */
    static final long GUARD_MS = 1000L;

    enum Verdict {
        /** Instagram gets the event. */
        PASS,
        /** The module consumes the event; Instagram never sees any of this touch. */
        SWALLOW,
        /** The third tap ended: open the prompt. The event still reaches Instagram, which got its DOWN. */
        OPEN
    }

    private final QuickTapSequence taps = QuickTapSequence.withGap(MAX_GAP_MS);
    private boolean tracking, swallowing;
    private float startX, startY;
    private long guardUntil = Long.MIN_VALUE;
    private String broken;

    /** Whether touches in the corner are still being swallowed at {@code time}. */
    boolean guarding(long time) { return time < guardUntil; }

    /** The prompt did not open after all: corner taps reach Instagram again. */
    void endGuard() { guardUntil = Long.MIN_VALUE; }

    /** Why a started sequence was just dropped, once, for the module log; else null. */
    String takeBreak() {
        String reason = broken;
        broken = null;
        return reason;
    }

    /**
     * ACTION_DOWN. {@code inCorner} is false off the inbox and, in the hidden inbox, outside the
     * guard (a tap there only counts toward the guard).
     */
    Verdict down(float x, float y, long time, boolean inCorner) {
        tracking = false;
        swallowing = inCorner && guarding(time);
        if (swallowing) return Verdict.SWALLOW;
        if (!inCorner) {
            breakSequence("touch outside the corner");
            return Verdict.PASS;
        }
        tracking = true;
        startX = x;
        startY = y;
        return Verdict.PASS;
    }

    /** Whether the current touch is a corner tap being followed or swallowed (else every event passes). */
    boolean inTouch() { return tracking || swallowing; }

    /** ACTION_MOVE and any other event inside a touch. */
    Verdict during() { return swallowing ? Verdict.SWALLOW : Verdict.PASS; }

    /** ACTION_POINTER_DOWN: a second finger makes this touch no tap. */
    Verdict pointerDown() {
        if (swallowing) return Verdict.SWALLOW;
        if (tracking) {
            tracking = false;
            breakSequence("second finger");
        }
        return Verdict.PASS;
    }

    /** ACTION_UP; {@code slop} and {@code longPress} are the platform's limits for a tap. */
    Verdict up(float x, float y, long time, long downTime, float slop, long longPress) {
        if (swallowing) {
            swallowing = false;
            return Verdict.SWALLOW;
        }
        if (!tracking) return Verdict.PASS;
        tracking = false;
        if (Math.hypot(x - startX, y - startY) > slop) {
            breakSequence("drag");
            return Verdict.PASS;
        }
        if (time - downTime > longPress) {
            breakSequence("long press");
            return Verdict.PASS;
        }
        int before = taps.count();
        long previous = taps.lastTap();
        if (taps.tap(time)) {
            guardUntil = time + GUARD_MS;
            return Verdict.OPEN;
        }
        if (before > 0 && taps.count() == 1) {
            broken = "pause of " + (time - previous) + " ms after " + before + " tap(s)";
        }
        return Verdict.PASS;
    }

    /** ACTION_CANCEL: the system (a back swipe) or a parent view took the touch. */
    Verdict cancel() {
        boolean swallowed = swallowing;
        swallowing = false;
        if (tracking) {
            tracking = false;
            breakSequence("touch cancelled");
        }
        return swallowed ? Verdict.SWALLOW : Verdict.PASS;
    }

    private void breakSequence(String reason) {
        if (taps.count() > 0) broken = reason + " after " + taps.count() + " tap(s)";
        taps.reset();
    }
}
