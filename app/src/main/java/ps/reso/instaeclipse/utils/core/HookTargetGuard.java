package ps.reso.instaeclipse.utils.core;

import java.lang.reflect.Modifier;
import java.util.List;

/**
 * Rejects DexKit hook targets that R8 folded into something bigger. A structural matcher that hit
 * a dedicated method on one Instagram build can land on a shared one on the next:
 * <ul>
 *   <li>R8 inlines small use-cases into their callers, which then carry the inlined method's trace
 *   marker after their own (IG 446 and 451: ContentDeepDiveUseCase.getUiState sits inside the
 *   whole Clips MediaInfoUseCase.getUiState).</li>
 *   <li>R8 merges unrelated lambdas into one class whose invoke() switches on a {@code $t} int
 *   field (IG 451: X.0Q77, about 40 cases).</li>
 *   <li>R8 merges the overrides of unrelated final classes into their abstract base (IG 451: the
 *   feed repost button shares one Litho render with the carousel pager).</li>
 * </ul>
 * A hook that nulls, replaces or blanks a result must refuse all three.
 *
 * <p>Pure Java (no Android) so it can be unit tested.
 */
public final class HookTargetGuard {

    /** Instagram's systrace markers look like "android_purge_26_q3_Class_method". */
    static final String MARKER_PREFIX = "android_purge_";
    /** Dalvik packed-switch opcode. */
    static final int PACKED_SWITCH = 0x2b;
    /** The int field R8 gives a lambda group to pick the merged lambda's case. */
    static final String LAMBDA_GROUP_FIELD = "$t";

    private HookTargetGuard() {}

    /**
     * True when {@code marker} is the first trace marker in {@code usingStrings}, i.e. the method's
     * own. A method opens its trace section before anything inlined into it, and DexKit lists a
     * method's strings in bytecode order.
     */
    public static boolean ownsMarker(List<String> usingStrings, String marker) {
        if (usingStrings == null || marker == null) return false;
        for (String s : usingStrings) {
            if (s != null && s.startsWith(MARKER_PREFIX)) return s.equals(marker);
        }
        return false;
    }

    /** True for an R8 lambda group: a {@code $t} int field and a method that switches on it. */
    public static boolean isMergedLambda(Class<?> declaringClass, List<Integer> opCodes) {
        return hasLambdaGroupField(declaringClass) && opCodes != null && opCodes.contains(PACKED_SWITCH);
    }

    static boolean hasLambdaGroupField(Class<?> c) {
        try {
            return c != null && c.getDeclaredField(LAMBDA_GROUP_FIELD).getType() == int.class;
        } catch (Throwable t) {
            return false;
        }
    }

    /** True when a method declared on this class is the class's own: final and not abstract. */
    public static boolean ownClass(Class<?> declaringClass) {
        if (declaringClass == null) return false;
        int modifiers = declaringClass.getModifiers();
        return Modifier.isFinal(modifiers) && !Modifier.isAbstract(modifiers);
    }
}
