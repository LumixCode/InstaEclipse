package ps.reso.instaeclipse.utils.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class HookTargetGuardTest {

    private static final String CDD = "android_purge_26_q3_ContentDeepDiveUseCase_getUiState";

    @SuppressWarnings("unused")
    static final class LambdaGroup {
        final int $t = 0;
    }

    @SuppressWarnings("unused")
    static final class LongTypeField {
        final long $t = 0;
    }

    static final class Plain {}

    abstract static class AbstractBase {}

    static class OpenClass {}

    @Test
    public void ownMarkerFirstIsAccepted() {
        assertTrue(HookTargetGuard.ownsMarker(Arrays.asList(CDD, ""), CDD));
    }

    @Test
    public void nonMarkerStringsBeforeTheMarkerAreIgnored() {
        assertTrue(HookTargetGuard.ownsMarker(Arrays.asList("kind", "unknown", CDD), CDD));
    }

    @Test
    public void markerInlinedAfterTheCallersOwnIsRejected() {
        // IG 451 X.047j.A0V: the whole Clips MediaInfoUseCase.getUiState with the CDD use-case inlined.
        List<String> strings = Arrays.asList(
                "android_purge_26_q3_MediaInfoUseCase_getUiState",
                "MediaInfoUseCase:recipeSheet",
                "android_purge_26_q3_MediaInfoBannersGroupUseCase_getUiState",
                CDD);
        assertFalse(HookTargetGuard.ownsMarker(strings, CDD));
    }

    @Test
    public void missingMarkerIsRejected() {
        assertFalse(HookTargetGuard.ownsMarker(Collections.singletonList("kind"), CDD));
        assertFalse(HookTargetGuard.ownsMarker(Collections.emptyList(), CDD));
        assertFalse(HookTargetGuard.ownsMarker(null, CDD));
    }

    @Test
    public void lambdaGroupWithSwitchIsMerged() {
        assertTrue(HookTargetGuard.isMergedLambda(LambdaGroup.class, Arrays.asList(0x08, 0x52, 0x2b, 0x54)));
    }

    @Test
    public void lambdaGroupFieldWithoutSwitchIsNotMerged() {
        assertFalse(HookTargetGuard.isMergedLambda(LambdaGroup.class, Arrays.asList(0x08, 0x52, 0x11)));
        assertFalse(HookTargetGuard.isMergedLambda(LambdaGroup.class, null));
    }

    @Test
    public void switchWithoutIntLambdaGroupFieldIsNotMerged() {
        assertFalse(HookTargetGuard.isMergedLambda(Plain.class, Collections.singletonList(0x2b)));
        assertFalse(HookTargetGuard.isMergedLambda(LongTypeField.class, Collections.singletonList(0x2b)));
        assertFalse(HookTargetGuard.isMergedLambda(null, Collections.singletonList(0x2b)));
    }

    @Test
    public void onlyFinalConcreteClassesOwnTheirMethods() {
        assertTrue(HookTargetGuard.ownClass(Plain.class));
        assertFalse(HookTargetGuard.ownClass(AbstractBase.class));
        assertFalse(HookTargetGuard.ownClass(OpenClass.class));
        assertFalse(HookTargetGuard.ownClass(null));
    }
}
