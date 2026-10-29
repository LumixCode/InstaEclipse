package ps.reso.instaeclipse.mods.ghost;

final class InboxTapGesture {
    static final long MAX_GAP_MS = 500L;
    static final long GUARD_MS = 1000L;

    enum Verdict {
        PASS,
        SWALLOW,
        OPEN
    }

    private final QuickTapSequence taps = QuickTapSequence.withGap(MAX_GAP_MS);
    private boolean tracking, swallowing;
    private float startX, startY;
    private long guardUntil = Long.MIN_VALUE;
    private String broken;

    boolean guarding(long time) { return time < guardUntil; }

    void endGuard() { guardUntil = Long.MIN_VALUE; }

    String takeBreak() {
        String reason = broken;
        broken = null;
        return reason;
    }

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

    boolean inTouch() { return tracking || swallowing; }

    Verdict during() { return swallowing ? Verdict.SWALLOW : Verdict.PASS; }

    Verdict pointerDown() {
        if (swallowing) return Verdict.SWALLOW;
        if (tracking) {
            tracking = false;
            breakSequence("second finger");
        }
        return Verdict.PASS;
    }

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
