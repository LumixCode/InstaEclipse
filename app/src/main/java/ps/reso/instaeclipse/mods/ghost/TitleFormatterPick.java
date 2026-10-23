package ps.reso.instaeclipse.mods.ghost;

import android.content.Context;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

final class TitleFormatterPick {
    static final String LEGACY_FORMATTER = "X.01eU";

    private TitleFormatterPick() {}

    static Method pick(List<Method> formatters, List<Method> callers) {
        for (Method method : callers) {
            Class<?>[] parameters = method.getParameterTypes();
            if (!method.getDeclaringClass().getName().equals(LEGACY_FORMATTER) || !Modifier.isStatic(method.getModifiers())
                    || method.getReturnType() != String.class || parameters.length != 3
                    || !Context.class.isAssignableFrom(parameters[0])) continue;
            return method;
        }
        return null;
    }
}
