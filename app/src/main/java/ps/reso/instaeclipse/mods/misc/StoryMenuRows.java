package ps.reso.instaeclipse.mods.misc;

import java.util.List;
import java.util.function.Predicate;

final class StoryMenuRows {

    private StoryMenuRows() {}

    static CharSequence[] append(CharSequence[] original, List<? extends CharSequence> rows) {
        if (rows.isEmpty()) return original;
        String first = rows.get(0).toString();
        for (CharSequence cs : original) if (first.contentEquals(cs)) return original;
        CharSequence[] extended = new CharSequence[original.length + rows.size()];
        System.arraycopy(original, 0, extended, 0, original.length);
        for (int i = 0; i < rows.size(); i++) extended[original.length + i] = rows.get(i);
        return extended;
    }

    static boolean owns(CharSequence tapped, String label, boolean counted) {
        if (tapped == null) return false;
        String t = tapped.toString();
        return counted ? t.startsWith(label) : t.equals(label);
    }

    static CharSequence tapped(Object[] args) {
        for (Object a : args) if (a instanceof CharSequence cs) return cs;
        return null;
    }

    static CharSequence ownedAt(CharSequence[] items, int which, Predicate<CharSequence> owned) {
        return null;
    }
}
