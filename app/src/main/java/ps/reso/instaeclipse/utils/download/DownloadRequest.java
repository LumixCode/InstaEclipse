/* HushGram-based downloader integration. SPDX-License-Identifier: GPL-3.0-only */
package ps.reso.instaeclipse.utils.download;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Snapshot of one selected item. Contains no Instagram model or live account/view references. */
public final class DownloadRequest {
    public static final int MAX_MANIFEST_CHARS = 128 * 1024;
    public static final int MAX_JSON_CHARS = 256 * 1024;
    public static final int MAX_RENDITIONS = 32;
    public enum AudioExpectation { PRESENT, SILENT, UNKNOWN }
    public final String mediaId, owner, filename, manifest;
    public final List<Rendition> direct;
    public final Options options;
    public final AudioExpectation audio;

    public DownloadRequest(String mediaId, String owner, String filename, String manifest,
            List<Rendition> direct, Options options, AudioExpectation audio) {
        this.mediaId = text(mediaId, 256);
        this.owner = text(owner, 128);
        this.filename = text(filename, 255);
        this.manifest = text(manifest, MAX_MANIFEST_CHARS);
        if (direct != null && direct.size() > MAX_RENDITIONS) throw new IllegalArgumentException("Too many renditions");
        ArrayList<Rendition> copy = new ArrayList<>();
        if (direct != null) for (Rendition r : direct) {
            if (r != null) copy.add(new Rendition(r.url, r.width, r.height, r.frameRate, r.codecs, r.bitrate));
        }
        this.direct = Collections.unmodifiableList(copy);
        this.options = options == null ? new Options(0, false, false)
                : new Options(options.maxShortSide, options.compatibility, options.allowFallback, options.originalSelection);
        this.audio = audio == null ? AudioExpectation.UNKNOWN : audio;
        if (this.options.originalSelection && (!this.manifest.isEmpty()
                || direct == null || direct.size() != 1 || copy.size() != 1 || copy.get(0).url.isEmpty()
                || this.audio != AudioExpectation.UNKNOWN))
            throw new IllegalArgumentException("Original selection requires one direct file, no manifest, and unknown audio");
    }

    public static final class Rendition {
        public final String url, codecs;
        public final int width, height;
        public final double frameRate;
        public final long bitrate;
        public Rendition(String url, int width, int height, double frameRate, String codecs, long bitrate) {
            this.url = text(url, 4096);
            this.codecs = text(codecs, 128).toLowerCase(Locale.ROOT);
            this.width = dimension(width);
            this.height = dimension(height);
            this.frameRate = Double.isFinite(frameRate) && frameRate > 0 && frameRate <= 1000 ? frameRate : 0;
            this.bitrate = Math.max(0, bitrate);
        }
        public int shortSide() { return Math.min(width, height); }
        @Override public String toString() { return width + "x" + height + " fps=" + frameRate + " codec=" + codecs; }
    }

    public static final class Options {
        public final int maxShortSide;
        public final boolean compatibility, allowFallback, originalSelection;
        public Options(int maxShortSide, boolean compatibility, boolean allowFallback) {
            this(maxShortSide, compatibility, allowFallback, false);
        }
        public Options(int maxShortSide, boolean compatibility, boolean allowFallback, boolean originalSelection) {
            if (!originalSelection && (maxShortSide < 0 || maxShortSide > 32768))
                throw new IllegalArgumentException("Invalid resolution ceiling");
            this.originalSelection = originalSelection;
            // Original copies the caller's selected direct file; Custom controls cannot alter it.
            this.maxShortSide = originalSelection ? 0 : maxShortSide;
            this.compatibility = !originalSelection && compatibility;
            this.allowFallback = !originalSelection && allowFallback;
        }
    }

    /** Private explicit-component service transport, never a log string or persistent queue. */
    public String toJson() {
        String json;
        try {
            JSONArray renditions = new JSONArray();
            for (Rendition r : direct) {
                renditions.put(new JSONObject().put("url", r.url).put("codecs", r.codecs).put("width", r.width)
                        .put("height", r.height).put("frameRate", r.frameRate).put("bitrate", r.bitrate));
            }
            json = new JSONObject().put("mediaId", mediaId).put("owner", owner).put("filename", filename)
                    .put("manifest", manifest).put("direct", renditions)
                    .put("options", new JSONObject().put("maxShortSide", options.maxShortSide)
                            .put("compatibility", options.compatibility).put("allowFallback", options.allowFallback)
                            .put("originalSelection", options.originalSelection))
                    .put("audio", audio.name()).toString();
        } catch (JSONException e) {
            throw new IllegalArgumentException("Invalid download request");
        }
        if (json.length() > MAX_JSON_CHARS) throw new IllegalArgumentException("Download request exceeds IPC limit");
        return json;
    }
    public static DownloadRequest fromJson(String json) {
        if (json == null || json.length() > MAX_JSON_CHARS) throw new IllegalArgumentException("Invalid download request");
        try {
            JSONTokener tokener = new JSONTokener(json);
            Object value = tokener.nextValue();
            if (!(value instanceof JSONObject) || tokener.nextClean() != 0) throw new JSONException("Not one object");
            JSONObject r = (JSONObject) value;
            return new DownloadRequest(string(r, "mediaId"), string(r, "owner"), string(r, "filename"),
                    string(r, "manifest"), renditions(r), options(r), audio(r));
        } catch (JSONException | RuntimeException e) {
            throw new IllegalArgumentException("Invalid download request");
        }
    }
    private static List<Rendition> renditions(JSONObject r) throws JSONException {
        if (r.isNull("direct")) return null;
        Object value = r.get("direct");
        if (!(value instanceof JSONArray)) throw new JSONException("direct");
        JSONArray array = (JSONArray) value;
        List<Rendition> out = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            if (array.isNull(i)) { out.add(null); continue; }
            Object item = array.get(i);
            if (!(item instanceof JSONObject)) throw new JSONException("direct");
            JSONObject o = (JSONObject) item;
            out.add(new Rendition(string(o, "url"), (int) whole(o, "width", Integer.MIN_VALUE, Integer.MAX_VALUE),
                    (int) whole(o, "height", Integer.MIN_VALUE, Integer.MAX_VALUE), decimal(o, "frameRate"),
                    string(o, "codecs"), whole(o, "bitrate", Long.MIN_VALUE, Long.MAX_VALUE)));
        }
        return out;
    }
    private static Options options(JSONObject r) throws JSONException {
        if (r.isNull("options")) return null;
        Object value = r.get("options");
        if (!(value instanceof JSONObject)) throw new JSONException("options");
        JSONObject o = (JSONObject) value;
        return new Options((int) whole(o, "maxShortSide", Integer.MIN_VALUE, Integer.MAX_VALUE),
                bool(o, "compatibility"), bool(o, "allowFallback"), bool(o, "originalSelection"));
    }
    private static AudioExpectation audio(JSONObject r) throws JSONException {
        String name = string(r, "audio");
        if (name == null) return null;
        for (AudioExpectation value : AudioExpectation.values()) if (value.name().equals(name)) return value;
        return null;
    }
    private static String string(JSONObject o, String key) throws JSONException {
        if (o.isNull(key)) return null;
        Object value = o.get(key);
        if (!(value instanceof String)) throw new JSONException(key);
        return (String) value;
    }
    private static boolean bool(JSONObject o, String key) throws JSONException {
        if (o.isNull(key)) return false;
        Object value = o.get(key);
        if (!(value instanceof Boolean)) throw new JSONException(key);
        return (Boolean) value;
    }
    private static double decimal(JSONObject o, String key) throws JSONException {
        if (o.isNull(key)) return 0;
        Object value = o.get(key);
        if (!(value instanceof Number)) throw new JSONException(key);
        return ((Number) value).doubleValue();
    }
    private static long whole(JSONObject o, String key, long min, long max) throws JSONException {
        if (o.isNull(key)) return 0;
        Object value = o.get(key);
        if (!(value instanceof Number)) throw new JSONException(key);
        BigDecimal number;
        try {
            number = value instanceof BigDecimal ? (BigDecimal) value
                    : value instanceof BigInteger ? new BigDecimal((BigInteger) value)
                    : value instanceof Double || value instanceof Float ? new BigDecimal(((Number) value).doubleValue())
                    : BigDecimal.valueOf(((Number) value).longValue());
            long exact = number.longValueExact();
            if (exact < min || exact > max) throw new JSONException(key);
            return exact;
        } catch (ArithmeticException | NumberFormatException e) {
            throw new JSONException(key);
        }
    }
    private static String text(String s, int limit) {
        if (s == null) return "";
        if (s.length() > limit) throw new IllegalArgumentException("Download metadata exceeds limit");
        return s;
    }
    private static int dimension(int n) { return n > 0 && n <= 32768 ? n : 0; }
}
