package ps.reso.instaeclipse.mods.media;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Build;
import android.os.ext.SdkExtensions;
import android.provider.MediaStore;

final class InstantPicker {
    @SuppressLint("InlinedApi")
    static final String PICK_IMAGES = MediaStore.ACTION_PICK_IMAGES;

    private InstantPicker() {}

    static String action(int sdk, int ext) {
        return sdk >= 33 || (sdk >= 30 && ext >= 2) ? PICK_IMAGES : Intent.ACTION_GET_CONTENT;
    }

    static Intent intent(int sdk, int ext) {
        Intent intent = new Intent(action(sdk, ext)).setType("image/*");
        if (Intent.ACTION_GET_CONTENT.equals(intent.getAction())) intent.addCategory(Intent.CATEGORY_OPENABLE);
        return intent;
    }

    static int extension() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ? SdkExtensions.getExtensionVersion(Build.VERSION_CODES.R) : 0;
    }
}
