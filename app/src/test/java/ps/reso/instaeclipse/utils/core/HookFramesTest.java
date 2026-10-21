package ps.reso.instaeclipse.utils.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

public class HookFramesTest {

    @Test
    public void afterSeesTheValueItsOwnBeforeLeft() {
        HookFrames.Slot slot = new HookFrames.Slot();
        slot.enter().put("previous", "story-1");
        assertEquals("story-1", slot.exit().get("previous"));
        assertEquals(0, slot.depth());
    }

    @Test
    public void nestedCallsCloseInReverseOrder() {
        HookFrames.Slot slot = new HookFrames.Slot();
        slot.enter().put("k", "outer");
        slot.enter().put("k", "inner");
        assertEquals("inner", slot.exit().get("k"));
        assertEquals("outer", slot.exit().get("k"));
    }

    @Test
    public void currentIsTheInnermostOpenFrame() {
        HookFrames.Slot slot = new HookFrames.Slot();
        HookFrames.Frame outer = slot.enter();
        HookFrames.Frame inner = slot.enter();
        assertSame(inner, slot.current());
        slot.current().put("claim", Boolean.TRUE);
        assertEquals(Boolean.TRUE, slot.exit().get("claim"));
        assertSame(outer, slot.current());
        assertNull(slot.exit().get("claim"));
    }

    @Test
    public void exitWithoutEnterIsEmptyAndHarmless() {
        HookFrames.Slot slot = new HookFrames.Slot();
        assertNull(slot.exit().get("anything"));
        slot.current().put("ignored", 1);
        assertEquals(0, slot.depth());
    }

    @Test
    public void slotsAndThreadsDoNotShareFrames() throws Exception {
        HookFrames.Slot a = new HookFrames.Slot();
        HookFrames.Slot b = new HookFrames.Slot();
        a.enter().put("k", "a");
        b.enter().put("k", "b");
        AtomicReference<Object> seen = new AtomicReference<>("unset");
        Thread other = new Thread(() -> seen.set(a.exit().get("k")));
        other.start();
        other.join();
        assertNull(seen.get());
        assertEquals("b", b.exit().get("k"));
        assertEquals("a", a.exit().get("k"));
    }
}
