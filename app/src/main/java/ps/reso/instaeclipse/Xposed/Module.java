package ps.reso.instaeclipse.Xposed;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;

import androidx.core.content.ContextCompat;

import org.luckypray.dexkit.DexKitBridge;

import java.util.List;
import java.util.Map;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;
import ps.reso.instaeclipse.xposedcompat.XC_LoadPackage;
import ps.reso.instaeclipse.mods.ads.AdBlocker;
import ps.reso.instaeclipse.mods.feed.FeedPhotoZoomHook;
import ps.reso.instaeclipse.mods.location.LocationSpoofHook;
import ps.reso.instaeclipse.utils.log.Logging;
import ps.reso.instaeclipse.mods.media.ForceReelQualityHook;
import ps.reso.instaeclipse.mods.feed.HideSuggestedFeedItemsHook;
import ps.reso.instaeclipse.mods.ads.TrackingLinkDisable;
import ps.reso.instaeclipse.mods.devops.BuildExpiredPopupHook;
import ps.reso.instaeclipse.mods.devops.DevOptionsUnlockHook;
import ps.reso.instaeclipse.mods.ghost.GhostChannelMarkAsReadHook;
import ps.reso.instaeclipse.mods.ghost.GhostDMMarkAsReadHook;
import ps.reso.instaeclipse.mods.ghost.GhostDMSeenHook;
import ps.reso.instaeclipse.mods.ghost.GhostEphemeralKeepHook;
import ps.reso.instaeclipse.mods.ghost.GhostPermanentViewHook;
import ps.reso.instaeclipse.mods.ghost.ViewOnceBadgeHook;
import ps.reso.instaeclipse.mods.ghost.KeepUnsentMessagesHook;
import ps.reso.instaeclipse.mods.ghost.GhostScreenshotDetectionHook;
import ps.reso.instaeclipse.mods.ghost.GhostStorySeenHook;
import ps.reso.instaeclipse.mods.ghost.GhostTypingIndicatorHook;
import ps.reso.instaeclipse.mods.ghost.GhostViewOnceHook;
import ps.reso.instaeclipse.mods.ghost.ScreenshotPermissionHook;
import ps.reso.instaeclipse.mods.media.FeedVideoDownloadHook;
import ps.reso.instaeclipse.mods.media.PostDownloadContextMenuHook;
import ps.reso.instaeclipse.mods.media.ProfilePicDownloadHook;
import ps.reso.instaeclipse.mods.media.ReelDownloadHook;
import ps.reso.instaeclipse.mods.media.StoryDownloadHook;
import ps.reso.instaeclipse.mods.misc.CommentCopyHook;
import ps.reso.instaeclipse.mods.misc.CaptionCopyHook;
import ps.reso.instaeclipse.mods.misc.DisableDoubleTapLikeHook;
import ps.reso.instaeclipse.mods.misc.DisableStoryFlippingHook;
import ps.reso.instaeclipse.mods.misc.DisableVideoAutoPlayHook;
import ps.reso.instaeclipse.mods.misc.StoryMentionHook;
import ps.reso.instaeclipse.mods.network.IGNetworkInterceptor;
import ps.reso.instaeclipse.mods.ui.UIHookManager;
import ps.reso.instaeclipse.mods.ui.theme.IgThemeEngine;
import ps.reso.instaeclipse.mods.ui.theme.IgThemeHook;
import ps.reso.instaeclipse.mods.ui.theme.ThemeSettingsHelper;
import ps.reso.instaeclipse.utils.core.CommonUtils;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.SettingsManager;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureManager;
import ps.reso.instaeclipse.utils.log.ModuleLog;


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
        if (nativeLibDir != null && new java.io.File(nativeLibDir, "libdexkit.so").exists()) {
            moduleLibDir = nativeLibDir;
            return;
        }

        String abi = Build.SUPPORTED_ABIS[0];
        String abiFolder;
        if (abi.equalsIgnoreCase("arm64-v8a")) abiFolder = "arm64";
        else if (abi.equalsIgnoreCase("armeabi-v7a") || abi.equalsIgnoreCase("armeabi") || abi.equalsIgnoreCase("armv8i"))
            abiFolder = "arm";
        else if (abi.equalsIgnoreCase("x86")) abiFolder = "x86";
        else if (abi.equalsIgnoreCase("x86_64")) abiFolder = "x86_64";
        else abiFolder = abi;

        moduleLibDir = moduleSourceDir.substring(0, moduleSourceDir.lastIndexOf("/")) + "/lib/" + abiFolder;
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
                    // Load the .so file from your module (if not already loaded)
                    System.load(moduleLibDir + "/libdexkit.so");
                    // ModuleLog.line("libdexkit.so loaded successfully.");

                    // Initialize DexKitBridge with the target app's APK
                    dexKitBridge = DexKitBridge.create(lpparam.appInfo.sourceDir);
                    // ModuleLog.line("DexKitBridge initialized with target APK: " + lpparam.appInfo.sourceDir);
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

    /** Debug bisect switch: `setprop debug.instaeclipse.skip A,B` skips those hook installs. */
    static boolean skip(String name) {
        if (skipped == null) {
            skipped = new java.util.HashSet<>();
            for (String prop : new String[]{"skip", "skip2", "skip3", "skip4", "skip5", "skip6"}) // props max 91 chars each
                for (String k : debugProp("debug.instaeclipse." + prop).split(",")) if (!k.trim().isEmpty()) skipped.add(k.trim());
            if (!skipped.isEmpty()) ModuleLog.line("(InstaEclipse) debug skip: " + skipped);
        }
        return skipped.contains(name);
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
                    // Install CommentCopyButtonHook BEFORE Instagram's Application.attach() runs
                    // so we catch any ViewBinding pre-inflation that happens during attach()
                    Context context = (Context) param.args[0];
                    if (param.thisObject instanceof android.app.Application app)
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
                        // The module APK is part of the key: a new module build may look things up
                        // differently, so it must not reuse what an older build cached for this IG.
                        String module = moduleSourceDir == null ? ""
                                : moduleSourceDir + "|" + new java.io.File(moduleSourceDir).length();
                        DexKitCache.init(context, vc + "|" + module);
                    } catch (Throwable e) {
                        ModuleLog.warn("(DexKitCache) Could not read the saved lookups, so this start takes longer: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);

                    // Setup context, preferences
                    Context context = (Context) param.args[0];
                    SettingsManager.init(context);
                    // Settings changed in the companion while Instagram wasn't running.
                    ps.reso.instaeclipse.utils.core.CompanionSync.pull(context);
                    SettingsManager.loadAllFlags(context);
                    // ...and the other way: bring the companion's copy up to date with changes
                    // made in this menu (newer side wins per key, see PrefsMerge).
                    ps.reso.instaeclipse.utils.core.CompanionSync.pushAsync(context);
                    // ...and while it sat frozen in the background: check again on every resume.
                    if (param.thisObject instanceof android.app.Application) {
                        ((android.app.Application) param.thisObject).registerActivityLifecycleCallbacks(
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
                    final Runnable installFeatureHooks = () -> {
                    UIHookManager instagramUI = new UIHookManager();
                    if (!skip("instagramUI")) instagramUI.mainActivity(hostClassLoader);

                    // Reels viewer: daily limit, nighttime block, Disable Reels covers, scroll lock
                    try {
                        if (!skip("ReelViewerHook")) new ps.reso.instaeclipse.mods.reels.ReelViewerHook().install(dexKitBridge, hostClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | ReelViewer): Could not start, so this feature will not work", t);
                    }

                    IGNetworkInterceptor interceptor = new IGNetworkInterceptor();

                    // --- Feature Hooks ---

                    // Developer Options
                    try {
                        if (!skip("DevOptionsUnlockHook")) new DevOptionsUnlockHook().handleDevOptions(dexKitBridge);
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | DevOptions): Could not start, so this feature will not work", t);
                    }

                    // Ghost Mode
                    try {
                        if (!skip("GhostDMSeenHook")) new GhostDMSeenHook().handleSeenBlock(dexKitBridge); // DM Seen
                        if (!skip("GhostDMMarkAsReadHook")) new GhostDMMarkAsReadHook(moduleSourceDir).install(lpparam.classLoader); // Mark as Read Button
                        if (!skip("GhostChannelMarkAsReadHook")) new GhostChannelMarkAsReadHook().install(lpparam.classLoader); // Channel Mark as Read Button
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | GhostSeen): Could not start, so this feature will not work", t);
                    }

                    try {
                        if (!skip("GhostTypingIndicatorHook")) new GhostTypingIndicatorHook().handleTypingBlock(dexKitBridge); // DM Typing
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | GhostTyping): Could not start, so this feature will not work", t);
                    }

                    try {
                        if (!skip("GhostScreenshotDetectionHook")) new GhostScreenshotDetectionHook().handleScreenshotBlock(dexKitBridge); // Screenshot
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | GhostScreenshot): Could not start, so this feature will not work", t);
                    }

                    try {
                        if (!skip("ScreenshotPermissionHook")) new ScreenshotPermissionHook().install(lpparam.classLoader); // Allow Screenshots
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | ScreenshotPermission): Could not start, so this feature will not work", t);
                    }

                    try {
                        if (!skip("GhostViewOnceHook")) new GhostViewOnceHook().handleViewOnceBlock(dexKitBridge); // View Once
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | GhostViewOnce): Could not start, so this feature will not work", t);
                    }

                    try {
                        if (!skip("GhostStorySeenHook")) new GhostStorySeenHook().handleStorySeenBlock(dexKitBridge); // Story Seen
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | GhostStorySeen): Could not start, so this feature will not work", t);
                    }

                    try {
                        if (!skip("KeepUnsentMessagesHook")) new KeepUnsentMessagesHook().install(dexKitBridge, lpparam.classLoader); // Keep Unsent
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | KeepUnsent): Could not start, so this feature will not work", t);
                    }

                    try {
                        if (!skip("UnsentThreadButtonHook")) new ps.reso.instaeclipse.mods.ghost.UnsentThreadButtonHook().install(lpparam.classLoader); // per-thread unsent button
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | UnsentBtn): Could not start, so this feature will not work", t);
                    }

                    try {
                        if (!skip("HideChatsHook")) new ps.reso.instaeclipse.mods.ghost.HideChatsHook().install(dexKitBridge, lpparam.classLoader); // Hide Specific Chats
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | HideChats): Could not start, so this feature will not work", t);
                    }

                    try {
                        if (!skip("InboxDiscoverPeopleHook")) new ps.reso.instaeclipse.mods.ui.InboxDiscoverPeopleHook().install(dexKitBridge, lpparam.classLoader); // Disable Discover People in Messages
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | InboxDiscover): Could not start, so this feature will not work", t);
                    }

                    try {
                        if (!skip("CustomFontHook")) new ps.reso.instaeclipse.mods.ui.CustomFontHook().install(lpparam.classLoader); // Custom UI font
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | CustomFont): Could not start, so this feature will not work", t);
                    }

                    try {
                        ps.reso.instaeclipse.mods.ui.RemoveMetaAIHook metaAi = new ps.reso.instaeclipse.mods.ui.RemoveMetaAIHook();
                        if (!skip("metaAi")) metaAi.install(lpparam.classLoader);              // composer/search XML layouts
                        if (!skip("metaAi")) metaAi.installReels(dexKitBridge, lpparam.classLoader); // reels Litho unit (#179)
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | RemoveMetaAI): Could not start, so this feature will not work", t);
                    }

                    // Disable Repost (feed + reels) — UI/action level; network drop is ineffective
                    try {
                        if (!skip("DisableRepostHook")) new ps.reso.instaeclipse.mods.ui.DisableRepostHook().install(context, dexKitBridge, lpparam.classLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | DisableRepost): Could not start, so this feature will not work", t);
                    }

                    try {
                        if (!skip("LockDirectMessagesHook")) new ps.reso.instaeclipse.mods.ui.LockDirectMessagesHook().install(lpparam.classLoader); // Lock DMs (#182)
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | LockDMs): Could not start, so this feature will not work", t);
                    }

                    // Hide in-feed widget units (suggested users panels, surveys, carousels, etc.)
                    try {
                        if (!skip("HideSuggestedFeedItemsHook")) new HideSuggestedFeedItemsHook().install(dexKitBridge, hostClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | HideSuggested): Could not start, so this feature will not work", t);
                    }

                    // Ads Blocker
                    try {
                        if (!skip("AdBlocker")) new AdBlocker().disableSponsoredContent(dexKitBridge, hostClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | AdBlocker): Could not start, so this feature will not work", t);
                    }

                    // tracking link disable
                    try {
                        if (!skip("TrackingLinkDisable")) new TrackingLinkDisable().disableTrackingLinks(hostClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | TrackingLinkDisable): Could not start, so this feature will not work", t);
                    }

                    // Exact timestamps instead of "3h ago" (#232)
                    try {
                        if (!skip("ExactTimestampHook")) new ps.reso.instaeclipse.mods.misc.ExactTimestampHook().install(dexKitBridge, hostClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | ExactTime): Could not start, so this feature will not work", t);
                    }

                    // Auto-expand feed captions (#246)
                    try {
                        if (!skip("ExpandCaptionsHook")) new ps.reso.instaeclipse.mods.feed.ExpandCaptionsHook().install(dexKitBridge);
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | ExpandCaptions): Could not start, so this feature will not work", t);
                    }

                    // Hide community notes (#259)
                    try {
                        if (!skip("HideCommunityNotesHook")) new ps.reso.instaeclipse.mods.feed.HideCommunityNotesHook().install(dexKitBridge, hostClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | CommunityNotes): Could not start, so this feature will not work", t);
                    }

                    // Extras: Reels auto-scroll (the scroll lock lives in ReelViewerHook)
                    try {
                        if (!skip("ReelsControlsHook")) new ps.reso.instaeclipse.mods.extras.ReelsControlsHook().install(dexKitBridge, hostClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | Reels): Could not start, so this feature will not work", t);
                    }

                    // Extras: lift the 5-account limit
                    try {
                        if (!skip("AccountLimitHook")) new ps.reso.instaeclipse.mods.extras.AccountLimitHook().install(dexKitBridge, hostClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | UnlimitedAccounts): Could not start, so this feature will not work", t);
                    }

                    // Extras: keep Instagram's realtime connection down (appear offline)
                    try {
                        if (!skip("AirplaneModeHook")) new ps.reso.instaeclipse.mods.extras.AirplaneModeHook().install(hostClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | Airplane): Could not start, so this feature will not work", t);
                    }

                    // Extras: pick the largest image candidate
                    try {
                        if (!skip("HighResolutionImageHook")) new ps.reso.instaeclipse.mods.extras.HighResolutionImageHook().install(dexKitBridge, hostClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | HighResImages): Could not start, so this feature will not work", t);
                    }

                    // Extras: skip contact/location onboarding pages
                    try {
                        if (!skip("OnboardingPromptHook")) new ps.reso.instaeclipse.mods.extras.OnboardingPromptHook().install(dexKitBridge, hostClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | Onboarding): Could not start, so this feature will not work", t);
                    }

                    // Extras: add FLAG_IMMUTABLE to IG's legacy push registration intent
                    try {
                        if (!skip("NotificationRegistrationHook")) new ps.reso.instaeclipse.mods.extras.NotificationRegistrationHook().install();
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | NotificationFix): Could not start, so this feature will not work", t);
                    }

                    // Startup tab
                    try {
                        if (!skip("StartupTabHook")) new ps.reso.instaeclipse.mods.ui.StartupTabHook().install(hostClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | StartupTab): Could not start, so this feature will not work", t);
                    }

                    // Back on the home feed leaves Instagram (#272)
                    try {
                        if (!skip("BackLeavesHomeHook")) new ps.reso.instaeclipse.mods.ui.BackLeavesHomeHook().install(hostClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(IE|BackLeavesHome): Could not start, so this feature will not work", t);
                    }

                    // Extras: no swipe to camera from the feed
                    try {
                        if (!skip("SwipeToCameraHook")) new ps.reso.instaeclipse.mods.extras.SwipeToCameraHook().install(hostClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | SwipeCamera): Could not start, so this feature will not work", t);
                    }

                    // home feed shows only accounts you follow
                    try {
                        if (!skip("FollowingOnlyFeedHook")) new ps.reso.instaeclipse.mods.feed.FollowingOnlyFeedHook().install(dexKitBridge, hostClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | FollowingOnlyFeed): Could not start, so this feature will not work", t);
                    }

                    // Follow-status label on profiles (#239): the header label first; the name-row
                    // label (ProfileFollowsYouHook) skips profiles where the header label shows.
                    try {
                        if (!skip("ProfileFollowStatusHook")) new ps.reso.instaeclipse.mods.misc.ProfileFollowStatusHook().install(dexKitBridge, hostClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | FollowStatusLabel): Could not start, so this feature will not work", t);
                    }
                    try {
                        if (!skip("ProfileFollowsYouHook")) new ps.reso.instaeclipse.mods.ui.ProfileFollowsYouHook().install(dexKitBridge, hostClassLoader);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | FollowsYou): ❌ Failed to hook");
                    }

                    // Follower toast: hooks IG's friendship-status handling here, once, never at runtime
                    try {
                        if (!skip("FollowStatusHook")) new ps.reso.instaeclipse.mods.misc.FollowStatusHook().install(dexKitBridge, hostClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | FollowerToast): Could not start, so this feature will not work", t);
                    }

                    // Follow status in your own following list
                    try {
                        if (!skip("FollowingFollowStatusHook")) new ps.reso.instaeclipse.mods.misc.FollowingFollowStatusHook().install(context, dexKitBridge, hostClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | FollowingFollowStatus): Could not start, so this feature will not work", t);
                    }

                    // Playback speed for feed videos + forced reel seek bar (#247)
                    try {
                        if (!skip("PlaybackSpeedHook")) new ps.reso.instaeclipse.mods.media.PlaybackSpeedHook().install(dexKitBridge, hostClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | PlaybackSpeed): Could not start, so this feature will not work", t);
                    }

                    // Hide the DM Notes tray (#241)
                    try {
                        if (!skip("HideNotesTrayHook")) new ps.reso.instaeclipse.mods.ui.HideNotesTrayHook().install(dexKitBridge);
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | NotesTray): Could not start, so this feature will not work", t);
                    }

                    // Download DM voice messages (#223)
                    try {
                        if (!skip("VoiceMessageDownloadHook")) new ps.reso.instaeclipse.mods.media.VoiceMessageDownloadHook().install(dexKitBridge, hostClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(IE|VoiceDL) Could not start, so this feature will not work", t);
                    }

                    // Keep DM edit history (#243)
                    try {
                        if (!skip("EditHistoryHook")) new ps.reso.instaeclipse.mods.ghost.EditHistoryHook().install(dexKitBridge, hostClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(IE|EditHistory) Could not start, so this feature will not work", t);
                    }

                    // Jump to the first message of a DM (#244)
                    try {
                        if (!skip("JumpToFirstMessageHook")) new ps.reso.instaeclipse.mods.ghost.JumpToFirstMessageHook().install();
                        if (!skip("ReadReceiptChatHook")) new ps.reso.instaeclipse.mods.ghost.ReadReceiptChatHook().install(hostClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(IE|JumpFirst) Could not start, so this feature will not work", t);
                    }

                    // Search your story viewers (Plus-style)
                    try {
                        if (!skip("StoryViewerSearchHook")) new ps.reso.instaeclipse.mods.ui.StoryViewerSearchHook().install(dexKitBridge, hostClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(IE|ViewerSearch) Could not start, so this feature will not work", t);
                    }

                    // Unlock Plus benefits (local override of Instagram's Plus eligibility checks)
                    try {
                        if (!skip("UnlockPlusBenefitsHook")) new ps.reso.instaeclipse.mods.misc.UnlockPlusBenefitsHook().install(dexKitBridge, hostClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | UnlockPlus): Could not start, so this feature will not work", t);
                    }

                    // Search inside the comments sheet (#230)
                    try {
                        if (!skip("CommentSearchHook")) new ps.reso.instaeclipse.mods.misc.CommentSearchHook().install(dexKitBridge, hostClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(IE|CommentSearch) Could not start, so this feature will not work", t);
                    }

                    // Export a DM chat to HTML (#225)
                    try {
                        if (!skip("ChatExportHook")) new ps.reso.instaeclipse.mods.ghost.ChatExportHook().install(dexKitBridge, hostClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(IE|ChatExport) Could not start, so this feature will not work", t);
                    }

                    // Export followers / following to CSV (#224)
                    try {
                        if (!skip("FollowListExportHook")) new ps.reso.instaeclipse.mods.misc.FollowListExportHook().install(dexKitBridge, hostClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(IE|FollowExport) Could not start, so this feature will not work", t);
                    }

                    // Like undo window (#235) — after HideSuggestedFeedItemsHook (has_liked getter)
                    try {
                        if (!skip("LikeUndoHook")) new ps.reso.instaeclipse.mods.misc.LikeUndoHook().install(dexKitBridge);
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | LikeUndo): Could not start, so this feature will not work", t);
                    }
                    // The same undo window for story likes (their own pipeline)
                    try {
                        if (!skip("StoryLikeUndoHook")) new ps.reso.instaeclipse.mods.misc.StoryLikeUndoHook().install(dexKitBridge);
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | LikeUndo): Could not start for story likes, so undo will not work there", t);
                    }
                    try {
                        // View.performClick hook: installed only while the feature is on (restart to enable).
                        if (FeatureFlags.confirmFollows && !skip("FollowConfirmHook")) new ps.reso.instaeclipse.mods.misc.FollowConfirmHook().install(dexKitBridge);
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | FollowConfirm): Could not start, so this feature will not work", t);
                    }
                    try {
                        if (!skip("ProfileMenuHook")) new ps.reso.instaeclipse.mods.ui.ProfileMenuHook().install(dexKitBridge, hostClassLoader);
                        if (!skip("PostCountSpoof")) ps.reso.instaeclipse.mods.feed.PostCountSpoof.install(dexKitBridge, hostClassLoader);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(IE|ProfileMenu) ❌ Failed to hook");
                    }
                    try {
                        if (!skip("ProfileSpoofHook")) new ps.reso.instaeclipse.mods.ui.ProfileSpoofHook().install(dexKitBridge, hostClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(IE|ProfileSpoof) Could not start, so this feature will not work", t);
                    }

                    // Hide "Create group" on the DM share sheet (#240)
                    try {
                        if (!skip("ShareSheetCleanupHook")) new ps.reso.instaeclipse.mods.ui.ShareSheetCleanupHook().install(hostClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | ShareSheet): Could not start, so this feature will not work", t);
                    }

                    // Open links in the external browser (#229): Activity launches here, and the
                    // fragment-hosted in-app browser modes in OpenLinksExternallyHook.
                    try {
                        if (!skip("ExternalBrowserHook")) new ps.reso.instaeclipse.mods.misc.ExternalBrowserHook().install();
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | ExternalBrowser): Could not start, so this feature will not work", t);
                    }
                    try {
                        if (!skip("OpenLinksExternallyHook")) new ps.reso.instaeclipse.mods.misc.OpenLinksExternallyHook().install(dexKitBridge, hostClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | OpenLinksExternally): Could not start, so this feature will not work", t);
                    }

                    // Miscellaneous
                    try {
                        if (!skip("DisableStoryFlippingHook")) new DisableStoryFlippingHook().handleStoryFlippingDisable(dexKitBridge); // Story Flipping
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | StoryFlipping): Could not start, so this feature will not work", t);
                    }

                    // Story Mentions
                    try {
                        if (!skip("StoryMentionHook")) new StoryMentionHook().install(dexKitBridge, lpparam.classLoader);
                        if (!skip("StoryLinksHook")) new ps.reso.instaeclipse.mods.misc.StoryLinksHook().install(dexKitBridge, lpparam.classLoader);
                        if (!skip("StoryInfoHook")) new ps.reso.instaeclipse.mods.misc.StoryInfoHook().install(dexKitBridge, lpparam.classLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | StoryMentions): Could not start, so this feature will not work", t);
                    }

                    // Manual "Mark as seen" story row; after StoryMentionHook, whose cached menu
                    // builders it reuses.
                    try {
                        if (!skip("ManualStorySeenHook")) new ps.reso.instaeclipse.mods.ghost.ManualStorySeenHook().install(dexKitBridge, lpparam.classLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | ManualStorySeen): Could not start, so this feature will not work", t);
                    }

                    // Comment Copy (Instagram's own long-press Copy row)
                    try {
                        if (!skip("CommentCopyHook")) new CommentCopyHook().install(lpparam.classLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | CopyComment): Could not start, so this feature will not work", t);
                    }

                    // Caption Copy (hold a caption to copy it)
                    try {
                        if (!skip("CaptionCopyHook")) new CaptionCopyHook().install(dexKitBridge, lpparam.classLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | Caption): Could not start, so this feature will not work", t);
                    }

                    // Disable Double Tap to Like
                    try {
                        if (!skip("DisableDoubleTapLikeHook")) new DisableDoubleTapLikeHook().install(dexKitBridge, lpparam.classLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | DoubleTapLike): Could not start, so this feature will not work", t);
                    }
                    // ...and in comments and DMs
                    try {
                        if (!skip("DoubleTapExtrasHook")) new ps.reso.instaeclipse.mods.misc.DoubleTapExtrasHook().install(dexKitBridge, lpparam.classLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | DoubleTapLike): Could not start for comments and chats, so double tap still likes there", t);
                    }

                    // Photo Zoom (long-press)
                    try {
                        if (!skip("FeedPhotoZoomHook")) new FeedPhotoZoomHook().install(lpparam.classLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | PhotoZoom): Could not start, so this feature will not work", t);
                    }

                    // Location Spoof
                    try {
                        if (!skip("LocationSpoofHook")) new LocationSpoofHook().install(lpparam.classLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | SpoofLocation): Could not start, so this feature will not work", t);
                    }

                    // Custom Theme
                    try {
                        if (!skip("IgThemeHook")) new IgThemeHook().install(hostClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | Theme): Could not start, so this feature will not work", t);
                    }

                    // Force Reel Quality
                    try {
                        if (!skip("ForceReelQualityHook")) new ForceReelQualityHook().install(dexKitBridge, lpparam.classLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | ForceReelQuality): Could not start, so this feature will not work", t);
                    }

                    // Force "Upload at highest quality" always on
                    try {
                        if (!skip("MaxUploadQualityHook")) new ps.reso.instaeclipse.mods.media.MaxUploadQualityHook().install(dexKitBridge, lpparam.classLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(IE|MaxUploadQuality) Could not start, so this feature will not work", t);
                    }

                    try {
                        if (!skip("DisableVideoAutoPlayHook")) new DisableVideoAutoPlayHook().handleAutoPlayDisable(dexKitBridge); // Video Autoplay
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | AutoPlayDisable): Could not start, so this feature will not work", t);
                    }

                    // Build Expired Popup
                    try {
                        if (!skip("BuildExpiredPopupHook")) new BuildExpiredPopupHook().install(dexKitBridge, lpparam.classLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | BuildExpired): Could not start, so this feature will not work", t);
                    }

                    // Media Download (feed)
                    try {
                        if (!skip("FeedVideoDownloadHook")) new FeedVideoDownloadHook().install(lpparam.classLoader);
                        if (!skip("FeedVideoDownloadHook")) FeedVideoDownloadHook.installVideoUrlCaptureHook(dexKitBridge, lpparam.classLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | MediaDownload): Could not start, so this feature will not work", t);
                    }

                    // Post Download — three-dots menu (replaces floating button + long-press)
                    try {
                        if (!skip("PostDownloadContextMenuHook")) new PostDownloadContextMenuHook().install(dexKitBridge, lpparam.classLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | PostDownload): Could not start, so this feature will not work", t);
                    }

                    // Titles for our rows in the reel ⋮ menu (Media info, Playback speed)
                    try {
                        if (!skip("ReelMenuLabels")) ps.reso.instaeclipse.mods.ui.ReelMenuLabels.install(dexKitBridge, lpparam.classLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | ReelMenuLabels): Could not start, so this feature will not work", t);
                    }

                    // Save Instants (#184) — long-press a received Instant (quicksnap) to save it
                    try {
                        if (!skip("InstantSaveHook")) new ps.reso.instaeclipse.mods.media.InstantSaveHook().install(lpparam.classLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | InstantSave): Could not start, so this feature will not work", t);
                    }

                    // Upload Instants from gallery (#199) — swap gallery bitmap into quicksnap send
                    try {
                        if (!skip("InstantUploadHook")) new ps.reso.instaeclipse.mods.media.InstantUploadHook().install(lpparam.classLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | InstantUpload): Could not start, so this feature will not work", t);
                    }

                    // Keep Ephemeral Messages
                    try {
                        if (!skip("GhostEphemeralKeepHook")) new GhostEphemeralKeepHook().install(dexKitBridge, lpparam.classLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | EphemeralHook): Could not start, so this feature will not work", t);
                    }

                    // Permanent View Mode (view-once / view-twice → permanent)
                    try {
                        if (!skip("GhostPermanentViewHook")) new GhostPermanentViewHook().install(dexKitBridge, lpparam.classLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | ViewOnceMedia): Could not start, so this feature will not work", t);
                    }

                    // Restore IG's native view-once/twice corner icon when Permanent View is on
                    try {
                        if (!skip("ViewOnceBadgeHook")) new ViewOnceBadgeHook().install(dexKitBridge, lpparam.classLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | VOBadge): Could not start, so this feature will not work", t);
                    }

                    // Locked saved collections (#219)
                    try {
                        if (!skip("LockedCollectionsHook")) new ps.reso.instaeclipse.mods.ui.LockedCollectionsHook().install(hostClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(IE|LockedColl) Could not start, so this feature will not work", t);
                    }

                    // Saved button on your own profile (#237)
                    try {
                        if (!skip("SavedOnProfileHook")) new ps.reso.instaeclipse.mods.ui.SavedOnProfileHook().install(dexKitBridge, hostClassLoader);
                        if (!skip("HideHeaderButtonsHook")) new ps.reso.instaeclipse.mods.ui.HideHeaderButtonsHook().install(dexKitBridge, hostClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(IE|SavedProfile) Could not start, so this feature will not work", t);
                    }

                    // Download a post/reel link shared to InstaEclipse from another app (#236)
                    try {
                        if (!skip("ShareLinkDownloadHook")) new ps.reso.instaeclipse.mods.media.ShareLinkDownloadHook().install(hostClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(IE|ShareDL) Could not start, so this feature will not work", t);
                    }

                    // Ask before calling (#277)
                    try {
                        // View.performClick hook: installed only while the feature is on (restart to enable).
                        if (FeatureFlags.confirmCalls && !skip("CallConfirmHook")) ps.reso.instaeclipse.mods.ghost.CallConfirmHook.install();
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | CallConfirm): Could not start, so this feature will not work", t);
                    }

                    // Don't save searches (#280)
                    try {
                        if (!skip("SearchHistoryHook")) ps.reso.instaeclipse.mods.ghost.SearchHistoryHook.install(dexKitBridge, hostClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | SearchHistory): Could not start, so this feature will not work", t);
                    }

                    // Ask before refreshing the feed (#278)
                    try {
                        if (!skip("FeedRefreshConfirmHook")) ps.reso.instaeclipse.mods.feed.FeedRefreshConfirmHook.install(hostClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | RefreshConfirm): Could not start, so this feature will not work", t);
                    }

                    // Rounded feed post corners (#276)
                    try {
                        if (!skip("RoundedFeedPostsHook")) ps.reso.instaeclipse.mods.feed.RoundedFeedPostsHook.install(hostClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | RoundedPosts): Could not start, so this feature will not work", t);
                    }

                    // Story tray size and ring color (#283)
                    try {
                        if (!skip("StoryTrayHook")) ps.reso.instaeclipse.mods.ui.StoryTrayHook.install(hostClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | StoryTray): Could not start, so this feature will not work", t);
                    }

                    // Story Download
                    try {
                        if (!skip("StoryDownloadHook")) new StoryDownloadHook().install(dexKitBridge, lpparam.classLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | StoryDownload): Could not start, so this feature will not work", t);
                    }

                    // Reel Download
                    try {
                        if (!skip("ReelDownloadHook")) new ReelDownloadHook().install(dexKitBridge, lpparam.classLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | ReelDownload): Could not start, so this feature will not work", t);
                    }

                    // Profile Picture Download
                    try {
                        if (!skip("ProfilePicDownloadHook")) ProfilePicDownloadHook.install(dexKitBridge, hostClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | ProfileDownload): Could not start, so this feature will not work", t);
                    }

                    // Network Interceptor
                    try {
                        if (!skip("interceptor")) interceptor.handleInterceptor(lpparam);
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | Interceptor): Could not start, so this feature will not work", t);
                    }

                    // Crash guard: drop tasks rejected by already-shut-down executors (carousel/
                    // realtime teardown race on IG 446+/447.0.0.39+) instead of letting AbortPolicy
                    // throw and hard-crash the app.
                    try {
                        if (!skip("TerminatedExecutorGuard")) new ps.reso.instaeclipse.mods.core.TerminatedExecutorGuard().install(lpparam.classLoader);
                    } catch (Throwable t) {
                        ModuleLog.error("(InstaEclipse | ExecGuard): Could not start, so this feature will not work", t);
                    }

                    // Every lookup is cached now: later launches install inline.
                    DexKitCache.saveString(CACHE_COMPLETE_KEY, CACHE_COMPLETE_VALUE);
                    ps.reso.instaeclipse.utils.feature.FeatureStatusTracker.logSummary(context);
                    };

                    if (DexKitCache.isCacheValid() && CACHE_COMPLETE_VALUE.equals(DexKitCache.loadString(CACHE_COMPLETE_KEY))) {
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
                                    + (android.os.SystemClock.elapsedRealtime() - start) + " ms)");
                        }, "InstaEclipse-Init");
                        t.setPriority(Thread.NORM_PRIORITY - 1);
                        t.start();
                    }
                }

            });

        } catch (Exception e) {
            ModuleLog.error("(InstaEclipse): Could not start in " + lpparam.packageName + ", so no feature will work", e);
        }
    }

    /**
     * Injects a dynamic receiver into Instagram to listen for settings changes
     * sent from the InstaEclipse companion app (FeaturesFragment staging system).
     */
    private void registerSyncReceiver(Context context) {
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
