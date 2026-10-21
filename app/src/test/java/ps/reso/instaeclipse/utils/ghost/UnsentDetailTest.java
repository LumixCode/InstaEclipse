package ps.reso.instaeclipse.utils.ghost;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import ps.reso.instaeclipse.utils.ghost.UnsentDetail.Kind;

import static org.junit.Assert.*;

public class UnsentDetailTest {

    /** English names as in strings_local.xml (the module resources are not on the JVM). */
    private static final UnsentDetail.Labels EN = (kind, user) -> {
        switch (kind) {
            case REEL: return user != null ? "Reel by @" + user : "Reel";
            case POST: return user != null ? "Post by @" + user : "Post";
            case STORY: return user != null ? "Story by @" + user : "Story";
            case STORY_REPLY: return user != null ? "Reply to @" + user + "'s story" : "Story reply";
            case STORY_MENTION: return user != null ? "Story mention by @" + user : "Story mention";
            case PROFILE: return user != null ? "Profile @" + user : "Profile";
            case VIDEO_SHARE: return user != null ? "Video by @" + user : "Shared video";
            case SHARED: return user != null ? "Shared by @" + user : "Shared content";
            case VOICE: return "Voice message";
            case VIDEO: return "Video";
            case PHOTO: return "Photo";
            case ALBUM: return "Album";
            case DISAPPEARING_PHOTO: return "Disappearing photo";
            case DISAPPEARING_VIDEO: return "Disappearing video";
            case STICKER: return "Sticker";
            case GIF: return "GIF";
            case LINK: return "Link";
            case LIKE: return "Like";
            case LOCATION: return "Location";
            case NO_TEXT: return "Message without text";
            default: return kind.id;
        }
    };

    private static String describe(String json) {
        UnsentDetail detail = UnsentDetail.fromMessageJson(json);
        return detail == null ? null : detail.format(EN);
    }

    // ── durations ───────────────────────────────────────────────────────────────

    @Test public void durationsUseMinutesAndHours() {
        assertEquals("0:29", UnsentDetail.duration(29_000));
        assertEquals("0:29", UnsentDetail.duration(29_400));
        assertEquals("0:30", UnsentDetail.duration(29_600));
        assertEquals("1:05", UnsentDetail.duration(65_000));
        assertEquals("10:00", UnsentDetail.duration(600_000));
        assertEquals("1:05:03", UnsentDetail.duration(3_903_000));
        assertEquals("0:01", UnsentDetail.duration(300)); // never "0:00" for a real clip
    }

    // ── usernames ───────────────────────────────────────────────────────────────

    @Test public void onlyInstagramHandlesNameAnOwner() {
        assertEquals("nat.geo_1", UnsentDetail.handle("@nat.geo_1"));
        assertEquals("natgeo", UnsentDetail.handle(" natgeo "));
        assertNull(UnsentDetail.handle("Reel"));             // a title, not a username
        assertNull(UnsentDetail.handle("two words"));
        assertNull(UnsentDetail.handle("1234567"));          // an id
        assertNull(UnsentDetail.handle("a234567890123456789012345678901")); // 31 characters
        assertNull(UnsentDetail.handle(null));
        assertEquals("someone", UnsentDetail.userFromUrl("https://www.instagram.com/stories/someone/3456789/"));
        assertEquals("someone", UnsentDetail.userFromUrl("https://instagram.com/someone/?igsh=x"));
        assertNull(UnsentDetail.userFromUrl("https://www.instagram.com/reel/CODE123/"));
        assertNull(UnsentDetail.userFromUrl("https://example.com/someone"));
    }

    // ── message kinds ───────────────────────────────────────────────────────────

    @Test public void voiceMessageShowsItsLength() {
        assertEquals("Voice message · 0:29", describe("{\"item_type\":\"voice_media\",\"voice_media\":"
                + "{\"media\":{\"id\":\"1\"},\"duration_ms\":29000,\"waveform_data\":[0.1,0.2]}}"));
        assertEquals("Voice message · 1:05:03", describe("{\"item_type\":\"voice_media\",\"voice_media\":"
                + "{\"media\":{\"audio\":{\"duration\":3903000}}}}"));
        assertEquals("Voice message", describe("{\"item_type\":\"voice_media\"}"));
    }

    @Test public void sharedReelsPostsAndStoriesNameTheirOwner() {
        assertEquals("Reel by @natgeo", describe("{\"item_type\":\"clip\",\"clip\":{\"clip\":"
                + "{\"product_type\":\"clips\",\"user\":{\"username\":\"natgeo\",\"full_name\":\"Nat Geo\"}}}}"));
        assertEquals("Post by @someone", describe("{\"item_type\":\"media_share\",\"media_share\":"
                + "{\"media_type\":8,\"product_type\":\"carousel_container\",\"user\":{\"username\":\"someone\"}}}"));
        assertEquals("Reel by @someone", describe("{\"item_type\":\"media_share\",\"media_share\":"
                + "{\"media_type\":2,\"product_type\":\"clips\",\"owner\":{\"username\":\"someone\"}}}"));
        assertEquals("Story by @friend", describe("{\"item_type\":\"story_share\",\"story_share\":"
                + "{\"media\":{\"user\":{\"username\":\"friend\"}},\"is_reel_persisted\":false}}"));
        // An expired story no longer carries its media.
        assertEquals("Story", describe("{\"item_type\":\"story_share\",\"story_share\":{\"title\":\"Unavailable\"}}"));
        assertEquals("Video by @creator", describe("{\"item_type\":\"felix_share\",\"felix_share\":"
                + "{\"video\":{\"user\":{\"username\":\"creator\"}}}}"));
    }

    @Test public void shareCardsUseTheirHeaderOrLink() {
        assertEquals("Reel by @natgeo", describe("{\"item_type\":\"xma_clip\",\"hscroll_share\":[{"
                + "\"header_title_text\":\"natgeo\",\"title_text\":\"Look at this\","
                + "\"target_url\":\"https://www.instagram.com/reel/C0DE/\"}]}"));
        assertEquals("Story by @friend", describe("{\"item_type\":\"xma_story_share\",\"hscroll_share\":[{"
                + "\"header_title_text\":\"Story\",\"target_url\":\"https://www.instagram.com/stories/friend/123/\"}]}"));
        assertEquals("Reel by @someone", describe("{\"item_type\":\"xma_media_share\",\"xma\":{"
                + "\"header_title_text\":\"someone\",\"target_url\":\"https://instagram.com/reel/XYZ/\"}}"));
        assertEquals("Location: Eiffel Tower", describe("{\"item_type\":\"location_share_xma\",\"hscroll_share\":"
                + "[{\"title_text\":\"Eiffel Tower\",\"subtitle_text\":\"Paris\"}]}"));
        assertEquals("Shared by @shop: Summer sale", describe("{\"item_type\":\"generic_xma\",\"hscroll_share\":"
                + "[{\"header_title_text\":\"shop\",\"title_text\":\"Summer sale\"}]}"));
        assertEquals("Profile @someone", describe("{\"item_type\":\"xma_profile\",\"hscroll_share\":"
                + "[{\"header_title_text\":\"Someone Name\",\"target_url\":\"https://www.instagram.com/someone\"}]}"));
        // A share card without a usable header falls back to the shared media's owner.
        assertEquals("Reel by @maker", describe("{\"item_type\":\"xma_media_share\",\"hscroll_share\":"
                + "[{\"header_title_text\":\"Maker Studio\"}],\"media\":{\"product_type\":\"clips\","
                + "\"user\":{\"username\":\"maker\"}}}"));
        assertEquals("Reel", describe("{\"item_type\":\"xma_clip\"}"));
    }

    @Test public void stickersGifsAndMedia() {
        assertEquals("Sticker", describe("{\"item_type\":\"animated_media\",\"animated_media\":{\"is_sticker\":true}}"));
        assertEquals("GIF", describe("{\"item_type\":\"animated_media\",\"animated_media\":{\"is_sticker\":false}}"));
        assertEquals("Sticker", describe("{\"item_type\":\"store_sticker\",\"store_sticker\":{\"sticker_id\":\"1\"}}"));
        assertEquals("Photo", describe("{\"item_type\":\"media\",\"media\":{\"media_type\":1}}"));
        assertEquals("Video · 0:12", describe("{\"item_type\":\"media\",\"media\":{\"media_type\":2,\"video_duration\":12.3}}"));
        assertEquals("Album", describe("{\"item_type\":\"media\",\"media\":{\"media_type\":8,\"carousel_media\":[]}}"));
        assertEquals("Disappearing photo", describe("{\"item_type\":\"visual_media\",\"visual_media\":"
                + "{\"view_mode\":\"once\",\"media\":{\"media_type\":1}}}"));
        assertEquals("Disappearing video · 0:07", describe("{\"item_type\":\"raven_media\",\"raven_media\":"
                + "{\"media_type\":2,\"video_duration\":7.0}}"));
    }

    @Test public void linksLikesProfilesAndReplies() {
        assertEquals("Link: example.com", describe("{\"item_type\":\"link\",\"link\":{\"text\":\"see https://www.example.com/a\","
                + "\"link_context\":{\"link_url\":\"https://www.example.com/a\",\"link_title\":\"A\"}}}"));
        assertEquals("Link: example.org", describe("{\"item_type\":\"link\",\"link\":{\"text\":\"www.example.org/x\"}}"));
        assertEquals("Like: ❤️", describe("{\"item_type\":\"like\",\"like\":\"❤️\"}"));
        assertEquals("Like", describe("{\"item_type\":\"like\",\"like\":\"heart_123\"}")); // not an emoji
        assertEquals("Profile @someone", describe("{\"item_type\":\"profile\",\"profile\":{\"username\":\"someone\",\"pk\":\"5\"}}"));
        assertEquals("Reply to @friend's story: so nice", describe("{\"item_type\":\"reel_share\",\"reel_share\":"
                + "{\"type\":\"reply\",\"text\":\"so\\nnice\",\"media\":{\"user\":{\"username\":\"friend\"}}}}"));
        assertEquals("Story mention by @friend", describe("{\"item_type\":\"reel_share\",\"reel_share\":"
                + "{\"type\":\"mention\",\"media\":{\"user\":{\"username\":\"friend\"}}}}"));
    }

    @Test public void enumStyleTypesAndMissingTypesFallBackToThePayload() {
        assertEquals("Reel by @natgeo", describe("{\"item_type\":\"CLIPS_SHARE\",\"clip\":{\"clip\":{\"user\":{\"username\":\"natgeo\"}}}}"));
        assertEquals("Voice message · 0:03", describe("{\"voice_media\":{\"duration_ms\":3000}}"));
        assertEquals("Sticker", describe("{\"item_type\":\"brand_new_type\",\"animated_media\":{\"is_sticker\":true}}"));
    }

    @Test public void unknownMessagesAreNotInvented() {
        assertNull(describe("{\"item_type\":\"brand_new_type\",\"item_id\":\"1\"}"));
        assertNull(describe("{}"));
        assertNull(describe("not json"));
        assertNull(describe("[1,2]"));
        assertNull(describe(null));
    }

    @Test public void aQuotedMessageNeverDescribesItsParent() {
        String json = "{\"item_type\":\"brand_new_type\",\"replied_to_message\":{\"item_type\":\"clip\","
                + "\"clip\":{\"clip\":{\"user\":{\"username\":\"natgeo\"}}}},\"forwarded_message\":"
                + "{\"voice_media\":{\"duration_ms\":1000}}}";
        assertNull(describe(json));
    }

    @Test public void textCopiesShowTheirText() {
        assertEquals("hello there", describe("{\"item_type\":\"text\",\"text\":\"hello  there\"}"));
    }

    // ── storage and labels ──────────────────────────────────────────────────────

    @Test public void storedDetailsRoundTripAndUnknownKindsAreDropped() {
        UnsentDetail stored = UnsentDetail.of("voice", null, 29_000, null);
        assertEquals(Kind.VOICE, stored.kind);
        assertEquals("Voice message · 0:29", stored.format(EN));
        UnsentDetail reel = UnsentDetail.of("reel", "@natgeo", 0, null);
        assertEquals("natgeo", reel.user);
        assertNull(UnsentDetail.of("kind_from_a_future_build", "x", 0, null));
        assertSame(UnsentDetail.NO_TEXT, UnsentDetail.of("none", null, 0, null));
        assertSame(UnsentDetail.NO_TEXT, UnsentDetail.of("text", null, 0, "  "));
        assertEquals("Message without text", UnsentDetail.NO_TEXT.format(EN));
        // A bad stored owner is never shown as one.
        assertEquals("Reel", UnsentDetail.of("reel", "Not A Handle", 0, null).format(EN));
    }

    @Test public void longWordsAreCutOnOneLine() {
        String cut = UnsentDetail.clean("a\nb " + "x".repeat(200), UnsentDetail.MAX_EXTRA);
        assertEquals(UnsentDetail.MAX_EXTRA, cut.length());
        assertTrue(cut.startsWith("a b x"));
        assertTrue(cut.endsWith("…"));
        // An emoji is never split in half.
        String emoji = "x".repeat(UnsentDetail.MAX_EXTRA - 2) + "😀😀";
        String cutEmoji = UnsentDetail.clean(emoji, UnsentDetail.MAX_EXTRA);
        assertFalse(Character.isHighSurrogate(cutEmoji.charAt(cutEmoji.length() - 2)));
        assertNull(UnsentDetail.clean(" \n ", 10));
    }

    // ── JSON reader ─────────────────────────────────────────────────────────────

    @Test public void jsonReaderHandlesEscapesNumbersAndNesting() {
        Object parsed = UnsentJson.parse(" {\"a\":\"q\\\"\\\\\\/\\n\\u00e9\\ud83d\\ude00\",\"n\":-1.5e2,"
                + "\"b\":[true,false,null,{}],\"e\":[]} ");
        Map<?, ?> map = (Map<?, ?>) parsed;
        assertEquals("q\"\\/\né😀", map.get("a"));
        assertEquals(-150.0, (Double) map.get("n"), 0);
        assertEquals(List.of(true, false), ((List<?>) map.get("b")).subList(0, 2));
        assertNull(((List<?>) map.get("b")).get(2));
        assertTrue(((List<?>) map.get("e")).isEmpty());
    }

    @Test public void jsonReaderRejectsBrokenAndHostileInput() {
        for (String bad : new String[]{"", "{", "{\"a\":}", "{\"a\" 1}", "[1,]x", "\"open", "{\"a\":tru}", "{} {}"}) {
            try {
                UnsentJson.parse(bad);
                fail("accepted " + bad);
            } catch (IllegalArgumentException expected) {}
        }
        String deep = "[".repeat(10_000) + "]".repeat(10_000);
        try {
            UnsentJson.parse(deep);
            fail("accepted deep nesting");
        } catch (IllegalArgumentException expected) {}
        assertNull(UnsentDetail.fromMessageJson(deep));
    }
}
