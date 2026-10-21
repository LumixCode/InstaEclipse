package ps.reso.instaeclipse.mods.ghost;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;
import android.widget.FrameLayout;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class UnsentMediaMarkInstrumentedTest {
    @Test public void outlineIsDrawnAroundTheMessageAndClearsWithoutChangingTheRow() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            FrameLayout row = new FrameLayout(InstrumentationRegistry.getInstrumentation().getTargetContext());
            row.setBackgroundColor(Color.BLUE);
            AtomicInteger clicks = new AtomicInteger();
            row.setOnClickListener(view -> clicks.incrementAndGet());
            UnsentMediaMark mark = new UnsentMediaMark(row);
            draw(row);
            mark.update(true);
            Bitmap marked = draw(row);
            assertEquals(UnsentMediaMark.COLOR, marked.getPixel(1, marked.getHeight() / 2));
            // Only an outline: the message itself stays visible.
            assertEquals(Color.BLUE, marked.getPixel(marked.getWidth() / 2, marked.getHeight() / 2));
            row.performClick();
            assertEquals(1, clicks.get());
            mark.clear();
            Bitmap cleared = draw(row);
            assertFalse(containsColor(cleared, UnsentMediaMark.COLOR));
            assertEquals(Color.BLUE, cleared.getPixel(1, cleared.getHeight() / 2));
        });
    }

    @Test public void unchangedMarkDoesNotKeepRequestingFrames() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            InvalidatingRow row = new InvalidatingRow(
                    InstrumentationRegistry.getInstrumentation().getTargetContext());
            UnsentMediaMark mark = new UnsentMediaMark(row);
            draw(row);
            row.invalidations = 0;
            mark.update(true);
            assertEquals(1, row.invalidations);
            assertTrue(containsColor(draw(row), UnsentMediaMark.COLOR));
            row.invalidations = 0;
            for (int frame = 0; frame < 10; frame++) mark.update(true);
            assertEquals(0, row.invalidations);
            row.layout(0, 0, row.getWidth() + 24, row.getHeight() + 24);
            row.invalidations = 0;
            mark.update(true);
            assertEquals(1, row.invalidations);
            mark.update(false);
            assertFalse(containsColor(draw(row), UnsentMediaMark.COLOR));
        });
    }

    private static final class InvalidatingRow extends FrameLayout {
        int invalidations;
        InvalidatingRow(Context context) { super(context); }
        @Override public void invalidate() {
            invalidations++;
            super.invalidate();
        }
    }

    private static Bitmap draw(View view) {
        float density = view.getResources().getDisplayMetrics().density;
        int width = Math.round(200 * density), height = Math.round(80 * density);
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        return bitmap;
    }

    private static boolean containsColor(Bitmap bitmap, int color) {
        for (int y = 0; y < bitmap.getHeight(); y++) {
            for (int x = 0; x < bitmap.getWidth(); x++) {
                if (bitmap.getPixel(x, y) == color) return true;
            }
        }
        return false;
    }
}
