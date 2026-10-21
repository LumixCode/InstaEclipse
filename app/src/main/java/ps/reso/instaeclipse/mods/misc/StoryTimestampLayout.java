package ps.reso.instaeclipse.mods.misc;

import android.text.TextUtils;
import android.text.method.TransformationMethod;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

/** Gives the native timestamp its own wrapping row without replacing the view or its actions. */
final class StoryTimestampLayout {
    private final Map<TextView, OriginalLayout> originals = new WeakHashMap<>();

    void update(TextView timestamp, LinearLayout container, boolean enabled) {
        OriginalLayout original = originals.get(timestamp);
        if (!enabled) {
            if (original != null) {
                original.restore(timestamp);
                originals.remove(timestamp);
            }
            return;
        }
        if (original != null) {
            LinearLayout adjustedContainer = original.container.get();
            if (adjustedContainer != null && timestamp.getParent() == adjustedContainer) return;
            original.restoreTextConstraints(timestamp);
            originals.remove(timestamp);
        }
        if (container == null || container.getOrientation() != LinearLayout.VERTICAL) return;
        if (!(timestamp.getParent() instanceof LinearLayout row)
                || row.getOrientation() != LinearLayout.HORIZONTAL || row.getParent() != container) return;

        original = new OriginalLayout(timestamp, row, container);
        originals.put(timestamp, original);
        int rowIndex = container.indexOfChild(row);
        row.removeView(timestamp);
        timestamp.setSingleLine(false);
        timestamp.setMaxLines(Integer.MAX_VALUE);
        timestamp.setEllipsize(null);
        container.addView(timestamp, rowIndex + 1, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private static final class OriginalLayout {
        final WeakReference<LinearLayout> row;
        final WeakReference<LinearLayout> container;
        final ViewGroup.LayoutParams params;
        final int index;
        final int minLines;
        final int maxLines;
        final TextUtils.TruncateAt ellipsize;
        final TransformationMethod transformation;

        OriginalLayout(TextView view, LinearLayout row, LinearLayout container) {
            this.row = new WeakReference<>(row);
            this.container = new WeakReference<>(container);
            params = view.getLayoutParams();
            index = row.indexOfChild(view);
            minLines = view.getMinLines();
            maxLines = view.getMaxLines();
            ellipsize = view.getEllipsize();
            transformation = view.getTransformationMethod();
        }

        void restore(TextView view) {
            restoreTextConstraints(view);
            LinearLayout originalRow = row.get();
            LinearLayout currentContainer = container.get();
            // Do not undo a host-driven move when a holder is repurposed during recycling.
            if (originalRow == null || currentContainer == null || view.getParent() != currentContainer
                    || originalRow.getParent() != currentContainer) return;
            currentContainer.removeView(view);
            originalRow.addView(view, Math.min(index, originalRow.getChildCount()), params);
        }

        void restoreTextConstraints(TextView view) {
            view.setSingleLine(maxLines == 1);
            view.setMinLines(minLines);
            view.setMaxLines(maxLines);
            view.setEllipsize(ellipsize);
            view.setTransformationMethod(transformation);
        }
    }
}
