package ps.reso.instaeclipse.mods.misc;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.Spanned;
import android.text.style.ClickableSpan;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.Found;
import ps.reso.instaeclipse.utils.core.HookFrames;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.text.CopyFeedback;
import ps.reso.instaeclipse.utils.users.UserUtils;

public class CaptionCopyHook {
    private static final String TEXT_VIEW = "com.instagram.ui.widget.textview.IgTextLayoutView";
    private static final String LITHO_HOST = "com.facebook.litho.ComponentHost";
    private static final String MEDIA = "com.instagram.feed.media.Media";
    private static final String USER = "com.instagram.user.model.User";
    private static final int MAX_HOPS = 3, MAX_VISITS = 300;
    private static final String CLAIM = "ie_caption_claim", RESTORE_UP = "ie_caption_restore_up";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<Class<?>, Method> TEXT_GETTERS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Field[]> LINK_FIELDS = new ConcurrentHashMap<>();

    private static Method captionGetter;
    private static Method captionText;

    private static WeakReference<View> pressed = new WeakReference<>(null);
    private static Runnable pendingCopy;
    private static boolean copied;
    private static float downX, downY;
    private static int reelCaptionId = -1, authorInfoId = -1, sheetListId = -1;
    private static final ArrayDeque<String> recentReelCaptions = new ArrayDeque<>();
    private static final int RECENT_REEL_CAPTIONS = 16;

    public void install(ClassLoader loader) {
        resolveCaptionGetter(loader);
        resolveCaptionText(loader);
        if (captionText == null) {
            if (FeatureFlags.enableCaptionCopy) FeatureStatusTracker.setBroken("CaptionCopy", R.string.ig_dialog_misc_copy_caption);
            return;
        }
        if (!FeatureFlags.enableCaptionCopy) return;
        int hooked = 0;
        for (String type : new String[]{TEXT_VIEW, LITHO_HOST}) {
            try {
                Class<?> cls = XposedHelpers.findClass(type, loader);
                Method onTouch = XposedHelpers.findMethodExact(cls, "onTouchEvent", MotionEvent.class);
                if (onTouch.getDeclaringClass() != cls) {
                    throw new NoSuchMethodException("onTouchEvent is inherited from "
                            + onTouch.getDeclaringClass().getName());
                }
                XposedBridge.hookMethod(onTouch, TOUCH);
                hooked++;
            } catch (Throwable t) {
                ModuleLog.warn("(IE|Caption) Hold to copy may not work on some captions: " + t);
            }
        }
        if (hooked > 0) FeatureStatusTracker.setHooked("CaptionCopy");
        else if (FeatureFlags.enableCaptionCopy) FeatureStatusTracker.setBroken("CaptionCopy", R.string.ig_dialog_misc_copy_caption);
        ModuleLog.line("(IE|Caption) " + (hooked > 0 ? "✅ Ready"
                : "❌ Could not turn on hold to copy captions: caption views are not supported in this Instagram version"));
        ModuleLog.detail("(IE|Caption) hold-to-copy on " + hooked + " caption view type(s)");
        markBuiltCaptions(loader);
    }

    static final class CaptionMedia {
        final WeakReference<Object> media;

        CaptionMedia(Object media) {
            this.media = new WeakReference<>(media);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof CaptionMedia other && other.media.get() == media.get();
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(media.get());
        }
    }

    private static volatile Field[] mediaPath;
    private static volatile Class<?> mediaPathOf;

    private static void markBuiltCaptions(ClassLoader loader) {
        try {
            Method builder = DexKitCache.isCacheValid() ? DexKitCache.loadMethod("CaptionCopy_builder", loader) : null;
            if (builder == null) {
                for (Found md : DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                        .usingStrings(" [...]").paramCount(7)))) {
                    Method m = md.getMethodInstance(loader);
                    if (Modifier.isStatic(m.getModifiers()) && m.getReturnType() != void.class) { builder = m; break; }
                }
                if (builder == null) { ModuleLog.warn("(IE|Caption) Copying expanded or short Reels captions may not work in this Instagram version (caption builder not found)"); return; }
                DexKitCache.saveMethod("CaptionCopy_builder", builder);
            }
            Class<?> config = null;
            for (Class<?> p : builder.getParameterTypes()) if (pathToMedia(p) != null) { config = p; break; }
            if (config == null) { ModuleLog.warn("(IE|Caption) Copying expanded or short Reels captions may not work in this Instagram version (caption config not found)"); return; }
            int hooked = 0;
            for (Method m : builder.getDeclaringClass().getDeclaredMethods()) {
                Class<?> r = m.getReturnType();
                if (r != android.text.SpannableStringBuilder.class && r != android.util.Pair.class) continue;
                int at = -1;
                Class<?>[] params = m.getParameterTypes();
                for (int i = 0; i < params.length; i++) if (params[i] == config) at = i;
                if (at < 0) continue;
                final int configAt = at;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!FeatureFlags.enableCaptionCopy) return;
                        Object out = param.getResult();
                        if (out instanceof android.util.Pair<?, ?> pair) out = pair.first;
                        if (out instanceof android.text.Spannable text) tag(text, param.args[configAt]);
                    }
                });
                hooked++;
            }
            ModuleLog.detail("(IE|Caption) post tags on " + hooked + " caption builder method(s) of "
                    + builder.getDeclaringClass().getName());
        } catch (Throwable t) {
            ModuleLog.warn("(IE|Caption) Copying expanded or short Reels captions may not work: " + t);
        }
    }

    private static void tag(android.text.Spannable text, Object config) {
        try {
            if (config == null || text.length() == 0) return;
            Field[] path = mediaPath;
            if (path == null || mediaPathOf != config.getClass()) {
                path = pathToMedia(config.getClass());
                if (path == null) return;
                mediaPath = path;
                mediaPathOf = config.getClass();
            }
            Object media = config;
            for (Field f : path) if ((media = f.get(media)) == null) return;
            CaptionMedia tag = new CaptionMedia(media);
            for (CaptionMedia old : text.getSpans(0, text.length(), CaptionMedia.class)) {
                if (old.equals(tag)) return;
                text.removeSpan(old);
            }
            text.setSpan(tag, 0, text.length(), android.text.Spanned.SPAN_INCLUSIVE_INCLUSIVE);
        } catch (Throwable ignored) {}
    }

    private static Field[] pathToMedia(Class<?> type) {
        if (type.isPrimitive() || !isInstagram(type.getName())) return null;
        for (Field f : instanceFields(type)) {
            if (f.getType().getName().equals(MEDIA)) return new Field[]{f};
        }
        for (Field f : instanceFields(type)) {
            Class<?> t = f.getType();
            if (t.isPrimitive() || t.isArray() || !isInstagram(t.getName())) continue;
            for (Field g : instanceFields(t)) {
                if (g.getType().getName().equals(MEDIA)) return new Field[]{f, g};
            }
        }
        return null;
    }

    private static List<Field> instanceFields(Class<?> type) {
        List<Field> out = new ArrayList<>();
        for (Class<?> c = type; c != null && isInstagram(c.getName()); c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                try { f.setAccessible(true); out.add(f); } catch (Throwable ignored) {}
            }
        }
        return out;
    }

    private static void resolveCaptionGetter(ClassLoader classLoader) {
        if (DexKitCache.isCacheValid()) {
            Method cached = DexKitCache.loadMethod("CaptionGetter", classLoader);
            if (cached != null) {
                captionGetter = cached;
                return;
            }
        }

        try {
            List<Found> results = DexKitQueryCache.methods(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .declaredClass(MEDIA)
                            .paramCount(0)
                            .usingEqStrings(List.of("caption"))));
            for (Found md : results) {
                if (md.getName().equals("<clinit>")) continue;
                try {
                    Method m = md.getMethodInstance(classLoader);
                    if (m.getParameterCount() != 0) continue;
                    if (m.getReturnType() == void.class || m.getReturnType().isPrimitive()) continue;
                    m.setAccessible(true);
                    captionGetter = m;
                    DexKitCache.saveMethod("CaptionGetter", m);
                    ModuleLog.detail("(IE|Caption) captionGetter=" + MEDIA + "." + m.getName());
                    return;
                } catch (Throwable ignored) {}
            }
            ModuleLog.error("(IE|Caption) Could not turn on caption copy: the caption was not found in this Instagram version");
        } catch (Throwable t) {
            ModuleLog.error("(IE|Caption) Could not turn on caption copy: " + t);
        }
    }

    private static void resolveCaptionText(ClassLoader classLoader) {
        if (captionGetter == null) return;
        Class<?> captionType = captionGetter.getReturnType();
        try {
            Method impl = DexKitCache.isCacheValid() ? DexKitCache.loadMethod("CaptionText", classLoader) : null;
            if (impl == null) {
                List<Found> found = DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                        .declaredClass(ClassMatcher.create()
                                .addInterface(captionType.getName(), StringMatchType.Equals, false))
                        .usingNumbers("text".hashCode()).paramCount(0).returnType(String.class)));
                if (found.size() != 1) {
                    ModuleLog.error("(IE|Caption) Could not turn on caption copy: the caption text was not found in this Instagram version");
                    ModuleLog.detail("(IE|Caption) caption text accessor matched " + found.size() + " methods");
                    return;
                }
                impl = found.get(0).getMethodInstance(classLoader);
                DexKitCache.saveMethod("CaptionText", impl);
            }
            captionText = captionType.isInterface() ? captionType.getMethod(impl.getName()) : impl;
            captionText.setAccessible(true);
            ModuleLog.detail("(IE|Caption) captionText=" + captionType.getName() + "." + captionText.getName());
        } catch (Throwable t) {
            ModuleLog.error("(IE|Caption) Could not turn on caption copy: " + t);
        }
    }

    private static String extractCaptionText(Object media) {
        try {
            if (!captionGetter.getDeclaringClass().isInstance(media)) return null;
            Object captionObj = captionGetter.invoke(media);
            return captionObj == null ? null : (String) captionText.invoke(captionObj);
        } catch (Throwable t) {
            ModuleLog.detail("(IE|Caption) extractCaptionText: " + t);
            return null;
        }
    }

    private static final XC_MethodHook TOUCH = new XC_MethodHook() {
        private final HookFrames.Slot frames = new HookFrames.Slot();

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            HookFrames.Frame frame = frames.enter();
            View view = (View) param.thisObject;
            MotionEvent event = (MotionEvent) param.args[0];
            boolean ours = view == pressed.get();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> press(view, event, param, frame);
                case MotionEvent.ACTION_MOVE -> { if (ours && !copied && moved(view, event)) cancel(); }
                case MotionEvent.ACTION_UP -> {
                    if (!ours) return;
                    boolean wasCopied = copied;
                    cancel();
                    if (wasCopied) {
                        event.setAction(MotionEvent.ACTION_CANCEL);
                        frame.put(RESTORE_UP, Boolean.TRUE);
                    }
                }
                case MotionEvent.ACTION_CANCEL -> { if (ours) cancel(); }
                default -> { }
            }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            HookFrames.Frame frame = frames.exit();
            if (Boolean.TRUE.equals(frame.get(RESTORE_UP))) {
                ((MotionEvent) param.args[0]).setAction(MotionEvent.ACTION_UP);
            } else if (Boolean.TRUE.equals(frame.get(CLAIM))
                    && Boolean.FALSE.equals(param.getResult())) {
                param.setResult(Boolean.TRUE);
            }
        }
    };

    private static void press(View view, MotionEvent event, XC_MethodHook.MethodHookParam param,
                              HookFrames.Frame frame) {
        cancel();
        if (!FeatureFlags.enableCaptionCopy) return;
        String caption = captionOf(view, param.method.getDeclaringClass().getName());
        if (caption == null) return;
        pressed = new WeakReference<>(view);
        downX = event.getX();
        downY = event.getY();
        pendingCopy = () -> {
            pendingCopy = null;
            if (pressed.get() != view || !view.isAttachedToWindow()) return;
            copied = true;
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            copyToClipboard(view.getContext(), caption);
            view.cancelLongPress();
        };
        MAIN.postDelayed(pendingCopy, ViewConfiguration.getLongPressTimeout());
        frame.put(CLAIM, Boolean.TRUE);
    }

    private static void cancel() {
        if (pendingCopy != null) MAIN.removeCallbacks(pendingCopy);
        pendingCopy = null;
        pressed = new WeakReference<>(null);
        copied = false;
    }

    private static boolean moved(View view, MotionEvent event) {
        int slop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
        return Math.abs(event.getX() - downX) > slop || Math.abs(event.getY() - downY) > slop;
    }

    private static String captionOf(View view, String hookedType) {
        try {
            boolean litho = LITHO_HOST.equals(hookedType);
            boolean reel = litho && inReelCaption(view);
            boolean sheet = litho && !reel && inSheetCaption(view);
            if (litho && !reel && !sheet) return null;
            CharSequence shown = shownText(view, hookedType);
            if (shown == null) return null;
            String visible = shown.toString();
            Spanned spanned = shown instanceof Spanned text ? text : null;
            List<Object> media = new ArrayList<>();
            Object page = reel ? pageMedia(view) : sheet ? CommentSearchHook.sheetPost() : null;
            if (page != null) media.add(page);
            if (spanned != null) {
                for (CaptionMedia tag : spanned.getSpans(0, spanned.length(), CaptionMedia.class)) {
                    Object m = tag.media.get();
                    if (m != null) media.add(m);
                }
            }
            int tagged = media.size() - (page != null ? 1 : 0);
            String caption = matching(media, visible, true, reel);
            if (caption != null) return caption;
            List<Object> linked = spanned != null ? linkedMedia(spanned) : List.of();
            caption = matching(linked, visible, litho, reel);
            if (caption != null) return caption;
            if (reel) {
                for (String remembered : recentReelCaptions) {
                    if (CaptionTextPolicy.showsWhole(visible, remembered)) return remembered;
                }
            }
            if (litho) {
                ModuleLog.detail("(IE|Caption) " + (reel ? "reel" : "sheet") + " caption not matched (spanned=" + (spanned != null)
                        + " page=" + (page != null) + " tagged=" + tagged + " links=" + linked.size()
                        + " remembered=" + recentReelCaptions.size() + ")");
            }
        } catch (Throwable t) {
            ModuleLog.detail("(IE|Caption) hold-to-copy: " + t);
        }
        return null;
    }

    private static String matching(List<Object> media, String visible, boolean shortCaptions, boolean reel) {
        for (Object m : media) {
            String caption = extractCaptionText(m);
            if (caption == null) continue;
            boolean isShort = CaptionTextPolicy.isShort(caption);
            if (isShort && !shortCaptions) continue;
            if (!CaptionTextPolicy.shows(visible, caption, isShort ? authorsOf(m) : null)) continue;
            caption = caption.trim();
            if (reel) rememberReelCaption(caption);
            return caption;
        }
        return null;
    }

    private static final Map<Class<?>, List<Method>> USER_GETTERS = new ConcurrentHashMap<>();

    private static List<String> authorsOf(Object media) {
        List<String> names = new ArrayList<>();
        for (Method getter : USER_GETTERS.computeIfAbsent(media.getClass(), CaptionCopyHook::userGetters)) {
            try {
                String name = UserUtils.callUsernameGetter(getter.invoke(media));
                if (name != null && !names.contains(name)) names.add(name);
            } catch (Throwable ignored) {}
        }
        return names;
    }

    private static List<Method> userGetters(Class<?> media) {
        List<Method> out = new ArrayList<>();
        for (Method m : media.getMethods()) {
            if (Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 0
                    || !m.getReturnType().getName().equals(USER)) continue;
            out.add(m);
        }
        return out;
    }

    private static final java.util.Map<Class<?>, List<Object[]>> PAGE_PATHS = new java.util.concurrent.ConcurrentHashMap<>();

    private static Object pageMedia(View view) {
        View current = view;
        for (int depth = 0; current != null && depth < 30; depth++) {
            Object media = taggedMedia(current);
            if (media == null && depth > 0 && current instanceof android.view.ViewGroup group) {
                for (int i = 0; i < group.getChildCount() && media == null; i++) media = taggedMedia(group.getChildAt(i));
            }
            if (media != null) return media;
            current = current.getParent() instanceof View parent ? parent : null;
        }
        return null;
    }

    private static Object taggedMedia(View view) {
        Object tag = view.getTag();
        return tag != null && isInstagram(tag.getClass().getName()) ? itemMedia(tag) : null;
    }

    private static Object itemMedia(Object holder) {
        for (Object[] path : PAGE_PATHS.computeIfAbsent(holder.getClass(), CaptionCopyHook::mediaPaths)) {
            try {
                Object item = ((Field) path[0]).get(holder);
                Object media = item == null ? null : ((Method) path[1]).invoke(item);
                if (media != null) return media;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static List<Object[]> mediaPaths(Class<?> holder) {
        List<Object[]> paths = new ArrayList<>();
        for (Field field : instanceFields(holder)) {
            String type = field.getType().getName();
            if (!type.startsWith("X.") && !type.startsWith("com.instagram.")) continue;
            for (Method getter : mediaGetters(field.getType())) paths.add(new Object[]{field, getter});
        }
        return paths;
    }

    private static List<Method> mediaGetters(Class<?> type) {
        List<Method> out = new ArrayList<>();
        for (Method m : type.getMethods()) {
            if (Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 0
                    || !m.getReturnType().getName().equals(MEDIA)) continue;
            if (m.getDeclaringClass().isInterface()) out.add(0, m); else out.add(m);
        }
        return out;
    }

    private static void rememberReelCaption(String caption) {
        recentReelCaptions.remove(caption);
        recentReelCaptions.addFirst(caption);
        if (recentReelCaptions.size() > RECENT_REEL_CAPTIONS) recentReelCaptions.removeLast();
    }

    static Method textAccessor() {
        return captionText;
    }

    private static CharSequence shownText(View view, String hookedType) throws Exception {
        if (TEXT_VIEW.equals(hookedType)) {
            Object layout = textGetter(view, "getTextLayout").invoke(view);
            return layout instanceof Layout l ? l.getText() : null;
        }
        CharSequence plain = null;
        if (textGetter(view, "getTextContentText").invoke(view) instanceof List<?> texts) {
            for (Object text : texts) {
                if (text instanceof Spanned spanned) return spanned;
                if (plain == null && text instanceof CharSequence cs && cs.length() > 0) plain = cs;
            }
        }
        return plain;
    }

    private static Method textGetter(View view, String name) throws NoSuchMethodException {
        Method getter = TEXT_GETTERS.get(view.getClass());
        if (getter == null) {
            getter = view.getClass().getMethod(name);
            TEXT_GETTERS.put(view.getClass(), getter);
        }
        return getter;
    }

    @SuppressLint("DiscouragedApi")
    private static boolean inSheetCaption(View view) {
        if (authorInfoId == -1) {
            authorInfoId = view.getResources().getIdentifier(
                    "clips_author_info_component", "id", view.getContext().getPackageName());
            sheetListId = view.getResources().getIdentifier(
                    "sticky_header_list", "id", view.getContext().getPackageName());
        }
        if (authorInfoId == 0 || sheetListId == 0) return false;
        View row = view.getParent() instanceof View parent ? parent : null;
        boolean authorRow = false;
        for (int depth = 0; depth < 2 && row != null && !authorRow; depth++) {
            authorRow = row.findViewById(authorInfoId) != null;
            if (!authorRow) row = row.getParent() instanceof View parent ? parent : null;
        }
        if (!authorRow) return false;
        for (View current = row; current != null; current = current.getParent() instanceof View parent ? parent : null) {
            if (current.getId() == sheetListId) return true;
        }
        return false;
    }

    @SuppressLint("DiscouragedApi")
    private static boolean inReelCaption(View view) {
        if (reelCaptionId == -1) {
            reelCaptionId = view.getResources().getIdentifier(
                    "clips_caption_component", "id", view.getContext().getPackageName());
        }
        if (reelCaptionId == 0) return false;
        View current = view;
        for (int depth = 0; depth < 6 && current != null; depth++) {
            if (current.getId() == reelCaptionId) return true;
            current = current.getParent() instanceof View parent ? parent : null;
        }
        return false;
    }

    private static List<Object> linkedMedia(Spanned text) {
        List<Object> media = new ArrayList<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Object[]> queue = new ArrayDeque<>();
        for (ClickableSpan span : text.getSpans(0, text.length(), ClickableSpan.class)) {
            queue.add(new Object[]{span, 0});
        }
        while (!queue.isEmpty() && seen.size() < MAX_VISITS) {
            Object[] next = queue.poll();
            Object value = next[0];
            int depth = (int) next[1];
            if (!seen.add(value)) continue;
            if (value.getClass().getName().equals(MEDIA)) {
                media.add(value);
                continue;
            }
            if (depth == MAX_HOPS || value.getClass().isArray()) continue;
            for (Field field : LINK_FIELDS.computeIfAbsent(value.getClass(), CaptionCopyHook::linkFields)) {
                try {
                    Object child = field.get(value);
                    if (child != null) queue.add(new Object[]{child, depth + 1});
                } catch (Throwable ignored) {}
            }
        }
        return media;
    }

    private static Field[] linkFields(Class<?> type) {
        List<Field> out = new ArrayList<>();
        for (Class<?> c = type; c != null && isInstagram(c.getName()); c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                try {
                    field.setAccessible(true);
                    out.add(field);
                } catch (Throwable ignored) {}
            }
        }
        return out.toArray(new Field[0]);
    }

    private static boolean isInstagram(String type) {
        return !(type.startsWith("java.") || type.startsWith("android.") || type.startsWith("androidx.")
                || type.startsWith("kotlin."));
    }

    private static void copyToClipboard(Context ctx, String text) {
        try {
            CopyFeedback.copy(ctx, "caption", text, R.string.ig_caption_copied);
        } catch (Throwable t) {
            ModuleLog.error("(IE|Caption) Could not copy the caption", t);
        }
    }
}
