package ps.reso.instaeclipse.utils.ui;

import android.app.Activity;
import android.view.View;

import com.google.android.material.snackbar.Snackbar;

import java.util.Collection;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.core.CommonUtils;
import ps.reso.instaeclipse.utils.core.InstagramRestarter;

public final class CompanionRestartHint {

    private CompanionRestartHint() {}

    public static void show(Activity activity, Collection<String> keys) {
        if (activity == null || keys == null || keys.isEmpty() || activity.isFinishing()) return;
        View root = activity.findViewById(android.R.id.content);
        if (root == null) return;
        Snackbar bar = Snackbar.make(root, R.string.cat_restart_snackbar, Snackbar.LENGTH_LONG)
                .setAction(R.string.home_action_restart, v -> restart(activity));
        View anchor = activity.findViewById(R.id.bottom_navigation);
        if (anchor != null && anchor.isShown()) bar.setAnchorView(anchor);
        bar.show();
    }

    private static void restart(Activity activity) {
        if (activity.isFinishing()) return;
        String pkg = InstagramRestarter.activePackage(activity, InstagramRestarter.installedPackages(activity));
        InstagramRestarter.restart(activity, pkg != null ? pkg : CommonUtils.IG_PACKAGE_NAME, outcome -> { });
    }
}
