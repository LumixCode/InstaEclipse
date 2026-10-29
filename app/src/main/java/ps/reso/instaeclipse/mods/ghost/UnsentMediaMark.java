package ps.reso.instaeclipse.mods.ghost;

import android.annotation.SuppressLint;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import ps.reso.instaeclipse.utils.core.SafeReflect;

final class UnsentMediaMark {
    static final int COLOR = 0xFFCF4545;
    private static final int UNRESOLVED = -1;
    private static int contentId = UNRESOLVED, voiceId = UNRESOLVED;
    private static final Map<Class<?>, Field[]> drawableFields = new WeakHashMap<>();
    private final WeakReference<View> rowRef;
    private Ring ring;
    private WeakReference<View> paintedView = new WeakReference<>(null);
    private Drawable paintedBackground;
    private final Map<Paint, Paint> savedPaints = new IdentityHashMap<>();
    private final List<Paint> paintedFills = new ArrayList<>();
    private final View.OnLayoutChangeListener relayout = (view, left, top, right, bottom,
                                                         oldLeft, oldTop, oldRight, oldBottom) -> stale = true;
    private boolean listening, stale = true;

    UnsentMediaMark(View row) {
        rowRef = new WeakReference<>(row);
    }

    void update(boolean shown) {
        if (!shown) { clear(); return; }
        View row = rowRef.get();
        if (row == null) return;
        if (!listening) {
            row.addOnLayoutChangeListener(relayout);
            listening = true;
            stale = true;
        }
        if (!stale && fillsStillRed()) return;
        stale = false;
        View voice = voiceBubble(row);
        if (voice != null && paintRed(voice)) {
            removeRing(row);
            return;
        }
        restorePaints();
        View bubble = bubble(row);
        boolean changed = ring == null;
        if (ring == null) {
            ring = new Ring(row);
            row.getOverlay().add(ring);
        }
        changed |= ring.resize(row.getWidth(), row.getHeight());
        changed |= ring.anchor(row, bubble);
        if (changed) row.invalidate();
    }

    void clear() {
        View row = rowRef.get();
        restorePaints();
        if (row != null) removeRing(row);
        ring = null;
        if (listening && row != null) row.removeOnLayoutChangeListener(relayout);
        listening = false;
        stale = true;
    }

    private boolean fillsStillRed() {
        if (paintedFills.isEmpty()) return true;
        View voice = paintedView.get();
        if (voice == null || voice.getBackground() != paintedBackground) return false;
        for (Paint paint : paintedFills) {
            if (paint.getColor() != COLOR || paint.getShader() != null) return false;
        }
        return true;
    }

    private void removeRing(View row) {
        if (ring == null) return;
        row.getOverlay().remove(ring);
        row.invalidate();
        ring = null;
    }

    @SuppressLint("DiscouragedApi")
    private static View voiceBubble(View row) {
        if (voiceId == UNRESOLVED) {
            voiceId = row.getResources().getIdentifier(
                    "message_content_voice_bubble_container", "id", row.getContext().getPackageName());
        }
        if (voiceId == 0) return null;
        View voice = row.findViewById(voiceId);
        return voice != null && voice.getVisibility() == View.VISIBLE ? voice : null;
    }

    private boolean paintRed(View container) {
        List<View> candidates = new ArrayList<>(List.of(container));
        if (container instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) candidates.add(group.getChildAt(i));
        }
        View voice = null;
        Drawable background = null;
        List<Paint> fills = new ArrayList<>();
        for (View candidate : candidates) {
            collectFills(candidate.getBackground(), fills, 0);
            if (fills.isEmpty()) continue;
            voice = candidate;
            background = candidate.getBackground();
            break;
        }
        if (voice == null) return false;
        if (paintedView.get() != voice) {
            restorePaints();
            paintedView = new WeakReference<>(voice);
        }
        paintedBackground = background;
        paintedFills.clear();
        paintedFills.addAll(fills);
        boolean changed = false;
        for (Paint paint : fills) {
            if (paint.getColor() == COLOR && paint.getShader() == null) continue;
            savedPaints.put(paint, new Paint(paint));
            paint.setShader(null);
            paint.setColor(COLOR);
            changed = true;
        }
        if (changed) {
            background.invalidateSelf();
            voice.invalidate();
        }
        return true;
    }

    private void restorePaints() {
        paintedFills.clear();
        if (savedPaints.isEmpty()) return;
        for (Map.Entry<Paint, Paint> saved : savedPaints.entrySet()) saved.getKey().set(saved.getValue());
        savedPaints.clear();
        if (paintedBackground != null) paintedBackground.invalidateSelf();
        View voice = paintedView.get();
        if (voice != null) voice.invalidate();
        paintedBackground = null;
    }

    private static void collectFills(Drawable drawable, List<Paint> out, int depth) {
        if (drawable == null || depth > 3) return;
        if (drawable instanceof ShapeDrawable shape) {
            Paint paint = shape.getPaint();
            if (paint.getAlpha() > 0 && paint.getStyle() != Paint.Style.STROKE) out.add(paint);
        } else if (drawable instanceof LayerDrawable layers) {
            for (int i = 0; i < layers.getNumberOfLayers(); i++) collectFills(layers.getDrawable(i), out, depth + 1);
        } else if (drawable instanceof DrawableWrapper wrapper) {
            collectFills(wrapper.getDrawable(), out, depth + 1);
        } else {
            for (Field field : drawableFields(drawable.getClass())) {
                try {
                    collectFills((Drawable) field.get(drawable), out, depth + 1);
                } catch (Throwable ignored) {}
            }
        }
    }

    private static Field[] drawableFields(Class<?> type) {
        synchronized (drawableFields) {
            Field[] fields = drawableFields.get(type);
            if (fields != null) return fields;
            List<Field> found = new ArrayList<>();
            for (Class<?> c = type; c != null && c != Drawable.class; c = c.getSuperclass()) {
                for (Field field : SafeReflect.fields(c)) {
                    if (Modifier.isStatic(field.getModifiers()) || !Drawable.class.isAssignableFrom(field.getType())) continue;
                    try {
                        field.setAccessible(true);
                        found.add(field);
                    } catch (Throwable ignored) {}
                }
            }
            fields = found.toArray(new Field[0]);
            drawableFields.put(type, fields);
            return fields;
        }
    }

    @SuppressLint("DiscouragedApi")
    private static View bubble(View row) {
        if (contentId == UNRESOLVED) {
            contentId = row.getResources().getIdentifier(
                    "message_content_horizontal_placeholder_container", "id", row.getContext().getPackageName());
        }
        if (contentId == 0 || !(row.findViewById(contentId) instanceof ViewGroup content)) return null;
        View bubble = null;
        for (int i = 0; i < content.getChildCount(); i++) {
            View child = content.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            if (bubble == null || area(child) > area(bubble)) bubble = child;
        }
        return bubble == null || area(bubble) == 0 ? null : bubble;
    }

    private static long area(View view) { return (long) view.getWidth() * view.getHeight(); }

    private static float cornerRadius(View bubble, float fallback) {
        Outline outline = new Outline();
        View current = bubble;
        for (int depth = 0; current != null && depth < 4; depth++) {
            ViewOutlineProvider provider = current.getOutlineProvider();
            if (provider != null && current.getWidth() > 0 && current.getHeight() > 0) {
                outline.setEmpty();
                try {
                    provider.getOutline(current, outline);
                    float radius = outline.isEmpty() ? 0 : outline.getRadius();
                    if (radius > 0) return radius;
                } catch (Throwable ignored) {}
            }
            View next = null;
            if (current instanceof ViewGroup group) {
                for (int i = 0; i < group.getChildCount(); i++) {
                    View child = group.getChildAt(i);
                    if (child.getVisibility() == View.VISIBLE && area(child) * 10 >= area(current) * 7
                            && (next == null || area(child) > area(next))) next = child;
                }
            }
            current = next;
        }
        return fallback;
    }

    private static final class Ring extends Drawable {
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF shape = new RectF();
        private final Rect bubble = new Rect();
        private final float fallbackRadius;
        private float radius;
        private boolean anchored;

        Ring(View row) {
            float density = row.getResources().getDisplayMetrics().density;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(2 * density);
            stroke.setColor(COLOR);
            fallbackRadius = 18 * density;
            radius = fallbackRadius;
        }

        boolean resize(int width, int height) {
            if (width > 0 && height > 0 && (getBounds().width() != width || getBounds().height() != height)) {
                setBounds(0, 0, width, height);
                return true;
            }
            return false;
        }

        boolean anchor(View row, View view) {
            if (view == null) {
                if (!anchored) return false;
                anchored = false;
                return true;
            }
            Rect bounds = new Rect(0, 0, view.getWidth(), view.getHeight());
            if (!(row instanceof ViewGroup group)) return false;
            try {
                group.offsetDescendantRectToMyCoords(view, bounds);
            } catch (IllegalArgumentException notDescendant) {
                return false;
            }
            float r = cornerRadius(view, fallbackRadius);
            if (anchored && bubble.equals(bounds) && r == radius) return false;
            anchored = true;
            bubble.set(bounds);
            radius = r;
            return true;
        }

        @Override public void draw(@NonNull Canvas canvas) {
            if (getBounds().isEmpty()) return;
            Rect area = anchored ? bubble : getBounds();
            float inset = stroke.getStrokeWidth() / 2f;
            shape.set(area.left + inset, area.top + inset, area.right - inset, area.bottom - inset);
            if (shape.width() <= 0 || shape.height() <= 0) return;
            float r = Math.max(0, Math.min(radius - inset, Math.min(shape.width(), shape.height()) / 2f));
            canvas.drawRoundRect(shape, r, r, stroke);
        }

        @Override public void setAlpha(int alpha) {
            stroke.setAlpha(alpha);
            invalidateSelf();
        }

        @Override public void setColorFilter(@Nullable ColorFilter filter) {
            stroke.setColorFilter(filter);
            invalidateSelf();
        }

        @SuppressWarnings("deprecation")
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
