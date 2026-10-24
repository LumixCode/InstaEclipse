package ps.reso.instaeclipse.mods.misc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

final class StoryInfoPolicy {

    static final String CORRECT = "✓  ";
    static final String PICKED = "  •";

    private static final String[] MUSIC_KEYS = {
            "story_music_stickers", "story_music_lyric_stickers", "music_metadata", "clips_metadata"};

    private StoryInfoPolicy() {}

    static final class Choice {
        final String label;
        final String votes;
        final double share;
        final boolean correct;
        final boolean picked;
        final boolean bold;
        final String copy;

        Choice(String label, String votes, double share, boolean correct, boolean picked, boolean bold, String copy) {
            this.label = label;
            this.votes = votes;
            this.share = share;
            this.correct = correct;
            this.picked = picked;
            this.bold = bold;
            this.copy = copy;
        }
    }

    static List<Choice> quiz(Object quiz) {
        List<Choice> out = new ArrayList<>();
        if (!(find(quiz, "tallies") instanceof List<?> opts)) return out;
        Long correct = num(find(quiz, "correct_answer"));
        Long mine = num(find(quiz, "viewer_answer"));
        long total = total(opts);
        for (int i = 0; i < opts.size(); i++) {
            Object o = opts.get(i);
            String text = optionText(o, i);
            Long count = num(find(o, "count"));
            boolean ok = correct != null && correct == i;
            boolean picked = mine != null && mine == i;
            out.add(new Choice((ok ? CORRECT : "") + text + (picked ? PICKED : ""),
                    votes(count, total), share(count, total), ok, picked, ok, text));
        }
        return out;
    }

    static List<Choice> poll(Object poll) {
        List<Choice> out = new ArrayList<>();
        if (!(find(poll, "tallies") instanceof List<?> opts)) return out;
        Long mine = num(find(poll, "viewer_vote"));
        long total = total(opts);
        long best = 0;
        for (Object o : opts) {
            Long c = num(find(o, "count"));
            if (c != null && c > best) best = c;
        }
        for (int i = 0; i < opts.size(); i++) {
            Object o = opts.get(i);
            String text = optionText(o, i);
            Long count = num(find(o, "count"));
            boolean picked = mine != null && mine == i;
            boolean leading = count != null && best > 0 && count == best;
            out.add(new Choice(text + (picked ? PICKED : ""),
                    votes(count, total), share(count, total), false, picked, leading, text));
        }
        return out;
    }

    static List<Choice> slider(Object slider, String average, String yours) {
        List<Choice> out = new ArrayList<>();
        String emoji = str(slider, "emoji");
        String e = emoji == null ? "" : emoji + "  ";
        Object avg = find(slider, "slider_vote_average");
        Long count = num(find(slider, "slider_vote_count"));
        Object mine = find(slider, "viewer_vote");
        if (avg instanceof Number a && (count == null || count > 0)) {
            String p = percent(a.doubleValue());
            out.add(new Choice(e + average, p, a.doubleValue(), false, false, true, p));
        }
        if (mine instanceof Number m && m.doubleValue() >= 0) {
            String p = percent(m.doubleValue());
            out.add(new Choice(e + yours, p, m.doubleValue(), false, true, false, p));
        }
        return out;
    }

    static String sliderHeading(Object slider) {
        String question = str(slider, "question");
        String emoji = str(slider, "emoji");
        return (emoji == null ? "" : emoji + "  ") + (question == null ? "" : question);
    }

    static Long sliderVotes(Object slider) {
        return num(find(slider, "slider_vote_count"));
    }

    static boolean anyPicked(List<Choice> choices) {
        for (Choice c : choices) if (c.picked) return true;
        return false;
    }

    static long totalVotes(Object sticker) {
        return find(sticker, "tallies") instanceof List<?> opts ? total(opts) : 0;
    }

    static long total(List<?> opts) {
        long total = 0;
        for (Object o : opts) {
            Long c = num(find(o, "count"));
            if (c != null) total += c;
        }
        return total;
    }

    static double share(Long count, long total) {
        return count == null || total <= 0 ? 0 : (double) count / total;
    }

    static String votes(Long count, long total) {
        if (count == null) return "";
        return total > 0 ? Math.round(count * 100.0 / total) + "%  ·  " + count : String.valueOf(count);
    }

    static String[] music(Function<String, Object> value) {
        for (String k : MUSIC_KEYS) {
            Object v = value.apply(k);
            if (v == null) continue;
            Object licensed = find(v, "music_asset_info");
            if (licensed != null) {
                String title = str(licensed, "title");
                String artist = str(licensed, "display_artist");
                if (title != null) {
                    String line = artist == null ? title : title + " — " + artist;
                    return new String[]{line, artist == null ? title : title + " " + artist};
                }
            }
            Object original = find(v, "original_sound_info");
            if (original != null) {
                String title = str(original, "original_audio_title");
                String artist = str(find(original, "ig_artist"), "username");
                if (title != null) return new String[]{artist == null ? title : title + " — @" + artist, null};
            }
        }
        return null;
    }

    static Object find(Object tree, String key) {
        if (tree instanceof Map<?, ?> m) {
            Object v = m.get(key);
            if (v != null) return v;
            for (Object c : m.values()) {
                Object r = find(c, key);
                if (r != null) return r;
            }
        } else if (tree instanceof List<?> l) {
            for (Object c : l) {
                Object r = find(c, key);
                if (r != null) return r;
            }
        }
        return null;
    }

    static String str(Object tree, String key) {
        return plain(find(tree, key));
    }

    static String plain(Object v) {
        if (v == null || v instanceof Map || v instanceof List) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    static Long num(Object v) {
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) try { return (long) Double.parseDouble(s); } catch (Throwable ignored) {}
        return null;
    }

    private static String optionText(Object option, int index) {
        String text = str(option, "text");
        return text == null ? "#" + (index + 1) : text;
    }

    private static String percent(double fraction) {
        return (int) Math.round(fraction * 100) + "%";
    }
}
