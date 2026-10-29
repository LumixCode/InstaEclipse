package ps.reso.instaeclipse.utils.core;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

public final class HookFrames {

    private HookFrames() {}

    public static final class Slot {
        private final ThreadLocal<ArrayDeque<Frame>> stack = ThreadLocal.withInitial(ArrayDeque::new);

        public Frame enter() {
            Frame frame = new Frame();
            stack.get().push(frame);
            return frame;
        }

        public Frame current() {
            Frame frame = stack.get().peek();
            return frame != null ? frame : new Frame();
        }

        public Frame exit() {
            Frame frame = stack.get().poll();
            return frame != null ? frame : new Frame();
        }

        int depth() {
            return stack.get().size();
        }
    }

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
