package ps.reso.instaeclipse.mods.media;

import java.io.IOException;

public final class HttpStatusException extends IOException {
    public final int code;
    public final String host;

    public HttpStatusException(int code, String host) {
        super("HTTP " + code + " from " + host);
        this.code = code;
        this.host = host;
    }
}
