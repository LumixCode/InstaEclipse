package ps.reso.instaeclipse.mods.media;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

final class StoryCapturePolicy {
    private static final long SECONDS_LIMIT = 100000000000L;
    private static final Map<Class<?>, Map<String, Optional<Field>>> OWN = new ConcurrentHashMap<>();

    private StoryCapturePolicy() {}

    static String id(Object raw) {
        if (!(raw instanceof String s)) return null;
        int cut = s.indexOf('_');
        String first = cut < 0 ? s : s.substring(0, cut);
        return first.isEmpty() ? null : first;
    }

    static long expiryMs(Object raw) {
        if (!(raw instanceof Long at) || at <= 0) return 0;
        return at < SECONDS_LIMIT ? at * 1000L : at;
    }

    static String author(Object item, String userType, String mediaType,
                         Function<Object, String> userName, Function<Object, String> mediaUserName) {
        if (item == null) return null;
        Object user = own(item, userType);
        String name = user == null ? null : userName.apply(user);
        if (name != null) return name;
        Object media = own(item, mediaType);
        return media == null ? null : mediaUserName.apply(media);
    }

    private static Object own(Object item, String type) {
        if (type == null) return null;
        Class<?> cls = item.getClass();
        Field field = OWN.computeIfAbsent(cls, c -> new ConcurrentHashMap<>())
                .computeIfAbsent(type, t -> Optional.ofNullable(StoryDownloadHook.OwnMedia.field(cls, t)))
                .orElse(null);
        if (field == null) return null;
        try {
            return field.get(item);
        } catch (IllegalAccessException e) {
            return null;
        }
    }
}
