package ps.reso.instaeclipse.mods.misc;

import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

public class ExactTimestampScopeTest {
    @Test public void inboxAndUnrelatedRelativeDatesStayNative() {
        assertFalse(ExactTimestampScope.isContentCaller("X.InboxBinder", List.of("android.content.Context"),
                List.of("Inbox - Digest shouldn't be null for this type")));
        assertFalse(ExactTimestampScope.isContentCaller("com.instagram.direct.inbox.Row", List.of(), List.of()));
        assertFalse(ExactTimestampScope.isContentCaller("X.Other", List.of("android.content.Context", "long"), List.of()));
        assertFalse(ExactTimestampScope.isContentCaller("X.Mixed", List.of("com.instagram.feed.media.Media"),
                List.of("direct_inbox_campaign")));
    }

    @Test public void acceptsProvenPostStoryAndCommentBoundaries() {
        assertTrue(ExactTimestampScope.isContentCaller("X.Post", List.of("com.instagram.feed.media.Media"), List.of()));
        assertTrue(ExactTimestampScope.isContentCaller("com.instagram.model.reels.ReelItem", List.of(), List.of()));
        assertTrue(ExactTimestampScope.isContentCaller("X.Archive", List.of("com.instagram.model.reels.ReelItem"), List.of()));
        assertTrue(ExactTimestampScope.isContentCaller("X.Comment", List.of(), List.of("row_comment_textview_time_ago")));
        assertTrue(ExactTimestampScope.isContentCaller("X.Comment", List.of(),
                List.of("null cannot be cast to instagram.features.feed.wellbeing.limitedprofile.adapter.LimitedCommentRowViewBinder.Holder")));
    }

    @Test public void nestedBindsKeepScopeUntilOutermostExitAndExceptionsRestoreIt() {
        ExactTimestampScope scope = new ExactTimestampScope();
        assertFalse(scope.active());
        scope.enter();
        try {
            scope.enter();
            try {
                assertTrue(scope.active());
                throw new IllegalStateException("host bind failed");
            } catch (IllegalStateException expected) {
                assertEquals("host bind failed", expected.getMessage());
            } finally {
                scope.exit();
            }
            assertTrue(scope.active());
        } finally {
            scope.exit();
        }
        assertFalse(scope.active());
    }

    @Test public void scopeDoesNotLeakToOtherThreadsOrDifferentSurfaces() throws Exception {
        ExactTimestampScope content = new ExactTimestampScope();
        ExactTimestampScope dm = new ExactTimestampScope();
        content.enter();
        try {
            assertFalse(dm.active());
            AtomicBoolean otherThreadActive = new AtomicBoolean(true);
            Thread thread = new Thread(() -> otherThreadActive.set(content.active()));
            thread.start();
            thread.join();
            assertFalse(otherThreadActive.get());
            assertTrue(content.active());
        } finally {
            content.exit();
        }
    }
}
