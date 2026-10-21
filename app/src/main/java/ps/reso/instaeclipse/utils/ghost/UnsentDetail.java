package ps.reso.instaeclipse.utils.ghost;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What an unsent message without text was ("Reel by @user", "Voice message · 0:29", "Sticker"),
 * read from the JSON Instagram's own message codec writes (the format of the restart copies).
 * Key names are Instagram's server field names (item_type, voice_media, clip, xma…), which
 * survive the obfuscation of the model classes. Only the message's own top-level payload counts:
 * a replied-to or forwarded message never describes its parent.
 *
 * The unsent log stores kind + user + duration + extra, so the label follows the UI language.
 */
public final class UnsentDetail {

    public enum Kind {
        TEXT("text"), NO_TEXT("none"), REEL("reel"), POST("post"), STORY("story"),
        STORY_REPLY("story_reply"), STORY_MENTION("story_mention"), VOICE("voice"), PHOTO("photo"),
        VIDEO("video"), ALBUM("album"), DISAPPEARING_PHOTO("disappearing_photo"),
        DISAPPEARING_VIDEO("disappearing_video"), STICKER("sticker"), GIF("gif"), LINK("link"),
        LIKE("like"), LOCATION("location"), PROFILE("profile"), VIDEO_SHARE("video_share"),
        LIVE("live"), MUSIC("music"), AUDIO("audio"), CALL("call"), EFFECT("effect"),
        PRODUCT("product"), POLL("poll"), NOTE_REPLY("note_reply"), UNAVAILABLE("unavailable"),
        ACTIVITY("activity"), SHARED("shared");

        /** Stored in the log; never rename one. */
        public final String id;

        Kind(String id) { this.id = id; }

        public static Kind of(String id) {
            for (Kind kind : values()) if (kind.id.equals(id)) return kind;
            return null;
        }
    }

    /** Names {@code kind} in the UI language, with {@code user} (a handle without "@") when it is
     *  given and the kind has a form that names its owner. */
    public interface Labels { String name(Kind kind, String user); }

    /** The message was captured and had no text, but its kind could not be read. */
    public static final UnsentDetail NO_TEXT = new UnsentDetail(Kind.NO_TEXT, null, 0, null);

    static final int MAX_EXTRA = 80;
    static final int MAX_TEXT = 2000;
    private static final Pattern HANDLE = Pattern.compile("[a-z0-9._]{1,30}");
    private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?://|www\\.)\\S+");
    // First path segments of instagram.com links that are not a profile.
    private static final Set<String> NOT_PROFILES = Set.of("p", "reel", "reels", "stories", "tv", "explore",
            "direct", "accounts", "about", "legal", "web", "s", "share", "music", "audio", "locations");

    public final Kind kind;
    /** Owner handle without "@", or null. */
    public final String user;
    /** Play length in ms, 0 when unknown or not media. */
    public final long durationMs;
    /** The message's own words, a link's host, a place, an emoji…, or null. */
    public final String extra;

    private UnsentDetail(Kind kind, String user, long durationMs, String extra) {
        this.kind = kind; this.user = user; this.durationMs = durationMs; this.extra = extra;
    }

    /** A stored detail; null when its kind is unknown to this build. */
    public static UnsentDetail of(String kindId, String user, long durationMs, String extra) {
        Kind kind = Kind.of(kindId);
        return kind == null ? null : make(kind, user, durationMs, extra);
    }

    private static UnsentDetail make(Kind kind, String user, long durationMs, String extra) {
        String words = clean(extra, kind == Kind.TEXT ? MAX_TEXT : MAX_EXTRA);
        if (kind == Kind.TEXT && words == null) return NO_TEXT;
        if (kind == Kind.NO_TEXT) return NO_TEXT;
        return new UnsentDetail(kind, handle(user), Math.max(0, durationMs), words);
    }

    /** "Reel by @user", "Voice message · 0:29", "Link: example.com"; a TEXT detail is its text. */
    public String format(Labels labels) {
        StringBuilder b = new StringBuilder();
        if (kind != Kind.TEXT) b.append(labels.name(kind, user));
        if (durationMs > 0) b.append(" · ").append(duration(durationMs));
        if (extra != null) b.append(b.length() > 0 ? ": " : "").append(extra);
        return b.toString();
    }

    /** m:ss, or h:mm:ss from one hour; a clip shorter than a second shows as 0:01. */
    public static String duration(long ms) {
        long total = Math.max(1, Math.round(ms / 1000.0));
        long h = total / 3600, m = total % 3600 / 60, s = total % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
                : String.format(Locale.ROOT, "%d:%02d", m, s);
    }

    /** The detail of a message serialized by Instagram's codec, or null when it is unreadable or
     *  its payload is unknown. Never throws. */
    public static UnsentDetail fromMessageJson(String json) {
        try {
            return UnsentJson.parse(json) instanceof Map<?, ?> message ? describe(message) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    static UnsentDetail describe(Map<?, ?> m) {
        String type = itemType(m);
        UnsentDetail detail = type != null ? byType(type, m) : null;
        return detail != null ? detail : byPayload(m);
    }

    /** Instagram writes the content type's server name; enum-style names are accepted too. */
    private static String itemType(Map<?, ?> m) {
        String type = str(m, "item_type");
        if (type == null) return null;
        type = type.toLowerCase(Locale.ROOT);
        switch (type) {
            case "clips_share": return "clip";
            case "xma_clips_share": return "xma_clip";
            case "expiring_media": return "raven_media";
            case "shopping_product": return "product_share";
            case "xma_shopping_product": return "xma_product_share";
            case "xma_location_share": return "location_share_xma";
            default: return type;
        }
    }

    private static UnsentDetail byType(String type, Map<?, ?> m) {
        switch (type) {
            case "text": return text(m);
            case "link": case "xma_link": case "cta_link": return link(m);
            case "like": return like(m);
            case "voice_media": return voice(m);
            case "media": case "progressive_image": case "video_memo": return direct(obj(m, "media"), false);
            case "raven_media": return direct(obj(m, "raven_media"), true);
            case "visual_media": return visual(m);
            case "animated_media": return animated(m);
            case "store_sticker": case "static_sticker": case "ai_sticker": case "avatar_sticker":
            case "viewer_avatar_sticker": case "animated_avatar_sticker": case "memu_sticker":
            case "cutout_sticker": case "riff_sticker": case "selfie_sticker": case "direct_headmoji":
                return make(Kind.STICKER, null, 0, null);
            case "music": case "direct_music_sticker": return titled(Kind.MUSIC, m);
            case "reels_audio_share": return titled(Kind.AUDIO, m);
            case "clip": case "clips_preview_share": case "clips_mention": return clip(m);
            case "xma_clip": return share(m, Kind.REEL);
            case "media_share": case "media_mention": case "repost_share": return mediaShare(m);
            case "xma_media_share": return share(m, Kind.POST);
            case "story_share": return story(m);
            case "xma_story_share": return share(m, Kind.STORY);
            case "xma_story_mention": case "xma_reel_mention": return share(m, Kind.STORY_MENTION);
            case "reel_share": return reelShare(m);
            case "xma_reel_share": return share(m, Kind.STORY_REPLY);
            case "felix_share": return felix(m);
            case "xma_felix_share": return share(m, Kind.VIDEO_SHARE);
            case "live_video_share": case "live_viewer_invite": case "xma_live_viewer_invite":
                return share(m, Kind.LIVE);
            case "profile": case "profile_card": case "receiver_profile_card": return profile(m);
            case "xma_profile": return share(m, Kind.PROFILE);
            case "location_share_xma": case "address_share_xma": return titled(Kind.LOCATION, m);
            case "video_call_event": return described(Kind.CALL, obj(m, "video_call_event"));
            case "action_log": case "instamadillo_action_log": return described(Kind.ACTIVITY, obj(m, "action_log"));
            case "ar_effect": case "xma_ar_effect": return make(Kind.EFFECT, null, 0, null);
            case "product_share": case "xma_product_share": case "shop_share": case "shops_collection_share":
                return make(Kind.PRODUCT, null, 0, null);
            case "direct_group_poll_v1": return make(Kind.POLL, null, 0, null);
            case "note_reply": case "status_reply": return make(Kind.NOTE_REPLY, null, 0, null);
            case "placeholder": case "expired_placeholder": return make(Kind.UNAVAILABLE, null, 0, null);
            default: return null;
        }
    }

    /** No (known) item_type: the payload the message carries decides. */
    private static UnsentDetail byPayload(Map<?, ?> m) {
        if (obj(m, "voice_media") != null) return voice(m);
        if (obj(m, "clip") != null) return clip(m);
        if (obj(m, "felix_share") != null) return felix(m);
        if (obj(m, "story_share") != null) return story(m);
        if (obj(m, "reel_share") != null) return reelShare(m);
        if (obj(m, "media_share") != null || obj(m, "direct_media_share") != null) return mediaShare(m);
        if (obj(m, "raven_media") != null) return direct(obj(m, "raven_media"), true);
        if (obj(m, "visual_media") != null) return visual(m);
        if (obj(m, "media") != null) return direct(obj(m, "media"), false);
        if (obj(m, "animated_media") != null) return animated(m);
        if (obj(m, "store_sticker") != null || obj(m, "static_sticker") != null) return make(Kind.STICKER, null, 0, null);
        if (obj(m, "music_sticker") != null) return titled(Kind.MUSIC, m);
        if (obj(m, "link") != null) return link(m);
        if (str(m, "like") != null) return like(m);
        if (obj(m, "profile") != null) return profile(m);
        if (xma(m) != null) return xmaShare(m);
        if (obj(m, "video_call_event") != null) return described(Kind.CALL, obj(m, "video_call_event"));
        if (obj(m, "placeholder") != null || obj(m, "expired_placeholder") != null) return make(Kind.UNAVAILABLE, null, 0, null);
        if (obj(m, "action_log") != null) return described(Kind.ACTIVITY, obj(m, "action_log"));
        return text(m);
    }

    // ── payloads ────────────────────────────────────────────────────────────────

    private static UnsentDetail text(Map<?, ?> m) {
        String text = str(m, "text");
        return text == null ? null : make(Kind.TEXT, null, 0, text);
    }

    private static UnsentDetail voice(Map<?, ?> m) {
        Map<?, ?> voice = obj(m, "voice_media");
        long ms = (long) num(voice, "duration_ms");
        Map<?, ?> media = obj(voice, "media");
        // The clip's audio info states its length in ms too.
        if (ms <= 0) ms = (long) deepNum(media, "duration", 3);
        if (ms <= 0) ms = videoMs(media);
        return make(Kind.VOICE, null, ms, null);
    }

    /** A photo or video sent in the chat itself; disappearing when it can be viewed once or replayed. */
    private static UnsentDetail direct(Map<?, ?> media, boolean disappearing) {
        if (media == null) return null;
        int type = (int) num(media, "media_type");
        long ms = videoMs(media);
        if (type == 8 || media.get("carousel_media") instanceof List) return make(Kind.ALBUM, null, 0, null);
        if (type == 2 || media.get("video_versions") != null || ms > 0) {
            return make(disappearing ? Kind.DISAPPEARING_VIDEO : Kind.VIDEO, null, ms, null);
        }
        if (type == 1 || media.get("image_versions2") != null) {
            return make(disappearing ? Kind.DISAPPEARING_PHOTO : Kind.PHOTO, null, 0, null);
        }
        return null;
    }

    private static UnsentDetail visual(Map<?, ?> m) {
        Map<?, ?> visual = obj(m, "visual_media");
        String mode = str(visual, "view_mode");
        return direct(obj(visual, "media"), "once".equals(mode) || "replayable".equals(mode));
    }

    private static UnsentDetail animated(Map<?, ?> m) {
        Map<?, ?> animated = obj(m, "animated_media");
        Object sticker = animated == null ? null : animated.get("is_sticker");
        return make(Boolean.TRUE.equals(sticker) || "true".equals(sticker) ? Kind.STICKER : Kind.GIF, null, 0, null);
    }

    private static UnsentDetail clip(Map<?, ?> m) {
        Map<?, ?> clip = obj(m, "clip");
        Map<?, ?> media = obj(clip, "clip");
        return make(Kind.REEL, owner(media != null ? media : clip), 0, null);
    }

    /** A shared feed post; Instagram's product type tells reels and stories apart. */
    private static UnsentDetail mediaShare(Map<?, ?> m) {
        Map<?, ?> media = obj(m, "media_share");
        if (media == null) media = obj(obj(m, "direct_media_share"), "media");
        if (media == null) return null;
        Kind kind = Kind.POST;
        String product = str(media, "product_type");
        if ("clips".equals(product)) kind = Kind.REEL;
        else if ("story".equals(product)) kind = Kind.STORY;
        else if ("igtv".equals(product)) kind = Kind.VIDEO_SHARE;
        return make(kind, owner(media), 0, null);
    }

    private static UnsentDetail story(Map<?, ?> m) {
        Map<?, ?> story = obj(m, "story_share");
        String user = owner(obj(story, "media"));
        Map<?, ?> reelOwner = obj(story, "reel_owner");
        if (user == null) user = handle(str(reelOwner, "profile_pic_username"));
        if (user == null) user = handle(str(reelOwner, "username"));
        return make(Kind.STORY, user, 0, null);
    }

    /** A reply to (or a mention in) a story; a reply's own words are kept. */
    private static UnsentDetail reelShare(Map<?, ?> m) {
        Map<?, ?> reel = obj(m, "reel_share");
        String user = owner(obj(reel, "media"));
        if ("mention".equals(str(reel, "type"))) return make(Kind.STORY_MENTION, user, 0, null);
        return make(Kind.STORY_REPLY, user, 0, str(reel, "text"));
    }

    private static UnsentDetail felix(Map<?, ?> m) {
        return make(Kind.VIDEO_SHARE, owner(obj(obj(m, "felix_share"), "video")), 0, null);
    }

    private static UnsentDetail profile(Map<?, ?> m) {
        return make(Kind.PROFILE, str(obj(m, "profile"), "username"), 0, null);
    }

    /** "Like: ❤️" when the like states its emoji; never an id or a word. */
    private static UnsentDetail like(Map<?, ?> m) {
        String emoji = str(m, "like");
        if (emoji != null && (emoji.length() > 16 || emoji.matches(".*[\\p{L}\\p{N}].*"))) emoji = null;
        return make(Kind.LIKE, null, 0, emoji);
    }

    /** A link's host ("Link: example.com"); its words when it has no readable address. */
    private static UnsentDetail link(Map<?, ?> m) {
        Map<?, ?> link = obj(m, "link");
        String url = str(obj(link, "link_context"), "link_url");
        if (url == null) url = str(link, "link_url");
        String text = str(link, "text");
        if (text == null) text = str(m, "text");
        if (url == null && text != null) {
            Matcher found = URL.matcher(text);
            if (found.find()) url = found.group();
        }
        String host = host(url);
        return make(Kind.LINK, null, 0, host != null ? host : text);
    }

    private static UnsentDetail described(Kind kind, Map<?, ?> event) {
        return make(kind, null, 0, str(event, "description"));
    }

    /** An Instagram share card (XMA) with a known kind; its header names the owner. */
    private static UnsentDetail share(Map<?, ?> m, Kind kind) {
        Map<?, ?> card = xma(m);
        // Some shares also carry the shared media itself under "media".
        Map<?, ?> media = obj(m, "media");
        if (kind == Kind.POST && (urlKind(str(card, "target_url")) == Kind.REEL
                || "clips".equals(str(media, "product_type")))) kind = Kind.REEL;
        String user = cardOwner(card);
        return make(kind, user != null ? user : owner(media), 0, null);
    }

    /** A share card of a type this build does not name: its link decides, else "Shared content". */
    private static UnsentDetail xmaShare(Map<?, ?> m) {
        Map<?, ?> card = xma(m);
        Kind kind = urlKind(str(card, "target_url"));
        if (kind != null) return share(m, kind);
        return make(Kind.SHARED, cardOwner(card), 0, str(card, "title_text"));
    }

    private static UnsentDetail titled(Kind kind, Map<?, ?> m) {
        Map<?, ?> card = xma(m);
        String title = str(card, "title_text");
        if (title == null) title = str(card, "header_title_text");
        if (title == null) title = str(obj(m, "music_sticker"), "artist_name");
        return make(kind, null, 0, title);
    }

    // ── fields ──────────────────────────────────────────────────────────────────

    /** Share cards are written as "xma" or, for the xma_* types, as the "hscroll_share" list. */
    private static Map<?, ?> xma(Map<?, ?> m) {
        Map<?, ?> card = obj(m, "xma");
        if (card != null) return card;
        if (m.get("hscroll_share") instanceof List<?> cards) {
            for (Object c : cards) if (c instanceof Map<?, ?> map) return map;
        }
        return null;
    }

    /** A share card's header names the owner; a story or profile link names it too. */
    private static String cardOwner(Map<?, ?> card) {
        String user = handle(str(card, "header_title_text"));
        return user != null ? user : userFromUrl(str(card, "target_url"));
    }

    private static String owner(Map<?, ?> media) {
        String user = handle(str(obj(media, "user"), "username"));
        return user != null ? user : handle(str(obj(media, "owner"), "username"));
    }

    private static long videoMs(Map<?, ?> media) {
        return Math.round(num(media, "video_duration") * 1000);
    }

    private static Kind urlKind(String url) {
        for (String segment : segments(url)) {
            switch (segment) {
                case "reel": case "reels": return Kind.REEL;
                case "p": return Kind.POST;
                case "stories": return Kind.STORY;
                case "tv": return Kind.VIDEO_SHARE;
                default: break;
            }
        }
        return null;
    }

    /** The handle in instagram.com/stories/<user>/…, instagram.com/_u/<user> or instagram.com/<user>. */
    static String userFromUrl(String url) {
        List<String> path = segments(url);
        if (path.size() >= 2 && (path.get(0).equals("stories") || path.get(0).equals("_u"))) return handle(path.get(1));
        if (path.size() == 1 && !NOT_PROFILES.contains(path.get(0))) return handle(path.get(0));
        return null;
    }

    private static List<String> segments(String url) {
        if (url == null) return List.of();
        try {
            URI uri = new URI(url.trim());
            String host = uri.getHost();
            if (host == null || !(host.equals("instagram.com") || host.endsWith(".instagram.com"))) return List.of();
            String path = uri.getPath() == null ? "" : uri.getPath().replaceAll("^/+|/+$", "");
            return path.isEmpty() ? List.of() : List.of(path.split("/+"));
        } catch (Exception e) {
            return List.of();
        }
    }

    /** example.com for http://www.example.com/page; null when it is no web address. */
    static String host(String url) {
        if (url == null) return null;
        String u = url.trim();
        if (!u.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) u = "https://" + u;
        try {
            String host = new URI(u).getHost();
            if (host == null || !host.contains(".")) return null;
            host = host.toLowerCase(Locale.ROOT);
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (Exception e) {
            return null;
        }
    }

    /** An Instagram username (lowercase letters, digits, "." and "_"), without "@"; else null. */
    static String handle(String value) {
        if (value == null) return null;
        String h = value.trim();
        if (h.startsWith("@")) h = h.substring(1);
        return HANDLE.matcher(h).matches() && h.matches(".*[a-z_].*") ? h : null;
    }

    /** One line, at most {@code max} characters (cut with "…"); null when empty. */
    static String clean(String value, int max) {
        if (value == null) return null;
        String s = value.replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) return null;
        if (s.length() <= max) return s;
        int cut = max - 1;
        if (Character.isHighSurrogate(s.charAt(cut - 1))) cut--;
        return s.substring(0, cut).trim() + "…";
    }

    private static Map<?, ?> obj(Map<?, ?> m, String key) {
        return m != null && m.get(key) instanceof Map<?, ?> map ? map : null;
    }

    private static String str(Map<?, ?> m, String key) {
        if (m == null || !(m.get(key) instanceof String s)) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** The first positive number under {@code key} in the subtree, at most {@code depth} levels down. */
    private static double deepNum(Object node, String key, int depth) {
        if (depth < 0) return 0;
        if (node instanceof Map<?, ?> map) {
            double own = num(map, key);
            if (own > 0) return own;
            for (Object child : map.values()) {
                double found = deepNum(child, key, depth - 1);
                if (found > 0) return found;
            }
        } else if (node instanceof List<?> list) {
            for (Object child : list) {
                double found = deepNum(child, key, depth - 1);
                if (found > 0) return found;
            }
        }
        return 0;
    }

    private static double num(Map<?, ?> m, String key) {
        Object v = m == null ? null : m.get(key);
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            try { return Double.parseDouble(s.trim()); } catch (NumberFormatException ignored) {}
        }
        return 0;
    }
}
