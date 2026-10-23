package ps.reso.instaeclipse.mods.ghost;

import android.content.Context;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import ps.reso.instaeclipse.utils.ghost.UnsentIdentity;

final class TitleFormatterPick {

    private TitleFormatterPick() {}

    static Method pick(List<Method> formatters, List<Method> callers) {
        if (formatters.size() != 1 || !isFormatter(formatters.get(0))) return null;
        Class<?>[] shape = formatters.get(0).getParameterTypes();
        Method found = null;
        for (Method caller : callers) {
            Class<?>[] parameters = caller.getParameterTypes();
            if (caller.getDeclaringClass() != formatters.get(0).getDeclaringClass()
                    || !Modifier.isStatic(caller.getModifiers()) || caller.getReturnType() != String.class
                    || parameters.length != 3 || parameters[0] != shape[0] || parameters[1] != shape[1]) continue;
            if (found != null && !found.equals(caller)) return null;
            found = caller;
        }
        return found;
    }

    private static boolean isFormatter(Method method) {
        Class<?>[] parameters = method.getParameterTypes();
        return Modifier.isStatic(method.getModifiers()) && method.getReturnType() == String.class
                && parameters.length == 5 && parameters[0] == Context.class
                && parameters[1].getName().equals(UnsentIdentity.SESSION) && parameters[2] == String.class
                && parameters[3] == List.class && parameters[4] == boolean.class;
    }
}
