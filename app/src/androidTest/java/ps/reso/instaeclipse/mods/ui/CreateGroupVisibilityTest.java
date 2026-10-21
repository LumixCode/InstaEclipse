package ps.reso.instaeclipse.mods.ui;

import android.animation.Animator;
import android.animation.LayoutTransition;
import android.content.Context;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import ps.reso.instaeclipse.utils.feature.FeatureFlags;

import static org.junit.Assert.*;

// Create-group hiding moved to ShareSheetCleanupHook's scoped sweep, which needs IG's ids;
// these cover the id-independent parts of hiding it (no parent fade, next-frame timing, the
// transition watcher) and the always-on share-row alignment that moved with it.
@RunWith(AndroidJUnit4.class)
public class CreateGroupVisibilityTest {
    /** Records the parent transition's state at the moment the button turns GONE. */
    private static final class RecordingTransition extends LayoutTransition {
        int hides;
        boolean fadeDuringHide, moveDuringHide;
        Animator moveAnimatorDuringHide;

        @Override public void hideChild(ViewGroup parent, View child, int newVisibility) {
            hides++;
            fadeDuringHide = isTransitionTypeEnabled(DISAPPEARING);
            moveDuringHide = isTransitionTypeEnabled(CHANGE_DISAPPEARING);
            moveAnimatorDuringHide = getAnimator(CHANGE_DISAPPEARING);
            super.hideChild(parent, child, newVisibility);
        }
    }

    @Test public void createGroupHideSkipsParentFadeAndRestoresTransition() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            RecordingTransition transition = new RecordingTransition();
            Animator move = transition.getAnimator(LayoutTransition.CHANGE_DISAPPEARING);
            LinearLayout container = new LinearLayout(context);
            container.setLayoutTransition(transition);
            View button = new View(context);
            container.addView(button);

            ShareSheetCleanupHook.hideWithoutTransition(button);
            assertEquals(View.GONE, button.getVisibility());
            assertEquals(1, transition.hides);
            // The fade drew the button at full alpha for 300 ms; the change type with no animator
            // only cancels the neighbours' queued moves.
            assertFalse(transition.fadeDuringHide);
            assertTrue(transition.moveDuringHide);
            assertNull(transition.moveAnimatorDuringHide);
            assertTrue(transition.isTransitionTypeEnabled(LayoutTransition.DISAPPEARING));
            assertTrue(transition.isTransitionTypeEnabled(LayoutTransition.CHANGE_DISAPPEARING));
            assertSame(move, transition.getAnimator(LayoutTransition.CHANGE_DISAPPEARING));
        });
    }

    @Test public void createGroupHideKeepsTransitionTypesTheHostDisabled() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            RecordingTransition transition = new RecordingTransition();
            transition.disableTransitionType(LayoutTransition.DISAPPEARING);
            transition.disableTransitionType(LayoutTransition.CHANGE_DISAPPEARING);
            FrameLayout container = new FrameLayout(context);
            container.setLayoutTransition(transition);
            View button = new View(context);
            container.addView(button);

            ShareSheetCleanupHook.hideWithoutTransition(button);
            assertEquals(View.GONE, button.getVisibility());
            assertTrue(transition.moveDuringHide);
            assertFalse(transition.isTransitionTypeEnabled(LayoutTransition.DISAPPEARING));
            assertFalse(transition.isTransitionTypeEnabled(LayoutTransition.CHANGE_DISAPPEARING));

            // A container without a transition gets a plain GONE.
            FrameLayout plain = new FrameLayout(context);
            View other = new View(context);
            plain.addView(other);
            ShareSheetCleanupHook.hideWithoutTransition(other);
            assertEquals(View.GONE, other.getVisibility());
        });
    }

    @Test public void appearingButtonHidesInNextFrameAndOnlyWhileEnabled() throws Exception {
        boolean enabled = FeatureFlags.hideShareCreateGroup;
        View[] buttons = new View[2];
        AtomicInteger hiddenState = new AtomicInteger(-1), disabledState = new AtomicInteger(-1);
        CountDownLatch frame = new CountDownLatch(1);
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
                LinearLayout container = new LinearLayout(context);
                container.setLayoutTransition(new LayoutTransition());
                for (int i = 0; i < buttons.length; i++) container.addView(buttons[i] = new View(context));
                FeatureFlags.hideShareCreateGroup = true;
                ShareSheetCleanupHook.hideBeforeNextLayout(buttons[0]);
                // Deferred: IG's own setVisibility(VISIBLE) call must finish first.
                assertEquals(View.VISIBLE, buttons[0].getVisibility());
                Choreographer.getInstance().postFrameCallback(frameTime -> {
                    hiddenState.set(buttons[0].getVisibility());
                    FeatureFlags.hideShareCreateGroup = false;
                    ShareSheetCleanupHook.hideBeforeNextLayout(buttons[1]);
                    Choreographer.getInstance().postFrameCallback(next -> {
                        disabledState.set(buttons[1].getVisibility());
                        frame.countDown();
                    });
                });
            });
            assertTrue(frame.await(5, TimeUnit.SECONDS));
            assertEquals(View.GONE, hiddenState.get());
            assertEquals(View.VISIBLE, disabledState.get());
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(
                    () -> FeatureFlags.hideShareCreateGroup = enabled);
        }
    }

    @Test public void appearingWatcherIsAddedOnceAndIgnoresOtherViews() throws Exception {
        boolean enabled = FeatureFlags.hideShareCreateGroup;
        View[] other = new View[1];
        AtomicInteger state = new AtomicInteger(-1);
        CountDownLatch frame = new CountDownLatch(1);
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
                LayoutTransition transition = new LayoutTransition();
                LinearLayout container = new LinearLayout(context);
                container.setLayoutTransition(transition);
                View button = new View(context);
                container.addView(button);
                container.addView(other[0] = new View(context));
                other[0].setId(View.generateViewId());
                ShareSheetCleanupHook.watchAppearing(button);
                ShareSheetCleanupHook.watchAppearing(button);
                int count = 0;
                for (LayoutTransition.TransitionListener l : transition.getTransitionListeners())
                    if (l == ShareSheetCleanupHook.APPEARING_WATCHER) count++;
                assertEquals(1, count);

                // The send button and anything else sharing the container stay as IG set them.
                FeatureFlags.hideShareCreateGroup = true;
                ShareSheetCleanupHook.APPEARING_WATCHER.startTransition(
                        transition, container, other[0], LayoutTransition.APPEARING);
                Choreographer.getInstance().postFrameCallback(frameTime -> {
                    state.set(other[0].getVisibility());
                    frame.countDown();
                });
            });
            assertTrue(frame.await(5, TimeUnit.SECONDS));
            assertEquals(View.VISIBLE, state.get());
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(
                    () -> FeatureFlags.hideShareCreateGroup = enabled);
        }
    }

    @Test public void shareActionsMatchCopyLinkAndStayCenteredAfterRelayout() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            int iconId = View.generateViewId();
            int rowId = View.generateViewId();
            FrameLayout list = new FrameLayout(context);
            list.setId(rowId);
            FrameLayout highItem = action(context, iconId);
            FrameLayout nativeItem = action(context, iconId);
            list.addView(highItem);
            list.addView(nativeItem);
            list.layout(0, 0, 1080, 319);
            highItem.layout(31, 0, 215, 235);
            nativeItem.layout(862, 42, 1035, 277);

            ShareSheetCleanupHook.alignActionButtons(list, rowId, iconId);
            assertEquals(42f, highItem.getTranslationY(), 0f);
            assertEquals(0f, nativeItem.getTranslationY(), 0f);
            ShareSheetCleanupHook.alignActionButtons(list, rowId, iconId);
            assertEquals(42f, highItem.getTranslationY(), 0f);
            assertEquals(0f, nativeItem.getTranslationY(), 0f);

            // Native recycling may later lay out the same item at the correct height.
            highItem.layout(31, 42, 215, 277);
            ShareSheetCleanupHook.alignActionButtons(list, rowId, iconId);
            assertEquals(0f, highItem.getTranslationY(), 0f);
            assertEquals(31, highItem.getLeft());
            assertEquals(235, highItem.getHeight());
        });
    }

    @Test public void shareAlignmentUsesAvailableHeightAndIgnoresOtherRows() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            int rowId = View.generateViewId();
            int iconId = View.generateViewId();
            FrameLayout root = new FrameLayout(context);
            FrameLayout list = new FrameLayout(context);
            list.setId(rowId);
            list.setPadding(0, 10, 0, 30);
            FrameLayout item = action(context, iconId);
            FrameLayout unrelated = action(context, iconId);
            root.addView(list);
            root.addView(unrelated);
            list.addView(item);
            list.layout(0, 0, 200, 320);
            item.layout(0, 0, 100, 200);
            unrelated.layout(0, 0, 100, 200);
            ShareSheetCleanupHook.alignActionButtons(root, rowId, iconId);
            assertEquals(50f, item.getTranslationY(), 0f);
            assertEquals(0f, unrelated.getTranslationY(), 0f);
        });
    }

    @Test public void shareButtonsUseEqualSlotsRegardlessOfLabelWidthAndOldMargins() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            int rowId = View.generateViewId();
            int iconId = View.generateViewId();
            FrameLayout row = new FrameLayout(context);
            row.setId(rowId);
            row.setPadding(20, 0, 20, 0);
            int slotWidth = Math.round(100 * context.getResources().getDisplayMetrics().density);
            row.layout(0, 0, 5 * slotWidth + 40, 320);
            for (int oldWidth : new int[]{184, 155, 259, 137, 173}) {
                FrameLayout item = action(context, iconId);
                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(oldWidth, 235);
                params.setMargins(12, 0, 12, 0);
                row.addView(item, params);
            }
            ShareSheetCleanupHook.alignActionButtons(row, rowId, iconId);
            for (int i = 0; i < row.getChildCount(); i++) {
                ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) row.getChildAt(i).getLayoutParams();
                assertEquals(slotWidth, params.width);
                assertEquals(0, params.getMarginStart());
                assertEquals(0, params.getMarginEnd());
                assertEquals(235, params.height);
            }
        });
    }

    @Test public void shareRowNeverCancelsDrawWhileResizedItemsAwaitLayout() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            int iconId = View.generateViewId();
            int slotWidth = Math.round(100 * context.getResources().getDisplayMetrics().density);
            FrameLayout row = new FrameLayout(context);
            row.layout(0, 0, 5 * slotWidth, 319);
            FrameLayout item = action(context, iconId);
            row.addView(item, new FrameLayout.LayoutParams(184, 235));
            item.layout(31, 0, 215, 235);
            // The Reels comment share sheet never re-lays out this row. Cancelling the draw
            // until it did froze the whole Instagram window until the user scrolled.
            ViewTreeObserver.OnPreDrawListener listener = ShareSheetCleanupHook.alignBeforeDraw(row, iconId);
            for (int frame = 0; frame < 3; frame++) assertTrue(listener.onPreDraw());
            assertEquals(slotWidth, item.getLayoutParams().width);
        });
    }

    private static FrameLayout action(Context context, int iconId) {
        FrameLayout item = new FrameLayout(context);
        View icon = new View(context);
        icon.setId(iconId);
        item.addView(icon, new FrameLayout.LayoutParams(40, 40));
        return item;
    }
}
