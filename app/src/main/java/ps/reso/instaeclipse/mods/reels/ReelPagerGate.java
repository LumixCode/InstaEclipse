package ps.reso.instaeclipse.mods.reels;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

final class ReelPagerGate {
    private final Field inputEnabled;
    private final Method setInputEnabled;
    private Boolean requestedInput;
    private boolean updating;

    ReelPagerGate(Field inputEnabled, Method setInputEnabled) {
        this.inputEnabled = inputEnabled;
        this.setInputEnabled = setInputEnabled;
    }

    boolean isUpdating() { return updating; }

    boolean isLocked() { return requestedInput != null; }

    void rememberRequest(boolean enabled) {
        if (requestedInput != null) requestedInput = enabled;
    }

    void update(Object pager, boolean lock) throws ReflectiveOperationException {
        if (lock && requestedInput == null) requestedInput = inputEnabled.getBoolean(pager);
        if (requestedInput == null) return;
        boolean enabled = !lock && requestedInput;
        if (inputEnabled.getBoolean(pager) != enabled) {
            updating = true;
            try {
                if (setInputEnabled != null) setInputEnabled.invoke(pager, enabled);
                else inputEnabled.setBoolean(pager, enabled);
            } finally {
                updating = false;
            }
        }
        if (!lock) requestedInput = null;
    }
}
