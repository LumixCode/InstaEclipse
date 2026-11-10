package ps.reso.instaeclipse.mods.feed;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.Found;
import ps.reso.instaeclipse.utils.core.HookTargetGuard;
import ps.reso.instaeclipse.utils.core.SafeReflect;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;

public class HideReelsItemsHook {

    private static final String TAG = "(IE|ReelsFilter) ";
    private static final String SUGGESTIONS = "HideSuggestionsInReels";
    private static final String PAID = "HidePaidPartnerships";
    private static final String LIST_KEY = "clips_special_response_type";
    private static final String SUGGESTION_TYPE = "XDTNetEgoMediaItem";
    private static final String MEDIA = "com.instagram.feed.media.Media";
    private static final int MAX_PARSERS = 4;
    private static final int KEEP = 0;
    private static final int SUGGESTION = 1;
    private static final int PAID_REEL = 2;
    private static final Field[] NO_FIELDS = new Field[0];
    private static final ClassLoader BOOT = Object.class.getClassLoader();

    private static final Map<Class<?>, Field[]> LISTS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Shape> SHAPES = new ConcurrentHashMap<>();
    private static final AtomicBoolean loggedFailure = new AtomicBoolean();
    private static volatile Set<String> suggestionUnits = Collections.emptySet();

    public void install(ClassLoader loader) {
        boolean suggestions = FeatureFlags.hideSuggestionsInReels;
        boolean paid = FeatureFlags.hidePaidPartnerships;
        if (!suggestions && !paid) return;
        try {
            List<Method> parse = listParse(loader);
            if (parse.isEmpty()) {
                ModuleLog.error(TAG + "Hiding suggestions and paid partnerships in Reels will not work: the Reels list was not found in this Instagram version");
                mark(suggestions, false, paid, false);
                return;
            }
            boolean suggestionsReady = suggestions && findSuggestionUnits();
            boolean paidReady = paid && PaidPartnershipFilter.install(loader);
            if (suggestionsReady || paidReady) {
                XC_MethodHook filter = filterHook();
                for (Method method : parse) {
                    XposedBridge.hookMethod(method, filter);
                    ModuleLog.detail(TAG + "hooked " + method.getDeclaringClass().getName() + "." + method.getName());
                }
                ModuleLog.ok(TAG + "ready");
            }
            mark(suggestions, suggestionsReady, paid, paidReady);
        } catch (Throwable t) {
            ModuleLog.error(TAG + "Hiding suggestions and paid partnerships in Reels will not work: setup failed", t);
            mark(suggestions, false, paid, false);
        }
    }

    private static void mark(boolean suggestions, boolean suggestionsReady, boolean paid, boolean paidReady) {
        if (suggestionsReady) FeatureStatusTracker.setHooked(SUGGESTIONS);
        else if (suggestions) FeatureStatusTracker.setBroken(SUGGESTIONS);
        if (paidReady) FeatureStatusTracker.setHooked(PAID);
        else if (paid) FeatureStatusTracker.setBroken(PAID);
    }

    private static List<Method> listParse(ClassLoader loader) {
        Set<Class<?>> parsers = new LinkedHashSet<>();
        for (Found found : DexKitQueryCache.methods(FindMethod.create().matcher(
                MethodMatcher.create().usingEqStrings(List.of(LIST_KEY))))) {
            try {
                Class<?> c = Class.forName(found.getClassName(), false, loader);
                if (HookTargetGuard.ownClass(c) && !parseBridges(c).isEmpty()) parsers.add(c);
            } catch (Throwable t) {
                ModuleLog.detail(TAG + "skipped " + found + ": " + t);
            }
        }
        if (parsers.isEmpty() || parsers.size() > MAX_PARSERS) {
            ModuleLog.detail(TAG + "Reels list parser " + (parsers.isEmpty() ? "not found" : "ambiguous, not hooked: " + parsers));
            return Collections.emptyList();
        }
        List<Method> parse = new ArrayList<>();
        for (Class<?> parser : parsers) parse.addAll(parseBridges(parser));
        return parse;
    }

    private static List<Method> parseBridges(Class<?> c) {
        List<Method> out = new ArrayList<>();
        for (Method method : SafeReflect.methods(c)) {
            if (method.isBridge() && method.getParameterCount() == 1 && method.getReturnType() == Object.class
                    && implementsAbstract(c.getSuperclass(), method)) {
                out.add(method);
            }
        }
        return out;
    }

    private static boolean implementsAbstract(Class<?> base, Method method) {
        for (Class<?> c = base; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return Modifier.isAbstract(c.getDeclaredMethod(method.getName(), method.getParameterTypes()).getModifiers());
            } catch (NoSuchMethodException e) {
                continue;
            } catch (Throwable t) {
                return false;
            }
        }
        return false;
    }

    private static boolean findSuggestionUnits() {
        Set<String> names = new HashSet<>();
        for (Found found : DexKitQueryCache.methods(FindMethod.create().matcher(
                MethodMatcher.create().usingEqStrings(List.of(SUGGESTION_TYPE))))) {
            names.add(found.getClassName());
        }
        if (names.isEmpty()) {
            ModuleLog.error(TAG + "Hiding suggestions in Reels will not work: suggestion cards were not found in this Instagram version");
            return false;
        }
        suggestionUnits = names;
        ModuleLog.detail(TAG + "suggestion card classes: " + names);
        return true;
    }

    private static XC_MethodHook filterHook() {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!FeatureFlags.hideSuggestionsInReels && !FeatureFlags.hidePaidPartnerships) return;
                try {
                    Object response = param.getResult();
                    if (response != null) filter(response);
                } catch (Throwable t) {
                    if (loggedFailure.getAndSet(true)) return;
                    ModuleLog.warn(TAG + "Suggestions and paid partnerships may still show in Reels: the list could not be filtered", t);
                }
            }
        };
    }

    private static void filter(Object response) throws IllegalAccessException {
        int[] removed = new int[3];
        int total = 0;
        for (Field field : lists(response.getClass())) {
            if (!(field.get(response) instanceof List<?> items) || items.isEmpty()) continue;
            List<Object> kept = new ArrayList<>(items.size());
            for (Object item : items) {
                int verdict = item == null ? KEEP : verdict(item);
                if (verdict == KEEP) kept.add(item);
                else removed[verdict]++;
            }
            total += items.size();
            if (kept.size() != items.size()) field.set(response, kept);
        }
        if (removed[SUGGESTION] + removed[PAID_REEL] == 0) return;
        ModuleLog.detail(TAG + "removed " + removed[SUGGESTION] + " suggestion(s) and " + removed[PAID_REEL]
                + " paid partnership reel(s) from " + total + " list item(s)");
    }

    private static int verdict(Object item) {
        if (item.getClass().getClassLoader() == BOOT) return KEEP;
        Shape shape = SHAPES.computeIfAbsent(item.getClass(), Shape::new);
        if (shape.media.length == 0) return KEEP;
        for (Field field : shape.media) {
            Object media = read(field, item);
            if (media != null) return PaidPartnershipFilter.shouldHide(media) ? PAID_REEL : KEEP;
        }
        if (!FeatureFlags.hideSuggestionsInReels) return KEEP;
        Set<String> units = suggestionUnits;
        for (Field field : shape.units) {
            Object unit = read(field, item);
            if (unit != null && units.contains(unit.getClass().getName())) return SUGGESTION;
        }
        return KEEP;
    }

    private static Object read(Field field, Object item) {
        try {
            return field.get(item);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Field[] lists(Class<?> c) {
        return LISTS.computeIfAbsent(c, HideReelsItemsHook::listFields);
    }

    private static Field[] listFields(Class<?> c) {
        List<Field> out = new ArrayList<>();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field field : SafeReflect.fields(k)) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != List.class) continue;
                try {
                    field.setAccessible(true);
                    out.add(field);
                } catch (Throwable t) {
                    ModuleLog.detail(TAG + "list field " + field.getName() + " not readable: " + t);
                }
            }
        }
        return out.toArray(NO_FIELDS);
    }

    private static final class Shape {
        final Field[] media;
        final Field[] units;

        Shape(Class<?> c) {
            List<Field> media = new ArrayList<>();
            List<Field> units = new ArrayList<>();
            for (Field field : SafeReflect.fields(c)) {
                Class<?> type = field.getType();
                if (Modifier.isStatic(field.getModifiers()) || type.isPrimitive() || type.isArray()) continue;
                boolean isMedia = type.getName().equals(MEDIA);
                if (!isMedia && type.getName().startsWith("java.")) continue;
                try {
                    field.setAccessible(true);
                } catch (Throwable t) {
                    continue;
                }
                (isMedia ? media : units).add(field);
            }
            this.media = media.toArray(NO_FIELDS);
            this.units = units.toArray(NO_FIELDS);
        }
    }
}
