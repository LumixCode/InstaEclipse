package ps.reso.instaeclipse.utils.text;

import android.content.res.Resources;

import java.util.function.IntFunction;

import ps.reso.instaeclipse.R;

public final class CacheTimeText {

    private CacheTimeText() {}

    public static String format(int hours, IntFunction<String> days, IntFunction<String> hoursText) {
        return hours > 0 && hours % 24 == 0 ? days.apply(hours / 24) : hoursText.apply(hours);
    }

    public static String format(Resources resources, int hours) {
        return format(hours, d -> resources.getQuantityString(R.plurals.ig_story_cache_days, d, d),
                h -> resources.getString(R.string.ig_story_cache_hours, h));
    }
}
