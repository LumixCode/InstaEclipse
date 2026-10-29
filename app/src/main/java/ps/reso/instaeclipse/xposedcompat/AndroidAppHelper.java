package ps.reso.instaeclipse.xposedcompat;

import android.app.Application;

public final class AndroidAppHelper {

    private static volatile Application app;

    private AndroidAppHelper() {}

    public static void setCurrentApplication(Application a) {
        if (a != null) app = a;
    }

    public static Application currentApplication() {
        Application a = app;
        if (a != null) return a;
        try {
            Object v = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
            if (v instanceof Application found) app = a = found;
        } catch (Throwable ignored) {}
        return a;
    }
}
