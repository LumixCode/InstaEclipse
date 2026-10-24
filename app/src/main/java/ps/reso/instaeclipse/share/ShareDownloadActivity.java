package ps.reso.instaeclipse.share;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.mods.media.ShareLinkDownloadHook;

/**
 * "Download with InstaEclipse" in the Android share sheet (#236): takes a shared Instagram
 * post/reel link and opens it in Instagram with a marker; the module (running in Instagram)
 * then saves the media and sends Instagram back to the background. No UI of its own.
 */
public class ShareDownloadActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ShareLinks.Link link = findLink(getIntent());
        if (link == null) {
            ps.reso.instaeclipse.utils.toast.IeToast.makeText(this, R.string.share_dl_no_link, Toast.LENGTH_SHORT).show();
        } else {
            boolean opaque = link.kind == ShareLinks.Kind.OPAQUE;
            Intent ig = new Intent(Intent.ACTION_VIEW, Uri.parse(link.canonicalUrl))
                    .setPackage("com.instagram.android")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (!opaque) ig.putExtra(ShareLinkDownloadHook.EXTRA, true);
            try {
                startActivity(ig);
                if (opaque) ps.reso.instaeclipse.utils.toast.IeToast.makeText(this, R.string.share_dl_opened_in_instagram, Toast.LENGTH_LONG).show();
            } catch (ActivityNotFoundException e) {
                ps.reso.instaeclipse.utils.toast.IeToast.makeText(this, R.string.share_dl_no_instagram, Toast.LENGTH_SHORT).show();
            }
        }
        finish();
    }

    private static ShareLinks.Link findLink(Intent i) {
        if (i == null) return null;
        String text = null;
        if (Intent.ACTION_SEND.equals(i.getAction())) {
            CharSequence t = i.getCharSequenceExtra(Intent.EXTRA_TEXT);
            text = t != null ? t.toString() : null;
        } else if (Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null) {
            text = i.getData().toString();
        }
        return ShareLinks.parse(text);
    }
}
