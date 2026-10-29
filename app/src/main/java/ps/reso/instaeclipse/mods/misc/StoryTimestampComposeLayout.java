package ps.reso.instaeclipse.mods.misc;

import java.util.List;

final class StoryTimestampComposeLayout {
    private StoryTimestampComposeLayout() {}

    static boolean matches(List<String> parameters) {
        if (parameters.size() != 20 || !parameters.get(7).equals("java.lang.String")
                || !parameters.get(8).equals("kotlin.jvm.functions.Function1")
                || !parameters.get(19).equals("boolean")) return false;
        for (int i = 9; i <= 15; i++) if (!parameters.get(i).equals("int")) return false;
        for (int i = 16; i <= 18; i++) if (!parameters.get(i).equals("long")) return false;
        return true;
    }

    static void wrap(Object[] arguments) {
        arguments[15] = ((Integer) arguments[15]) | 0x1500;
    }
}
