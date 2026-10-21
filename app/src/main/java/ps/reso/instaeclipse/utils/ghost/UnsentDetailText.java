package ps.reso.instaeclipse.utils.ghost;

import android.content.Context;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.ghost.UnsentDetail.Kind;
import ps.reso.instaeclipse.utils.i18n.I18n;

/** What the Unsent viewer shows for a log entry: its text, else what the message was. */
public final class UnsentDetailText {
    private UnsentDetailText() {}

    /** True when {@link #body} describes the message instead of quoting its words. */
    public static boolean isDescription(UnsentLog.Entry entry) {
        return !entry.hasBody && (entry.detail == null || entry.detail.kind != Kind.TEXT);
    }

    public static String body(Context context, UnsentLog.Entry entry) {
        if (entry.hasBody) return entry.text;
        // Entries from before descriptions existed, captured without a restart copy: say so
        // rather than guess what they were.
        if (entry.detail == null) return I18n.t(context, R.string.ig_unsent_detail_unknown);
        return entry.detail.format((kind, user) -> name(context, kind, user));
    }

    private static String name(Context context, Kind kind, String user) {
        int named = user == null ? 0 : namedRes(kind);
        return named != 0 ? I18n.t(context, named, user) : I18n.t(context, res(kind));
    }

    /** The form naming the owner ("Reel by @%1$s"), or 0 when the kind has none. */
    private static int namedRes(Kind kind) {
        switch (kind) {
            case REEL: return R.string.ig_unsent_detail_reel_by;
            case POST: return R.string.ig_unsent_detail_post_by;
            case STORY: return R.string.ig_unsent_detail_story_by;
            case STORY_REPLY: return R.string.ig_unsent_detail_story_reply_by;
            case STORY_MENTION: return R.string.ig_unsent_detail_story_mention_by;
            case PROFILE: return R.string.ig_unsent_detail_profile_by;
            case VIDEO_SHARE: return R.string.ig_unsent_detail_video_share_by;
            case LIVE: return R.string.ig_unsent_detail_live_by;
            case SHARED: return R.string.ig_unsent_detail_shared_by;
            default: return 0;
        }
    }

    private static int res(Kind kind) {
        switch (kind) {
            case REEL: return R.string.ig_unsent_detail_reel;
            case POST: return R.string.ig_unsent_detail_post;
            case STORY: return R.string.ig_unsent_detail_story;
            case STORY_REPLY: return R.string.ig_unsent_detail_story_reply;
            case STORY_MENTION: return R.string.ig_unsent_detail_story_mention;
            case VOICE: return R.string.ig_unsent_detail_voice;
            case PHOTO: return R.string.ig_unsent_detail_photo;
            case VIDEO: return R.string.ig_unsent_detail_video;
            case ALBUM: return R.string.ig_unsent_detail_album;
            case DISAPPEARING_PHOTO: return R.string.ig_unsent_detail_disappearing_photo;
            case DISAPPEARING_VIDEO: return R.string.ig_unsent_detail_disappearing_video;
            case STICKER: return R.string.ig_unsent_detail_sticker;
            case GIF: return R.string.ig_unsent_detail_gif;
            case LINK: return R.string.ig_unsent_detail_link;
            case LIKE: return R.string.ig_unsent_detail_like;
            case LOCATION: return R.string.ig_unsent_detail_location;
            case PROFILE: return R.string.ig_unsent_detail_profile;
            case VIDEO_SHARE: return R.string.ig_unsent_detail_video_share;
            case LIVE: return R.string.ig_unsent_detail_live;
            case MUSIC: return R.string.ig_unsent_detail_music;
            case AUDIO: return R.string.ig_unsent_detail_audio;
            case CALL: return R.string.ig_unsent_detail_call;
            case EFFECT: return R.string.ig_unsent_detail_effect;
            case PRODUCT: return R.string.ig_unsent_detail_product;
            case POLL: return R.string.ig_unsent_detail_poll;
            case NOTE_REPLY: return R.string.ig_unsent_detail_note_reply;
            case UNAVAILABLE: return R.string.ig_unsent_detail_unavailable;
            case ACTIVITY: return R.string.ig_unsent_detail_activity;
            case SHARED: return R.string.ig_unsent_detail_shared;
            case NO_TEXT: case TEXT: default: return R.string.ig_unsent_detail_no_text;
        }
    }
}
