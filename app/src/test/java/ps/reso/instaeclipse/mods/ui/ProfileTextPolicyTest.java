package ps.reso.instaeclipse.mods.ui;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class ProfileTextPolicyTest {
    private static final String BIO = "Photographer 📷 based in Example City\nDMs open for collabs\n@studio_one #streetphoto\nlinktr.ee/example";

    @Test public void titleIsTheUsername() {
        assertEquals("example.user", ProfileTextPolicy.username("example.user"));
    }

    @Test public void privateAccountPadlockAndMarksAreNotPartOfTheUsername() {
        assertEquals("example.user", ProfileTextPolicy.username("  example.user")); // padlock image span + space
        assertEquals("example.user", ProfileTextPolicy.username("example.user ￼"));
        assertEquals("example.user", ProfileTextPolicy.username("⁨example.user⁩"));
    }

    @Test public void emptyTitleHasNoUsername() {
        assertNull(ProfileTextPolicy.username(null));
        assertNull(ProfileTextPolicy.username(" ‎ "));
    }

    @Test public void collapsedBioShowsIt() {
        assertTrue(ProfileTextPolicy.shows("Photographer 📷 based in Example City\nDMs open for… more", BIO));
    }

    @Test public void expandedBioWithOtherLineBreaksShowsIt() {
        assertTrue(ProfileTextPolicy.shows("Photographer 📷 based in Example City DMs open for collabs @studio_one #streetphoto linktr.ee/example", BIO));
    }

    @Test public void bioCollapsedWithoutAnEllipsisShowsIt() {
        assertTrue(ProfileTextPolicy.shows("Photographer 📷 based in Example City\nDMs open for more", BIO));
    }

    @Test public void bioCollapsedAfterAShortFirstLine() {
        assertTrue(ProfileTextPolicy.shows("Wow… more", "Wow\n\nThis is the rest of the bio"));
        assertTrue(ProfileTextPolicy.shows("Wow... more", "Wow\n\nThis is the rest of the bio"));
    }

    @Test public void otherHeaderTextIsNotTheBio() {
        assertFalse(ProfileTextPolicy.shows("Photographer", BIO)); // the business category
        assertFalse(ProfileTextPolicy.shows("linktr.ee/example", BIO));
        assertFalse(ProfileTextPolicy.shows("@studio_one", BIO));
        assertFalse(ProfileTextPolicy.shows("1 Example Street, Example City", BIO));
        assertFalse(ProfileTextPolicy.shows("Something else… more", BIO));
    }

    @Test public void shortBioMustStartTheText() {
        assertTrue(ProfileTextPolicy.shows("🇺🇳 travel", "🇺🇳 travel"));
        assertFalse(ProfileTextPolicy.shows("I love 🇺🇳 travel guides", "🇺🇳 travel"));
    }

    @Test public void heldMentionInsideTheBioCopiesTheWholeModelBio() {
        String copied = ProfileTextPolicy.bioFor(List.of("@studio_one", "Photographer 📷 based in Example City\nDMs open for… more"),
                BIO, List.of());
        assertEquals(BIO, copied);
    }

    @Test public void headerBioIsTheFallbackWhenTheModelHasNone() {
        assertEquals(BIO, ProfileTextPolicy.bioFor(List.of("Photographer 📷 based in Example City\nDMs open for… more"),
                null, List.of("Other profile bio here", BIO + "\n")));
    }

    @Test public void headerBioIsUsedWhenTheModelBioIsNotWhatIsShown() {
        String shown = "Spoofed bio for screenshots only";
        assertEquals(shown, ProfileTextPolicy.bioFor(List.of(shown), BIO, List.of(shown)));
    }

    @Test public void holdOffTheBioCopiesNothing() {
        assertNull(ProfileTextPolicy.bioFor(List.of("linktr.ee/example"), BIO, List.of(BIO)));
        assertNull(ProfileTextPolicy.bioFor(List.of(), BIO, List.of(BIO)));
        assertNull(ProfileTextPolicy.bioFor(List.of("Photographer 📷 based in Example City"), null, List.of()));
    }
}
