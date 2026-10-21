package ps.reso.instaeclipse.mods.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class VideoUrlRankingTest {

    private static final String M86 = "https://scontent.cdninstagram.com/o1/v/t16/f2/m86/a.mp4";
    private static final String M86_ENCODED = "https://scontent.cdninstagram.com/v/t?u=%2Fm86%2Fb.mp4";
    private static final String P480 = "https://scontent.cdninstagram.com/o1/v/t2/f2/m69/p480.mp4";
    private static final String P720 = "https://scontent.cdninstagram.com/o1/v/t2/f2/m69/p720.mp4";
    private static final String P1080 = "https://scontent.cdninstagram.com/o1/v/t2/f2/m69/p1080.mp4";
    private static final String Q720 = "https://scontent.cdninstagram.com/o1/v/t2/f2/m69/q720.mp4";

    private static Map<String, Integer> heights(Object... pairs) {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], (Integer) pairs[i + 1]);
        return m;
    }

    @Test
    public void tallestVersionWinsOverListOrderAndM86() {
        VideoUrlRanking.Pick p = VideoUrlRanking.pick(List.of(M86, P480, P1080, P720),
                heights(M86, 720, P480, 480, P1080, 1080, P720, 720));
        assertEquals(P1080, p.url);
        assertEquals(1080, p.height);
    }

    @Test
    public void m86WinsATieWhicheverComesFirst() {
        VideoUrlRanking.Pick p = VideoUrlRanking.pick(List.of(P720, M86),
                heights(P720, 720, M86, 720));
        assertEquals(M86, p.url);
        assertEquals(720, p.height);
    }

    @Test
    public void firstFoundWinsATieWithoutM86() {
        VideoUrlRanking.Pick p = VideoUrlRanking.pick(List.of(P720, Q720), heights(P720, 720, Q720, 720));
        assertEquals(P720, p.url);
    }

    @Test
    public void unknownHeightsFallBackToFirstM86ThenFirstUrl() {
        VideoUrlRanking.Pick p = VideoUrlRanking.pick(List.of(P480, M86_ENCODED, M86),
                Collections.emptyMap());
        assertEquals(M86_ENCODED, p.url);
        assertTrue(p.height <= 0);

        p = VideoUrlRanking.pick(List.of(P720, P480), null);
        assertEquals(P720, p.url);
        assertTrue(p.height <= 0);
    }

    @Test
    public void zeroHeightCountsAsUnknown() {
        VideoUrlRanking.Pick p = VideoUrlRanking.pick(List.of(P480, M86), heights(P480, 0));
        assertEquals(M86, p.url);
        assertTrue(p.height <= 0);
    }

    @Test
    public void aKnownHeightBeatsUnknownOnes() {
        VideoUrlRanking.Pick p = VideoUrlRanking.pick(List.of(M86, P480), heights(P480, 480));
        assertEquals(P480, p.url);
        assertEquals(480, p.height);
    }

    @Test
    public void emptyOrNullListPicksNothing() {
        assertNull(VideoUrlRanking.pick(Collections.emptyList(), heights(P480, 480)));
        assertNull(VideoUrlRanking.pick(null, null));
    }

    @Test
    public void detectsCombinedStreamInPlainAndEncodedPaths() {
        assertTrue(VideoUrlRanking.isCombinedStream(M86));
        assertTrue(VideoUrlRanking.isCombinedStream(M86_ENCODED));
        assertFalse(VideoUrlRanking.isCombinedStream(P720));
    }
}
