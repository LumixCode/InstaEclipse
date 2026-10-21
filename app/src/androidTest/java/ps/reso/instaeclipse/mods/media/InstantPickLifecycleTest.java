package ps.reso.instaeclipse.mods.media;

import android.app.Activity;
import android.graphics.Bitmap;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

@RunWith(AndroidJUnit4.class)
public class InstantPickLifecycleTest {
    @After public void reset() {
        InstantUploadHook.clearPick();
    }

    @Test public void pickLivesOnlyWhileItsCameraStaysOnScreen() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Activity camera = new Activity();
            Activity elsewhere = new Activity();
            Bitmap pick = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            InstantUploadHook.Lifecycle lifecycle = new InstantUploadHook.Lifecycle();

            // Android's order: the picker pauses and stops the camera, the result arms the pick,
            // then the camera resumes. The pick must survive that.
            lifecycle.onActivityPaused(camera);
            lifecycle.onActivityStopped(camera);
            InstantUploadHook.armPick(pick, camera);
            lifecycle.onActivityResumed(camera);
            assertSame(pick, InstantUploadHook.pendingBitmap);

            // Leaving afterwards (lock screen, Home) drops it: a later shot must be real.
            lifecycle.onActivityPaused(camera);
            assertNull(InstantUploadHook.pendingBitmap);

            InstantUploadHook.armPick(pick, camera);
            lifecycle.onActivityResumed(elsewhere);
            assertNull(InstantUploadHook.pendingBitmap);

            InstantUploadHook.armPick(pick, camera);
            lifecycle.onActivityDestroyed(camera);
            assertNull(InstantUploadHook.pendingBitmap);
        });
    }
}
