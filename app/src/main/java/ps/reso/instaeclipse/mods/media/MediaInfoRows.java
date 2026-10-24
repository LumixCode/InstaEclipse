package ps.reso.instaeclipse.mods.media;

import java.util.ArrayList;
import java.util.List;

import ps.reso.instaeclipse.R;

public final class MediaInfoRows {
    public interface Texts {
        String text(int id, Object... args);
    }

    public static final class Facts {
        public Integer type;
        public int slide = -1, slides;
        public Integer width, height;
        public Double duration;
        public String codec;
        public Boolean audio;
        public List<String> qualities = List.of();
        public long size = -1;
    }

    public static final class Row {
        public final String label, value;

        Row(String label, String value) {
            this.label = label;
            this.value = value;
        }
    }

    private MediaInfoRows() {}

    public static List<Row> build(Facts facts, Texts texts) {
        List<Row> rows = new ArrayList<>();
        Integer type = facts.type;
        if (type != null) {
            String kind = texts.text(type == 2 ? R.string.ig_media_info_video
                    : type == 8 ? R.string.ig_media_info_carousel : R.string.ig_media_info_photo);
            if (facts.slides > 1 && facts.slide >= 0 && facts.slide < facts.slides)
                kind = texts.text(R.string.ig_media_info_slide, kind, facts.slide + 1, facts.slides);
            rows.add(new Row(texts.text(R.string.ig_media_info_type), kind));
        }
        if (facts.width != null && facts.height != null)
            rows.add(new Row(texts.text(R.string.ig_media_info_original),
                    texts.text(R.string.ig_media_info_size_value, facts.width, facts.height)));
        if (facts.duration != null && facts.duration > 0)
            rows.add(new Row(texts.text(R.string.ig_media_info_duration),
                    texts.text(R.string.ig_media_info_seconds, facts.duration)));
        if (facts.codec != null && !facts.codec.isEmpty())
            rows.add(new Row(texts.text(R.string.ig_media_info_codec), facts.codec));
        if (facts.audio != null)
            rows.add(new Row(texts.text(R.string.ig_media_info_audio),
                    texts.text(facts.audio ? R.string.ig_media_info_yes : R.string.ig_media_info_no)));
        if (facts.qualities != null && !facts.qualities.isEmpty())
            rows.add(new Row(texts.text(R.string.ig_media_info_qualities), String.join("\n", facts.qualities)));
        if (facts.size > 0)
            rows.add(new Row(texts.text(R.string.ig_media_info_file_size),
                    texts.text(R.string.ig_media_info_megabytes, facts.size / 1048576.0)));
        return rows.isEmpty() ? error(texts) : rows;
    }

    public static List<Row> error(Texts texts) {
        return List.of(new Row(texts.text(R.string.ig_media_info_error), null));
    }

    public static String copy(List<Row> rows) {
        StringBuilder out = new StringBuilder();
        for (Row row : rows) {
            if (out.length() > 0) out.append('\n');
            out.append(row.label);
            if (row.value != null) out.append(": ").append(row.value);
        }
        return out.toString();
    }
}
