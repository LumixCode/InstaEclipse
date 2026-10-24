package ps.reso.instaeclipse.mods.feed;

import android.view.MotionEvent;

public final class ZoomPointers {

    public interface Touch {
        int action();

        int actionIndex();

        int count();

        int id(int index);

        float x(int index);

        float y(int index);
    }

    private static final int NONE = -1;
    private static final float DISMISS_FRACTION = 0.25f;

    private int active = NONE;
    private float lastX;
    private float lastY;
    private float dx;
    private float dy;

    public boolean update(Touch touch) {
        dx = 0f;
        dy = 0f;
        switch (touch.action()) {
            case MotionEvent.ACTION_DOWN:
                follow(touch, 0);
                return false;
            case MotionEvent.ACTION_POINTER_UP: {
                int lifted = touch.actionIndex();
                if (touch.id(lifted) == active && touch.count() > 1) follow(touch, lifted == 0 ? 1 : 0);
                return false;
            }
            case MotionEvent.ACTION_MOVE: {
                int index = indexOf(touch, active);
                if (index < 0) {
                    follow(touch, 0);
                    return false;
                }
                float x = touch.x(index);
                float y = touch.y(index);
                dx = x - lastX;
                dy = y - lastY;
                lastX = x;
                lastY = y;
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                active = NONE;
                return false;
            default:
                return false;
        }
    }

    public float dx() {
        return dx;
    }

    public float dy() {
        return dy;
    }

    int active() {
        return active;
    }

    static boolean dismisses(float offset, float height) {
        return height > 0f && offset > height * DISMISS_FRACTION;
    }

    private void follow(Touch touch, int index) {
        active = touch.id(index);
        lastX = touch.x(index);
        lastY = touch.y(index);
    }

    private static int indexOf(Touch touch, int id) {
        if (id == NONE) return -1;
        for (int i = 0; i < touch.count(); i++) {
            if (touch.id(i) == id) return i;
        }
        return -1;
    }
}
