package ps.reso.instaeclipse.mods.media;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.Toast;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.download.DownloadEngine;
import ps.reso.instaeclipse.utils.download.DownloadMessages;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.text.CopyFeedback;
import ps.reso.instaeclipse.utils.users.UserUtils;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.Found;

public class FeedVideoDownloadHook {

    private static final String MEDIA = "com.instagram.feed.media.Media";
    private static Class<?> mediaClass;
    private static final List<Method> resolvedVideoVersionsGetters = new ArrayList<>();
    private static Method resolvedMediaTypeMethod;
    private static Method   methodImageUrl;
    private static Method   extendedImageUrlGetUrl;
    private static Method   carouselMediaGetter;
    private static Method   hasAudioGetter;
    private static Method   musicMetadataGetter;
    private static Method   clipsMetadataGetter;
    private static Method   dashManifestGetter;

    private static Class<?> userClass;
    private static Method   mediaAuthorGetter;

    private static final class UrlEntry {
        final String url; final long time;
        UrlEntry(String u) { url = u; time = System.currentTimeMillis(); }
    }
    private static final int MAX_URLS = 200;
    private static final Deque<UrlEntry> videoUrlBuffer = new ArrayDeque<>();
    static final ExecutorService executor    = MediaHttp.newDownloadExecutor(4);
    static final Handler         mainHandler = new Handler(Looper.getMainLooper());

    public void install(ClassLoader classLoader) {
        try {
            mediaClass = classLoader.loadClass("com.instagram.feed.media.Media");
            Class<?> mediaExtKt = classLoader.loadClass("com.instagram.feed.media.MediaExtKt");
            Method extVariant = null;
            for (Method m : ps.reso.instaeclipse.utils.core.SafeReflect.methods(mediaExtKt)) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 2 || !Modifier.isStatic(m.getModifiers())
                        || !"android.content.Context".equals(p[0].getName()) || p[1] != mediaClass) continue;
                if (m.getReturnType() == String.class) {
                    m.setAccessible(true);
                    methodImageUrl = m;
                    break;
                }
                if (extVariant == null
                        && "com.instagram.model.mediasize.ExtendedImageUrl".equals(m.getReturnType().getName())) {
                    extVariant = m;
                }
            }
            if (methodImageUrl == null && extVariant != null) {
                extendedImageUrlGetUrl = extVariant.getReturnType().getMethod("getUrl");
                extVariant.setAccessible(true);
                methodImageUrl = extVariant;
            }
            ModuleLog.detail("(IE|DL) image url method=" + (methodImageUrl == null ? "not found"
                    : methodImageUrl.getName() + (extendedImageUrlGetUrl != null ? " (ExtendedImageUrl)" : "")));
        } catch (Throwable ignored) {}
    }

    private static String probeCdnUrlViaStringMethods(Object obj) {
        if (obj == null) return null;
        Class<?> cls = obj.getClass();
        while (cls != null && cls != Object.class) {
            String cn = cls.getName();
            if (!cn.startsWith("X.") && !cn.startsWith("com.instagram.") && !cn.startsWith("com.facebook.")) break;
            for (Method m : ps.reso.instaeclipse.utils.core.SafeReflect.methods(cls)) {
                if (m.getParameterCount() != 0 || m.getReturnType() != String.class) continue;
                try {
                    m.setAccessible(true);
                    Object r = m.invoke(obj);
                    if (r instanceof String s && isCdnMediaUrl(s)) return s;
                } catch (Throwable ignored) {}
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    static void collectAllVideoUrls(Object obj, List<String> out, Set<Object> visited, int depth) {
        if (obj == null || depth > 7 || !visited.add(obj)) return;

        if (looksLikeVideoVersion(obj)) {
            addVideoVersionUrl(obj, out);
            return;
        }

        if (obj instanceof Map<?, ?> map) {
            for (Object value : map.values())
                collectAllVideoUrls(value, out, visited, depth + 1);
            return;
        }
        if (obj instanceof Iterable<?> iterable) {
            for (Object value : iterable)
                collectAllVideoUrls(value, out, visited, depth + 1);
            return;
        }
        if (obj instanceof Object[] array) {
            for (Object value : array)
                collectAllVideoUrls(value, out, visited, depth + 1);
            return;
        }

        Class<?> cls = obj.getClass();
        String cn = cls.getName();
        if (!cn.startsWith("X.") && !cn.startsWith("com.instagram.") && !cn.startsWith("com.facebook.")) return;

        while (cls != null && cls != Object.class) {
            for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(cls)) {
                try {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    Object val = f.get(obj);
                    if (val == null) continue;
                    String vcn = val.getClass().getName();
                    if (val instanceof Iterable<?> || val instanceof Map<?, ?>
                            || val instanceof Object[] || vcn.startsWith("X.")
                            || vcn.startsWith("com.instagram.")
                            || vcn.startsWith("com.facebook."))
                        collectAllVideoUrls(val, out, visited, depth + 1);
                } catch (Throwable ignored) {}
            }
            cls = cls.getSuperclass();
        }

        cls = obj.getClass();
        while (cls != null && cls != Object.class) {
            for (Method method : ps.reso.instaeclipse.utils.core.SafeReflect.methods(cls)) {
                if (method.getParameterCount() != 0
                        || !List.class.isAssignableFrom(method.getReturnType())) continue;
                try {
                    method.setAccessible(true);
                    Object result = method.invoke(obj);
                    if (!(result instanceof List<?> items) || items.isEmpty()) continue;
                    if (isVideoVersionsList(items)) {
                        for (Object item : items) addVideoVersionUrl(item, out);
                    } else {
                        collectAllVideoUrls(items, out, visited, depth + 1);
                    }
                } catch (Throwable ignored) {}
            }
            cls = cls.getSuperclass();
        }
    }

    private static boolean looksLikeVideoVersion(Object item) {
        if (item == null) return false;
        String name = item.getClass().getName().toLowerCase(Locale.US);
        return name.contains("videoversion") || name.contains("video_version")
                || name.contains("videourl");
    }

    private static boolean isVideoVersionsList(List<?> items) {
        int checked = 0;
        for (Object item : items) {
            if (item == null) continue;
            checked++;
            if (!looksLikeVideoVersion(item)) return false;
        }
        return checked > 0;
    }

    private static void addVideoVersionUrl(Object item, List<String> out) {
        String url = videoUrlFromVersionObject(item);
        if (url == null) return;
        int h = ForceReelQualityHook.heightOf(item);
        if (h > 0) VIDEO_HEIGHT.put(url, h);
        rememberVideoUrl(url);
        if (!out.contains(url)) out.add(url);
    }

    static String bestVideoUrlFromMedia(Object media) {
        List<String> all = new ArrayList<>();
        collectVideoUrlsFromDictionary(media, all);
        if (all.isEmpty() && isDirectVideoVersionsOwner(media)) return null;
        if (all.isEmpty()) {
            Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            collectAllVideoUrls(media, all, visited, 0);
        }
        return pickVideoUrl(media, all);
    }

    private static String ownVideoUrl(Object slide) {
        List<String> found = new ArrayList<>();
        for (Method getter : resolvedVideoVersionsGetters) {
            if (getter.getDeclaringClass().isInstance(slide)) {
                collectUrlsFromVideoVersionsMethod(slide, getter, found);
            }
        }
        return pickVideoUrl(slide, found);
    }

    private static String pickVideoUrl(Object media, List<String> all) {
        VideoUrlRanking.Pick pick = VideoUrlRanking.pick(all, VIDEO_HEIGHT);
        if (pick == null) return null;
        if (pick.height <= 0) return pick.url;
        String best = pick.url;
        int bestH = pick.height;
        String[] dash = DashAudio.bestVideo(dashManifest(media),
                android.os.Build.VERSION.SDK_INT < 34);
        int dashH = dash != null ? Integer.parseInt(dash[1]) : -1;
        if (dashH > bestH) DASH_UPGRADE.put(best, new String[]{dash[0], extractAudioUrl(media)});
        ModuleLog.probe("(IE|DL) video " + bestH + "p of " + all.size() + " version(s)"
                + (dashH > bestH ? ", DASH " + dashH + "p" : ""));
        return best;
    }

    private static final Map<String, String[]> DASH_UPGRADE = Collections.synchronizedMap(
            new java.util.LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String[]> e) { return size() > 50; }
            });

    private static final Map<String, Integer> VIDEO_HEIGHT = Collections.synchronizedMap(
            new java.util.LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Integer> e) { return size() > 300; }
            });

    static boolean isMediaVideo(Object media) {
        if (media == null || resolvedMediaTypeMethod == null) return false;
        try {
            Object target = resolvedMediaTypeMethod.getDeclaringClass().isInstance(media)
                    ? media
                    : MediaModelResolver.findObjectOfType(
                            media, resolvedMediaTypeMethod.getDeclaringClass(), 5);
            if (target == null) return false;
            return MediaTypeDetector.isVideoType(resolvedMediaTypeMethod.invoke(target));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isDirectVideoVersionsOwner(Object media) {
        if (media == null) return false;
        for (Method getter : resolvedVideoVersionsGetters) {
            if (getter.getDeclaringClass().isInstance(media)) return true;
        }
        return false;
    }

    private static void collectVideoUrlsFromDictionary(Object media, List<String> out) {
        for (Method getter : resolvedVideoVersionsGetters) {
            Object owner = getter.getDeclaringClass().isInstance(media)
                    ? media
                    : MediaModelResolver.findObjectOfType(media, getter.getDeclaringClass(), 7);
            if (owner != null) collectUrlsFromVideoVersionsMethod(owner, getter, out);
        }
    }

    private static void collectUrlsFromVideoVersionsMethod(Object dict, Method getter,
                                                            List<String> out) {
        try {
            Object result = ForceReelQualityHook.raw(() -> getter.invoke(dict));
            if (!(result instanceof List<?> items) || items.isEmpty()) return;

            List<String> found = new ArrayList<>();
            for (Object item : items) {
                String url = videoUrlFromVersionObject(item);
                if (url == null) continue;
                int h = ForceReelQualityHook.heightOf(item);
                if (h > 0) VIDEO_HEIGHT.put(url, h);
                found.add(url);
            }
            for (String url : found) {
                rememberVideoUrl(url);
                if (!out.contains(url)) out.add(url);
            }
        } catch (Throwable ignored) {}
    }

    private static String videoUrlFromVersionObject(Object item) {
        if (item == null) return null;
        String url = tryGetUrl(item);
        if (url != null && isCdnMediaUrl(url)) return url;
        return videoUrlFromStringFields(item);
    }

    private static String videoUrlFromStringFields(Object item) {
        if (item == null) return null;
        Class<?> cls = item.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(cls)) {
                if (f.getType() != String.class
                        || java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(item);
                    if (v instanceof String s && isCdnMediaUrl(s)) return s;
                } catch (Throwable ignored) {}
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    private static String tryGetUrl(Object obj) {
        if (obj == null) return null;
        try {
            Method m = obj.getClass().getMethod("getUrl");
            Object result = m.invoke(obj);
            return result instanceof String ? (String) result : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String pickBestImageUrl(List<String> images) {
        for (String url : images) {
            if (!url.contains("/s150x") && !url.contains("/s240x") &&
                !url.contains("/s320x") && !url.contains("/s480x") &&
                !url.contains("/s640x") && !url.contains("_s.jpg")) {
                return url;
            }
        }
        return images.get(0);
    }

    private static final int MAX_SCAN_DEPTH = 6;
    private static final int MAX_SCAN_URLS  = 20;

    private static void scanForCdnUrls(Object obj, List<String> out,
                                        int depth, Set<Object> visited) {
        if (obj == null || depth > MAX_SCAN_DEPTH || out.size() >= MAX_SCAN_URLS) return;
        if (!visited.add(obj)) return;

        Class<?> cls = obj.getClass();
        String cn = cls.getName();
        if (cn.startsWith("android.") || cn.startsWith("java.lang.")  ||
            cn.startsWith("java.util.concurrent.") || cn.startsWith("kotlin.")) return;

        String directUrl = tryGetUrl(obj);
        if (directUrl != null && isCdnMediaUrl(directUrl) && !out.contains(directUrl))
            out.add(directUrl);

        while (cls != null && cls != Object.class) {
            for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(cls)) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(obj);
                    if (val == null) continue;

                    if (val instanceof String s) {
                        if (isCdnMediaUrl(s) && !out.contains(s)) out.add(s);
                    } else if (val instanceof List<?> list) {
                        for (Object item : list) scanForCdnUrls(item, out, depth + 1, visited);
                    } else if (val instanceof Object[] arr) {
                        for (Object item : arr) scanForCdnUrls(item, out, depth + 1, visited);
                    } else {
                        String vcn = val.getClass().getName();
                        if (vcn.startsWith("X.")               ||
                            vcn.startsWith("com.instagram.")   ||
                            vcn.startsWith("com.facebook.")) {
                            scanForCdnUrls(val, out, depth + 1, visited);
                        }
                    }
                } catch (Throwable ignored) {}
            }
            cls = cls.getSuperclass();
        }
    }

    static void rememberVideoUrl(String url) {
        if (url == null || !isCdnMediaUrl(url)) return;
        synchronized (videoUrlBuffer) {
            if (!videoUrlBuffer.isEmpty() && videoUrlBuffer.peekFirst().url.equals(url)) return;
            videoUrlBuffer.removeIf(entry -> entry.url.equals(url));
            videoUrlBuffer.addFirst(new UrlEntry(url));
            while (videoUrlBuffer.size() > MAX_URLS) videoUrlBuffer.removeLast();
        }
    }

    private static boolean wasCapturedAsVideo(String url) {
        if (url == null) return false;
        synchronized (videoUrlBuffer) {
            for (UrlEntry entry : videoUrlBuffer) {
                if (entry.url.equals(url)) return true;
            }
        }
        return false;
    }

    public static void installVideoUrlCaptureHook(ClassLoader classLoader) {
        SelectedMediaAdapter.install(classLoader);
        resolvedVideoVersionsGetters.clear();
        resolvedVideoVersionsGetters.addAll(SelectedMediaAdapter.getters(MEDIA, "video_versions"));
        carouselMediaGetter = adapterGetter("carousel_media");
        dashManifestGetter = adapterGetter("video_dash_manifest");
        hasAudioGetter = adapterGetter("has_audio");
        mediaAuthorGetter = adapterGetter("user");
        ModuleLog.detail("(IE|DL) media getters: video_versions=" + resolvedVideoVersionsGetters.size()
                + " carousel=" + (carouselMediaGetter != null) + " manifest=" + (dashManifestGetter != null)
                + " has_audio=" + (hasAudioGetter != null) + " author=" + (mediaAuthorGetter != null));
        if (hasAudioGetter != null && dashManifestGetter != null) FeatureStatusTracker.setHooked("AudioDownload");
        else if (FeatureFlags.offerAudioDownload) FeatureStatusTracker.setBroken("AudioDownload");
        musicMetadataGetter = resolveMediaGetter(classLoader, "music_metadata");
        clipsMetadataGetter = resolveMediaGetter(classLoader, "clips_metadata");
        resolveMediaTypeMethod(classLoader);
        resolveUsernameGetter(classLoader);
    }

    private static Method adapterGetter(String key) {
        List<Method> found = SelectedMediaAdapter.getters(MEDIA, key);
        return found.size() == 1 ? found.get(0) : null;
    }

    static Boolean hasAudio(Object media) {
        Method getter = hasAudioGetter;
        if (media == null || getter == null || !getter.getDeclaringClass().isInstance(media)) return null;
        try {
            Object v = getter.invoke(media);
            return v instanceof Boolean ? (Boolean) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    static List<?> carouselChildren(Object media) {
        if (media == null || carouselMediaGetter == null) return null;
        try {
            Object owner = carouselMediaGetter.getDeclaringClass().isInstance(media) ? media
                    : MediaModelResolver.findObjectOfType(media, carouselMediaGetter.getDeclaringClass(), 5);
            Object r = owner != null ? carouselMediaGetter.invoke(owner) : null;
            return r instanceof List<?> l ? l : null;
        } catch (Throwable t) {
            return null;
        }
    }

    static String dashManifest(Object media) {
        Method getter = dashManifestGetter;
        if (media == null || getter == null) return null;
        try {
            Object target = getter.getDeclaringClass().isInstance(media) ? media
                    : MediaModelResolver.findObjectOfType(media, getter.getDeclaringClass(), 5);
            Object r = target != null ? getter.invoke(target) : null;
            return r instanceof String s ? s : null;
        } catch (Throwable t) {
            return null;
        }
    }

    static String extractAudioUrl(Object media) {
        Method getter = dashManifestGetter;
        if (media == null || getter == null) return null;
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
                if (target == null) return null;
            }
            Object manifest = getter.invoke(target);
            String audio = manifest instanceof String s ? DashAudio.audioUrl(s) : null;
            if (audio != null) return audio;
            List<?> children = carouselChildren(media);
            if (children != null) {
                for (Object child : children) {
                    if (child == null || child == media) continue;
                    String a = extractAudioUrl(child);
                    if (a != null) return a;
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    static Object[] audioSource(Object media) {
        return audioSource(media, media);
    }

    static Object[] audioSource(Object media, Object post) {
        if (!FeatureFlags.offerAudioDownload || media == null) return null;
        String dashAudio = extractAudioUrl(media);
        String videoForAudio = dashAudio == null ? bestVideoUrlFromMedia(media) : null;
        Boolean sound = hasAudio(media);
        ModuleLog.detail("(IE|DL|Audio) has_audio=" + sound + " dash=" + (dashAudio != null)
                + " video=" + (videoForAudio != null));
        String url = dashAudio != null ? dashAudio : videoForAudio;
        if (url != null && !Boolean.FALSE.equals(sound)) return new Object[]{url, dashAudio == null, 0L, -1L, false};
        Object[] music = musicSource(post);
        if (music == null && post != media) music = musicSource(media);
        return music;
    }

    static Object[] musicSource(Object media) {
        if (media == null) return null;
        for (Method getter : new Method[]{musicMetadataGetter, clipsMetadataGetter}) {
            if (getter == null || !getter.getDeclaringClass().isInstance(media)) continue;
            try {
                Object meta = getter.invoke(media);
                Object info = meta == null ? null : callByReturnType(meta, "com.instagram.api.schemas.MusicInfo");
                Object track = info == null ? null : callByReturnType(info, "com.instagram.api.schemas.TrackData");
                java.util.Map<?, ?> tm = fieldMap(track);
                if (tm == null) continue;
                Object url = tm.get("progressive_download_url");
                if (!(url instanceof String) || ((String) url).isEmpty()) url = tm.get("fast_start_progressive_download_url");
                if (!(url instanceof String) || ((String) url).isEmpty()) continue;
                long start = 0;
                java.util.Map<?, ?> cm = fieldMap(callByReturnType(info, "com.instagram.music.common.model.MusicConsumptionModel"));
                if (cm != null && cm.get("audio_asset_start_time_in_ms") instanceof Number) {
                    start = ((Number) cm.get("audio_asset_start_time_in_ms")).longValue();
                }
                long length = DashAudio.musicClipMs(cm);
                ModuleLog.detail("(IE|DL|Music) song=\"" + tm.get("title") + "\" by " + tm.get("display_artist")
                        + " start=" + start + "ms length=" + length + "ms");
                return new Object[]{url, true, start, length, true};
            } catch (Throwable t) {
                ModuleLog.detail("(IE|DL|Music) lookup: " + t);
            }
        }
        return null;
    }

    private static Object callByReturnType(Object obj, String typeName) {
        if (obj == null) return null;
        Class<?> type;
        try {
            type = Class.forName(typeName, false, obj.getClass().getClassLoader());
        } catch (Throwable t) {
            return null;
        }
        for (Method m : ps.reso.instaeclipse.utils.core.SafeReflect.publicMethods(obj.getClass())) {
            if (m.getParameterCount() != 0 || !type.isAssignableFrom(m.getReturnType())) continue;
            try {
                Object v = m.invoke(obj);
                if (v != null) return v;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static java.util.Map<?, ?> fieldMap(Object obj) {
        if (obj == null) return null;
        for (Method m : ps.reso.instaeclipse.utils.core.SafeReflect.publicMethods(obj.getClass())) {
            if (!java.util.Map.class.isAssignableFrom(m.getReturnType())) continue;
            try {
                Object r = null;
                if (m.getParameterCount() == 0) {
                    r = m.invoke(obj);
                } else if (m.getParameterCount() == 1 && !m.getParameterTypes()[0].isPrimitive()) {
                    java.lang.reflect.Constructor<?> c = m.getParameterTypes()[0].getDeclaredConstructor();
                    c.setAccessible(true);
                    r = m.invoke(obj, c.newInstance());
                }
                if (r instanceof java.util.Map) return (java.util.Map<?, ?>) r;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Method resolveMediaGetter(ClassLoader classLoader, String field) {
        try {
            for (Found md : DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                    .declaredClass("com.instagram.feed.media.Media")
                    .paramCount(0)
                    .usingEqStrings(List.of(field))))) {
                if (md.getName().startsWith("<")) continue;
                try {
                    Method m = md.getMethodInstance(classLoader);
                    if (m.getReturnType() == void.class) continue;
                    m.setAccessible(true);
                    ModuleLog.detail("(IE|DL|DexKit) " + field + " getter=" + m.getName());
                    return m;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            ModuleLog.detail("(IE|DL|DexKit) " + field + " getter failed: " + t);
        }
        return null;
    }

    static void downloadAudio(Context ctx, String url, String filename, String username) {
        downloadAudio(ctx, url, filename, username, false);
    }

    static void downloadAudio(Context ctx, String url, String filename, String username,
                              boolean extractFromVideo) {
        downloadAudio(ctx, url, filename, username, extractFromVideo, 0, -1);
    }

    static void downloadAudio(Context ctx, String url, String filename, String username,
                              boolean extractFromVideo, long trimStartMs, long trimDurationMs) {
        ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_downloading_audio), Toast.LENGTH_SHORT).show();
        downloadAudio(ctx, url, filename, username, extractFromVideo, trimStartMs, trimDurationMs, false);
    }

    private static void downloadAudio(Context ctx, String url, String filename, String username,
                              boolean extractFromVideo, long trimStartMs, long trimDurationMs, boolean local) {
        executor.submit(() -> {
            try {
                String uri = local ? "" : FeatureFlags.downloaderCustomUri.isEmpty()
                        ? readCompanionUri() : FeatureFlags.downloaderCustomUri;
                if (!uri.isEmpty()) {
                    android.content.Intent intent = new android.content.Intent();
                    intent.setClassName("ps.reso.instaeclipse",
                            "ps.reso.instaeclipse.mods.media.DownloadSaveService");
                    intent.putExtra("url", url);
                    intent.putExtra("filename", filename);
                    intent.putExtra("mimeType", "audio/mp4");
                    intent.putExtra("username", username);
                    intent.putExtra("extractAudio", extractFromVideo);
                    intent.putExtra("trimStartMs", trimStartMs);
                    intent.putExtra("trimDurationMs", trimDurationMs);
                    ps.reso.instaeclipse.utils.ipc.CompanionBridge.downloadLegacy(ctx, intent, error -> companionResult(ctx, error, "audio/mp4",
                            () -> downloadAudio(ctx, url, filename, username, extractFromVideo, trimStartMs, trimDurationMs, true)));
                    return;
                }
                File temp = File.createTempFile("ie_dl_", ".m4a", ctx.getCacheDir());
                File video = extractFromVideo ? File.createTempFile("ie_dv_", ".mp4", ctx.getCacheDir()) : null;
                try {
                    if (video != null) {
                        downloadToFileAndGetType(url, video);
                        DownloadSaveService.extractAudioTrack(video.getAbsolutePath(), temp.getAbsolutePath(),
                                trimStartMs * 1000, trimDurationMs > 0 ? trimDurationMs * 1000 : -1);
                    } else {
                        downloadToFileAndGetType(url, temp);
                    }
                    DownloadPublisher.save(ctx, temp, filename, "audio/mp4", username, new DownloadEngine.Cancellation());
                } finally {
                    //noinspection ResultOfMethodCallIgnored
                    temp.delete();
                    //noinspection ResultOfMethodCallIgnored
                    if (video != null) video.delete();
                }
                mainHandler.post(() -> ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_audio_saved), Toast.LENGTH_SHORT).show());
            } catch (Throwable e) {
                ModuleLog.error("(IE|DL|Audio) Could not save the audio: " + failureLog(e, url));
                mainHandler.post(() -> ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx,
                        DownloadMessages.failure(ctx, e), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private static void resolveMediaTypeMethod(ClassLoader classLoader) {
        try {
            if (DexKitCache.isCacheValid()) {
                Method cached = DexKitCache.loadMethod("MediaDownload_MediaType_v1", classLoader);
                if (cached != null) { cached.setAccessible(true); resolvedMediaTypeMethod = cached; return; }
            }
            List<Found> found = DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                    .declaredClass("com.instagram.feed.media.Media")
                    .paramCount(0)
                    .returnType("java.lang.Integer")
                    .usingEqStrings(List.of("media_type"))));
            if (found.size() == 1) {
                Method m = found.get(0).getMethodInstance(classLoader);
                m.setAccessible(true);
                resolvedMediaTypeMethod = m;
                DexKitCache.saveMethod("MediaDownload_MediaType_v1", m);
            }
            ModuleLog.detail("(IE|DL|DexKit) media_type getter="
                    + (resolvedMediaTypeMethod == null ? "not found" : resolvedMediaTypeMethod.getName()));
        } catch (Throwable t) {
            ModuleLog.detail("(IE|DL|DexKit) media_type getter failed: " + t);
        }
    }

    private static void resolveUsernameGetter(ClassLoader classLoader) {
        if (DexKitCache.isCacheValid()) {
            String cachedClassName = DexKitCache.loadString("UserClass");
            Method cachedGetter    = DexKitCache.loadMethod("UsernameGetter", classLoader);
            if (cachedClassName != null) {
                try {
                    userClass = classLoader.loadClass(cachedClassName);
                    if (cachedGetter != null) {
                        UserUtils.userUsernameGetter = cachedGetter;
                    }
                    return;
                } catch (Throwable ignored) {}
            }
        }

        try {
            List<Found> userMethods = DexKitQueryCache.methods(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .usingStrings("username_missing_during_update")));

            if (userMethods.isEmpty()) {
                ModuleLog.warn("(IE|DL|Username) Usernames may be missing from download file names: the user model was not found in this Instagram version");
                ModuleLog.detail("(IE|DL|Username) username_missing_during_update not found");
                return;
            }

            userClass = userMethods.get(0).getMethodInstance(classLoader).getDeclaringClass();
            DexKitCache.saveString("UserClass", userClass.getName());
            ModuleLog.detail("(IE|DL|Username) userClass=" + userClass.getName());

            try {
                List<Found> ugMethods = DexKitQueryCache.methods(FindMethod.create()
                        .matcher(MethodMatcher.create()
                                .declaredClass("com.instagram.user.model.User")
                                .returnType("java.lang.String")
                                .paramCount(0)
                                .usingNumbers(-265713450)));
                if (!ugMethods.isEmpty()) {
                    UserUtils.userUsernameGetter = ugMethods.get(0).getMethodInstance(classLoader);
                    UserUtils.userUsernameGetter.setAccessible(true);
                    DexKitCache.saveMethod("UsernameGetter", UserUtils.userUsernameGetter);
                    ModuleLog.detail("(IE|DL|Username) userUsernameGetter=" + UserUtils.userUsernameGetter.getName());
                } else {
                    ModuleLog.warn("(IE|DL|Username) Usernames may be missing from download file names: the username field was not found in this Instagram version");
                    ModuleLog.detail("(IE|DL|Username) userUsernameGetter not found via -265713450");
                }
            } catch (Throwable t) {
                ModuleLog.warn("(IE|DL|Username) Usernames may be missing from download file names: " + t);
            }
        } catch (Throwable t) {
            ModuleLog.warn("(IE|DL|Username) Usernames may be missing from download file names: " + t);
        }
    }

    static String buildFilename(String username, String type, String mediaId, boolean isVideo) {
        return DownloadNames.buildFilename(username, type, mediaId, isVideo,
                FeatureFlags.downloaderAddTimestamp, System.currentTimeMillis());
    }

    public static OutputStream openExport(Context ctx, String filename, String mime) throws Exception {
        return DownloadPublisher.openStream(ctx, filename, mime, "exports");
    }

    public static OutputStream openCommentsExport(Context ctx, String filename) throws Exception {
        return DownloadPublisher.openStream(ctx, filename, "text/html", "comments");
    }

    public static OutputStream openChatExportFile(Context ctx, String sub, String filename, String mime)
            throws Exception {
        String folder = "chats" + (sub == null || sub.isEmpty() ? "" : "/" + sub);
        return DownloadPublisher.openStream(ctx, filename, mime, folder);
    }

    static void saveFileToDestination(Context ctx, File tempFile, String filename,
                                      String mimeType, String username) throws Exception {
        DownloadPublisher.save(ctx, tempFile, filename, mimeType, username, new DownloadEngine.Cancellation());
    }

    public static void saveLocalFileToGallery(Context ctx, String localPath, String author,
                                              String id, boolean video, String okMsg, String failMsg) {
        executor.submit(() -> {
            try {
                File src = new File(localPath);
                if (!src.exists()) return;
                MediaTypeDetector.Result named = MediaTypeDetector.verified(
                        ps.reso.instaeclipse.utils.media.StoryCache.mimeOf(localPath, video),
                        buildFilename(author, "story", id, video));
                String mime = named.mimeType;
                saveFileToDestination(ctx, src, named.filename, mime, author);
                mainHandler.post(() -> ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, okMsg, android.widget.Toast.LENGTH_SHORT).show());
                if (hasCompanionDestination() && !DownloadPublisher.reachesChosenFolder(FeatureFlags.downloaderCustomPath, mime))
                    mainHandler.post(() -> ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx,
                            I18n.t(ctx, R.string.ig_dl_companion_fallback), Toast.LENGTH_LONG).show());
            } catch (Throwable t) {
                mainHandler.post(() -> ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, failMsg, android.widget.Toast.LENGTH_SHORT).show());
            }
        });
    }

    private static String readCompanionUri() {
        try {
            return ps.reso.instaeclipse.xposedcompat.CompanionPrefs.getString("downloaderCustomUri", "");
        } catch (Throwable t) {
            return "";
        }
    }

    static boolean downloadAndSave(Context ctx, String url, String filename,
                                   boolean isVideo, String username) throws Exception {
        String[] dash = isVideo ? DASH_UPGRADE.get(url) : null;
        return downloadCapturedOriginal(ctx, url, filename, isVideo, username, dash);
    }

    static boolean downloadCapturedOriginal(Context ctx, String url, String filename, boolean isVideo,
            String username, String[] dash) throws Exception {
        return downloadCapturedOriginal(ctx, url, filename, isVideo, username, dash, false);
    }

    private static boolean downloadCapturedOriginal(Context ctx, String url, String filename, boolean isVideo,
            String username, String[] dash, boolean local) throws Exception {
        String uri = local ? "" : FeatureFlags.downloaderCustomUri.isEmpty() ? readCompanionUri() : FeatureFlags.downloaderCustomUri;
        if (!uri.isEmpty()) {
            Runnable saveLocally = () -> executor.submit(() -> {
                try {
                    if (!downloadCapturedOriginal(ctx, url, filename, isVideo, username, dash, true))
                        mainHandler.post(() -> ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx,
                                I18n.t(ctx, R.string.ig_toast_file_saved, filename), Toast.LENGTH_SHORT).show());
                } catch (Throwable e) {
                    ModuleLog.error("(IE|DL) Could not save the download: " + failureLog(e, url));
                    mainHandler.post(() -> ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx,
                            DownloadMessages.failure(ctx, e), Toast.LENGTH_SHORT).show());
                }
            });
            if (dash != null) delegateUrlToCompanionApp(ctx, dash[0], dash[1], url, filename, true, username, saveLocally);
            else delegateUrlToCompanionApp(ctx, url, null, null, filename, isVideo, username, saveLocally);
            return true;
        }
        if (dash != null) {
            File tv = File.createTempFile("ie_dv_", ".mp4", ctx.getCacheDir());
            File ta = dash[1] != null ? File.createTempFile("ie_da_", ".mp4", ctx.getCacheDir()) : null;
            File merged = File.createTempFile("ie_dm_", ".mp4", ctx.getCacheDir());
            try {
                downloadToFile(dash[0], tv);
                File out = tv;
                if (ta != null) {
                    downloadToFile(dash[1], ta);
                    StreamMerge.mergeVideoAudio(tv.getAbsolutePath(), ta.getAbsolutePath(), merged.getAbsolutePath());
                    out = merged;
                }
                saveFileToDestination(ctx, out, MediaTypeDetector.withCorrectExtension(
                        filename, MediaTypeDetector.Kind.VIDEO), "video/mp4", username);
                return false;
            } catch (Throwable t) {
                ModuleLog.warn("(IE|DL) Could not download the highest quality; saving the version Instagram was playing instead");
                ModuleLog.detail("(IE|DL) DASH download failed, saving the progressive file: " + failureLog(t, dash[0]));
            } finally {
                //noinspection ResultOfMethodCallIgnored
                tv.delete();
                if (ta != null) //noinspection ResultOfMethodCallIgnored
                    ta.delete();
                //noinspection ResultOfMethodCallIgnored
                merged.delete();
            }
        }

        File temp = File.createTempFile("ie_dl_", ".bin", ctx.getCacheDir());
        try {
            String responseType = downloadToFileAndGetType(url, temp);
            MediaTypeDetector.Result detected = MediaTypeDetector.resolve(
                    temp, responseType, isVideo ? "video/mp4" : "image/jpeg", filename);
            ModuleLog.detail("(IE|DL|Type) requested=" + (isVideo ? "video" : "image")
                    + " response=" + responseType + " detected=" + detected.kind
                    + " file=" + detected.filename);
            saveFileToDestination(ctx, temp, detected.filename, detected.mimeType, username);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
        return false;
    }

    private static void delegateUrlToCompanionApp(Context ctx,
                                                   String url,
                                                   String audioUrl,
                                                   String fallbackUrl,
                                                   String filename, boolean isVideo,
                                                   String username, Runnable saveLocally) throws Exception {
        android.content.Intent intent = new android.content.Intent();
        if (fallbackUrl != null) intent.putExtra("fallbackUrl", fallbackUrl);
        intent.setClassName("ps.reso.instaeclipse",
                            "ps.reso.instaeclipse.mods.media.DownloadSaveService");
        intent.putExtra("url",      url);
        if (audioUrl != null) intent.putExtra("audioUrl", audioUrl);
        intent.putExtra("filename", filename);
        intent.putExtra("mimeType", isVideo ? "video/mp4" : "image/jpeg");
        intent.putExtra("username", username);
        ps.reso.instaeclipse.utils.ipc.CompanionBridge.downloadLegacy(ctx, intent, error -> companionResult(ctx, error,
                isVideo ? "video/mp4" : "image/jpeg", saveLocally));
        ModuleLog.detail("(IE|DL) Submitted companion download request");
    }

    private static void companionResult(Context ctx, String error, String mime, Runnable saveLocally) {
        if (error == null) return;
        if (saveLocally != null && SelectedMediaDownloads.companionUnreachable(error)) {
            SelectedMediaDownloads.savedLocally(ctx, mime);
            saveLocally.run();
            return;
        }
        mainHandler.post(() -> ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx,
                DownloadMessages.companionFailure(ctx, error), Toast.LENGTH_SHORT).show());
    }

    static List<String> collectCdnUrls(Object obj) {
        List<String> out = new ArrayList<>();
        scanForCdnUrls(obj, out, 0, Collections.newSetFromMap(new IdentityHashMap<>()));
        return out;
    }

    private static Object invokeImageUrl(Context ctx, Object media) throws Exception {
        Object r = methodImageUrl.invoke(null, ctx, media);
        if (r != null && extendedImageUrlGetUrl != null) r = extendedImageUrlGetUrl.invoke(r);
        return r;
    }

    static String imageUrlFromMedia(Context ctx, Object media) {
        if (methodImageUrl == null || ctx == null || media == null) return null;
        try {
            Object r = invokeImageUrl(ctx, media);
            return (r instanceof String s && isCdnMediaUrl(s)) ? s : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    static String originalImageFromSelected(Context ctx, Object media, boolean carouselChild) {
        String image = imageUrlFromMedia(ctx, media);
        if (image != null) return image;
        if (carouselChild) {
            String probed = probeCdnUrlViaStringMethods(media);
            if (probed != null) return probed;
        }
        List<String> scanned = collectCdnUrls(media);
        if (scanned.isEmpty()) return null;
        return carouselChild ? pickBestImageUrl(scanned) : scanned.get(0);
    }

    static boolean hasCompanionDestination() {
        return !FeatureFlags.downloaderCustomUri.isEmpty() || !readCompanionUri().isEmpty();
    }
    static OriginalMediaSelection.Plan originalPlan(Object media, String progressive) {
        if (progressive == null) return null;
        Integer height = VIDEO_HEIGHT.get(progressive);
        String[] dash = height != null && height > 0 ? DashAudio.bestVideo(dashManifest(media),
                android.os.Build.VERSION.SDK_INT < 34) : null;
        return dash != null && Integer.parseInt(dash[1]) > height
                ? new OriginalMediaSelection.Plan(dash[0], extractAudioUrl(media), progressive)
                : new OriginalMediaSelection.Plan(progressive, null, null);
    }

    public static List<String> extractAllUrlsFromMedia(Context ctx, Object media) {
        if (media == null) return new ArrayList<>();

        List<String> carousel = extractCarouselUrls(ctx, media);
        if (carousel != null && carousel.size() >= 2) return carousel;

        String videoUrl = bestVideoUrlFromMedia(media);
        if (videoUrl != null) return new ArrayList<>(List.of(videoUrl));

        if (isMediaVideo(media)) {
            List<String> mediaUrls = collectCdnUrls(media);
            for (String candidate : mediaUrls) {
                if (isVideoUrl(candidate)) {
                    rememberVideoUrl(candidate);
                    return new ArrayList<>(List.of(candidate));
                }
            }
            ModuleLog.detail("(IE|Post|DL) media is video but no video URL was resolved; "
                    + "refusing image cover fallback");
            return new ArrayList<>();
        }

        String imageUrl = imageUrlFromMedia(ctx, media);
        if (imageUrl != null) return new ArrayList<>(List.of(imageUrl));

        List<String> cdnUrls = collectCdnUrls(media);
        if (!cdnUrls.isEmpty()) return new ArrayList<>(List.of(cdnUrls.get(0)));

        return new ArrayList<>();
    }

    private static List<String> extractCarouselUrls(Context ctx, Object media) {
        if (carouselMediaGetter == null) return null;
        try {
            Object owner = carouselMediaGetter.getDeclaringClass().isInstance(media)
                    ? media
                    : MediaModelResolver.findObjectOfType(
                            media, carouselMediaGetter.getDeclaringClass(), 5);
            if (owner == null) return null;

            Object listObj = carouselMediaGetter.invoke(owner);
            if (!(listObj instanceof List<?> items) || items.size() < 2) return null;

            List<String> urls = new ArrayList<>();
            for (Object child : items) {
                if (child == null) continue;
                String v = ownVideoUrl(child);
                if (v == null && !isDirectVideoVersionsOwner(child)) v = bestVideoUrlFromMedia(child);
                if (v != null) { urls.add(v); continue; }
                String img = imageUrlFromMedia(ctx, child);
                if (img != null) { urls.add(img); continue; }
                if (methodImageUrl != null && ctx != null) {
                    try {
                        Object r = invokeImageUrl(ctx, child);
                        if (r instanceof String s && isCdnMediaUrl(s)) urls.add(s);
                    } catch (Throwable ignored) {}
                }
            }
            return urls;
        } catch (Throwable t) {
            ModuleLog.detail("(IE|Post) extractCarouselUrls: " + t);
            return null;
        }
    }

    static void copyLinkToClipboard(Context ctx, String url) {
        mainHandler.postDelayed(() -> {
            try {
                CopyFeedback.copy(ctx, ClipData.newPlainText("InstaEclipse", url), I18n.t(ctx, R.string.ig_copy_link_copied));
            } catch (Throwable t) {
                ModuleLog.error("(IE|Post) Could not copy the link: " + t);
            }
        }, 350);
    }

    @SuppressLint("DefaultLocale")
    static void showCopyLinkSheet(Context ctx, List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_copy_link_none), Toast.LENGTH_SHORT).show();
            return;
        }
        if (urls.size() == 1) { copyLinkToClipboard(ctx, urls.get(0)); return; }

        try {
            final int n = urls.size();
            CharSequence[] slides = new CharSequence[n];
            for (int i = 0; i < n; i++) slides[i] = I18n.t(ctx, R.string.ig_copy_link_slide, i + 1, n);
            ps.reso.instaeclipse.utils.dialog.IeDialog dlg = new ps.reso.instaeclipse.utils.dialog.IeDialog(ctx)
                    .title(I18n.t(ctx, R.string.ie_copy_media_link))
                    .message(I18n.t(ctx, R.string.ig_dl_carousel_subtitle, n))
                    .choices(slides, -1, idx -> copyLinkToClipboard(ctx, urls.get(idx)));
            dlg.primary(I18n.t(ctx, R.string.ig_copy_link_all, n), () -> {
                StringBuilder sb = new StringBuilder();
                for (String u : urls) sb.append(u).append('\n');
                final String allText = sb.toString().trim();
                mainHandler.postDelayed(() -> {
                    try {
                        CopyFeedback.copy(ctx, ClipData.newPlainText("InstaEclipse", allText),
                                I18n.t(ctx, R.string.ig_copy_link_copied_all, n));
                    } catch (Throwable t) {
                        ModuleLog.error("(IE|Post) Could not copy the links: " + t);
                    }
                }, 350);
            });
            dlg.show();

        } catch (Throwable t) {
            ModuleLog.error("(IE|Post) Could not show the link choices", t);
        }
    }

    static String extractUsernameFromMediaObject(Object media) {
        if (media == null) return null;
        if (mediaAuthorGetter != null && mediaClass != null && mediaClass.isInstance(media)) {
            try {
                Object user = mediaAuthorGetter.invoke(media);
                String name = UserUtils.callUsernameGetter(user);
                if (name != null) return name;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static void downloadToFile(String url, File dest) throws Exception {
        downloadToFileAndGetType(url, dest);
    }

    static String downloadToFileAndGetType(String url, File dest) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        MediaHttp.applyTimeouts(conn);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36");
        try {
            conn.connect();
            int code = conn.getResponseCode();
            if (code >= 400) throw new HttpStatusException(code, conn.getURL().getHost());
            String contentType = conn.getContentType();
            try (InputStream in = conn.getInputStream(); FileOutputStream fos = new FileOutputStream(dest)) {
                byte[] buf = new byte[32768]; int n;
                while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
            }
            return contentType;
        } finally { conn.disconnect(); }
    }

    static String failureLog(Throwable error, String url) {
        String host;
        try { host = new URL(url).getHost(); } catch (Exception invalid) { host = "an invalid address"; }
        return error.getClass().getSimpleName()
                + (error instanceof HttpStatusException http ? " " + http.code : "") + " from " + host;
    }

    static boolean isCdnMediaUrl(String url) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false;
        if (!url.contains("cdninstagram.com") && !url.contains("fbcdn.net")) return false;
        if (url.contains("/t51.") && url.contains("-19/")) return false;
        if (url.contains("t51.39750")) return false;
        return true;
    }

    static boolean isVideoUrl(String url) {
        if (url == null) return false;
        if (wasCapturedAsVideo(url)) return true;
        String lower = url.toLowerCase(Locale.US);
        if (lower.contains("t50.")) return true;
        if (lower.contains("/o1/") || lower.contains("%2fo1%2f")) return true;
        return lower.contains(".mp4")
                || lower.contains("mime_type=video")
                || lower.contains("mime%2ftype=video");
    }
}
