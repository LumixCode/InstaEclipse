package ps.reso.instaeclipse.Xposed;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;
import ps.reso.instaeclipse.xposedcompat.XC_LoadPackage;
import ps.reso.instaeclipse.utils.log.Logging;
import ps.reso.instaeclipse.mods.ui.UIHookManager;
import ps.reso.instaeclipse.utils.core.CommonUtils;
import ps.reso.instaeclipse.utils.core.CompanionSync;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.DexKitStats;
import ps.reso.instaeclipse.utils.core.LegacyPaths;
import ps.reso.instaeclipse.utils.core.ModuleBridge;
import ps.reso.instaeclipse.utils.core.SettingsManager;
import ps.reso.instaeclipse.utils.core.StartupIdle;
import ps.reso.instaeclipse.utils.feature.FeatureManager;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.log.StartupTrace;
import ps.reso.instaeclipse.utils.media.StoryCacheRetention;


public class Module {
    private static final List<String> SUPPORTED_PACKAGES = CommonUtils.SUPPORTED_PACKAGES;
    public static ClassLoader hostClassLoader;
    public static String moduleSourceDir;
    private static String moduleLibDir;

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


        if (SUPPORTED_PACKAGES.contains(lpparam.packageName) && "1".equals(debugProp("debug.instaeclipse.off"))) {
            android.util.Log.i("InstaEclipse", "debug.instaeclipse.off=1 -> no hooks installed");
            return;
        }
        if (SUPPORTED_PACKAGES.contains(lpparam.packageName)) {
            if (lpparam.processName != null && !lpparam.processName.equals(lpparam.packageName)) {
                return;
            }
            ModuleLog.line("(InstaEclipse): Xposed backend: " + ps.reso.instaeclipse.xposedcompat.XposedBridge.backend().name());
            hostClassLoader = lpparam.classLoader;
            hookInstagram(lpparam);
            ModuleBridge.prepare(moduleLibDir, lpparam.appInfo.sourceDir);
            if (LegacyPaths.has(LegacyPaths.WARMBRIDGE)) ModuleBridge.start(ModuleBridge.LOAD);
        }
    }

    private static final String CACHE_COMPLETE_KEY = "InstallComplete";
    private static final String CACHE_COMPLETE_VALUE = String.valueOf(ps.reso.instaeclipse.BuildConfig.VERSION_CODE);

    private static final String INIT_THREAD = "InstaEclipse-Init";
    private static final long IDLE_DELAY_MS = 3000L;
    private static final String STEP_KEYS = "assets/dexkit/step-keys.tsv";
    private static final InstallSchedule.Store CACHE_STORE = new InstallSchedule.Store() {
        @Override
        public String get(String key) {
            return DexKitCache.loadSaved(key);
        }

        @Override
        public boolean has(String storedKey) {
            return DexKitCache.has(storedKey);
        }

        @Override
        public void put(String key, String value) {
            if (value == null) DexKitCache.remove(key);
            else DexKitCache.saveString(key, value);
        }
    };

    private static java.util.Set<String> skipped;

    private static volatile InstallRunner lastRunner;
    private static volatile InstallSchedule.Split lastSchedule;
    private static volatile boolean safeStart;
    private static volatile boolean inlineOnMainThread;
    private static volatile String backgroundThread;
    private static volatile Consumer<Runnable> backgroundStarter = Module::startInitThread;

    private static volatile Context appContext;
    private static volatile InstallPlan keptPlan;
    private static volatile InstallSchedule.Split keptSplit;

    public static Context appContext() {
        return appContext;
    }

    public static java.util.List<java.util.Map<String, Object>> lastInstallTimings() {
        InstallRunner runner = lastRunner;
        return runner == null ? new java.util.ArrayList<>() : runner.timings();
    }

    public static InstallSchedule.Split lastSchedule() {
        return lastSchedule;
    }

    public static boolean inlineRanOnMainThread() {
        return inlineOnMainThread;
    }

    public static String backgroundThread() {
        return backgroundThread;
    }

    public static void startBackgroundForTest(Consumer<Runnable> starter) {
        backgroundStarter = starter == null ? Module::startInitThread : starter;
    }

    static java.util.Set<String> skipSet() {
        if (skipped == null) {
            skipped = new java.util.HashSet<>();
            for (String prop : new String[]{"skip", "skip2", "skip3", "skip4", "skip5", "skip6"})
                for (String k : debugProp("debug.instaeclipse." + prop).split(",")) if (!k.trim().isEmpty()) skipped.add(k.trim());
            if (!skipped.isEmpty()) ModuleLog.line("(InstaEclipse) debug skip: " + skipped);
        }
        return skipped;
    }

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
                protected void beforeHookedMethod(MethodHookParam param) {
                    attachBefore(param.thisObject, (Context) param.args[0], lpparam);
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
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
        if (application instanceof android.app.Application app)
            ps.reso.instaeclipse.xposedcompat.AndroidAppHelper.setCurrentApplication(app);
        SettingsManager.init(context);
        if (!CompanionSync.pull(context)) SettingsManager.loadAllFlags(context);

        ps.reso.instaeclipse.mods.misc.DeviceSpoofHook.apply(lpparam.classLoader);
        ps.reso.instaeclipse.mods.misc.DeviceSpoofHook.syncOverrides(context);

        try {
            android.content.pm.PackageInfo pi =
                    context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            long vc = pi.getLongVersionCode();
            String module = moduleSourceDir == null ? ""
                    : moduleSourceDir + "|" + new java.io.File(moduleSourceDir).length();
            DexKitCache.init(context, vc + "|" + module,
                    DexKitCache.queryKey(vc, new java.io.File(lpparam.appInfo.sourceDir).length()));
            safeStart = StartupIdle.safeStart(context, vc + "|" + moduleBuild());
        } catch (Throwable e) {
            ModuleLog.warn("(DexKitCache) Could not read the saved lookups, so this start takes longer: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        if (safeStart) ModuleBridge.block();
        else startBridgeIfNeeded();
        StartupTrace.end(span);
    }

    private static void startBridgeIfNeeded() {
        boolean scheduleNeedsBridge;
        try {
            InstallPlan plan = new InstallPlan();
            InstallSchedule.Split split = schedule(plan);
            keptPlan = plan;
            keptSplit = split;
            scheduleNeedsBridge = backgroundNeedsBridge(split);
        } catch (Throwable t) {
            keptPlan = null;
            keptSplit = null;
            scheduleNeedsBridge = true;
        }
        boolean start = true;
        try {
            start = ModuleBridge.shouldStart(LegacyPaths.has(LegacyPaths.WARMBRIDGE), ModuleBridge.stamp(), scheduleNeedsBridge);
        } catch (Throwable ignored) {
        }
        if (start) ModuleBridge.start(ModuleBridge.ATTACH);
    }

    static boolean backgroundNeedsBridge(InstallSchedule.Split split) {
        if (split == null) return false;
        for (InstallStep step : split.background) {
            if (step.dexKit && split.backgroundOpen.contains(step.name)) return true;
        }
        return false;
    }

    public static void attachAfter(Object application, Context context, XC_LoadPackage.LoadPackageParam lpparam) {
        StartupTrace.Span span = StartupTrace.begin(StartupTrace.ATTACH_AFTER);
        ps.reso.instaeclipse.utils.core.StartupIdle.armFallback();
        SettingsManager.init(context);
        if (CompanionSync.pull(context)) {
            keptPlan = null;
            keptSplit = null;
        }
        if (LegacyPaths.has(LegacyPaths.IDLE) || !CompanionSync.remotePrefsAvailable()) {
            CompanionSync.pushAsync(context);
        } else {
            StartupIdle.afterFirstFrame(() -> CompanionSync.pushAsync(context), IDLE_DELAY_MS);
        }
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

        Logging.init(context, "instaeclipse_module.log");
        if (LegacyPaths.has(LegacyPaths.IDLE) || StartupIdle.previousStartEndedEarly(context)) {
            Logging.enableMirror(context);
        } else {
            StartupIdle.afterFirstFrame(() -> Logging.enableMirror(context), IDLE_DELAY_MS);
        }
        ps.reso.instaeclipse.utils.log.CrashForensics.collectAsync(context);

        ps.reso.instaeclipse.utils.core.ModuleResources.init(context, moduleSourceDir);
        ps.reso.instaeclipse.utils.ghost.UnsentLog.init(context);
        ps.reso.instaeclipse.utils.ghost.UnsentMessageStore.init(context);
        ps.reso.instaeclipse.mods.reels.DailyReelLimit.init(context);

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
        ModuleLog.detail("(InstaEclipse) Settings: " + CompanionSync.startupApplied() + " companion change(s) applied before the hooks; "
                + "companion remote preferences " + (CompanionSync.remotePrefsAvailable() ? "available" : "empty"));
        StartupTrace.end(span);
        installFeatureHooks(context, lpparam);
    }

    private static boolean installedBefore() {
        return DexKitCache.isCacheValid() && CACHE_COMPLETE_VALUE.equals(DexKitCache.loadString(CACHE_COMPLETE_KEY));
    }

    public static void installFeatureHooks(Context context, XC_LoadPackage.LoadPackageParam lpparam) {
        appContext = context;
        StartupTrace.Span span = StartupTrace.begin(StartupTrace.INSTALL);
        InstallPlan kept = keptPlan;
        InstallSchedule.Split keptSchedule = keptSplit;
        keptPlan = null;
        keptSplit = null;
        boolean reuse = kept != null && keptSchedule != null;
        InstallPlan plan = reuse ? kept : new InstallPlan();
        InstallSchedule.Split split = reuse ? keptSchedule : schedule(plan);
        if (!reuse && !safeStart && backgroundNeedsBridge(split)) ModuleBridge.start(ModuleBridge.INSTALL);
        lastSchedule = split;
        InstallRunner runner = new InstallRunner(skipSet(), android.os.SystemClock::elapsedRealtimeNanos, DexKitStats.INLINE);
        lastRunner = runner;
        runner.setSafeStart(safeStart);
        if (safeStart) {
            ModuleLog.error("(InstaEclipse): Instagram closed during startup twice in a row on this version, so this start"
                    + " only runs the features that don't depend on Instagram's code. The next start tries everything again");
        }
        Runnable saveStamp = safeStart ? null : ModuleBridge::saveStamp;
        if (!split.legacy && !safeStart) {
            runner.setLedger(new InstallSchedule.Book(CACHE_STORE, split,
                    step -> InstallSchedule.lookupState(step, plan::flag), moduleBuild(), ModuleLog::detail));
        }
        InstallPlan.InstallCtx ctx = new InstallPlan.InstallCtx(context, lpparam, hostClassLoader);
        inlineOnMainThread = DexKitStats.onMainThread();
        runner.run(plan, split.inline, DexKitStats.INLINE, ctx, InstallRunner.LOG, -1, null,
                split.background.isEmpty() ? saveStamp : null);
        if (split.background.isEmpty()) {
            finishInstall(context, runner, span, true);
            return;
        }
        runner.mark(null);
        if (split.legacy) {
            ModuleLog.line("(InstaEclipse): First start after an Instagram or InstaEclipse update: setting up features in the background, they turn on in a few seconds");
        } else {
            ModuleLog.line("(InstaEclipse): Setting up " + split.backgroundOpen.size()
                    + " features in the background; network filters, locks and the menu are already on");
            ModuleLog.detail("(IE|Startup) The background setup starts at " + split.firstBackground + ": " + split.reason);
        }
        StartupTrace.handOff(span);
        List<InstallStep> background = split.background;
        int early = split.legacy ? 0 : InstallPlan.earlyCount(background);
        backgroundStarter.accept(() -> {
            long start = android.os.SystemClock.elapsedRealtime();
            backgroundThread = Thread.currentThread().getName();
            runner.run(plan, background, DexKitStats.BACKGROUND, ctx, InstallRunner.LOG, early, () -> {
                if (INIT_THREAD.equals(Thread.currentThread().getName())) {
                    Thread.currentThread().setPriority(Thread.NORM_PRIORITY - 1);
                }
            }, saveStamp);
            finishInstall(context, runner, span, false);
            ModuleLog.ok("(InstaEclipse): Features are ready (background setup took "
                    + (android.os.SystemClock.elapsedRealtime() - start) + " ms, "
                    + StartupTrace.newSearches(StartupTrace.lastInstall()) + ")");
            UIHookManager.setupCurrentScreen();
        });
    }

    private static void finishInstall(Context context, InstallRunner runner, StartupTrace.Span span, boolean warm) {
        runner.report(InstallRunner.LOG);
        runner.mark("(finish)");
        if (!safeStart) DexKitCache.saveString(CACHE_COMPLETE_KEY, CACHE_COMPLETE_VALUE);
        FeatureStatusTracker.logSummary(context);
        runner.mark(null);
        StartupTrace.end(span);
        StartupTrace.logInstallSummary(span, warm);
    }

    private static InstallSchedule.Split schedule(InstallPlan plan) {
        if (LegacyPaths.has(LegacyPaths.PHASES)) return InstallSchedule.legacy(plan.legacySteps(), installedBefore());
        InstallSchedule.Rows rows = InstallSchedule.withLearned(stepKeys(), DexKitCache::loadSaved, moduleBuild());
        return InstallSchedule.split(plan.steps(), plan::open, step -> InstallSchedule.lookupState(step, plan::flag),
                name -> DexKitCache.loadSaved(InstallSchedule.markerKey(name)), rows, DexKitCache::has);
    }

    static String moduleBuild() {
        long size = moduleSourceDir == null ? 0L : new File(moduleSourceDir).length();
        return ps.reso.instaeclipse.BuildConfig.VERSION_CODE + "|" + size;
    }

    static InstallSchedule.StepKeys stepKeys() {
        if (moduleSourceDir == null) return InstallSchedule.StepKeys.EMPTY;
        try (ZipFile zip = new ZipFile(moduleSourceDir)) {
            ZipEntry entry = zip.getEntry(STEP_KEYS);
            if (entry == null) return InstallSchedule.StepKeys.EMPTY;
            try (InputStream in = zip.getInputStream(entry)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
                return InstallSchedule.StepKeys.parse(new String(out.toByteArray(), StandardCharsets.UTF_8));
            }
        } catch (Throwable t) {
            ModuleLog.detail("(IE|Startup) The list of saved lookups could not be read, so features are set up in the background once: " + t);
            return InstallSchedule.StepKeys.EMPTY;
        }
    }

    private static void startInitThread(Runnable task) {
        Thread thread = new Thread(task, INIT_THREAD);
        thread.setPriority(Thread.NORM_PRIORITY);
        thread.start();
    }

    private static void registerSyncReceiver(Context context) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (intent == null) return;
                String action = intent.getAction();
                if (applySettingsIntent(ctx, intent)) return;
                if (CommonUtils.ACTION_REQUEST_LOGS.equals(action)) {
                    Logging.refreshMirror(ctx, () -> {
                        try {
                            Intent reply = new Intent(CommonUtils.ACTION_LOGS_REPLY);
                            reply.setPackage(CommonUtils.MY_PACKAGE_NAME);
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

                } else if (CommonUtils.ACTION_RESTART_INSTAGRAM.equals(action)) {
                    ps.reso.instaeclipse.utils.core.IgSelfRestart.handle(ctx, intent);

                } else if ("ps.reso.instaeclipse.ACTION_REQUEST_PREFS".equals(action)) {
                    ModuleLog.detail("(InstaEclipse) Sync: Companion app requested current preferences.");

                    android.content.SharedPreferences prefs = ctx.getSharedPreferences("instaeclipse_prefs", Context.MODE_PRIVATE);
                    Intent reply = new Intent("ps.reso.instaeclipse.ACTION_SEND_PREFS");
                    reply.setPackage("ps.reso.instaeclipse");

                    Bundle bundle = new Bundle();
                    for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
                        if (ps.reso.instaeclipse.utils.core.PrefsMerge.isSharedStamp(entry.getKey(), entry.getValue())) {
                            bundle.putLong(entry.getKey(), (Long) entry.getValue());
                        } else if (!ps.reso.instaeclipse.utils.core.PrefsMerge.isSharedSetting(entry.getKey(), entry.getValue())) {
                            continue;
                        } else if (entry.getValue() instanceof Boolean) {
                            bundle.putBoolean(entry.getKey(), (Boolean) entry.getValue());
                        } else if (entry.getValue() instanceof String) {
                            bundle.putString(entry.getKey(), (String) entry.getValue());
                        } else if (entry.getValue() instanceof Integer) {
                            bundle.putInt(entry.getKey(), (Integer) entry.getValue());
                        }
                    }
                    if (ps.reso.instaeclipse.mods.reels.DailyReelLimit.isInitialized()) {
                        bundle.putInt("dailyReelsWatchedToday", ps.reso.instaeclipse.mods.reels.DailyReelLimit.watchedToday());
                        bundle.putString("dailyReelUsageDate", java.time.LocalDate.now().toString());
                    }
                    bundle.putInt(CommonUtils.EXTRA_FEATURES_ACTIVE, FeatureStatusTracker.activeCount());
                    bundle.putInt(CommonUtils.EXTRA_FEATURES_ON, FeatureStatusTracker.enabledCount());
                    bundle.putBoolean(CommonUtils.EXTRA_SAFE_START, safeStart);
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
        filter.addAction(CommonUtils.ACTION_APPLY_SETTINGS);
        filter.addAction(CommonUtils.ACTION_REQUEST_LOGS);
        filter.addAction(CommonUtils.ACTION_CLEAR_LOGS);
        filter.addAction(CommonUtils.ACTION_RESTART_INSTAGRAM);
        filter.addAction("ps.reso.instaeclipse.ACTION_REQUEST_PREFS");
        filter.addAction("ps.reso.instaeclipse.ACTION_EXPORT_CONFIG");

        ps.reso.instaeclipse.utils.core.IpcSecurity.registerCompanionOnlyReceiver(context, receiver, filter);
    }

    public static boolean applySettingsIntent(Context ctx, Intent intent) {
        String action = intent.getAction();
        if (CommonUtils.ACTION_APPLY_SETTINGS.equals(action)) {
            Bundle bundle = intent.getBundleExtra("values");
            Map<String, Object> values = new LinkedHashMap<>();
            if (bundle != null) {
                for (String key : bundle.keySet()) {
                    Object value = bundle.get(key);
                    if (value instanceof Boolean || value instanceof Integer || value instanceof String) values.put(key, value);
                }
            }
            SettingsEvents.afterChange(CompanionSync.applyLive(ctx, values, intent.getLongExtra("ts", 0)));
            return true;
        }
        if (action == null || !action.startsWith("ps.reso.instaeclipse.ACTION_UPDATE_PREF")) return false;
        String key = intent.getStringExtra("key");
        long ts = intent.getLongExtra("ts", 0);
        Object value = intent.getExtras() == null ? null : intent.getExtras().get("value");
        if (!ps.reso.instaeclipse.utils.ipc.ModuleReplies.isPreferenceUpdate(action, key, value)) {
            CompanionSync.markApplied(ctx, key, ts);
            CompanionSync.pushAsync(ctx);
            return true;
        }
        if (LegacyPaths.has(LegacyPaths.APPLY)) {
            applyLegacy(ctx, key, value, ts);
            return true;
        }
        SettingsEvents.afterChange(CompanionSync.applyLive(ctx, Collections.singletonMap(key, value), ts));
        return true;
    }

    private static void applyLegacy(Context ctx, String key, Object value, long ts) {
        CompanionSync.markApplied(ctx, key, ts);
        SharedPreferences prefs = ctx.getSharedPreferences("instaeclipse_prefs", Context.MODE_PRIVATE);
        if (!SettingsManager.acceptsCompanionWrite(prefs, key, value)) {
            CompanionSync.pushAsync(ctx);
            return;
        }
        SharedPreferences.Editor editor = prefs.edit();
        if (value instanceof Boolean) {
            ModuleLog.line("(InstaEclipse) Sync: Updating " + key + " to " + value);
            editor.putBoolean(key, (Boolean) value);
        } else if (value instanceof Integer) {
            int n = "storyCacheHours".equals(key) ? StoryCacheRetention.normalize((Integer) value) : (Integer) value;
            ModuleLog.line("(InstaEclipse) Sync: Updating int pref " + key + " to " + n);
            editor.putInt(key, n);
        } else {
            ModuleLog.line("(InstaEclipse) Sync: Updating string pref " + key);
            editor.putString(key, (String) value);
        }
        editor.apply();
        SettingsManager.loadAllFlags(ctx);
        SettingsEvents.afterChange(Collections.singletonList(key));
        if (!(value instanceof String)) FeatureManager.refreshFeatureStatus();
    }
}
