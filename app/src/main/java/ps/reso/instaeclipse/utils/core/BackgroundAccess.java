package ps.reso.instaeclipse.utils.core;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import ps.reso.instaeclipse.R;

public final class BackgroundAccess {

    public static final String FOLDER_LOST_DISMISSED_FOR = "folderLostDismissedFor";

    private BackgroundAccess() {}

    public static boolean unrestricted(Context ctx) {
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        return pm == null || pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
    }

    public static void askIfNeeded(Context ctx, boolean always) {
        SharedPreferences prefs = CompanionUiPrefs.get(ctx);
        if (unrestricted(ctx) || (!always && prefs.getBoolean(CompanionUiPrefs.BACKGROUND_ACCESS_ASKED, false))) return;
        prefs.edit().putBoolean(CompanionUiPrefs.BACKGROUND_ACCESS_ASKED, true).apply();
        new MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.background_access_title)
                .setMessage(R.string.background_access_message)
                .setPositiveButton(R.string.background_access_allow, (d, w) -> request(ctx))
                .setNegativeButton(R.string.background_access_later, null)
                .show();
    }

    @SuppressLint("BatteryLife")
    private static void request(Context ctx) {
        try {
            ctx.startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + ctx.getPackageName())));
        } catch (Throwable t) {
            try {
                ctx.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + ctx.getPackageName())));
            } catch (Throwable ignored) { }
        }
    }

    public static boolean folderGrantLost(Context ctx, String uri) {
        if (uri == null || uri.isEmpty() || !uri.startsWith("content://")) return false;
        for (android.content.UriPermission p : ctx.getContentResolver().getPersistedUriPermissions())
            if (p.getUri().toString().equals(uri) && p.isWritePermission()) return false;
        return true;
    }

    public static void askToRechooseFolder(Context ctx, String uri, Runnable choose, Runnable useDefault) {
        SharedPreferences ui = CompanionUiPrefs.get(ctx);
        if (uri.equals(ui.getString(FOLDER_LOST_DISMISSED_FOR, null))) return;
        new MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.folder_access_lost_title)
                .setMessage(R.string.folder_access_lost_message)
                .setPositiveButton(R.string.folder_access_lost_choose, (d, w) -> choose.run())
                .setNeutralButton(R.string.feat_use_default_folder, (d, w) -> useDefault.run())
                .setNegativeButton(R.string.background_access_later,
                        (d, w) -> ui.edit().putString(FOLDER_LOST_DISMISSED_FOR, uri).apply())
                .show();
    }
}
