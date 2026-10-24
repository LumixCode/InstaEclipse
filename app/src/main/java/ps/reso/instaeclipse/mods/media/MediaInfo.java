package ps.reso.instaeclipse.mods.media;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.Xposed.Module;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.dialog.IeDialog;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;

/**
 * "Media info" (#231): resolution, duration, codec, audio, the available video qualities (from the
 * DASH manifest) and the file size of the post / visible carousel slide.
 *
 * Values come from com.instagram.feed.media.Media getters found by their JSON key
 * (declaredClass Media + no args + usingEqStrings(key) + return type — the same pattern the
 * downloader uses for video_dash_manifest), resolved lazily and cached. Size = HEAD Content-Length
 * of the URL the downloader would save.
 */
public final class MediaInfo {

    private static final String MEDIA = "com.instagram.feed.media.Media";
    private static final Map<String, Method> GETTERS = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> MISSING = new ConcurrentHashMap<>();

    private MediaInfo() {}

    static void show(Context ctx, Object media, Object clickHandler) {
        ModuleLog.detail("(IE|MediaInfo) requested for " + (media == null ? "null" : media.getClass().getName()));
        int slide = -1, slides = 0;
        try {
            List<?> children = FeedVideoDownloadHook.carouselChildren(media);
            if (children != null && children.size() > 1) {
                slides = children.size();
                slide = ReelDownloadHook.findCarouselIndexFromView(ctx, slides);
                if (slide < 0) slide = PostDownloadContextMenuHook.findCarouselIndex(clickHandler, slides);
                if (slide < 0 || slide >= slides) slide = 0;
            }
        } catch (Throwable t) {
            ModuleLog.detail("(IE|MediaInfo) slide: " + t);
        }
        final int fSlide = slide, fSlides = slides;
        MediaInfoRows.Texts texts = (id, args) -> I18n.t(ctx, id, args);
        FeedVideoDownloadHook.executor.submit(() -> {
            List<MediaInfoRows.Row> rows;
            String[] mediaUrl = new String[1];
            try {
                rows = MediaInfoRows.build(facts(ctx, media, fSlide, fSlides, mediaUrl), texts);
            } catch (Throwable t) {
                rows = MediaInfoRows.error(texts);
                ModuleLog.error("(IE|MediaInfo) Could not read the media info", t);
            }
            final List<MediaInfoRows.Row> shown = rows;
            FeedVideoDownloadHook.mainHandler.post(() -> {
                try {
                    // The menu's context may belong to a sheet that has closed by now; show on the
                    // Activity under it (a post opened from a profile has its own, above the main one).
                    Context host = ps.reso.instaeclipse.mods.ui.UIHookManager.dialogHost(ctx);
                    ModuleLog.detail("(IE|MediaInfo) showing " + shown.size() + " rows on " + host.getClass().getSimpleName());
                    IeDialog dlg = dialog(host, shown);
                    // Song + Spotify / YouTube Music / Apple Music, like Story Info.
                    ps.reso.instaeclipse.mods.misc.StoryInfoHook.addMusic(dlg, host, media);
                    if (mediaUrl[0] != null) dlg.secondary(I18n.t(host, R.string.ig_media_open_with), () -> openWith(host, mediaUrl[0]));
                    dlg.primary(I18n.t(host, R.string.ig_dialog_ok), null);
                    dlg.show();
                } catch (Throwable t) {
                    ModuleLog.error("(IE|MediaInfo) Could not show the media info", t);
                }
            });
        });
    }

    public static IeDialog dialog(Context host, List<MediaInfoRows.Row> shown) {
        IeDialog dlg = new IeDialog(host).title(I18n.t(host, R.string.ig_media_info_title));
        LinearLayout rows = new LinearLayout(dlg.ctx);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setBackground(dlg.cardBg());
        rows.setPadding(dlg.dp(14), dlg.dp(6), dlg.dp(14), dlg.dp(6));
        for (MediaInfoRows.Row row : shown) {
            LinearLayout r = new LinearLayout(dlg.ctx);
            r.setPadding(0, dlg.dp(7), 0, dlg.dp(7));
            TextView k = new TextView(dlg.ctx);
            k.setText(row.label);
            k.setTextColor(row.value != null ? dlg.sub : dlg.text);
            k.setTextSize(14);
            r.addView(k, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            if (row.value != null) {
                TextView v = new TextView(dlg.ctx);
                v.setText(row.value);
                v.setTextColor(dlg.text);
                v.setTextSize(14);
                v.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                v.setGravity(android.view.Gravity.END);
                v.setTextIsSelectable(true);
                r.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f));
            }
            rows.addView(r);
        }
        dlg.view(rows);
        String copy = MediaInfoRows.copy(shown);
        dlg.secondary(I18n.t(host, R.string.ig_media_info_copy), () -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) host.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("media info", copy));
        });
        return dlg;
    }

    /** Hands the media's direct CDN link to any app that can open it (player, browser, editor). */
    private static void openWith(Context host, String url) {
        try {
            android.content.Intent view = new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url));
            String path = android.net.Uri.parse(url).getPath();
            String type = path == null ? null : path.toLowerCase(Locale.ROOT).endsWith(".mp4") ? "video/*"
                    : path.toLowerCase(Locale.ROOT).matches(".*\\.(jpe?g|png|webp|heic|gif)$") ? "image/*" : null;
            if (type != null) view.setDataAndType(android.net.Uri.parse(url), type);
            android.content.Intent chooser = android.content.Intent.createChooser(view, I18n.t(host, R.string.ig_media_open_with));
            if (!(host instanceof android.app.Activity)) chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            host.startActivity(chooser);
        } catch (Throwable t) {
            ModuleLog.error("(IE|MediaInfo) Could not open the media in another app: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static MediaInfoRows.Facts facts(Context ctx, Object media, int slide, int slides, String[] urlOut) {
        Object target = media;
        MediaInfoRows.Facts facts = new MediaInfoRows.Facts();
        if (slides > 1) {
            List<?> children = FeedVideoDownloadHook.carouselChildren(media);
            if (children != null && children.size() == slides) {
                target = children.get(slide);
                facts.slide = slide;
                facts.slides = slides;
            }
        }
        facts.type = (Integer) get(target, "media_type", Integer.class);
        facts.width = (Integer) get(target, "original_width", Integer.class);
        facts.height = (Integer) get(target, "original_height", Integer.class);
        facts.duration = (Double) get(target, "video_duration", Double.class);
        facts.codec = (String) get(target, "video_codec", String.class);
        facts.audio = (Boolean) get(target, "has_audio", Boolean.class);
        facts.qualities = representations(FeedVideoDownloadHook.dashManifest(target), Locale.getDefault());
        List<String> urls = FeedVideoDownloadHook.extractAllUrlsFromMedia(ctx, target);
        if (urls != null && !urls.isEmpty()) {
            urlOut[0] = urls.get(0);
            facts.size = headSize(urls.get(0));
        }
        return facts;
    }

    /** "1080×1920 · 3.21 Mbps · avc1.64001F" for each video Representation, best first. */
    static List<String> representations(String manifest) {
        return representations(manifest, Locale.US);
    }

    static List<String> representations(String manifest, Locale locale) {
        List<String> out = new ArrayList<>();
        if (manifest == null) return out;
        Matcher m = Pattern.compile("<Representation\\b([^>]*)>").matcher(manifest);
        List<long[]> order = new ArrayList<>();
        while (m.find()) {
            String a = m.group(1);
            String mime = attr(a, "mimeType");
            String wid = attr(a, "width"), hei = attr(a, "height");
            if (wid == null || hei == null || (mime != null && !mime.startsWith("video"))) continue;
            String bw = attr(a, "bandwidth"), codecs = attr(a, "codecs");
            long bps = bandwidth(bw);
            String line = wid + "×" + hei + (bps > 0 ? String.format(locale, " · %.2f Mbps", bps / 1e6) : "")
                    + (codecs != null ? " · " + codecs : "");
            order.add(new long[]{bps, out.size()});
            out.add(line);
        }
        order.sort((x, y) -> Long.compare(y[0], x[0]));
        List<String> sorted = new ArrayList<>();
        for (long[] o : order) sorted.add(out.get((int) o[1]));
        return sorted;
    }

    private static long bandwidth(String value) {
        if (value == null) return 0;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String attr(String attrs, String name) {
        Matcher m = Pattern.compile("\\b" + name + "=\"([^\"]*)\"").matcher(attrs);
        return m.find() ? m.group(1) : null;
    }

    private static long headSize(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("HEAD");
            c.setConnectTimeout(6000);
            c.setReadTimeout(6000);
            return c.getContentLengthLong();
        } catch (Throwable t) {
            return -1;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static Object get(Object media, String key, Class<?> rt) {
        if (media == null || MISSING.containsKey(key)) return null;
        try {
            Method m = GETTERS.get(key);
            if (m == null) {
                m = DexKitCache.isCacheValid() ? DexKitCache.loadMethod("MediaInfo_" + key, Module.hostClassLoader) : null;
                if (m == null) {
                    DexKitBridge bridge = Module.dexKitBridge;
                    List<MethodData> found = bridge == null ? List.of() : DexKitQueryCache.findMethod(bridge, FindMethod.create()
                            .matcher(MethodMatcher.create().declaredClass(MEDIA).paramCount(0)
                                    .usingEqStrings(List.of(key)).returnType(rt.getName())));
                    if (found.size() != 1) {
                        MISSING.put(key, true);
                        return null;
                    }
                    m = found.get(0).getMethodInstance(Module.hostClassLoader);
                    DexKitCache.saveMethod("MediaInfo_" + key, m);
                }
                m.setAccessible(true);
                GETTERS.put(key, m);
            }
            return m.getDeclaringClass().isInstance(media) ? m.invoke(media) : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
