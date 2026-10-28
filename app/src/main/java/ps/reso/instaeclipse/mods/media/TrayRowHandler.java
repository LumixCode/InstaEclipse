package ps.reso.instaeclipse.mods.media;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

final class TrayRowHandler {
    static final String ROW = "IEHighlightRow";
    static final String LOAD = "IEHighlightLoad";

    private TrayRowHandler() {}

    static InvocationHandler row(Class<?> iface, Executor post, Runnable click) {
        return (proxy, m, a) -> {
            if (m.getReturnType() == boolean.class) return true;
            if (m.getReturnType() == void.class && m.getDeclaringClass() == iface) {
                post.execute(click);
                return null;
            }
            if (m.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (m.getName().equals("equals")) return proxy == a[0];
            if (m.getName().equals("toString")) return ROW;
            return null;
        };
    }

    static final class Load implements InvocationHandler {
        private final String id;
        private final Executor post;
        private final Set<Object> pending;
        private final Consumer<Boolean> done;

        Load(String id, Executor post, Set<Object> pending, Consumer<Boolean> done) {
            this.id = id;
            this.post = post;
            this.pending = pending;
            this.done = done;
        }

        @Override
        public Object invoke(Object proxy, Method m, Object[] a) {
            switch (m.getName()) {
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return proxy == a[0];
                case "toString": return LOAD;
            }
            if (a == null || a.length == 0 || !id.equals(a[0])) return null;
            boolean success = a.length == 2;
            post.execute(() -> {
                if (!pending.remove(proxy)) return;
                done.accept(success);
            });
            return null;
        }

        boolean waiting(Object proxy) {
            return pending.contains(proxy);
        }

        boolean cancel(Object proxy) {
            return pending.remove(proxy);
        }
    }
}
