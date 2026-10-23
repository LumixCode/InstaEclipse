package ps.reso.instaeclipse.mods.ui;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import ps.reso.instaeclipse.utils.core.SafeReflect;

/** Which switches remove the profile's Threads button, and how its IG members are picked; kept Android-free for JVM tests. */
final class ThreadsButtonPolicy {

    private ThreadsButtonPolicy() {}

    /** Your own profile's Threads button is a Threads cross-promo, so either switch removes it. */
    static boolean hideOwnProfileButton(boolean hideThreadsButton, boolean hideThreadsSuggestions) {
        return hideThreadsButton || hideThreadsSuggestions;
    }

    /**
     * The window-wide view scan: always for "Hide Threads Button" (what it did before the gate hook),
     * and for "Hide Threads suggestions" only while the gate is not hooked. With the gate hooked the
     * button is never built, and the per-action-bar fallback covers the rest without a layout listener.
     */
    static boolean scanWindow(boolean hideThreadsButton, boolean hideThreadsSuggestions, boolean gateHooked) {
        return hideThreadsButton || (hideThreadsSuggestions && !gateHooked);
    }

    /**
     * The Threads button gate in the class of the "is Threads installed" check: its only instance
     * method {@code (UserSession, boolean, boolean) -> boolean} (IG 451: X.0AZB.A06, 446: X.0EHO.A04).
     * Null when there is none or more than one, so an ambiguous build is left alone.
     */
    static Method gate(Class<?> owner, String sessionClass) {
        Method found = null;
        for (Method m : SafeReflect.methods(owner)) {
            if (Modifier.isStatic(m.getModifiers()) || m.isSynthetic() || m.getReturnType() != boolean.class) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length != 3 || !p[0].getName().equals(sessionClass) || p[1] != boolean.class || p[2] != boolean.class) continue;
            if (found != null) return null;
            found = m;
        }
        return found;
    }

    /** ProfileActionBar's bind method: its only instance method with six parameters that returns void. */
    static Method bindMethod(Class<?> bar) {
        Method found = null;
        for (Method m : SafeReflect.methods(bar)) {
            if (Modifier.isStatic(m.getModifiers()) || m.isSynthetic()
                    || m.getReturnType() != void.class || m.getParameterCount() != 6) continue;
            if (found != null) return null;
            found = m;
        }
        return found;
    }
}
