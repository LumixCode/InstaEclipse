package ps.reso.instaeclipse.utils.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.View;

import java.util.concurrent.ConcurrentHashMap;

public final class ResIds {

    private static final ConcurrentHashMap<String, Integer> IDS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Integer> DRAWABLES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Integer> ATTRS = new ConcurrentHashMap<>();

    private ResIds() {}

    public static int id(Context ctx, String name) {
        return resolve(IDS, ctx, name, "id");
    }

    public static int drawable(Context ctx, String name) {
        return resolve(DRAWABLES, ctx, name, "drawable");
    }

    public static int attr(Context ctx, String name) {
        return resolve(ATTRS, ctx, name, "attr");
    }

    public static boolean is(View view, String name) {
        int id = id(view.getContext(), name);
        return id != 0 && view.getId() == id;
    }

    @SuppressLint("DiscouragedApi")
    private static int resolve(ConcurrentHashMap<String, Integer> cache, Context ctx,
                               String name, String type) {
        Integer cached = cache.get(name);
        if (cached != null) return cached;
        if (ctx == null) return 0;
        int id;
        try {
            id = ctx.getResources().getIdentifier(name, type, ctx.getPackageName());
        } catch (Throwable t) {
            id = 0;
        }
        cache.put(name, id);
        return id;
    }
}
