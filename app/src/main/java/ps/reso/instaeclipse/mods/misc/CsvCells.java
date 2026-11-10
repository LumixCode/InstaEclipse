package ps.reso.instaeclipse.mods.misc;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.function.Consumer;

public final class CsvCells {

    public interface Sink {
        OutputStream open() throws Exception;
    }

    static final int REASON_MAX = 120;

    private CsvCells() {}

    public static String cell(String value) {
        if (value == null) return "\"\"";
        String text = startsFormula(value) ? "'" + value : value;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private static boolean startsFormula(String value) {
        if (value.isEmpty()) return false;
        char c = value.charAt(0);
        return c == '=' || c == '+' || c == '-' || c == '@' || c == '\t' || c == '\r';
    }

    static String fileName(String owner, String type, long now) {
        String who = owner == null || owner.isEmpty() ? "follow_list" : owner;
        StringBuilder safe = new StringBuilder(who.length());
        who.codePoints().forEach(c -> {
            boolean ok = c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '.' || c == '_' || c == '-';
            safe.append(ok ? (char) c : '_');
        });
        String list = FollowListExportHook.FOLLOWING.equals(type) ? "following" : "followers";
        return safe + "_" + list + "_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(now)) + ".csv";
    }

    public static byte[] bytes(CharSequence csv) {
        byte[] text = csv.toString().getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[text.length + 3];
        bytes[0] = (byte) 0xEF;
        bytes[1] = (byte) 0xBB;
        bytes[2] = (byte) 0xBF;
        System.arraycopy(text, 0, bytes, 3, text.length);
        return bytes;
    }

    public static boolean write(Sink sink, byte[] bytes, Consumer<Throwable> failed) {
        try (OutputStream out = sink.open()) {
            if (out == null) throw new IOException("no file");
            out.write(bytes);
            return true;
        } catch (Throwable t) {
            failed.accept(t);
            return false;
        }
    }

    public static String reason(Throwable t) {
        String message = t.getMessage() != null ? t.getMessage().trim() : "";
        if (message.isEmpty()) return t.getClass().getSimpleName();
        return message.length() > REASON_MAX ? message.substring(0, REASON_MAX - 1) + "…" : message;
    }
}
