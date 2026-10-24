package ps.reso.instaeclipse.mods.misc;

final class FollowToastText {

    private FollowToastText() {}

    static String text(String username, boolean followsYou, String follows, String notFollows) {
        if (username == null || username.isEmpty()) return null;
        return String.format(followsYou ? follows : notFollows, username);
    }
}
