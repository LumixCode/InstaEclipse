package ps.reso.instaeclipse.mods.ui;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class LockGateThrottleTest {
    private static final long FRAME = 16L;
    private static final long INTERVAL = LockDirectMessagesHook.GateThrottle.INTERVAL_MS;
    private static final long SETTLE = LockDirectMessagesHook.GateThrottle.SETTLE_MS;

    /** Draws a frame every FRAME ms in [from, to), running posted checks when due; returns check times. */
    private static List<Long> run(LockDirectMessagesHook.GateThrottle throttle, long from, long to) {
        List<Long> checks = new ArrayList<>();
        long postedAt = -1L;
        for (long now = from; now < to; now++) {
            if (now == postedAt) {
                postedAt = -1L;
                if (throttle.trailingDue(now)) checks.add(now);
            }
            if ((now - from) % FRAME != 0) continue;
            long delay = throttle.preDraw(now);
            if (delay == 0L) checks.add(now);
            else if (delay > 0L) {
                assertEquals("one posted check at a time", -1L, postedAt);
                postedAt = now + delay;
            }
        }
        return checks;
    }

    @Test public void steadyFramesCheckAboutTenTimesASecondAndNoFrameWaitsLongerThanTheInterval() {
        LockDirectMessagesHook.GateThrottle throttle = new LockDirectMessagesHook.GateThrottle();
        List<Long> checks = run(throttle, 10_000L, 11_000L);
        assertTrue("checks: " + checks, checks.size() <= 1_000L / INTERVAL + 1);
        for (long frame = 10_000L; frame < 11_000L - INTERVAL; frame += FRAME) {
            boolean covered = false;
            for (long check : checks) covered |= check >= frame && check <= frame + INTERVAL;
            assertTrue("frame " + frame + " unchecked, checks: " + checks, covered);
        }
    }

    @Test public void layoutPassChecksThatFrameAndEveryFrameUntilSettled() {
        LockDirectMessagesHook.GateThrottle throttle = new LockDirectMessagesHook.GateThrottle();
        assertEquals(0L, throttle.preDraw(10_000L));
        throttle.laidOut(10_001L); // a new screen was laid out right after a check
        for (long now = 10_001L; now < 10_001L + SETTLE; now += FRAME) {
            assertEquals("frame " + now, 0L, throttle.preDraw(now));
        }
        long last = 10_001L + ((SETTLE - 1) / FRAME) * FRAME;
        assertTrue(throttle.preDraw(last + FRAME) > 0L); // settled: throttled again
    }

    @Test public void postedCheckIsSkippedWhenALaterFrameAlreadyChecked() {
        LockDirectMessagesHook.GateThrottle throttle = new LockDirectMessagesHook.GateThrottle();
        assertEquals(0L, throttle.preDraw(10_000L));
        assertEquals(INTERVAL - FRAME, throttle.preDraw(10_000L + FRAME));
        assertTrue(throttle.preDraw(10_000L + 2 * FRAME) < 0L); // already posted
        assertEquals(0L, throttle.preDraw(10_000L + INTERVAL));
        assertFalse(throttle.trailingDue(10_000L + INTERVAL));
        // The next skipped frame may post again.
        assertTrue(throttle.preDraw(10_000L + INTERVAL + FRAME) > 0L);
        assertTrue(throttle.trailingDue(10_000L + 2 * INTERVAL));
    }

    @Test public void firstFrameChecks() {
        assertEquals(0L, new LockDirectMessagesHook.GateThrottle().preDraw(0L));
    }
}
