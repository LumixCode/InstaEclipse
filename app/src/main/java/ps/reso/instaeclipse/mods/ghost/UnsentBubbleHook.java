package ps.reso.instaeclipse.mods.ghost;

import android.view.View;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.utils.core.HookFrames;
import ps.reso.instaeclipse.utils.ghost.UnsentIdentity;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/** Style a retained message's own native surface; never mutate its shared chat theme. */
final class UnsentBubbleHook {
    private static final long UNSENT_RED = (long) UnsentMediaMark.COLOR << 32;
    private static final String ITEM = "instagram.features.direct.messagethread.TextMessageComposableHolder.Item (TextMessageComposableHolder.kt:61)";
    private static final String PROVIDED_VALUES = "instagram.features.direct.messagethread.MessageComposableHolder.getProvidedValues (MessageComposableHolder.kt:174)";
    private static final String TEXT = "com.instagram.direct.messagethread.compose.TextMessageInternal (TextMessage.kt:134)";
    // Line-free: the text and media lambdas differ only by a line number that drifts per build.
    private static final String BACKGROUND = "com.instagram.direct.messagethread.compose.background.bubbleBackground.<anonymous> (MessageBubbleBackground.kt:";
    private static final String VOICE = "instagram.features.direct.thread.feature.messagelist.message.voice.ui.VoiceMessage (VoiceMessage.kt:105)";
    private static final String VOICE_CONTENT = "instagram.features.direct.thread.feature.messagelist.message.voice.ui.VoiceMessage.<anonymous> (VoiceMessage.kt:246)";
    private static final String PORTRAIT = "instagram.features.direct.thread.feature.messagelist.message.portraitxma.ui.PortraitXmaMessage (PortraitXmaMessage.kt:158)";
    private static final String GENERIC = "instagram.features.direct.thread.feature.messagelist.message.genericxma.ui.GenericXmaMessage (GenericXmaMessage.kt:258)";
    private static final List<String> PREVIEWS = List.of(PORTRAIT, GENERIC);
    private final Map<View, State> views = Collections.synchronizedMap(new WeakHashMap<>());
    private final ThreadLocal<State> bindingState = new ThreadLocal<>();
    private final ThreadLocal<MediaScope> voiceScope = new ThreadLocal<>();
    private final ThreadLocal<MediaScope> previewScope = new ThreadLocal<>();
    private Constructor<?> stateConstructor;
    private Class<?> textHolder;
    private Object policy, localView;
    private Method readState, writeState, consumeLocal, nativeFill;
    private boolean installed, reportedFailure;

    boolean install(DexKitBridge bridge, ClassLoader loader, Field itemView) {
        if (installed) return true;
        try {
            resolveState(bridge, loader);
            List<Method> text = KeepUnsentMessagesHook.cachedMethods("UnsentBubble_text2", loader,
                    () -> findText(bridge, loader));
            if (text.size() != 3) throw new IllegalStateException("Text bubble surfaces ambiguous");
            Method item = text.get(0);
            if (Modifier.isStatic(item.getModifiers())
                    || !itemView.getDeclaringClass().isAssignableFrom(item.getDeclaringClass())) {
                throw new IllegalStateException("Text bubble holder changed");
            }
            textHolder = item.getDeclaringClass();
            resolveLocalView(bridge, loader, item);
            installBackground(text.get(1), text.get(2));
            XposedBridge.hookMethod(item, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    try {
                        View view = (View) itemView.get(param.thisObject);
                        if (view != null) stateFor(view).textBubble = true;
                    } catch (Throwable error) { report(error); }
                }
            });
            installed = true;
            ModuleLog.detail("(IE|UnsentBubble) native text bubble background installed");
            installMedia(bridge, loader);
            return true;
        } catch (Throwable error) {
            ModuleLog.detail("(IE|UnsentBubble) unavailable: " + error);
            return false;
        }
    }

    /** [text holder Item, text bubble background lambda, its native fill]; empty when any is missing. */
    private static List<Method> findText(DexKitBridge bridge, ClassLoader loader) throws Exception {
        List<MethodData> items = anchored(bridge, ITEM, true, m -> m.returnType("void"));
        // This native text lambda handles both gradients and solid fills. The other
        // bubbleBackground overload belongs to media/voice/XMA, including quoted media, and has
        // the same shape and trace prefix, so the text one is the lambda whose factory
        // TextMessageInternal calls (line 73 on 446, 78 on 451).
        Set<String> textCalls = new LinkedHashSet<>();
        for (MethodData composable : anchored(bridge, TEXT, true, m -> m)) {
            for (MethodData call : composable.getInvokes()) textCalls.add(call.getDescriptor());
        }
        List<MethodData> backgrounds = new ArrayList<>();
        for (MethodData lambda : anchored(bridge, BACKGROUND, false,
                m -> m.paramTypes(Object.class, Object.class, Object.class).returnType(Object.class))) {
            if (builtBy(bridge, lambda.getMethodInstance(loader).getDeclaringClass(), textCalls)) backgrounds.add(lambda);
        }
        if (items.size() != 1 || backgrounds.size() != 1) return List.of();
        Method fill = null;
        for (MethodData call : backgrounds.get(0).getInvokes()) {
            List<String> types = call.getParamTypeNames();
            if (call.isConstructor() || !Modifier.isStatic(call.getModifiers()) || types.size() != 3
                    || !types.get(2).equals("long") || !call.getReturnTypeName().equals(types.get(0))) continue;
            if (fill != null) return List.of(); // native background fill ambiguous
            fill = call.getMethodInstance(loader);
        }
        if (fill == null) return List.of();
        return List.of(items.get(0).getMethodInstance(loader), backgrounds.get(0).getMethodInstance(loader), fill);
    }

    private static boolean builtBy(DexKitBridge bridge, Class<?> lambda, Set<String> factories) {
        for (Constructor<?> constructor : lambda.getDeclaredConstructors()) {
            for (MethodData caller : bridge.getMethodData(constructor).getCallers()) {
                if (factories.contains(caller.getDescriptor())) return true;
            }
        }
        return false;
    }

    private void resolveLocalView(DexKitBridge bridge, ClassLoader loader, Method item) throws Throwable {
        Field field = KeepUnsentMessagesHook.cachedField("UnsentBubble_localView", loader, () -> {
            Set<Field> locals = new LinkedHashSet<>();
            for (MethodData provider : anchored(bridge, PROVIDED_VALUES, true,
                    m -> m.declaredClass(item.getDeclaringClass().getName()))) {
                for (var usage : provider.getUsingFields()) {
                    Field local = usage.getField().getFieldInstance(loader);
                    if (Modifier.isStatic(local.getModifiers()) && local.getDeclaringClass().getName()
                            .equals("androidx.compose.ui.platform.AndroidCompositionLocals_androidKt")) locals.add(local);
                }
            }
            return locals.size() == 1 ? locals.iterator().next() : null;
        });
        if (field == null) throw new IllegalStateException("Text holder LocalView ambiguous");
        field.setAccessible(true);
        localView = field.get(null);
        for (Method method : item.getParameterTypes()[0].getMethods()) {
            if (Modifier.isStatic(method.getModifiers()) || method.getReturnType() != Object.class
                    || method.getParameterCount() != 1 || !method.getParameterTypes()[0].isInstance(localView)) continue;
            if (consumeLocal != null) throw new IllegalStateException("Composition local reader ambiguous");
            consumeLocal = method;
        }
        if (consumeLocal == null) throw new NoSuchMethodException("Composition local reader");
    }

    private void resolveState(DexKitBridge bridge, ClassLoader loader) throws Throwable {
        Class<?> state = Class.forName("androidx.compose.runtime.ParcelableSnapshotMutableState", false, loader);
        Class<?> mutable = Class.forName("androidx.compose.runtime.MutableState", false, loader);
        for (Constructor<?> constructor : state.getConstructors()) {
            Class<?>[] types = constructor.getParameterTypes();
            if (types.length == 2 && types[0].isInterface() && types[1] == Object.class) {
                if (stateConstructor != null) throw new IllegalStateException("State constructor ambiguous");
                stateConstructor = constructor;
            }
        }
        if (stateConstructor == null) throw new NoSuchMethodException("Native mutable state");
        List<Method> policies = KeepUnsentMessagesHook.cachedMethods("UnsentBubble_policy", loader,
                () -> KeepUnsentMessagesHook.methodsOf(bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                        .usingStrings("StructuralEqualityPolicy").name("toString").paramCount(0)
                        .returnType(String.class))), loader));
        if (policies.size() != 1) throw new IllegalStateException("State equality policy ambiguous");
        Class<?> policyClass = policies.get(0).getDeclaringClass();
        if (!stateConstructor.getParameterTypes()[0].isAssignableFrom(policyClass)) {
            throw new IllegalStateException("Unexpected equality policy type");
        }
        for (Field field : policyClass.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == policyClass) {
                if (policy != null) throw new IllegalStateException("Equality singleton ambiguous");
                field.setAccessible(true);
                policy = field.get(null);
            }
        }
        if (policy == null) throw new IllegalStateException("Equality singleton missing");
        readState = mutable.getMethod("getValue");
        for (Method method : mutable.getMethods()) {
            if (!Modifier.isStatic(method.getModifiers()) && method.getReturnType() == void.class
                    && List.of(method.getParameterTypes()).equals(List.of(Object.class))) {
                if (writeState != null) throw new IllegalStateException("State setter ambiguous");
                writeState = method;
            }
        }
        if (writeState == null) throw new NoSuchMethodException("Native state setter");
    }

    private void installBackground(Method background, Method fill) throws Exception {
        Field shape = null;
        for (Field field : background.getDeclaringClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || !fill.getParameterTypes()[1].isAssignableFrom(field.getType())) continue;
            if (shape != null) throw new IllegalStateException("Bubble shape ambiguous");
            field.setAccessible(true);
            shape = field;
        }
        if (shape == null) throw new NoSuchFieldException("Native bubble shape");
        nativeFill = fill;
        Field nativeShape = shape;
        XposedBridge.hookMethod(background, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (param.hasThrowable() || views.isEmpty()) return;
                try {
                    // Nested Compose restarts bypass the holder's Item call. Resolve the
                    // current owning view each time instead of retaining a lambda's old row.
                    Object view = consumeLocal.invoke(param.args[1], localView);
                    State state = view instanceof View ? views.get(view) : null;
                    if (state == null || !state.textBubble) return;
                    // A snapshot read subscribes this composition to live deletion/setting changes.
                    // Let the original run first so its Compose groups always remain balanced.
                    if (Boolean.TRUE.equals(readState.invoke(state.nativeValue))) {
                        param.setResult(nativeFill.invoke(null, param.args[0], nativeShape.get(param.thisObject), UNSENT_RED));
                    }
                } catch (Throwable error) { report(error); }
            }
        });
    }

    void setMarked(View view, boolean marked, List<String> ids) {
        if (!installed) return;
        try {
            State state = stateFor(view);
            state.ids = ids;
            if (state.marked == marked) return;
            writeState.invoke(state.nativeValue, marked);
            state.marked = marked;
        } catch (Throwable error) { report(error); }
    }

    void clear(View view) {
        if (view == null) return;
        setMarked(view, false, List.of());
        State state = views.get(view);
        if (state != null) {
            state.textBubble = false;
            state.nativeMedia = false;
        }
    }

    /** Whether this row's Compose voice or shared-media surface is already drawn red. */
    boolean marksMedia(View view) {
        State state = view == null ? null : views.get(view);
        return state != null && state.marked && state.nativeMedia;
    }

    /** Makes the row available while its native binder creates Compose media before IDs are attached. */
    Object beginBind(View view) {
        State previous = bindingState.get();
        if (!installed || view == null) return previous;
        try {
            clear(view);
            bindingState.set(stateFor(view));
        } catch (Throwable error) { report(error); }
        return previous;
    }

    void endBind(Object previous) {
        if (previous instanceof State) bindingState.set((State) previous);
        else bindingState.remove();
    }

    void bind(View view, Object holder) {
        if (!installed || view == null || holder == null) return;
        try {
            stateFor(view).textBubble = textHolder.isInstance(holder);
        } catch (Throwable error) {
            report(error);
        }
    }

    private void installMedia(DexKitBridge bridge, ClassLoader loader) {
        try {
            Class<?> identifier = Class.forName(UnsentIdentity.IDENTIFIER, false, loader);
            List<Method> surfaces = KeepUnsentMessagesHook.cachedMethods("UnsentBubble_voice", loader,
                    () -> findVoice(bridge, loader));
            if (surfaces.size() != 3) throw new IllegalStateException("Voice surface ambiguous");
            Method voice = surfaces.get(0), content = surfaces.get(1), factory = surfaces.get(2);
            Class<?> voiceModel = voice.getParameterTypes()[2];
            Field[] identity = UnsentMediaIdentity.path(voiceModel, identifier);
            Field payload = uniqueField(content.getDeclaringClass(), voiceModel);
            if (identity == null || identity.length != 1) throw new IllegalStateException("Voice identity changed");
            hookScope(content, voiceScope, param -> UnsentMediaIdentity.ids(payload.get(param.thisObject), identity));
            XposedBridge.hookMethod(factory, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    if (param.hasThrowable()) return;
                    try {
                        MediaScope scope = voiceScope.get();
                        if (isMarked(scope)) {
                            param.setResult(nativeFill.invoke(null, param.args[2], param.args[1], UNSENT_RED));
                            scope.state.nativeMedia = true;
                        }
                    } catch (Throwable error) { report(error); }
                }
            });
            ModuleLog.detail("(IE|UnsentBubble) native voice surface installed");
        } catch (Throwable error) {
            ModuleLog.warn("(IE|UnsentBubble) Unsent voice messages are not colored red on this Instagram version");
            ModuleLog.detail("(IE|UnsentBubble) voice unavailable: " + error);
        }

        try {
            List<Method> preview = KeepUnsentMessagesHook.cachedMethods("UnsentBubble_preview", loader,
                    () -> findPreview(bridge, loader));
            if (preview.size() != 1 + 3 * PREVIEWS.size()) throw new IllegalStateException("Preview shape boundary ambiguous");
            Method border = preview.get(0);
            Set<Method> clips = new LinkedHashSet<>(), shapes = new LinkedHashSet<>();
            Class<?> identifier = Class.forName(UnsentIdentity.IDENTIFIER, false, loader);
            for (int i = 0; i < PREVIEWS.size(); i++) {
                Method render = preview.get(1 + i * 3);
                int modelArg = UnsentMediaIdentity.argument(render.getParameterTypes(), identifier);
                if (modelArg < 0) throw new IllegalStateException("Preview identity ambiguous");
                Field[] identity = UnsentMediaIdentity.path(render.getParameterTypes()[modelArg], identifier);
                clips.add(preview.get(2 + i * 3));
                shapes.add(preview.get(3 + i * 3));
                hookScope(render, previewScope, param -> UnsentMediaIdentity.ids(param.args[modelArg], identity));
            }
            for (Method shape : shapes) XposedBridge.hookMethod(shape, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    MediaScope scope = previewScope.get();
                    if (scope != null && scope.shape == null && !param.hasThrowable()) scope.shape = param.getResult();
                }
            });
            for (Method clip : clips) XposedBridge.hookMethod(clip, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    MediaScope scope = previewScope.get();
                    if (param.hasThrowable() || scope == null || scope.shape != param.args[1]) return;
                    try {
                        // Stroke the native preview shape; the image, play button and gesture modifier stay intact.
                        if (isMarked(scope)) {
                            param.setResult(border.invoke(null, param.getResult(), param.args[1], 2f, UNSENT_RED));
                            scope.state.nativeMedia = true;
                        }
                    } catch (Throwable error) { report(error); }
                }
            });
            ModuleLog.detail("(IE|UnsentBubble) native shared-media preview outline installed");
        } catch (Throwable error) {
            ModuleLog.warn("(IE|UnsentBubble) Unsent shared posts and reels get no red outline on this Instagram version");
            ModuleLog.detail("(IE|UnsentBubble) preview unavailable: " + error);
        }
    }

    /** [VoiceMessage, its content lambda, the voice surface factory]; empty when any is missing. */
    private List<Method> findVoice(DexKitBridge bridge, ClassLoader loader) throws Exception {
        List<MethodData> voices = anchored(bridge, VOICE, true, m -> m);
        List<MethodData> contents = anchored(bridge, VOICE_CONTENT, true, m -> m);
        if (voices.size() != 1 || contents.size() != 1) return List.of();
        Method factory = null;
        for (MethodData call : contents.get(0).getInvokes()) {
            if (call.isConstructor()) continue;
            Method method = call.getMethodInstance(loader);
            Class<?>[] types = method.getParameterTypes();
            if (!Modifier.isStatic(method.getModifiers()) || types.length != 8
                    || types[2] != nativeFill.getParameterTypes()[0]
                    || !nativeFill.getParameterTypes()[1].isAssignableFrom(types[1])
                    || method.getReturnType() != types[2]) continue;
            if (factory != null) return List.of(); // voice surface ambiguous
            factory = method;
        }
        if (factory == null) return List.of();
        return List.of(voices.get(0).getMethodInstance(loader), contents.get(0).getMethodInstance(loader), factory);
    }

    /** [border, then render, clip and shape for each of PREVIEWS]; empty when any is missing. */
    private List<Method> findPreview(DexKitBridge bridge, ClassLoader loader) throws Exception {
        Method border = resolveBorder(bridge, loader);
        if (border == null) return List.of();
        List<Method> parts = new ArrayList<>(List.of(border));
        for (String anchor : PREVIEWS) {
            List<MethodData> renders = anchored(bridge, anchor, true, m -> m);
            if (renders.size() != 1) return List.of();
            MethodData data = renders.get(0);
            Method render = data.getMethodInstance(loader);
            Set<Method> renderClips = new LinkedHashSet<>(), renderShapes = new LinkedHashSet<>();
            for (MethodData call : data.getInvokes()) {
                if (call.isConstructor()) continue;
                Method method = call.getMethodInstance(loader);
                if (!Modifier.isStatic(method.getModifiers())) continue;
                Class<?>[] types = method.getParameterTypes();
                if (List.of(types).equals(List.of(border.getParameterTypes()[0], border.getParameterTypes()[1]))
                        && method.getReturnType() == types[0]) renderClips.add(method);
                if (types.length == 4 && types[0] == render.getParameterTypes()[0]
                        && border.getParameterTypes()[1].isAssignableFrom(method.getReturnType())) renderShapes.add(method);
            }
            if (renderClips.size() != 1 || renderShapes.size() != 1) return List.of();
            parts.add(render);
            parts.add(renderClips.iterator().next());
            parts.add(renderShapes.iterator().next());
        }
        return parts;
    }

    private boolean isMarked(MediaScope scope) throws Exception {
        if (scope == null) return false;
        // Read even while unmarked so the actual voice/preview composition observes later unsends.
        boolean marked = Boolean.TRUE.equals(readState.invoke(scope.state.nativeValue));
        return marked && UnsentMediaIdentity.matches(scope.state.ids, scope.ids);
    }

    private void hookScope(Method method, ThreadLocal<MediaScope> target, RenderIdentity identity) {
        XposedBridge.hookMethod(method, new XC_MethodHook() {
            private final HookFrames.Slot frames = new HookFrames.Slot();

            @Override protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                HookFrames.Frame frame = frames.enter();
                frame.put("ie_unsent_previous_scope", target.get());
                target.remove();
                try {
                    List<String> renderedIds = identity.ids(param);
                    State state = null;
                    Object view = consumeLocal.invoke(param.args[0], localView);
                    // Text overrides LocalView with its row. Media may retain the nested Compose host.
                    for (View host = view instanceof View ? (View) view : null; host != null;
                         host = host.getParent() instanceof View ? (View) host.getParent() : null) {
                        state = views.get(host);
                        if (state == null) continue;
                        break;
                    }
                    // During the holder bind, Compose can run before afterHookedMethod attaches IDs.
                    // Reading that row's state now subscribes it to the marked update at bind end.
                    if (state == null) state = bindingState.get();
                    // Later independent recompositions may expose a detached AndroidComposeView.
                    if (state == null) state = findUniqueState(renderedIds);
                    if (state != null) target.set(new MediaScope(state, renderedIds));
                } catch (Throwable error) { report(error); }
            }
            @Override protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                HookFrames.Frame frame = frames.exit();
                MediaScope previous = (MediaScope) frame.get("ie_unsent_previous_scope");
                if (previous == null) target.remove(); else target.set(previous);
            }
        });
    }

    private State findUniqueState(List<String> renderedIds) {
        synchronized (views) {
            List<State> candidates = new java.util.ArrayList<>(views.values());
            List<List<String>> ids = new java.util.ArrayList<>(candidates.size());
            for (State state : candidates) ids.add(state.ids);
            int index = UnsentMediaIdentity.uniqueMatch(ids, renderedIds);
            return index < 0 ? null : candidates.get(index);
        }
    }

    private Method resolveBorder(DexKitBridge bridge, ClassLoader loader) throws Exception {
        List<MethodData> elements = anchored(bridge, "BorderModifierNodeElement(width=", false, m -> m);
        if (elements.size() != 1) return null;
        Class<?> element = elements.get(0).getMethodInstance(loader).getDeclaringClass();
        Set<Class<?>> factories = new LinkedHashSet<>();
        for (Constructor<?> constructor : element.getDeclaredConstructors()) {
            for (MethodData caller : bridge.getMethodData(constructor).getCallers()) {
                if (caller.getReturnTypeName().equals(nativeFill.getReturnType().getName())) {
                    factories.add(caller.getMethodInstance(loader).getDeclaringClass());
                }
            }
        }
        Set<Method> borders = new LinkedHashSet<>();
        for (Class<?> factory : factories) for (Method method : factory.getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers()) && method.getReturnType() == nativeFill.getReturnType()
                    && List.of(method.getParameterTypes()).equals(List.of(nativeFill.getParameterTypes()[0],
                    nativeFill.getParameterTypes()[1], float.class, long.class))) borders.add(method);
        }
        return borders.size() == 1 ? borders.iterator().next() : null; // null: border ambiguous
    }

    /** Methods using {@code anchor}. Compose trace strings end in a source line number that drifts
     *  between builds, so with no exact hit {@code byPrefix} retries on the text before ".kt:". */
    private static List<MethodData> anchored(DexKitBridge bridge, String anchor, boolean byPrefix,
                                             java.util.function.UnaryOperator<MethodMatcher> shape) {
        List<MethodData> exact = bridge.findMethod(FindMethod.create().matcher(
                shape.apply(MethodMatcher.create().usingStrings(anchor))));
        int line = anchor.lastIndexOf(".kt:");
        if (!exact.isEmpty() || !byPrefix || line < 0) return exact;
        return bridge.findMethod(FindMethod.create().matcher(shape.apply(MethodMatcher.create()
                .addUsingString(anchor.substring(0, line + 4), StringMatchType.StartsWith))));
    }

    private static Field uniqueField(Class<?> owner, Class<?> type) throws Exception {
        Field match = null;
        for (Field field : owner.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.getType() != type) continue;
            if (match != null) throw new IllegalStateException("Media payload ambiguous");
            match = field;
        }
        if (match == null) throw new NoSuchFieldException("Media payload");
        match.setAccessible(true);
        return match;
    }

    private interface RenderIdentity {
        List<String> ids(XC_MethodHook.MethodHookParam param) throws Exception;
    }

    private static final class MediaScope {
        final State state;
        final List<String> ids;
        Object shape;
        MediaScope(State state, List<String> ids) { this.state = state; this.ids = ids; }
    }

    private State stateFor(View view) throws Exception {
        State state = views.get(view);
        if (state == null) {
            state = new State(stateConstructor.newInstance(policy, Boolean.FALSE));
            views.put(view, state);
        }
        return state;
    }

    private void report(Throwable error) {
        if (reportedFailure) return;
        reportedFailure = true;
        ModuleLog.warn("(IE|UnsentBubble) Could not update the color of an unsent message", error);
    }

    private static final class State {
        final Object nativeValue;
        boolean marked, textBubble, nativeMedia;
        List<String> ids = List.of();
        State(Object nativeValue) { this.nativeValue = nativeValue; }
    }
}
