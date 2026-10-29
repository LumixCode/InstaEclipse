package ps.reso.instaeclipse.mods.feed;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.ViewConfiguration;
import android.widget.ImageView;

@android.annotation.SuppressLint("AppCompatCustomView")
public class ZoomableImageView extends ImageView {

    private static final float MIN_SCALE = 1f;
    private static final float MAX_SCALE = 5f;
    private static final float DOUBLE_TAP_SCALE = 2.5f;
    private static final long SETTLE_MS = 150;

    public interface SwipeListener {
        void onSwipe(float fraction);

        void onDismiss();
    }

    private final Matrix matrix = new Matrix();
    private float scale = 1f;
    private final ZoomPointers pointers = new ZoomPointers();
    private final EventTouch touch = new EventTouch();
    private final int touchSlop;

    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private Runnable onDismissRequest;
    private SwipeListener swipeListener;
    private ValueAnimator settle;
    private float pendingSwipe;
    private float swipeOffset;
    private boolean swiping;

    public ZoomableImageView(Context ctx) {
        super(ctx);
        setScaleType(ScaleType.MATRIX);
        setClickable(true);
        touchSlop = ViewConfiguration.get(ctx).getScaledTouchSlop();

        scaleDetector = new ScaleGestureDetector(ctx, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float newScale = clamp(scale * detector.getScaleFactor());
                float factor = newScale / scale;
                scale = newScale;
                matrix.postScale(factor, factor, detector.getFocusX(), detector.getFocusY());
                fixTranslation();
                setImageMatrix(matrix);
                return true;
            }
        });

        gestureDetector = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (scale > MIN_SCALE + 0.01f) {
                    resetZoom();
                } else {
                    float factor = DOUBLE_TAP_SCALE / scale;
                    scale = DOUBLE_TAP_SCALE;
                    matrix.postScale(factor, factor, e.getX(), e.getY());
                    fixTranslation();
                    setImageMatrix(matrix);
                }
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (onDismissRequest != null) onDismissRequest.run();
                return true;
            }
        });
    }

    public void setOnDismissRequest(Runnable r) {
        this.onDismissRequest = r;
    }

    public void setOnSwipeDown(SwipeListener listener) {
        this.swipeListener = listener;
    }

    @Override
    public void setImageBitmap(android.graphics.Bitmap bm) {
        super.setImageBitmap(bm);
        post(this::resetZoom);
    }

    private static float clamp(float s) {
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, s));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (changed) resetZoom();
    }

    private void resetZoom() {
        Drawable d = getDrawable();
        int vw = getWidth(), vh = getHeight();
        if (d == null || vw == 0 || vh == 0) return;
        int dw = d.getIntrinsicWidth(), dh = d.getIntrinsicHeight();
        if (dw <= 0 || dh <= 0) return;

        float fitScale = Math.min((float) vw / dw, (float) vh / dh);
        matrix.reset();
        matrix.postScale(fitScale, fitScale);
        matrix.postTranslate((vw - dw * fitScale) / 2f, (vh - dh * fitScale) / 2f);
        scale = 1f;
        setImageMatrix(matrix);
    }

    private void fixTranslation() {
        Drawable d = getDrawable();
        if (d == null) return;
        RectF rect = new RectF(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
        matrix.mapRect(rect);

        int vw = getWidth(), vh = getHeight();
        float deltaX = 0, deltaY = 0;

        if (rect.width() <= vw) {
            deltaX = (vw - rect.width()) / 2f - rect.left;
        } else if (rect.left > 0) {
            deltaX = -rect.left;
        } else if (rect.right < vw) {
            deltaX = vw - rect.right;
        }

        if (rect.height() <= vh) {
            deltaY = (vh - rect.height()) / 2f - rect.top;
        } else if (rect.top > 0) {
            deltaY = -rect.top;
        } else if (rect.bottom < vh) {
            deltaY = vh - rect.bottom;
        }

        matrix.postTranslate(deltaX, deltaY);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);

        touch.event = event;
        boolean moved = pointers.update(touch);
        touch.event = null;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                if (moved && event.getPointerCount() == 1) {
                    if (scale > MIN_SCALE + 0.01f) {
                        matrix.postTranslate(pointers.dx(), pointers.dy());
                        fixTranslation();
                        setImageMatrix(matrix);
                    } else if (swipeListener != null) {
                        swipe(pointers.dy());
                    }
                }
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_CANCEL:
                endSwipe(false);
                break;
            case MotionEvent.ACTION_UP:
                endSwipe(ZoomPointers.dismisses(swipeOffset, getHeight()));
                break;
        }
        return true;
    }

    private void swipe(float dy) {
        if (!swiping) {
            pendingSwipe = Math.max(0f, pendingSwipe + dy);
            if (pendingSwipe <= touchSlop) return;
            swiping = true;
            if (settle != null) settle.cancel();
            swipeOffset = getTranslationY();
        }
        swipeOffset = Math.max(0f, swipeOffset + dy);
        setTranslationY(swipeOffset);
        report(swipeOffset);
    }

    private void endSwipe(boolean dismiss) {
        pendingSwipe = 0f;
        if (!swiping) return;
        swiping = false;
        float offset = swipeOffset;
        swipeOffset = 0f;
        if (dismiss) {
            if (swipeListener != null) swipeListener.onDismiss();
            return;
        }
        settle = ValueAnimator.ofFloat(offset, 0f);
        settle.setDuration(SETTLE_MS);
        settle.addUpdateListener(a -> {
            float y = (float) a.getAnimatedValue();
            setTranslationY(y);
            report(y);
        });
        settle.start();
    }

    private void report(float offset) {
        int h = getHeight();
        if (swipeListener != null && h > 0) swipeListener.onSwipe(offset / h);
    }

    private final class EventTouch implements ZoomPointers.Touch {
        MotionEvent event;

        @Override public int action() {
            return event.getActionMasked();
        }

        @Override public int actionIndex() {
            return event.getActionIndex();
        }

        @Override public int count() {
            return event.getPointerCount();
        }

        @Override public int id(int index) {
            return event.getPointerId(index);
        }

        @Override public float x(int index) {
            return event.getX(index) + getTranslationX();
        }

        @Override public float y(int index) {
            return event.getY(index) + getTranslationY();
        }
    }
}
