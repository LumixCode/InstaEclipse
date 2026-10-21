package ps.reso.instaeclipse.mods.misc;

import java.util.List;
import java.util.Locale;

/** A formatter is shared with inbox rows, so only explicit content binds may replace its result. */
final class ExactTimestampScope {
    private final ThreadLocal<Integer> depth = ThreadLocal.withInitial(() -> 0);

    void enter() { depth.set(depth.get() + 1); }

    void exit() {
        int remaining = depth.get() - 1;
        if (remaining <= 0) depth.remove();
        else depth.set(remaining);
    }

    boolean active() { return depth.get() > 0; }

    static boolean isContentCaller(String owner, List<String> parameters, List<String> strings) {
        if (owner.contains(".direct.inbox.")) return false;
        for (String value : strings) {
            if (value.toLowerCase(Locale.ROOT).contains("inbox")) return false;
        }
        if (owner.equals("com.instagram.model.reels.ReelItem")
                || parameters.contains("com.instagram.feed.media.Media")
                || parameters.contains("com.instagram.model.reels.ReelItem")
                || parameters.contains("com.instagram.feed.media.Comment")) return true;
        for (String value : strings) {
            if (value.equals("row_comment_textview_time_ago")
                    || value.contains("com.instagram.comments.mvvm.view.compose.CommentAuthorHeader")
                    || value.contains("LimitedCommentRowViewBinder.Holder")) return true;
        }
        return false;
    }
}
