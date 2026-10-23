package ps.reso.instaeclipse.mods.reels;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

final class PagerJumpFilter {

    private PagerJumpFilter() {}

    static List<Method> jumps(Class<?> pager) {
        List<Method> out = new ArrayList<>();
        for (Method method : pager.getDeclaredMethods()) {
            if (isJump(method)) out.add(method);
        }
        return out;
    }

    static boolean isJump(Method method) {
        Class<?>[] p = method.getParameterTypes();
        if (Modifier.isStatic(method.getModifiers()) || method.getReturnType() != void.class
                || p.length == 0 || p[0] != int.class) return false;
        if (p.length == 1) return method.getName().equals("setCurrentItem");
        return p.length == 2 && p[1] == boolean.class;
    }
}
