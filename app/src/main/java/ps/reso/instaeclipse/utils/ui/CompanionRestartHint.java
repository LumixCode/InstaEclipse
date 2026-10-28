package ps.reso.instaeclipse.utils.ui;

import android.app.Activity;
import android.view.View;

import com.google.android.material.snackbar.Snackbar;

import java.util.Collection;
import java.util.function.Consumer;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.core.CommonUtils;
import ps.reso.instaeclipse.utils.core.InstagramRestarter;

public final class CompanionRestartHint {

    private static volatile Consumer<Collection<String>> observer;

    private CompanionRestartHint() {}

    public static void observeForTest(Consumer<Collection<String>> testObserver) {
        observer = testObserver;
    }

    public static void show(Activity activity, Collection<String> keys) {
        show(activity, keys, null);
    }

    public static void show(Activity activity, Collection<String> keys, Runnable after) {
        Consumer<Collection<String>> watching = observer;
        if (watching != null) watching.accept(keys);
        Snackbar bar = bar(activity, keys);
        if (bar == null) {
            if (after != null) after.run();
            return;
        }
        if (after != null) {
            bar.addCallback(new Snackbar.Callback() {
                @Override
                public void onDismissed(Snackbar dismissed, int event) {
                    after.run();
                }
            });
        }
        bar.show();
    }

    private static Snackbar bar(Activity activity, Collection<String> keys) {
        if (activity == null || keys == null || keys.isEmpty() || activity.isFinishing()) return null;
        View root = activity.findViewById(android.R.id.content);
        if (root == null) return null;
        Snackbar bar = Snackbar.make(root, R.string.cat_restart_snackbar, Snackbar.LENGTH_LONG)
                .setAction(R.string.home_action_restart, v -> restart(activity));
        View anchor = activity.findViewById(R.id.bottom_navigation);
        if (anchor != null && anchor.isShown()) bar.setAnchorView(anchor);
        return bar;
    }

    private static void restart(Activity activity) {
        if (activity.isFinishing()) return;
        String pkg = InstagramRestarter.activePackage(activity, InstagramRestarter.installedPackages(activity));
        InstagramRestarter.restart(activity, pkg != null ? pkg : CommonUtils.IG_PACKAGE_NAME, outcome -> { });
    }
}
