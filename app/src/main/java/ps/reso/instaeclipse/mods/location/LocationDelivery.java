package ps.reso.instaeclipse.mods.location;

import java.util.concurrent.Executor;

final class LocationDelivery {

    static final class Nesting {
        private final ThreadLocal<int[]> depth = ThreadLocal.withInitial(() -> new int[1]);

        void enter() {
            depth.get()[0]++;
        }

        boolean exit() {
            int[] d = depth.get();
            if (d[0] > 0) d[0]--;
            return d[0] == 0;
        }
    }

    private LocationDelivery() {}

    static Object target(Object[] args, Class<?> looperType, Object currentLooper, Object mainLooper) {
        if (args != null) {
            for (Object a : args) if (a instanceof Executor) return a;
            for (Object a : args) if (looperType.isInstance(a)) return a;
        }
        return currentLooper != null ? currentLooper : mainLooper;
    }

    static boolean rewritesUnparcelled(String provider, boolean alreadySpoofed) {
        return !alreadySpoofed && !"mediastore".equals(provider) && !"photo".equals(provider);
    }
}
