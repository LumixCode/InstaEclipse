package ps.reso.instaeclipse.mods.feed;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import java.lang.reflect.Method;
import java.net.URI;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.Found;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;

public final class PaidPartnershipFilter {

    private static final String TAG = "(IE|ReelsFilter) ";
    private static final String MEDIA = "com.instagram.feed.media.Media";
    private static final String IDS_KEY = "clips_media_ids";
    private static final String IDS_PARAM = IDS_KEY + "=";
    private static final int MAX_OPENED = 200;
    private static final int MAX_BUILDERS = 16;
    private static final int MIN_ID_DIGITS = 6;

    private static final Map<String, Boolean> OPENED = new LinkedHashMap<String, Boolean>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > MAX_OPENED;
        }
    };

    private static volatile Method paidGetter;
    private static volatile Method sponsorsGetter;
    private static volatile Method injectedGetter;
    private static volatile Method idGetter;
    private static volatile boolean ready;

    private PaidPartnershipFilter() {}

    static boolean install(ClassLoader loader) {
        paidGetter = getter(loader, "is_paid_partnership", "java.lang.Boolean");
        sponsorsGetter = getter(loader, "sponsor_tags", "java.util.List");
        injectedGetter = getter(loader, "injected", null);
        idGetter = idGetter(loader);
        if (paidGetter == null && sponsorsGetter == null && injectedGetter == null) {
            ModuleLog.error(TAG + "Hiding paid partnerships will not work: the paid partnership label was not found in this Instagram version");
            return false;
        }
        if (idGetter == null) {
            ModuleLog.error(TAG + "Hiding paid partnerships will not work: reels could not be told apart in this Instagram version");
            return false;
        }
        int builders = hookOpenedRequests(loader);
        ModuleLog.detail(TAG + "requests for opened reels hooked: " + builders);
        if (builders == 0) {
            ModuleLog.error(TAG + "Hiding paid partnerships will not work: the request for a reel you open was not found in this Instagram version, so those reels could not be kept");
            return false;
        }
        if (paidGetter == null || sponsorsGetter == null || injectedGetter == null) {
            ModuleLog.warn(TAG + "Some paid partnership or sponsored reels may still show: part of their label was not found in this Instagram version");
        }
        ready = true;
        return true;
    }

    static boolean shouldHide(Object media) {
        if (!FeatureFlags.hidePaidPartnerships || !ready || media == null) return false;
        try {
            return isPaid(media) && !isOpened(media);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void noteRequest(URI uri) {
        if (!FeatureFlags.hidePaidPartnerships || uri == null) return;
        try {
            String query = uri.getQuery();
            int at = query == null ? -1 : query.indexOf(IDS_PARAM);
            if (at < 0) return;
            int end = query.indexOf('&', at);
            remember(query.substring(at + IDS_PARAM.length(), end < 0 ? query.length() : end));
        } catch (Throwable ignored) {
        }
    }

    private static boolean isPaid(Object media) {
        if (Boolean.TRUE.equals(call(paidGetter, media))) return true;
        if (call(sponsorsGetter, media) instanceof Collection<?> sponsors && !sponsors.isEmpty()) return true;
        return call(injectedGetter, media) != null;
    }

    private static boolean isOpened(Object media) throws ReflectiveOperationException {
        if (!(idGetter.invoke(media) instanceof String id) || id.isEmpty()) return true;
        int cut = id.indexOf('_');
        String pk = cut > 0 ? id.substring(0, cut) : id;
        synchronized (OPENED) {
            return OPENED.get(pk) != null;
        }
    }

    private static Object call(Method getter, Object media) {
        if (getter == null) return null;
        try {
            return getter.invoke(media);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method getter(ClassLoader loader, String jsonKey, String returnType) {
        try {
            MethodMatcher matcher = MethodMatcher.create().declaredClass(MEDIA)
                    .usingEqStrings(List.of(jsonKey)).paramCount(0);
            if (returnType != null) matcher.returnType(returnType);
            Method hit = null;
            int matches = 0;
            for (Found found : DexKitQueryCache.methods(FindMethod.create().matcher(matcher))) {
                if (!found.isMethod()) continue;
                Method method = found.getMethodInstance(loader);
                if (method.getReturnType().isPrimitive()) continue;
                hit = method;
                matches++;
            }
            if (matches == 1) {
                hit.setAccessible(true);
                ModuleLog.detail(TAG + jsonKey + " getter Media." + hit.getName());
                return hit;
            }
            ModuleLog.detail(TAG + jsonKey + " getter " + (matches == 0 ? "not found" : "ambiguous (" + matches + ")"));
        } catch (Throwable t) {
            ModuleLog.detail(TAG + jsonKey + " getter lookup failed: " + t);
        }
        return null;
    }

    private static Method idGetter(ClassLoader loader) {
        try {
            Method method = Class.forName(MEDIA, false, loader).getMethod("getId");
            if (method.getReturnType() != String.class) return null;
            method.setAccessible(true);
            return method;
        } catch (Throwable t) {
            ModuleLog.detail(TAG + "Media.getId not found: " + t);
            return null;
        }
    }

    private static int hookOpenedRequests(ClassLoader loader) {
        XC_MethodHook record = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!FeatureFlags.hidePaidPartnerships || param.args == null) return;
                try {
                    for (Object arg : param.args) note(arg);
                } catch (Throwable ignored) {
                }
            }
        };
        int hooked = 0;
        try {
            List<Found> builders = DexKitQueryCache.hookable(DexKitQueryCache.methods(FindMethod.create().matcher(
                    MethodMatcher.create().usingEqStrings(List.of(IDS_KEY)))), loader);
            for (Found found : builders) {
                if (hooked >= MAX_BUILDERS) break;
                if (!found.isMethod()) continue;
                try {
                    Method method = found.getMethodInstance(loader);
                    if (!takesIds(method)) continue;
                    XposedBridge.hookMethod(method, record);
                    hooked++;
                } catch (Throwable t) {
                    ModuleLog.detail(TAG + "skipped " + found + ": " + t);
                }
            }
        } catch (Throwable t) {
            ModuleLog.detail(TAG + IDS_KEY + " lookup failed: " + t);
        }
        return hooked;
    }

    private static boolean takesIds(Method method) {
        for (Class<?> type : method.getParameterTypes()) {
            if (type == String.class || Collection.class.isAssignableFrom(type)) return true;
        }
        return false;
    }

    private static void note(Object arg) {
        if (arg instanceof String text) {
            remember(text);
        } else if (arg instanceof Collection<?> values) {
            for (Object value : values) {
                if (value instanceof String text) remember(text);
            }
        }
    }

    static void remember(String text) {
        int i = 0;
        int length = text.length();
        while (i < length) {
            if (!isDigit(text.charAt(i))) {
                i++;
                continue;
            }
            int start = i;
            while (i < length && isDigit(text.charAt(i))) i++;
            if (i - start < MIN_ID_DIGITS) continue;
            synchronized (OPENED) {
                OPENED.put(text.substring(start, i), Boolean.TRUE);
            }
        }
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
