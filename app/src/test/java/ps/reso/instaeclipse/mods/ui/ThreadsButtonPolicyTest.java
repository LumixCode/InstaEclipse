package ps.reso.instaeclipse.mods.ui;

import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.*;

public class ThreadsButtonPolicyTest {
    static final class Session {}
    interface Fn {}

    // Shape of IG 451's X.0AZB (446: X.0EHO): static helpers next to the one instance gate.
    @SuppressWarnings("unused")
    static final class GateOwner {
        static boolean A02(Object config, Session s, String pkg, int i, boolean a, boolean b) { return false; }
        static boolean A03(Session s) { return false; }
        static boolean A05(Session s, Fn f, boolean a, boolean b) { return false; }
        static boolean A07(Session s, boolean a, boolean b) { return false; }
        final boolean A06(Session s, boolean isSelf, boolean isAiUser) { return isSelf && !isAiUser; }
    }

    @SuppressWarnings("unused")
    static final class TwoGates {
        final boolean A(Session s, boolean a, boolean b) { return a; }
        final boolean B(Session s, boolean a, boolean b) { return b; }
    }

    @SuppressWarnings("unused")
    static final class NoGate {
        final boolean A(Object s, boolean a, boolean b) { return a; }
        final Boolean B(Session s, boolean a, boolean b) { return b; }
        final boolean C(Session s, boolean a) { return a; }
    }

    // Shape of com.instagram.profile.actionbar.ProfileActionBar on IG 451.
    @SuppressWarnings("unused")
    static final class Bar {
        final void A00(Session s, Object activity, Object model, Object listener, Object context, Fn fn) {}
        final Integer getComposedHeightPx() { return null; }
        static void helper(Object a, Object b, Object c, Object d, Object e, Object f) {}
    }

    @SuppressWarnings("unused")
    static final class TwoBinds {
        final void A(Object a, Object b, Object c, Object d, Object e, Object f) {}
        final void B(Object a, Object b, Object c, Object d, Object e, Object f) {}
    }

    @Test public void eitherSwitchHidesOwnProfileButton() {
        assertTrue(ThreadsButtonPolicy.hideOwnProfileButton(true, false));
        assertTrue(ThreadsButtonPolicy.hideOwnProfileButton(false, true));
        assertTrue(ThreadsButtonPolicy.hideOwnProfileButton(true, true));
        assertFalse(ThreadsButtonPolicy.hideOwnProfileButton(false, false));
    }

    @Test public void windowScanKeepsThreadsButtonAndFallsBackForSuggestions() {
        assertTrue(ThreadsButtonPolicy.scanWindow(true, false, true));
        assertTrue(ThreadsButtonPolicy.scanWindow(true, false, false));
        assertTrue(ThreadsButtonPolicy.scanWindow(false, true, false));
        assertFalse(ThreadsButtonPolicy.scanWindow(false, true, true));
        assertFalse(ThreadsButtonPolicy.scanWindow(false, false, false));
    }

    @Test public void picksTheInstanceGate() {
        Method gate = ThreadsButtonPolicy.gate(GateOwner.class, Session.class.getName());
        assertNotNull(gate);
        assertEquals("A06", gate.getName());
    }

    @Test public void refusesAmbiguousOrMissingGate() {
        assertNull(ThreadsButtonPolicy.gate(TwoGates.class, Session.class.getName()));
        assertNull(ThreadsButtonPolicy.gate(NoGate.class, Session.class.getName()));
        assertNull(ThreadsButtonPolicy.gate(GateOwner.class, "com.instagram.common.session.UserSession"));
    }

    @Test public void picksTheBindMethod() {
        Method bind = ThreadsButtonPolicy.bindMethod(Bar.class);
        assertNotNull(bind);
        assertEquals("A00", bind.getName());
        assertNull(ThreadsButtonPolicy.bindMethod(TwoBinds.class));
        assertNull(ThreadsButtonPolicy.bindMethod(NoGate.class));
    }
}
