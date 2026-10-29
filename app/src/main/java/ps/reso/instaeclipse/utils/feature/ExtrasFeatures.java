package ps.reso.instaeclipse.utils.feature;

import java.lang.reflect.Field;

import ps.reso.instaeclipse.R;

public final class ExtrasFeatures {

    public static final class Entry {
        public final String flag;
        public final int label;

        Entry(String flag, int label) {
            this.flag = flag;
            this.label = label;
        }
    }

    public static final Entry[] ALL = {
            new Entry("disableSwipeToCamera", R.string.ig_dialog_extras_no_swipe_camera),
            new Entry("reelsAutoScroll", R.string.ig_dialog_extras_reels_auto_scroll),
            new Entry("reelsLockScroll", R.string.ig_dialog_extras_reels_lock_scroll),
            new Entry("unlimitedAccounts", R.string.ig_dialog_extras_unlimited_accounts),
            new Entry("airplaneMode", R.string.cat_airplaneMode),
            new Entry("highResolutionImages", R.string.ig_dialog_extras_high_res_images),
            new Entry("hideOnboardingPrompts", R.string.ig_dialog_extras_hide_onboarding),
            new Entry("fixNotificationRegistration", R.string.cat_fixNotificationRegistration),
            new Entry("hideStoriesTray", R.string.ig_dialog_extras_hide_stories_tray),
    };

    private ExtrasFeatures() {}

    public static String[] flags() {
        String[] out = new String[ALL.length];
        for (int i = 0; i < ALL.length; i++) out[i] = ALL[i].flag;
        return out;
    }

    public static Entry entry(String flag) {
        for (Entry e : ALL) if (e.flag.equals(flag)) return e;
        return null;
    }

    public static boolean get(String flag) {
        try {
            return field(flag).getBoolean(null);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void set(String flag, boolean value) {
        try {
            field(flag).setBoolean(null, value);
        } catch (Throwable ignored) {}
    }

    private static Field field(String flag) throws NoSuchFieldException {
        return FeatureFlags.class.getField(flag);
    }
}
