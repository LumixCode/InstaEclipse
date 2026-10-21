package ps.reso.instaeclipse.mods.misc;

import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import ps.reso.instaeclipse.utils.feature.FeatureFlags;

import static org.junit.Assert.*;
import static ps.reso.instaeclipse.mods.misc.ProfileFollowStatus.Status.*;

@RunWith(AndroidJUnit4.class)
public class ProfileFollowStatusViewTest {
    @Test public void constraintHeaderUsesExistingRowAndRebindsWithoutDuplicateViews() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            boolean original = FeatureFlags.showFollowStatusLabel;
            FeatureFlags.showFollowStatusLabel = true;
            try {
                Context context = InstrumentationRegistry.getInstrumentation().getContext();
                ConstraintLayout root = new ConstraintLayout(context);
                TextView name = name(context);
                root.addView(name);
                LinearLayout row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setId(id(context, "internal_only_badges"));
                root.addView(row);
                ProfileFollowStatusHook.bindLabel(root, FOLLOWS_YOU);
                TextView label = root.findViewWithTag("ie_profile_follow_status");
                assertNotNull(label);
                assertSame(row, label.getParent());
                assertEquals(View.VISIBLE, label.getVisibility());
                ProfileFollowStatusHook.bindLabel(root, DOES_NOT_FOLLOW);
                assertEquals(1, row.getChildCount());
                assertSame(label, row.getChildAt(0));
                ProfileFollowStatusHook.bindLabel(root, HIDDEN);
                assertEquals(View.GONE, label.getVisibility());
            } finally {
                FeatureFlags.showFollowStatusLabel = original;
            }
        });
    }

    @Test public void verticalHeaderRefreshesThemeAndHidesWhenDisabled() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            boolean original = FeatureFlags.showFollowStatusLabel;
            FeatureFlags.showFollowStatusLabel = true;
            try {
                Context context = InstrumentationRegistry.getInstrumentation().getContext();
                LinearLayout root = new LinearLayout(context);
                root.setOrientation(LinearLayout.VERTICAL);
                TextView name = name(context);
                root.addView(name);
                ProfileFollowStatusHook.bindLabel(root, MUTUAL);
                TextView label = root.findViewWithTag("ie_profile_follow_status");
                assertSame(label, root.getChildAt(1));
                assertEquals(Color.BLACK, label.getCurrentTextColor());
                name.setTextColor(Color.WHITE);
                ProfileFollowStatusHook.bindLabel(root, MUTUAL);
                assertEquals(Color.WHITE, label.getCurrentTextColor());
                FeatureFlags.showFollowStatusLabel = false;
                ProfileFollowStatusHook.syncLabel(root, label);
                assertEquals(View.GONE, label.getVisibility());
            } finally {
                FeatureFlags.showFollowStatusLabel = original;
            }
        });
    }

    @Test public void hiddenNativeRowIsRestoredOnDisableAndProfileRecycling() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            boolean original = FeatureFlags.showFollowStatusLabel;
            FeatureFlags.showFollowStatusLabel = true;
            try {
                Context context = InstrumentationRegistry.getInstrumentation().getContext();
                ConstraintLayout root = new ConstraintLayout(context);
                LinearLayout row = new LinearLayout(context);
                row.setId(id(context, "internal_only_badges"));
                row.setVisibility(View.GONE);
                root.addView(row);
                TextView nativeBadge = new TextView(context);
                nativeBadge.setText("Native badge");
                row.addView(nativeBadge);
                ProfileFollowStatusHook.bindLabel(root, FOLLOWS_YOU);
                TextView label = root.findViewWithTag("ie_profile_follow_status");
                assertEquals(View.VISIBLE, row.getVisibility());
                assertSame(nativeBadge, row.getChildAt(0));
                FeatureFlags.showFollowStatusLabel = false;
                ProfileFollowStatusHook.syncLabel(root, label);
                assertEquals(View.GONE, row.getVisibility());

                FeatureFlags.showFollowStatusLabel = true;
                ProfileFollowStatusHook.bindLabel(root, MUTUAL, "1");
                assertEquals(View.VISIBLE, row.getVisibility());
                ProfileFollowStatusHook.bindLabel(root, HIDDEN, "1");
                assertEquals("A bind of the same profile without the relationship keeps the label",
                        View.VISIBLE, row.getVisibility());
                ProfileFollowStatusHook.bindLabel(root, HIDDEN, "2");
                assertEquals(View.GONE, row.getVisibility());
                ProfileFollowStatusHook.syncLabel(root, label);
                assertEquals("A recycled header must not redisplay the prior profile", View.GONE, row.getVisibility());
                // IG changes the row state for the next profile while binding it.
                row.setVisibility(View.INVISIBLE);
                ProfileFollowStatusHook.bindLabel(root, DOES_NOT_FOLLOW);
                assertEquals(View.VISIBLE, row.getVisibility());
                ProfileFollowStatusHook.bindLabel(root, HIDDEN);
                assertEquals(View.INVISIBLE, row.getVisibility());
                assertEquals(2, row.getChildCount());
                assertEquals(View.VISIBLE, nativeBadge.getVisibility());

                row.setVisibility(View.VISIBLE);
                ProfileFollowStatusHook.bindLabel(root, FOLLOWS_YOU);
                ProfileFollowStatusHook.bindLabel(root, HIDDEN);
                assertEquals(View.VISIBLE, row.getVisibility());
            } finally {
                FeatureFlags.showFollowStatusLabel = original;
            }
        });
    }

    /** The header binder runs for every profile header row on each list refresh; resource names
     *  must be looked up once per process, not per bind (getIdentifier scans IG's ~37k keys). */
    @Test public void repeatedHeaderBindsDoNotRepeatResourceNameLookups() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            boolean original = FeatureFlags.showFollowStatusLabel;
            FeatureFlags.showFollowStatusLabel = true;
            try {
                Context base = InstrumentationRegistry.getInstrumentation().getContext();
                int[] lookups = {0};
                @SuppressWarnings("deprecation")
                android.content.res.Resources counting = new android.content.res.Resources(base.getAssets(),
                        base.getResources().getDisplayMetrics(), base.getResources().getConfiguration()) {
                    @Override public int getIdentifier(String name, String defType, String defPackage) {
                        lookups[0]++;
                        return super.getIdentifier(name, defType, defPackage);
                    }
                };
                Context context = new android.content.ContextWrapper(base) {
                    @Override public android.content.res.Resources getResources() { return counting; }
                };
                ConstraintLayout root = new ConstraintLayout(context);
                root.addView(name(context));
                LinearLayout row = new LinearLayout(context);
                row.setId(id(context, "internal_only_badges"));
                root.addView(row);
                lookups[0] = 0;
                for (int i = 0; i < 5; i++) {
                    ProfileFollowStatusHook.bindLabel(root, i % 2 == 0 ? FOLLOWS_YOU : MUTUAL);
                }
                assertNotNull(root.findViewWithTag("ie_profile_follow_status"));
                // at most the 3 header ids + the secondary-text attr, once each; 0 if already cached
                assertTrue("getIdentifier per bind: " + lookups[0], lookups[0] <= 4);
            } finally {
                FeatureFlags.showFollowStatusLabel = original;
            }
        });
    }

    private static TextView name(Context context) {
        TextView name = new TextView(context);
        name.setId(id(context, "profile_header_full_name"));
        name.setText("Profile name");
        name.setTextColor(Color.BLACK);
        return name;
    }

    private static int id(Context context, String name) {
        int id = context.getResources().getIdentifier(name, "id", context.getPackageName());
        assertNotEquals("Test host resource missing", 0, id);
        return id;
    }
}
