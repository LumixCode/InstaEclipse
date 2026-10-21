package ps.reso.instaeclipse.mods.media;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class MediaTypeDetectorTest {
    @Test public void verifiedFormatsKeepTheirOwnExtensionsWithoutChangingBytes() {
        String[][] formats = {{"image/png", ".png"}, {"image/webp", ".webp"},
                {"image/avif", ".avif"}, {"image/heic", ".heic"}, {"image/gif", ".gif"},
                {"audio/webm", ".webm"}, {"audio/mpeg", ".mp3"}, {"audio/mp4", ".m4a"}};
        for (String[] format : formats) {
            MediaTypeDetector.Result result = MediaTypeDetector.verified(format[0], "captured_custom.jpg");
            assertEquals(format[0], result.mimeType);
            assertEquals("captured_custom" + format[1], result.filename);
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void verifiedPublicationRejectsNonMediaMime() {
        MediaTypeDetector.verified("text/html", "error.jpg");
    }


    @Test
    public void detectsMp4ByFtypEvenWhenUrlAndNameLookLikeImage() {
        byte[] header = new byte[] {
                0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 0, 0
        };

        assertEquals(MediaTypeDetector.Kind.VIDEO,
                MediaTypeDetector.sniff(header, header.length));
        assertEquals("story_123.mp4", MediaTypeDetector.withCorrectExtension(
                "story_123.jpg", MediaTypeDetector.Kind.VIDEO));
    }

    @Test
    public void detectsJpegAndCorrectsWrongVideoExtension() {
        byte[] header = new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0};

        assertEquals(MediaTypeDetector.Kind.IMAGE,
                MediaTypeDetector.sniff(header, header.length));
        assertEquals("story_123.jpg", MediaTypeDetector.withCorrectExtension(
                "story_123.mp4", MediaTypeDetector.Kind.IMAGE));
    }

    @Test
    public void doesNotTreatHeicAsVideo() {
        byte[] header = new byte[] {
                0, 0, 0, 24, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c', 0, 0, 0, 0
        };

        assertEquals(MediaTypeDetector.Kind.IMAGE,
                MediaTypeDetector.sniff(header, header.length));
    }

    @Test
    public void usesContentTypeWhenSignatureIsUnknown() {
        assertEquals(MediaTypeDetector.Kind.VIDEO,
                MediaTypeDetector.fromContentType("video/mp4; charset=binary"));
        assertEquals(MediaTypeDetector.Kind.IMAGE,
                MediaTypeDetector.fromContentType("image/webp"));
        assertEquals(MediaTypeDetector.Kind.UNKNOWN,
                MediaTypeDetector.sniff("unknown".getBytes(StandardCharsets.US_ASCII), 7));
    }

    @Test
    public void preservesAudioForMp4VoiceContainerWithMisleadingCdnType() throws Exception {
        assertAudio(new byte[] {0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'},
                "video/mp4", "audio/mp4", "voice.m4a");
    }

    @Test
    public void identifiesM4aBrandEvenWithoutAudioResponseType() throws Exception {
        assertAudio(new byte[] {0, 0, 0, 24, 'f', 't', 'y', 'p', 'M', '4', 'A', ' '},
                "application/octet-stream", "audio/mp4", "voice.m4a");
    }

    @Test
    public void mp4ContainerWinsOverMisleadingAudioResponseType() throws Exception {
        assertAudio(new byte[] {0, 0, 0, 24, 'f', 't', 'y', 'p', 'M', '4', 'A', ' '},
                "audio/mpeg", "audio/mp4", "voice.m4a");
    }

    @Test
    public void keepsOggWaveAndMp3FilesInTheirOriginalAudioContainer() throws Exception {
        assertAudio("OggSabcdefghijkl".getBytes(StandardCharsets.US_ASCII),
                "application/octet-stream", "audio/ogg", "voice.ogg");
        assertAudio("RIFF1234WAVEabcd".getBytes(StandardCharsets.US_ASCII),
                "application/octet-stream", "audio/wav", "voice.wav");
        assertAudio("ID3abcdefghijkl".getBytes(StandardCharsets.US_ASCII),
                "application/octet-stream", "audio/mpeg", "voice.mp3");
    }

    private static void assertAudio(byte[] bytes, String responseType, String expectedMime,
                                    String expectedFilename) throws Exception {
        File file = File.createTempFile("ie-audio-test", ".bin");
        try {
            Files.write(file.toPath(), bytes);
            MediaTypeDetector.Result result = MediaTypeDetector.resolve(
                    file, responseType, "audio/mp4", "voice.jpg");
            assertEquals(MediaTypeDetector.Kind.AUDIO, result.kind);
            assertEquals(expectedMime, result.mimeType);
            assertEquals(expectedFilename, result.filename);
        } finally {
            Files.deleteIfExists(file.toPath());
        }
    }

    @Test
    public void keepsGifAsGifEvenWhenRequestedAsJpeg() throws Exception {
        File f = File.createTempFile("ie_gif", ".bin");
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write("GIF89a".getBytes(StandardCharsets.US_ASCII));
            out.write(new byte[16]);
        }
        MediaTypeDetector.Result r = MediaTypeDetector.resolve(f, "image/gif", "image/jpeg", "comment_gif_1.jpg");
        assertEquals("image/gif", r.mimeType);
        assertEquals("comment_gif_1.gif", r.filename);
        f.delete();
    }
}
