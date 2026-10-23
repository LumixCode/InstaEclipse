package ps.reso.instaeclipse.mods.media;

import android.app.Activity;
import android.content.Context;
import android.widget.Toast;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.toast.IeToast;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;

/**
 * Download voice messages from the DM long-press menu (#223).
 *
 * - The menu is built by the static factory of the named class MessageActionsViewModel; its
 *   main action list is a List<LongPressActionData> argument. We add a row built like IG's own
 *   helper (new LongPressActionData(null, style, action, null, null, icon, null, label, ...)),
 *   carried by the action enum value named COPY_TEXT (never offered on voice messages). The row
 *   goes right after the status header: IG moves every row past its 3/7-row cutoff under "More".
 * - The clip is read only from the selected message's own voice payload (the class parsed with
 *   "waveform_data"), never from a quoted/replied-to message. Without that anchor the graph walk
 *   below is the fallback.
 * - Stickers and GIFs (enableStickerDownload) use the same row; their URL is read when the menu
 *   is built (never on a text, link or voice message) and the tap downloads exactly that URL.
 * - The long-pressed message comes from the message lookup in the class using
 *   ": null_direct_message" (X.0BVY on 452); we keep the last one returned.
 * - Taps of every DM menu renderer end in the dispatcher using
 *   "DirectReportDailyPromptInteractor.reportDailyPrompt" (X.0RAg.A06), which receives the
 *   action and the message; SAVE_MEDIA on a voice message is consumed and downloaded.
 * - Audio URL: the message's AudioInfoIntf (found through its fields / Media) mapped by IG's
 *   serializer (static AudioInfoIntf -> Map using "audio_src_expiration_timestamp_us" +
 *   "waveform_sampling_frequency_hz") → "audio_src".
 */
public class VoiceMessageDownloadHook {

    private static final String TAG = "(IE|VoiceDL) ";
    private static final String VIEW_MODEL = "com.instagram.direct.messagethread.messageactions.model.MessageActionsViewModel";
    private static final String ACTION_DATA = "com.instagram.direct.messagethread.interaction.longpressaction.LongPressActionData";
    private static final String AUDIO_INFO = "com.instagram.api.schemas.AudioInfoIntf";
    private static final String MEDIA = "com.instagram.feed.media.Media";
    private static final String ANIMATED_MEDIA = "com.instagram.model.direct.gifs.DirectAnimatedMedia";
    private static final String GIF_URL = "com.instagram.model.mediasize.GifUrlImpl";
    private static final String STORE_STICKER = "com.instagram.model.direct.stickerstore.DirectStoreSticker";
    private static final String TYPED_IMAGE_URL = "com.instagram.model.direct.stickerstore.TypedImageUrl";
    private static final String IMAGE_URL = "com.instagram.common.typedurl.ImageUrl";
    private static final String USER_CACHE = "com.instagram.user.model.UserCache";
    // Row carrier: an action IG fully wires (click → dispatch) but never offers on voice messages.
    private static final String CARRIER = "COPY_TEXT";
    // Translate row carrier: a staff-only action every menu renderer dispatches but that never
    // appears for normal users, so the action alone identifies our tap (COPY_TEXT would be
    // indistinguishable from IG's own Copy row on text messages).
    private static final String TRANSLATE_CARRIER = "DEBUG";
    // Bulk unsend row carrier: another staff-only action (never shown to normal users).
    private static final String BULK_CARRIER = "REPORT_BUG_FOR_VISUAL_MEDIA";

    private static volatile Object lastMessage;
    private static volatile long lastMessageAt;
    private static Class<?> messageClass;
    private static Class<?> audioInfoClass;
    private static Method audioMapper;
    private static Object saveMediaAction;
    private static final String VOICE_CACHE = "VoiceDL_VoiceMedia";
    private static volatile Class<?> voiceType;      // the voice payload (X.03jC on 446)
    private static volatile Field voiceMediaField;   // its Media field (X.03jC.A02 on 446)
    // The message our Download row was added for in the menu being shown; reset on every menu
    // build, so IG's own Copy on a later text message is never taken for a voice download.
    private static volatile Object pendingVoice;
    private static volatile Field primaryActionField; // LongPressActionData's own action (ctor param 3)
    private static final Set<String> diagnostics = Collections.synchronizedSet(new HashSet<>());
    private static Class<?> animatedMediaClass, gifUrlClass, storeStickerClass, typedImageUrlClass, imageUrlClass;
    /** Instagram's user caches (one per signed-in account), kept from their constructor. */
    private static final List<java.lang.ref.WeakReference<Object>> userCaches = new java.util.concurrent.CopyOnWriteArrayList<>();
    // The sticker / GIF our Download row was added for, and its message; reset with pendingVoice on
    // every menu build, so a later tap never re-walks recentMessage() (it can be a text reply).
    private static volatile String pendingSticker;
    private static volatile Object pendingStickerMessage;

    public void install(DexKitBridge bridge, ClassLoader cl) {
        try {
            audioInfoClass = cl.loadClass(AUDIO_INFO);
            try {
                animatedMediaClass = cl.loadClass(ANIMATED_MEDIA);
                gifUrlClass = cl.loadClass(GIF_URL);
                storeStickerClass = cl.loadClass(STORE_STICKER);
                typedImageUrlClass = cl.loadClass(TYPED_IMAGE_URL);
                imageUrlClass = cl.loadClass(IMAGE_URL);
                XposedBridge.hookAllConstructors(cl.loadClass(USER_CACHE), new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                        userCaches.removeIf(r -> r.get() == null);
                        userCaches.add(new java.lang.ref.WeakReference<>(param.thisObject));
                    }
                });
            } catch (Throwable t) {
                ModuleLog.error(TAG + "Downloading stickers and GIFs from chats will not work: " + t);
            }
            for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                    .usingStrings("audio_src_expiration_timestamp_us", "waveform_sampling_frequency_hz")
                    .returnType("java.util.Map")))) {
                Method m = md.getMethodInstance(cl);
                if (Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == audioInfoClass) { audioMapper = m; break; }
            }
            resolveVoiceModel(bridge, cl);

            // message lookup → remember the long-pressed message
            int lookups = 0;
            for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                    .usingStrings(": null_direct_message")))) {
                Class<?> owner = md.getMethodInstance(cl).getDeclaringClass();
                messageClass = md.getMethodInstance(cl).getReturnType();
                for (Method m : ps.reso.instaeclipse.utils.core.SafeReflect.methods(owner)) {
                    // static on some builds, instance methods on others (X.0Awd on 452.0.0.33)
                    if (m.getReturnType() != messageClass || Modifier.isAbstract(m.getModifiers())) continue;
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                            if (param.getResult() != null) {
                                lastMessage = param.getResult();
                                lastMessageAt = System.currentTimeMillis();
                                if (param.thisObject == null && param.args.length > 0 && !(param.args[0] instanceof String))
                                    ps.reso.instaeclipse.mods.ghost.BulkUnsend.onLookup(param.args[0]);
                            }
                        }
                    });
                    lookups++;
                }
                break;
            }

            // menu factory → add our row for voice messages
            Class<?> vm = cl.loadClass(VIEW_MODEL);
            Class<?> data = cl.loadClass(ACTION_DATA);
            Constructor<?> dataCtor = null;
            for (Constructor<?> c : ps.reso.instaeclipse.utils.core.SafeReflect.constructors(data)) if (c.getParameterCount() == 11) dataCtor = c;
            final Constructor<?> ctor = dataCtor;
            if (ctor != null) {
                for (Object v : ctor.getParameterTypes()[2].getEnumConstants())
                    if (CARRIER.equals(((Enum<?>) v).name())) saveMediaAction = v;
            }
            int factories = 0;
            for (Method m : ps.reso.instaeclipse.utils.core.SafeReflect.methods(vm)) {
                if (!Modifier.isStatic(m.getModifiers()) || m.getReturnType() != vm) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                        clearPending(); // the pending targets belong to the menu being built
                        if ((!downloadEnabled() && !FeatureFlags.translateMessages && !FeatureFlags.bulkUnsend) || ctor == null) return;
                        Object msg = recentMessage();
                        // The factory gets the message's content type (VOICE_MEDIA / TEXT / LINK);
                        // unlike a field scan it can't mistake a text reply to a voice note for voice.
                        String type = contentType(param.args);
                        boolean voice = FeatureFlags.enableVoiceDownload && msg != null
                                && (type == null || "VOICE_MEDIA".equals(type)) && hasOwnVoice(msg);
                        // Stickers and GIFs: never on a text, link or voice message, whose reply
                        // preview can hold the quoted sticker.
                        String sticker = FeatureFlags.enableStickerDownload && !voice && msg != null
                                && !"TEXT".equals(type) && !"LINK".equals(type) && !"VOICE_MEDIA".equals(type)
                                ? stickerUrl(msg) : null;
                        // Translate (like comments): hand a text message to the user's translator app.
                        String text = FeatureFlags.translateMessages && !voice && sticker == null && msg != null
                                && (type == null || "TEXT".equals(type) || "LINK".equals(type)) ? messageText(msg) : null;
                        diagnose("menu-" + (voice ? "voice" : sticker != null ? "sticker" : text != null ? "text" : "none"),
                                "message=" + (msg != null) + " type=" + type);
                        for (int i = 0; i < param.args.length; i++) {
                            if (!(param.args[i] instanceof List<?> list) || list.isEmpty() || !data.isInstance(list.get(0))) continue;
                            // Bulk unsend: only on messages IG itself lets you unsend (your own).
                            boolean bulk = FeatureFlags.bulkUnsend && ps.reso.instaeclipse.mods.ghost.BulkUnsend.ready()
                                    && hasRow(list, "UNSEND");
                            if (!voice && sticker == null && text == null && !bulk) return;
                            // style template: a regular action row (the first item is the
                            // non-clickable timestamp header)
                            Object template = list.get(0);
                            for (Object r : list) {
                                String an = actionName(r);
                                if ("FORWARD".equals(an) || "REPLY".equals(an) || "PIN".equals(an) || "UNPIN".equals(an)) { template = r; break; }
                            }
                            Context c = param.args[0] instanceof Context cc ? cc : null;
                            List<Object> copy = new ArrayList<>(list);
                            if (voice || sticker != null) {
                                String label = downloadLabel();
                                Object row = buildRow(ctor, template, c, label, "instagram_download_outline_24", CARRIER);
                                if (row != null) {
                                    copy = VoiceMessageMenuPolicy.withVisibleDownload(copy, row,
                                            a -> isOurRow(a, label, CARRIER),
                                            a -> "MESSAGE_STATUS_TEXT".equals(actionName(a)));
                                    if (voice) {
                                        pendingVoice = msg;
                                        wireRowWhenShown(label, act -> { downloadVoice(act, msg); pendingVoice = null; });
                                    } else {
                                        pendingSticker = sticker;
                                        pendingStickerMessage = msg;
                                        wireRowWhenShown(label, act -> { downloadStickerOnce(act, sticker, msg); clearPending(); });
                                    }
                                }
                            } else if (text != null) {
                                Object row = buildRow(ctor, template, c, translateLabel(), "instagram_translate_pano_outline_24", TRANSLATE_CARRIER);
                                if (row != null) {
                                    copy.add(row);
                                    pendingText = text;
                                    wireRowWhenShown(translateLabel(), act -> translate(act));
                                }
                            }
                            if (bulk) {
                                // styled from IG's own Unsend row, so it keeps the same icon
                                Object unsendRow = template;
                                for (Object r : list) if ("UNSEND".equals(actionName(r))) unsendRow = r;
                                Object row = buildRow(ctor, unsendRow, c, bulkLabel(), "", BULK_CARRIER);
                                if (row != null) {
                                    copy.add(row);
                                    pendingBulk = msg;
                                    wireRowWhenShown(bulkLabel(), act -> bulkUnsend(act));
                                }
                            }
                            if (!copy.equals(list)) param.args[i] = copy; // rows added, or ours moved up
                            return;
                        }
                    }
                });
                factories++;
            }

            // tap dispatcher
            int dispatchers = 0;
            for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                    .usingStrings("DirectReportDailyPromptInteractor.reportDailyPrompt")))) {
                Method m = md.getMethodInstance(cl);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                        if (FeatureFlags.bulkUnsend && hasAction(param.args, BULK_CARRIER)) {
                            Activity bctx = null;
                            for (Object a : param.args) if (a instanceof Activity act) bctx = act;
                            param.setResult(null);
                            bulkUnsend(bctx);
                            return;
                        }
                        if (FeatureFlags.translateMessages && hasAction(param.args, TRANSLATE_CARRIER)) {
                            Context tctx = null;
                            for (Object a : param.args) if (a instanceof Activity act) tctx = act;
                            ModuleLog.probe(TAG + "dispatch: translate");
                            param.setResult(null);
                            translate(tctx);
                            return;
                        }
                        // DM photos/videos: IG's own "Save" goes through our downloader instead (its
                        // save lands in Pictures/Instagram as IMG_<date>, no username or folder).
                        if (FeatureFlags.enableDmMediaDownload && hasAction(param.args, "SAVE_MEDIA")) {
                            Object media = null;
                            Context sctx = null;
                            for (Object a : param.args) {
                                if (a != null && messageClass != null && messageClass.isInstance(a)) media = mediaOf(a);
                                else if (a instanceof Activity act) sctx = act;
                            }
                            if (media != null && sctx != null) {
                                param.setResult(null);
                                downloadDmMedia(sctx, media);
                                return;
                            }
                        }
                        if (!downloadEnabled()) return;
                        Object action = null, msg = null;
                        Context ctx = null;
                        for (Object a : param.args) {
                            if (a instanceof Enum<?> e && CARRIER.equals(e.name())) action = a;
                            else if (a != null && messageClass != null && messageClass.isInstance(a)) msg = a;
                            else if (a instanceof Activity act) ctx = act;
                        }
                        if (action == null) return;
                        // Only a tap on our row in this menu; IG's own Copy keeps working.
                        if (isPendingSticker(msg)) {
                            param.setResult(null);
                            downloadPending(ctx);
                            clearPending();
                            return;
                        }
                        if (msg == null) msg = pendingVoice;
                        if (pendingVoice == null || !hasOwnVoice(msg)) return;
                        param.setResult(null);
                        downloadVoice(ctx, msg);
                        pendingVoice = null;
                    }
                });
                dispatchers++;
            }
            // Row taps of the long-press menu go through the static method of the menu controller
            // that takes a LongPressActionData (MessageActionsController.A02 on 452).
            for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                    .paramTypes(ACTION_DATA, null, "java.lang.String")))) {
                Method m = md.getMethodInstance(cl);
                if (!Modifier.isStatic(m.getModifiers())) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                        if (!downloadEnabled() && !FeatureFlags.translateMessages && !FeatureFlags.bulkUnsend) return;
                        Object row = null;
                        for (Object a : param.args) if (data.isInstance(a)) row = a;
                        if (row == null) return;
                        if (isOurRow(row, bulkLabel(), BULK_CARRIER)) {
                            param.setResult(null);
                            bulkUnsend(null);
                            return;
                        }
                        if (isOurRow(row, translateLabel(), TRANSLATE_CARRIER)) {
                            ModuleLog.probe(TAG + "row tap: translate");
                            param.setResult(null);
                            translate(null);
                            return;
                        }
                        if (!downloadEnabled() || !isOurRow(row, downloadLabel(), CARRIER)) return;
                        ModuleLog.probe(TAG + "row tap: " + (pendingSticker != null ? "sticker" : "voice"));
                        // Not consumed: the action-selected listener that runs next consumes it.
                        downloadPending(null);
                    }
                });
                dispatchers++;
            }
            // "Action selected" listeners: (ActionEnum, String) -> void, e.g. X.0lMj.E5t on 452.
            if (ctor != null) {
                String actionEnum = ctor.getParameterTypes()[2].getName();
                for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                        .paramTypes(actionEnum, "java.lang.String").returnType("void")))) {
                    Method m;
                    try { m = md.getMethodInstance(cl); } catch (Throwable t) { continue; }
                    if (Modifier.isStatic(m.getModifiers()) || Modifier.isAbstract(m.getModifiers())) continue;
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                            if (FeatureFlags.bulkUnsend && param.args[0] instanceof Enum<?> be && BULK_CARRIER.equals(be.name())) {
                                param.setResult(null);
                                bulkUnsend(null);
                                return;
                            }
                            if (FeatureFlags.translateMessages && param.args[0] instanceof Enum<?> te && TRANSLATE_CARRIER.equals(te.name())) {
                                ModuleLog.probe(TAG + "action selected: translate");
                                param.setResult(null);
                                translate(null);
                                return;
                            }
                            if (!downloadEnabled()) return;
                            if (!(param.args[0] instanceof Enum<?> e) || !CARRIER.equals(e.name())) return;
                            // The listener holds the selected message itself (X.0OJy.A0h on 446); this
                            // runs before the dispatcher for every DM action, IG's own Copy included.
                            Object msg = messageClass != null
                                    ? VoiceMessageTargetResolver.readTypedField(param.thisObject, messageClass) : null;
                            if (isPendingSticker(msg)) {
                                ModuleLog.probe(TAG + "action selected via " + param.method.getDeclaringClass().getName() + ": sticker");
                                param.setResult(null);
                                downloadPending(null);
                                clearPending();
                                return;
                            }
                            if (msg == null) msg = pendingVoice;
                            boolean voice = pendingVoice != null && hasOwnVoice(msg);
                            ModuleLog.probe(TAG + "action selected via " + param.method.getDeclaringClass().getName() + ": voice=" + voice);
                            if (!voice) return;
                            param.setResult(null);
                            downloadVoice(null, msg);
                            pendingVoice = null;
                        }
                    });
                    dispatchers++;
                }
            }
            // Compose menu rows: every method that reads the row label field and takes a Kotlin
            // Function1 click callback (X.0Ssj.A00 on 452). Wrap the callback so a tap on our row
            // downloads; other rows are passed through untouched.
            int composeRows = 0;
            try {
                java.lang.reflect.Field labelField = null;
                for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(data)) {
                    if (f.getType() == String.class && !Modifier.isStatic(f.getModifiers())) {
                        // label = constructor param 8 (index 7); pick the field set from it
                        Class<?>[] cp = ctor.getParameterTypes();
                        Object style = cp[1].isEnum() ? cp[1].getEnumConstants()[0] : null;
                        Object act = cp[2].getEnumConstants()[0];
                        ctor.setAccessible(true);
                        Object probe = ctor.newInstance(null, style, act, null, null, null, null, "ie", null, null, null);
                        f.setAccessible(true);
                        if ("ie".equals(f.get(probe))) {
                            labelField = f;
                            primaryActionField = findPrimaryActionField(data, cp[2], probe, act);
                            break;
                        }
                    }
                }
                if (labelField != null) {
                    labelFieldRef = labelField;
                    final Field lf = labelField;
                    String desc = "L" + ACTION_DATA.replace('.', '/') + ";->" + lf.getName() + ":Ljava/lang/String;";
                    Class<?> fn1 = cl.loadClass("kotlin.jvm.functions.Function1");
                    for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                            .addUsingField(desc)))) {
                        if (!md.isMethod()) continue;
                        Method m;
                        try { m = md.getMethodInstance(cl); } catch (Throwable t) { continue; }
                        boolean takesFn = false;
                        for (Class<?> pt : m.getParameterTypes()) if (pt == fn1) takesFn = true;
                        if (!takesFn) continue;
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                                if (!downloadEnabled() && !FeatureFlags.translateMessages && !FeatureFlags.bulkUnsend) return;
                                for (int i = 0; i < param.args.length; i++) {
                                    Object orig = param.args[i];
                                    if (!fn1.isInstance(orig) || java.lang.reflect.Proxy.isProxyClass(orig.getClass())) continue;
                                    param.args[i] = java.lang.reflect.Proxy.newProxyInstance(cl, new Class<?>[]{fn1}, (proxy, method, margs) -> {
                                        if (method.getName().equals("invoke") && margs != null && margs.length == 1
                                                && data.isInstance(margs[0]) && isOurRow(margs[0], bulkLabel(), BULK_CARRIER)) {
                                            dismissMenu();
                                            bulkUnsend(null);
                                            return null;
                                        }
                                        if (method.getName().equals("invoke") && margs != null && margs.length == 1
                                                && data.isInstance(margs[0]) && isOurRow(margs[0], translateLabel(), TRANSLATE_CARRIER)) {
                                            ModuleLog.probe(TAG + "compose row tap: translate");
                                            dismissMenu();
                                            translate(null);
                                            return null;
                                        }
                                        if (method.getName().equals("invoke") && margs != null && margs.length == 1
                                                && data.isInstance(margs[0]) && isOurRow(margs[0], downloadLabel(), CARRIER)) {
                                            ModuleLog.probe(TAG + "compose row tap: " + (pendingSticker != null ? "sticker" : "voice"));
                                            downloadPending(null);
                                            clearPending();
                                            dismissMenu();
                                            return null;
                                        }
                                        if (method.getDeclaringClass() == Object.class) {
                                            if (method.getName().equals("equals")) return proxy == margs[0];
                                            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                                            if (method.getName().equals("toString")) return "IEWrappedClick";
                                        }
                                        return method.invoke(orig, margs);
                                    });
                                }
                            }
                        });
                        composeRows++;
                    }
                }
            } catch (Throwable t) {
                ModuleLog.warn(TAG + "Taps on the added rows in the newer chat menu may not work: " + t + " / " + t.getCause());
            }
            ModuleLog.detail(TAG + "compose row hooks=" + composeRows + " (label field " + (labelFieldRef != null ? "ok" : "missing")
                    + ", primary action " + (primaryActionField != null ? "ok" : "missing") + ")");
            if (factories > 0 && dispatchers > 0) ModuleLog.ok(TAG + "ready");
            else ModuleLog.error(TAG + "Could not turn on downloads from chats (voice messages, stickers, photos): the message menu was not found in this Instagram version");
            ModuleLog.detail(TAG + "lookups=" + lookups + " factories=" + factories + " dispatchers=" + dispatchers
                    + " mapper=" + (audioMapper != null) + " message=" + (messageClass != null ? messageClass.getName() : "?")
                    + " mode=" + (voiceType != null ? "precise" : "graph-walk fallback"));
            if (factories > 0 && dispatchers > 0) {
                FeatureStatusTracker.setHooked("VoiceDownload");
                if (animatedMediaClass != null && gifUrlClass != null) FeatureStatusTracker.setHooked("StickerDownload");
                else if (FeatureFlags.enableStickerDownload) FeatureStatusTracker.setBroken("StickerDownload");
                FeatureStatusTracker.setHooked("DmMediaDownload");
            } else {
                if (FeatureFlags.enableVoiceDownload) FeatureStatusTracker.setBroken("VoiceDownload");
                if (FeatureFlags.enableStickerDownload) FeatureStatusTracker.setBroken("StickerDownload");
                if (FeatureFlags.enableDmMediaDownload) FeatureStatusTracker.setBroken("DmMediaDownload");
            }
            if (factories > 0 && labelFieldRef != null) FeatureStatusTracker.setHooked("ChatTranslate");
            else if (FeatureFlags.translateMessages) FeatureStatusTracker.setBroken("ChatTranslate");
            // Bulk unsend runs its own DexKit sweeps, so only while it is on (switching it on needs an
            // Instagram restart, like the other DexKit features).
            if (FeatureFlags.bulkUnsend) ps.reso.instaeclipse.mods.ghost.BulkUnsend.install(bridge, cl, messageClass);
        } catch (Throwable t) {
            ModuleLog.error(TAG + "Could not turn on downloads from chats", t);
            if (FeatureFlags.enableVoiceDownload) FeatureStatusTracker.setBroken("VoiceDownload");
            if (FeatureFlags.enableStickerDownload) FeatureStatusTracker.setBroken("StickerDownload");
            if (FeatureFlags.enableDmMediaDownload) FeatureStatusTracker.setBroken("DmMediaDownload");
            if (FeatureFlags.translateMessages) FeatureStatusTracker.setBroken("ChatTranslate");
        }
    }

    /**
     * The voice payload class and its Media field, from the payload's JSON parser. Optional: when
     * it can't be resolved uniquely, audioUrl() falls back to the graph walk.
     */
    private static void resolveVoiceModel(DexKitBridge bridge, ClassLoader cl) {
        try {
            String cached = DexKitCache.isCacheValid() ? DexKitCache.loadString(VOICE_CACHE) : null;
            if ("missing".equals(cached)) return;
            Field media = null;
            if (cached != null) {
                int hash = cached.indexOf('#');
                media = cl.loadClass(cached.substring(0, hash)).getDeclaredField(cached.substring(hash + 1));
            } else {
                List<MethodData> parsers = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                        .name("unsafeParseFromJson").usingStrings("url_expire_at_secs", "waveform_data",
                                "current_playback_position_ms", "duration_ms")));
                Set<Field> mediaFields = new LinkedHashSet<>();
                if (parsers.size() == 1) {
                    for (var used : parsers.get(0).getUsingFields()) {
                        if (MEDIA.equals(used.getField().getTypeName())) {
                            mediaFields.add(used.getField().getFieldInstance(cl));
                        }
                    }
                }
                if (mediaFields.size() == 1) media = mediaFields.iterator().next();
                DexKitCache.saveString(VOICE_CACHE, media != null
                        ? media.getDeclaringClass().getName() + "#" + media.getName() : "missing");
            }
            if (media == null) return;
            media.setAccessible(true);
            voiceMediaField = media;
            voiceType = media.getDeclaringClass();
        } catch (Throwable t) {
            ModuleLog.detail(TAG + "voice payload (using the graph-walk fallback): " + t);
        }
    }

    /** The field of LongPressActionData set from its own action (ctor param 3), not a secondary one. */
    private static Field findPrimaryActionField(Class<?> data, Class<?> actionType, Object probe, Object action) {
        Field primary = null;
        for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(data)) {
            if (Modifier.isStatic(f.getModifiers()) || f.getType() != actionType) continue;
            try {
                f.setAccessible(true);
                if (f.get(probe) != action) continue;
            } catch (Throwable t) {
                continue;
            }
            if (primary != null) return null; // ambiguous: keep the any-field fallback
            primary = f;
        }
        return primary;
    }

    /** Name of the factory's message content-type argument (X.00xJ on 446), or null if absent. */
    private static String contentType(Object[] args) {
        for (Object a : args) {
            if (!(a instanceof Enum<?> e)) continue;
            for (Object c : e.getDeclaringClass().getEnumConstants()) {
                if ("VOICE_MEDIA".equals(((Enum<?>) c).name())) return e.name();
            }
        }
        return null;
    }

    /** The selected message's own voice Media (never a quoted message's), or null. */
    static Object ownVoiceMedia(Object msg) {
        Field media = voiceMediaField;
        Class<?> type = voiceType, message = messageClass;
        if (media == null || type == null || message == null || msg == null) return null;
        Object payload = VoiceMessageTargetResolver.find(msg, message, type);
        if (payload == null) return null;
        try {
            return media.get(payload);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Whether the message is itself a voice message (its URL is resolved only at tap time). */
    private static boolean hasOwnVoice(Object msg) {
        if (msg == null) return false;
        return voiceType != null ? ownVoiceMedia(msg) != null : audioUrl(msg) != null;
    }

    private static void diagnose(String reason, String detail) {
        if (diagnostics.add(reason)) ModuleLog.detail(TAG + reason + ": " + detail);
    }

    /**
     * The menu renders our row but routes unknown actions to a dismiss-only branch, so attach our
     * own click handler to the row once it is on screen (found by its label), then close the menu.
     */
    private static void wireRowWhenShown(String label, java.util.function.Consumer<Activity> onTap) {
        android.app.Activity act = ps.reso.instaeclipse.mods.ui.UIHookManager.getCurrentActivity();
        if (act == null) return;
        android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        final int[] tries = {0};
        h.post(new Runnable() {
            @Override
            public void run() {
                android.view.View row = findRow(act.getWindow().getDecorView(), label);
                if (row == null) {
                    if (++tries[0] < 25) h.postDelayed(this, 80);
                    return;
                }
                row.setOnClickListener(v -> {
                    onTap.accept(act);
                    act.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK));
                    act.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK));
                });
                ModuleLog.probe(TAG + "row wired (" + row.getClass().getSimpleName() + ")");
            }
        });
    }

    /** The clickable row containing a TextView whose text is exactly {@code label}. */
    private static android.view.View findRow(android.view.View root, String label) {
        java.util.ArrayDeque<android.view.View> st = new java.util.ArrayDeque<>();
        st.push(root);
        while (!st.isEmpty()) {
            android.view.View v = st.pop();
            if (v instanceof android.widget.TextView tv && tv.isShown() && label.contentEquals(tv.getText())) {
                android.view.View r = tv;
                for (int i = 0; i < 4 && r.getParent() instanceof android.view.View p; i++) {
                    r = p;
                    if (r.isClickable()) return r;
                }
                return tv.getParent() instanceof android.view.View p ? p : tv;
            }
            if (v instanceof android.view.ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) st.push(g.getChildAt(i));
        }
        return null;
    }

    private static volatile String voiceMediaId;

    /** Sender of the voice clip (its Media's owner, like post downloads), else the chat name. */
    private static String voiceUsername(Object message) {
        try {
            if (message != null) {
                Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
                ArrayDeque<Object[]> q = new ArrayDeque<>();
                q.add(new Object[]{message, 0});
                while (!q.isEmpty() && seen.size() < 200) {
                    Object[] e = q.poll();
                    Object o = e[0];
                    int d = (int) e[1];
                    if (o == null || !seen.add(o)) continue;
                    if (o.getClass().getName().equals(MEDIA)) {
                        try {
                            Object id = o.getClass().getMethod("getId").invoke(o);
                            if (id instanceof String sid && !sid.isEmpty()) voiceMediaId = sid.split("_")[0];
                        } catch (Throwable ignored) {}
                        String u = FeedVideoDownloadHook.extractUsernameFromMediaObject(o);
                        if (u != null && !u.isEmpty()) return u;
                        continue;
                    }
                    if (d >= 3) continue;
                    String n = o.getClass().getName();
                    if (!n.startsWith("X.") && !n.startsWith("com.instagram.")) continue;
                    for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass())
                        for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(k)) {
                            if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive() || f.getType() == String.class) continue;
                            try { f.setAccessible(true); q.add(new Object[]{f.get(o), d + 1}); } catch (Throwable ignored) {}
                        }
                }
            }
            String sender = senderUsername(message);
            if (sender == null) sender = chatUsername();
            if (sender != null) return sender;
            String chat = ps.reso.instaeclipse.utils.ghost.ThreadNames.resolve(
                    ps.reso.instaeclipse.utils.ghost.CurrentThreadTracker.currentThreadId());
            // keep letters of every script: a chat named in Cyrillic or Arabic stays readable
            if (chat != null && !chat.isEmpty()) return chat.replaceAll("[^\\p{L}\\p{N}._-]", "_");
        } catch (Throwable t) {
            ModuleLog.detail(TAG + "username: " + t);
        }
        return "voice";
    }

    /**
     * The username of a message's sender, for messages without a Media (stickers, GIFs): the
     * sender's id, a number or numeric string field of the message, is looked up in Instagram's
     * user caches (id -> User).
     */
    private static String senderUsername(Object message) {
        if (message == null || userCaches.isEmpty()) return null;
        try {
            List<Map<?, ?>> maps = new ArrayList<>();
            for (java.lang.ref.WeakReference<Object> ref : userCaches) {
                Object cache = ref.get();
                if (cache == null) continue;
                for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(cache.getClass())) {
                    if (Modifier.isStatic(f.getModifiers()) || !Map.class.isAssignableFrom(f.getType())) continue;
                    f.setAccessible(true);
                    if (f.get(cache) instanceof Map<?, ?> m) maps.add(m);
                }
            }
            for (Class<?> k = message.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(k)) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    Class<?> t = f.getType();
                    if (t != String.class && t != long.class && t != Long.class) continue;
                    f.setAccessible(true);
                    Object v = f.get(message);
                    if (v == null) continue;
                    String id = v.toString();
                    if (id.length() < 3 || id.length() > 20 || !id.chars().allMatch(Character::isDigit)) continue;
                    for (Map<?, ?> m : maps) {
                        Object user = m.get(id);
                        if (user == null) { try { user = m.get(Long.valueOf(id)); } catch (Throwable ignored) {} }
                        if (user == null) continue;
                        String name = ps.reso.instaeclipse.utils.users.UserUtils.callUsernameGetter(user);
                        if (name != null && !name.isEmpty()) return name;
                    }
                }
            }
        } catch (Throwable t) {
            ModuleLog.detail(TAG + "sender: " + t);
        }
        return null;
    }

    /**
     * The username of the open one-to-one chat, for messages without a Media (stickers, GIFs):
     * the thread header's subtitle (header_subtitle) names it. Group chats show a member count
     * there instead, which isn't a username, so they fall back to the chat's title.
     */
    private static String chatUsername() {
        try {
            Activity a = ps.reso.instaeclipse.mods.ui.UIHookManager.getCurrentActivity();
            if (a == null || a.getWindow() == null) return null;
            @SuppressWarnings("DiscouragedApi")
            int id = a.getResources().getIdentifier("header_subtitle", "id", a.getPackageName());
            android.view.View v = id != 0 ? a.getWindow().getDecorView().findViewById(id) : null;
            if (v == null) return null;
            CharSequence c = v.getContentDescription();
            if ((c == null || c.length() == 0) && v instanceof android.widget.TextView tv) c = tv.getText();
            String u = c != null ? c.toString().trim() : "";
            return u.matches("[A-Za-z0-9._]{1,30}") ? u : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Our injected row with this label (carried by the CARRIER action, so IG's own rows with the
     *  same text, e.g. its native "Translate", never match). */
    private static boolean isOurRow(Object row, String label, String carrier) {
        try {
            Field lf = labelFieldRef;
            return lf != null && label.equals(lf.get(row)) && carrier.equals(actionName(row));
        } catch (Throwable t) {
            return false;
        }
    }

    private static volatile Field labelFieldRef;
    private static volatile String pendingText;

    private static String downloadLabel() {
        Context c = ps.reso.instaeclipse.xposedcompat.AndroidAppHelper.currentApplication();
        return c != null ? I18n.t(c, R.string.ig_dl_title) : "Download";
    }

    private static String translateLabel() {
        Context c = ps.reso.instaeclipse.xposedcompat.AndroidAppHelper.currentApplication();
        return c != null ? I18n.t(c, R.string.ig_chat_translate_row) : "Translate with app";
    }

    private static volatile long lastTranslateAt;
    private static volatile long lastBulkAt;
    private static volatile Object pendingBulk;

    private static String bulkLabel() {
        Context c = ps.reso.instaeclipse.xposedcompat.AndroidAppHelper.currentApplication();
        return c != null ? I18n.t(c, R.string.ig_bulk_unsend_row) : "Unsend multiple";
    }

    /** Opens the bulk-unsend picker for the long-pressed message (several tap paths fire). */
    private static void bulkUnsend(Activity act) {
        if (System.currentTimeMillis() - lastBulkAt < 1500) return;
        lastBulkAt = System.currentTimeMillis();
        Object msg = pendingBulk != null ? pendingBulk : recentMessage();
        Activity a = act != null ? act : ps.reso.instaeclipse.mods.ui.UIHookManager.getCurrentActivity();
        if (a == null) return;
        // after the menu has closed
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                () -> ps.reso.instaeclipse.mods.ghost.BulkUnsend.show(a, msg), 250);
    }

    /** True when the menu list has a row carrying IG's {@code action}. */
    private static boolean hasRow(List<?> list, String action) {
        for (Object r : list) if (action.equals(actionName(r))) return true;
        return false;
    }

    /** Hands the long-pressed message's text to the user's translator app (same as comments). */
    private static void translate(Context ctx) {
        if (System.currentTimeMillis() - lastTranslateAt < 1500) return; // several tap paths fire
        lastTranslateAt = System.currentTimeMillis();
        String text = pendingText;
        if (text == null) {
            Object msg = recentMessage();
            text = msg != null ? messageText(msg) : null;
        }
        if (text == null) return;
        Context c = ctx != null ? ctx : ps.reso.instaeclipse.mods.ui.UIHookManager.getCurrentActivity();
        if (c == null) c = ps.reso.instaeclipse.xposedcompat.AndroidAppHelper.currentApplication();
        final Context host = c;
        final String t = text;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(
                () -> ps.reso.instaeclipse.utils.core.TextTranslator.translate(host, t));
    }

    /**
     * The message's own text, found by shape rather than by (obfuscated) name: a String field
     * declared on the message object whose value is repeated inside its inner message model
     * (wrapper + model both carry the text). Ids ("mid.…", digits) are skipped. Falls back to the
     * single non-id String field when nothing repeats.
     */
    public static String messageText(Object msg) {
        try {
            List<String> own = new ArrayList<>();
            List<Object> children = new ArrayList<>();
            boolean textType = false; // the message type enum (TEXT on 451); skips media/stickers
            for (Class<?> k = msg.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(k)) {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                    f.setAccessible(true);
                    Object v = f.get(msg);
                    if (v instanceof Enum<?> e && ("TEXT".equals(e.name()) || "LINK".equals(e.name()))) textType = true;
                    if (v instanceof String s) { if (isTextLike(s)) own.add(s); }
                    else if (v != null && (v.getClass().getName().startsWith("X.") || v.getClass().getName().startsWith("com.instagram."))) children.add(v);
                }
            }
            if (own.isEmpty() || !textType) return null;
            Set<String> nested = new java.util.HashSet<>();
            for (Object c : children)
                for (Class<?> k = c.getClass(); k != null && k != Object.class; k = k.getSuperclass())
                    for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(k)) {
                        if (Modifier.isStatic(f.getModifiers()) || f.getType() != String.class) continue;
                        try { f.setAccessible(true); Object v = f.get(c); if (v instanceof String s) nested.add(s); } catch (Throwable ignored) {}
                    }
            // The text is stored twice on 451 (a field of the message class and one of its base
            // class); count both that and copies in a nested model.
            java.util.Map<String, Integer> seenCount = new java.util.HashMap<>();
            for (String s : own) seenCount.merge(s, 1, Integer::sum);
            String best = null;
            for (String s : own) {
                boolean repeated = seenCount.get(s) > 1 || nested.contains(s);
                if (repeated && (best == null || s.length() > best.length())) best = s;
            }
            if (best == null && own.size() == 1) best = own.get(0);
            return best;
        } catch (Throwable t) {
            ModuleLog.detail(TAG + "text: " + t);
            return null;
        }
    }

    private static boolean isTextLike(String s) {
        if (s == null || s.trim().isEmpty()) return false;
        if (s.startsWith("mid.") || s.matches("[0-9]+")) return false;
        return true;
    }

    private static void dismissMenu() {
        android.app.Activity act = ps.reso.instaeclipse.mods.ui.UIHookManager.getCurrentActivity();
        if (act == null) return;
        act.runOnUiThread(() -> {
            act.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK));
            act.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK));
        });
    }

    private static volatile long lastDownloadAt;

    /** Downloads the voice clip of {@code msg} (fallback: the message our row was added for). */
    private static void downloadVoice(Context ctx, Object msg) {
        if (System.currentTimeMillis() - lastDownloadAt < 1500) return; // several tap paths fire
        lastDownloadAt = System.currentTimeMillis();
        if (ctx == null) ctx = ps.reso.instaeclipse.mods.ui.UIHookManager.getCurrentActivity();
        if (ctx == null) return;
        if (msg == null) msg = pendingVoice != null ? pendingVoice : recentMessage();
        String url = msg != null ? audioUrl(msg) : null;
        if (url == null) {
            final Context c = ctx;
            FeedVideoDownloadHook.mainHandler.post(() -> IeToast.makeText(c,
                    I18n.t(c, R.string.ig_voice_download_unavailable), Toast.LENGTH_SHORT).show());
            return;
        }
        // Name the file after the clip's own Media (sender + media id), not a graph walk.
        Object media = ownVoiceMedia(msg);
        String user = media != null ? FeedVideoDownloadHook.extractUsernameFromMediaObject(media) : null;
        String id = null;
        try {
            Object mid = media != null ? media.getClass().getMethod("getId").invoke(media) : null;
            if (mid instanceof String sid && !sid.isEmpty()) id = sid.split("_")[0];
        } catch (Throwable ignored) {}
        if (user == null || user.isEmpty() || id == null) {
            voiceMediaId = null;
            String walked = voiceUsername(msg);
            if (user == null || user.isEmpty()) user = walked;
            if (id == null) id = voiceMediaId;
        }
        if (id == null) id = String.valueOf(System.currentTimeMillis());
        String name = FeedVideoDownloadHook.buildFilename(user, "voice", id, true).replace(".mp4", ".m4a");
        FeedVideoDownloadHook.downloadAudio(ctx, url, name, user);
    }

    /** Downloads the sticker / GIF {@code url} of {@code message} (several tap paths fire). */
    private static void downloadStickerOnce(Context ctx, String url, Object message) {
        if (url == null || System.currentTimeMillis() - lastDownloadAt < 1500) return;
        lastDownloadAt = System.currentTimeMillis();
        if (ctx == null) ctx = ps.reso.instaeclipse.mods.ui.UIHookManager.getCurrentActivity();
        if (ctx == null) return;
        downloadSticker(ctx, url, message);
    }

    /** A tap on our Download row: the sticker / GIF or the voice clip the row was added for. */
    private static void downloadPending(Context ctx) {
        String sticker = pendingSticker;
        if (sticker != null) downloadStickerOnce(ctx, sticker, pendingStickerMessage);
        else if (FeatureFlags.enableVoiceDownload) downloadVoice(ctx, pendingVoice);
    }

    /**
     * Whether a tap is for the sticker / GIF our row was added for: the tap's own message (null on
     * paths that carry none) must be that message, or hold that same sticker.
     */
    private static boolean isPendingSticker(Object msg) {
        String url = pendingSticker;
        return url != null && (msg == null || msg == pendingStickerMessage || url.equals(stickerUrl(msg)));
    }

    private static void clearPending() {
        pendingVoice = null;
        pendingSticker = null;
        pendingStickerMessage = null;
    }

    private static final java.util.regex.Pattern URL_SIZE = java.util.regex.Pattern.compile("[-.]w(\\d{2,5})h(\\d{2,5})");

    /** Pixel area named in a CDN URL's cache key ("...-w1080h1920..."), or 0 when it has none. */
    private static long urlArea(String url) {
        java.util.regex.Matcher m = URL_SIZE.matcher(url);
        long best = 0;
        while (m.find()) best = Math.max(best, Long.parseLong(m.group(1)) * Long.parseLong(m.group(2)));
        return best;
    }

    /** The message's Media (its photo or video), found by its stable class name. */
    private static Object mediaOf(Object message) {
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Object[]> q = new ArrayDeque<>();
        q.add(new Object[]{message, 0});
        while (!q.isEmpty() && seen.size() < 300) {
            Object[] e = q.poll();
            Object o = e[0];
            int d = (int) e[1];
            if (o == null || !seen.add(o)) continue;
            if (o.getClass().getName().equals(MEDIA)) return o;
            if (d >= 3) continue;
            String n = o.getClass().getName();
            if (!n.startsWith("X.") && !n.startsWith("com.instagram.")) continue;
            for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass())
                for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(k)) {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive() || f.getType() == String.class) continue;
                    try { f.setAccessible(true); q.add(new Object[]{f.get(o), d + 1}); } catch (Throwable ignored) {}
                }
        }
        return null;
    }

    /** Saves a DM photo/video (every item of a carousel) like posts: username in the name and
     *  the username subfolder, at the best quality the downloader finds. */
    private static void downloadDmMedia(Context ctx, Object media) {
        String user = FeedVideoDownloadHook.extractUsernameFromMediaObject(media);
        if (user == null || user.isEmpty()) user = voiceUsername(null);
        String id = null;
        try {
            Object v = media.getClass().getMethod("getId").invoke(media);
            if (v instanceof String sid && !sid.isEmpty()) id = sid.split("_")[0];
        } catch (Throwable ignored) {}
        final String fUser = user, fId = id != null ? id : String.valueOf(System.currentTimeMillis());
        ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_downloading), android.widget.Toast.LENGTH_SHORT).show();
        FeedVideoDownloadHook.executor.submit(() -> {
            try {
                List<String> urls = FeedVideoDownloadHook.extractAllUrlsFromMedia(ctx, media);
                // a single photo: IG's own URL is sized for the screen; take the largest version
                if (urls.size() == 1 && !FeedVideoDownloadHook.isVideoUrl(urls.get(0))) {
                    Object[] big = StoryDownloadHook.largestImage(media);
                    long igArea = urlArea(urls.get(0));
                    ModuleLog.detail(TAG + "DM photo: largest=" + (big != null ? big[1] : null) + " ig=" + igArea);
                    if (big != null && igArea > 0 && (int) big[1] > igArea) urls.set(0, (String) big[0]);
                }
                ModuleLog.detail(TAG + "DM media: " + urls.size() + " item(s), user=" + fUser);
                if (urls.isEmpty()) throw new IllegalStateException("no media URL");
                boolean delegated = false, anyVideo = false;
                for (int i = 0; i < urls.size(); i++) {
                    String u = urls.get(i);
                    boolean video = FeedVideoDownloadHook.isVideoUrl(u);
                    anyVideo |= video;
                    String itemId = urls.size() > 1 ? fId + "_" + (i + 1) : fId;
                    delegated |= FeedVideoDownloadHook.downloadAndSave(ctx, u,
                            FeedVideoDownloadHook.buildFilename(fUser, "dm", itemId, video), video, fUser);
                }
                int saved = anyVideo ? R.string.ig_toast_video_saved : R.string.ig_toast_photo_saved;
                if (!delegated) FeedVideoDownloadHook.mainHandler.post(() -> ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx,
                        I18n.t(ctx, saved), android.widget.Toast.LENGTH_SHORT).show());
            } catch (Throwable t) {
                ModuleLog.error(TAG + "Could not save the chat photo or video: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                FeedVideoDownloadHook.mainHandler.post(() -> ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx,
                        I18n.t(ctx, R.string.ig_toast_download_failed, String.valueOf(t.getMessage())), android.widget.Toast.LENGTH_SHORT).show());
            }
        });
    }

    private static String actionName(Object row) {
        Field primary = primaryActionField;
        if (primary != null) {
            try {
                return primary.get(row) instanceof Enum<?> e ? e.name() : null;
            } catch (Throwable ignored) {
                return null;
            }
        }
        try {
            Field f = ps.reso.instaeclipse.utils.core.SafeReflect.fields(row.getClass())[0];
            for (Field g : ps.reso.instaeclipse.utils.core.SafeReflect.fields(row.getClass())) {
                if (Modifier.isStatic(g.getModifiers()) || !g.getType().isEnum()) continue;
                g.setAccessible(true);
                Object v = g.get(row);
                // the action enum is the constructor's 3rd param type; its values have names like REPLY
                if (v instanceof Enum<?> e && e.getDeclaringClass() == (saveMediaAction != null ? saveMediaAction.getClass() : e.getDeclaringClass())) {
                    String n = e.name();
                    if (n.equals(n.toUpperCase()) && n.length() > 2) return n;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static boolean hasAction(Object[] args, String name) {
        for (Object a : args) if (a instanceof Enum<?> e && name.equals(e.name())) return true;
        return false;
    }

    private static Object recentMessage() {
        return System.currentTimeMillis() - lastMessageAt < 15000 ? lastMessage : null;
    }

    /** Our injected row ("Download" / "Translate with app"), styled like an existing row. */
    private static Object buildRow(Constructor<?> ctor, Object template, Context ctx, String label, String iconName, String carrier) {
        try {
            Class<?>[] p = ctor.getParameterTypes();
            if (saveMediaAction == null) {
                for (Object v : p[2].getEnumConstants()) if (CARRIER.equals(((Enum<?>) v).name())) saveMediaAction = v;
            }
            if (saveMediaAction == null) return null;
            Object style = null;
            Integer icon = null;
            for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(template.getClass())) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                if (f.getType() == p[1] && style == null) style = f.get(template);
                if (f.getType() == Integer.class && icon == null) icon = (Integer) f.get(template);
            }
            Context c = ctx != null ? ctx : ps.reso.instaeclipse.mods.ui.UIHookManager.getCurrentActivity();
            if (c != null) {
                @SuppressWarnings("DiscouragedApi")
                int id = c.getResources().getIdentifier(iconName, "drawable", c.getPackageName());
                if (id != 0) icon = id;
            }
            ctor.setAccessible(true);
            Object action = saveMediaAction;
            for (Object v : p[2].getEnumConstants()) if (carrier.equals(((Enum<?>) v).name())) action = v;
            return ctor.newInstance(null, style, action, null, null, icon, null, label, null, null, null);
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "Could not add a row to this message menu: " + t);
            return null;
        }
    }

    private static boolean downloadEnabled() {
        return FeatureFlags.enableVoiceDownload || FeatureFlags.enableStickerDownload;
    }

    /**
     * The image of a sticker or GIF message, found by named classes:
     * - Giphy stickers and GIFs: DirectAnimatedMedia holds a GifUrlImpl with the "url" (GIF),
     *   "webp" and "mp4" versions. The GIF keeps the sticker's transparency and plays everywhere.
     * - Sticker-pack stickers: DirectStoreSticker holds TypedImageUrls, each an ImageUrl with
     *   getUrl / getWidth / getHeight; the largest is taken.
     */
    static String stickerUrl(Object message) {
        if (animatedMediaClass == null || gifUrlClass == null) return null;
        try {
            Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            ArrayDeque<Object[]> q = new ArrayDeque<>();
            q.add(new Object[]{message, 0});
            while (!q.isEmpty() && seen.size() < 300) {
                Object[] e = q.poll();
                Object o = e[0];
                int d = (int) e[1];
                if (o == null || !seen.add(o)) continue;
                if (storeStickerClass != null && storeStickerClass.isInstance(o)) {
                    String store = storeStickerUrl(o);
                    if (store != null) return store;
                    continue;
                }
                if (animatedMediaClass.isInstance(o)) {
                    String best = null;
                    for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(animatedMediaClass)) {
                        if (f.getType() != gifUrlClass || Modifier.isStatic(f.getModifiers())) continue;
                        f.setAccessible(true);
                        Object gif = f.get(o);
                        if (gif == null) continue;
                        for (Field g : ps.reso.instaeclipse.utils.core.SafeReflect.fields(gifUrlClass)) {
                            if (g.getType() != String.class || Modifier.isStatic(g.getModifiers())) continue;
                            g.setAccessible(true);
                            if (!(g.get(gif) instanceof String u) || !u.startsWith("http")) continue;
                            String path = u.split("\\?", 2)[0].toLowerCase(Locale.ROOT);
                            if (path.endsWith(".gif")) return u;
                            if (best == null) best = u;
                        }
                    }
                    return best;
                }
                if (d >= 3) continue;
                String n = o.getClass().getName();
                if (!n.startsWith("X.") && !n.startsWith("com.instagram.")) continue;
                for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                    for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(k)) {
                        if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive() || f.getType() == String.class) continue;
                        try { f.setAccessible(true); q.add(new Object[]{f.get(o), d + 1}); } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable t) {
            ModuleLog.detail(TAG + "sticker url: " + t);
        }
        return null;
    }

    /** The largest image of a sticker-pack sticker. */
    private static String storeStickerUrl(Object sticker) throws ReflectiveOperationException {
        if (typedImageUrlClass == null || imageUrlClass == null) return null;
        String best = null;
        long bestArea = -1;
        for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(storeStickerClass)) {
            if (f.getType() != typedImageUrlClass || Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true);
            Object typed = f.get(sticker);
            if (typed == null) continue;
            String type = null;
            Object image = null;
            for (Field g : ps.reso.instaeclipse.utils.core.SafeReflect.fields(typedImageUrlClass)) {
                if (Modifier.isStatic(g.getModifiers())) continue;
                g.setAccessible(true);
                if (g.getType() == String.class) type = (String) g.get(typed);
                else if (imageUrlClass.isAssignableFrom(g.getType())) image = g.get(typed);
            }
            if (image == null) continue;
            String url = (String) imageUrlClass.getMethod("getUrl").invoke(image);
            long area = (long) (int) imageUrlClass.getMethod("getWidth").invoke(image)
                    * (int) imageUrlClass.getMethod("getHeight").invoke(image);
            ModuleLog.probe(TAG + "store sticker: " + type + " " + area + " " + url);
            if (url != null && url.startsWith("http") && area > bestArea) { best = url; bestArea = area; }
        }
        return best;
    }

    /** Saves a sticker / GIF like DM media: username in the name and the username subfolder. */
    private static void downloadSticker(Context ctx, String url, Object message) {
        String user = voiceUsername(message);
        String id = String.valueOf(System.currentTimeMillis());
        // a .jpg name for a GIF is corrected to .gif once the file's signature is read
        String name = FeedVideoDownloadHook.buildFilename(user, "sticker", id, false);
        ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_downloading), android.widget.Toast.LENGTH_SHORT).show();
        FeedVideoDownloadHook.executor.submit(() -> {
            try {
                boolean delegated = FeedVideoDownloadHook.downloadAndSave(ctx, url, name, false, user);
                if (!delegated) FeedVideoDownloadHook.mainHandler.post(() -> ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx,
                        I18n.t(ctx, R.string.ig_toast_photo_saved), android.widget.Toast.LENGTH_SHORT).show());
            } catch (Throwable t) {
                ModuleLog.error(TAG + "Could not save the sticker: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                FeedVideoDownloadHook.mainHandler.post(() -> ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx,
                        I18n.t(ctx, R.string.ig_toast_download_failed, String.valueOf(t.getMessage())), android.widget.Toast.LENGTH_SHORT).show());
            }
        });
    }

    /** audio_src of the message's voice clip, or null when it isn't a voice message. */
    static String audioUrl(Object message) {
        if (audioMapper == null || audioInfoClass == null) return null;
        if (voiceType != null) return audioUrlOfMedia(ownVoiceMedia(message));
        // Fallback (voice payload anchor missing): this walk can also reach a quoted message.
        try {
            Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            ArrayDeque<Object[]> q = new ArrayDeque<>();
            q.add(new Object[]{message, 0});
            while (!q.isEmpty() && seen.size() < 300) {
                Object[] e = q.poll();
                Object o = e[0];
                int d = (int) e[1];
                if (o == null || !seen.add(o)) continue;
                if (audioInfoClass.isInstance(o)) {
                    Object map = audioMapper.invoke(null, o);
                    Object src = map instanceof Map<?, ?> m ? m.get("audio_src") : null;
                    if (src instanceof String s && s.startsWith("http")) return s;
                    continue;
                }
                if (o.getClass().getName().equals(MEDIA)) {
                    for (Method m : ps.reso.instaeclipse.utils.core.SafeReflect.publicMethods(o.getClass())) {
                        if (m.getParameterCount() == 0 && m.getReturnType() == audioInfoClass) {
                            try { q.add(new Object[]{m.invoke(o), d + 1}); } catch (Throwable ignored) {}
                        }
                    }
                    continue;
                }
                if (d >= 3) continue;
                String n = o.getClass().getName();
                if (!n.startsWith("X.") && !n.startsWith("com.instagram.")) continue;
                for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                    for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(k)) {
                        if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive() || f.getType() == String.class) continue;
                        try { f.setAccessible(true); q.add(new Object[]{f.get(o), d + 1}); } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable t) {
            ModuleLog.detail(TAG + "audio url: " + t);
        }
        return null;
    }

    /** audio_src through the Media's AudioInfoIntf getter (Media.A0b on 446). */
    private static String audioUrlOfMedia(Object media) {
        if (media == null) return null;
        try {
            for (Method m : ps.reso.instaeclipse.utils.core.SafeReflect.publicMethods(media.getClass())) {
                if (m.getParameterCount() != 0 || m.getReturnType() != audioInfoClass) continue;
                Object info;
                try { info = m.invoke(media); } catch (Throwable ignored) { continue; }
                if (info == null) continue;
                Object map = audioMapper.invoke(null, info);
                Object src = map instanceof Map<?, ?> mp ? mp.get("audio_src") : null;
                if (src instanceof String s && s.startsWith("http")) return s;
            }
        } catch (Throwable t) {
            ModuleLog.detail(TAG + "audio url: " + t);
        }
        return null;
    }
}
