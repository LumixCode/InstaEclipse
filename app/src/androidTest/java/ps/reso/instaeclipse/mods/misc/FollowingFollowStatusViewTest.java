package ps.reso.instaeclipse.mods.misc;

import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.List;

import ps.reso.instaeclipse.utils.feature.FeatureFlags;

import static org.junit.Assert.*;
import static ps.reso.instaeclipse.mods.misc.ProfileFollowStatus.Status.*;

@RunWith(AndroidJUnit4.class)
public class FollowingFollowStatusViewTest {
    @Test public void statusUsesTextColumnWithoutReplacingNamesOrFollowButton() {
        onMain(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            LinearLayout row = new LinearLayout(context);
            LinearLayout text = column(context);
            TextView name = new TextView(context);
            name.setText("person.username");
            TextView subtitle = new TextView(context);
            subtitle.setText("A person's display name");
            subtitle.setTextColor(Color.GRAY);
            text.addView(name);
            text.addView(subtitle);
            row.addView(text);
            Button button = new Button(context);
            button.setText("Following");
            row.addView(button);

            FollowingFollowStatusHook.bindLabel(subtitle, MUTUAL);
            TextView label = text.findViewWithTag("ie_following_follow_status");
            assertSame(label, text.getChildAt(2));
            assertEquals("Follows you", label.getText().toString());
            assertEquals(Color.GRAY, label.getCurrentTextColor());
            assertFalse(label.isClickable());
            assertFalse(label.isFocusable());
            assertEquals("person.username", name.getText().toString());
            assertEquals("A person's display name", subtitle.getText().toString());
            assertSame(button, row.getChildAt(1));

            FollowingFollowStatusHook.bindLabel(subtitle, DOES_NOT_FOLLOW);
            assertEquals(3, text.getChildCount());
            assertEquals("Doesn't follow you", label.getText().toString());
            subtitle.setTextColor(Color.LTGRAY);
            FollowingFollowStatusHook.bindLabel(subtitle, FOLLOWS_YOU);
            assertEquals(Color.LTGRAY, label.getCurrentTextColor());
        });
    }

    @Test public void recyclingUnknownRelationshipsAndToggleOffClearOldStatus() {
        onMain(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            LinearLayout column = column(context);
            TextView subtitle = new TextView(context);
            column.addView(subtitle);
            FollowingFollowStatusHook.bindLabel(subtitle, FOLLOWS_YOU);
            TextView label = column.findViewWithTag("ie_following_follow_status");
            assertEquals(View.VISIBLE, label.getVisibility());
            FollowingFollowStatusHook.clearLabel(subtitle);
            assertEquals(View.GONE, label.getVisibility());
            FollowingFollowStatusHook.bindLabel(subtitle, HIDDEN);
            assertEquals(View.GONE, label.getVisibility());
            FollowingFollowStatusHook.bindLabel(subtitle, DOES_NOT_FOLLOW);
            assertEquals(View.VISIBLE, label.getVisibility());
            // A toggle-off applies on the row's next bind: clear first, then bind.
            FeatureFlags.showFollowingFollowStatus = false;
            FollowingFollowStatusHook.clearLabel(subtitle);
            FollowingFollowStatusHook.bindLabel(subtitle, FOLLOWS_YOU);
            assertEquals(View.GONE, label.getVisibility());
        });
    }

    @Test public void subtitleIsTheRowTextInflatedWithTheSubtitleId() {
        onMain(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            Row row = new Row(context);
            int id = View.generateViewId();
            row.subtitle.setId(id);
            row.username.setId(View.generateViewId());
            try {
                List<Field> texts = List.of(Row.class.getDeclaredField("social"),
                        Row.class.getDeclaredField("username"), Row.class.getDeclaredField("subtitle"));
                assertEquals("subtitle", FollowingFollowStatusHook.subtitleField(texts, row, id).getName());
                // An unresolved or renamed resource id must never pick another row text.
                assertNull(FollowingFollowStatusHook.subtitleField(texts, row, View.NO_ID));
                assertNull(FollowingFollowStatusHook.subtitleField(texts, row, 0));
                assertNull(FollowingFollowStatusHook.subtitleField(texts, row, View.generateViewId()));
            } catch (ReflectiveOperationException error) {
                throw new AssertionError(error);
            }
        });
    }

    @Test public void unsupportedHorizontalContainersAreLeftUntouched() {
        onMain(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            LinearLayout row = new LinearLayout(context);
            TextView subtitle = new TextView(context);
            row.addView(subtitle);
            FollowingFollowStatusHook.bindLabel(subtitle, FOLLOWS_YOU);
            assertEquals(1, row.getChildCount());
        });
    }

    /** Mirrors IG's row holder: several TextView fields, one of them not inflated. */
    private static final class Row {
        final TextView username;
        final TextView subtitle;
        TextView social;

        Row(Context context) {
            username = new TextView(context);
            subtitle = new TextView(context);
        }
    }

    private static LinearLayout column(Context context) {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    private static void onMain(Runnable test) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            boolean before = FeatureFlags.showFollowingFollowStatus;
            FeatureFlags.showFollowingFollowStatus = true;
            try { test.run(); }
            finally { FeatureFlags.showFollowingFollowStatus = before; }
        });
    }
}
