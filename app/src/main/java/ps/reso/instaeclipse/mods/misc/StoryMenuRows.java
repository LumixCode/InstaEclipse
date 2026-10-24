package ps.reso.instaeclipse.mods.misc;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

final class StoryMenuRows {

    private StoryMenuRows() {}

    static CharSequence[] append(CharSequence[] original, List<? extends CharSequence> rows) {
        List<CharSequence> add = new ArrayList<>();
        for (CharSequence row : rows) {
            if (row == null || contains(original, row) || contains(add, row)) continue;
            add.add(row);
        }
        if (add.isEmpty()) return original;
        CharSequence[] extended = new CharSequence[original.length + add.size()];
        System.arraycopy(original, 0, extended, 0, original.length);
        for (int i = 0; i < add.size(); i++) extended[original.length + i] = add.get(i);
        return extended;
    }

    private static boolean contains(CharSequence[] items, CharSequence row) {
        for (CharSequence cs : items) if (cs != null && cs.toString().contentEquals(row)) return true;
        return false;
    }

    private static boolean contains(List<CharSequence> items, CharSequence row) {
        for (CharSequence cs : items) if (cs.toString().contentEquals(row)) return true;
        return false;
    }

    static boolean owns(CharSequence tapped, String label, boolean counted) {
        if (tapped == null) return false;
        String t = tapped.toString();
        if (t.equals(label)) return true;
        if (!counted || !t.startsWith(label + " (") || !t.endsWith(")")) return false;
        String count = t.substring(label.length() + 2, t.length() - 1);
        if (count.isEmpty()) return false;
        for (int i = 0; i < count.length(); i++) if (count.charAt(i) < '0' || count.charAt(i) > '9') return false;
        return true;
    }

    static CharSequence tapped(Object[] args) {
        for (int i = args.length - 1; i >= 0; i--) if (args[i] instanceof CharSequence cs) return cs;
        return null;
    }

    static CharSequence ownedAt(CharSequence[] items, int which, Predicate<CharSequence> owned) {
        if (items == null || which < 0 || which >= items.length) return null;
        CharSequence item = items[which];
        return item != null && owned.test(item) ? item : null;
    }
}
