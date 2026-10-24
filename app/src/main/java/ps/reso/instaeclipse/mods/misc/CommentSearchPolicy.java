package ps.reso.instaeclipse.mods.misc;

import android.icu.text.MeasureFormat;
import android.icu.util.Measure;
import android.icu.util.MeasureUnit;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CommentSearchPolicy {

    enum Load { GOING, DONE, MAYBE_MORE }

    static final Pattern VIEW_REPLIES =
            Pattern.compile("(?i)^\\s*view\\s+(all\\s+)?\\d[\\d,.]*\\s+(more\\s+)?repl(y|ies)\\s*$");

    private static final String GAP = "[\\s\\u00A0\\u202F]+";
    private static final String ANY_NUMBER = "\\p{Nd}+(?:[,.'\\u00A0\\u202F\\u2009\\u066B\\u066C ]\\p{Nd}+)*";
    private static final Pattern NUMBER = Pattern.compile("%(?:\\d+\\$)?[-#+0,(]*\\d*[ds]|" + ANY_NUMBER);
    private static final Pattern SPACE = Pattern.compile(GAP);
    private static final Pattern LETTER = Pattern.compile("\\p{L}");

    private static Locale ageLocale;
    private static MeasureFormat ageFormat;

    private CommentSearchPolicy() {}

    static final class Age {
        final long value;
        final ChronoUnit unit;

        Age(long value, ChronoUnit unit) {
            this.value = value;
            this.unit = unit;
        }
    }

    static List<Pattern> replyMatchers(Collection<String> templates) {
        Map<String, Pattern> out = new LinkedHashMap<>();
        for (String t : templates) {
            if (t == null) continue;
            String template = t.trim();
            StringBuilder regex = new StringBuilder("^\\s*");
            StringBuilder words = new StringBuilder();
            Matcher m = NUMBER.matcher(template);
            int at = 0;
            while (m.find()) {
                String part = template.substring(at, m.start());
                words.append(part);
                regex.append(literal(part)).append(ANY_NUMBER);
                at = m.end();
            }
            String tail = template.substring(at);
            words.append(tail);
            regex.append(literal(tail)).append("\\s*$");
            if (!LETTER.matcher(words).find()) continue;
            String key = regex.toString();
            if (!out.containsKey(key)) out.put(key, Pattern.compile(key, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
        }
        out.put(VIEW_REPLIES.pattern(), VIEW_REPLIES);
        return new ArrayList<>(out.values());
    }

    private static String literal(String s) {
        StringBuilder b = new StringBuilder();
        Matcher m = SPACE.matcher(s);
        int at = 0;
        while (m.find()) {
            if (m.start() > at) b.append(Pattern.quote(s.substring(at, m.start())));
            b.append(GAP);
            at = m.end();
        }
        if (at < s.length()) b.append(Pattern.quote(s.substring(at)));
        return b.toString();
    }

    static boolean isReplyLink(List<Pattern> matchers, CharSequence text) {
        if (text == null) return false;
        for (Pattern p : matchers) if (p.matcher(text).matches()) return true;
        return false;
    }

    static String[] header(List<String[]> rows, String[] post) {
        if (post != null) return new String[]{post[0], post[1]};
        for (String[] r : rows) if (r[2].isEmpty() && !r[0].isEmpty()) return new String[]{r[0], r[1]};
        return new String[]{"", ""};
    }

    static List<String[]> comments(List<String[]> rows, String[] header) {
        List<String[]> out = new ArrayList<>();
        for (String[] r : rows) if (!r[2].isEmpty()) out.add(r);
        if (!out.isEmpty()) return out;
        for (String[] r : rows) if (!(r[0].equals(header[0]) && r[1].equals(header[1]))) out.add(r);
        return out;
    }

    static int count(List<String[]> rows, String[] header) {
        return comments(rows, header).size();
    }

    static String mediaId(List<String[]> rows) {
        for (String[] r : rows) if (!r[2].isEmpty() && r.length > 7 && !r[7].isEmpty()) return r[7];
        return "";
    }

    static boolean sameMedia(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return false;
        return a.equals(b) || pk(a).equals(pk(b));
    }

    private static String pk(String id) {
        int i = id.indexOf('_');
        return i < 0 ? id : id.substring(0, i);
    }

    static Load loadState(boolean recycler, long idleMs, boolean atEnd, boolean grew) {
        if (recycler) return idleMs > 3000 && atEnd ? Load.DONE : Load.GOING;
        return idleMs > (grew ? 6000 : 4000) ? Load.MAYBE_MORE : Load.GOING;
    }

    static boolean exportable(List<String[]> rows) {
        for (String[] r : rows) if (!r[2].isEmpty()) return true;
        return false;
    }

    static boolean mediaOnly(String[] r) {
        return r[1].isEmpty() && r.length > 8 && !r[8].isEmpty();
    }

    static boolean gif(String[] r) {
        return r.length > 9 && "gif".equals(r[9]);
    }

    static String copyText(String[] r) {
        return mediaOnly(r) ? r[8] : r[1];
    }

    static Age age(long seconds) {
        long s = Math.max(0, seconds);
        if (s < 60) return new Age(s, ChronoUnit.SECONDS);
        if (s < 3600) return new Age(s / 60, ChronoUnit.MINUTES);
        if (s < 86400) return new Age(s / 3600, ChronoUnit.HOURS);
        if (s < 604800) return new Age(s / 86400, ChronoUnit.DAYS);
        return new Age(s / 604800, ChronoUnit.WEEKS);
    }

    static synchronized String age(long seconds, Locale locale) {
        if (ageFormat == null || !locale.equals(ageLocale)) {
            ageFormat = MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.NARROW);
            ageLocale = locale;
        }
        Age a = age(seconds);
        return ageFormat.format(new Measure(a.value, unit(a.unit)));
    }

    private static MeasureUnit unit(ChronoUnit unit) {
        switch (unit) {
            case SECONDS: return MeasureUnit.SECOND;
            case MINUTES: return MeasureUnit.MINUTE;
            case HOURS: return MeasureUnit.HOUR;
            case DAYS: return MeasureUnit.DAY;
            default: return MeasureUnit.WEEK;
        }
    }

    static String mediaExt(String url, String kind) {
        try {
            String p = url.split("\\?")[0];
            int dot = p.lastIndexOf('.'), slash = p.lastIndexOf('/');
            if (dot > slash && dot >= 0 && p.length() - dot <= 5) return p.substring(dot).toLowerCase(Locale.ROOT);
        } catch (Throwable ignored) {}
        return "gif".equals(kind) ? ".gif" : ".jpg";
    }

    static String mediaMime(String ext) {
        switch (ext) {
            case ".gif":  return "image/gif";
            case ".webp": return "image/webp";
            case ".png":  return "image/png";
            case ".mp4":  return "video/mp4";
            default:       return "image/jpeg";
        }
    }

    static String[] commentMedia(JSONObject j) {
        JSONObject gif = j.optJSONObject("giphy_media_info");
        if (gif != null) {
            String u = firstUrl(gif, 0);
            if (!u.isEmpty()) return new String[]{u, "gif"};
        }
        for (String k : new String[]{"media_info", "media_comment_info", "media"}) {
            Object o = j.opt(k);
            if (o instanceof JSONObject || o instanceof JSONArray) {
                String u = firstUrl(o, 0);
                if (!u.isEmpty()) return new String[]{u, u.contains(".gif") || u.contains("giphy") ? "gif" : "image"};
            }
        }
        return new String[]{"", ""};
    }

    static String firstUrl(Object o, int depth) {
        if (o == null || depth > 6) return "";
        if (o instanceof JSONObject jo) {
            for (String k : new String[]{"url", "uri", "image_url"}) {
                String v = jo.optString(k, "");
                if (v.startsWith("https://")) return v;
            }
            for (Iterator<String> it = jo.keys(); it.hasNext(); ) {
                String r = firstUrl(jo.opt(it.next()), depth + 1);
                if (!r.isEmpty()) return r;
            }
        } else if (o instanceof JSONArray ja) {
            for (int i = 0; i < ja.length() && i < 10; i++) {
                String r = firstUrl(ja.opt(i), depth + 1);
                if (!r.isEmpty()) return r;
            }
        }
        return "";
    }
}
