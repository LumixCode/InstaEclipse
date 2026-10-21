package ps.reso.instaeclipse.mods.misc;

import java.util.Map;
import java.util.List;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;

/** Unknown relationship data is not evidence that someone does not follow you. */
final class ProfileFollowStatus {
    enum Status { HIDDEN, FOLLOWS_YOU, MUTUAL, DOES_NOT_FOLLOW }

    private ProfileFollowStatus() {}

    /** Only header objects supplied to this bind may identify the displayed profile. */
    static Object boundUser(List<Field> userFields, Object[] arguments) throws IllegalAccessException {
        Object result = null;
        for (Field field : userFields) {
            for (Object argument : arguments) {
                if (!field.getDeclaringClass().isInstance(argument)) continue;
                Object candidate = field.get(argument);
                if (candidate == null) continue;
                if (result != null && result != candidate) return null;
                result = candidate;
            }
        }
        return result;
    }

    static Status from(String viewerId, String profileId, Map<?, ?> friendship) {
        if (viewerId == null || viewerId.isEmpty() || profileId == null || profileId.isEmpty()
                || viewerId.equals(profileId) || friendship == null) return Status.HIDDEN;
        Object followedBy = friendship.get("followed_by");
        if (!(followedBy instanceof Boolean)) return Status.HIDDEN;
        if (!((Boolean) followedBy)) return Status.DOES_NOT_FOLLOW;
        return Boolean.TRUE.equals(friendship.get("following")) ? Status.MUTUAL : Status.FOLLOWS_YOU;
    }

    /** A header can carry several projections of the same profile; conflicting identities hide it. */
    static Status fromSchemas(String viewerId, List<Object> schemas, Method stringGetter,
                              Method childGetter, Method booleanGetter) throws ReflectiveOperationException {
        String profileId = null;
        Boolean followedBy = null;
        Boolean following = null;
        for (Object schema : schemas) {
            String id = (String) stringGetter.invoke(schema, "id".hashCode());
            if (id == null || id.isEmpty()) continue;
            if (profileId != null && !profileId.equals(id)) return Status.HIDDEN;
            profileId = id;
            Object relationship = childGetter.invoke(schema, "friendship_status".hashCode());
            if (relationship == null) continue;
            Boolean incoming = (Boolean) booleanGetter.invoke(relationship, "followed_by".hashCode());
            Boolean outgoing = (Boolean) booleanGetter.invoke(relationship, "following".hashCode());
            if (incoming != null) {
                if (followedBy != null && !followedBy.equals(incoming)) return Status.HIDDEN;
                followedBy = incoming;
            }
            if (outgoing != null) {
                if (following != null && !following.equals(outgoing)) return Status.HIDDEN;
                following = outgoing;
            }
        }
        return from(viewerId, profileId, followedBy == null ? null : Map.of("followed_by", followedBy,
                "following", Boolean.TRUE.equals(following)));
    }

    /** The one profile id the bound projections agree on, or null (none, or conflicting ids). */
    static String profileIdOf(List<Object> schemas, Method stringGetter) throws ReflectiveOperationException {
        String profileId = null;
        for (Object schema : schemas) {
            String id = (String) stringGetter.invoke(schema, "id".hashCode());
            if (id == null || id.isEmpty()) continue;
            if (profileId != null && !profileId.equals(id)) return null;
            profileId = id;
        }
        return profileId;
    }

    static List<Object> boundSchemas(List<Field> headerFields, Field schemaField, Object[] arguments)
            throws IllegalAccessException {
        List<Object> schemas = new ArrayList<>();
        for (Field field : headerFields) {
            for (Object argument : arguments) {
                if (!field.getDeclaringClass().isInstance(argument)) continue;
                Object wrapper = field.get(argument);
                if (wrapper == null) continue;
                Object schema = schemaField.get(wrapper);
                if (schema != null && !schemas.contains(schema)) schemas.add(schema);
            }
        }
        return schemas;
    }
}
