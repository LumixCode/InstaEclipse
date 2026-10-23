package ps.reso.instaeclipse.utils.ui;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.toast.IeToast;

public final class Links {

    private Links() {}

    public static void open(Context context, String url) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            IeToast.makeText(context, context.getString(R.string.links_no_app), Toast.LENGTH_SHORT).show();
        }
    }
}
