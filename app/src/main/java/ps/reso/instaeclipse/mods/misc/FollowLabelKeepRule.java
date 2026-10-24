package ps.reso.instaeclipse.mods.misc;

final class FollowLabelKeepRule {

    private FollowLabelKeepRule() {}

    static boolean keep(boolean shown, boolean fromNetwork, String shownProfile, String boundProfile) {
        if (!shown) return false;
        if (boundProfile == null) return fromNetwork;
        return boundProfile.equals(shownProfile);
    }
}
