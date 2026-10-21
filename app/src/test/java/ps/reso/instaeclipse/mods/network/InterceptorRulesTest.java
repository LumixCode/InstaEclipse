package ps.reso.instaeclipse.mods.network;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class InterceptorRulesTest {
    private static final String API = "i.instagram.com";

    @Test public void exploreBlocksTheSearchTopResultsStreamIg451Calls() {
        assertTrue(IGNetworkInterceptor.isExploreRequest(API, "/api/v1/fbsearch/top_serp_stream/"));
        assertTrue(IGNetworkInterceptor.isExploreRequest("b.i.instagram.com", "/api/v1/fbsearch/top_serp_stream/"));
        assertTrue(IGNetworkInterceptor.isExploreRequest(API, "/api/v1/fbsearch/top_serp/"));
    }

    @Test public void exploreStillBlocksTheExploreGrid() {
        assertTrue(IGNetworkInterceptor.isExploreRequest(API, "/api/v1/discover/topical_explore/"));
        assertTrue(IGNetworkInterceptor.isExploreRequest(null, "/api/v1/discover/topical_explore_stream/"));
    }

    @Test public void exploreLeavesOtherSearchEndpointsAndHostsAlone() {
        assertFalse(IGNetworkInterceptor.isExploreRequest(API, "/api/v1/fbsearch/typeahead_stream/"));
        assertFalse(IGNetworkInterceptor.isExploreRequest(API, "/api/v1/fbsearch/account_serp/"));
        assertFalse(IGNetworkInterceptor.isExploreRequest(API, "/api/v1/fbsearch/recent_searches/"));
        assertFalse(IGNetworkInterceptor.isExploreRequest("graph.instagram.com", "/api/v1/fbsearch/top_serp_stream/"));
        assertFalse(IGNetworkInterceptor.isExploreRequest(null, "/api/v1/fbsearch/top_serp_stream/"));
    }

    @Test public void lastSeenRulesKeepTheirLegacyEndpoints() {
        assertTrue(IGNetworkInterceptor.isLastSeenRequest("/api/v1/push/setForegroundState/"));
        assertTrue(IGNetworkInterceptor.isLastSeenRequest("/api/v1/accounts/update_active_status/"));
        assertTrue(IGNetworkInterceptor.isLastSeenRequest("/api/v1/users/123/last_active/"));
    }

    @Test public void lastSeenRulesDoNotTouchPresenceReadsOrNoteActions() {
        // IG 451 presence reads (others' status) and the repost note endpoint report nothing.
        assertFalse(IGNetworkInterceptor.isLastSeenRequest("/api/v1/direct_v2/get_presence/"));
        assertFalse(IGNetworkInterceptor.isLastSeenRequest("/api/v1/direct_v2/get_presence_active_now/"));
        assertFalse(IGNetworkInterceptor.isLastSeenRequest("/api/v1/direct_v2/fetch_and_subscribe_presence/"));
        assertFalse(IGNetworkInterceptor.isLastSeenRequest("/api/v1/media/create_note/v2/"));
    }
}
