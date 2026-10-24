package ps.reso.instaeclipse.utils.ui;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.widget.Switch;

public final class IeSwitch {

    static final int TRACK_DP = 14;
    private static final int[][] STATES = {{android.R.attr.state_checked}, {}};

    private IeSwitch() {}

    public static void style(Switch toggle, IePalette p) {
        style(toggle, p.accent, p.thumbOff, p.trackOn, p.trackOff);
    }

    public static void style(Switch toggle, int accent, int surface, int text) {
        style(toggle, accent, IePalette.WHITE, IePalette.trackOn(surface, accent), IePalette.trackOff(surface, text));
    }

    private static void style(Switch toggle, int accent, int thumbOff, int trackOn, int trackOff) {
        toggle.setThumbTintList(new ColorStateList(STATES, new int[]{accent, thumbOff}));
        toggle.setTrackTintList(null);
        int height = Math.round(TRACK_DP * toggle.getResources().getDisplayMetrics().density);
        toggle.setTrackDrawable(new Track(new ColorStateList(STATES, new int[]{trackOn, trackOff}), height));
    }

    static final class Track extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final ColorStateList colors;
        private final int height;
        private int color;
        private int alpha = 255;

        Track(ColorStateList colors, int height) {
            this.colors = colors;
            this.height = height;
            color = colors.getDefaultColor();
            apply();
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float h = Math.min(height, b.height());
            float top = b.exactCenterY() - h / 2f;
            rect.set(b.left, top, b.right, top + h);
            canvas.drawRoundRect(rect, h / 2f, h / 2f, paint);
        }

        @Override
        public boolean isStateful() {
            return true;
        }

        @Override
        protected boolean onStateChange(int[] state) {
            int next = colors.getColorForState(state, colors.getDefaultColor());
            if (next == color) return false;
            color = next;
            apply();
            invalidateSelf();
            return true;
        }

        @Override
        public int getIntrinsicHeight() {
            return height;
        }

        @Override
        public void setAlpha(int alpha) {
            this.alpha = alpha;
            apply();
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
            paint.setColorFilter(filter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        private void apply() {
            paint.setColor(color);
            paint.setAlpha(Color.alpha(color) * alpha / 255);
        }
    }
}
