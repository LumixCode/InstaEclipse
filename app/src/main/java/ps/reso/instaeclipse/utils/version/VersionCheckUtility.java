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
    private static final String REPO = "ReSo7200/InstaEclipse";
    private static final int TIMEOUT_MS = 10_000;
    private static final int MAX_RESPONSE_CHARS = 16 * 1024;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    public static void checkForUpdates(Activity activity) {
        if (!UpdateCheckPolicy.shouldCheck(System.currentTimeMillis(), 0, BuildConfig.DEBUG)) return;
        // Hold the activity weakly: the request can outlive a rotation or back press.
        WeakReference<Activity> ref = new WeakReference<>(activity);
        EXECUTOR.execute(() -> {
            VersionCheck check = fetch();
            // Offline or unreachable: an update check must never interrupt the app (fetch() logs it).
            ReleaseInfo result = check == null ? null : ReleaseInfo.of(check.getLatestVersion(), check.getUpdateUrl(), null, REPO);
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

    private static void handleVersionCheckResult(Activity activity, ReleaseInfo release) {
        if (UpdateCheckPolicy.shouldPrompt(release.version, BuildConfig.VERSION_NAME, null)) {
            showUpdateDialog(activity, release.url, release.version);
        }
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
