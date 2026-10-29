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

            Class<?> tigonClass = classLoader.loadClass("com.instagram.api.tigon.TigonServiceLayer");
            Method[] methods = ps.reso.instaeclipse.utils.core.SafeReflect.methods(tigonClass);

            Class<?> random_param_1 = null;
            Class<?> random_param_2 = null;
            Class<?> random_param_3 = null;
            Field uriField = null;

            for (Method method : methods) {
                if (method.getName().equals("startRequest") && method.getParameterCount() == 3) {
                    Class<?>[] paramTypes = method.getParameterTypes();
                    random_param_1 = paramTypes[0];
                    random_param_2 = paramTypes[1];
                    random_param_3 = paramTypes[2];
                    break;
                }
            }

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

            if (random_param_1 != null && random_param_2 != null && random_param_3 != null && uriField != null) {
                Field finalUriField = uriField;
                XC_MethodHook dropHook = new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                              try {
                                Object requestObj = param.args[0];
                                URI uri = (URI) finalUriField.get(requestObj);

                                if (uri != null && uri.getPath() != null) {
                                    final String host = uri.getHost();
                                    boolean shouldDrop = NetworkDropRules.shouldDrop(host, uri.getPath(),
                                            ps.reso.instaeclipse.utils.ghost.ReadReceiptWhitelist::pathMentionsAllowed);

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
                                    ReelBlockPolicy.Reason reelReason = ReelBlockPolicy.requestReason();
                                    shouldDrop |= ReelRequestPolicy.shouldBlock(uri,
                                            reelReason != ReelBlockPolicy.Reason.NONE,
                                            ReelBlockPolicy.requestAllowsDm(reelReason));

                                    if (shouldDrop) {
                                        NetworkDropRules.neutralize(requestObj, uri, FAKE_URI, FAKE_URL);
                                    }

                                    if (FollowStatusHook.wanted()) FollowStatusHook.handleRequest(uri);
                                }
                              } catch (Throwable ignored) {}
                            }
                        };
                XposedHelpers.findAndHookMethod("com.instagram.api.tigon.TigonServiceLayer", classLoader, "startRequest",
                        random_param_1, random_param_2, random_param_3, dropHook);
                IGNetworkInterceptor.dropHook = dropHook;
                startRequestParams = new Class<?>[]{random_param_1, random_param_2, random_param_3};
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
