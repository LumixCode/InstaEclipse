package ps.reso.instaeclipse.mods.misc;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
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
import android.widget.Toast;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.HookFrames;
import ps.reso.instaeclipse.utils.core.JitGuard;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.toast.IeToast;

/**
 * Hold a feed or reel caption to copy it.
 *
 * Feed captions are IgTextLayoutView text; reel captions are Litho text inside
 * clips_caption_component, and tapping a reel caption's "…" opens a comment sheet whose top row
 * shows the whole caption (Litho text next to clips_author_info_component). Captions are usually
 * collapsed, so the full caption comes from the post's Media: the reel page's holder, the sheet's
 * post, or the caption's own link spans. A view counts as a caption only when its visible text
 * shows that Media's caption, so comments (also IgTextLayoutView) behave as before.
 * Touches are watched instead of adding a long-click listener: the caption's link spans take the
 * press before View's own long-press detection sees it, and Litho reuses hosts for other
 * components, so a listener would leak onto them.
 *
 * Caption text: Media (or LiveTreeMediaDict on <=436) -> caption object (the zero-arg getter
 * guarded by the "caption" string) -> its text accessor. The caption type has many other String
 * getters (status, ids, JSON debug dumps), so the accessor is the one its Pando implementation
 * reads the "text" key with, never a guess.
 */
public class CaptionCopyHook {
    private static final String TEXT_VIEW = "com.instagram.ui.widget.textview.IgTextLayoutView";
    private static final String LITHO_HOST = "com.facebook.litho.ComponentHost";
    private static final String MEDIA = "com.instagram.feed.media.Media";
    private static final int MAX_HOPS = 3, MAX_VISITS = 300;
    private static final String CLAIM = "ie_caption_claim", RESTORE_UP = "ie_caption_restore_up";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static Method captionGetter; // Media (or LiveTreeMediaDict) -> caption object
    private static Method captionText;   // caption object -> caption text

    // One gesture at a time; touch events arrive on the main thread.
    private static WeakReference<View> pressed = new WeakReference<>(null);
    private static Runnable pendingCopy;
    private static boolean copied;
    private static float downX, downY;
    private static int reelCaptionId = -1, authorInfoId = -1, sheetListId = -1;
    // Reel captions a link span resolved, newest first. An expanded reel caption (00jG.A08 on
    // IG 451) drops the "… more" span, so unless it has @mentions or #hashtags nothing in it leads
    // to the Media; the tap that expanded it resolved the caption through that span.
    private static final ArrayDeque<String> recentReelCaptions = new ArrayDeque<>();
    private static final int RECENT_REEL_CAPTIONS = 16;

    public void install(DexKitBridge bridge, ClassLoader loader) {
        resolveCaptionGetter(bridge, loader);
        resolveCaptionText(bridge, loader);
        if (captionText == null) {
            if (FeatureFlags.enableCaptionCopy) FeatureStatusTracker.setBroken("CaptionCopy", R.string.ig_dialog_misc_copy_caption);
            return;
        }
        int hooked = 0;
        for (String type : new String[]{TEXT_VIEW, LITHO_HOST}) {
            try {
                // The compat lookup also walks superclasses: if this type stopped overriding
                // onTouchEvent it would bind View's, hooking every view in the app.
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
        markBuiltCaptions(bridge, loader);
    }

    // ── Caption text → post ──────────────────────────────────────────────────

    /**
     * Inert span tying caption text to its post. Instagram's caption builder gets the post through
     * its config, but an expanded reel caption (00jG.A08 on 451) or a short one keeps no link span
     * leading back to it, so the builder's output is tagged with it here. Equal for the same post,
     * so rebuilt captions still compare equal.
     */
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

    private static volatile Field[] mediaPath; // caption config -> ... -> Media
    private static volatile Class<?> mediaPathOf;

    /**
     * Tags every caption text Instagram's caption builder (the class of the " [...]" builder, 00jG
     * on 451) returns: the collapsed, expanded and short forms all come from its methods that take
     * the caption config, which reaches the Media in two hops (08jO -> 05Af -> Media).
     */
    private static void markBuiltCaptions(DexKitBridge bridge, ClassLoader loader) {
        try {
            Method builder = DexKitCache.isCacheValid() ? DexKitCache.loadMethod("CaptionCopy_builder", loader) : null;
            if (builder == null) {
                for (MethodData md : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                        .usingStrings(" [...]").paramCount(7)))) {
                    Method m = md.getMethodInstance(loader);
                    if (Modifier.isStatic(m.getModifiers()) && m.getReturnType() != void.class) { builder = m; break; }
                }
                if (builder == null) { ModuleLog.warn("(IE|Caption) Copying expanded or short Reels captions may not work in this Instagram version (caption builder not found)"); return; }
                DexKitCache.saveMethod("CaptionCopy_builder", builder);
            }
            // The config type: the builder's parameter that leads to a Media.
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
                        JitGuard.tick(param.method);
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

    /** Fields from this type to a Media: one of its own, or one of a field's (two hops). */
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

    // ── Caption text ─────────────────────────────────────────────────────────

    private static void resolveCaptionGetter(DexKitBridge bridge, ClassLoader classLoader) {
        if (DexKitCache.isCacheValid()) {
            Method cached = DexKitCache.loadMethod("CaptionGetter", classLoader);
            if (cached != null) {
                captionGetter = cached;
                return;
            }
        }

        try {
            // The caption getter (0-arg, object-returning, guarded by the "caption" string)
            // moved from LiveTreeMediaDict (<=436) onto com.instagram.feed.media.Media itself
            // in the 442+ media-model refactor. Try the current home first, then the legacy
            // class. Only stable com.instagram.feed.media.* class names are used — never an
            // obfuscated X.* name (those change every Instagram version).
            String[] declClassCandidates = {
                    "com.instagram.feed.media.Media",             // 442+ (447: Media caption getter)
                    "com.instagram.feed.media.LiveTreeMediaDict"  // 436 and earlier
            };

            for (String declClass : declClassCandidates) {
                List<MethodData> results;
                try {
                    results = bridge.findMethod(FindMethod.create()
                            .matcher(MethodMatcher.create()
                                    .declaredClass(declClass)
                                    .paramCount(0)
                                    .usingEqStrings(List.of("caption"))));
                } catch (Throwable ignored) {
                    continue; // class absent on this build
                }

                for (MethodData md : results) {
                    if (md.getName().equals("<clinit>")) continue;
                    try {
                        Method m = md.getMethodInstance(classLoader);
                        if (m.getParameterCount() != 0) continue;   // excludes the setter
                        if (m.getReturnType() == void.class || m.getReturnType().isPrimitive()) continue;
                        m.setAccessible(true);
                        captionGetter = m;
                        DexKitCache.saveMethod("CaptionGetter", m);
                        ModuleLog.detail("(IE|Caption) captionGetter=" + declClass + "." + m.getName());
                        return;
                    } catch (Throwable ignored) {}
                }
            }
            ModuleLog.error("(IE|Caption) Could not turn on caption copy: the caption was not found in this Instagram version");
        } catch (Throwable t) {
            ModuleLog.error("(IE|Caption) Could not turn on caption copy: " + t);
        }
    }

    private static void resolveCaptionText(DexKitBridge bridge, ClassLoader classLoader) {
        if (captionGetter == null) return;
        Class<?> captionType = captionGetter.getReturnType();
        try {
            Method impl = DexKitCache.isCacheValid() ? DexKitCache.loadMethod("CaptionText", classLoader) : null;
            if (impl == null) {
                List<MethodData> found = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
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
            // Call it through the caption type, so every implementation (Pando or immutable) answers.
            captionText = captionType.isInterface() ? captionType.getMethod(impl.getName()) : impl;
            captionText.setAccessible(true);
            ModuleLog.detail("(IE|Caption) captionText=" + captionType.getName() + "." + captionText.getName());
        } catch (Throwable t) {
            ModuleLog.error("(IE|Caption) Could not turn on caption copy: " + t);
        }
    }

    private static String extractCaptionText(Object media) {
        try {
            // The receiver depends on where the getter is declared:
            //   442+ : declared on Media itself      -> invoke on `media` directly
            //   <=436: declared on LiveTreeMediaDict -> find that dict field on `media`
            Class<?> owner = captionGetter.getDeclaringClass();
            Object receiver;
            if (owner.isInstance(media)) {
                receiver = media;
            } else {
                receiver = null;
                Class<?> cls = media.getClass();
                outer:
                while (cls != null && cls != Object.class) {
                    for (Field f : cls.getDeclaredFields()) {
                        if (owner.isAssignableFrom(f.getType())) {
                            f.setAccessible(true);
                            Object v = f.get(media);
                            if (v != null) { receiver = v; break outer; }
                        }
                    }
                    cls = cls.getSuperclass();
                }
                if (receiver == null) return null;
            }

            Object captionObj = captionGetter.invoke(receiver);
            return captionObj == null ? null : (String) captionText.invoke(captionObj);
        } catch (Throwable t) {
            ModuleLog.detail("(IE|Caption) extractCaptionText: " + t);
            return null;
        }
    }

    // ── Hold to copy ─────────────────────────────────────────────────────────

    private static final XC_MethodHook TOUCH = new XC_MethodHook() {
        private final HookFrames.Slot frames = new HookFrames.Slot();

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            JitGuard.tick(param.method);
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
                    // Finish a copied hold as a cancel, so the release doesn't also open a link
                    // or expand the caption.
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
            JitGuard.tick(param.method);
            HookFrames.Frame frame = frames.exit();
            if (Boolean.TRUE.equals(frame.get(RESTORE_UP))) {
                ((MotionEvent) param.args[0]).setAction(MotionEvent.ACTION_UP);
            } else if (Boolean.TRUE.equals(frame.get(CLAIM))
                    && Boolean.FALSE.equals(param.getResult())) {
                // A press between the caption's links: keep the gesture so the hold is seen.
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
            // Instagram can toggle a reel caption on long press too; the hold is ours now.
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
            // Litho text counts in a reel's caption, or in the caption row on top of the comment
            // sheet that tapping a reel caption's "…" opens.
            boolean litho = LITHO_HOST.equals(hookedType);
            boolean reel = litho && inReelCaption(view);
            boolean sheet = litho && !reel && inSheetCaption(view);
            if (litho && !reel && !sheet) return null;
            CharSequence shown = shownText(view, hookedType);
            if (shown == null) return null;
            String visible = shown.toString();
            int tagged = 0, links = 0;
            List<Object> media = new ArrayList<>();
            if (shown instanceof Spanned text) {
                media.addAll(linkedMedia(text));
                links = media.size();
                for (CaptionMedia tag : text.getSpans(0, text.length(), CaptionMedia.class)) {
                    Object m = tag.media.get();
                    if (m != null) { media.add(0, m); tagged++; }
                }
            }
            // A reel caption's own page knows its reel whatever the text carries (often plain text,
            // with no link back); the caption atop a comment sheet belongs to the sheet's post.
            Object page = reel ? pageMedia(view) : sheet ? CommentSearchHook.sheetPost() : null;
            if (page != null) media.add(0, page);
            for (Object m : media) {
                String caption = extractCaptionText(m);
                if (caption != null && CaptionTextPolicy.shows(visible, caption)) {
                    caption = caption.trim();
                    if (reel) rememberReelCaption(caption);
                    return caption;
                }
            }
            if (reel) {
                for (String caption : recentReelCaptions) {
                    if (CaptionTextPolicy.showsWhole(visible, caption)) return caption;
                }
            }
            if (litho) {
                ModuleLog.detail("(IE|Caption) " + (reel ? "reel" : "sheet") + " caption not matched (spanned=" + (shown instanceof Spanned)
                        + " page=" + (page != null) + " tagged=" + tagged + " links=" + links
                        + " remembered=" + recentReelCaptions.size() + ")");
            }
        } catch (Throwable t) {
            ModuleLog.detail("(IE|Caption) hold-to-copy: " + t);
        }
        return null;
    }

    // Page holder class -> its {item field, Media getter} pairs.
    private static final java.util.Map<Class<?>, List<Object[]>> PAGE_PATHS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The reel of the page around this view. Instagram tags each reel page's root
     * (RoundedCornerFrameLayout, in LithoClipsItemDefinition.bindView) with its holder, which keeps
     * the bound reel item; the item hands out its Media (017l.Cgu() on 451).
     */
    private static Object pageMedia(View view) {
        View current = view;
        for (int depth = 0; current != null && depth < 30; depth++) {
            Object media = taggedMedia(current);
            // The page root can also be a sibling of the caption's own branch.
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

    /** The first Media one of the holder's items hands out (06Sp.A01 017l → Cgu() on 451). */
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

    /** Every (item field, Media getter) pair of a holder type; other items (players, state) have none or answer null. */
    private static List<Object[]> mediaPaths(Class<?> holder) {
        List<Object[]> paths = new ArrayList<>();
        for (Field field : instanceFields(holder)) {
            String type = field.getType().getName();
            if (!type.startsWith("X.") && !type.startsWith("com.instagram.")) continue;
            for (Method getter : mediaGetters(field.getType())) paths.add(new Object[]{field, getter});
        }
        return paths;
    }

    /** No-arg Media getters of a type, interface ones (Cgu) first. */
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

    /** The comment/caption interface's text accessor (02Rd.DhX on 451), or null if unresolved. */
    static Method textAccessor() {
        return captionText;
    }

    private static CharSequence shownText(View view, String hookedType) throws Exception {
        if (TEXT_VIEW.equals(hookedType)) {
            Object layout = view.getClass().getMethod("getTextLayout").invoke(view);
            return layout instanceof Layout l ? l.getText() : null;
        }
        CharSequence plain = null;
        if (view.getClass().getMethod("getTextContentText").invoke(view) instanceof List<?> texts) {
            for (Object text : texts) {
                if (text instanceof Spanned spanned) return spanned;
                if (plain == null && text instanceof CharSequence cs && cs.length() > 0) plain = cs;
            }
        }
        return plain;
    }

    /**
     * Litho text in the caption row of a comment sheet (the Litho sheet a reel caption's "…"
     * opens): the row also holds the author info (clips_author_info_component), and it sits in the
     * sheet's list (sticky_header_list).
     */
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

    /** Media objects the caption's links point at, nearest first, through Instagram classes only. */
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
            for (Class<?> c = value.getClass(); c != null && isInstagram(c.getName()); c = c.getSuperclass()) {
                for (Field field : c.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                    try {
                        field.setAccessible(true);
                        Object child = field.get(value);
                        if (child != null) queue.add(new Object[]{child, depth + 1});
                    } catch (Throwable ignored) {}
                }
            }
        }
        return media;
    }

    private static boolean isInstagram(String type) {
        return !(type.startsWith("java.") || type.startsWith("android.") || type.startsWith("androidx.")
                || type.startsWith("kotlin."));
    }

    private static void copyToClipboard(Context ctx, String text) {
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) return;
            cm.setPrimaryClip(ClipData.newPlainText("caption", text));
            // Android 13+ confirms every copy itself; a second toast would just repeat it.
            if (android.os.Build.VERSION.SDK_INT < 33) {
                IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_caption_copied), Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            ModuleLog.error("(IE|Caption) Could not copy the caption", t);
        }
    }
}
