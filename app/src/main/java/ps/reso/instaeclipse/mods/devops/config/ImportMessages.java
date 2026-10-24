package ps.reso.instaeclipse.mods.devops.config;

import java.nio.charset.CharacterCodingException;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.ipc.BrokerPayload;

public final class ImportMessages {

    public static final long TIMEOUT_MS = 5000;

    public enum Result { OK, FAILED, NO_REPLY, TOO_LARGE, NOT_VALID, UNREADABLE, CANT_RECEIVE }

    public interface Observer {
        void onResult(Result result);
    }

    private static volatile Observer observer;

    private ImportMessages() {}

    public static Result ofReply(String error) {
        return error == null ? Result.OK : Result.FAILED;
    }

    public static Result ofReadError(Throwable error) {
        if (error instanceof BrokerPayload.TooLarge) return Result.TOO_LARGE;
        if (error instanceof IllegalArgumentException || error instanceof CharacterCodingException) return Result.NOT_VALID;
        return Result.UNREADABLE;
    }

    public static int forResult(Result result, boolean data) {
        if (result == Result.OK) return R.string.json_sent;
        if (result == Result.TOO_LARGE) return R.string.import_too_large;
        if (result == Result.CANT_RECEIVE) return R.string.import_cant_receive;
        if (result == Result.FAILED) return data ? R.string.ig_data_restore_failed : R.string.import_cant_receive;
        if (result == Result.NOT_VALID) return data ? R.string.ig_data_restore_failed : R.string.json_not_valid;
        if (result == Result.UNREADABLE) return data ? R.string.ig_data_restore_failed : R.string.json_read_failed;
        return R.string.import_no_reply;
    }

    public static void observeForTest(Observer o) {
        observer = o;
    }

    public static void report(Result result) {
        Observer o = observer;
        if (o != null) o.onResult(result);
    }
}
