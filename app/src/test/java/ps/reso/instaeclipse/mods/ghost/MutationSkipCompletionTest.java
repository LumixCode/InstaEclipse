package ps.reso.instaeclipse.mods.ghost;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;

public class MutationSkipCompletionTest {
    /** Stands in for the mutation error enum (X.0SLP on 451). */
    enum Failure { NETWORK }

    /** Same shape as IG 451's mutation callback X.0lfj: two context getters, the completion
     *  method FpP(error, itemId) and a static success helper. */
    public interface Callback {
        Object failureContext();
        Object successContext();
        void complete(Failure error, String itemId);
        static void succeed(Object callback) { ((Callback) callback).complete(null, null); }
    }

    public interface Ambiguous {
        void complete(Failure error, String itemId);
        void fail(Failure error, String reason);
    }

    /** What the legacy anchor resolves to on 451: a parser whose 2nd parameter is a model class. */
    public static final class MessageModel {
        public void set(Object key, String value) { }
    }

    static final class RecordingCallback implements Callback {
        final List<Object[]> completions = new ArrayList<>();
        @Override public Object failureContext() { return null; }
        @Override public Object successContext() { return null; }
        @Override public void complete(Failure error, String itemId) {
            completions.add(new Object[]{error, itemId});
        }
    }

    @SuppressWarnings("unused")
    public static final class Processor {
        public void send(Object context, Callback callback, Object mutation) { }
        public void parse(Object reader, MessageModel model, String field) { }
    }

    private static XC_MethodHook.MethodHookParam call(String name, Object... args) throws Exception {
        XC_MethodHook.MethodHookParam param = new XC_MethodHook.MethodHookParam();
        for (Method m : Processor.class.getDeclaredMethods()) if (m.getName().equals(name)) param.method = m;
        param.args = args;
        return param;
    }

    @Test public void findsTheOnlyInstanceCompletionMethod() throws Exception {
        assertEquals(Callback.class.getMethod("complete", Failure.class, String.class),
                GhostViewOnceHook.completionMethod(Callback.class));
    }

    @Test public void rejectsClassesAndAmbiguousCallbacks() {
        assertNull(GhostViewOnceHook.completionMethod(MessageModel.class));
        assertNull(GhostViewOnceHook.completionMethod(Ambiguous.class));
        assertNull(GhostViewOnceHook.completionMethod(null));
    }

    @Test public void skipCompletesTheMutationAsSuccessOnce() throws Exception {
        RecordingCallback callback = new RecordingCallback();
        XC_MethodHook.MethodHookParam param = call("send", new Object(), callback, new Object());
        GhostViewOnceHook.skipMutation(param, "test");
        assertTrue(param.returnEarly);
        assertNull(param.getResult());
        assertEquals(1, callback.completions.size());
        assertNull(callback.completions.get(0)[0]);
        assertNull(callback.completions.get(0)[1]);
    }

    @Test public void legacyShapeIsAPlainSkip() throws Exception {
        XC_MethodHook.MethodHookParam param = call("parse", new Object(), new MessageModel(), "is_visual_item_seen");
        GhostViewOnceHook.skipMutation(param, "test");
        assertTrue(param.returnEarly);
        XC_MethodHook.MethodHookParam missing = call("send", new Object(), null, new Object());
        GhostViewOnceHook.skipMutation(missing, "test");
        assertTrue(missing.returnEarly);
    }
}
