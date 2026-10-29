package ps.reso.instaeclipse.ui.theme;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;

public class AdvancedColorPickerView extends View {

    public interface OnColorChangedListener {
        void onColorChanged(int color);
    }

    private static final int TARGET_NONE = 0;
    private static final int TARGET_HUE = 1;
    private static final int TARGET_SV = 2;

    private final Paint svPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint huePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint previewPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint checkerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF svRect = new RectF();
    private final RectF hueRect = new RectF();
    private final RectF previewRect = new RectF();
    private final Path previewClip = new Path();

    private float hue = 0.0f;
    private float saturation = 1.0f;
    private float value = 1.0f;
    private int alpha = 255;
    private int initialColor = Color.WHITE;
    private int target = TARGET_NONE;
    private OnColorChangedListener listener;

    public AdvancedColorPickerView(Context context) {
        super(context);
        init();
    }

    public AdvancedColorPickerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        cursorPaint.setStyle(Paint.Style.STROKE);
        cursorPaint.setStrokeWidth(4.0f);
        cursorPaint.setColor(Color.WHITE);
        previewPaint.setStyle(Paint.Style.FILL);
        checkerPaint.setStyle(Paint.Style.FILL);
        int cell = Math.max(1, Math.round(dp(6.0f)));
        Bitmap checker = Bitmap.createBitmap(cell * 2, cell * 2, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(checker);
        c.drawColor(Color.WHITE);
        Paint grey = new Paint();
        grey.setColor(0xFFCCCCCC);
        c.drawRect(0, 0, cell, cell, grey);
        c.drawRect(cell, cell, cell * 2, cell * 2, grey);
        checkerPaint.setShader(new BitmapShader(checker, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
    }

    public void setColor(int color) {
        alpha = Color.alpha(color);
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        if (hsv[1] > 0.0f && hsv[2] > 0.0f) hue = hsv[0];
        saturation = hsv[1];
        value = hsv[2];
        updateShaders();
        invalidate();
    }

    public void setInitialColor(int color) {
        initialColor = color;
        invalidate();
    }

    public int getColor() {
        int rgb = Color.HSVToColor(new float[]{hue, saturation, value});
        return Color.argb(alpha, Color.red(rgb), Color.green(rgb), Color.blue(rgb));
    }

    public void setAlphaChannel(int a) {
        alpha = Math.max(0, Math.min(255, a));
        notifyChanged();
        invalidate();
    }

    public void setOnColorChangedListener(OnColorChangedListener listener) {
        this.listener = listener;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float pad = dp(12.0f);
        float previewSize = dp(48.0f);
        previewRect.set(pad, pad, pad + previewSize, pad + previewSize);
        hueRect.set(pad + previewSize + dp(16.0f), dp(8.0f) + pad, w - pad, dp(28.0f) + pad);
        svRect.set(pad, previewRect.bottom + dp(16.0f), w - pad, h - pad);
        previewClip.reset();
        previewClip.addRoundRect(previewRect, dp(12.0f), dp(12.0f), Path.Direction.CW);
        updateShaders();
    }

    private void updateShaders() {
        if (svRect.width() <= 0.0f || hueRect.width() <= 0.0f) return;
        int hueColor = Color.HSVToColor(new float[]{hue, 1.0f, 1.0f});
        Shader whiteGrad = new LinearGradient(svRect.left, svRect.top, svRect.right, svRect.top, Color.WHITE, hueColor, Shader.TileMode.CLAMP);
        Shader blackGrad = new LinearGradient(svRect.left, svRect.top, svRect.left, svRect.bottom, 0, Color.BLACK, Shader.TileMode.CLAMP);
        svPaint.setShader(new ComposeShader(whiteGrad, blackGrad, PorterDuff.Mode.MULTIPLY));
        int[] hueColors = new int[7];
        for (int i = 0; i < hueColors.length; i++) hueColors[i] = Color.HSVToColor(new float[]{i * 60.0f, 1.0f, 1.0f});
        huePaint.setShader(new LinearGradient(hueRect.left, 0.0f, hueRect.right, 0.0f, hueColors, null, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.save();
        canvas.clipPath(previewClip);
        canvas.drawRect(previewRect, checkerPaint);
        previewPaint.setColor(initialColor);
        canvas.drawRect(previewRect.left, previewRect.top, previewRect.centerX(), previewRect.bottom, previewPaint);
        previewPaint.setColor(getColor());
        canvas.drawRect(previewRect.centerX(), previewRect.top, previewRect.right, previewRect.bottom, previewPaint);
        canvas.restore();
        canvas.drawRoundRect(svRect, dp(16.0f), dp(16.0f), svPaint);
        canvas.drawRoundRect(hueRect, dp(10.0f), dp(10.0f), huePaint);
        float svX = svRect.left + (saturation * svRect.width());
        float svY = svRect.top + ((1.0f - value) * svRect.height());
        cursorPaint.setColor(Color.WHITE);
        canvas.drawCircle(svX, svY, dp(10.0f), cursorPaint);
        cursorPaint.setColor(Color.BLACK);
        canvas.drawCircle(svX, svY, dp(8.0f), cursorPaint);
        float hueX = hueRect.left + ((hue / 360.0f) * hueRect.width());
        cursorPaint.setColor(Color.WHITE);
        canvas.drawCircle(hueX, hueRect.centerY(), dp(10.0f), cursorPaint);
        cursorPaint.setColor(Color.BLACK);
        canvas.drawCircle(hueX, hueRect.centerY(), dp(8.0f), cursorPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (hueRect.contains(x, y)) target = TARGET_HUE;
                else if (svRect.contains(x, y)) target = TARGET_SV;
                else target = TARGET_NONE;
                if (target == TARGET_NONE) return super.onTouchEvent(event);
                ViewParent parent = getParent();
                if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
                track(x, y);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (target == TARGET_NONE) return super.onTouchEvent(event);
                track(x, y);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (target == TARGET_NONE) return super.onTouchEvent(event);
                target = TARGET_NONE;
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void track(float x, float y) {
        if (target == TARGET_HUE) {
            hue = clamp01((x - hueRect.left) / hueRect.width()) * 360.0f;
            updateShaders();
        } else {
            saturation = clamp01((x - svRect.left) / svRect.width());
            value = 1.0f - clamp01((y - svRect.top) / svRect.height());
        }
        notifyChanged();
        invalidate();
    }

    private void notifyChanged() {
        if (listener != null) listener.onColorChanged(getColor());
    }

    private float clamp01(float v) {
        return Math.max(0.0f, Math.min(1.0f, v));
    }

    private float dp(float v) {
        return getResources().getDisplayMetrics().density * v;
    }
}
