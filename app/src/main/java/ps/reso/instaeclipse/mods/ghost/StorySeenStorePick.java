package ps.reso.instaeclipse.mods.ghost;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import ps.reso.instaeclipse.utils.core.SafeReflect;

final class StorySeenStorePick {
    record Caller(String name, List<String> interfaces, List<List<String>> voidParams) {}

    private StorySeenStorePick() {}

    static String pick(String item, List<Caller> callers) {
        String picked = null;
        for (Caller caller : callers) {
            if (caller.name().equals(item) || caller.name().equals(picked) || !isStore(item, caller)) continue;
            if (picked != null) return null;
            picked = caller.name();
        }
        return picked;
    }

    private static boolean isStore(String item, Caller caller) {
        for (String type : caller.interfaces()) if (type.startsWith("android.")) return false;
        for (List<String> params : caller.voidParams()) if (params.contains(item)) return true;
        return false;
    }

    static Caller shape(Class<?> type) {
        List<String> interfaces = new ArrayList<>();
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Class<?> implemented : c.getInterfaces()) interfaces.add(implemented.getName());
        }
        List<List<String>> voidParams = new ArrayList<>();
        for (Method method : SafeReflect.methods(type)) {
            if (method.getReturnType() != void.class) continue;
            List<String> params = new ArrayList<>();
            for (Class<?> param : method.getParameterTypes()) params.add(param.getName());
            voidParams.add(params);
        }
        return new Caller(type.getName(), interfaces, voidParams);
    }
}
