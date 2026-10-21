package ps.reso.instaeclipse.mods.misc;

import java.util.Map;

final class FollowingFollowStatus {
    private FollowingFollowStatus() {}

    static ProfileFollowStatus.Status from(boolean enabled, String viewerId, String listOwnerId,
                                          String listType, String profileId, Boolean directFollowedBy,
                                          Boolean relationshipFollowedBy, Boolean following) {
        if (!enabled || viewerId == null || viewerId.isEmpty() || !viewerId.equals(listOwnerId)
                || !("FOLLOWING".equals(listType) || "FOLLOWING_SIMPLIFIED".equals(listType)
                || "SELF_FOLLOWING".equals(listType)) || Boolean.FALSE.equals(following)) {
            return ProfileFollowStatus.Status.HIDDEN;
        }
        // A partially refreshed native User can carry two conflicting projections.
        if (directFollowedBy != null && relationshipFollowedBy != null
                && !directFollowedBy.equals(relationshipFollowedBy)) return ProfileFollowStatus.Status.HIDDEN;
        Boolean followedBy = relationshipFollowedBy != null ? relationshipFollowedBy : directFollowedBy;
        return ProfileFollowStatus.from(viewerId, profileId, followedBy == null ? null
                : Map.of("followed_by", followedBy, "following", Boolean.TRUE.equals(following)));
    }
}
