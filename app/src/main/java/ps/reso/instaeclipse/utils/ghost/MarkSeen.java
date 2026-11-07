package ps.reso.instaeclipse.utils.ghost;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.mods.ghost.ChatExportHook;
import ps.reso.instaeclipse.mods.ghost.HideChatsHook;
import ps.reso.instaeclipse.mods.ui.UIHookManager;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.toast.IeToast;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;

public final class MarkSeen {

    private static final int CHATS_KEPT = 32;
    private static final ThreadLocal<Boolean> ASKING = new ThreadLocal<>();
    private static final Map<String, Unseen> UNSEEN = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Unseen> eldest) {
            return size() > CHATS_KEPT;
        }
    };

    private static volatile Method sender, check;
    private static volatile WeakReference<Object> checker;

    private record Unseen(Object[] args, String item) {}

    private MarkSeen() {}

    public static void useSender(Method method) {
        sender = method;
    }

    public static void useCheck(Method method) {
        check = method;
    }

    public static void useChecker(Object owner) {
        WeakReference<Object> held = checker;
        if (held == null || held.get() != owner) checker = new WeakReference<>(owner);
    }

    public static boolean asking() {
        return ASKING.get() == Boolean.TRUE;
    }

    public static void remember(Object[] args) {
        try {
            int at = 0;
            while (at < args.length && !(args[at] instanceof String)) at++;
            if (at == args.length) return;
            String thread = (String) args[at];
            String item = at + 1 < args.length && args[at + 1] instanceof String s ? s : null;
            synchronized (UNSEEN) {
                Unseen known = UNSEEN.get(thread);
                if (known == null || newer(item, known.item())) UNSEEN.put(thread, new Unseen(args.clone(), item));
            }
        } catch (Throwable t) {
            ModuleLog.detail("(IE|MarkAsRead) Could not note the newest unseen message: " + t);
        }
    }

    static boolean newer(String item, String than) {
        if (than == null) return true;
        if (item == null) return false;
        return item.length() != than.length() ? item.length() > than.length() : item.compareTo(than) >= 0;
    }

    public static void markOpenThreadSeen() {
        markThreadSeen(null, null);
    }

    public static void markThreadSeen(String thread, Object session) {
        if (Looper.myLooper() == Looper.getMainLooper()) send(thread, session);
        else new Handler(Looper.getMainLooper()).post(() -> send(thread, session));
    }

    public static void press(View anchor, View messageList, int toast) {
        try {
            Context ctx = anchor.getContext();
            if (messageList != null) messageList.scrollBy(0, 100_000);
            int text = send(null, null) ? toast : R.string.ig_toast_nothing_to_mark_seen;
            IeToast.makeText(ctx, I18n.t(ctx, text), Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            ModuleLog.warn("(IE|MarkAsRead) The mark as seen button failed", t);
        }
    }

    private static boolean send(String chat, Object session) {
        try {
            Method out = sender;
            String thread = chat != null ? chat : HideChatsHook.resolveThreadId(UIHookManager.getCurrentActivity());
            if (out == null || thread == null) {
                ModuleLog.detail("(IE|MarkAsRead) Nothing sent: open chat " + thread + ", sender ready " + (out != null));
                return false;
            }
            askInstagram();
            Unseen unseen = takeUnseen(thread, session);
            if (unseen == null) {
                ModuleLog.detail("(IE|MarkAsRead) Nothing new to mark as seen in chat " + thread);
                return false;
            }
            XposedBridge.invokeOriginalMethod(out, null, unseen.args());
            LocalSeen.sent(thread);
            ModuleLog.detail("(IE|MarkAsRead) Sent the read receipt for message " + unseen.item() + " in chat " + thread);
            return true;
        } catch (Throwable t) {
            ModuleLog.warn("(IE|MarkAsRead) Could not mark this chat as seen", t);
            return false;
        }
    }

    private static Unseen takeUnseen(String thread, Object session) {
        synchronized (UNSEEN) {
            Unseen known = UNSEEN.remove(thread);
            if (known != null) return known;
        }
        if (!LocalSeen.waiting(thread)) return null;
        if (session == null || !UnsentIdentity.SESSION.equals(session.getClass().getName())) {
            session = ChatExportHook.openChatSession(UIHookManager.getCurrentActivity());
        }
        Object[] args = LocalSeen.replay(thread, session);
        return args == null ? null : new Unseen(args, (String) args[3]);
    }

    private static void askInstagram() {
        Method asked = check;
        WeakReference<Object> held = checker;
        Object owner = held == null ? null : held.get();
        if (asked == null || owner == null) {
            ModuleLog.detail("(IE|MarkAsRead) Instagram's seen check is not ready; using the last message it tried to mark");
            return;
        }
        ASKING.set(Boolean.TRUE);
        try {
            asked.invoke(owner);
        } catch (Throwable t) {
            ModuleLog.detail("(IE|MarkAsRead) Instagram's seen check failed; using the last message it tried to mark: " + t);
        } finally {
            ASKING.remove();
        }
    }
}
