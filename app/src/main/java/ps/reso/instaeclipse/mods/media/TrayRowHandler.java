package ps.reso.instaeclipse.mods.media;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class TrayRowHandler {
    static final String ROW = "IEHighlightRow";
    static final String LOAD = "IEHighlightLoad";

    private TrayRowHandler() {}

    static InvocationHandler row(Class<?> iface, Executor post, Runnable click) {
        return (proxy, m, a) -> {
            if (m.getDeclaringClass() == Object.class) return objectMethod(proxy, m, a, ROW);
            boolean own = m.getDeclaringClass() == iface;
            if (m.getReturnType() == boolean.class) return own;
            if (own && m.getReturnType() == void.class) post.execute(click);
            return null;
        };
    }

    private static Object objectMethod(Object proxy, Method m, Object[] a, String name) {
        switch (m.getName()) {
            case "hashCode": return System.identityHashCode(proxy);
            case "equals": return a != null && a.length == 1 && proxy == a[0];
            default: return name;
        }
    }

    static final class Load implements InvocationHandler {
        private final String id;
        private final Executor post;
        private final Set<Object> pending;
        private final Consumer<Boolean> done;
        private final AtomicBoolean fired = new AtomicBoolean();

        Load(String id, Executor post, Set<Object> pending, Consumer<Boolean> done) {
            this.id = id;
            this.post = post;
            this.pending = pending;
            this.done = done;
        }

        @Override
        public Object invoke(Object proxy, Method m, Object[] a) {
            if (m.getDeclaringClass() == Object.class) return objectMethod(proxy, m, a, LOAD);
            if (a == null || a.length == 0 || !id.equals(a[0]) || !finish(proxy)) return null;
            boolean success = a.length == 2;
            post.execute(() -> done.accept(success));
            return null;
        }

        boolean waiting(Object proxy) {
            return pending.contains(proxy);
        }

        boolean cancel(Object proxy) {
            return finish(proxy);
        }

        private boolean finish(Object proxy) {
            if (!fired.compareAndSet(false, true)) return false;
            pending.remove(proxy);
            return true;
        }
    }
}
