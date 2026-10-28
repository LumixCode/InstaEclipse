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
        if (activity == null || keys == null || keys.isEmpty() || activity.isFinishing()) {
            if (after != null) after.run();
            return;
        }
        String pkg = InstagramRestarter.activePackage(activity, InstagramRestarter.installedPackages(activity));
        if (!InstagramRestarter.ping(activity, pkg, running -> present(activity, running, after))) {
            present(activity, true, after);
        }
    }

    private static void present(Activity activity, boolean running, Runnable after) {
        Snackbar bar = bar(activity, running);
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

    private static Snackbar bar(Activity activity, boolean running) {
        if (activity.isFinishing() || activity.isDestroyed()) return null;
        View root = activity.findViewById(android.R.id.content);
        if (root == null) return null;
        Snackbar bar = running
                ? Snackbar.make(root, R.string.cat_restart_snackbar, Snackbar.LENGTH_LONG)
                        .setAction(R.string.home_action_restart, v -> restart(activity))
                : Snackbar.make(root, R.string.home_restart_applies_on_open, Snackbar.LENGTH_LONG);
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
