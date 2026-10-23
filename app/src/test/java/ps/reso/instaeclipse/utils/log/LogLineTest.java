package ps.reso.instaeclipse.utils.log;

import org.junit.Test;

import ps.reso.instaeclipse.utils.log.LogLine.Level;

import static org.junit.Assert.*;

public class LogLineTest {

    @Test
    public void markedLevelWins() {
        assertEquals(Level.WARN, LogLine.levelOf("W/ [A.b:1] (IE|Crash) previous exit"));
        assertEquals(Level.DETAIL, LogLine.levelOf("D/ [A.b:1] (IE|RemoveMetaAI) skipped X.0Q77.invoke"));
        assertEquals(Level.OK, LogLine.levelOf("S/ [A.b:1] (IE|ShareSheet) ready (2 hooks)"));
        assertEquals(Level.ERROR, LogLine.levelOf("E/ [A.b:1] (IE|DL) Could not save the video"));
        assertEquals(Level.INFO, LogLine.levelOf("I/ [A.b:1] (InstaEclipse): failed=0"));
    }

    @Test
    public void legacyLinesDoNotTurnTagsOrCountsIntoErrors() {
        assertEquals(Level.INFO, LogLine.levelOf("[CrashForensics.exits:221] (IE|Crash) previous exit 20250101 USER_REQUESTED"));
        assertEquals(Level.INFO, LogLine.levelOf("[ManualStorySeenHook.installMenu:390] (IE|ManualStorySeen) Menu discovery: skipped=0"));
        assertEquals(Level.OK, LogLine.levelOf("[HideHeaderButtonsHook.hookProfileActionBar:161] (IE|HideButtons) ✅ ProfileActionBar fallback hooked: A00"));
        assertEquals(Level.WARN, LogLine.levelOf("[DevOptionsUnlockHook.x:106] (InstaEclipse | DevOptionsEnable): ⚠️ Tier 1 failed."));
        assertEquals(Level.ERROR, LogLine.levelOf("[X.y:1] (ExactTimestamps) Could not install: java.lang.IllegalStateException"));
    }

    @Test
    public void lineLevelComesOnlyFromStatusEmoji() {
        assertEquals(Level.ERROR, LogLine.emojiLevel("(IE|X) ❌ Failed to hook"));
        assertEquals(Level.WARN, LogLine.emojiLevel("(IE|X) ⚠️ inflate: boom"));
        assertEquals(Level.OK, LogLine.emojiLevel("(IE|X) ✅ installed"));
        assertEquals(Level.INFO, LogLine.emojiLevel("(IE|X) 3 failed, 2 skipped, fallback used"));
    }

    @Test
    public void friendlyDropsMarkerCallerAndEmoji() {
        assertEquals("Keep unsent messages: hooks installed",
                LogLine.friendly("S/ [KeepUnsentMessagesHook.install:412] (IE|KeepUnsent) ✅ hooks installed"));
        assertEquals("Disable double-tap like: ready",
                LogLine.friendly("S/ [DisableDoubleTapLikeHook.findAndHook:140] (InstaEclipse | DoubleTapLike): ready"));
        assertEquals("InstaEclipse: Sync: Companion app requested current preferences.",
                LogLine.friendly("I/ [Module$2.onReceive:1054] (InstaEclipse) Sync: Companion app requested current preferences."));
        assertEquals("Exact timestamps: ready",
                LogLine.friendly("S/ [ExactTimestampHook.install:79] (ExactTimestamps) ready"));
        assertEquals("Downloads · code search: 3 getters cached",
                LogLine.friendly("D/ [A.b:1] (IE|DL|DexKit) 3 getters cached"));
        assertEquals("plain text without tag", LogLine.friendly("I/ plain text without tag"));
    }

    @Test
    public void stripMarkerKeepsOldBodies() {
        assertEquals("[A.b:1] (IE|X) hi", LogLine.stripMarker("W/ [A.b:1] (IE|X) hi"));
        assertEquals("[A.b:1] (IE|X) hi", LogLine.stripMarker("[A.b:1] (IE|X) hi"));
        assertEquals("X/ not a level", LogLine.stripMarker("X/ not a level"));
    }

    @Test
    public void humanizesUnknownTagsAndKeepsAcronyms() {
        assertEquals("Hide suggested posts", LogLine.featureName("InstaEclipse | HideSuggested"));
        assertEquals("Reels viewer", LogLine.featureName("InstaEclipse | ReelsViewer"));
        assertEquals("Ghost DM seen", LogLine.featureName("IE|GhostDMSeen"));
        assertEquals("InstaEclipse", LogLine.featureName("InstaEclipse"));
    }
}
