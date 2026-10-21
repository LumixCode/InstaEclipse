package ps.reso.instaeclipse.utils.media;

import android.util.AtomicFile;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import ps.reso.instaeclipse.utils.feature.FeatureFlags;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class StoryCachePersistenceTest {
    private static final long HOUR = 3_600_000L;
    private final List<File> mediaFiles = new ArrayList<>();
    private AtomicFile storage;
    private Object originalStorage;
    private boolean originalLoaded;
    private boolean originalLoadFailed;
    private int originalHours;
    private List<StoryCache.Entry> liveCache;
    private List<StoryCache.Entry> originalCache;

    @SuppressWarnings("unchecked")
    @Before public void isolateCache() throws Exception {
        originalStorage = field("storage").get(null);
        originalLoaded = field("loaded").getBoolean(null);
        originalLoadFailed = field("loadFailed").getBoolean(null);
        originalHours = FeatureFlags.storyCacheHours;
        liveCache = (List<StoryCache.Entry>) field("cache").get(null);
        originalCache = new ArrayList<>(liveCache);
        storage = new AtomicFile(File.createTempFile("ie_story_index_", ".json", directory()));
        StoryCache.write(storage, List.of());
        field("storage").set(null, storage);
        FeatureFlags.storyCacheHours = 24;
        restart();
    }

    @After public void restoreCache() throws Exception {
        liveCache.clear();
        liveCache.addAll(originalCache);
        field("storage").set(null, originalStorage);
        field("loaded").setBoolean(null, originalLoaded);
        field("loadFailed").setBoolean(null, originalLoadFailed);
        FeatureFlags.storyCacheHours = originalHours;
        storage.delete();
        corruptIndex().delete();
        for (File media : mediaFiles) media.delete();
    }

    @Test public void changingDurationPrunesLoadedEntriesAndPersistsTheRemoval() throws Exception {
        StoryCache.Entry old = entry("old", 12, 0);
        StoryCache.Entry recent = entry("recent", 1, 0);
        StoryCache.write(storage, List.of(old, recent));
        assertEquals(2, StoryCache.entries().size());

        FeatureFlags.storyCacheHours = 6;
        StoryCache.refresh();
        assertEquals("recent", StoryCache.entries().get(0).id);
        assertEquals(1, StoryCache.entries().size());
        assertFalse(new File(old.path).exists());
        assertEquals("recent", StoryCache.read(storage).get(0).id);
        assertEquals(1, StoryCache.read(storage).size());

        restart();
        assertFalse(StoryCache.has("old"));
        assertTrue(StoryCache.has("recent"));
    }

    @Test public void hasAppliesChangedRetentionWithoutTouchingFiles() throws Exception {
        StoryCache.Entry story = entry("story", 12, 0);
        StoryCache.write(storage, List.of(story));
        assertTrue(StoryCache.has("story"));
        FeatureFlags.storyCacheHours = 6;
        assertFalse(StoryCache.has("story"));
        // has() runs on the UI thread for every story shown, so removal waits for the next prune.
        assertEquals(1, StoryCache.read(storage).size());
        assertTrue(new File(story.path).exists());
        StoryCache.refresh();
        assertTrue(StoryCache.read(storage).isEmpty());
        assertFalse(new File(story.path).exists());
    }

    @Test public void entriesAlsoPrunesAfterTheInitialLoad() throws Exception {
        StoryCache.write(storage, List.of(entry("story", 12, 0)));
        assertEquals(1, StoryCache.entries().size());
        FeatureFlags.storyCacheHours = 6;
        assertTrue(StoryCache.entries().isEmpty());
        assertTrue(StoryCache.read(storage).isEmpty());
    }

    @Test public void instagramExpiryDoesNotShortenTheSelectedLocalRetention() throws Exception {
        FeatureFlags.storyCacheHours = 72;
        StoryCache.Entry story = entry("expired-on-instagram", 36, System.currentTimeMillis() - HOUR);
        StoryCache.write(storage, List.of(story));
        assertTrue(StoryCache.entries().get(0).isExpired());
        assertTrue(StoryCache.has(story.id));
        assertTrue(new File(story.path).exists());
    }

    @Test public void missingMediaIsRemovedFromMemoryAndTheIndex() throws Exception {
        StoryCache.Entry story = entry("missing", 1, 0);
        StoryCache.write(storage, List.of(story));
        assertTrue(StoryCache.has("missing"));
        assertTrue(new File(story.path).delete());
        assertTrue(StoryCache.entries().isEmpty());
        assertFalse(StoryCache.has("missing"));
        assertTrue(StoryCache.read(storage).isEmpty());
    }

    @Test public void initialLoadAlsoPersistsExpiredEntryRemoval() throws Exception {
        StoryCache.Entry story = entry("expired", 25, 0);
        StoryCache.write(storage, List.of(story));
        assertTrue(StoryCache.entries().isEmpty());
        assertTrue(StoryCache.read(storage).isEmpty());
        assertFalse(new File(story.path).exists());
    }

    @Test public void maximumSizeStillEvictsTheOldestStory() throws Exception {
        StoryCache.Entry old = entry("old", 2, 0);
        StoryCache.Entry recent = entry("recent", 1, 0);
        // Sparse files exercise the 500 MB cap without downloading or writing 600 MB of media.
        for (StoryCache.Entry story : List.of(old, recent)) {
            try (RandomAccessFile file = new RandomAccessFile(story.path, "rw")) {
                file.setLength(300L * 1024 * 1024);
            }
        }
        StoryCache.write(storage, List.of(old, recent));
        assertEquals(1, StoryCache.entries().size());
        assertEquals("recent", StoryCache.entries().get(0).id);
        assertFalse(new File(old.path).exists());
        assertTrue(new File(recent.path).exists());
        assertEquals("recent", StoryCache.read(storage).get(0).id);
    }

    @Test public void interruptedIndexReplacementRetainsTheLastCompleteIndex() throws Exception {
        StoryCache.write(storage, List.of(entry("saved", 1, 0)));
        try (FileOutputStream unfinished = storage.startWrite()) {
            unfinished.write("[{".getBytes(StandardCharsets.UTF_8));
        }
        assertEquals("saved", StoryCache.entries().get(0).id);
    }

    @Test public void unreadableIndexIsKeptAsideAndTheCacheStartsEmpty() throws Exception {
        byte[] incomplete = "[{".getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream out = new FileOutputStream(storage.getBaseFile())) {
            out.write(incomplete);
        }
        assertTrue(StoryCache.entries().isEmpty());
        assertArrayEquals(incomplete, new AtomicFile(corruptIndex()).readFully());
        StoryCache.clearAll();
        assertTrue(StoryCache.read(storage).isEmpty());
        assertArrayEquals(incomplete, new AtomicFile(corruptIndex()).readFully());
    }

    @Test public void captureDropsAnExpiredEntryBeforeReusingItsId() throws Exception {
        StoryCache.Entry stale = entry("story", 25, 0);
        StoryCache.write(storage, List.of(stale));
        // The download cannot succeed here; the stale entry must already be gone before it starts,
        // because a fresh capture of the same story is written to the same file path.
        StoryCache.capture("story", "author", "https://example.invalid/story", false, 0);
        assertTrue(StoryCache.read(storage).isEmpty());
        assertFalse(new File(stale.path).exists());
        assertFalse(StoryCache.has("story"));
    }

    private StoryCache.Entry entry(String id, int ageHours, long expiringAt) throws Exception {
        File media = File.createTempFile("ie_story_media_", ".jpg", directory());
        mediaFiles.add(media);
        try (FileOutputStream out = new FileOutputStream(media)) { out.write(1); }
        return new StoryCache.Entry(id, "author", "https://example.invalid/story", false,
                media.getAbsolutePath(), System.currentTimeMillis() - ageHours * HOUR, expiringAt);
    }

    private void restart() throws Exception {
        liveCache.clear();
        field("loaded").setBoolean(null, false);
        field("loadFailed").setBoolean(null, false);
    }

    private File corruptIndex() {
        return new File(storage.getBaseFile().getPath() + ".corrupt");
    }

    private static File directory() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir();
    }

    private static Field field(String name) throws Exception {
        Field field = StoryCache.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
