package ps.reso.instaeclipse.utils.ghost;

import android.util.AtomicFile;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.*;

/** The log's "d" field (what a message without text was) next to entries written before it existed. */
@RunWith(AndroidJUnit4.class)
public class UnsentLogDetailTest {

    @Test public void detailsSurviveARewriteAndOldEntriesLoadWithout() throws Exception {
        File dir = InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir();
        AtomicFile file = new AtomicFile(new File(dir, "ie-unsent-detail-" + java.util.UUID.randomUUID() + ".json"));
        try {
            String json = "["
                    + "{\"t\":1,\"a\":\"alice\",\"th\":\"chat\",\"id\":\"old\",\"m\":\"[Retained message]\",\"body\":false},"
                    + "{\"t\":2,\"a\":\"alice\",\"th\":\"chat\",\"id\":\"voice\",\"m\":\"[Retained message]\",\"body\":false,"
                    + "\"d\":{\"k\":\"voice\",\"ms\":29000}},"
                    + "{\"t\":3,\"a\":\"alice\",\"th\":\"chat\",\"id\":\"reel\",\"m\":\"[Unsent message]\",\"body\":false,"
                    + "\"logOnly\":true,\"d\":{\"k\":\"reel\",\"u\":\"natgeo\"}},"
                    + "{\"t\":4,\"a\":\"alice\",\"th\":\"chat\",\"id\":\"future\",\"m\":\"[Retained message]\",\"body\":false,"
                    + "\"d\":{\"k\":\"kind_from_a_future_build\"}}]";
            try (FileOutputStream out = new FileOutputStream(file.getBaseFile())) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            }
            UnsentMessageIndex index = new UnsentMessageIndex(1000);
            List<UnsentLog.Entry> entries = UnsentLog.read(file, index, new UnsentMessageIndex(1000));
            assertNull(entries.get(0).detail);
            assertEquals("[Retained message]", entries.get(0).text);
            assertEquals(UnsentDetail.Kind.VOICE, entries.get(1).detail.kind);
            assertEquals(29000, entries.get(1).detail.durationMs);
            assertEquals("natgeo", entries.get(2).detail.user);
            assertTrue(entries.get(2).logOnly);
            assertNull(entries.get(3).detail);

            UnsentLog.write(file, entries, index, new UnsentMessageIndex(1000));
            List<UnsentLog.Entry> reread = UnsentLog.read(file, new UnsentMessageIndex(1000), new UnsentMessageIndex(1000));
            assertNull(reread.get(0).detail);
            assertEquals(UnsentDetail.Kind.VOICE, reread.get(1).detail.kind);
            assertEquals(UnsentDetail.Kind.REEL, reread.get(2).detail.kind);
            // The old placeholder stays in "m" for older builds and backups.
            assertEquals("[Unsent message]", reread.get(2).text);
            assertFalse(reread.get(2).hasBody);
        } finally {
            file.delete();
        }
    }
}
