package ps.reso.instaeclipse.utils.ui;

import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;

public final class ViewAttachHooks {

    public interface Handler {
        void onAttached(View v) throws Throwable;
    }

    private static final class Entry {
        final String tag;
        final Handler handler;
        volatile boolean failed;

        Entry(String tag, Handler handler) {
            this.tag = tag;
            this.handler = handler;
        }
    }

    interface Hooker {
        void hook(XC_MethodHook callback);
    }

    private static final XC_MethodHook CALLBACK = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            dispatch((View) param.thisObject);
        }
    };

    static Hooker hooker = callback -> XposedHelpers.findAndHookMethod(View.class, "onAttachedToWindow", callback);
    private static final List<Entry> handlers = new CopyOnWriteArrayList<>();
    private static boolean installed;

    private ViewAttachHooks() {}

    public static synchronized void add(String tag, Handler handler) {
        if (!installed) {
            hooker.hook(CALLBACK);
            installed = true;
        }
        handlers.add(new Entry(tag, handler));
    }

    static void dispatch(View view) {
        for (Entry entry : handlers) {
            try {
                entry.handler.onAttached(view);
            } catch (Throwable t) {
                if (entry.failed) continue;
                entry.failed = true;
                ModuleLog.detail("(IE|ViewAttach) " + entry.tag + " failed on an attached view: " + t);
            }
        }
    }

    static List<String> tags() {
        List<String> out = new ArrayList<>();
        for (Entry entry : handlers) out.add(entry.tag);
        return out;
    }

    static synchronized void reset() {
        handlers.clear();
        installed = false;
    }
}
