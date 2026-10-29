package ps.reso.instaeclipse.utils.ipc;

import android.os.Bundle;
import android.os.Parcel;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class LegacyBrokerPayload {
    private LegacyBrokerPayload() { }
    private static final Set<String> MEDIA = keys("url", "audioUrl", "fallbackUrl", "filename", "mimeType",
            "username", "extractAudio", "trimStartMs", "trimDurationMs");
    private static final Set<String> EXPORT = keys("chatExportSubdir", "htmlFilename", "htmlB64",
            "saveUri", "messageCount", "mediaUrls", "mediaNames", "mediaMimes", "exportMime");
    private static final Set<String> EXPORT_TYPES = keys("text/html", "text/csv");

    public static Bundle validate(Bundle source) {
        if (source == null || source.hasFileDescriptors()) invalid();
        Map<String, Object> values = new LinkedHashMap<>();
        for (String key : source.keySet()) values.put(key, source.get(key));
        Bundle out = new Bundle();
        for (Map.Entry<String, Object> entry : validateValues(values).entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof String) out.putString(key, (String)value);
            else if (value instanceof String[]) out.putStringArray(key, (String[])value);
            else if (value instanceof Boolean) out.putBoolean(key, (Boolean)value);
            else if (value instanceof Long) out.putLong(key, (Long)value);
            else out.putInt(key, (Integer)value);
        }
        Parcel parcel = Parcel.obtain();
        try { out.writeToParcel(parcel, 0); if (parcel.dataSize() > BrokerPayload.MAX_PARCEL_BYTES - 4096) invalid(); }
        finally { parcel.recycle(); }
        return out;
    }

    static Map<String, Object> validateValues(Map<String, Object> source) {
        if (source == null || source.size() > 12) invalid();
        boolean export = source.containsKey("chatExportSubdir");
        Set<String> allowed = export ? EXPORT : MEDIA;
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            if (!allowed.contains(key)) invalid();
            Object value = entry.getValue();
            if (value == null) continue;
            if (value instanceof String[]) out.put(key, ((String[])value).clone());
            else if (value instanceof String || value instanceof Boolean || value instanceof Long
                    || value instanceof Integer) out.put(key, value);
            else invalid();
        }
        if (export) {
            String subdir = string(out, "chatExportSubdir", true);
            if (subdir.length() > 160 || !subdir.matches("(?:chats|comments)/[A-Za-z0-9._-]+|exports")
                    || subdir.endsWith("/.") || subdir.endsWith("/..")) invalid();
            String type = string(out, "exportMime", false);
            if (type != null && !EXPORT_TYPES.contains(type)) invalid();
            filename(string(out, "htmlFilename", true));
            String encoded = string(out, "htmlB64", true);
            if (encoded.length() > 240_000 || !encoded.matches("[A-Za-z0-9+/=]*")) invalid();
            out.remove("saveUri");
            Object count = out.get("messageCount");
            if (count != null && !(count instanceof Integer)) invalid();
            if (count != null && (Integer)count < 0) invalid();
            String[] urls = array(out, "mediaUrls"), names = array(out, "mediaNames"), mimes = array(out, "mediaMimes");
            if (urls.length > 500 || urls.length != names.length || urls.length != mimes.length) invalid();
            for (int i = 0; i < urls.length; i++) {
                filename(names[i]);
                if (!ps.reso.instaeclipse.utils.download.DownloadEngine.isAllowedMediaUrl(urls[i])) invalid();
                if (!keys("image/jpeg", "image/png", "image/webp", "image/gif", "video/mp4", "audio/mp4", "audio/mpeg", "audio/webm").contains(mimes[i])) invalid();
            }
        } else {
            String mime = string(out, "mimeType", true);
            BrokerPayload.legacy(string(out, "url", true), string(out, "audioUrl", false),
                    string(out, "filename", true), mime, string(out, "username", false));
            String fallback = string(out, "fallbackUrl", false);
            if (fallback != null && !ps.reso.instaeclipse.utils.download.DownloadEngine.isAllowedMediaUrl(fallback)) invalid();
            Object extract = out.get("extractAudio");
            if (extract != null && !(extract instanceof Boolean)) invalid();
            if (Boolean.TRUE.equals(extract) && !"audio/mp4".equals(mime)) invalid();
            long start = number(out, "trimStartMs", 0), duration = number(out, "trimDurationMs", -1);
            if (start < 0 || start > 86_400_000 || duration < -1 || duration > 86_400_000) invalid();
        }
        return out;
    }
    private static String string(Map<String, Object> b, String key, boolean required) {
        Object value = b.get(key);
        if (value != null && !(value instanceof String)) invalid();
        String text = (String)value;
        if (required && (text == null || text.isEmpty())) invalid();
        return text;
    }
    private static String[] array(Map<String, Object> b, String key) {
        Object value = b.get(key);
        if (value == null) return new String[0];
        if (!(value instanceof String[])) invalid();
        return (String[])value;
    }
    private static long number(Map<String, Object> b, String key, long fallback) {
        Object value = b.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Long)) invalid();
        return (Long)value;
    }
    private static void filename(String value) {
        if (value == null || value.isEmpty() || value.length() > 255 || value.equals(".") || value.equals("..")) invalid();
        for (int i=0; i<value.length(); i++) if (value.charAt(i)=='/' || value.charAt(i)==92 || Character.isISOControl(value.charAt(i))) invalid();
    }
    private static Set<String> keys(String... values) { return new HashSet<>(Arrays.asList(values)); }
    private static void invalid() { throw new IllegalArgumentException("Invalid companion request"); }
}
