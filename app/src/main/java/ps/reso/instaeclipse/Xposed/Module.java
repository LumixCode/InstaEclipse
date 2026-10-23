package ps.reso.instaeclipse.Xposed;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;

import org.luckypray.dexkit.DexKitBridge;

import java.util.List;
import java.util.Map;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;
import ps.reso.instaeclipse.xposedcompat.XC_LoadPackage;
import ps.reso.instaeclipse.utils.log.Logging;
import ps.reso.instaeclipse.mods.ui.UIHookManager;
import ps.reso.instaeclipse.mods.ui.theme.IgThemeEngine;
import ps.reso.instaeclipse.mods.ui.theme.IgThemeHook;
import ps.reso.instaeclipse.mods.ui.theme.ThemeSettingsHelper;
import ps.reso.instaeclipse.utils.core.CommonUtils;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.DexKitStats;
import ps.reso.instaeclipse.utils.core.SettingsManager;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureManager;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.log.StartupTrace;


@SuppressLint("UnsafeDynamicallyLoadedCode")
/**
 * The module's bootstrap, shared by both entry points: {@code LegacyEntry} (assets/xposed_init,
 * legacy frameworks) and {@code ModernEntry} (META-INF/xposed/java_init.list, libxposed 102).
 * It references no framework API itself, so it loads on either. See docs/xposed-api-102.
 */
public class Module {
    // List of supported Instagram package names (maintained in CommonUtils)
    private static final List<String> SUPPORTED_PACKAGES = CommonUtils.SUPPORTED_PACKAGES;
    public static DexKitBridge dexKitBridge;
    public static ClassLoader hostClassLoader;
    public static String moduleSourceDir;
    private static String moduleLibDir;

    // for dev usage
    /*
    public static void showToast(final String text) {
        new Handler(Looper.getMainLooper()).post(() -> ps.reso.instaeclipse.utils.toast.IeToast.makeText(AndroidAppHelper.currentApplication().getApplicationContext(), text, Toast.LENGTH_LONG).show());
    }
    */

    /** The module APK, and its native library folder when the framework knows it (else derived). */
    public static void setModulePaths(String apkPath, String nativeLibDir) {
        moduleSourceDir = apkPath;
        ps.reso.instaeclipse.xposedcompat.ModuleResources.setApkPath(apkPath);
        boolean is64Bit = android.os.Process.is64Bit();
        java.util.function.Predicate<java.io.File> hasLib = dir -> new java.io.File(dir, DexKitLibPath.LIB).exists();
        moduleLibDir = DexKitLibPath.choose(apkPath, nativeLibDir, is64Bit,
                Build.SUPPORTED_64_BIT_ABIS, Build.SUPPORTED_32_BIT_ABIS, hasLib);
        if (!is64Bit && !DexKitLibPath.usable(moduleLibDir, false, hasLib)) {
            ModuleLog.warn("(InstaEclipse): Instagram runs as a 32-bit app; install the 64-bit (arm64-v8a) Instagram build");
        }
    }

    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        // Ensure preferences are loaded


        // Hook into Instagram and its clones
        // Debug kill switch: `adb shell setprop debug.instaeclipse.off 1` loads the module but
        // installs no hooks (isolates crashes to the framework vs. this module).
        if (SUPPORTED_PACKAGES.contains(lpparam.packageName) && "1".equals(debugProp("debug.instaeclipse.off"))) {
            android.util.Log.i("InstaEclipse", "debug.instaeclipse.off=1 -> no hooks installed");
            return;
        }
        if (SUPPORTED_PACKAGES.contains(lpparam.packageName)) {
            // Only the main app process: Instagram's helper processes (":fbns" push, browser,
            // video …) never show our features, so hooking them only costs startup time and memory.
            if (lpparam.processName != null && !lpparam.processName.equals(lpparam.packageName)) {
                return;
            }
            ModuleLog.line("(InstaEclipse): Xposed backend: " + ps.reso.instaeclipse.xposedcompat.XposedBridge.backend().name());
            try {
                if (dexKitBridge == null) {
                    StartupTrace.Span span = StartupTrace.begin(StartupTrace.DEXKIT);
                    try {
                        // Load the .so file from your module (if not already loaded)
                        System.load(moduleLibDir + "/libdexkit.so");
                        DexKitStats.libraryLoaded();
                        // ModuleLog.line("libdexkit.so loaded successfully.");

                        // Initialize DexKitBridge with the target app's APK
                        dexKitBridge = DexKitBridge.create(lpparam.appInfo.sourceDir);
                        DexKitStats.bridgeCreated();
                        // ModuleLog.line("DexKitBridge initialized with target APK: " + lpparam.appInfo.sourceDir);
                    } finally {
                        StartupTrace.end(span);
                    }
                }

                // Use the target app's ClassLoader
                hostClassLoader = lpparam.classLoader;

                // Call the method to hook the target app
                hookInstagram(lpparam);

            } catch (Exception e) {
                ModuleLog.error("(InstaEclipse): Could not start in " + lpparam.packageName + ", so no feature will work", e);
            }
        }
    }

    /** DexKitCache key set once every feature hook has been installed for this IG version. */
    private static final String CACHE_COMPLETE_KEY = "InstallComplete";
    /** ...by this module build: a new build can bring new lookups, so its first launch is a cold
     *  one too (the old "1" marker predates DexKitQueryCache, whose entries that launch fills). */
    private static final String CACHE_COMPLETE_VALUE = String.valueOf(ps.reso.instaeclipse.BuildConfig.VERSION_CODE);

    private static java.util.Set<String> skipped;

    private static volatile InstallRunner lastRunner;

    private static volatile Context appContext;

    public static Context appContext() {
        return appContext;
    }

    public static java.util.List<java.util.Map<String, Object>> lastInstallTimings() {
        InstallRunner runner = lastRunner;
        return runner == null ? new java.util.ArrayList<>() : runner.timings();
    }

    /** Debug bisect switch: `setprop debug.instaeclipse.skip A,B` skips those hook installs. */
    static java.util.Set<String> skipSet() {
        if (skipped == null) {
            skipped = new java.util.HashSet<>();
            for (String prop : new String[]{"skip", "skip2", "skip3", "skip4", "skip5", "skip6"}) // props max 91 chars each
                for (String k : debugProp("debug.instaeclipse." + prop).split(",")) if (!k.trim().isEmpty()) skipped.add(k.trim());
            if (!skipped.isEmpty()) ModuleLog.line("(InstaEclipse) debug skip: " + skipped);
        }
        return skipped;
    }

    /** Reads a system property (debug switches); empty string when unavailable. */
    static String debugProp(String key) {
        try {
            Object v = Class.forName("android.os.SystemProperties").getMethod("get", String.class).invoke(null, key);
            return v instanceof String ? (String) v : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private void hookInstagram(XC_LoadPackage.LoadPackageParam lpparam) {

        try {


            XposedHelpers.findAndHookMethod("android.app.Application", lpparam.classLoader, "attach", Context.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    attachBefore(param.thisObject, (Context) param.args[0], lpparam);
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    attachAfter(param.thisObject, (Context) param.args[0], lpparam);
                }

            });

        } catch (Exception e) {
            ModuleLog.error("(InstaEclipse): Could not start in " + lpparam.packageName + ", so no feature will work", e);
        }
    }

    public static void attachBefore(Object application, Context context, XC_LoadPackage.LoadPackageParam lpparam) {
        DexKitCache.preload(context);
        StartupTrace.Span span = StartupTrace.begin(StartupTrace.ATTACH_BEFORE);
        // Install CommentCopyButtonHook BEFORE Instagram's Application.attach() runs
        // so we catch any ViewBinding pre-inflation that happens during attach()
        if (application instanceof android.app.Application app)
            ps.reso.instaeclipse.xposedcompat.AndroidAppHelper.setCurrentApplication(app);
        SettingsManager.init(context);
        SettingsManager.loadAllFlags(context);

        // Before Instagram reads anything about the device; a change applies on the next start.
        ps.reso.instaeclipse.mods.misc.DeviceSpoofHook.apply(lpparam.classLoader);
        ps.reso.instaeclipse.mods.misc.DeviceSpoofHook.syncOverrides(context);

        // Init DexKit cache — checks IG version to decide if saved descriptors are valid.
        // Must run before any hook that calls DexKitCache.isCacheValid().
        try {
            android.content.pm.PackageInfo pi =
                    context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            long vc = pi.getLongVersionCode();
            String module = moduleSourceDir == null ? ""
                    : moduleSourceDir + "|" + new java.io.File(moduleSourceDir).length();
            DexKitCache.init(context, vc + "|" + module,
                    DexKitCache.queryKey(vc, new java.io.File(lpparam.appInfo.sourceDir).length()));
        } catch (Throwable e) {
            ModuleLog.warn("(DexKitCache) Could not read the saved lookups, so this start takes longer: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        StartupTrace.end(span);
    }

    public static void attachAfter(Object application, Context context, XC_LoadPackage.LoadPackageParam lpparam) {
        StartupTrace.Span span = StartupTrace.begin(StartupTrace.ATTACH_AFTER);
        ps.reso.instaeclipse.utils.core.StartupIdle.armFallback();
        // Setup context, preferences
        SettingsManager.init(context);
        // Settings changed in the companion while Instagram wasn't running.
        ps.reso.instaeclipse.utils.core.CompanionSync.pull(context);
        SettingsManager.loadAllFlags(context);
        // ...and the other way: bring the companion's copy up to date with changes
        // made in this menu (newer side wins per key, see PrefsMerge).
        ps.reso.instaeclipse.utils.core.CompanionSync.pushAsync(context);
        // ...and while it sat frozen in the background: check again on every resume.
        if (application instanceof android.app.Application) {
            ((android.app.Application) application).registerActivityLifecycleCallbacks(
                    new android.app.Application.ActivityLifecycleCallbacks() {
                        @Override public void onActivityResumed(android.app.Activity a) {
                            ps.reso.instaeclipse.utils.core.CompanionSync.pullAsync(a);
                        }
                        @Override public void onActivityCreated(android.app.Activity a, Bundle b) {}
                        @Override public void onActivityStarted(android.app.Activity a) {}
                        @Override public void onActivityPaused(android.app.Activity a) {}
                        @Override public void onActivityStopped(android.app.Activity a) {}
                        @Override public void onActivitySaveInstanceState(android.app.Activity a, Bundle b) {}
                        @Override public void onActivityDestroyed(android.app.Activity a) {}
                    });
            ps.reso.instaeclipse.utils.core.CacheAutoClear.register((android.app.Application) application);
        }

        // In-app log viewer: every ModuleLog.line(...) call across the hook codebase
        // appends to this buffer, which the companion app can read via IPC.
        Logging.init(context, "instaeclipse_module.log");
        // Mirror the buffer into a file owned by the companion app (LogMirrorProvider)
        // so its Logs tab works even when Instagram is backgrounded or killed.
        Logging.enableMirror(context);
        // Logs the previous exits and saves the last crash's tombstone and Breakpad
        // minidumps to the companion, before Instagram uploads and deletes them.
        ps.reso.instaeclipse.utils.log.CrashForensics.collectAsync(context);

        // Process-wide stores, before any hook or I18n.t: module strings and icons come
        // from the loaded APK (LSPatch-safe), and the unsent/reel stores load once here.
        ps.reso.instaeclipse.utils.core.ModuleResources.init(context, moduleSourceDir);
        ps.reso.instaeclipse.utils.ghost.UnsentLog.init(context);
        ps.reso.instaeclipse.utils.ghost.UnsentMessageStore.init(context);
        ps.reso.instaeclipse.mods.reels.DailyReelLimit.init(context);

        // Pull downloader path from companion app's cache so it's available even
        // when Instagram was started without ever receiving the sync broadcast.
        try {
            String path = ps.reso.instaeclipse.xposedcompat.CompanionPrefs.getString("downloaderCustomPath", "");
            String uri  = ps.reso.instaeclipse.xposedcompat.CompanionPrefs.getString("downloaderCustomUri",  "");
            if (!path.isEmpty()) FeatureFlags.downloaderCustomPath = path;
            if (!uri.isEmpty())  FeatureFlags.downloaderCustomUri  = uri;
        } catch (Throwable ignored) {
        }

        FeatureManager.refreshFeatureStatus(); // Update internal feature states

        // Activate the sync bridge to listen to FeaturesFragment updates
        registerSyncReceiver(context);

        try {
            UIHookManager.registerConfigImportReceiver(context);
        } catch (Throwable e) {
            ModuleLog.error("(InstaEclipse | ImportReceiver): Importing a developer config from the InstaEclipse app will not work", e);
        }
        try {
            UIHookManager.registerSettingsRestoreReceiver(context);
            ps.reso.instaeclipse.mods.media.DataBackup.registerRestoreReceiver(context);
        } catch (Throwable e) {
            ModuleLog.error("(InstaEclipse | RestoreReceiver): Restoring settings or a backup from the InstaEclipse app will not work", e);
        }
        StartupTrace.end(span);
        final Runnable installFeatureHooks = () -> installFeatureHooks(context, lpparam);

        if (installedBefore()) {
            // Warm cache: installing is quick (every DexKit query is answered by
            // DexKitQueryCache, the rest is reflection), so hooks are in place before
            // Instagram's own startup code runs.
            installFeatureHooks.run();
        } else {
            // First launch for this Instagram version or module build (or an interrupted one): DexKit has
            // to scan the APK, which takes many seconds. Inside Application.attach that
            // risks Android killing the app for not finishing startup, before the cache
            // is even written. Resolve in the background instead; features turn on a few
            // seconds after launch, and from the next launch on everything is cached.
            ModuleLog.line("(InstaEclipse): First start after an Instagram or InstaEclipse update: setting up features in the background, they turn on in a few seconds");
            Thread t = new Thread(() -> {
                long start = android.os.SystemClock.elapsedRealtime();
                installFeatureHooks.run();
                ModuleLog.ok("(InstaEclipse): Features are ready (background setup took "
                        + (android.os.SystemClock.elapsedRealtime() - start) + " ms, "
                        + StartupTrace.newSearches(StartupTrace.lastInstall()) + ")");
            }, "InstaEclipse-Init");
            t.setPriority(Thread.NORM_PRIORITY - 1);
            t.start();
        }
    }

    private static boolean installedBefore() {
        return DexKitCache.isCacheValid() && CACHE_COMPLETE_VALUE.equals(DexKitCache.loadString(CACHE_COMPLETE_KEY));
    }

    public static void installFeatureHooks(Context context, XC_LoadPackage.LoadPackageParam lpparam) {
        appContext = context;
        boolean warm = installedBefore();
        StartupTrace.Span span = StartupTrace.begin(StartupTrace.INSTALL);
        InstallRunner runner = new InstallRunner(skipSet(), android.os.SystemClock::elapsedRealtimeNanos,
                warm ? DexKitStats.INLINE : DexKitStats.BACKGROUND);
        lastRunner = runner;
        runner.runAll(new InstallPlan(), new InstallPlan.InstallCtx(context, lpparam, hostClassLoader, dexKitBridge), InstallRunner.LOG);
        runner.mark("(finish)");
        // Every lookup is cached now: later launches install inline.
        DexKitCache.saveString(CACHE_COMPLETE_KEY, CACHE_COMPLETE_VALUE);
        ps.reso.instaeclipse.utils.feature.FeatureStatusTracker.logSummary(context);
        runner.mark(null);
        StartupTrace.end(span);
        StartupTrace.logInstallSummary(span, warm);
    }

    /**
     * Injects a dynamic receiver into Instagram to listen for settings changes
     * sent from the InstaEclipse companion app (FeaturesFragment staging system).
     */
    private static void registerSyncReceiver(Context context) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (intent == null) return;
                String action = intent.getAction();
                // Companion writes: mark the key applied first, so a refused write stops counting as
                // pending and PrefsMerge keeps Instagram's value; then upstream's schema check, then
                // the local type / Extreme Mode / private-key guard. A refused write pushes Instagram's
                // values back so the companion's switch snaps back.
                if (action != null && action.startsWith("ps.reso.instaeclipse.ACTION_UPDATE_PREF")) {
                    ps.reso.instaeclipse.utils.core.CompanionSync.markApplied(ctx, intent.getStringExtra("key"), intent.getLongExtra("ts", 0));
                    if (!ps.reso.instaeclipse.utils.ipc.ModuleReplies.isPreferenceUpdate(action,
                            intent.getStringExtra("key"), intent.getExtras() == null ? null : intent.getExtras().get("value"))) {
                        ps.reso.instaeclipse.utils.core.CompanionSync.pushAsync(ctx);
                        return;
                    }
                }
                if ("ps.reso.instaeclipse.ACTION_UPDATE_PREF".equals(action)) {
                    String key = intent.getStringExtra("key");
                    boolean value = intent.getBooleanExtra("value", false);

                    android.content.SharedPreferences prefs = ctx.getSharedPreferences("instaeclipse_prefs", Context.MODE_PRIVATE);
                    if (!SettingsManager.acceptsCompanionWrite(prefs, key, value)) {
                        ps.reso.instaeclipse.utils.core.CompanionSync.pushAsync(ctx);
                        return;
                    }
                    ModuleLog.line("(InstaEclipse) Sync: Updating " + key + " to " + value);
                    prefs.edit().putBoolean(key, value).apply();

                    SettingsManager.loadAllFlags(ctx);
                    afterCompanionWrite(key);
                    FeatureManager.refreshFeatureStatus();

                } else if ("ps.reso.instaeclipse.ACTION_UPDATE_PREF_STRING".equals(action)) {
                    String key = intent.getStringExtra("key");
                    String value = intent.getStringExtra("value");

                    android.content.SharedPreferences prefs = ctx.getSharedPreferences("instaeclipse_prefs", Context.MODE_PRIVATE);
                    if (!SettingsManager.acceptsCompanionWrite(prefs, key, value)) {
                        ps.reso.instaeclipse.utils.core.CompanionSync.pushAsync(ctx);
                        return;
                    }
                    ModuleLog.line("(InstaEclipse) Sync: Updating string pref " + key);
                    prefs.edit().putString(key, value).apply();

                    SettingsManager.loadAllFlags(ctx);
                    afterCompanionWrite(key);

                } else if ("ps.reso.instaeclipse.ACTION_UPDATE_PREF_INT".equals(action)) {
                    String key = intent.getStringExtra("key");
                    int value = intent.getIntExtra("value", 0);

                    android.content.SharedPreferences prefs = ctx.getSharedPreferences("instaeclipse_prefs", Context.MODE_PRIVATE);
                    if (!SettingsManager.acceptsCompanionWrite(prefs, key, value)) {
                        ps.reso.instaeclipse.utils.core.CompanionSync.pushAsync(ctx);
                        return;
                    }
                    if ("storyCacheHours".equals(key)) {
                        value = ps.reso.instaeclipse.utils.media.StoryCacheRetention.normalize(value);
                    }
                    ModuleLog.line("(InstaEclipse) Sync: Updating int pref " + key + " to " + value);
                    prefs.edit().putInt(key, value).apply();

                    SettingsManager.loadAllFlags(ctx);
                    afterCompanionWrite(key);
                    FeatureManager.refreshFeatureStatus();

                } else if (CommonUtils.ACTION_REQUEST_LOGS.equals(action)) {
                    // Re-attach/rewrite the companion-side mirror file first (covers the companion
                    // having cleared its data), then reply; the companion re-reads the file on
                    // reply and only uses the inline text as a fallback.
                    Logging.refreshMirror(ctx, () -> {
                        try {
                            Intent reply = new Intent(CommonUtils.ACTION_LOGS_REPLY);
                            reply.setPackage(CommonUtils.MY_PACKAGE_NAME);
                            // Mirror active -> companion reads the file; skip the big (binder-limited) extra.
                            reply.putExtra(CommonUtils.EXTRA_LOG_TEXT, Logging.isMirrorActive() ? "" : Logging.getSnapshotForIpc());
                            reply.putExtra(CommonUtils.EXTRA_LOG_SOURCE, ctx.getPackageName());
                            ps.reso.instaeclipse.utils.core.IpcSecurity.echoNonce(intent, reply);
                            ps.reso.instaeclipse.utils.ipc.ModuleReplies.reply(intent, reply.getExtras());
                        } catch (Throwable t) {
                            Intent reply = new Intent(CommonUtils.ACTION_LOGS_REPLY);
                            reply.setPackage(CommonUtils.MY_PACKAGE_NAME);
                            reply.putExtra(CommonUtils.EXTRA_LOG_ERROR, "Logs are unavailable");
                            reply.putExtra(CommonUtils.EXTRA_LOG_SOURCE, ctx.getPackageName());
                            ps.reso.instaeclipse.utils.core.IpcSecurity.echoNonce(intent, reply);
                            ps.reso.instaeclipse.utils.ipc.ModuleReplies.reply(intent, reply.getExtras());
                        }
                    });

                } else if (CommonUtils.ACTION_CLEAR_LOGS.equals(action)) {
                    Logging.clear();

                } else if ("ps.reso.instaeclipse.ACTION_REQUEST_PREFS".equals(action)) {
                    ModuleLog.detail("(InstaEclipse) Sync: Companion app requested current preferences.");

                    android.content.SharedPreferences prefs = ctx.getSharedPreferences("instaeclipse_prefs", Context.MODE_PRIVATE);
                    Intent reply = new Intent("ps.reso.instaeclipse.ACTION_SEND_PREFS");
                    reply.setPackage("ps.reso.instaeclipse");

                    Bundle bundle = new Bundle();
                    for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
                        // The lock passcode hash, its salt and the locked chats never leave Instagram's process.
                        if (ps.reso.instaeclipse.utils.core.PrefsMerge.isPrivate(entry.getKey())) continue;
                        // Change stamps, so the companion keeps its own newer changes (PrefsMerge).
                        if (ps.reso.instaeclipse.utils.core.PrefsMerge.isSharedStamp(entry.getKey(), entry.getValue())) {
                            bundle.putLong(entry.getKey(), (Long) entry.getValue());
                        } else if (entry.getValue() instanceof Boolean) {
                            bundle.putBoolean(entry.getKey(), (Boolean) entry.getValue());
                        } else if (entry.getValue() instanceof String) {
                            bundle.putString(entry.getKey(), (String) entry.getValue());
                        } else if (entry.getValue() instanceof Integer) {
                            bundle.putInt(entry.getKey(), (Integer) entry.getValue());
                        }
                    }
                    // Today's reel count for the companion's Distraction-Free page (not a setting).
                    if (ps.reso.instaeclipse.mods.reels.DailyReelLimit.isInitialized()) {
                        bundle.putInt("dailyReelsWatchedToday", ps.reso.instaeclipse.mods.reels.DailyReelLimit.watchedToday());
                        bundle.putString("dailyReelUsageDate", java.time.LocalDate.now().toString());
                    }
                    reply.putExtras(bundle);
                    ps.reso.instaeclipse.utils.core.IpcSecurity.echoNonce(intent, reply);
                    ps.reso.instaeclipse.utils.ipc.ModuleReplies.reply(intent, reply.getExtras());

                } else if ("ps.reso.instaeclipse.ACTION_EXPORT_CONFIG".equals(action)) {
                    ModuleLog.detail("(InstaEclipse) Sync: Companion app requested Dev Config export.");
                    try {
                        java.io.File source = new java.io.File(ctx.getFilesDir(), "mobileconfig/mc_overrides.json");
                        if (!source.exists()) {
                            ModuleLog.line("(InstaEclipse) Export: there is no developer config to export yet (mc_overrides.json not found)");
                            Intent reply = new Intent("ps.reso.instaeclipse.ACTION_SEND_CONFIG");
                            reply.setPackage("ps.reso.instaeclipse");
                            reply.putExtra("error", "mc_overrides.json not found.");
                            ps.reso.instaeclipse.utils.core.IpcSecurity.echoNonce(intent, reply);
                            ps.reso.instaeclipse.utils.ipc.ModuleReplies.reply(intent, reply.getExtras());
                            return;
                        }
                        String json;
                        try (java.io.InputStream input = new java.io.FileInputStream(source)) {
                            json = ps.reso.instaeclipse.utils.ipc.BrokerPayload.readConfig(input);
                        }
                        Intent reply = new Intent("ps.reso.instaeclipse.ACTION_SEND_CONFIG");
                        reply.setPackage("ps.reso.instaeclipse");
                        reply.putExtra("json_content", json);
                        ps.reso.instaeclipse.utils.core.IpcSecurity.echoNonce(intent, reply);
                            ps.reso.instaeclipse.utils.ipc.ModuleReplies.reply(intent, reply.getExtras());
                        ModuleLog.line("(InstaEclipse) Export: developer config sent to the InstaEclipse app");
                    } catch (Exception e) {
                        Intent reply = new Intent("ps.reso.instaeclipse.ACTION_SEND_CONFIG");
                        reply.putExtra("error", "Configuration could not be read or exceeds the supported size");
                        ps.reso.instaeclipse.utils.core.IpcSecurity.echoNonce(intent, reply);
                        ps.reso.instaeclipse.utils.ipc.ModuleReplies.reply(intent, reply.getExtras());
                        ModuleLog.error("(IE|Config) Could not export the developer config: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction("ps.reso.instaeclipse.ACTION_UPDATE_PREF");
        filter.addAction("ps.reso.instaeclipse.ACTION_UPDATE_PREF_STRING");
        filter.addAction("ps.reso.instaeclipse.ACTION_UPDATE_PREF_INT");
        filter.addAction(CommonUtils.ACTION_REQUEST_LOGS);
        filter.addAction(CommonUtils.ACTION_CLEAR_LOGS);
        filter.addAction("ps.reso.instaeclipse.ACTION_REQUEST_PREFS");
        filter.addAction("ps.reso.instaeclipse.ACTION_EXPORT_CONFIG");

        // Only the companion (holding the signature permission) can reach this receiver.
        ps.reso.instaeclipse.utils.core.IpcSecurity.registerCompanionOnlyReceiver(context, receiver, filter);
    }

    /**
     * Live side effects once a companion value landed (sync receiver and CompanionSync.pullAsync).
     * Main thread only. Only theme keys repaint: recreating Instagram for any other setting loses
     * drafts and playback.
     */
    public static void afterCompanionWrite(String key) {
        if (key == null) return;
        if ("hideSpecificChats".equals(key) || "showHideChatButton".equals(key)) {
            ps.reso.instaeclipse.mods.ghost.HideChatsHook.refreshVisibleButtons();
        }
        if ("storyCacheHours".equals(key)) ps.reso.instaeclipse.utils.media.StoryCache.refresh();
        if ("airplaneMode".equals(key)) ps.reso.instaeclipse.mods.extras.AirplaneModeHook.apply();
        if ("likeUndo".equals(key)) ps.reso.instaeclipse.mods.misc.LikeUndoHook.onLikeUndoChanged();
        if (ThemeSettingsHelper.isThemeKey(key)) {
            IgThemeEngine.invalidate();
            IgThemeHook.refreshCurrentActivity();
        }
    }
}
