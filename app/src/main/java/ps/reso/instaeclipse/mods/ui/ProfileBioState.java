package ps.reso.instaeclipse.mods.ui;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.Found;
import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;

public final class ProfileBioState {

    private static final String BIO_COMPOSABLE = "ProfileBioComponent (ProfileBioComponent.kt";
    private static final String KEY = "ProfileBioState";

    public interface Spoof {
        String bio(Object[] args, Layout at);
    }

    public interface Copy {
        void built(String username, String bio);
    }

    public static final class Layout {
        final int[] strings;
        final int usernameArg;
        final int bioArg;
        final int entitiesArg;

        private Layout(int[] strings, int entitiesArg) {
            this.strings = strings;
            this.usernameArg = strings[1];
            this.bioArg = strings[2];
            this.entitiesArg = entitiesArg;
        }

        static Layout of(Class<?>[] types) {
            int[] strings = new int[types.length];
            int n = 0, lastString = -1;
            for (int i = 0; i < types.length; i++) if (types[i] == String.class) { strings[n++] = i; lastString = i; }
            if (n < 3) return null;
            int entities = -1;
            for (int i = lastString + 1; i < types.length; i++)
                if (!types[i].isPrimitive() && types[i] != Integer.class) { entities = i; break; }
            return new Layout(Arrays.copyOf(strings, n), entities);
        }

        public boolean hasString(Object[] args, String value) {
            for (int i : strings) if (value.equals(args[i])) return true;
            return false;
        }

        public Object username(Object[] args) {
            return args[usernameArg];
        }
    }

    private static volatile Spoof spoof;
    private static volatile Copy copy;
    private static volatile Layout layout;
    private static boolean tried;
    private static String found;

    private ProfileBioState() {}

    public static String installSpoof(ClassLoader cl, Spoof part) {
        spoof = part;
        return install(cl);
    }

    public static String installCopy(ClassLoader cl, Copy part) {
        copy = part;
        return install(cl);
    }

    private static synchronized String install(ClassLoader cl) {
        if (!tried) {
            tried = true;
            found = hook(cl);
        }
        return found;
    }

    private static String hook(ClassLoader cl) {
        try {
            Class<?> state = null;
            String cached = DexKitCache.isCacheValid() ? DexKitCache.loadString(KEY) : null;
            if (cached != null && !cached.isEmpty()) {
                try { state = Class.forName(cached, false, cl); } catch (Throwable ignored) {}
            }
            if (state == null) {
                for (Found md : DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                        .usingStrings(List.of(BIO_COMPOSABLE), StringMatchType.Contains, false)))) {
                    Method m;
                    try { m = md.getMethodInstance(cl); } catch (Throwable t) { continue; }
                    for (Class<?> p : m.getParameterTypes()) {
                        if (constructor(p) != null) { state = p; break; }
                    }
                    if (state != null) break;
                }
                if (state != null) DexKitCache.saveString(KEY, state.getName());
            }
            Constructor<?> ctor = state != null ? constructor(state) : null;
            Layout at = ctor != null ? Layout.of(ctor.getParameterTypes()) : null;
            if (at == null) return "not found";
            layout = at;
            XposedBridge.hookMethod(ctor, HOOK);
            return state.getName() + " args " + at.usernameArg + "/" + at.bioArg;
        } catch (Throwable t) {
            return String.valueOf(t);
        }
    }

    private static final XC_MethodHook HOOK = new XC_MethodHook() {
        @Override protected void beforeHookedMethod(MethodHookParam param) {
            before(param.args, layout, spoof);
        }

        @Override protected void afterHookedMethod(MethodHookParam param) {
            after(param.args, layout, copy);
        }
    };

    static void before(Object[] args, Layout at, Spoof part) {
        if (part == null || at == null) return;
        String bio = part.bio(args, at);
        if (bio == null) return;
        args[at.bioArg] = bio;
        if (at.entitiesArg >= 0) args[at.entitiesArg] = null;
    }

    static void after(Object[] args, Layout at, Copy part) {
        if (part == null || at == null) return;
        if (args[at.usernameArg] instanceof String user && args[at.bioArg] instanceof String bio) part.built(user, bio);
    }

    static Constructor<?> constructor(Class<?> c) {
        if (c.isPrimitive() || c.isInterface() || c.getName().startsWith("java") || c.getName().startsWith("android")) return null;
        for (Constructor<?> k : c.getDeclaredConstructors()) {
            int strings = 0, booleans = 0;
            for (Class<?> p : k.getParameterTypes()) {
                if (p == String.class) strings++;
                else if (p == boolean.class) booleans++;
            }
            if (strings >= 4 && booleans >= 5) return k;
        }
        return null;
    }
}
