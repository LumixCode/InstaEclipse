package ps.reso.instaeclipse.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.core.CompanionSettings;
import ps.reso.instaeclipse.utils.profile.SpoofAvatar;

public class ProfilePhotoPickerActivity extends Activity {

    private static final int PICK = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) return;
        try {
            startActivityForResult(new ActivityResultContracts.PickVisualMedia().createIntent(this,
                    new PickVisualMediaRequest.Builder()
                            .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                            .build()), PICK);
        } catch (Throwable t) {
            finish();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Uri uri = resultCode == RESULT_OK && data != null ? data.getData() : null;
        if (requestCode != PICK || uri == null) {
            finish();
            return;
        }
        android.content.Context app = getApplicationContext();
        new Thread(() -> {
            String b64 = SpoofAvatar.encode(app.getContentResolver(), uri);
            if (b64 != null) save(app, b64);
            runOnUiThread(() -> {
                ps.reso.instaeclipse.utils.toast.IeToast.makeText(app, getString(b64 == null
                        ? R.string.ig_profile_spoofer_photo_failed : R.string.ig_profile_spoofer_photo_saved), Toast.LENGTH_SHORT).show();
                finish();
            });
        }, "IE-ProfilePhoto").start();
    }

    public static void save(android.content.Context context, String b64) {
        CompanionSettings.put(context, "spoofAvatar", b64);
    }
}
