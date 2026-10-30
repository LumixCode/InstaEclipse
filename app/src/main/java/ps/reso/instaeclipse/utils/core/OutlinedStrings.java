package ps.reso.instaeclipse.utils.core;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.query.matchers.base.StringMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import ps.reso.instaeclipse.Xposed.Module;
import ps.reso.instaeclipse.utils.log.ModuleLog;

final class OutlinedStrings {

    private static final String TABLE_SHAPE = "(I)Ljava/lang/String;";
    private static final int MAX_PARTIAL_SLOTS = 4;
    private static final int MIN_TABLE_STRINGS = 8;

    private static final class Slot {
        final String table;
        final int index;

        Slot(String table, int index) {
            this.table = table;
            this.index = index;
        }
    }

    private OutlinedStrings() {}

    static boolean onlyTables(List<MethodData> found) {
        for (MethodData m : found) {
            if (!m.getDescriptor().endsWith(TABLE_SHAPE)) return false;
        }
        return true;
    }

    static List<MethodData> withoutTables(List<MethodData> found) {
        List<MethodData> kept = new ArrayList<>(found.size());
        for (MethodData m : found) {
            if (!m.getDescriptor().endsWith(TABLE_SHAPE) || m.getUsingStrings().size() < MIN_TABLE_STRINGS) kept.add(m);
            else ModuleLog.detail("(DexKitCache) Skipped string table " + m.getDescriptor());
        }
        return kept;
    }

    static List<MethodData> search(DexKitBridge bridge, FindMethod query) {
        MethodMatcher matcher = query.getMatcher();
        List<StringMatcher> strings = matcher == null ? null : matcher.getUsingStringsMatcher();
        ClassLoader loader = Module.hostClassLoader;
        if (strings == null || strings.isEmpty() || loader == null) return null;
        try {
            List<StringMatcher> all = new ArrayList<>(strings);
            List<StringMatcher> outlined = new ArrayList<>();
            List<Slot> first = new ArrayList<>();
            for (StringMatcher string : all) {
                List<Slot> slots = slots(bridge, string, loader);
                if (slots.isEmpty()) continue;
                outlined.add(string);
                first.add(slots.get(0));
                for (Slot slot : slots) {
                    List<MethodData> found = swapped(bridge, query, List.of(string), List.of(slot));
                    if (found.isEmpty() || onlyTables(found)) continue;
                    DexKitQueryCache.noteFallback(string.getValue(), "table " + slot.table + " #" + slot.index);
                    return found;
                }
            }
            if (outlined.size() < 2) return null;
            List<MethodData> found = swapped(bridge, query, outlined, first);
            if (found.isEmpty() || onlyTables(found)) return null;
            DexKitQueryCache.noteFallback(outlined.get(0).getValue(), outlined.size() + " strings in tables");
            return found;
        } catch (Throwable t) {
            ModuleLog.detail("(DexKitCache) Outlined string search failed: " + t);
        }
        return null;
    }

    private static List<MethodData> swapped(DexKitBridge bridge, FindMethod query, List<StringMatcher> outlined,
                                            List<Slot> slots) {
        MethodMatcher matcher = query.getMatcher();
        List<StringMatcher> strings = matcher.getUsingStringsMatcher();
        List<StringMatcher> kept = new ArrayList<>(strings);
        strings.removeAll(outlined);
        for (Slot slot : slots) matcher.addInvoke(slot.table).addUsingNumber(slot.index);
        try {
            return bridge.findMethod(query);
        } finally {
            strings.clear();
            strings.addAll(kept);
            for (int i = 0; i < slots.size(); i++) {
                removeLast(matcher.getInvokeMethodsMatcher().getMethodsMatcher());
                removeLast(matcher.getUsingNumbersMatcher());
            }
        }
    }

    private static void removeLast(List<?> list) {
        list.remove(list.size() - 1);
    }

    private static List<Slot> slots(DexKitBridge bridge, StringMatcher string, ClassLoader loader) throws Exception {
        List<Slot> exact = new ArrayList<>();
        List<Slot> partial = new ArrayList<>();
        for (MethodData table : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                .returnType("java.lang.String").paramTypes("int").addUsingString(string)))) {
            Method method = table.getMethodInstance(loader);
            if (!isTable(method.getDeclaringClass())) continue;
            method.setAccessible(true);
            int last = table.getUsingStrings().size();
            for (int i = 0; i <= last; i++) {
                String value = (String) method.invoke(null, i);
                if (value == null) continue;
                if (value.equals(string.getValue())) exact.add(new Slot(table.getDescriptor(), i));
                else if (matches(string, value)) partial.add(new Slot(table.getDescriptor(), i));
            }
        }
        if (!exact.isEmpty()) return exact;
        return partial.size() <= MAX_PARTIAL_SLOTS ? partial : Collections.emptyList();
    }

    private static boolean isTable(Class<?> c) {
        if (c.getSuperclass() != Object.class || c.getDeclaredFields().length != 0) return false;
        for (Method m : c.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (!Modifier.isStatic(m.getModifiers()) || m.getReturnType() != String.class
                    || p.length != 1 || p[0] != int.class) return false;
        }
        return true;
    }

    private static boolean matches(StringMatcher string, String value) {
        String wanted = string.getValue();
        if (wanted == null) return false;
        boolean fold = string.getIgnoreCase();
        String a = fold ? value.toLowerCase(Locale.ROOT) : value;
        String b = fold ? wanted.toLowerCase(Locale.ROOT) : wanted;
        StringMatchType type = string.getMatchType();
        if (type == StringMatchType.Equals) return a.equals(b);
        if (type == StringMatchType.StartsWith) return a.startsWith(b);
        if (type == StringMatchType.EndsWith) return a.endsWith(b);
        return type == StringMatchType.Contains && a.contains(b);
    }
}
