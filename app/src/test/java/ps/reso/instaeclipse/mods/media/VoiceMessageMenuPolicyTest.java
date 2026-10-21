package ps.reso.instaeclipse.mods.media;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class VoiceMessageMenuPolicyTest {
    @Test public void downloadStaysAboveNativeThreeItemOverflowCutoff() {
        List<String> original = List.of("reply", "forward", "copy", "report", "unsend");
        assertEquals(List.of("download", "reply", "forward", "copy", "report", "unsend"), promote(original));
        assertEquals(List.of("reply", "forward", "copy", "report", "unsend"), original);
    }

    @Test public void statusHeaderRemainsBeforeDownloadAndExistingSaveIsPromoted() {
        assertEquals(List.of("status", "download", "reply", "forward"),
                promote(List.of("status", "reply", "forward", "save")));
    }

    @Test public void secondarySaveDoesNotSuppressVisibleDownload() {
        assertEquals(List.of("download", "group-with-secondary-save", "reply"),
                promote(List.of("group-with-secondary-save", "reply")));
    }

    @Test public void repeatedInjectionKeepsExactlyOneDownloadEntry() {
        assertEquals(List.of("download", "reply"), promote(promote(List.of("reply"))));
        assertEquals(List.of("download"), promote(List.of()));
    }

    private static List<String> promote(List<String> input) {
        return VoiceMessageMenuPolicy.withVisibleDownload(input, "download",
                action -> action.equals("save") || action.equals("download"), action -> action.equals("status"));
    }
}
