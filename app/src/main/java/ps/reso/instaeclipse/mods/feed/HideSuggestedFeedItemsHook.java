package ps.reso.instaeclipse.mods.feed;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.UsingType;
import org.luckypray.dexkit.query.matchers.FieldMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.HookTargetGuard;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;

public class HideSuggestedFeedItemsHook {

    // v2: the old string-only lookup could cache the wrong class on IG 451 (order-dependent pick).
    private static final String CACHE_KEY_PARSER = "FeedItemParserClass_v2";
    private static final String MEDIA_CLASS = "com.instagram.feed.media.Media";
    private static final String CACHE_KEY_HAS_LIKED = "MediaHasLikedGetter";

    // "has_liked" getter: Media.()Boolean on IG 447+, LiveTreeMediaDict.()Boolean on <= 446 (436)
    // — reached through the Media field of that type. Resolved once at install (#249).
    private static volatile Method hasLikedGetter;

    public void install(DexKitBridge bridge, ClassLoader classLoader) {
        XC_MethodHook filterHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (!FeatureFlags.hideSuggestionsInFeed && !FeatureFlags.hideThreadsSuggestions
                        && !FeatureFlags.hideLikedPosts) return;

                Object result = param.getResult();
                if (result == null) return;

                // The parsed FeedItem is a large union type — exactly one of its many
                // optional fields is populated per server-sent unit. A real post always
                // has its Media field set; every suggestion/injection type (clips netego,
                // suggested users, Threads cross-promo units, ...) leaves it null.
                boolean hasMedia = false;
                boolean isThreadsUnit = false;
                Object media = null;
                for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(result.getClass())) {
                    String typeName = f.getType().getSimpleName();
                    if (typeName.equals("Media")) {
                        try {
                            f.setAccessible(true);
                            media = f.get(result);
                            if (media != null) { hasMedia = true; break; }
                        } catch (Throwable ignored) {}
                    } else if (typeName.contains("Threads") || typeName.startsWith("TextApp") || typeName.startsWith("XDTTextApp")) {
                        // Threads-in-feed cross-promo unit types (ThreadsInFeedUnit...,
                        // TextApp...UnitDictImpl, XDTTextApp...UnitDictImpl) — matched by
                        // simple name since these are stable, unobfuscated GraphQL-generated
                        // class names, unlike the surrounding obfuscated field names.
                        try {
                            f.setAccessible(true);
                            if (f.get(result) != null) isThreadsUnit = true;
                        } catch (Throwable ignored) {}
                    }
                }

                if (hasMedia) {
                    // Real post: only hidden when "Hide liked posts" is on and you already liked it.
                    // Applied at parse time, so a post liked now disappears on the next refresh.
                    if (FeatureFlags.hideLikedPosts && isLiked(media)) param.setResult(null);
                    return;
                }
                if (!FeatureFlags.hideSuggestionsInFeed && !FeatureFlags.hideThreadsSuggestions) return;

                boolean shouldHide = isThreadsUnit ? FeatureFlags.hideThreadsSuggestions
                                                    : FeatureFlags.hideSuggestionsInFeed;
                if (!shouldHide) return;

                param.setResult(null);
            }
        };

        resolveHasLikedGetter(bridge, classLoader);

        if (DexKitCache.isCacheValid()) {
            String cached = DexKitCache.loadString(CACHE_KEY_PARSER);
            if (cached != null && isFeedItemParser(cached, classLoader)) {
                try {
                    hookBridgeMethod(cached, classLoader, filterHook);
                    markHookedForEnabledFlags();
                    return;
                } catch (Throwable t) {
                    ModuleLog.line("(InstaEclipse | HideSuggested): ⚠️ Cache hook failed: " + t.getMessage());
                }
            }
        }

        try {
            String targetClass = findFeedItemParser(bridge, classLoader);
            if (targetClass == null) return;
            hookBridgeMethod(targetClass, classLoader, filterHook);
            DexKitCache.saveString(CACHE_KEY_PARSER, targetClass);
            markHookedForEnabledFlags();
            ModuleLog.line("(InstaEclipse | HideSuggested): ✅ Hooked: " + targetClass);

        } catch (Throwable t) {
            ModuleLog.line("(InstaEclipse | HideSuggested): ❌ Exception: " + t.getMessage());
        }
    }

    /**
     * The FeedItem parser: the class whose unsafeParseFromJson reads the "clips_netego" unit and
     * writes the parsed post into a Media field of the FeedItem it builds (IG 451: X.07au building
     * X.07at; IG 446: X.08qc building X.08qb). Strings alone are ambiguous on 451: "Unknown FeedItem
     * Type" moved into the FeedItem itself, and clips_netego + media_or_ad also hits a string table
     * and an enum. So the Media write is required, and exactly one class must match.
     */
    private static String findFeedItemParser(DexKitBridge bridge, ClassLoader classLoader) {
        Set<String> classes = new LinkedHashSet<>();
        for (MethodData md : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                .name("unsafeParseFromJson")
                .usingStrings("clips_netego")
                .addUsingField(FieldMatcher.create().type(MEDIA_CLASS), UsingType.Write)))) {
            classes.add(md.getClassName());
        }
        if (classes.size() == 1) return classes.iterator().next();
        if (classes.isEmpty()) {
            // The strict query needs the unsafeParseFromJson name; on a build that renamed it,
            // fall back to the string queries, every match checked for the parser's shape.
            String fallback = findCheckedFeedItemParser(bridge, classLoader);
            if (fallback != null) {
                ModuleLog.line("(InstaEclipse | HideSuggested): strict FeedItem parser query found nothing, "
                        + "using the shape-checked fallback: " + fallback);
                return fallback;
            }
        }
        ModuleLog.line("(InstaEclipse | HideSuggested): ❌ FeedItem parser "
                + (classes.isEmpty() ? "not found." : "ambiguous, not hooked: " + classes));
        return null;
    }

    /**
     * Fallback lookup by the parser's strings. Other classes use the same strings (on 451 a large
     * string table, X.0000, matches first), so every match must pass {@link #isFeedItemParser}.
     */
    private static String findCheckedFeedItemParser(DexKitBridge bridge, ClassLoader classLoader) {
        String[][] queries = {
                {"clips_netego", "suggested_users", "Unknown FeedItem Type"},
                {"clips_netego", "media_or_ad"},
                {"clips_netego", "stories_netego", "bloks_netego"}};
        for (String[] strings : queries) {
            for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(
                    MethodMatcher.create().usingStrings(strings)))) {
                if (isFeedItemParser(md.getClassName(), classLoader)) return md.getClassName();
            }
        }
        return null;
    }

    /**
     * The FeedItem JSON parser: has bridge parse methods and a static serializer
     * (writer, FeedItem) whose FeedItem declares a Media field.
     */
    private static boolean isFeedItemParser(String className, ClassLoader classLoader) {
        try {
            Class<?> c = Class.forName(className, false, classLoader);
            boolean bridges = false, serializer = false;
            for (Method m : ps.reso.instaeclipse.utils.core.SafeReflect.methods(c)) {
                if (m.isBridge()) bridges = true;
                if (java.lang.reflect.Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 2) {
                    for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(m.getParameterTypes()[1]))
                        if (f.getType().getName().equals("com.instagram.feed.media.Media")) { serializer = true; break; }
                }
            }
            return bridges && serializer;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void resolveHasLikedGetter(DexKitBridge bridge, ClassLoader classLoader) {
        try {
            if (DexKitCache.isCacheValid()) {
                Method cached = DexKitCache.loadMethod(CACHE_KEY_HAS_LIKED, classLoader);
                if (cached != null) { hasLikedGetter = cached; return; }
            }
            for (String owner : new String[]{"com.instagram.feed.media.Media",
                    "com.instagram.feed.media.LiveTreeMediaDict"}) {
                List<MethodData> found;
                try {
                    found = DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                            .declaredClass(owner)
                            .usingEqStrings(java.util.List.of("has_liked"))
                            .returnType("java.lang.Boolean")
                            .paramCount(0)));
                } catch (Throwable t) { continue; }
                if (found.size() != 1) continue;
                Method m = found.get(0).getMethodInstance(classLoader);
                m.setAccessible(true);
                hasLikedGetter = m;
                DexKitCache.saveMethod(CACHE_KEY_HAS_LIKED, m);
                ModuleLog.line("(InstaEclipse | HideLiked): ✅ has_liked getter " + owner + "." + m.getName());
                return;
            }
            ModuleLog.line("(InstaEclipse | HideLiked): ❌ has_liked getter not found");
        } catch (Throwable t) {
            ModuleLog.line("(InstaEclipse | HideLiked): ❌ " + t.getMessage());
        }
    }

    public static boolean isLiked(Object media) {
        Method getter = hasLikedGetter;
        if (media == null || getter == null) return false;
        try {
            Object target = media;
            Class<?> owner = getter.getDeclaringClass();
            if (!owner.isInstance(media)) {
                target = null;
                for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(media.getClass())) {
                    if (!owner.isAssignableFrom(f.getType())) continue;
                    f.setAccessible(true);
                    target = f.get(media);
                    break;
                }
                if (target == null) return false;
            }
            return Boolean.TRUE.equals(getter.invoke(target));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void hookBridgeMethod(String className, ClassLoader classLoader, XC_MethodHook hook)
            throws ClassNotFoundException {
        Class<?> clazz = Class.forName(className, false, classLoader);
        // The filter nulls the parse result, so never on a parser shared through an abstract base.
        if (!HookTargetGuard.ownClass(clazz)) {
            throw new IllegalStateException(className + " is abstract or non-final, not hooked");
        }
        int hooked = 0;
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.isBridge()) {
                XposedBridge.hookMethod(m, hook);
                hooked++;
            }
        }
        if (hooked == 0) throw new IllegalStateException(className + " has no parse bridge, not hooked");
    }

    // Mark the feature(s) as hooked as soon as the filter is successfully attached, rather
    // than waiting for a matching feed item to actually be filtered at runtime. The status
    // toast is built ~1.5s after the main activity's onCreate — whether a suggestion/Threads
    // unit has scrolled into the feed by then is essentially random, so gating the status on
    // that produced a false ❌ even when the hook was installed and working correctly.
    private void markHookedForEnabledFlags() {
        if (FeatureFlags.hideSuggestionsInFeed) FeatureStatusTracker.setHooked("HideSuggestionsInFeed");
        if (FeatureFlags.hideThreadsSuggestions) FeatureStatusTracker.setHooked("HideThreadsSuggestions");
        if (FeatureFlags.hideLikedPosts && hasLikedGetter != null) FeatureStatusTracker.setHooked("HideLikedPosts");
    }
}
