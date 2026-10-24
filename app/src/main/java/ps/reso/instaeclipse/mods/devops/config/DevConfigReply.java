package ps.reso.instaeclipse.mods.devops.config;

import ps.reso.instaeclipse.R;

public final class DevConfigReply {

    public static final long TIMEOUT_MS = 5000;

    public enum Result { NO_REPLY, OK, ERROR }

    public interface Observer {
        void onResult(Result result);
    }

    private static volatile Observer observer;

    private DevConfigReply() {}

    public static Result of(boolean replied, String error) {
        if (!replied) return Result.NO_REPLY;
        return error != null ? Result.ERROR : Result.OK;
    }

    public static int messageFor(Result result, boolean export) {
        if (result == null || result == Result.NO_REPLY) return R.string.dev_config_open_instagram;
        if (result == Result.ERROR) return export ? R.string.dev_config_export_failed : R.string.ig_toast_config_import_failed;
        return export ? 0 : R.string.ig_toast_default_config_restored;
    }

    public static void observeForTest(Observer o) {
        observer = o;
    }

    public static void report(Result result) {
        Observer o = observer;
        if (o != null) o.onResult(result);
    }
}
