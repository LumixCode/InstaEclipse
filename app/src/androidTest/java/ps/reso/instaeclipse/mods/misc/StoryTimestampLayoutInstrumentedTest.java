package ps.reso.instaeclipse.mods.misc;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class StoryTimestampLayoutInstrumentedTest {
    @Test public void longExactDateWrapsWithoutEllipsisAndKeepsNativeClick() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Fixture fixture = new Fixture();
            AtomicInteger clicks = new AtomicInteger();
            fixture.timestamp.setOnClickListener(view -> clicks.incrementAndGet());
            fixture.layout.update(fixture.timestamp, fixture.container, true);
            fixture.measure();

            assertSame(fixture.container, fixture.timestamp.getParent());
            assertTrue(fixture.timestamp.getLineCount() > 1);
            for (int i = 0; i < fixture.timestamp.getLineCount(); i++) {
                assertEquals(0, fixture.timestamp.getLayout().getEllipsisCount(i));
            }
            assertEquals(fixture.timestamp.getText().length(), fixture.timestamp.getLayout()
                    .getLineEnd(fixture.timestamp.getLineCount() - 1));
            assertTrue(fixture.timestamp.getBottom() <= fixture.container.getHeight());
            fixture.timestamp.performClick();
            assertEquals(1, clicks.get());
        });
    }

    @Test public void repeatedBindsAndDisableRestoreOriginalRowWithoutDuplicates() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Fixture fixture = new Fixture();
            ViewGroup.LayoutParams original = fixture.timestamp.getLayoutParams();
            for (int i = 0; i < 3; i++) fixture.layout.update(fixture.timestamp, fixture.container, true);
            assertEquals(2, fixture.container.getChildCount());
            assertEquals(1, fixture.row.getChildCount());
            fixture.layout.update(fixture.timestamp, fixture.container, false);
            assertSame(fixture.row, fixture.timestamp.getParent());
            assertSame(original, fixture.timestamp.getLayoutParams());
            assertEquals(1, fixture.row.indexOfChild(fixture.timestamp));
            assertEquals(1, fixture.timestamp.getMaxLines());
            assertEquals(TextUtils.TruncateAt.END, fixture.timestamp.getEllipsize());
            assertEquals(1, fixture.container.getChildCount());
            fixture.layout.update(fixture.timestamp, fixture.container, true);
            assertEquals(2, fixture.container.getChildCount());
        });
    }

    @Test public void disableAfterHostReparentRestoresTextButPreservesHostPlacement() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Fixture fixture = new Fixture();
            fixture.layout.update(fixture.timestamp, fixture.container, true);
            LinearLayout hostParent = new LinearLayout(fixture.context);
            ViewGroup.LayoutParams hostParams = new LinearLayout.LayoutParams(120, 60);
            fixture.container.removeView(fixture.timestamp);
            hostParent.addView(fixture.timestamp, hostParams);

            fixture.layout.update(fixture.timestamp, null, false);

            assertSame(hostParent, fixture.timestamp.getParent());
            assertSame(hostParams, fixture.timestamp.getLayoutParams());
            assertEquals(1, fixture.timestamp.getMaxLines());
            assertEquals(TextUtils.TruncateAt.END, fixture.timestamp.getEllipsize());
            assertEquals(1, fixture.row.getChildCount());
        });
    }

    @Test public void enabledRebindAfterHostMoveRestoresOwnedTextBeforeDiscardingState() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Fixture fixture = new Fixture();
            fixture.layout.update(fixture.timestamp, fixture.container, true);
            LinearLayout hostParent = new LinearLayout(fixture.context);
            fixture.container.removeView(fixture.timestamp);
            hostParent.addView(fixture.timestamp);

            fixture.layout.update(fixture.timestamp, null, true);
            assertSame(hostParent, fixture.timestamp.getParent());
            assertEquals(1, fixture.timestamp.getMaxLines());
            assertEquals(TextUtils.TruncateAt.END, fixture.timestamp.getEllipsize());
            fixture.layout.update(fixture.timestamp, null, false);
            assertSame(hostParent, fixture.timestamp.getParent());
        });
    }

    @Test public void removedOriginalRowDoesNotReceiveTimestampOnDisable() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Fixture fixture = new Fixture();
            fixture.layout.update(fixture.timestamp, fixture.container, true);
            fixture.container.removeView(fixture.row);

            fixture.layout.update(fixture.timestamp, fixture.container, false);

            assertSame(fixture.container, fixture.timestamp.getParent());
            assertNull(fixture.row.getParent());
            assertEquals(1, fixture.row.getChildCount());
            assertEquals(1, fixture.timestamp.getMaxLines());
            assertEquals(TextUtils.TruncateAt.END, fixture.timestamp.getEllipsize());
        });
    }

    @Test public void replacedOriginalRowPreservesHostMoveIntoReplacement() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Fixture fixture = new Fixture();
            fixture.layout.update(fixture.timestamp, fixture.container, true);
            fixture.container.removeView(fixture.row);
            LinearLayout replacement = new LinearLayout(fixture.context);
            fixture.container.addView(replacement, 0);
            fixture.container.removeView(fixture.timestamp);
            replacement.addView(fixture.timestamp);

            fixture.layout.update(fixture.timestamp, fixture.container, false);

            assertSame(replacement, fixture.timestamp.getParent());
            assertEquals(1, fixture.row.getChildCount());
            assertEquals(1, fixture.timestamp.getMaxLines());
            assertEquals(TextUtils.TruncateAt.END, fixture.timestamp.getEllipsize());
        });
    }

    private static final class Fixture {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final LinearLayout container = new LinearLayout(context);
        final LinearLayout row = new LinearLayout(context);
        final TextView timestamp = new TextView(context);
        final StoryTimestampLayout layout = new StoryTimestampLayout();

        Fixture() {
            container.setOrientation(LinearLayout.VERTICAL);
            row.setOrientation(LinearLayout.HORIZONTAL);
            container.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView username = new TextView(context);
            username.setText("a_long_instagram_username");
            row.addView(username, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            timestamp.setTextSize(16);
            timestamp.setText("Wednesday, September 23, 2026 at 11:59:59 PM");
            timestamp.setSingleLine(true);
            timestamp.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(timestamp, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        void measure() {
            int width = (int) (200 * context.getResources().getDisplayMetrics().density);
            container.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            container.layout(0, 0, width, container.getMeasuredHeight());
        }
    }
}
