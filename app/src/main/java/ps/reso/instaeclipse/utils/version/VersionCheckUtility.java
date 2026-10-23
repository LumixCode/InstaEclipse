package ps.reso.instaeclipse.utils.version;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import ps.reso.instaeclipse.BuildConfig;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.log.ModuleLog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.gson.Gson;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VersionCheckUtility {

    private static final String VERSION_CHECK_URL = "https://raw.githubusercontent.com/ReSo7200/InstaEclipse/refs/heads/main/version.json";
    private static final String RELEASES_PREFIX = "https://github.com/ReSo7200/InstaEclipse/";
    private static final String RELEASES_URL = RELEASES_PREFIX + "releases/latest";
    private static final int TIMEOUT_MS = 10_000;
    private static final int MAX_RESPONSE_CHARS = 16 * 1024;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    public static void checkForUpdates(Activity activity) {
        // Hold the activity weakly: the request can outlive a rotation or back press.
        WeakReference<Activity> ref = new WeakReference<>(activity);
        EXECUTOR.execute(() -> {
            VersionCheck result = fetch();
            // Offline or unreachable: an update check must never interrupt the app (fetch() logs it).
            if (result == null) return;
            new Handler(Looper.getMainLooper()).post(() -> {
                Activity a = ref.get();
                if (a == null || a.isFinishing() || a.isDestroyed()) return;
                handleVersionCheckResult(a, result);
            });
        });
    }

    private static VersionCheck fetch() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(VERSION_CHECK_URL).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return null;

            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                    if (response.length() > MAX_RESPONSE_CHARS) return null;
                }
            }
            return new Gson().fromJson(response.toString(), VersionCheck.class);
        } catch (Exception e) {
            ModuleLog.detail("(InstaEclipse | Update): check failed: " + e.getMessage());
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void handleVersionCheckResult(Activity activity, VersionCheck versionCheck) {
        String latestVersion = versionCheck.getLatestVersion();
        if (latestVersion == null) return;
        // Only prompt when the published version is actually newer (dev builds are often ahead).
        // BuildConfig can't fail like a PackageManager lookup, which would read as "0" and prompt
        // on every start; VersionComparator ignores the "-debug" suffix.
        if (VersionComparator.compare(latestVersion, BuildConfig.VERSION_NAME) > 0) {
            showUpdateDialog(activity, safeUpdateUrl(versionCheck.getUpdateUrl()), latestVersion);
        }
    }

    /** The URL comes from the network: only ever open this project's GitHub pages. */
    private static String safeUpdateUrl(String url) {
        return url != null && url.startsWith(RELEASES_PREFIX) ? url : RELEASES_URL;
    }

    private static void showUpdateDialog(Activity activity, String updateUrl, String newVersion) {
        new MaterialAlertDialogBuilder(activity)
                .setTitle(activity.getString(R.string.ig_update_title))
                .setMessage(activity.getString(R.string.ig_update_message, newVersion))
                .setPositiveButton(activity.getString(R.string.ig_update_button), (dialogInterface, which) -> {
                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(updateUrl));
                    try {
                        activity.startActivity(browserIntent);
                    } catch (Exception ignored) {
                        // No browser installed.
                    }
                })
                .setNegativeButton(activity.getString(R.string.ig_update_later), (dialogInterface, which) -> dialogInterface.dismiss())
                .show();
    }
}
