package ps.reso.instaeclipse.mods.extras;

import org.junit.Test;

import java.io.StreamTokenizer;
import java.io.StringReader;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.Assert.*;

public class OnboardingPromptPolicyTest {
    private static final String LOCATION = "com.bloks.www.bloks.ig.ndx.ls.entry.screen";
    private static final String CONTACTS = "com.bloks.www.bloks.ig.ndx.ci.entry.screen";
    private static final String FINE = "android.permission.ACCESS_FINE_LOCATION";
    private static final String COARSE = "android.permission.ACCESS_COARSE_LOCATION";
    private static final String BACKGROUND = "android.permission.ACCESS_BACKGROUND_LOCATION";
    private static final String CAMERA = "android.permission.CAMERA";

    // Shapes of Instagram 446/451: the opener (X.0LCp / X.0MxJ) keeps the app id in an obfuscated
    // String field; the static CDS presenter gets it inside its screen-data argument (X.0S9v / X.0mOi).
    static class Opener { final String appId; final String label = "bloks"; final Object params = null; Opener(String id) { appId = id; } }
    static class SubOpener extends Opener { final String extra = "x"; SubOpener(String id) { super(id); } }
    static class ScreenData { final long ts = -1; final String name = null; final String appId; ScreenData(String id) { appId = id; } }
    static class Holder { static String appId = LOCATION; final Object screen; Holder(Object s) { screen = s; } }

    // LocationPluginImpl's callback shapes: 446 X.0mue / X.066d, 451 X.0oiH / X.06t8 (permission
    // callback and its result enum), 446 X.0Mom / 451 X.0NyM (location listener).
    enum Result { GRANTED, DENIED, DENIED_DONT_ASK_AGAIN }
    enum Unrelated { ON, OFF }
    interface PermissionCallback { void onResult(Result result); boolean onPromptShown(); }
    interface LocationListener { void onError(Exception e); void onLocationChanged(Object location); }
    interface UnrelatedCallback { void onState(Unrelated state); }

    @Test public void recognisesBothSetupPagesByAppId() {
        assertEquals(LOCATION, OnboardingPromptPolicy.skippedScreen(new Opener(LOCATION)));
        assertEquals(CONTACTS, OnboardingPromptPolicy.skippedScreen(new Opener(CONTACTS)));
        assertEquals(LOCATION, OnboardingPromptPolicy.skippedScreen(new SubOpener(LOCATION)));
        assertEquals(CONTACTS, OnboardingPromptPolicy.skippedScreen(new ScreenData(CONTACTS)));
    }

    @Test public void leavesOtherScreensAlone() {
        assertNull(OnboardingPromptPolicy.skippedScreen(new Opener("com.bloks.www.bloks.ig.ndx.np.entry.screen")));
        assertNull(OnboardingPromptPolicy.skippedScreen(new Opener(null)));
        assertNull(OnboardingPromptPolicy.skippedScreen(null));
    }

    @Test public void readsOnlyTheObjectsOwnInstanceFields() {
        // Statics and nested objects never count: only the opener or screen data itself names the page.
        assertNull(OnboardingPromptPolicy.skippedScreen(new Holder(new Opener(LOCATION))));
    }

    @Test public void neverReadsFrameworkObjects() {
        // StreamTokenizer.sval is a public String field, so only the boot-class rule keeps it out;
        // on Android the same rule keeps maps, boxed ints and other framework arguments out.
        StreamTokenizer tokenizer = new StreamTokenizer(new StringReader(""));
        tokenizer.sval = LOCATION;
        assertNull(OnboardingPromptPolicy.skippedScreen(tokenizer));
        assertNull(OnboardingPromptPolicy.skippedScreen(LOCATION));
    }

    @Test public void ungrantedLocationIsRemovedAndOthersKept() {
        String[] requested = {CAMERA, FINE, COARSE, BACKGROUND};
        assertArrayEquals(new String[]{CAMERA},
                OnboardingPromptPolicy.withoutUngrantedLocation(requested, granted()));
    }

    @Test public void locationOnlyRequestBecomesEmpty() {
        assertEquals(0, OnboardingPromptPolicy.withoutUngrantedLocation(new String[]{COARSE, FINE}, granted()).length);
    }

    @Test public void grantedLocationStaysBecauseAndroidAnswersWithoutDialog() {
        assertArrayEquals(new String[]{COARSE},
                OnboardingPromptPolicy.withoutUngrantedLocation(new String[]{COARSE, FINE}, granted(COARSE)));
    }

    @Test public void unchangedRequestReturnsSameArraySoCallerCanSkipWork() {
        String[] requested = {CAMERA};
        assertSame(requested, OnboardingPromptPolicy.withoutUngrantedLocation(requested, granted()));
        String[] alreadyGranted = {FINE, CAMERA};
        assertSame(alreadyGranted, OnboardingPromptPolicy.withoutUngrantedLocation(alreadyGranted, granted(FINE)));
        assertNull(OnboardingPromptPolicy.withoutUngrantedLocation(null, granted()));
    }

    @Test public void findsPermissionResultCallbackByShape() throws Exception {
        Method method = OnboardingPromptPolicy.resultMethod(PermissionCallback.class);
        assertEquals(PermissionCallback.class.getMethod("onResult", Result.class), method);
        assertSame(Result.DENIED, OnboardingPromptPolicy.deniedConstant(method.getParameterTypes()[0]));
    }

    @Test public void ignoresListenersAndUnrelatedTypes() {
        assertNull(OnboardingPromptPolicy.resultMethod(LocationListener.class));
        assertNull(OnboardingPromptPolicy.resultMethod(UnrelatedCallback.class));
        assertNull(OnboardingPromptPolicy.resultMethod(String.class));
        assertNull(OnboardingPromptPolicy.resultMethod(null));
        assertNull(OnboardingPromptPolicy.deniedConstant(Unrelated.class));
        assertNull(OnboardingPromptPolicy.deniedConstant(String.class));
    }

    private static Predicate<String> granted(String... permissions) {
        Set<String> set = new HashSet<>(Arrays.asList(permissions));
        return set::contains;
    }
}
