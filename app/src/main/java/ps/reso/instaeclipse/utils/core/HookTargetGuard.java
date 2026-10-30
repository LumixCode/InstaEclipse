package ps.reso.instaeclipse.utils.core;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;

public final class HookTargetGuard {

    static final String MARKER_PREFIX = "android_purge_";
    static final int PACKED_SWITCH = 0x2b;
    static final String LAMBDA_GROUP_FIELD = "$t";

    private HookTargetGuard() {}

    public static boolean ownsMarker(List<String> usingStrings, String marker) {
        if (usingStrings == null || marker == null) return false;
        for (String s : usingStrings) {
            if (s != null && s.startsWith(MARKER_PREFIX)) return s.equals(marker);
        }
        return false;
    }

    public static boolean isMergedLambda(Class<?> declaringClass, List<Integer> opCodes) {
        return hasLambdaGroupField(declaringClass) && opCodes != null && opCodes.contains(PACKED_SWITCH);
    }

    static boolean hasLambdaGroupField(Class<?> c) {
        return lambdaGroupField(c) != null;
    }

    public static Field lambdaGroupField(Class<?> c) {
        try {
            Field field = c == null ? null : c.getDeclaredField(LAMBDA_GROUP_FIELD);
            return field != null && field.getType() == int.class ? field : null;
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean ownClass(Class<?> declaringClass) {
        if (declaringClass == null) return false;
        int modifiers = declaringClass.getModifiers();
        return Modifier.isFinal(modifiers) && !Modifier.isAbstract(modifiers);
    }
}
