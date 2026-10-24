package ps.reso.instaeclipse.mods.feed;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import ps.reso.instaeclipse.utils.core.SafeReflect;

public final class FeedItemKinds {

    private static final Field[] NO_FIELDS = new Field[0];
    private static final Map<Class<?>, Layout> LAYOUTS = new ConcurrentHashMap<>();

    private FeedItemKinds() {}

    public static final class Unit {
        public final Object media;
        public final boolean threads;
        public final String kind;

        Unit(Object media, boolean threads, String kind) {
            this.media = media;
            this.threads = threads;
            this.kind = kind;
        }
    }

    public static Unit read(Object item) {
        Class<?> c = item.getClass();
        Layout layout = LAYOUTS.get(c);
        if (layout == null) {
            layout = new Layout(c);
            LAYOUTS.put(c, layout);
        }
        return layout.read(item);
    }

    public static boolean hides(String kind, boolean threads, boolean hideSuggestions, boolean hideThreads) {
        return threads ? hideThreads : hideSuggestions;
    }

    static boolean isMediaType(Class<?> type) {
        return type.getSimpleName().equals("Media");
    }

    static boolean isThreadsType(Class<?> type) {
        String name = type.getSimpleName();
        return name.contains("Threads") || name.startsWith("TextApp") || name.startsWith("XDTTextApp");
    }

    static boolean isKindType(Class<?> type) {
        if (!type.isEnum()) return false;
        try {
            for (Object constant : type.getEnumConstants()) {
                if (((Enum<?>) constant).name().equals("MEDIA")) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static final class Layout {
        final Field[] media;
        final Field[] threads;
        final Field kind;

        Layout(Class<?> c) {
            List<Field> media = new ArrayList<>();
            List<Field> threads = new ArrayList<>();
            Field kind = null;
            for (Field f : SafeReflect.fields(c)) {
                Class<?> type = f.getType();
                boolean isMedia = isMediaType(type);
                boolean isThreads = !isMedia && isThreadsType(type);
                boolean isKind = !isMedia && !isThreads && kind == null && isKindType(type);
                if (!isMedia && !isThreads && !isKind) continue;
                try {
                    f.setAccessible(true);
                } catch (Throwable t) {
                    continue;
                }
                if (isMedia) media.add(f);
                else if (isThreads) threads.add(f);
                else kind = f;
            }
            this.media = media.toArray(NO_FIELDS);
            this.threads = threads.toArray(NO_FIELDS);
            this.kind = kind;
        }

        Unit read(Object item) {
            Object found = null;
            for (Field f : media) {
                found = get(f, item);
                if (found != null) break;
            }
            boolean isThreads = false;
            if (found == null) {
                for (Field f : threads) {
                    if (get(f, item) != null) {
                        isThreads = true;
                        break;
                    }
                }
            }
            Object value = kind == null ? null : get(kind, item);
            String name = value instanceof Enum ? ((Enum<?>) value).name() : null;
            return new Unit(found, isThreads, name);
        }

        private static Object get(Field f, Object item) {
            try {
                return f.get(item);
            } catch (Throwable t) {
                return null;
            }
        }
    }
}
