package ps.reso.instaeclipse;

import android.app.Application;
import android.content.SharedPreferences;
import android.os.Process;

import java.util.LinkedHashMap;
import java.util.Map;

import ps.reso.instaeclipse.mods.media.FrameSessionCache;
import ps.reso.instaeclipse.utils.core.BackgroundAccess;
import ps.reso.instaeclipse.utils.core.CommonUtils;
import ps.reso.instaeclipse.utils.core.CompanionSettings;
import ps.reso.instaeclipse.utils.core.CompanionUiPrefs;
import ps.reso.instaeclipse.utils.core.RemotePrefsMirror;
import ps.reso.instaeclipse.utils.core.SettingsSchema;
import ps.reso.instaeclipse.utils.log.Logging;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public class InstaEclipseApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        CompanionUiPrefs.migrate(this);
        migrateSettings();
        CommonUtils.dropStaleCacheKeys(this);
        clearLostFolder();
        RemotePrefsMirror.start(this);
        new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            Logging.init(this, "instaeclipse_companion.log");
            if (!FrameSessionCache.cleanupOrphans(getCacheDir()))
                ModuleLog.detail("(IE|Frame) Startup cache cleanup incomplete (left-over frame files are removed later)");
        }, "ie-app-init").start();
    }

    private void migrateSettings() {
        try {
            SharedPreferences cache = CommonUtils.companionCache(this);
            Map<String, Object> edits = SettingsSchema.migrate(cache.getAll());
            if (edits.isEmpty()) return;
            SharedPreferences.Editor editor = cache.edit();
            for (Map.Entry<String, Object> edit : edits.entrySet()) {
                if (edit.getValue() instanceof Boolean) editor.putBoolean(edit.getKey(), (Boolean) edit.getValue());
                else editor.remove(edit.getKey());
            }
            editor.apply();
        } catch (Throwable ignored) {}
    }

    private void clearLostFolder() {
        try {
            String uri = CommonUtils.companionCache(this).getString("downloaderCustomUri", "");
            if (!BackgroundAccess.folderGrantLost(this, uri)) return;
            LinkedHashMap<String, Object> folder = new LinkedHashMap<>();
            folder.put("downloaderCustomUri", "");
            folder.put("downloaderCustomPath", "");
            CompanionSettings.putAll(this, folder);
        } catch (Throwable ignored) {}
    }
}
