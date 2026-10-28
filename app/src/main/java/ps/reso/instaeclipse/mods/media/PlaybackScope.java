package ps.reso.instaeclipse.mods.media;

import android.content.SharedPreferences;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

final class PlaybackScope {

    enum Surface { FEED, REEL, STORY, SHARED_PLAYER, VOICE_MESSAGE, MUSIC_STICKER }

    static final String STAYS_SHOWN = "__playbackSpeedStaysShown";
    static final Set<String> INSTAGRAM_SPEED_ROWS = Set.of("PLAYBACK_SPEED", "PLAYBACK_CONTROLS");

    private final Map<Object, Boolean> instagramPlayers = Collections.synchronizedMap(new WeakHashMap<>());
    private volatile boolean instagramOwns;
    private volatile float instagramSpeed = 1f;

    static boolean covers(Surface surface) {
        return switch (surface) {
            case FEED, REEL, STORY, SHARED_PLAYER -> true;
            case VOICE_MESSAGE, MUSIC_STICKER -> false;
        };
    }

    boolean applies(Surface surface, Object player, float chosen) {
        if (chosen == 1f || !covers(surface) || instagramOwns) return false;
        return player == null || !instagramPlayers.containsKey(player);
    }

    void instagramSet(Object player, float requested) {
        instagramSpeed = requested;
        if (player != null && requested != 1f && requested != 2f) instagramPlayers.put(player, Boolean.TRUE);
    }

    void menuTapped(String option) {
        if (option != null && INSTAGRAM_SPEED_ROWS.contains(option)) instagramOwns = true;
    }

    void moduleChose() {
        instagramOwns = false;
        instagramPlayers.clear();
    }

    boolean instagramOwns() {
        return instagramOwns;
    }

    float restoreSpeed(float chosen, boolean offered) {
        if (instagramOwns) return instagramSpeed;
        return offered ? chosen : 1f;
    }

    static boolean instagramOffersSpeed(Collection<?> options) {
        if (options == null) return false;
        for (Object option : options) {
            if (option != null && INSTAGRAM_SPEED_ROWS.contains(option.toString())) return true;
        }
        return false;
    }

    static boolean takeStaysNotice(SharedPreferences prefs, float chosen) {
        if (chosen == 1f || prefs == null || prefs.getBoolean(STAYS_SHOWN, false)) return false;
        prefs.edit().putBoolean(STAYS_SHOWN, true).apply();
        return true;
    }
}
