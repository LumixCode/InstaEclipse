package ps.reso.instaeclipse.mods.ghost;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** One dispatch for one explicitly constructed receipt, bound to the originating login session. */
final class ManualStorySeenPolicy {
    static final String PARAMETER = "instaeclipse_manual_seen";
    private static final long LIFETIME_MS = 60_000;
    private final Map<String, Approval> approvals = new LinkedHashMap<>();

    static CharSequence[] appendAction(CharSequence[] original, String label) {
        for (CharSequence entry : original) if (entry != null && label.contentEquals(entry)) return original;
        CharSequence[] extended = original instanceof String[]
                ? java.util.Arrays.copyOf(original, original.length + 1, String[].class)
                : java.util.Arrays.copyOf(original, original.length + 1, CharSequence[].class);
        extended[original.length] = label;
        return extended;
    }

    static boolean hasMarker(URI uri) {
        if (uri == null || uri.getRawQuery() == null) return false;
        for (String pair : uri.getRawQuery().split("&", -1)) {
            int separator = pair.indexOf('=');
            String name = separator < 0 ? pair : pair.substring(0, separator);
            if (PARAMETER.equals(name)) return true;
        }
        return false;
    }

    static void stripMarker(Object request, URI original, URI clean) throws Exception {
        int rewritten = 0;
        for (Class<?> c = request.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())
                        || field.getType() != URI.class && field.getType() != String.class) continue;
                field.setAccessible(true);
                Object value = field.get(request);
                if (value == null) continue;
                String text = value.toString();
                if (!text.contains(PARAMETER + "=")) continue;
                if (!text.equals(original.toString())) throw new IllegalStateException("Conflicting request URLs");
                field.set(request, value instanceof URI ? clean : clean.toString());
                rewritten++;
            }
        }
        if (rewritten == 0) throw new IllegalStateException("No request URL to rewrite");
    }

    static Object createSingleItem(Constructor<?> constructor, Method populate,
                                   Object graph, Object session, String reelId) throws Exception {
        Object item = constructor.newInstance();
        requireSingleReceipt(item, false);
        if (Modifier.isStatic(populate.getModifiers())) populate.invoke(null, graph, session, item, reelId);
        else populate.invoke(item, session, graph, reelId);
        requireSingleReceipt(item, true);
        return item;
    }

    private static void requireSingleReceipt(Object item, boolean populated) throws Exception {
        int groups = 0;
        for (Field field : item.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            Object value = field.get(item);
            if (value instanceof Collection && !((Collection<?>) value).isEmpty()) {
                throw new IllegalStateException("Unexpected extra story receipts");
            }
            if (!(value instanceof Map<?, ?> map) || map.isEmpty()) continue;
            if (!populated || map.size() != 1 || ++groups > 1) {
                throw new IllegalStateException("Unexpected story receipt batch");
            }
            Object group = map.values().iterator().next();
            int records = 0;
            for (Field member : group.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(member.getModifiers())) continue;
                member.setAccessible(true);
                Object entries = member.get(group);
                if (entries instanceof Collection) records += ((Collection<?>) entries).size();
            }
            if (records != 1) throw new IllegalStateException("Expected exactly one story receipt");
        }
        if (populated && groups != 1) throw new IllegalStateException("No story receipt populated");
    }

    private static final class Approval {
        final WeakReference<Object> session;
        final long expiresAt;
        Approval(Object session, long now) {
            this.session = new WeakReference<>(session);
            expiresAt = now + LIFETIME_MS;
        }
    }

    synchronized String authorize(Object session, long now) {
        if (session == null) throw new IllegalArgumentException("A login session is required");
        approvals.entrySet().removeIf(e -> e.getValue().expiresAt <= now
                || e.getValue().session.get() == null);
        while (approvals.size() >= 8) approvals.remove(approvals.keySet().iterator().next());
        String token = UUID.randomUUID().toString();
        approvals.put(token, new Approval(session, now));
        return token;
    }

    synchronized void revoke(String token) { approvals.remove(token); }

    synchronized URI consume(URI uri, Object session, long now) {
        if (uri == null || !"https".equals(uri.getScheme())
                || !"i.instagram.com".equals(uri.getHost())
                || uri.getRawPath() == null
                || !uri.getRawPath().matches("/api/v[0-9]+/media/seen/")) return null;
        String query = uri.getRawQuery();
        if (query == null) return null;
        String token = null;
        StringBuilder remaining = new StringBuilder();
        for (String pair : query.split("&", -1)) {
            if (pair.startsWith(PARAMETER + "=")) {
                if (token != null) return null;
                token = pair.substring(PARAMETER.length() + 1);
            } else if (!pair.isEmpty()) {
                if (remaining.length() > 0) remaining.append('&');
                remaining.append(pair);
            }
        }
        Approval approval = approvals.get(token);
        if (approval == null || approval.session.get() != session || session == null
                || approval.expiresAt <= now) return null;
        approvals.remove(token);
        return URI.create(uri.getScheme() + "://" + uri.getRawAuthority() + uri.getRawPath()
                + (remaining.length() == 0 ? "" : "?" + remaining));
    }
}
