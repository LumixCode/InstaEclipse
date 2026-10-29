package ps.reso.instaeclipse.mods.ui;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import ps.reso.instaeclipse.utils.core.SafeReflect;

final class ThreadsButtonPolicy {

    private ThreadsButtonPolicy() {}

    static boolean hideOwnProfileButton(boolean hideThreadsButton, boolean hideThreadsSuggestions) {
        return hideThreadsButton || hideThreadsSuggestions;
    }

    static boolean scanWindow(boolean hideThreadsButton, boolean hideThreadsSuggestions, boolean gateHooked) {
        return hideThreadsButton || (hideThreadsSuggestions && !gateHooked);
    }

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
