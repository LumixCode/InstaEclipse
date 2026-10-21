package ps.reso.instaeclipse.mods.reels;

import org.junit.Test;

import java.net.URI;

import static org.junit.Assert.*;

public class ReelRequestPolicyTest {
    private URI uri(String path) { return URI.create("https://i.instagram.com" + path); }

    @Test public void disabledFeatureLeavesReelsAlone() {
        assertFalse(ReelRequestPolicy.shouldBlock(uri("/api/v1/clips/discover/"), false, false, false));
        assertFalse(ReelRequestPolicy.shouldBlock(uri("/api/v1/clips/discover/"), false, true, false));
    }

    @Test public void genericScheduledRestrictionUsesTheSameDmSafeRequestBoundary() {
        assertTrue(ReelRequestPolicy.shouldBlock(uri("/api/v1/clips/discover/"), true, true));
        assertFalse(ReelRequestPolicy.shouldBlock(uri("/api/v1/clips/items/?ids=shared"), true, true));
        assertTrue(ReelRequestPolicy.shouldBlock(uri("/api/v1/clips/items/?max_id=next"), true, true));
        assertTrue(ReelRequestPolicy.shouldBlock(uri("/api/v1/clips/items/?ids=shared"), true, false));
        assertFalse(ReelRequestPolicy.shouldBlock(uri("/api/v1/clips/discover/"), false, false));
    }

    @Test public void blocksClipsAcrossFeedsAndApiVersions() {
        for (String path : new String[]{"/api/v1/clips/discover/", "/api/v1/clips/friends/",
                "/api/v2/clips/discover/", "/api/v1/mixed_media/discover/stream/",
                "/api/v1/discover/explore_clips/"}) {
            assertTrue(path, ReelRequestPolicy.shouldBlock(uri(path), true, false, false));
            assertTrue(path, ReelRequestPolicy.shouldBlock(uri(path), false, false, true));
        }
    }

    @Test public void dmExceptionAllowsAnItemButNeverDiscovery() {
        assertFalse(ReelRequestPolicy.shouldBlock(uri("/api/v1/clips/items/?ids=123"), true, true, false));
        assertTrue(ReelRequestPolicy.shouldBlock(uri("/api/v1/clips/discover/"), true, true, false));
        assertTrue(ReelRequestPolicy.shouldBlock(uri("/api/v1/discover/explore_clips/"), true, true, false));
        assertTrue(ReelRequestPolicy.shouldBlock(uri("/api/v1/clips/items/?max_id=123"), true, true, false));
        assertTrue(ReelRequestPolicy.shouldBlock(uri("/api/v1/clips/items/?next_media_ids=123"), true, true, false));
    }

    @Test public void dailyLimitAllowsSingleItemsForDmButNotMoreFeedPages() {
        assertFalse(ReelRequestPolicy.shouldBlock(uri("/api/v1/clips/items/?ids=dm-reel"), false, true, true));
        assertFalse(ReelRequestPolicy.shouldBlock(uri("/api/v2/clips/items/?ids=dm-reel"), true, true, true));
        assertTrue(ReelRequestPolicy.shouldBlock(uri("/api/v1/clips/items/?max_id=next"), false, true, true));
        assertTrue(ReelRequestPolicy.shouldBlock(uri("/api/v1/clips/items/?next_media_ids=next"), false, true, true));
        assertTrue(ReelRequestPolicy.shouldBlock(uri("/api/v1/clips/discover/"), true, true, true));
    }

    @Test public void fullDisableWithoutDmExceptionStillBlocksDmItems() {
        assertTrue(ReelRequestPolicy.shouldBlock(uri("/api/v1/clips/items/?ids=dm-reel"), true, false, false));
        assertTrue(ReelRequestPolicy.shouldBlock(uri("/api/v1/clips/items/?ids=dm-reel"), true, false, true));
    }

    @Test public void dailyDmExceptionCanBeDisabledWithoutBlockingMessages() {
        assertTrue(ReelRequestPolicy.shouldBlock(uri("/api/v1/clips/items/?ids=dm-reel"), false, false, true));
        assertFalse(ReelRequestPolicy.shouldBlock(uri("/api/v1/clips/items/?ids=dm-reel"), false, false, false));
        assertFalse(ReelRequestPolicy.shouldBlock(uri("/api/v1/direct_v2/threads/123/items/"), false, false, true));
    }

    @Test public void leavesMessagesStoriesFeedAndUnrelatedUrlsUntouched() {
        for (String path : new String[]{"/api/v1/direct_v2/threads/123/opened/", "/api/v1/feed/reels_tray/",
                "/api/v1/feed/timeline/", "/api/v1/qp/batch_fetch/", "/api/v1/accounts/myclips/",
                "/api/v1/users/123/?ref=clips"}) {
            assertFalse(path, ReelRequestPolicy.shouldBlock(uri(path), true, false, true));
        }
    }

    @Test public void handlesMissingPathsAndQueryValuesContainingPaginationNames() {
        assertFalse(ReelRequestPolicy.shouldBlock(null, true, false, true));
        assertFalse(ReelRequestPolicy.shouldBlock(URI.create("mailto:clips"), true, false, true));
        assertFalse(ReelRequestPolicy.shouldBlock(uri("/api/v1/clips/items/?ref=max_id=123"), true, true, false));
    }
}
