package ps.reso.instaeclipse.utils.version;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AlertDialog;

import ps.reso.instaeclipse.BuildConfig;
import ps.reso.instaeclipse.MainActivity;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.core.CompanionUiPrefs;
import ps.reso.instaeclipse.utils.core.ExportBrand;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.ui.Links;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VersionCheckUtility {

    private static final String RELEASES_API = "https://api.github.com/repos/" + ExportBrand.REPO_SLUG + "/releases/latest";
    private static final int TIMEOUT_MS = 10_000;
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private static final int WHATS_NEW_CHARS = 800;
    private static final String[] FIELDS = {"tag_name", "html_url", "body", "draft", "prerelease"};

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    public static void checkForUpdates(Activity activity) {
        SharedPreferences ui = CompanionUiPrefs.get(activity);
        long now = System.currentTimeMillis();
        if (!UpdateCheckPolicy.shouldCheck(now, ui.getLong(CompanionUiPrefs.LAST_UPDATE_CHECK, 0), BuildConfig.DEBUG)) return;
        ui.edit().putLong(CompanionUiPrefs.LAST_UPDATE_CHECK, now).apply();
        WeakReference<Activity> ref = new WeakReference<>(activity);
        EXECUTOR.execute(() -> {
            ReleaseInfo result = fetch();
            if (result == null) return;
            new Handler(Looper.getMainLooper()).post(() -> {
                Activity a = ref.get();
                if (a == null || a.isFinishing() || a.isDestroyed()) return;
                handleVersionCheckResult(a, ui, result);
            });
        });
    }

    private static ReleaseInfo fetch() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(RELEASES_API).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", "InstaEclipse/" + BuildConfig.VERSION_NAME);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            byte[] data = readCapped(connection.getInputStream());
            if (data == null) return null;
            JSONObject json = new JSONObject(new String(data, StandardCharsets.UTF_8));
            Map<String, Object> release = new HashMap<>();
            for (String field : FIELDS) {
                if (!json.isNull(field)) release.put(field, json.opt(field));
            }
            return ReleaseInfo.from(release, ExportBrand.REPO_SLUG);
        } catch (Exception e) {
            ModuleLog.detail("(InstaEclipse | Update): check failed: " + e.getMessage());
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static byte[] readCapped(InputStream in) throws IOException {
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = stream.read(buffer)) != -1) {
                if (out.size() + n > MAX_RESPONSE_BYTES) return null;
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static void handleVersionCheckResult(Activity activity, SharedPreferences ui, ReleaseInfo release) {
        String skipped = ui.getString(CompanionUiPrefs.SKIPPED_VERSION, null);
        if (UpdateCheckPolicy.shouldPrompt(release.version, BuildConfig.VERSION_NAME, skipped)) {
            showUpdateDialog(activity, ui, release);
        }
    }

    private static void showUpdateDialog(Activity activity, SharedPreferences ui, ReleaseInfo release) {
        StringBuilder message = new StringBuilder(activity.getString(R.string.ig_update_message, release.version));
        String body = release.body == null ? "" : release.body.trim();
        if (!body.isEmpty()) {
            if (body.length() > WHATS_NEW_CHARS) {
                int end = Character.isHighSurrogate(body.charAt(WHATS_NEW_CHARS - 1)) ? WHATS_NEW_CHARS - 1 : WHATS_NEW_CHARS;
                body = body.substring(0, end).trim() + "…";
            }
            message.append("\n\n").append(activity.getString(R.string.update_whats_new)).append('\n').append(body);
        }
        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(activity.getString(R.string.ig_update_title))
                .setMessage(message)
                .setPositiveButton(activity.getString(R.string.ig_update_button), (dialogInterface, which) -> Links.open(activity, release.url))
                .setNegativeButton(activity.getString(R.string.ig_update_later), (dialogInterface, which) -> dialogInterface.dismiss())
                .setNeutralButton(activity.getString(R.string.update_skip_version), (dialogInterface, which) ->
                        ui.edit().putString(CompanionUiPrefs.SKIPPED_VERSION, release.version).apply())
                .create();
        dialog.setOnDismissListener(d -> MainActivity.noticeClosed(activity, MainActivity.NOTICE_UPDATE));
        MainActivity.noticeOpened(activity, MainActivity.NOTICE_UPDATE);
        dialog.show();
    }
}
