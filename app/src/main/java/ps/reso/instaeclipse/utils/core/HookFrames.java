package ps.reso.instaeclipse.utils.core;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * Values a hooked call's before callback leaves for its own after callback, in place of the legacy
 * {@code MethodHookParam.setObjectExtra/getObjectExtra}, which the compat layer doesn't have.
 *
 * <p>The value can't live on the param: the legacy backend hands before and after two different
 * compat params. Both callbacks of one call run on the same thread, and nested calls finish in
 * reverse order, so a per-callback thread-local stack pairs them on either backend.
 */
public final class HookFrames {

    private HookFrames() {}

    /** One per hook callback object. */
    public static final class Slot {
        private final ThreadLocal<ArrayDeque<Frame>> stack = ThreadLocal.withInitial(ArrayDeque::new);

        /** Opens this call's frame. Call it first in beforeHookedMethod, before anything can throw. */
        public Frame enter() {
            Frame frame = new Frame();
            stack.get().push(frame);
            return frame;
        }

        /** The frame of the innermost call still in its before callback; an empty one outside. */
        public Frame current() {
            Frame frame = stack.get().peek();
            return frame != null ? frame : new Frame();
        }

        /** Closes this call's frame. Call it in afterHookedMethod; an empty frame if none is open. */
        public Frame exit() {
            Frame frame = stack.get().poll();
            return frame != null ? frame : new Frame();
        }

        int depth() {
            return stack.get().size();
        }
    }

    /** The values of one hooked call. */
    public static final class Frame {
        private Map<String, Object> values;

        public void put(String key, Object value) {
            if (values == null) values = new HashMap<>(4);
            values.put(key, value);
        }

        public Object get(String key) {
            return values == null ? null : values.get(key);
        }
    }
}
