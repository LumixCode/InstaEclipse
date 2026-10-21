package ps.reso.instaeclipse;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import ps.reso.instaeclipse.utils.core.ModuleResources;
import ps.reso.instaeclipse.utils.dialog.DialogUtils;
import ps.reso.instaeclipse.utils.i18n.I18n;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ModuleResourcesInstrumentedTest {
    @Test public void foreignHostLoadsModuleStringsAndDrawsIconsWithoutXposedResources() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Context host = InstrumentationRegistry.getInstrumentation().getContext();
        ModuleResources.init(host, null);
        assertEquals(target.getString(R.string.ig_reel_limit_title), I18n.t(host, R.string.ig_reel_limit_title));

        // Simulate an embedded module whose companion cannot be resolved by package name.
        Context embeddedHost = new ContextWrapper(host) {
            @Override public Context createPackageContext(String name, int flags) throws PackageManager.NameNotFoundException {
                throw new PackageManager.NameNotFoundException(name);
            }
        };
        ModuleResources.init(embeddedHost, target.getApplicationInfo().sourceDir);
        try {
            assertEquals(target.getString(R.string.ig_reel_limit_usage, 42, 100),
                    I18n.t(embeddedHost, R.string.ig_reel_limit_usage, 42, 100));
            for (int id : new int[]{R.string.ig_dialog_downloader_voice,
                    R.string.ig_feature_unsent_indicator, R.string.ig_feature_unsent_indicator_requires,
                    R.string.ig_feature_show_unsent_button, R.string.ig_feature_keep_unsent_in_chat,
                    R.string.ig_dialog_misc_exact_timestamps, R.string.ig_dialog_misc_profile_follows_label,
                    R.string.ig_dialog_open_links_externally,
                    R.string.ig_feature_manual_story_seen, R.string.ig_feature_manual_story_seen_requires,
                    R.string.ig_hide_chat_menu_button, R.string.ig_hide_chat_menu_button_hint}) {
                assertEquals("Missing embedded settings label " + id, target.getString(id), I18n.t(embeddedHost, id));
            }
            for (int id : new int[]{R.drawable.ic_movie, R.drawable.ic_timer, R.drawable.ic_notification,
                    R.drawable.ic_chevron_right, R.drawable.ic_eye_off, R.drawable.ic_delete,
                    R.drawable.ic_download, R.drawable.ic_chat, R.drawable.ic_at,
                    R.drawable.ic_link, R.drawable.ic_eye, R.drawable.ic_tune}) {
                Drawable icon = DialogUtils.moduleIcon(embeddedHost, id, Color.WHITE);
                assertNotNull("Missing icon " + id, icon);
                Bitmap image = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
                icon.setBounds(0, 0, 64, 64);
                icon.draw(new Canvas(image));
                int[] pixels = new int[64 * 64];
                image.getPixels(pixels, 0, 64, 0, 0, 64, 64);
                boolean visible = false;
                for (int pixel : pixels) visible |= Color.alpha(pixel) > 0;
                assertTrue("Blank icon " + id, visible);
                image.recycle();
            }
        } finally {
            ModuleResources.init(host, null);
        }
    }
}
