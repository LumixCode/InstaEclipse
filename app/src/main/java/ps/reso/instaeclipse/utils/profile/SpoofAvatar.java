package ps.reso.instaeclipse.utils.profile;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.net.Uri;

public final class SpoofAvatar {

    public static final int MAX_SIDE = 320;
    public static final int MAX_CHARS = 96 * 1024;

    private static String cachedValue;
    private static Bitmap cachedBitmap;

    private SpoofAvatar() {}

    public static boolean isValid(String value) {
        if (value == null) return false;
        if (value.length() > MAX_CHARS) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '+' || c == '/' || c == '=' || c == '\n' || c == '\r';
            if (!ok) return false;
        }
        return true;
    }

    public static String encode(ContentResolver resolver, Uri uri) {
        Bitmap src = null;
        try {
            android.graphics.ImageDecoder.Source source = android.graphics.ImageDecoder.createSource(resolver, uri);
            src = android.graphics.ImageDecoder.decodeBitmap(source, (decoder, info, s) -> {
                decoder.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE);
                int shortSide = Math.min(info.getSize().getWidth(), info.getSize().getHeight());
                int sample = 1;
                while (shortSide / (sample * 2) >= MAX_SIDE) sample *= 2;
                decoder.setTargetSampleSize(sample);
            });
            int side = Math.min(src.getWidth(), src.getHeight());
            int out = Math.min(side, MAX_SIDE);
            Bitmap square = Bitmap.createBitmap(out, out, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(square);
            c.drawColor(0xFFFFFFFF);
            Rect from = new Rect((src.getWidth() - side) / 2, (src.getHeight() - side) / 2,
                    (src.getWidth() + side) / 2, (src.getHeight() + side) / 2);
            c.drawBitmap(src, from, new Rect(0, 0, out, out), new Paint(Paint.FILTER_BITMAP_FLAG));
            for (int quality = 85; quality >= 40; quality -= 15) {
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                square.compress(Bitmap.CompressFormat.JPEG, quality, bytes);
                String b64 = android.util.Base64.encodeToString(bytes.toByteArray(), android.util.Base64.NO_WRAP);
                if (b64.length() <= MAX_CHARS) {
                    square.recycle();
                    return b64;
                }
            }
            square.recycle();
            return null;
        } catch (Throwable t) {
            return null;
        } finally {
            if (src != null) src.recycle();
        }
    }

    public static synchronized Bitmap bitmap(String value) {
        if (value == null || value.isEmpty()) return null;
        if (value.equals(cachedValue)) return cachedBitmap;
        Bitmap b = null;
        try {
            byte[] bytes = android.util.Base64.decode(value, android.util.Base64.DEFAULT);
            b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Throwable ignored) {}
        cachedValue = value;
        cachedBitmap = b;
        return b;
    }

    public static final class CircleDrawable extends Drawable {
        public final Bitmap bitmap;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Matrix matrix = new Matrix();
        private final BitmapShader shader;

        public CircleDrawable(Bitmap bitmap) {
            this.bitmap = bitmap;
            shader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            paint.setShader(shader);
        }

        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            if (b.isEmpty()) return;
            float side = Math.min(b.width(), b.height());
            float scale = side / Math.min(bitmap.getWidth(), bitmap.getHeight());
            matrix.setScale(scale, scale);
            matrix.postTranslate(b.exactCenterX() - bitmap.getWidth() * scale / 2f,
                    b.exactCenterY() - bitmap.getHeight() * scale / 2f);
            shader.setLocalMatrix(matrix);
            c.drawCircle(b.exactCenterX(), b.exactCenterY(), side / 2f, paint);
        }

        @Override public int getIntrinsicWidth() { return bitmap.getWidth(); }
        @Override public int getIntrinsicHeight() { return bitmap.getHeight(); }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    public static final class PlaceholderDrawable extends Drawable {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint figure = new Paint(Paint.ANTI_ALIAS_FLAG);

        public PlaceholderDrawable(int background, int foreground) {
            fill.setColor(background);
            figure.setColor(foreground);
        }

        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            float cx = b.exactCenterX(), cy = b.exactCenterY(), r = Math.min(b.width(), b.height()) / 2f;
            c.save();
            Path clip = new Path();
            clip.addCircle(cx, cy, r, Path.Direction.CW);
            c.clipPath(clip);
            c.drawCircle(cx, cy, r, fill);
            c.drawCircle(cx, cy - r * 0.18f, r * 0.34f, figure);
            c.drawCircle(cx, cy + r * 0.95f, r * 0.68f, figure);
            c.restore();
        }

        @Override public void setAlpha(int alpha) { fill.setAlpha(alpha); figure.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
