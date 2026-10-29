package ps.reso.instaeclipse.mods.network;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;
import ps.reso.instaeclipse.xposedcompat.XC_LoadPackage;
import ps.reso.instaeclipse.mods.ghost.ManualStorySeenHook;
import ps.reso.instaeclipse.mods.misc.FollowStatusHook;
import ps.reso.instaeclipse.mods.reels.ReelBlockPolicy;
import ps.reso.instaeclipse.mods.reels.ReelRequestPolicy;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public class IGNetworkInterceptor {

    private static final URI FAKE_URI = URI.create("https://127.0.0.1/404");
    private static final String FAKE_URL = "https://127.0.0.1/404";

    static volatile Class<?>[] startRequestParams;
    static volatile XC_MethodHook dropHook;

    public void installTigon(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            ClassLoader classLoader = lpparam.classLoader;

            // Locate the TigonServiceLayer class dynamically
            Class<?> tigonClass = classLoader.loadClass("com.instagram.api.tigon.TigonServiceLayer");
            Method[] methods = ps.reso.instaeclipse.utils.core.SafeReflect.methods(tigonClass);

            Class<?> random_param_1 = null;
            Class<?> random_param_2 = null;
            Class<?> random_param_3 = null;
            Field uriField = null;

            // Analyze methods in TigonServiceLayer
            for (Method method : methods) {
                if (method.getName().equals("startRequest") && method.getParameterCount() == 3) {
                    Class<?>[] paramTypes = method.getParameterTypes();
                    random_param_1 = paramTypes[0];
                    random_param_2 = paramTypes[1];
                    random_param_3 = paramTypes[2];
                    break;
                }
            }

            // Dynamically identify the URI field in the request object
            if (random_param_1 != null) {
                for (Field field : ps.reso.instaeclipse.utils.core.SafeReflect.fields(random_param_1)) {
                    if (field.getType().equals(URI.class)
                            && !java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                        field.setAccessible(true);
                        uriField = field;
                        break;
                    }
                }
            }

            // If classes and fields are resolved, hook the method
            if (random_param_1 != null && random_param_2 != null && random_param_3 != null && uriField != null) {
                Field finalUriField = uriField;
                XC_MethodHook dropHook = new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                              try {
                                Object requestObj = param.args[0];
                                URI uri = (URI) finalUriField.get(requestObj);

                                if (uri != null && uri.getPath() != null) {
                                    final String host = uri.getHost(); // null for opaque/relative URIs — guard before use
                                    boolean shouldDrop = NetworkDropRules.shouldDrop(host, uri.getPath(),
                                            ps.reso.instaeclipse.utils.ghost.ReadReceiptWhitelist::pathMentionsAllowed);

                                    // Version-agnostic: getPath() excludes the ?reel=... query, so it
                                    // reads /api/vN/media/seen/ on both old (v2) and new (447: v1) builds.
                                    // A manual receipt (marked) is authorized in the Tigon pass only: the
                                    // approval is one-shot and needs the Tigon layer's session, and the
                                    // chain-entry pass runs first. Unmarked receipts are dropped in both
                                    // passes, as upstream does, in case one never reaches Tigon.
                                    if (uri.getPath().contains("/media/seen/")) {
                                        boolean marked = ManualStorySeenHook.hasManualMarker(uri);
                                        if (param.method.getDeclaringClass() == tigonClass) {
                                            boolean manualSeen = marked && ManualStorySeenHook
                                                    .allowManualRequest(requestObj, uri, param.thisObject);
                                            shouldDrop |= !manualSeen && (FeatureFlags.isGhostStory || marked);
                                        } else if (!marked) {
                                            shouldDrop |= FeatureFlags.isGhostStory;
                                        }
                                    }
                                    // Disable Reels, quiet hours and the daily cap share one policy. Its
                                    // DM exception only lets single reel items through and never returns
                                    // early, so the privacy drops above and below still apply.
                                    ReelBlockPolicy.Reason reelReason = ReelBlockPolicy.requestReason();
                                    shouldDrop |= ReelRequestPolicy.shouldBlock(uri,
                                            reelReason != ReelBlockPolicy.Reason.NONE,
                                            ReelBlockPolicy.requestAllowsDm(reelReason));
                                    // NOTE: Disable Repost is handled at the UI/action level in
                                    // mods.ui.DisableRepostHook — NOT here. Dropping the repost network
                                    // request is ineffective because IG applies the repost optimistically
                                    // client-side, so the repost completes even when the request is dropped.

                                    if (shouldDrop) {
                                        NetworkDropRules.neutralize(requestObj, uri, FAKE_URI, FAKE_URL);
                                    }

                                    // Follower toast / follow label: only notes which profile's
                                    // /friendships/show/ was requested; FollowStatusHook reads IG's parsed
                                    // answer through hooks it installs at startup. Never hook a request's
                                    // callback classes from here: on IG 451 they include the app-wide
                                    // RequestCallbackDispatcher (X.03ta), and hooking it mid-flight
                                    // corrupted the heap on LSPatch 1.2.
                                    if (FollowStatusHook.wanted()) FollowStatusHook.handleRequest(uri);
                                }
                              } catch (Throwable ignored) {
                                  // A malformed/opaque request must never crash IG's network dispatch.
                              }
                            }
                        };
                XposedHelpers.findAndHookMethod("com.instagram.api.tigon.TigonServiceLayer", classLoader, "startRequest",
                        random_param_1, random_param_2, random_param_3, dropHook);
                IGNetworkInterceptor.dropHook = dropHook;
                startRequestParams = new Class<?>[]{random_param_1, random_param_2, random_param_3};
                // These act on every matching request, so they are in place once the hook is.
                for (String key : new String[]{"BlockAnalytics", "DisableStoriesSection", "DisableFeedSection",
                        "DisableReelsSection", "DisableExploreSection", "DisableCommentsSection", "HideDmSuggestions",
                        "GhostStories", "GhostLive", "DisableDiscoverPeople"})
                    FeatureStatusTracker.setHooked(key);
            } else {
                ModuleLog.error("(InstaEclipse | Interceptor): Network filters (analytics blocking, disabled sections, parts of ghost mode) will not work: Instagram's request classes were not found in this version");
            }

        } catch (Exception e) {
            ModuleLog.error("(InstaEclipse | Interceptor): Network filters (analytics blocking, disabled sections, parts of ghost mode) will not work", e);
        }
    }
}
