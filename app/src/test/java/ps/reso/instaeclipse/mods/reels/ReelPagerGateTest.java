package ps.reso.instaeclipse.mods.reels;

import org.junit.Test;

import static org.junit.Assert.*;

public class ReelPagerGateTest {
    public static class Pager {
        public boolean input = true;
        int calls;
        public void setUserInputEnabled(boolean enabled) { input = enabled; calls++; }
    }

    private ReelPagerGate gate() throws Exception {
        return new ReelPagerGate(Pager.class.getField("input"),
                Pager.class.getMethod("setUserInputEnabled", boolean.class));
    }

    @Test public void disablesBeforeScrollAndRestoresAfterLimitIncreaseOrMidnight() throws Exception {
        Pager pager = new Pager();
        ReelPagerGate gate = gate();
        gate.update(pager, true);
        assertFalse(pager.input);
        for (int i = 0; i < 100; i++) gate.update(pager, true);
        assertEquals(1, pager.calls);
        gate.update(pager, false);
        assertTrue(pager.input);
        assertEquals(2, pager.calls);
        gate.update(pager, true);
        assertFalse(pager.input);
    }

    @Test public void preservesInputDisabledByInstagram() throws Exception {
        Pager pager = new Pager();
        pager.input = false;
        ReelPagerGate gate = gate();
        gate.update(pager, true);
        gate.update(pager, false);
        assertFalse(pager.input);
        assertEquals(0, pager.calls);
    }

    @Test public void restoresLatestHostRequestAfterUnlock() throws Exception {
        Pager pager = new Pager();
        ReelPagerGate gate = gate();
        gate.update(pager, true);
        gate.rememberRequest(false);
        gate.update(pager, false);
        assertFalse(pager.input);
        gate.update(pager, true);
        gate.rememberRequest(true);
        assertFalse(pager.input);
        gate.update(pager, false);
        assertTrue(pager.input);
    }

    @Test public void repeatedUnlockedChecksDoNotOverrideHostChanges() throws Exception {
        Pager pager = new Pager();
        ReelPagerGate gate = gate();
        gate.update(pager, false);
        assertEquals(0, pager.calls);
        gate.update(pager, true);
        gate.update(pager, false);
        pager.input = false;
        gate.update(pager, false);
        assertFalse(pager.input);
    }

    @Test public void writesTheFieldWhenTheSetterWasInlinedAndReportsTheLock() throws Exception {
        Pager pager = new Pager();
        ReelPagerGate gate = new ReelPagerGate(Pager.class.getField("input"), null);
        assertFalse(gate.isLocked());
        gate.update(pager, true);
        assertFalse(pager.input);
        assertTrue(gate.isLocked());
        gate.update(pager, false);
        assertTrue(pager.input);
        assertFalse(gate.isLocked());
        assertEquals(0, pager.calls);
    }

    @Test public void separateViewerRetainsItsOwnInputPreference() throws Exception {
        Pager main = new Pager();
        Pager dm = new Pager();
        dm.input = false;
        ReelPagerGate mainGate = gate();
        ReelPagerGate dmGate = gate();
        mainGate.update(main, true);
        dmGate.update(dm, true);
        mainGate.update(main, false);
        dmGate.update(dm, false);
        assertTrue(main.input);
        assertFalse(dm.input);
    }
}
