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

/**
 * Renderer-independent mark for a retained non-text message, in the same red as an unsent text
 * bubble: a voice message's own bubble turns red, any other media (reels, posts, photos, videos)
 * gets a red outline around its bubble.
 *
 * The voice bubble (the legacy VoiceContentViewHolder row) is a LayerDrawable of ShapeDrawables that
 * Instagram repaints on every bind, so only its fill paints' colours change here, and the paints are
 * restored when the mark goes. Tinting, colour filters or alpha would outlive the bind and leak to
 * the next message the recycled row shows.
 */
final class UnsentMediaMark {
    static final int COLOR = 0xFFCF4545;
    private static final int UNRESOLVED = -1;
    private static int contentId = UNRESOLVED, voiceId = UNRESOLVED;
    private final WeakReference<View> rowRef;
    private Ring ring;
    private WeakReference<View> paintedView = new WeakReference<>(null);
    private Drawable paintedBackground;
    private final Map<Paint, Paint> savedPaints = new IdentityHashMap<>();

    UnsentMediaMark(View row) {
        rowRef = new WeakReference<>(row);
    }

    void update(boolean shown) {
        if (!shown) { clear(); return; }
        View row = rowRef.get();
        if (row == null) return;
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
        // An overlay added with empty bounds may not invalidate its newly occupied region.
        if (changed) row.invalidate();
    }

    void clear() {
        View row = rowRef.get();
        restorePaints();
        if (row != null) removeRing(row);
        ring = null;
    }

    private void removeRing(View row) {
        if (ring == null) return;
        row.getOverlay().remove(ring);
        row.invalidate();
        ring = null;
    }

    // ── Voice: Instagram's own bubble, painted red ───────────────────────────

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

    /** False when the voice bubble has no ShapeDrawable fill to repaint (the outline is used then). */
    private boolean paintRed(View container) {
        // The bubble drawable is the container's background, or one of its children's.
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
        boolean changed = false;
        for (Paint paint : fills) {
            if (paint.getColor() == COLOR && paint.getShader() == null) continue;
            // Instagram's paint as of now: after a repaint this replaces the older copy.
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
        if (savedPaints.isEmpty()) return;
        for (Map.Entry<Paint, Paint> saved : savedPaints.entrySet()) saved.getKey().set(saved.getValue());
        savedPaints.clear();
        if (paintedBackground != null) paintedBackground.invalidateSelf();
        View voice = paintedView.get();
        if (voice != null) voice.invalidate();
        paintedBackground = null;
    }

    /** The visible filled shapes of a bubble drawable; strokes (received-bubble borders) stay. */
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
            // Instagram's own wrappers keep the shape in a plain Drawable field.
            for (Class<?> c = drawable.getClass(); c != null && c != Drawable.class; c = c.getSuperclass()) {
                for (Field field : c.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || !Drawable.class.isAssignableFrom(field.getType())) continue;
                    try {
                        field.setAccessible(true);
                        collectFills((Drawable) field.get(drawable), out, depth + 1);
                    } catch (Throwable ignored) {}
                }
            }
        }
    }

    // ── Other media: a red outline around the bubble ─────────────────────────

    /**
     * The message bubble, or null to use the whole row. The row spans the full width while a
     * received message sits on the left. The bubble is the largest child of Instagram's content
     * row; the sender avatar and forward shortcut are its smaller siblings.
     */
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

    /**
     * The bubble's corner radius from its outline (its rounded background), or from the view that
     * fills it (the rounded media frame). Shared media bubbles are rounded by Instagram either way.
     */
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
            // Down into the child that fills most of it (the media inside the bubble).
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

        /** Follows the bubble (row coordinates) and its corner radius; null uses the whole row. */
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
