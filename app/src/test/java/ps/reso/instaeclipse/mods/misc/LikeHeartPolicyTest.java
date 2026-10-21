package ps.reso.instaeclipse.mods.misc;

import org.junit.Test;
import static org.junit.Assert.*;

public class LikeHeartPolicyTest {
    private static final int SLOP = 12, BAND = 200;

    @Test public void tapPicksTheHeartUnderIt() {
        int[][] hearts = {{0, 500, 48, 548}, {0, 1100, 48, 1148}};
        assertEquals(1, LikeHeartPolicy.pick(hearts, true, 24, 1120, false, null, SLOP, BAND));
        assertEquals("within the slop", 0, LikeHeartPolicy.pick(hearts, true, 56, 520, false, null, SLOP, BAND));
    }

    @Test public void tapAwayFromEveryHeartPicksNone() {
        int[][] hearts = {{0, 500, 48, 548}};
        assertEquals(-1, LikeHeartPolicy.pick(hearts, true, 300, 300, false, null, SLOP, BAND));
    }

    @Test public void feedDoubleTapPicksTheHeartUnderTheMedia() {
        // Media 0..400 tall; its row's heart at the left, the comment preview's heart further down
        // at the right, and the previous post's heart above it.
        int[] media = {0, 0, 400, 400};
        int[][] hearts = {{376, 500, 400, 524}, {0, 400, 48, 448}, {0, -60, 48, -12}};
        assertEquals(1, LikeHeartPolicy.pick(hearts, true, 360, 200, true, media, SLOP, BAND));
    }

    @Test public void doubleTapIgnoresHeartsOutsideThePost() {
        int[] media = {0, 100, 400, 500};
        int[][] hearts = {{0, 20, 48, 68}, {0, 900, 48, 948}};
        assertEquals(-1, LikeHeartPolicy.pick(hearts, true, 200, 300, true, media, SLOP, BAND));
    }

    @Test public void reelDoubleTapBelowTheHeartStillPicksIt() {
        // The reels heart sits on the video, so a tap under it finds it above.
        int[] media = {0, 0, 1080, 2000};
        int[][] hearts = {{980, 1100, 1060, 1180}};
        assertEquals(0, LikeHeartPolicy.pick(hearts, true, 500, 1700, true, media, SLOP, BAND));
    }

    @Test public void withoutATouchOnlyALoneHeartIsPicked() {
        assertEquals(0, LikeHeartPolicy.pick(new int[][]{{0, 0, 10, 10}}, false, 0, 0, false, null, SLOP, BAND));
        assertEquals(-1, LikeHeartPolicy.pick(new int[][]{{0, 0, 10, 10}, {0, 20, 10, 30}}, false, 0, 0, false, null, SLOP, BAND));
        assertEquals(-1, LikeHeartPolicy.pick(new int[0][], false, 0, 0, false, null, SLOP, BAND));
    }

    @Test public void secondPressSoonAfterTheFirstLiftInOnePlaceIsADoubleTap() {
        // First press at 1000, lifted at 1120: the gap counts from the lift, as Instagram's does.
        assertTrue(LikeHeartPolicy.isDoubleTap(1120, 500, 800, 1400, 520, 790, 300, 260));
        assertTrue("a slower press with a normal gap", LikeHeartPolicy.isDoubleTap(1150, 500, 800, 1430, 500, 800, 300, 260));
        assertFalse("too long a gap", LikeHeartPolicy.isDoubleTap(1120, 500, 800, 1450, 500, 800, 300, 260));
        assertFalse("under GestureDetector's minimum gap", LikeHeartPolicy.isDoubleTap(1120, 500, 800, 1150, 500, 800, 300, 260));
        assertFalse("too far apart", LikeHeartPolicy.isDoubleTap(1120, 100, 800, 1300, 900, 800, 300, 260));
        assertFalse("no earlier lift", LikeHeartPolicy.isDoubleTap(0, 0, 0, 200, 0, 0, 300, 260));
    }

    @Test public void matrixTurnsTheLikeRedBlueAndKeepsGreys() {
        float[] m = LikeHeartPolicy.redToBlueMatrix(LikeHeartPolicy.LIKE_RED, LikeHeartPolicy.LIKE_BLUE);
        assertArrayEquals(LikeHeartPolicy.channels(LikeHeartPolicy.LIKE_BLUE),
                apply(m, LikeHeartPolicy.channels(LikeHeartPolicy.LIKE_RED)), 1e-4f);
        for (float grey : new float[]{0f, 0.5f, 1f}) {
            assertArrayEquals(new float[]{grey, grey, grey}, apply(m, new float[]{grey, grey, grey}), 1e-4f);
        }
        assertEquals("alpha passes through", 1f, m[18], 0f);
    }

    private static float[] apply(float[] m, float[] c) {
        float[] out = new float[3];
        for (int row = 0; row < 3; row++) {
            out[row] = m[row * 5] * c[0] + m[row * 5 + 1] * c[1] + m[row * 5 + 2] * c[2] + m[row * 5 + 4];
        }
        return out;
    }
}
