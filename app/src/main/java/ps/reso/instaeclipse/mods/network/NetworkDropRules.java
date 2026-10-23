package ps.reso.instaeclipse.mods.network;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.util.function.Predicate;

import ps.reso.instaeclipse.utils.core.SafeReflect;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;

public final class NetworkDropRules {
    private NetworkDropRules() {}

    public static boolean shouldDrop(String host, String path, Predicate<String> chatAllowed) {
        boolean shouldDrop = false;
        if (FeatureFlags.disableSearchHistory) {
            shouldDrop |= path.contains("/fbsearch/register_recent_search_click/");
        }
        if (FeatureFlags.isGhostSeen) {
            shouldDrop |= path.contains("/threads/") && path.contains("/opened")
                    && !chatAllowed.test(path);
        }
        if (FeatureFlags.keepEphemeralMessages) {
            shouldDrop |= path.contains("/mark_ephemeral_item_ranges_viewed");
        }
        if (FeatureFlags.isGhostScreenshot) {
            shouldDrop |= path.endsWith("/screenshot/") || path.endsWith("/ephemeral_screenshot/");
        }
        if (FeatureFlags.isGhostViewOnce) {
            shouldDrop |= path.endsWith("/item_replayed/");
            shouldDrop |= (path.contains("/direct") && path.endsWith("/item_seen/"));
        }
        if (FeatureFlags.isGhostLive) {
            shouldDrop |= path.contains("/heartbeat_and_get_viewer_count/");
        }
        if (FeatureFlags.removeMetaAI) {
            shouldDrop |= path.contains("/ig_meta_ai_side_chat_send_contextual_query/")
                    || path.contains("/ig_meta_ai_side_chat_new_session/")
                    || path.contains("/create_ig_meta_ai_side_chat/")
                    || path.contains("/genai/response")
                    || (host != null && host.contains("aistudio.instagram.com"));
        }
        if (FeatureFlags.disableStories) {
            shouldDrop |= path.contains("/feed/reels_tray/")
                    || path.contains("feed/get_latest_reel_media/")
                    || path.contains("direct_v2/pending_inbox/?visual_message")
                    || path.contains("stories/hallpass/")
                    || path.contains("/api/v1/feed/reels_media_stream/");
        }
        if (FeatureFlags.disableFeed) {
            shouldDrop |= path.endsWith("/feed/timeline/");
        }
        if (FeatureFlags.disableExplore) {
            shouldDrop |= isExploreRequest(host, path);
        }
        if (FeatureFlags.hideDmSuggestions) {
            shouldDrop |= path.contains("/dynamic_onboarding/get_direct_empty_state/");
        }
        if (FeatureFlags.disableComments) {
            shouldDrop |= path.contains("/api/v1/media/") && path.contains("comments/");
        }
        if (FeatureFlags.isAdBlockEnabled) {
            shouldDrop |= path.contains("profile_ads/get_profile_ads/")
                    || path.contains("/async_ads/")
                    || path.contains("/feed/injected_reels_media/")
                    || path.equals("/api/v1/ads/graphql/");
        }
        if (FeatureFlags.isAnalyticsBlocked) {
            shouldDrop |= (host != null && (host.contains("graph.instagram.com")
                    || host.contains("graph.facebook.com")))
                    || path.contains("/logging_client_events");
        }
        if (FeatureFlags.spoofLastSeen) {
            shouldDrop |= isLastSeenRequest(path);
        }
        if (FeatureFlags.disableDiscoverPeople) {
            shouldDrop |= path.contains("/discover/ayml/");
            shouldDrop |= path.contains("discover/chaining/");
        }
        return shouldDrop;
    }

    static boolean isExploreRequest(String host, String path) {
        return path.contains("/discover/topical_explore")
                || path.contains("/discover/topical_explore_stream")
                || (host != null && host.contains("i.instagram.com") && path.contains("/fbsearch/top_serp"));
    }

    static boolean isLastSeenRequest(String path) {
        return path.contains("/push/setForegroundState/")
                || path.contains("/accounts/update_active_status")
                || path.contains("/notes/create_note")
                || path.contains("/accounts/set_presence_disabled")
                || path.contains("/update_active_status")
                || path.contains("/banyan/banyan/")
                || path.endsWith("/last_active/")
                || path.contains("/presence/");
    }

    public static void neutralize(Object requestObj, URI original, URI fake, String fakeUrl) {
        String orig = original.toString();
        String origPath = original.getPath();
        for (Field f : SafeReflect.fields(requestObj.getClass())) {
            try {
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Object v = f.get(requestObj);
                if (v instanceof URI) {
                    f.set(requestObj, fake);
                } else if (v instanceof String s && s.startsWith("http")
                        && (s.equals(orig) || (origPath != null && !origPath.isEmpty() && s.contains(origPath)))) {
                    f.set(requestObj, fakeUrl);
                }
            } catch (Throwable ignored) {}
        }
    }
}
