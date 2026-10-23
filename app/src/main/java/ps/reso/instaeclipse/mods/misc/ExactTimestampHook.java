package ps.reso.instaeclipse.mods.misc;

import ps.reso.instaeclipse.xposedcompat.AndroidAppHelper;
import android.content.Context;
import android.content.res.Resources;
import android.text.format.DateFormat;
import android.view.View;
import android.view.ViewParent;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.HookFrames;
import ps.reso.instaeclipse.utils.core.JitGuard;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public final class ExactTimestampHook {
    private static final String CACHE_KEY = "ExactTimestampAbsoluteFormatters_v1";
    private static final String STORY_ITEM = "com.instagram.model.reels.ReelItem";
    private static final String STORY_BINDER_CACHE = "ExactTimestampStoryBinders_v1";
    private static final String STORY_BINDER_ABSENT = "ExactTimestampStoryBinders_v1_absent";
    private static final String COMPOSE_STORY_CACHE = "ExactTimestampComposeStory_v1";
    private static final String CONTENT_SCOPE_CACHE = "ExactTimestampContentScopes_v1";
    private static final String DM_TIME_CACHE = "ExactTimestampDmTime_v1";
    private static final String DM_SCOPE_CACHE = "ExactTimestampDmScopes_v1";
    private static final String DM_METADATA_ANCHOR = "Unexpected Message lifecycle state for message from other: %s";
    private final ExactTimestampScope contentScope = new ExactTimestampScope();
    private final ExactTimestampScope dmScope = new ExactTimestampScope();
    private final ExactTimestampScope composeStoryScope = new ExactTimestampScope();
    private final StoryTimestampLayout storyLayout = new StoryTimestampLayout();
    private boolean reportedFormatFailure;

    public void install(DexKitBridge bridge, ClassLoader classLoader) {
        try {
            List<Method> methods = DexKitCache.isCacheValid()
                    ? DexKitCache.loadMethods(CACHE_KEY, classLoader) : null;
            if (methods == null || methods.isEmpty()) {
                methods = discover(bridge, classLoader);
                DexKitCache.saveMethods(CACHE_KEY, methods);
            }
            if (methods.isEmpty()) throw new IllegalStateException("Absolute date formatter not found");
            installContentScopes(bridge, classLoader, methods);
            for (Method method : methods) installFormatter(method);
            // The content formatters are already live, so neither optional surface may mark the
            // whole feature broken or stop the other from installing.
            try {
                installDmTime(bridge, classLoader);
            } catch (Throwable error) {
                ModuleLog.warn("(ExactTimestamps) Exact times in chats are not available in this Instagram version; other dates still work");
                ModuleLog.detail("(ExactTimestamps) DM seconds unavailable: " + error);
            }
            try {
                installStoryLayout(bridge, classLoader);
            } catch (Throwable error) {
                ModuleLog.warn("(ExactTimestamps) Long dates in stories may be cut off: the story date layout was not found in this Instagram version");
                ModuleLog.detail("(ExactTimestamps) Story layout adjustment unavailable: " + error);
            }
            FeatureStatusTracker.setHooked("ExactTimestamps");
            ModuleLog.ok("(ExactTimestamps) Ready");
            ModuleLog.detail("(ExactTimestamps) Installed " + methods.size() + " absolute-date formatters");
        } catch (Throwable error) {
            if (FeatureFlags.exactTimestamps) FeatureStatusTracker.setBroken("ExactTimestamps");
            ModuleLog.error("(ExactTimestamps) Could not turn on exact dates: " + error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }

    private void installContentScopes(DexKitBridge bridge, ClassLoader loader, List<Method> formatters)
            throws Exception {
        List<Method> entries = DexKitCache.isCacheValid()
                ? DexKitCache.loadMethods(CONTENT_SCOPE_CACHE, loader) : null;
        if (entries == null || entries.isEmpty()) {
            Map<String, Method> found = new LinkedHashMap<>();
            for (Method formatter : formatters) {
                for (MethodData caller : bridge.getMethodData(formatter).getCallers()) {
                    if (ExactTimestampScope.isContentCaller(caller.getClassName(),
                            caller.getParamTypeNames(), caller.getUsingStrings())) {
                        found.put(caller.getDescriptor(), caller.getMethodInstance(loader));
                    }
                }
            }
            entries = new ArrayList<>(found.values());
            DexKitCache.saveMethods(CONTENT_SCOPE_CACHE, entries);
        }
        if (entries.isEmpty()) throw new IllegalStateException("Content timestamp scopes missing");
        for (Method entry : entries) installScope(entry, contentScope, "ExactTimestampContentScope");
        ModuleLog.detail("(ExactTimestamps) Scoped exact dates to " + entries.size() + " content binders");
    }

    private static void installScope(Method entry, ExactTimestampScope scope, String marker) {
        XposedBridge.hookMethod(entry, new XC_MethodHook() {
            private final HookFrames.Slot frames = new HookFrames.Slot();

            @Override protected void beforeHookedMethod(MethodHookParam param) {
                JitGuard.tick(param.method);
                HookFrames.Frame frame = frames.enter();
                if (!FeatureFlags.exactTimestamps) return;
                scope.enter();
                frame.put(marker, Boolean.TRUE);
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                JitGuard.tick(param.method);
                HookFrames.Frame frame = frames.exit();
                // Xposed invokes the after hook even when the host binder throws. Do not test
                // the setting here: it may have changed while a nested bind was in progress.
                if (Boolean.TRUE.equals(frame.get(marker))) scope.exit();
            }
        });
    }

    private void installDmTime(DexKitBridge bridge, ClassLoader loader) throws Exception {
        Method formatter = DexKitCache.isCacheValid() ? DexKitCache.loadMethod(DM_TIME_CACHE, loader) : null;
        List<Method> scopes = DexKitCache.isCacheValid() ? DexKitCache.loadMethods(DM_SCOPE_CACHE, loader) : null;
        if (formatter == null || scopes == null || scopes.isEmpty()) {
            Map<String, MethodData> candidates = new LinkedHashMap<>();
            for (MethodData builder : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                    .usingStrings(DM_METADATA_ANCHOR)))) {
                for (MethodData call : builder.getInvokes()) {
                    if (signature(call, "android.content.Context", "com.instagram.common.session.UserSession", "long")) {
                        candidates.put(call.getDescriptor(), call);
                    }
                }
            }
            if (candidates.size() != 1) throw new IllegalStateException("Ambiguous DM metadata time formatter");
            MethodData target = candidates.values().iterator().next();
            formatter = target.getMethodInstance(loader);
            scopes = new ArrayList<>();
            for (MethodData caller : target.getCallers()) {
                // The message metadata builder and the native call-message timestamp helper
                // are the two reference callers. Inbox digests use different helpers.
                if (caller.getUsingStrings().contains(DM_METADATA_ANCHOR)
                        || signature(caller, "com.instagram.common.session.UserSession", "long", "boolean", "boolean")) {
                    scopes.add(caller.getMethodInstance(loader));
                }
            }
            if (scopes.isEmpty()) throw new IllegalStateException("DM metadata scope missing");
            DexKitCache.saveMethod(DM_TIME_CACHE, formatter);
            DexKitCache.saveMethods(DM_SCOPE_CACHE, scopes);
        }
        for (Method entry : scopes) installScope(entry, dmScope, "ExactTimestampDmScope");
        XposedBridge.hookMethod(formatter, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                JitGuard.tick(param.method);
                if (!FeatureFlags.exactTimestamps || !dmScope.active() || param.hasThrowable()
                        || !(param.getResult() instanceof String original) || original.isEmpty()) return;
                try {
                    Context context = (Context) param.args[0];
                    Locale locale = context.getResources().getConfiguration().getLocales().get(0);
                    String pattern = DateFormat.getBestDateTimePattern(locale,
                            ExactTimestampFormatter.timeSkeleton(DateFormat.is24HourFormat(context)));
                    // Direct messages carry microseconds; the native helper divides by 1000
                    // before constructing Date. Keep this time-only swipe label compact.
                    String exact = ExactTimestampFormatter.formatMicros(((Number) param.args[2]).longValue(),
                            locale, TimeZone.getDefault(), pattern);
                    if (exact != null) param.setResult(exact);
                } catch (Throwable error) {
                    reportFailure(error);
                }
            }
        });
    }

    private void installStoryLayout(DexKitBridge bridge, ClassLoader classLoader) throws Exception {
        List<Method> binders = DexKitCache.isCacheValid()
                ? DexKitCache.loadMethods(STORY_BINDER_CACHE, classLoader) : null;
        // 446+ has no legacy binder, so an empty result is the normal case: remember that it was
        // searched for instead of repeating the full-dex string query on every cold start.
        boolean knownAbsent = binders != null && binders.isEmpty()
                && "1".equals(DexKitCache.loadString(STORY_BINDER_ABSENT));
        if (!knownAbsent && (binders == null || binders.isEmpty())) {
            binders = new ArrayList<>();
            for (MethodData binder : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                    .returnType("void").usingStrings("ReelViewerItemBinder.bindTimestamp")))) {
                binders.add(binder.getMethodInstance(classLoader));
            }
            DexKitCache.saveMethods(STORY_BINDER_CACHE, binders);
            DexKitCache.saveString(STORY_BINDER_ABSENT, binders.isEmpty() ? "1" : "0");
        }
        if (binders.isEmpty()) {
            installComposeStoryLayout(bridge, classLoader);
            return;
        }
        for (Method binder : binders) {
            Map<Integer, List<Field>> fields = new LinkedHashMap<>();
            Class<?>[] parameters = binder.getParameterTypes();
            for (int i = 0; i < parameters.length; i++) {
                for (Field field : parameters[i].getDeclaredFields()) {
                    if (!TextView.class.isAssignableFrom(field.getType())) continue;
                    field.setAccessible(true);
                    fields.computeIfAbsent(i, ignored -> new ArrayList<>()).add(field);
                }
            }
            if (fields.isEmpty()) throw new IllegalStateException("Story timestamp holder missing");
            XposedBridge.hookMethod(binder, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    JitGuard.tick(param.method);
                    if (param.hasThrowable()) return;
                    try {
                        for (Map.Entry<Integer, List<Field>> holder : fields.entrySet()) {
                            Object owner = param.args[holder.getKey()];
                            if (owner == null) continue;
                            for (Field field : holder.getValue()) {
                                if (!(field.get(owner) instanceof TextView timestamp)
                                        || !hasResourceName(timestamp, "reel_viewer_timestamp")) continue;
                                LinearLayout container = null;
                                for (ViewParent parent = timestamp.getParent(); parent instanceof View view;
                                        parent = parent.getParent()) {
                                    if (view instanceof LinearLayout layout
                                            && hasResourceName(view, "reel_viewer_text_container")) {
                                        container = layout;
                                        break;
                                    }
                                }
                                storyLayout.update(timestamp, container, FeatureFlags.exactTimestamps);
                            }
                        }
                    } catch (Throwable error) {
                        reportFailure(error);
                    }
                }
            });
        }
    }

    private void installComposeStoryLayout(DexKitBridge bridge, ClassLoader loader) throws Exception {
        List<Method> methods = DexKitCache.isCacheValid()
                ? DexKitCache.loadMethods(COMPOSE_STORY_CACHE, loader) : null;
        if (methods == null || methods.size() != 2) {
            // Compose source markers carry a line number that moves between versions (446
            // Timestamp.kt:12, 451 :13), so match the file prefix; the shape check stays exact.
            List<MethodData> entries = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                    .returnType("void").addUsingString(
                            "instagram.features.stories.mvvm.feature.header.compose.Timestamp (Timestamp.kt:",
                            StringMatchType.StartsWith)));
            if (entries.size() != 1) throw new IllegalStateException("Compose story timestamp entry missing");
            MethodData entry = entries.get(0);
            MethodData text = null;
            for (MethodData call : entry.getInvokes()) {
                boolean igText = false;
                for (String marker : call.getUsingStrings()) {
                    igText |= marker.startsWith("com.instagram.compose.core.ui.IgText (IgText.kt:");
                }
                if (!igText || !StoryTimestampComposeLayout.matches(call.getParamTypeNames())) continue;
                if (text != null) throw new IllegalStateException("Ambiguous Compose story text layout");
                text = call;
            }
            if (text == null) throw new IllegalStateException("Compose story text layout missing");
            methods = java.util.Arrays.asList(entry.getMethodInstance(loader), text.getMethodInstance(loader));
            DexKitCache.saveMethods(COMPOSE_STORY_CACHE, methods);
        }
        installScope(methods.get(0), composeStoryScope, "ExactTimestampComposeStoryScope");
        XposedBridge.hookMethod(methods.get(1), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                JitGuard.tick(param.method);
                if (FeatureFlags.exactTimestamps && composeStoryScope.active()) {
                    StoryTimestampComposeLayout.wrap(param.args);
                }
            }
        });
        ModuleLog.detail("(ExactTimestamps) Installed scoped Compose story wrapping");
    }

    private static boolean hasResourceName(View view, String name) {
        // Generated ids (package byte 0) are no resources: skip the lookup and Android's log line.
        if (view.getId() == View.NO_ID || (view.getId() >>> 24) == 0) return false;
        try {
            return name.equals(view.getResources().getResourceEntryName(view.getId()));
        } catch (Resources.NotFoundException ignored) {
            return false;
        }
    }

    private static List<Method> discover(DexKitBridge bridge, ClassLoader classLoader) throws Exception {
        MethodData storyFormatter = null;
        MethodData relativeCore, dateCore;
        try {
            storyFormatter = storyDateFormatter(bridge);
            relativeCore = onlyStringDelegate(storyFormatter, storyFormatter.getClassName());
            dateCore = onlyStringDelegate(relativeCore, storyFormatter.getClassName());
        } catch (IllegalStateException entryChanged) {
            // ReelItem's single (Context)->String entry is this path's one point of failure. The
            // date utility itself is still unique by its format strings, so fall back to that.
            ModuleLog.detail("(ExactTimestamps) Story entry unusable (" + entryChanged.getMessage()
                    + "), using the date utility anchor");
            storyFormatter = null;
            MethodData[] cores = anchoredCores(bridge, classLoader);
            relativeCore = cores[0];
            dateCore = cores[1];
        }
        String owner = dateCore.getClassName();

        // The same wrappers feed post captions/footers, ReelItem and both Compose and legacy
        // comment headers. Resolve from their call graph, never from translated text or an
        // obfuscated name. The utility also contains elapsed-time and future-countdown helpers;
        // those have different signatures and must retain their original behavior.
        Map<String, Method> methods = new LinkedHashMap<>();
        List<MethodData> candidates = bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create().declaredClass(owner).returnType("java.lang.String")));
        for (MethodData candidate : candidates) {
            List<String> types = candidate.getParamTypeNames();
            boolean simpleAbsolute = (types.size() == 2 || (types.size() == 3
                    && types.get(2).equals("boolean")))
                    && (types.get(0).equals("android.content.Context")
                    || types.get(0).equals("android.content.res.Resources"))
                    && (types.get(1).equals("double") || types.get(1).equals("long"));
            if (simpleAbsolute && (invokes(candidate, relativeCore) || invokes(candidate, dateCore))) {
                methods.put(candidate.getDescriptor(), candidate.getMethodInstance(classLoader));
            }
        }
        // Older/archived stories use the same utility's calendar formatter instead of the
        // relative wrappers. Its first double is the item date; the second is the current date.
        for (MethodData call : dateCore.getInvokes()) {
            if (call.getClassName().equals(owner) && signature(call, "java.lang.String", "double", "double")) {
                methods.put(call.getDescriptor(), call.getMethodInstance(classLoader));
            }
        }
        if (storyFormatter != null && !methods.containsKey(storyFormatter.getDescriptor())) {
            throw new IllegalStateException("Story timestamp wrapper not covered");
        }
        return new ArrayList<>(methods.values());
    }

    private static MethodData storyDateFormatter(DexKitBridge bridge) {
        List<MethodData> storyMethods = bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create().declaredClass(STORY_ITEM)
                        .returnType("java.lang.String").paramTypes("android.content.Context")));
        if (storyMethods.size() != 1) throw new IllegalStateException("Ambiguous story timestamp entry");

        MethodData storyFormatter = null;
        for (MethodData call : storyMethods.get(0).getInvokes()) {
            if (signature(call, "android.content.res.Resources", "double")) {
                if (storyFormatter != null) throw new IllegalStateException("Ambiguous date utility");
                storyFormatter = call;
            }
        }
        if (storyFormatter == null) throw new IllegalStateException("Story date utility missing");
        return storyFormatter;
    }

    /**
     * Upstream's anchor: the date utility is the only class using "%d:%02d:%02d", "MMMM d" and
     * "MMM d" (436-451). Returns {relative "ago" core, date core}. The relative core is the one
     * taking the utility itself and a single time, so countdown helpers (no utility parameter) and
     * the date core (two times) are never picked.
     */
    private static MethodData[] anchoredCores(DexKitBridge bridge, ClassLoader loader) throws Exception {
        Method core = null;
        for (ClassData utility : bridge.findClass(FindClass.create().matcher(ClassMatcher.create()
                .usingStrings("%d:%02d:%02d", "MMMM d", "MMM d")))) {
            Method found = findFormatter(utility.getInstance(loader));
            if (found == null) continue;
            if (core != null) throw new IllegalStateException("Ambiguous date utility anchor");
            core = found;
        }
        if (core == null) throw new IllegalStateException("Date utility anchor missing");
        MethodData dateCore = bridge.getMethodData(core);
        String owner = dateCore.getClassName();
        MethodData relativeCore = null;
        for (MethodData candidate : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                .declaredClass(owner).returnType("java.lang.String")))) {
            List<String> types = candidate.getParamTypeNames();
            if (types.size() != 6 || !types.get(0).equals("android.content.res.Resources")
                    || !types.get(2).equals(owner) || !types.get(3).equals("java.lang.Integer")
                    || !types.get(4).equals("double") || !types.get(5).equals("boolean")
                    || !invokes(candidate, dateCore)) continue;
            if (relativeCore != null) throw new IllegalStateException("Ambiguous relative date core");
            relativeCore = candidate;
        }
        if (relativeCore == null) throw new IllegalStateException("Relative date core missing");
        return new MethodData[]{relativeCore, dateCore};
    }

    /** static String m(Resources, Enum, ownClass, Integer, double, double, boolean, boolean, boolean, boolean) */
    private static Method findFormatter(Class<?> c) {
        for (Method m : ps.reso.instaeclipse.utils.core.SafeReflect.methods(c)) {
            Class<?>[] p = m.getParameterTypes();
            if (!Modifier.isStatic(m.getModifiers()) || m.getReturnType() != String.class || p.length != 10) continue;
            if (p[0] != Resources.class || p[3] != Integer.class
                    || p[4] != double.class || p[5] != double.class) continue;
            boolean flags = true;
            for (int i = 6; i < 10; i++) flags &= p[i] == boolean.class;
            if (flags) return m;
        }
        return null;
    }

    private static MethodData onlyStringDelegate(MethodData source, String owner) {
        MethodData found = null;
        for (MethodData call : source.getInvokes()) {
            if (!call.getClassName().equals(owner) || !call.getReturnTypeName().equals("java.lang.String")) continue;
            if (found != null && !found.getDescriptor().equals(call.getDescriptor())) {
                throw new IllegalStateException("Ambiguous absolute timestamp delegate");
            }
            found = call;
        }
        if (found == null) throw new IllegalStateException("Absolute timestamp delegate missing");
        return found;
    }

    private static boolean signature(MethodData method, String... parameters) {
        return method.getReturnTypeName().equals("java.lang.String")
                && method.getParamTypeNames().equals(java.util.Arrays.asList(parameters));
    }

    private static boolean invokes(MethodData source, MethodData target) {
        for (MethodData call : source.getInvokes()) {
            if (call.getDescriptor().equals(target.getDescriptor())) return true;
        }
        return false;
    }

    private void installFormatter(Method method) {
        XposedBridge.hookMethod(method, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                JitGuard.tick(param.method);
                if (!FeatureFlags.exactTimestamps || !contentScope.active() || param.hasThrowable()
                        || !(param.getResult() instanceof String original) || original.isEmpty()) return;
                try {
                    Context context = param.args[0] instanceof Context supplied
                            ? supplied : AndroidAppHelper.currentApplication();
                    if (context == null) return;
                    Resources resources = param.args[0] instanceof Resources supplied
                            ? supplied : context.getResources();
                    Locale locale = resources.getConfiguration().getLocales().get(0);
                    // The "Date format" setting, else the device's own pattern with seconds.
                    String pattern = ExactTimestampFormatter.pattern(FeatureFlags.exactTimestampFormat,
                            DateFormat.getBestDateTimePattern(locale,
                                    ExactTimestampFormatter.skeleton(DateFormat.is24HourFormat(context))));
                    String exact = ExactTimestampFormatter.formatSeconds(
                            ((Number) param.args[1]).doubleValue(), locale, TimeZone.getDefault(), pattern);
                    if (exact != null) param.setResult(exact);
                } catch (Throwable error) {
                    reportFailure(error);
                }
            }
        });
    }

    private void reportFailure(Throwable error) {
        if (reportedFormatFailure) return;
        reportedFormatFailure = true;
        ModuleLog.warn("(ExactTimestamps) Some dates could not be shown exactly: " + error);
    }
}
