package ps.reso.instaeclipse.mods.ghost;

import org.junit.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class ManualStorySeenPolicyTest {
    public static final class Group {
        final List<String> stories = new ArrayList<>();
        Group(String id) { stories.add(id); }
    }
    public static final class Pending {
        final Map<String, Group> seen = new HashMap<>();
        final List<String> forced = new ArrayList<>();
        Object usedSession;
        public Pending() { }
        public void populateCurrent(Object session, Object graph, String reelId) {
            usedSession = session;
            seen.put(reelId, new Group((String) graph));
        }
        public void populateCurrentBatch(Object session, Object graph, String reelId) {
            populateCurrent(session, graph, reelId);
            seen.get(reelId).stories.add("another-unseen-story");
        }
    }
    public static void populate(Object graph, Object session, Pending pending, String reelId) {
        pending.seen.put(reelId, new Group((String) graph));
    }
    public static void populateBatch(Object graph, Object session, Pending pending, String reelId) {
        populate(graph, session, pending, reelId);
        pending.seen.get(reelId).stories.add("another-unseen-story");
    }
    public static final class Request {
        static final URI malformed = URI.create("https://malformed.url.invalid/");
        String url;
        URI uri;
        URI secondUri;
        Request(URI uri) { this.uri = uri; secondUri = uri; url = uri.toString(); }
    }

    private static URI request(String token) {
        return URI.create("https://i.instagram.com/api/v2/media/seen/?reel=1&live_vod=0&"
                + ManualStorySeenPolicy.PARAMETER + "=" + token);
    }

    @Test public void permitsOnlyExplicitRequestAndRemovesPrivateMarker() {
        ManualStorySeenPolicy policy = new ManualStorySeenPolicy();
        Object session = new Object();
        URI marked = request(policy.authorize(session, 100));
        assertNull(policy.consume(URI.create("https://i.instagram.com/api/v2/media/seen/?reel=1"), session, 100));
        assertEquals("https://i.instagram.com/api/v2/media/seen/?reel=1&live_vod=0",
                policy.consume(marked, session, 101).toString());
        assertNull(policy.consume(marked, session, 102));
    }

    @Test public void switchingAccountsCannotUseAnotherSessionsAuthorization() {
        ManualStorySeenPolicy policy = new ManualStorySeenPolicy();
        Object first = new Object(), second = new Object();
        URI marked = request(policy.authorize(first, 100));
        assertNull(policy.consume(marked, second, 101));
        assertNotNull(policy.consume(marked, first, 102));
    }

    @Test public void expiryAndExplicitCancellationKeepGhostBlocking() {
        ManualStorySeenPolicy policy = new ManualStorySeenPolicy();
        Object session = new Object();
        URI expired = request(policy.authorize(session, 100));
        assertNull(policy.consume(expired, session, 60_100));
        String cancelled = policy.authorize(session, 60_100);
        policy.revoke(cancelled);
        assertNull(policy.consume(request(cancelled), session, 60_101));
    }

    @Test public void wrongEndpointOrHostCannotSpendApproval() {
        ManualStorySeenPolicy policy = new ManualStorySeenPolicy();
        Object session = new Object();
        URI marked = request(policy.authorize(session, 100));
        assertNull(policy.consume(URI.create(marked.toString().replace("i.instagram.com", "example.com")), session, 101));
        assertNull(policy.consume(URI.create(marked.toString().replace("media/seen/", "direct/seen/")), session, 101));
        assertNotNull(policy.consume(marked, session, 101));
    }

    @Test public void duplicateMarkersAreRejectedInsteadOfAuthorizingAmbiguousRequest() {
        ManualStorySeenPolicy policy = new ManualStorySeenPolicy();
        Object session = new Object();
        String token = policy.authorize(session, 100);
        URI duplicate = URI.create(request(token) + "&" + ManualStorySeenPolicy.PARAMETER + "=" + token);
        assertNull(policy.consume(duplicate, session, 101));
    }

    @Test public void authorizationQueueIsBoundedWithoutOpeningBroadWindow() {
        ManualStorySeenPolicy policy = new ManualStorySeenPolicy();
        Object session = new Object();
        URI first = request(policy.authorize(session, 100));
        URI latest = null;
        for (int i = 0; i < 8; i++) latest = request(policy.authorize(session, 100));
        assertNull(policy.consume(first, session, 101));
        assertNotNull(policy.consume(latest, session, 101));
    }

    @Test public void freshReceiptContainsOnlySelectedStoryAndDoesNotReplayQueuedStories() throws Exception {
        Pending queued = new Pending();
        queued.seen.put("creator", new Group("previous-unseen-story"));
        Pending selected = (Pending) ManualStorySeenPolicy.createSingleItem(Pending.class.getConstructor(),
                getClass().getMethod("populate", Object.class, Object.class, Pending.class, String.class),
                "selected-story", new Object(), "creator");
        assertNotSame(queued, selected);
        assertEquals(List.of("selected-story"), selected.seen.get("creator").stories);
        assertEquals(List.of("previous-unseen-story"), queued.seen.get("creator").stories);
        assertTrue(selected.forced.isEmpty());
    }

    @Test public void unexpectedMultiStoryPopulationIsRejectedBeforeDispatch() throws Exception {
        assertThrows(IllegalStateException.class, () -> ManualStorySeenPolicy.createSingleItem(
                Pending.class.getConstructor(),
                getClass().getMethod("populateBatch", Object.class, Object.class, Pending.class, String.class),
                "selected-story", new Object(), "creator"));
    }

    @Test public void stripMarkerRewritesStringAndUriCopies() throws Exception {
        URI marked = request("one-shot-token");
        URI clean = URI.create("https://i.instagram.com/api/v2/media/seen/?reel=1&live_vod=0");
        Request request = new Request(marked);
        ManualStorySeenPolicy.stripMarker(request, marked, clean);
        assertEquals(clean, request.uri);
        assertEquals(clean, request.secondUri);
        assertEquals(clean.toString(), request.url);
        assertEquals(URI.create("https://malformed.url.invalid/"), Request.malformed);
    }

    @Test public void stripFailureDoesNotPermitMissingOrConflictingUrlCopies() {
        URI marked = request("one-shot-token");
        URI clean = URI.create("https://i.instagram.com/api/v2/media/seen/");
        assertThrows(IllegalStateException.class, () -> ManualStorySeenPolicy.stripMarker(new Object(), marked, clean));
        Request conflict = new Request(marked);
        conflict.url += "&different=1";
        assertThrows(IllegalStateException.class, () -> ManualStorySeenPolicy.stripMarker(conflict, marked, clean));
    }

    @Test public void malformedMarkersStillRequireBlockingWhenGhostIsDisabled() {
        String base = "https://i.instagram.com/api/v2/media/seen/?";
        assertTrue(ManualStorySeenPolicy.hasMarker(URI.create(base + ManualStorySeenPolicy.PARAMETER)));
        assertTrue(ManualStorySeenPolicy.hasMarker(URI.create(base + ManualStorySeenPolicy.PARAMETER + "=")));
        assertTrue(ManualStorySeenPolicy.hasMarker(URI.create(base + "reel=1&" + ManualStorySeenPolicy.PARAMETER + "=invalid=value")));
        assertFalse(ManualStorySeenPolicy.hasMarker(URI.create(base + "not_" + ManualStorySeenPolicy.PARAMETER + "=value")));
        assertFalse(ManualStorySeenPolicy.hasMarker(URI.create(base + "other=" + ManualStorySeenPolicy.PARAMETER)));
        assertFalse(ManualStorySeenPolicy.hasMarker(URI.create("https://i.instagram.com/#" + ManualStorySeenPolicy.PARAMETER)));
    }

    @Test public void appendingManualActionPreservesNativeStringArrayReturnType() {
        String[] nativeMenu = {"Report", "Mute", null};
        CharSequence[] extended = ManualStorySeenPolicy.appendAction(nativeMenu, "Mark this story as seen");
        assertTrue(extended instanceof String[]);
        assertArrayEquals(new String[]{"Report", "Mute", null, "Mark this story as seen"}, extended);
        assertEquals(3, nativeMenu.length);
        assertSame(extended, ManualStorySeenPolicy.appendAction(extended, "Mark this story as seen"));
    }

    @Test public void appendingActionAcceptsNarrowCharSequenceArrayWithoutLosingNativeEntries() {
        StringBuilder report = new StringBuilder("Report");
        CharSequence[] extended = ManualStorySeenPolicy.appendAction(new StringBuilder[]{report}, "Mark this story as seen");
        assertSame(report, extended[0]);
        assertEquals("Mark this story as seen", extended[1]);
    }

    @Test public void currentInstancePopulationUsesFreshItemAndOriginalAccount() throws Exception {
        Object session = new Object();
        Pending selected = (Pending) ManualStorySeenPolicy.createSingleItem(Pending.class.getConstructor(),
                Pending.class.getMethod("populateCurrent", Object.class, Object.class, String.class),
                "selected-story", session, "creator");
        assertSame(session, selected.usedSession);
        assertEquals(List.of("selected-story"), selected.seen.get("creator").stories);
        assertTrue(selected.forced.isEmpty());
    }

    @Test public void currentInstancePopulationStillRejectsAdditionalStories() {
        assertThrows(IllegalStateException.class, () -> ManualStorySeenPolicy.createSingleItem(
                Pending.class.getConstructor(),
                Pending.class.getMethod("populateCurrentBatch", Object.class, Object.class, String.class),
                "selected-story", new Object(), "creator"));
    }
}
