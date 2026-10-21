package ps.reso.instaeclipse.utils.feature;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class FeatureBatchStatusTest {
    private final Map<Field, Object> originalFlags = new LinkedHashMap<>();

    @Before public void saveFlags() throws Exception {
        for (Field field : FeatureFlags.class.getFields()) originalFlags.put(field, field.get(null));
    }

    @After public void restoreFlags() throws Exception {
        for (Map.Entry<Field, Object> entry : originalFlags.entrySet()) entry.getKey().set(null, entry.getValue());
        FeatureManager.refreshFeatureStatus();
    }

    @Test public void ghostChildrenAreInactiveUntilTheirParentIsOn() {
        FeatureFlags.manualStorySeen = true;
        FeatureFlags.showUnsentIndicator = true;
        FeatureFlags.isGhostStory = false;
        FeatureFlags.keepUnsentMessages = false;
        FeatureManager.refreshFeatureStatus();
        assertFalse(FeatureStatusTracker.getStatus().containsKey("ManualStorySeen"));
        assertFalse(FeatureStatusTracker.getStatus().containsKey("UnsentIndicator"));
        FeatureFlags.isGhostStory = true;
        FeatureFlags.keepUnsentMessages = true;
        FeatureManager.refreshFeatureStatus();
        assertTrue(FeatureStatusTracker.getStatus().containsKey("ManualStorySeen"));
        assertTrue(FeatureStatusTracker.getStatus().containsKey("UnsentIndicator"));
    }

    @Test public void disablingParentPreservesChildPreferenceForLater() {
        FeatureFlags.manualStorySeen = true;
        FeatureFlags.showUnsentIndicator = true;
        FeatureFlags.isGhostStory = true;
        FeatureFlags.keepUnsentMessages = true;
        FeatureManager.refreshFeatureStatus();
        FeatureFlags.isGhostStory = false;
        FeatureFlags.keepUnsentMessages = false;
        FeatureManager.refreshFeatureStatus();
        assertFalse(FeatureStatusTracker.getStatus().containsKey("ManualStorySeen"));
        assertFalse(FeatureStatusTracker.getStatus().containsKey("UnsentIndicator"));
        assertTrue(FeatureFlags.manualStorySeen);
        assertTrue(FeatureFlags.showUnsentIndicator);
    }

    @Test public void persistentFollowLabelIsIndependentOfFollowerToast() {
        FeatureFlags.showFollowerToast = false;
        FeatureFlags.showFollowStatusLabel = true;
        FeatureManager.refreshFeatureStatus();
        assertTrue(FeatureStatusTracker.getStatus().containsKey("FollowStatusLabel"));
        // Both profile-label renderers report under one key (upstream's "ProfileFollowsYou" is gone).
        assertFalse(FeatureStatusTracker.getStatus().containsKey("ProfileFollowsYou"));
        assertFalse(FeatureStatusTracker.getStatus().containsKey("FollowerToast"));
        FeatureFlags.showFollowerToast = true;
        FeatureFlags.showFollowStatusLabel = false;
        FeatureManager.refreshFeatureStatus();
        assertFalse(FeatureStatusTracker.getStatus().containsKey("FollowStatusLabel"));
        assertTrue(FeatureStatusTracker.getStatus().containsKey("FollowerToast"));
    }

    @Test public void chatMenuGestureAndOnboardingPromptsReportUnderTheirHookKeys() {
        FeatureFlags.hideChatMenuButton = true;
        FeatureFlags.hideOnboardingPrompts = true;
        FeatureManager.refreshFeatureStatus();
        // Keys the hooks mark: ThreadHeaderMenu.installTapGesture and OnboardingPromptHook's flag.
        assertTrue(FeatureStatusTracker.getStatus().containsKey("ChatMenuTripleTap"));
        assertTrue(FeatureStatusTracker.getStatus().containsKey("hideOnboardingPrompts"));
        // The old location switch was folded into hideOnboardingPrompts.
        assertFalse(FeatureStatusTracker.getStatus().containsKey("BlockLocationPrompts"));
        FeatureFlags.hideChatMenuButton = false;
        FeatureFlags.hideOnboardingPrompts = false;
        FeatureManager.refreshFeatureStatus();
        assertFalse(FeatureStatusTracker.getStatus().containsKey("ChatMenuTripleTap"));
        assertFalse(FeatureStatusTracker.getStatus().containsKey("hideOnboardingPrompts"));
    }
}
