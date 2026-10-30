package ps.reso.instaeclipse.utils.ghost;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ps.reso.instaeclipse.utils.core.SafeReflect;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public final class LocalSeen {

    private static final String TAG = "(IE|LocalSeen) ";
    private static final String SEPARATOR = "\n";
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final IdTitleStore READ = new IdTitleStore("ie_local_seen.json",
            failure -> ModuleLog.warn(TAG + "Could not read the chats marked as read on this device: " + failure),
            failure -> ModuleLog.warn(TAG + "Could not save the chats marked as read on this device: " + failure));

    private static volatile Method echo, store, threadOf, markOf;

    private LocalSeen() {}

    public static boolean use(Method sender, Method echoMethod, Method storeGetter) {
        Class<?>[] sent = sender.getParameterTypes();
        Class<?>[] echoed = echoMethod.getParameterTypes();
        if (sent.length != 5 || sent[2] != String.class || sent[3] != String.class || sent[4] != String.class) {
            return false;
        }
        Method thread = threadLookup(storeGetter.getReturnType(), echoed[0]);
        Method mark = markFactory(echoed[1], sent[1]);
        if (thread == null || mark == null) return false;
        thread.setAccessible(true);
        mark.setAccessible(true);
        storeGetter.setAccessible(true);
        echoMethod.setAccessible(true);
        threadOf = thread;
        markOf = mark;
        store = storeGetter;
        echo = echoMethod;
        return true;
    }

    private static Method threadLookup(Class<?> type, Class<?> chat) {
        for (Method method : SafeReflect.publicMethods(type)) {
            Class<?>[] params = method.getParameterTypes();
            if (!Modifier.isStatic(method.getModifiers()) && params.length == 1 && params[0] == String.class
                    && chat.isAssignableFrom(method.getReturnType())) {
                return method;
            }
        }
        return null;
    }

    private static Method markFactory(Class<?> mark, Class<?> marker) {
        Class<?>[] params = {marker, String.class, String.class, String.class, long.class};
        for (Method method : SafeReflect.methods(mark)) {
            if (Modifier.isStatic(method.getModifiers()) && method.getReturnType() == mark
                    && Arrays.equals(method.getParameterTypes(), params)) {
                return method;
            }
        }
        return null;
    }

    public static void mark(Object[] args) {
        try {
            if (echo == null || !(args[2] instanceof String thread) || !(args[3] instanceof String item)
                    || !(args[4] instanceof String from)) {
                return;
            }
            WORKER.execute(() -> apply(args[0], args[1], thread, item, from));
        } catch (Throwable t) {
            ModuleLog.detail(TAG + "Could not note the message you read: " + t);
        }
    }

    private static void apply(Object session, Object marker, String thread, String item, String from) {
        try {
            String known = kept(thread)[0];
            if (item.equals(known) || !MarkSeen.newer(item, known)) return;
            Object chats = store.invoke(null, session);
            Object chat = threadOf.invoke(chats, thread);
            String me = UnsentIdentity.account(session);
            if (chat == null || me == null) {
                ModuleLog.detail(TAG + "Left chat " + thread + " unread: chat loaded " + (chat != null) + ", account known " + (me != null));
                return;
            }
            Object mark = markOf.invoke(null, marker, thread, item, "", System.currentTimeMillis() * 1000L);
            echo.invoke(chats, chat, mark, me, true);
            READ.put(thread, item + SEPARATOR + from);
            ModuleLog.detail(TAG + "Chat " + thread + " is read on this device up to message " + item + "; no read receipt was sent");
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "Could not mark chat " + thread + " as read on this device; it stays unread here", t);
        }
    }

    public static boolean waiting(String thread) {
        return READ.contains(thread);
    }

    public static Object[] replay(String thread, Object session) {
        String[] read = kept(thread);
        if (session == null || read.length < 2) return null;
        return new Object[]{session, null, thread, read[0], read[1]};
    }

    public static void sent(String thread) {
        WORKER.execute(() -> READ.remove(thread));
    }

    private static String[] kept(String thread) {
        return READ.get(thread).split(SEPARATOR, 2);
    }
}
