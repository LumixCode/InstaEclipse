package ps.reso.instaeclipse.utils.core;

import android.os.Build;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import ps.reso.instaeclipse.utils.log.ModuleLog;

public final class JitGuard {
    private static final int K_ACC_COMPILE_DONT_BOTHER = 0x02000000;
    private static final int ACCESS_FLAGS_OFFSET = 4;

    private static boolean needed = Build.VERSION.SDK_INT == Build.VERSION_CODES.TIRAMISU;
    private static int calls;
    private static boolean broken;
    private static final Set<Class<?>> done = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    private static Field backtrace, artMethod;
    private static Method peekInt, pokeInt;

    private JitGuard() {}

    private static volatile IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();

    public static void tick(Object member) {
        if (!needed || broken) return;
        boolean first = member != null && !seen.containsKey(member);
        if (!first && ((++calls) & 0x3F) != 0) return;
        if (first) {
            synchronized (JitGuard.class) {
                IdentityHashMap<Object, Boolean> next = new IdentityHashMap<>(seen);
                next.put(member, Boolean.TRUE);
                seen = next;
            }
        }
        try {
            scan();
        } catch (Throwable t) {
            broken = true;
            ModuleLog.warn("(IE|JitGuard) A crash safeguard for Android 13 is not available on this device; Instagram may crash more often there");
            ModuleLog.detail("(IE|JitGuard) disabled: " + t);
        }
    }

    public static boolean active() {
        return needed && !broken && !done.isEmpty();
    }

    public static String state() {
        if (!needed) return "off (Android 13 only)";
        return active() ? "active" : "inactive";
    }

    static boolean isStub(String name) {
        return name.startsWith("LSPHooker_") || name.startsWith("Vector_") || name.startsWith("LSPatch_");
    }

    private static void scan() throws Throwable {
        if (backtrace == null) {
            backtrace = Throwable.class.getDeclaredField("backtrace");
            backtrace.setAccessible(true);
            artMethod = Class.forName("java.lang.reflect.Executable").getDeclaredField("artMethod");
            artMethod.setAccessible(true);
            Class<?> mem = Class.forName("libcore.io.Memory");
            peekInt = mem.getDeclaredMethod("peekInt", long.class, boolean.class);
            pokeInt = mem.getDeclaredMethod("pokeInt", long.class, int.class, boolean.class);
            peekInt.setAccessible(true);
            pokeInt.setAccessible(true);
        }
        Object bt = backtrace.get(new Throwable());
        if (!(bt instanceof Object[] frames)) return;
        for (Object f : frames) {
            if (!(f instanceof Class<?> c) || done.contains(c) || !isStub(c.getName())) continue;
            done.add(c);
            int n = 0;
            for (Method m : ps.reso.instaeclipse.utils.core.SafeReflect.methods(c)) {
                if (m.getDeclaringClass() != c) continue;
                long art = (long) artMethod.get(m);
                if (art == 0) continue;
                int flags = (int) peekInt.invoke(null, art + ACCESS_FLAGS_OFFSET, false);
                if ((flags & 0x0F) != (m.getModifiers() & 0x0F)) throw new IllegalStateException("ArtMethod layout " + Integer.toHexString(flags));
                if ((flags & K_ACC_COMPILE_DONT_BOTHER) == 0) {
                    pokeInt.invoke(null, art + ACCESS_FLAGS_OFFSET, flags | K_ACC_COMPILE_DONT_BOTHER, false);
                    n++;
                }
            }
            if (done.size() <= 3 || done.size() % 25 == 0)
                ModuleLog.detail("(IE|JitGuard) stubs protected: " + done.size() + " (last: " + n + " of "
                        + java.util.Arrays.toString(ps.reso.instaeclipse.utils.core.SafeReflect.methods(c)) + ")");
        }
    }
}
