package ps.reso.instaeclipse.mods.media;

import android.content.Context;
import android.widget.Toast;

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

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.download.DownloadEngine;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.toast.IeToast;

public final class CommentMediaDownload {

    private static final String TAG = "(InstaEclipse | CopyComment): ";
    private static final String GIPHY = "com.instagram.api.schemas.CommentGiphyMediaInfoIntf";
    private static final String GIPHY_PREFIX = "com.instagram.api.schemas.CommentGiphyMedia";
    private static final String OVERLAY = "com.instagram.api.schemas.MediaOverlayPayloadSchemaIntf";
    private static final String MEDIA = "com.instagram.feed.media.Media";

    private static final Map<Class<?>, Method[]> GETTERS = new ConcurrentHashMap<>();
    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    private CommentMediaDownload() {}

    public static final class Found {
        public final boolean gif;
        final Object source;
        Found(boolean gif, Object source) { this.gif = gif; this.source = source; }
    }

    public static Object[] comment(Object model) {
        if (model == null) return null;
        for (Class<?> c = model.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Class<?> i : c.getInterfaces()) if (getters(i)[0] != null) return new Object[]{i, model};
        }
        for (Class<?> c = model.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || !f.getType().isInterface()
                        || getters(f.getType())[0] == null) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(model);
                    if (v != null) return new Object[]{f.getType(), v};
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    public static Found find(Class<?> iface, Object comment) {
        Method[] g = getters(iface);
        Object giphy = call(g[0], comment);
        if (giphy != null) return log(new Found(true, giphy), g[0]);
        Object info = call(g[1], comment);
        Object media = info != null ? call(g[2], info) : null;
        if (media != null && !FeedVideoDownloadHook.isMediaVideo(media)) return log(new Found(false, media), g[1]);
        return null;
    }

    public static void save(Context ctx, Found found, String commenter) {
        String url = found.gif ? gifUrl(found.source) : photoUrl(ctx, found.source);
        if (url == null) {
            ModuleLog.error(TAG + "Could not save the comment " + (found.gif ? "GIF" : "photo") + ": no download link found");
            IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_download_failed, "no URL"), Toast.LENGTH_SHORT).show();
            return;
        }
        logOnce("url:" + found.gif + ":" + host(url), "media URL host " + host(url));
        IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_comment_gif_downloading), Toast.LENGTH_SHORT).show();
        String filename = FeedVideoDownloadHook.buildFilename(commenter,
                found.gif ? "comment_gif" : "comment_photo", null, false);
        Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        FeedVideoDownloadHook.executor.submit(() -> {
            try {
                boolean delegated = FeedVideoDownloadHook.downloadAndSave(app, url, filename, false, commenter);
                if (!delegated) FeedVideoDownloadHook.mainHandler.post(() -> IeToast.makeText(ctx,
                        I18n.t(ctx, R.string.ig_toast_file_saved, filename), Toast.LENGTH_SHORT).show());
            } catch (Throwable t) {
                ModuleLog.error(TAG + "Could not save the comment media: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                FeedVideoDownloadHook.mainHandler.post(() -> IeToast.makeText(ctx,
                        I18n.t(ctx, R.string.ig_toast_download_failed, String.valueOf(t.getMessage())),
                        Toast.LENGTH_SHORT).show());
            }
        });
    }

    private static Method[] getters(Class<?> iface) {
        Method[] cached = GETTERS.get(iface);
        if (cached != null) return cached;
        Method[] g = new Method[3];
        try {
            for (Method m : iface.getMethods()) {
                if (m.getParameterCount() != 0 || Modifier.isStatic(m.getModifiers())) continue;
                Class<?> r = m.getReturnType();
                if (r.getName().equals(GIPHY)) { g[0] = m; continue; }
                if (r.isPrimitive() || r.isArray() || r.getName().startsWith("java.")) continue;
                Method media = null;
                boolean overlay = false;
                for (Method n : r.getMethods()) {
                    if (n.getParameterCount() != 0) continue;
                    String type = n.getReturnType().getName();
                    if (type.equals(MEDIA)) media = n;
                    else if (type.equals(OVERLAY)) overlay = true;
                }
                if (media != null && overlay) { g[1] = m; g[2] = media; }
            }
        } catch (Throwable ignored) {}
        GETTERS.put(iface, g);
        return g;
    }

    private static String gifUrl(Object giphy) {
        List<String> urls = new ArrayList<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Object[]> queue = new ArrayDeque<>();
        queue.add(new Object[]{giphy, 0});
        while (!queue.isEmpty() && seen.size() < 40) {
            Object[] next = queue.poll();
            Object value = next[0];
            int depth = (int) next[1];
            if (!seen.add(value)) continue;
            for (Class<?> type : giphyTypes(value.getClass())) {
                for (Method m : type.getMethods()) {
                    if (m.getParameterCount() != 0) continue;
                    if (m.getReturnType() == String.class && m.getName().equals("getUrl")) {
                        Object url = call(m, value);
                        if (url instanceof String s && s.startsWith("https://") && !urls.contains(s)) urls.add(s);
                    } else if (depth < 3 && m.getReturnType().getName().startsWith(GIPHY_PREFIX)) {
                        Object child = call(m, value);
                        if (child != null) queue.add(new Object[]{child, depth + 1});
                    }
                }
            }
        }
        return pickGifUrl(urls);
    }

    static String pickGifUrl(List<String> urls) {
        for (String u : urls) if (DownloadEngine.isAllowedMediaUrl(u) && !isGiphy(u)) return u;
        for (String u : urls) if (DownloadEngine.isAllowedMediaUrl(u)) return u;
        return urls.isEmpty() ? null : urls.get(0);
    }

    private static boolean isGiphy(String url) {
        String h = host(url);
        return h != null && (h.equals("giphy.com") || h.endsWith(".giphy.com"));
    }

    private static List<Class<?>> giphyTypes(Class<?> cls) {
        List<Class<?>> types = new ArrayList<>();
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Class<?> i : c.getInterfaces()) if (i.getName().startsWith(GIPHY_PREFIX)) types.add(i);
        }
        return types;
    }

    private static String photoUrl(Context ctx, Object media) {
        String url = FeedVideoDownloadHook.imageUrlFromMedia(ctx, media);
        if (url != null) return url;
        List<String> all = FeedVideoDownloadHook.extractAllUrlsFromMedia(ctx, media);
        return all.isEmpty() ? null : all.get(0);
    }

    private static Object call(Method m, Object target) {
        if (m == null || target == null) return null;
        try {
            return m.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Found log(Found found, Method via) {
        logOnce((found.gif ? "gif:" : "photo:") + via, (found.gif ? "GIF" : "photo") + " row via "
                + via.getDeclaringClass().getName() + "." + via.getName());
        return found;
    }

    private static void logOnce(String key, String message) {
        if (LOGGED.add(key)) ModuleLog.detail(TAG + message);
    }

    private static String host(String url) {
        try {
            String h = java.net.URI.create(url).getHost();
            return h != null ? h.toLowerCase(java.util.Locale.ROOT) : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
