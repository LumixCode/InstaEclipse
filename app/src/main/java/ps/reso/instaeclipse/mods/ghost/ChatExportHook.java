package ps.reso.instaeclipse.mods.ghost;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.mods.media.DownloadFiles;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.ghost.ThreadNames;
import ps.reso.instaeclipse.utils.ghost.UnsentIdentity;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.text.HtmlExport;
import ps.reso.instaeclipse.utils.text.TimestampFormat;
import ps.reso.instaeclipse.utils.users.UserUtils;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.Found;

public class ChatExportHook {

    private static final String TAG = "(IE|ChatExport) ";
    private static final String BTN_TAG = "ie_chat_export_btn";
    private static final String USER = "com.instagram.user.model.User";
    private static final String SESSION = "com.instagram.common.session.UserSession";
    private static final String DTK = "com.instagram.model.direct.DirectThreadKey";
    private static final String AR_EFFECT_SHARE = "com.instagram.direct.model.DirectAREffectShare";
    private static final String PREFS = "instaeclipse_chat_export";
    private static Method messagesMethod, serializer, factoryWriterMethod;
    private static Constructor<?> factoryConstructor;
    private static Class<?> entryClass;
    private static final Map<Class<?>, Optional<Method>> entryGetters = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Optional<Method>> lazyChecks = new ConcurrentHashMap<>();

    private static boolean holdsEffectShare(Class<?> message) {
        for (java.lang.reflect.Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(message)) {
            if (AR_EFFECT_SHARE.equals(f.getType().getName())) return true;
        }
        return false;
    }

    public void install(ClassLoader cl) {
        try {
            Class<?> store = null;
            for (Found md : DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                    .usingStrings("getUnseenMessageCount directMessage is null")))) {
                store = cl.loadClass(md.getClassName());
                break;
            }
            if (store == null) { ModuleLog.error(TAG + "Could not turn on chat export: not supported by this Instagram version (message store not found)"); return; }
            entryClass = store;
            for (Method m : ps.reso.instaeclipse.utils.core.SafeReflect.methods(store)) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 1 && p[0] == boolean.class && m.getReturnType() == java.util.ArrayList.class) messagesMethod = m;
            }
            Method firstSerializer = null;
            for (Found md : DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                    .usingStrings("timestamp_in_micro", "client_context").returnType("void").paramCount(2)))) {
                Method m = md.getMethodInstance(cl);
                if (!Modifier.isStatic(m.getModifiers())) continue;
                if (firstSerializer == null) firstSerializer = m;
                if (holdsEffectShare(m.getParameterTypes()[1])) { serializer = m; break; }
            }
            if (serializer == null) serializer = firstSerializer;
            if (serializer != null) {
                Class<?> gen = serializer.getParameterTypes()[0];
                for (Found md : DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                        .paramTypes("java.io.Writer").returnType(gen.getName())))) {
                    Method m = md.getMethodInstance(cl);
                    if (Modifier.isStatic(m.getModifiers())) continue;
                    try {
                        factoryConstructor = m.getDeclaringClass().getConstructor();
                        factoryWriterMethod = m;
                        break;
                    } catch (NoSuchMethodException ignored) {}
                }
            }
            if (messagesMethod != null) messagesMethod.setAccessible(true);
            ModuleLog.line(TAG + (ready() ? "✅ ready"
                    : "❌ Could not turn on chat export: not supported by this Instagram version (message reader incomplete)"));
            ModuleLog.detail(TAG + "store " + store.getName() + " messages=" + (messagesMethod != null)
                    + " serializer=" + (serializer != null) + " factory=" + (factoryConstructor != null ? factoryConstructor.getDeclaringClass().getName() : "none"));
            if (ready()) FeatureStatusTracker.setHooked("ChatExport");
        } catch (Throwable t) {
            ModuleLog.error(TAG + "Could not turn on chat export", t);
        }
    }

    static void inject(Activity a) {
        if (!FeatureFlags.chatExport || !ready() || ThreadHeaderMenu.has(a, "export")) return;
        ThreadHeaderMenu.add(a, "export", I18n.t(a, R.string.ig_chat_export), () -> ask(a));
    }

    static boolean ready() {
        return ChatExportLabels.ready(messagesMethod != null, serializer != null, factoryWriterMethod != null);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void ask(Activity a) {
        String threadId = openThread(a);
        Object session = threadId != null ? openChatSession(a) : null;
        SharedPreferences prefs = prefs(a);
        ps.reso.instaeclipse.utils.dialog.IeDialog dlg = new ps.reso.instaeclipse.utils.dialog.IeDialog(a)
                .title(I18n.t(a, R.string.ig_chat_export));
        android.widget.CheckBox box = dlg.checkBox(I18n.t(a, R.string.ig_chat_export_load_all), prefs.getBoolean("loadAll", true));
        android.widget.CheckBox mediaBox = dlg.checkBox(I18n.t(a, R.string.ig_chat_export_media), prefs.getBoolean("media", false));
        dlg.secondary(I18n.t(a, R.string.ig_dialog_cancel), null);
        dlg.primary(I18n.t(a, R.string.ig_chat_export), () -> {
            boolean loadAll = box.isChecked();
            boolean withMedia = mediaBox.isChecked();
            prefs.edit().putBoolean("loadAll", loadAll).putBoolean("media", withMedia).apply();
            if (loadAll && ready() && session != null) {
                JumpToFirstMessageHook.run(a, threadId, thread -> export(a, thread, session, withMedia));
            } else {
                export(a, threadId, session, withMedia);
            }
        });
        dlg.show();
    }

    private static String openThread(Activity a) {
        String tracked = UnsentThreadButtonHook.openChat(a);
        if (tracked == null) try { tracked = UnsentThreadButtonHook.openThread.get(); } catch (Throwable ignored) {}
        return tracked;
    }

    private static void export(Activity a, String threadId, Object session, boolean withMedia) {
        if (!ready()) {
            ps.reso.instaeclipse.utils.toast.IeToast.makeText(a, I18n.t(a, R.string.ig_chat_export_unsupported), Toast.LENGTH_LONG).show();
            return;
        }
        if (session == null) {
            ModuleLog.detail(TAG + "export thread=" + threadId + " session=false");
            ps.reso.instaeclipse.utils.toast.IeToast.makeText(a, I18n.t(a, R.string.ig_chat_export_none), Toast.LENGTH_SHORT).show();
            return;
        }
        final ChatExportLabels labels = labels(a);
        final SimpleDateFormat fmt = TimestampFormat.dialogDateFormat(a);
        ps.reso.instaeclipse.utils.toast.IeToast.makeText(a, I18n.t(a, R.string.ig_chat_export_working), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                Object entry = threadEntry(session, threadId);
                ModuleLog.detail(TAG + "export thread=" + threadId + " entry=" + (entry != null));
                if (entry == null) {
                    new Handler(Looper.getMainLooper()).post(() ->
                            ps.reso.instaeclipse.utils.toast.IeToast.makeText(a, I18n.t(a, R.string.ig_chat_export_none), Toast.LENGTH_SHORT).show());
                    return;
                }
                List<?> msgs = (List<?>) messagesMethod.invoke(entry, true);
                Object factory = factoryConstructor.newInstance();
                List<Object[]> rows = new ArrayList<>();
                Map<String, JSONObject> byId = new HashMap<>();
                Set<String> senderIds = new HashSet<>();
                for (Object m : msgs) {
                    JSONObject j = toJson(factory, m);
                    if (j == null) continue;
                    rows.add(new Object[]{m, j});
                    String id = j.optString("item_id", "");
                    if (!id.isEmpty()) byId.put(id, j);
                    String sender = j.optString("user_id", "");
                    if (!sender.isEmpty()) senderIds.add(sender);
                }
                Map<String, String> names = participants(entry, senderIds);
                String selfId = UnsentIdentity.account(session);
                String title = ThreadNames.resolve(threadId);
                if (title == null || title.isEmpty()) title = "chat";
                String user = chatUsername(rows, names, selfId);

                String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                String safe = DownloadFiles.safeSegment(user != null ? user : title, threadId);
                String folder = "chat_" + safe + "_" + stamp;
                String file = folder + ".html";

                Map<Integer, String[]> media = new HashMap<>();
                List<String> mUrls = new ArrayList<>(), mNames = new ArrayList<>(), mMimes = new ArrayList<>();
                if (withMedia) {
                    for (int i = 0; i < rows.size(); i++) {
                        JSONObject j = (JSONObject) rows.get(i)[1];
                        String[] mm = mediaForMessage(j, j.optString("item_type", ""));
                        if (mm == null || !mm[0].startsWith("https://")) continue;
                        String mime = mimeFor(mm[1], mm[2]);
                        String name = String.format(Locale.US, "%04d_%s%s", i + 1, mm[1], extForMime(mime));
                        media.put(i, new String[]{name, mm[1]});
                        mUrls.add(mm[0]); mNames.add(name); mMimes.add(mime);
                    }
                }

                SimpleDateFormat daySep = new SimpleDateFormat("EEEE, d MMM yyyy", Locale.getDefault());

                StringBuilder body = new StringBuilder();
                long first = 0, last = 0, lastDay = -1;
                Map<String, Integer> perUser = new LinkedHashMap<>();
                for (int i = 0; i < rows.size(); i++) {
                    Object[] r = rows.get(i);
                    JSONObject j = (JSONObject) r[1];
                    String uid = j.optString("user_id", "");
                    String who = names.getOrDefault(uid, uid);
                    perUser.merge(who, 1, Integer::sum);
                    long ts = timestampMs(j);
                    if (ts > 0) {
                        if (first == 0) first = ts;
                        last = ts;
                        long day = (ts + java.util.TimeZone.getDefault().getOffset(ts)) / 86400000L;
                        if (day != lastDay) { lastDay = day; body.append("<div class=date-sep><span>").append(HtmlExport.esc(daySep.format(new Date(ts)))).append("</span></div>"); }
                    }
                    boolean self = uid.equals(selfId);
                    JSONObject replied = repliedTo(r[0], j.optString("item_id", ""), byId, factory);
                    body.append(messageHtml(j, self, who, ts, replied, names, media.get(i), fmt, labels));
                }
                int n = rows.size();
                StringBuilder html = new StringBuilder();
                html.append("<!doctype html><html><head><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\"><title>")
                        .append(HtmlExport.esc(title)).append("</title><style>").append(CHAT_CSS).append("</style></head><body><div class=wrap>")
                        .append(ps.reso.instaeclipse.utils.core.ExportBrand.htmlBanner(labels.banner()))
                        .append("<div class=head><h1>").append(HtmlExport.esc(title))
                        .append(user != null && !user.equalsIgnoreCase(title) ? " <span class=sub>@" + HtmlExport.esc(user) + "</span>" : "").append("</h1><div class=sub>").append(HtmlExport.esc(labels.messages(n)));
                if (first > 0) html.append(" · ").append(fmt.format(new Date(first))).append(" – ").append(fmt.format(new Date(last)));
                html.append("</div><div class=chips>");
                for (Map.Entry<String, Integer> e : perUser.entrySet())
                    html.append("<span class=chip>").append(HtmlExport.esc(e.getKey())).append(" · ").append(e.getValue()).append("</span>");
                html.append("</div></div>").append(body)
                        .append(ps.reso.instaeclipse.utils.core.ExportBrand.htmlFooter()).append("</div></body></html>");

                final int count = n;
                final String page = html.toString();
                String saf = FeatureFlags.downloaderCustomUri;
                boolean delegated = false;
                if (saf != null && !saf.isEmpty()) {
                    try {
                        android.content.Intent it = new android.content.Intent();
                        it.setClassName("ps.reso.instaeclipse",
                                "ps.reso.instaeclipse.mods.media.DownloadSaveService");
                        it.putExtra("chatExportSubdir", "chats/" + folder);
                        it.putExtra("htmlFilename", file);
                        it.putExtra("htmlB64", android.util.Base64.encodeToString(
                                page.getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP));
                        it.putExtra("saveUri", saf);
                        it.putExtra("messageCount", count);
                        if (!mUrls.isEmpty()) {
                            it.putExtra("mediaUrls", mUrls.toArray(new String[0]));
                            it.putExtra("mediaNames", mNames.toArray(new String[0]));
                            it.putExtra("mediaMimes", mMimes.toArray(new String[0]));
                        }
                        delegated = ps.reso.instaeclipse.utils.ipc.ExportDelegate.delegate(a, it, TAG, () -> {
                            try { writeLocally(a, folder, file, page, count, mUrls, mNames, mMimes); }
                            catch (Throwable t) { exportFailed(a, t); }
                        });
                        if (!delegated) {
                            ModuleLog.detail(TAG + "outside the companion's limits (" + count + " msgs, " + mUrls.size()
                                    + " media, " + page.length() + " html chars), writing locally");
                        } else {
                            ModuleLog.line(TAG + "Exporting " + count + " messages and " + mUrls.size()
                                    + " media files in the InstaEclipse app to " + folder);
                        }
                    } catch (Throwable t) {
                        ModuleLog.detail(TAG + "delegate failed, writing locally: " + t);
                    }
                }

                if (!delegated) writeLocally(a, folder, file, page, count, mUrls, mNames, mMimes);
            } catch (Throwable t) {
                exportFailed(a, t);
            }
        }).start();
    }

    private static void writeLocally(Activity a, String folder, String file, String page, int count,
                                     List<String> mUrls, List<String> mNames, List<String> mMimes) throws Exception {
        int saved = 0;
        for (int i = 0; i < mUrls.size(); i++) {
            File tmp = null;
            try {
                tmp = File.createTempFile("ie_chat_media", null, a.getCacheDir());
                boolean ok;
                try (OutputStream fout = new FileOutputStream(tmp)) { ok = httpGet(mUrls.get(i), fout); }
                if (ok && tmp.length() > 0) {
                    try (InputStream in = new FileInputStream(tmp);
                         OutputStream mout = ps.reso.instaeclipse.mods.media.FeedVideoDownloadHook
                                 .openChatExportFile(a, folder, mNames.get(i), mMimes.get(i))) {
                        byte[] buf = new byte[32768];
                        int rd;
                        while ((rd = in.read(buf)) != -1) mout.write(buf, 0, rd);
                    }
                    saved++;
                }
            } catch (Throwable ignored) {
            } finally {
                //noinspection ResultOfMethodCallIgnored
                if (tmp != null) tmp.delete();
            }
        }
        try (OutputStream out = ps.reso.instaeclipse.mods.media.FeedVideoDownloadHook.openChatExportFile(a, folder, file, "text/html")) {
            out.write(page.getBytes(StandardCharsets.UTF_8));
        }
        final int fsaved = saved;
        ModuleLog.line(TAG + "Exported " + count + " messages and " + fsaved + " media files to " + folder);
        new Handler(Looper.getMainLooper()).post(() -> {
            if (fsaved > 0)
                ps.reso.instaeclipse.utils.toast.IeToast.makeText(a, I18n.t(a, R.string.ig_chat_export_done_media, count, fsaved, folder), Toast.LENGTH_LONG).show();
            else
                ps.reso.instaeclipse.utils.toast.IeToast.makeText(a, I18n.t(a, R.string.ig_chat_export_done, count, file), Toast.LENGTH_LONG).show();
        });
    }

    private static void exportFailed(Activity a, Throwable t) {
        ModuleLog.error(TAG + "Could not export the chat", t);
        String reason = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
        new Handler(Looper.getMainLooper()).post(() ->
                ps.reso.instaeclipse.utils.toast.IeToast.makeText(a, I18n.t(a, R.string.ig_unsent_export_failed, reason), Toast.LENGTH_SHORT).show());
    }

    private static boolean httpGet(String url, OutputStream out) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(30000);
            conn.connect();
            if (conn.getResponseCode() / 100 != 2) return false;
            try (InputStream in = conn.getInputStream()) {
                byte[] buf = new byte[32768];
                int r;
                while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
            }
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
        }
    }

    static String[] mediaForMessage(JSONObject j, String type) {
        Object payload = j.opt(type);
        if (!(payload instanceof JSONObject) && !"text".equals(type)) {
            Object m = j.opt("media");
            if (m != null) payload = m;
        }
        if (payload == null) payload = j;
        switch (type) {
            case "media": case "raven_media": case "visual_media": {
                boolean video = "2".equals(first(payload, "media_type")) || nodeFor(payload, "video_versions", 0) != null;
                if (video) {
                    String u = deepUrl(nodeFor(payload, "video_versions", 0), 0);
                    if (!u.isEmpty()) return new String[]{u, "video", ".mp4"};
                }
                String u = deepUrl(nodeFor(payload, "image_versions2", 0), 0);
                if (u.isEmpty()) u = deepUrl(payload, 0);
                if (!u.isEmpty()) return new String[]{u, "photo", ".jpg"};
                return null;
            }
            case "voice_media": {
                String u = first(payload, "audio_src");
                if (u.isEmpty()) u = deepUrl(nodeFor(payload, "audio", 0), 0);
                if (u.isEmpty()) u = deepUrl(payload, 0);
                if (!u.isEmpty()) return new String[]{u, "voice", extFromUrl(u, ".mp4")};
                return null;
            }
            case "animated_media": {
                String u = deepUrl(payload, 0);
                if (u.isEmpty()) return null;
                return new String[]{u, u.contains(".mp4") ? "video" : "gif", extFromUrl(u, ".gif")};
            }
            case "media_share": case "clip": case "felix_share":
            case "xma_media_share": case "xma_clip": case "xma_felix_share":
            case "story_share": case "xma_story_share":
            case "reel_share": case "xma_reel_share":
            case "xma_reel_mention": case "xma_story_mention":
            case "profile": case "xma_profile":
            case "link": case "xma_link": case "generic_xma": case "xma": {
                String u = deepUrl(nodeFor(payload, "image_versions2", 0), 0);
                if (u.isEmpty()) u = deepUrl(payload, 0);
                if (!u.isEmpty()) return new String[]{u, "preview", extFromUrl(u, ".jpg")};
                return null;
            }
            default:
                return null;
        }
    }

    private static String deepUrl(Object o, int d) {
        if (o == null || d > 6) return "";
        if (o instanceof JSONObject jo) {
            for (String k : new String[]{"url", "uri", "image_url", "audio_src", "playable_url", "src"}) {
                String v = jo.optString(k, "");
                if (v.startsWith("https://")) return v;
            }
            for (Iterator<String> it = jo.keys(); it.hasNext(); ) {
                String r = deepUrl(jo.opt(it.next()), d + 1);
                if (!r.isEmpty()) return r;
            }
        } else if (o instanceof JSONArray ja) {
            for (int i = 0; i < ja.length() && i < 15; i++) {
                String r = deepUrl(ja.opt(i), d + 1);
                if (!r.isEmpty()) return r;
            }
        }
        return "";
    }

    private static Object nodeFor(Object o, String key, int d) {
        if (o == null || d > 6) return null;
        if (o instanceof JSONObject jo) {
            Object direct = jo.opt(key);
            if (direct instanceof JSONObject || direct instanceof JSONArray) return direct;
            for (Iterator<String> it = jo.keys(); it.hasNext(); ) {
                Object r = nodeFor(jo.opt(it.next()), key, d + 1);
                if (r != null) return r;
            }
        } else if (o instanceof JSONArray ja) {
            for (int i = 0; i < ja.length() && i < 15; i++) {
                Object r = nodeFor(ja.opt(i), key, d + 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static String extFromUrl(String url, String fallback) {
        try {
            String p = url.split("\\?")[0];
            int dot = p.lastIndexOf('.'), slash = p.lastIndexOf('/');
            if (dot > slash && dot >= 0 && p.length() - dot <= 5) return p.substring(dot).toLowerCase(Locale.ROOT);
        } catch (Throwable ignored) {}
        return fallback;
    }

    private static String mimeFor(String kind, String ext) {
        switch (kind) {
            case "video": return "video/mp4";
            case "voice": return ".webm".equals(ext) ? "audio/webm" : "audio/mp4";
            case "gif":   return ".webp".equals(ext) ? "image/webp" : "image/gif";
            default:      return ".png".equals(ext) ? "image/png" : ".webp".equals(ext) ? "image/webp" : "image/jpeg";
        }
    }

    private static String extForMime(String mime) {
        switch (mime) {
            case "video/mp4":  return ".mp4";
            case "audio/mp4":  return ".m4a";
            case "audio/webm": return ".webm";
            case "image/gif":  return ".gif";
            case "image/webp": return ".webp";
            case "image/png":  return ".png";
            default:           return ".jpg";
        }
    }

    private static String mediaHtml(String rel, String kind) {
        switch (kind) {
            case "video": return "<video class=media controls preload=none src=\"" + rel + "\" onerror=\"this.remove()\"></video>";
            case "voice": return "<audio class=media controls preload=none src=\"" + rel + "\" onerror=\"this.remove()\"></audio>";
            default:      return "<img class=media loading=lazy src=\"" + rel + "\" onerror=\"this.remove()\">";
        }
    }

    private static Object payloadOf(JSONObject j, String type) {
        Object payload = j.opt(type);
        if (!(payload instanceof JSONObject) && !"text".equals(type)) { Object m = j.opt("media"); if (m != null) payload = m; }
        return payload;
    }

    private static String stripBrackets(String s) {
        s = s.trim();
        if (s.startsWith("[") && s.endsWith("]")) s = s.substring(1, s.length() - 1);
        return s;
    }

    private static String messageHtml(JSONObject j, boolean self, String who, long ts,
                                      JSONObject replied, Map<String, String> names, String[] mrel, SimpleDateFormat fmt,
                                      ChatExportLabels labels) {
        String type = j.optString("item_type", "");
        if (type.equals("action_log") || type.equals("video_call_event")
                || type.equals("placeholder") || type.equals("expired_placeholder")) {
            String t = stripBrackets(describe(j));
            return "<div class=sys><span>" + HtmlExport.esc(t) + (ts > 0 ? " · " + HtmlExport.esc(fmt.format(new Date(ts))) : "") + "</span></div>";
        }
        StringBuilder b = new StringBuilder();
        b.append("<div class=\"row").append(self ? " me" : "").append("\">");
        if (!self) b.append("<div class=av style=\"background:#").append(HtmlExport.avatarHex(who)).append("\">").append(HtmlExport.esc(HtmlExport.initial(who))).append("</div>");
        b.append("<div class=bub>");
        if (!self) b.append("<div class=nm>").append(HtmlExport.esc(who)).append("</div>");
        if (replied != null) {
            String ru = replied.optString("user_id", "");
            b.append("<div class=q><b>").append(HtmlExport.esc(names.getOrDefault(ru, ru))).append("</b> ")
             .append(HtmlExport.esc(clip(describe(replied), 100))).append("</div>");
        }
        b.append(contentHtml(j, type, mrel, self, labels));
        b.append(editHistory(j, fmt));
        b.append("<div class=meta>");
        if (ts > 0) b.append(HtmlExport.esc(fmt.format(new Date(ts))));
        if (j.optInt("edit_count", 0) > 0 || j.has("edit_history")) b.append("<span class=badge>").append(HtmlExport.esc(labels.edited())).append("</span>");
        if (j.optBoolean("is_pinned", false)) b.append("<span class=badge>").append(HtmlExport.esc(labels.pinned())).append("</span>");
        if (j.optBoolean("show_forward_attribution", false)) b.append("<span class=badge>").append(HtmlExport.esc(labels.forwarded())).append("</span>");
        b.append("</div>");
        String react = reactions(j, names);
        if (!react.isEmpty()) {
            b.append("<div class=react>");
            for (String part : react.split(", ")) b.append("<span class=rx>").append(HtmlExport.esc(part)).append("</span>");
            b.append("</div>");
        }
        return b.append("</div></div>").toString();
    }

    private static String contentHtml(JSONObject j, String type, String[] mrel, boolean self, ChatExportLabels labels) {
        String text = j.optString("text", "");
        switch (type) {
            case "like":
                return "<div class=like>❤️</div>";
            case "media": case "raven_media": case "visual_media": case "animated_media": case "voice_media": {
                StringBuilder s = new StringBuilder();
                if (mrel != null) s.append(mediaHtml(mrel[0], mrel[1]));
                else s.append("<div class=ph>").append(HtmlExport.esc(describe(j))).append("</div>");
                if (!text.isEmpty()) s.append("<div class=txt>").append(HtmlExport.esc(text)).append("</div>");
                return s.toString();
            }
            case "media_share": case "clip": case "felix_share":
            case "xma_media_share": case "xma_clip": case "xma_felix_share":
            case "story_share": case "xma_story_share":
                return sharedCard(j, type, mrel, text, false, labels);
            case "reel_share": case "xma_reel_share":
                return sharedCard(j, type, mrel, text, true, labels);
            case "xma_reel_mention": case "xma_story_mention": {
                String inner = "<div class=ci><div class=cl>" + HtmlExport.esc(labels.mention(self)) + "</div></div></div>";
                return mrel != null ? "<div class=card><img loading=lazy src=\"" + mrel[0] + "\" onerror=\"this.remove()\">" + inner : "<div class=card>" + inner;
            }
            case "profile": case "xma_profile": {
                String u = first(payloadOf(j, type), "username");
                return "<div class=card><div class=ci><div class=cl>" + HtmlExport.esc(labels.profile()) + "</div>"
                        + (u.isEmpty() ? "" : "<div class=cu>@" + HtmlExport.esc(u) + "</div>") + "</div></div>";
            }
            case "text":
                return "<div class=txt>" + HtmlExport.esc(text) + "</div>";
            default: {
                StringBuilder s = new StringBuilder();
                if (mrel != null) s.append(mediaHtml(mrel[0], mrel[1]));
                String d = text.isEmpty() ? describe(j) : text;
                if (!d.isEmpty()) s.append("<div class=txt>").append(HtmlExport.esc(d)).append("</div>");
                return s.toString();
            }
        }
    }

    private static String sharedCard(JSONObject j, String type, String[] mrel, String text, boolean storyReply,
                                     ChatExportLabels labels) {
        Object payload = payloadOf(j, type);
        String user = first(payload, "username");
        String caption = first(findObj(payload, "caption", 0), "text");
        String code = first(payload, "code");
        boolean reel = storyReply || "clips".equals(first(payload, "product_type")) || findObj(payload, "clips_metadata", 0) != null;
        String label = labels.shared(type, storyReply, reel);
        String link = code.isEmpty() ? "" : "https://www.instagram.com/" + (reel && !storyReply ? "reel/" : "p/") + code + "/";
        StringBuilder s = new StringBuilder();
        boolean hasPrev = mrel != null && "preview".equals(mrel[1]);
        s.append(link.isEmpty() ? "<div class=card>" : "<a class=card href=\"" + link + "\" target=_blank rel=noopener>");
        if (hasPrev) s.append("<img loading=lazy src=\"").append(mrel[0]).append("\" onerror=\"this.remove()\">");
        s.append("<div class=ci><div class=cl>").append(HtmlExport.esc(label)).append("</div>");
        if (!user.isEmpty()) s.append("<div class=cu>").append(HtmlExport.esc(labels.sharedBy(user, storyReply))).append("</div>");
        if (!caption.isEmpty()) s.append("<div class=cc>").append(HtmlExport.esc(clip(caption, 220))).append("</div>");
        s.append("</div>").append(link.isEmpty() ? "</div>" : "</a>");
        if (!text.isEmpty()) s.append("<div class=txt>").append(HtmlExport.esc(text)).append("</div>");
        return s.toString();
    }

    private static final String CHAT_CSS =
            ":root{--bg:#f4f4f6;--panel:#fff;--text:#0b0b0c;--sub:#8a8a92;--line:#e7e7ec;--other:#fff;--me:#3797f0;--meTx:#fff;--accent:#c13584}"
            + "@media(prefers-color-scheme:dark){:root{--bg:#0a0a0b;--panel:#161618;--text:#f2f2f3;--sub:#9a9aa0;--line:#262629;--other:#232326;--me:#3797f0;--meTx:#fff;--accent:#e06ebb}}"
            + "*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:15px/1.45 -apple-system,system-ui,Segoe UI,Roboto,sans-serif}"
            + ".wrap{max-width:760px;margin:0 auto;padding:16px}"
            + ".head{background:var(--panel);border:1px solid var(--line);border-radius:18px;padding:16px 18px;margin-bottom:8px}"
            + ".head h1{margin:0;font-size:21px;word-break:break-word}.head .sub{color:var(--sub);font-size:13px;margin-top:4px}"
            + ".chips{display:flex;gap:8px;flex-wrap:wrap;margin-top:12px}"
            + ".chip{background:var(--bg);border:1px solid var(--line);border-radius:999px;padding:4px 12px;font-size:13px;color:var(--sub)}"
            + ".date-sep{text-align:center;margin:18px 0 8px}.date-sep span{background:var(--panel);border:1px solid var(--line);color:var(--sub);font-size:12px;border-radius:999px;padding:4px 12px}"
            + ".row{display:flex;gap:8px;margin:8px 0;align-items:flex-end}.row.me{flex-direction:row-reverse}"
            + ".av{flex:none;width:30px;height:30px;border-radius:50%;display:flex;align-items:center;justify-content:center;color:#fff;font-weight:700;font-size:13px}"
            + ".bub{max-width:78%;background:var(--other);border:1px solid var(--line);border-radius:18px;padding:9px 13px;overflow-wrap:anywhere}"
            + ".row.me .bub{background:var(--me);color:var(--meTx);border-color:transparent}"
            + ".nm{font-weight:700;font-size:12.5px;color:var(--accent);margin-bottom:3px}"
            + ".txt{white-space:normal}.ph{opacity:.8;font-style:italic}"
            + ".meta{font-size:11px;color:var(--sub);margin-top:5px;display:flex;gap:6px;flex-wrap:wrap;align-items:center}"
            + ".row.me .meta{color:rgba(255,255,255,.82);justify-content:flex-end}"
            + ".badge{font-size:10.5px;padding:1px 7px;border-radius:999px;background:var(--bg);border:1px solid var(--line);color:var(--sub)}"
            + ".row.me .badge{background:rgba(255,255,255,.2);border-color:transparent;color:#fff}"
            + ".q{border-left:3px solid var(--accent);padding:4px 9px;margin:0 0 6px;font-size:13px;color:var(--sub);background:var(--bg);border-radius:9px}"
            + ".row.me .q{background:rgba(255,255,255,.16);color:rgba(255,255,255,.92);border-left-color:rgba(255,255,255,.65)}"
            + ".media{display:block;max-width:100%;max-height:420px;border-radius:14px;margin:3px 0}audio.media{width:min(260px,70vw);height:40px}"
            + ".react{display:flex;gap:4px;flex-wrap:wrap;margin-top:5px}.row.me .react{justify-content:flex-end}"
            + ".rx{font-size:12px;background:var(--panel);border:1px solid var(--line);border-radius:999px;padding:1px 8px;color:var(--text)}"
            + ".card{display:block;text-decoration:none;color:inherit;border:1px solid var(--line);border-radius:14px;overflow:hidden;margin:3px 0;background:var(--bg)}"
            + ".row.me .card{background:rgba(255,255,255,.14);border-color:rgba(255,255,255,.28)}"
            + ".card img{display:block;width:100%;max-height:280px;object-fit:cover}.card .ci{padding:9px 12px}"
            + ".card .cl{font-size:11px;text-transform:uppercase;letter-spacing:.4px;color:var(--accent);font-weight:800}"
            + ".row.me .card .cl{color:#fff}.card .cu{font-weight:700;font-size:13px;margin-top:2px}"
            + ".card .cc{font-size:12.5px;color:var(--sub);margin-top:3px}.row.me .card .cc{color:rgba(255,255,255,.86)}"
            + ".sys{text-align:center;color:var(--sub);font-size:12.5px;margin:12px 0}.sys span{background:var(--panel);border:1px solid var(--line);border-radius:999px;padding:4px 12px}"
            + ".e{font-size:12px;opacity:.78;margin-top:4px;border-left:2px solid var(--line);padding-left:8px}.row.me .e{border-left-color:rgba(255,255,255,.5)}"
            + ".like{font-size:30px;line-height:1.1}";

    private static long timestampMs(JSONObject j) {
        try { return Long.parseLong(j.optString("timestamp", "0")) / 1000L; } catch (Throwable t) { return 0; }
    }

    private static String clip(String s, int max) {
        s = s.replace('\n', ' ');
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    static String describe(JSONObject j) {
        String text = j.optString("text", "");
        String type = j.optString("item_type", "");
        Object payload = j.opt(type);
        if (!(payload instanceof JSONObject) && !type.equals("text")) payload = j.opt("media");
        String extra = switch (type) {
            case "text" -> "";
            case "like" -> "❤️";
            case "link" -> first(payload, "text", "link_url");
            case "media" -> mediaKind(payload, false);
            case "raven_media", "visual_media" -> disappearing(j.opt("visual_media") != null ? j.opt("visual_media") : payload);
            case "voice_media" -> "[voice message" + duration(payload) + "]";
            case "animated_media" -> "[GIF]";
            case "media_share", "clip", "felix_share", "xma_media_share", "xma_clip", "xma_felix_share" ->
                    shared(payload, "post");
            case "story_share", "xma_story_share" -> shared(payload, "story");
            case "reel_share", "xma_reel_share" -> ("[story reply] " + first(payload, "text")).trim();
            case "xma_reel_mention", "xma_story_mention" -> "[mentioned you in a story]";
            case "profile", "xma_profile" -> {
                String u = first(payload, "username");
                yield u.isEmpty() ? "[shared profile]" : "[shared profile] @" + u;
            }
            case "generic_xma", "xma", "xma_link" -> {
                String t = first(payload, "title_text", "header_title_text", "target_url");
                yield t.isEmpty() ? "[shared content]" : "[shared] " + t;
            }
            case "action_log" -> "[" + first(payload, "description") + "]";
            case "video_call_event" -> "[" + first(payload, "description") + "]";
            case "placeholder", "expired_placeholder" -> "[" + first(payload, "message", "title") + "]";
            default -> {
                String t = first(payload, "title_text", "text", "description", "target_url");
                yield type.isEmpty() ? "" : "[" + type.replace('_', ' ') + "]" + (t.isEmpty() ? "" : " " + t);
            }
        };
        if (text.isEmpty()) return extra.isEmpty() ? "[" + (type.isEmpty() ? "message" : type) + "]" : extra;
        if (extra.isEmpty() || type.equals("link") || extra.equals(text)) return text;
        return extra + "\n" + text;
    }

    private static String mediaKind(Object payload, boolean once) {
        String kind = "2".equals(first(payload, "media_type")) ? "video" : "photo";
        return "[" + (once ? "disappearing " : "") + kind + "]";
    }

    private static String disappearing(Object visual) {
        String s = mediaKind(visual, true);
        String state = visual instanceof JSONObject v && v.optJSONObject("expiring_media_action_summary") != null
                ? v.optJSONObject("expiring_media_action_summary").optString("type", "") : "";
        if (state.startsWith("RAVEN_")) state = state.substring(6);
        return state.isEmpty() ? s : s.substring(0, s.length() - 1) + " · " + state.toLowerCase(Locale.ROOT) + "]";
    }

    private static String duration(Object payload) {
        try {
            double ms = Double.parseDouble(first(payload, "duration"));
            return String.format(Locale.US, ", %.1fs", ms / 1000.0);
        } catch (Throwable t) { return ""; }
    }

    private static String editHistory(JSONObject j, SimpleDateFormat fmt) {
        JSONArray h = j.optJSONArray("edit_history");
        if (h == null || h.length() == 0) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < h.length(); i++) {
            JSONObject e = h.optJSONObject(i);
            if (e == null) continue;
            long ts = e.optLong("timestamp", 0);
            b.append("<div class=e>✎ ").append(HtmlExport.esc(e.optString("body", "")));
            if (ts > 0) b.append(" <span class=t>").append(fmt.format(new Date(ts))).append("</span>");
            b.append("</div>");
        }
        return b.toString();
    }

    private static String shared(Object payload, String what) {
        String user = first(payload, "username");
        String caption = first(findObj(payload, "caption", 0), "text");
        String code = first(payload, "code");
        boolean reel = "clips".equals(first(payload, "product_type")) || findObj(payload, "clips_metadata", 0) != null;
        StringBuilder b = new StringBuilder("[shared ").append(reel && what.equals("post") ? "reel" : what);
        if (!user.isEmpty()) b.append(" by @").append(user);
        b.append("]");
        if (!code.isEmpty()) b.append(" https://www.instagram.com/").append(reel ? "reel/" : "p/").append(code).append("/");
        if (!caption.isEmpty()) b.append("\n").append(clip(caption, 300));
        return b.toString();
    }

    private static String first(Object o, String... keys) {
        for (String k : keys) { String v = find(o, k, 0); if (v != null && !v.isEmpty()) return v; }
        return "";
    }

    private static String find(Object o, String key, int d) {
        if (o == null || d > 6) return null;
        if (o instanceof JSONObject jo) {
            Object v = jo.opt(key);
            if (v != null && v != JSONObject.NULL && !(v instanceof JSONObject) && !(v instanceof JSONArray)) return String.valueOf(v);
            for (Iterator<String> it = jo.keys(); it.hasNext(); ) {
                String r = find(jo.opt(it.next()), key, d + 1);
                if (r != null && !r.isEmpty()) return r;
            }
        } else if (o instanceof JSONArray ja) {
            for (int i = 0; i < ja.length() && i < 20; i++) {
                String r = find(ja.opt(i), key, d + 1);
                if (r != null && !r.isEmpty()) return r;
            }
        }
        return null;
    }

    private static Object findObj(Object o, String key, int d) {
        if (!(o instanceof JSONObject jo) || d > 6) return null;
        if (jo.opt(key) instanceof JSONObject v) return v;
        for (Iterator<String> it = jo.keys(); it.hasNext(); ) {
            Object r = findObj(jo.opt(it.next()), key, d + 1);
            if (r != null) return r;
        }
        return null;
    }

    private static String reactions(JSONObject j, Map<String, String> names) {
        JSONObject r = j.optJSONObject("reactions");
        if (r == null) return "";
        List<String> out = new ArrayList<>();
        JSONArray emojis = r.optJSONArray("emojis");
        if (emojis != null) for (int i = 0; i < emojis.length(); i++) {
            JSONObject e = emojis.optJSONObject(i);
            if (e == null) continue;
            String uid = e.optString("sender_id", "");
            out.add(e.optString("emoji", "❤️") + " " + names.getOrDefault(uid, uid));
        }
        JSONArray likes = r.optJSONArray("likes");
        if (out.isEmpty() && likes != null) for (int i = 0; i < likes.length(); i++) {
            JSONObject e = likes.optJSONObject(i);
            String uid = e != null ? e.optString("sender_id", "") : "";
            out.add("❤️ " + names.getOrDefault(uid, uid));
        }
        return String.join(", ", out);
    }

    private static JSONObject repliedTo(Object message, String ownId, Map<String, JSONObject> byId, Object factory) {
        Class<?> msgClass = serializer.getParameterTypes()[1];
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        seen.add(message);
        ArrayDeque<Object[]> q = new ArrayDeque<>();
        addFields(q, message, 1);
        while (!q.isEmpty() && seen.size() < 150) {
            Object[] e = q.poll();
            Object o = e[0];
            int d = (int) e[1];
            if (o == null || !seen.add(o)) continue;
            if (o instanceof String s) {
                if (!s.equals(ownId) && byId.containsKey(s)) return byId.get(s);
                continue;
            }
            if (msgClass.isInstance(o)) {
                JSONObject j = toJson(factory, o);
                if (j != null && !ownId.equals(j.optString("item_id", ""))) return j;
                continue;
            }
            if (d >= 2) continue;
            String n = o.getClass().getName();
            if (n.startsWith("X.") || n.startsWith("com.instagram.")) addFields(q, o, d + 1);
        }
        return null;
    }

    private static void addFields(ArrayDeque<Object[]> q, Object o, int depth) {
        for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass())
            for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(k)) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                try { f.setAccessible(true); q.add(new Object[]{f.get(o), depth}); } catch (Throwable ignored) {}
            }
    }

    private static JSONObject toJson(Object factory, Object message) {
        try {
            if (!serializer.getParameterTypes()[1].isInstance(message)) return null;
            StringWriter sw = new StringWriter();
            Object gen = factoryWriterMethod.invoke(factory, sw);
            serializer.invoke(null, gen, message);
            if (gen instanceof java.io.Flushable f) f.flush();
            return new JSONObject(sw.toString());
        } catch (Throwable t) {
            return null;
        }
    }

    private static ChatExportLabels labels(Context c) {
        return new ChatExportLabels(new ChatExportLabels.Text() {
            @Override public String get(ChatExportLabels.Label label, Object... args) {
                return I18n.t(c, resource(label), args);
            }

            @Override public String count(ChatExportLabels.Label label, int n) {
                try {
                    return I18n.moduleResources(c).getQuantityString(resource(label), n, n);
                } catch (Throwable t) {
                    return String.valueOf(n);
                }
            }
        });
    }

    private static int resource(ChatExportLabels.Label label) {
        return switch (label) {
            case BANNER -> R.string.ig_chat_export_banner;
            case MESSAGES -> R.plurals.ig_chat_export_messages;
            case EDITED -> R.string.ig_chat_export_edited;
            case PINNED -> R.string.ig_chat_export_pinned;
            case FORWARDED -> R.string.ig_chat_export_forwarded;
            case STORY_REPLY -> R.string.ig_chat_export_story_reply;
            case REPLY_TO -> R.string.ig_chat_export_reply_to;
            case SHARED_STORY -> R.string.ig_chat_export_shared_story;
            case SHARED_REEL -> R.string.ig_chat_export_shared_reel;
            case SHARED_POST -> R.string.ig_chat_export_shared_post;
            case SHARED_PROFILE -> R.string.ig_chat_export_shared_profile;
            case MENTION_BY_YOU -> R.string.ig_chat_export_mention_by_you;
            case MENTION_OF_YOU -> R.string.ig_chat_export_mention_of_you;
        };
    }

    private static Object call(Object target, String name) {
        if (target == null) return null;
        try {
            Method method = HideChatsHook.noArgMethod(target.getClass(), name);
            return method == null ? null : method.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    public static Object openChatSession(Activity a) {
        Object manager = call(a, "getSupportFragmentManager");
        Object session = fragmentSession(manager, true);
        return session != null ? session : fragmentSession(manager, false);
    }

    private static Object fragmentSession(Object manager, boolean chatOnly) {
        if (!(call(manager, "getFragments") instanceof List<?> fragments)) return null;
        for (int i = fragments.size() - 1; i >= 0; i--) {
            Object fragment = fragments.get(i);
            Object nested = fragmentSession(call(fragment, "getChildFragmentManager"), chatOnly);
            if (nested != null) return nested;
            if (!Boolean.TRUE.equals(call(fragment, "isResumed"))) continue;
            if (chatOnly && !"direct_thread".equals(call(fragment, "getModuleName"))) continue;
            Object session = call(fragment, "getSession");
            if (session != null && SESSION.equals(session.getClass().getName())) return session;
        }
        return null;
    }

    private static Object threadEntry(Object session, String threadId) throws ReflectiveOperationException {
        Object key = Class.forName(DTK, false, entryClass.getClassLoader())
                .getConstructor(String.class, String.class, List.class).newInstance(threadId, null, null);
        List<Map<?, ?>> nested = new ArrayList<>();
        for (Object value : fieldValues(session)) {
            if (value instanceof Map<?, ?> scope) {
                Object entry = entryIn(scope, key);
                if (entry != null) return entry;
            } else if (walkable(value)) {
                for (Object inner : fieldValues(value)) if (inner instanceof Map<?, ?> scope) nested.add(scope);
            }
        }
        for (Map<?, ?> scope : nested) {
            Object entry = entryIn(scope, key);
            if (entry != null) return entry;
        }
        return null;
    }

    private static Object entryIn(Map<?, ?> scope, Object key) throws ReflectiveOperationException {
        Object[] values;
        try {
            values = scope.values().toArray();
        } catch (RuntimeException e) {
            return null;
        }
        for (Object value : values) {
            Object scoped = initialized(value);
            Method getter = scoped == null ? null : entryGetter(scoped.getClass());
            if (getter == null) continue;
            Object entry = getter.invoke(scoped, key);
            if (entry != null) return entry;
        }
        return null;
    }

    private static Object initialized(Object value) {
        if (value == null) return null;
        Method get = HideChatsHook.noArgMethod(value.getClass(), "getValue");
        if (get == null || get.getReturnType() != Object.class) return value;
        Method check = lazyCheck(value.getClass());
        try {
            return check != null && Boolean.TRUE.equals(check.invoke(value)) ? get.invoke(value) : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static Method lazyCheck(Class<?> type) {
        return lazyChecks.computeIfAbsent(type, ignored -> {
            for (Class<?> k = type; k != null && k != Object.class; k = k.getSuperclass()) {
                for (Class<?> lazy : k.getInterfaces()) {
                    Method check = null;
                    boolean hasValue = false;
                    int checks = 0;
                    for (Method m : ps.reso.instaeclipse.utils.core.SafeReflect.methods(lazy)) {
                        if (Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 0) continue;
                        if (m.getName().equals("getValue")) hasValue = true;
                        else if (m.getReturnType() == boolean.class) {
                            check = m;
                            checks++;
                        }
                    }
                    if (hasValue && checks == 1) {
                        check.setAccessible(true);
                        return Optional.of(check);
                    }
                }
            }
            return Optional.empty();
        }).orElse(null);
    }

    private static Method entryGetter(Class<?> type) {
        return entryGetters.computeIfAbsent(type, ignored -> {
            Method found = null;
            for (Method m : ps.reso.instaeclipse.utils.core.SafeReflect.methods(type)) {
                if (Modifier.isStatic(m.getModifiers()) || m.getReturnType() != entryClass) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 1 || !p[0].getName().equals(DTK)) continue;
                if (found != null) return Optional.empty();
                found = m;
            }
            if (found != null) found.setAccessible(true);
            return Optional.ofNullable(found);
        }).orElse(null);
    }

    private static boolean walkable(Object o) {
        if (o == null) return false;
        String n = o.getClass().getName();
        return (n.startsWith("X.") || n.startsWith("com.instagram.")) && !n.equals(SESSION);
    }

    private static List<Object> fieldValues(Object o) {
        List<Object> out = new ArrayList<>();
        for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass())
            for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(k)) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive() || f.getType() == String.class) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(o);
                    if (v != null) out.add(v);
                } catch (Throwable ignored) {}
            }
        return out;
    }

    private static Object[] items(Collection<?> c) {
        try {
            return c.toArray();
        } catch (RuntimeException e) {
            return new Object[0];
        }
    }

    private static String chatUsername(List<Object[]> rows, Map<String, String> names, String me) {
        java.util.LinkedHashSet<String> others = new java.util.LinkedHashSet<>();
        for (String id : names.keySet()) if (!id.equals(me)) others.add(id);
        if (others.size() == 1) return names.get(others.iterator().next());
        if (others.isEmpty()) return me != null ? names.get(me) : null;
        java.util.LinkedHashSet<String> senders = new java.util.LinkedHashSet<>();
        for (Object[] r : rows) {
            String id = ((JSONObject) r[1]).optString("user_id", "");
            if (!id.isEmpty() && !id.equals(me) && names.containsKey(id)) senders.add(id);
        }
        return senders.size() == 1 && me != null ? names.get(senders.iterator().next()) : null;
    }

    private static Map<String, String> participants(Object entry, Set<String> senders) {
        Map<String, String> names = new HashMap<>();
        for (Object holder : fieldValues(entry)) {
            if (addUser(holder, names) || !walkable(holder)) continue;
            for (Object thread : fieldValues(holder)) {
                if (addUser(thread, names) || !walkable(thread)) continue;
                for (Object value : fieldValues(thread)) {
                    if (value instanceof Collection<?> users) for (Object user : items(users)) addUser(user, names);
                    else addUser(value, names);
                }
            }
        }
        if (!names.keySet().containsAll(senders)) reachableUsers(entry, names);
        return names;
    }

    private static boolean addUser(Object o, Map<String, String> names) {
        if (o == null || !o.getClass().getName().equals(USER)) return false;
        String u = UserUtils.callUsernameGetter(o);
        String id = userId(o);
        if (u != null && id != null) names.putIfAbsent(id, u);
        return true;
    }

    private static void reachableUsers(Object root, Map<String, String> out) {
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Object[]> q = new ArrayDeque<>();
        q.add(new Object[]{root, 0});
        while (!q.isEmpty() && seen.size() < 3000) {
            Object[] e = q.poll();
            Object o = e[0];
            int d = (int) e[1];
            if (o == null || !seen.add(o)) continue;
            if (o instanceof List<?> l) { if (d < 4) for (Object it : items(l)) q.add(new Object[]{it, d + 1}); continue; }
            if (o.getClass().getName().equals(SESSION) || addUser(o, out)) continue;
            if (d >= 4) continue;
            String n = o.getClass().getName();
            if (!n.startsWith("X.") && !n.startsWith("com.instagram.")) continue;
            for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass())
                for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(k)) {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive() || f.getType() == String.class) continue;
                    try { f.setAccessible(true); q.add(new Object[]{f.get(o), d + 1}); } catch (Throwable ignored) {}
                }
        }
    }

    private static String userId(Object user) {
        for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(user.getClass())) {
            if (f.getType() != String.class || Modifier.isStatic(f.getModifiers()) || !Modifier.isFinal(f.getModifiers())) continue;
            try { f.setAccessible(true); Object v = f.get(user); if (v instanceof String s && s.matches("\\d+")) return s; } catch (Throwable ignored) {}
        }
        return null;
    }
}
