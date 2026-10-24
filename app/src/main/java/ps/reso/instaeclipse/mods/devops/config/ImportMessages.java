package ps.reso.instaeclipse.mods.devops.config;

import ps.reso.instaeclipse.R;

public final class ImportMessages {

    public static final long TIMEOUT_MS = 5000;

    public enum Result { OK, FAILED, NO_REPLY, TOO_LARGE, NOT_VALID, UNREADABLE, CANT_RECEIVE }

    private ImportMessages() {}

    public static Result ofReply(String error) {
        return error == null ? Result.OK : Result.FAILED;
    }

    public static Result ofReadError(Throwable error) {
        return Result.UNREADABLE;
    }

    public static int forResult(Result result, boolean data) {
        if (result == Result.CANT_RECEIVE) return R.string.json_target_not_specified;
        if (result == Result.NOT_VALID) return R.string.json_not_valid;
        if (result == Result.TOO_LARGE || result == Result.UNREADABLE)
            return data ? R.string.ig_data_restore_failed : R.string.json_read_failed;
        return R.string.json_sent;
    }
}
