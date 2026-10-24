package ps.reso.instaeclipse.mods.reels;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Predicate;

final class ReelSessions {

    private static final class Binding {
        final WeakReference<Object> key;
        final ReelViewerSession session;

        Binding(Object key, ReelViewerSession session) {
            this.key = new WeakReference<>(key);
            this.session = session;
        }
    }

    private final List<Binding> configs = new ArrayList<>();
    private final List<Binding> sources = new ArrayList<>();
    private final List<Binding> roots = new ArrayList<>();

    synchronized ReelViewerSession forConfig(Object config, Predicate<Object> direct) {
        if (config == null) return null;
        Binding found = find(configs, config);
        if (found != null) return found.session;
        ReelViewerSession session = new ReelViewerSession(direct.test(config));
        configs.add(new Binding(config, session));
        return session;
    }

    synchronized void bindSource(Object source, ReelViewerSession session) {
        bind(sources, source, session);
    }

    synchronized ReelViewerSession forSource(Object source) {
        Binding found = find(sources, source);
        return found == null ? null : found.session;
    }

    synchronized void bindRoot(Object root, ReelViewerSession session) {
        bind(roots, root, session);
    }

    synchronized boolean isRoot(Object root) {
        return find(roots, root) != null;
    }

    synchronized ReelViewerSession forRoot(Object root) {
        Binding found = find(roots, root);
        return found == null ? null : found.session;
    }

    static int[] sourceParams(Class<?>[] types) {
        int[] found = new int[types.length];
        int count = 0;
        for (int i = 0; i < types.length; i++) {
            if (hasModuleName(types[i])) found[count++] = i;
        }
        int[] out = new int[count];
        System.arraycopy(found, 0, out, 0, count);
        return out;
    }

    private static boolean hasModuleName(Class<?> type) {
        if (type.isPrimitive() || type.isArray()) return false;
        try {
            return type.getMethod("getModuleName").getReturnType() == String.class;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void bind(List<Binding> list, Object key, ReelViewerSession session) {
        if (key == null) return;
        Binding found = find(list, key);
        if (found != null) list.remove(found);
        list.add(new Binding(key, session));
    }

    private static Binding find(List<Binding> list, Object key) {
        if (key == null) return null;
        Binding found = null;
        for (Iterator<Binding> it = list.iterator(); it.hasNext(); ) {
            Binding binding = it.next();
            Object bound = binding.key.get();
            if (bound == null) it.remove();
            else if (found == null && bound == key) found = binding;
        }
        return found;
    }
}
