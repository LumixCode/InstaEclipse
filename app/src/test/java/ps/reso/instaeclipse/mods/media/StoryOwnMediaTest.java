package ps.reso.instaeclipse.mods.media;

import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.*;

public class StoryOwnMediaTest {
    static final class Media { }

    /** IG 451 ReelItem shape: the secondary A08 is declared before the item's own final A17. */
    static final class ReelItem {
        static Media cached;
        public Media A08;
        public final Media A17;
        ReelItem(Media own) { A17 = own; }
    }
    static final class OnlyMedia { Media media; }
    static final class TwoMutable { Media first, second; }
    static final class TwoFinal {
        final Media first, second;
        TwoFinal(Media first, Media second) { this.first = first; this.second = second; }
    }

    private static Field own(Class<?> type) {
        return StoryDownloadHook.OwnMedia.field(type, Media.class.getName());
    }

    @Test public void storyOpenedFromFeedPostResolvesItsOwnMedia() throws Exception {
        Media story = new Media(), feedPost = new Media();
        ReelItem item = new ReelItem(story);
        item.A08 = feedPost;
        ReelItem.cached = feedPost;
        Field field = own(ReelItem.class);
        assertEquals("A17", field.getName());
        assertSame(story, field.get(item));
    }

    @Test public void itemWithoutOwnMediaNeverFallsBackToTheFeedPost() throws Exception {
        ReelItem item = new ReelItem(null);
        item.A08 = new Media();
        assertNull(own(ReelItem.class).get(item));
    }

    @Test public void singleMediaFieldIsTheOwnOne() {
        assertEquals("media", own(OnlyMedia.class).getName());
    }

    @Test public void ambiguousMediaFieldsAreNotGuessed() {
        assertNull(own(TwoMutable.class));
        assertNull(own(TwoFinal.class));
        assertNull(own(Object.class));
    }
}
