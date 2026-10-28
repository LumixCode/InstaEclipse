package ps.reso.instaeclipse.utils.ui;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;

public final class Gestures {

    public static final long HINT_DELAY_MS = 3000L;
    static final String PREFS = "instaeclipse_hints";
    static final String HINT_SHOWN = "gesturesHintShown";

    public record Gesture(String key, int title, int description, boolean inHint, String... needs) {}

    public static final List<Gesture> ALL = List.of(
            new Gesture("menu", R.string.ig_gesture_menu, R.string.ig_gesture_menu_desc, true),
            new Gesture("quickToggle", R.string.ig_gesture_quick_toggle, R.string.ig_gesture_quick_toggle_desc, true),
            new Gesture("hiddenChats", R.string.ig_gesture_hidden_chats, R.string.ig_gesture_hidden_chats_desc, false,
                    "hideSpecificChats"),
            new Gesture("chatMenu", R.string.ig_gesture_chat_menu, R.string.ig_gesture_chat_menu_desc, true,
                    "chatExport", "jumpToFirstMessage", "readReceiptChats", "keepUnsentMessages",
                    "hideSpecificChats", "lockSpecificMessages"),
            new Gesture("reelSpeed", R.string.ig_gesture_reel_speed, R.string.ig_gesture_reel_speed_desc, true,
                    "holdLeftSlowMotion"),
            new Gesture("caption", R.string.ig_gesture_caption, R.string.ig_gesture_caption_desc, true,
                    "enableCaptionCopy"),
            new Gesture("comment", R.string.ig_gesture_comment, R.string.ig_gesture_comment_desc, true,
                    "enableCopyComment"));

    private Gestures() {}

    public static boolean usable(Gesture g, Predicate<String> on) {
        if (g.needs().length == 0) return true;
        for (String key : g.needs()) if (on.test(key)) return true;
        return false;
    }

    public static List<Gesture> hint(Predicate<String> on) {
        List<Gesture> out = new ArrayList<>();
        for (Gesture g : ALL) if (g.inHint() && usable(g, on)) out.add(g);
        return out;
    }

    public static boolean flag(String key) {
        try {
            return Boolean.TRUE.equals(FeatureFlags.class.getField(key).get(null));
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    public static final class HintRule {
        private boolean armed, done;

        public synchronized boolean arm() {
            if (armed || done) return false;
            armed = true;
            return true;
        }

        public synchronized boolean fire(boolean shown, boolean inFront) {
            armed = false;
            if (done) return false;
            if (shown) {
                done = true;
                return false;
            }
            if (!inFront) return false;
            done = true;
            return true;
        }
    }

    public static boolean hintShown(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(HINT_SHOWN, false);
    }

    public static void markHintShown(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(HINT_SHOWN, true).apply();
    }
}
