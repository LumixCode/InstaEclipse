package ps.reso.instaeclipse.utils.core;

import ps.reso.instaeclipse.utils.feature.ExtrasFeatures;
import android.content.Context;
import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import ps.reso.instaeclipse.mods.reels.DailyReelLimit;
import ps.reso.instaeclipse.mods.reels.NightReelSchedule;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureManager;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.media.StoryCacheRetention;

public class SettingsManager {
    private static final String PREF_NAME = "instaeclipse_prefs";

    /** Extreme Mode keeps these on: turning one off from outside the locked menus is refused. */
    private static final Set<String> EXTREME_KEEP_ON = new HashSet<>(Arrays.asList(
            "disableStories", "disableFeed", "disableReels", "disableExplore", "disableComments",
            "dailyReelLimitEnabled", "nightReelBlockEnabled"));
    /** Extreme Mode freezes these entirely (any new value could loosen the reel limits). */
    private static final Set<String> EXTREME_FROZEN = new HashSet<>(Arrays.asList(
            "isExtremeMode", "disableReelsExceptDM", "dailyReelLimitAllowDM", "nightReelBlockAllowDM",
            "dailyReelLimit", "nightReelBlockStartMinutes", "nightReelBlockEndMinutes"));
    /** While a passcode is set, only Instagram's Lock menu (it asks for the passcode) turns these off. */
    private static final Set<String> PASSCODE_LOCKS = new HashSet<>(Arrays.asList(
            "lockWholeApp", "lockDirectMessages", "lockSpecificMessages", "lockDirectAlways",
            "lockSavedCollections"));
    private static SharedPreferences prefs;
    /** Application context, for pushing in-app changes to the companion (CompanionSync). */
    private static Context appContext;

    public static void init(Context context) {
        if (appContext == null && context != null) {
            appContext = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        }
        if (prefs == null) {
            prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        }
    }

    public static void saveAllFlags() {
        // Snapshot to stamp the keys this save changes (see CompanionSync).
        java.util.Map<String, ?> before = new java.util.HashMap<>(prefs.getAll());
        SharedPreferences.Editor editor = prefs.edit();

        editor.putBoolean("isDevEnabled", FeatureFlags.isDevEnabled);

        // Ghost Mode
        editor.putBoolean("isGhostModeEnabled", FeatureFlags.isGhostModeEnabled);
        editor.putBoolean("isGhostSeen", FeatureFlags.isGhostSeen);
        editor.putBoolean("isGhostTyping", FeatureFlags.isGhostTyping);
        editor.putBoolean("isGhostScreenshot", FeatureFlags.isGhostScreenshot);
        editor.putBoolean("isGhostViewOnce", FeatureFlags.isGhostViewOnce);
        editor.putBoolean("isGhostStory", FeatureFlags.isGhostStory);
        editor.putBoolean("manualStorySeen", FeatureFlags.manualStorySeen);
        editor.putBoolean("isGhostLive", FeatureFlags.isGhostLive);
        editor.putBoolean("allowScreenshots", FeatureFlags.allowScreenshots);
        editor.putBoolean("keepEphemeralMessages", FeatureFlags.keepEphemeralMessages);
        editor.putBoolean("permanentViewMode", FeatureFlags.permanentViewMode);
        editor.putBoolean("keepUnsentMessages", FeatureFlags.keepUnsentMessages);
        editor.putBoolean("keepUnsentInChat", FeatureFlags.keepUnsentInChat);
        editor.putBoolean("showUnsentIndicator", FeatureFlags.showUnsentIndicator);
        editor.putBoolean("showUnsentButton", FeatureFlags.showUnsentButton);
        editor.putBoolean("autoClearCache", FeatureFlags.autoClearCache);
        editor.putInt("autoClearCacheSizeMb", FeatureFlags.autoClearCacheSizeMb);
        editor.putBoolean("removeMetaAI", FeatureFlags.removeMetaAI);
        editor.putBoolean("lockDirectMessages", FeatureFlags.lockDirectMessages);
        editor.putBoolean("lockSpecificMessages", FeatureFlags.lockSpecificMessages);
        editor.putString("lockedDirectThreads", FeatureFlags.lockedDirectThreads);
        editor.putString("lockDirectPasscode", FeatureFlags.lockDirectPasscode);
        editor.putString("lockDirectSalt", FeatureFlags.lockDirectSalt);
        editor.putBoolean("lockDirectAlways", FeatureFlags.lockDirectAlways);
        editor.putBoolean("lockWholeApp", FeatureFlags.lockWholeApp);
        editor.putBoolean("hideSpecificChats", FeatureFlags.hideSpecificChats);
        editor.putBoolean("showHideChatButton", FeatureFlags.showHideChatButton);
        editor.putBoolean("downloaderUseOriginal", FeatureFlags.downloaderUseOriginal);
        editor.putInt("downloaderQualityMaxShortSide", FeatureFlags.downloaderQualityMaxShortSide);
        editor.putInt("storyTrayScale", FeatureFlags.storyTrayScale);
        editor.putInt("feedCornerRadius", FeatureFlags.feedCornerRadius);
        editor.putString("storyRingColor", FeatureFlags.storyRingColor);
        editor.putBoolean("downloaderCompatibility", FeatureFlags.downloaderCompatibility);
        editor.putBoolean("downloaderAllowFallback", FeatureFlags.downloaderAllowFallback);
        editor.putBoolean("lockUseFingerprint", FeatureFlags.lockUseFingerprint);

        // Quick Toggles
        editor.putBoolean("quickToggleSeen", FeatureFlags.quickToggleSeen);
        editor.putBoolean("quickToggleTyping", FeatureFlags.quickToggleTyping);
        editor.putBoolean("quickToggleScreenshot", FeatureFlags.quickToggleScreenshot);
        editor.putBoolean("quickToggleViewOnce", FeatureFlags.quickToggleViewOnce);
        editor.putBoolean("quickToggleStory", FeatureFlags.quickToggleStory);
        editor.putBoolean("quickToggleLive", FeatureFlags.quickToggleLive);
        editor.putBoolean("quickToggleEphemeral", FeatureFlags.quickToggleEphemeral);
        editor.putBoolean("quickTogglePermanentView", FeatureFlags.quickTogglePermanentView);
        editor.putBoolean("quickToggleAllowScreenshots", FeatureFlags.quickToggleAllowScreenshots);

        // Distraction Free
        editor.putBoolean("isExtremeMode", FeatureFlags.isExtremeMode);
        editor.putBoolean("isDistractionFree", FeatureFlags.isDistractionFree);
        editor.putBoolean("disableStories", FeatureFlags.disableStories);
        editor.putBoolean("disableFeed", FeatureFlags.disableFeed);
        editor.putBoolean("disableReels", FeatureFlags.disableReels);
        editor.putBoolean("disableReelsExceptDM", FeatureFlags.disableReelsExceptDM);
        editor.putBoolean("disableExplore", FeatureFlags.disableExplore);
        editor.putBoolean("disableComments", FeatureFlags.disableComments);
        editor.putBoolean("dailyReelLimitEnabled", FeatureFlags.dailyReelLimitEnabled);
        editor.putBoolean("dailyReelLimitAllowDM", FeatureFlags.dailyReelLimitAllowDM);
        editor.putInt("dailyReelLimit", FeatureFlags.dailyReelLimit);
        editor.putBoolean("nightReelBlockEnabled", FeatureFlags.nightReelBlockEnabled);
        editor.putInt("nightReelBlockStartMinutes", FeatureFlags.nightReelBlockStartMinutes);
        editor.putInt("nightReelBlockEndMinutes", FeatureFlags.nightReelBlockEndMinutes);
        editor.putBoolean("nightReelBlockAllowDM", FeatureFlags.nightReelBlockAllowDM);

        // Clean Feed
        editor.putBoolean("hideSuggestionsInFeed", FeatureFlags.hideSuggestionsInFeed);
        editor.putBoolean("hideThreadsSuggestions", FeatureFlags.hideThreadsSuggestions);
        editor.putBoolean("hideLikedPosts", FeatureFlags.hideLikedPosts);
        editor.putBoolean("confirmFeedRefresh", FeatureFlags.confirmFeedRefresh);
        editor.putBoolean("hideCommunityNotes", FeatureFlags.hideCommunityNotes);
        editor.putBoolean("followingOnlyFeed", FeatureFlags.followingOnlyFeed);
        editor.putBoolean("hidePlusUpsells", FeatureFlags.hidePlusUpsells);
        editor.putBoolean("expandCaptions", FeatureFlags.expandCaptions);
        editor.putBoolean("offerAudioDownload", FeatureFlags.offerAudioDownload);
        editor.putBoolean("enableVoiceDownload", FeatureFlags.enableVoiceDownload);
        editor.putBoolean("enableStickerDownload", FeatureFlags.enableStickerDownload);
        editor.putBoolean("enableDmMediaDownload", FeatureFlags.enableDmMediaDownload);
        editor.putBoolean("offerCopyImage", FeatureFlags.offerCopyImage);
        editor.putBoolean("offerMediaInfo", FeatureFlags.offerMediaInfo);
        editor.putBoolean("offerPlaybackSpeed", FeatureFlags.offerPlaybackSpeed);
        editor.putBoolean("maxUploadQuality", FeatureFlags.maxUploadQuality);
        editor.putBoolean("forceReelScrubber", FeatureFlags.forceReelScrubber);
        editor.putBoolean("holdLeftSlowMotion", FeatureFlags.holdLeftSlowMotion);
        editor.putBoolean("keepEditHistory", FeatureFlags.keepEditHistory);
        editor.putBoolean("jumpToFirstMessage", FeatureFlags.jumpToFirstMessage);
        editor.putBoolean("hideChatMenuButton", FeatureFlags.hideChatMenuButton);
        editor.putBoolean("translateMessages", FeatureFlags.translateMessages);
        editor.putBoolean("bulkUnsend", FeatureFlags.bulkUnsend);
        editor.putBoolean("chatExport", FeatureFlags.chatExport);
        editor.putBoolean("hideTabHome", FeatureFlags.hideTabHome);
        editor.putBoolean("hideTabReels", FeatureFlags.hideTabReels);
        editor.putBoolean("hideTabCreate", FeatureFlags.hideTabCreate);
        editor.putBoolean("hideTabMessages", FeatureFlags.hideTabMessages);
        editor.putBoolean("hideTabActivity", FeatureFlags.hideTabActivity);
        editor.putBoolean("hideTabProfile", FeatureFlags.hideTabProfile);
        editor.putBoolean("backLeavesHome", FeatureFlags.backLeavesHome);
        editor.putBoolean("hideNotesTray", FeatureFlags.hideNotesTray);
        editor.putBoolean("hideDmSuggestions", FeatureFlags.hideDmSuggestions);
        editor.putBoolean("hideInstants", FeatureFlags.hideInstants);
        editor.putBoolean("hideDmBlend", FeatureFlags.hideDmBlend);
        editor.putBoolean("readReceiptChats", FeatureFlags.readReceiptChats);
        editor.putBoolean("hideThreadsButton", FeatureFlags.hideThreadsButton);

        // Ads
        editor.putBoolean("isAdBlockEnabled", FeatureFlags.isAdBlockEnabled);
        editor.putBoolean("isAnalyticsBlocked", FeatureFlags.isAnalyticsBlocked);
        editor.putBoolean("disableTrackingLinks", FeatureFlags.disableTrackingLinks);
        editor.putBoolean("openLinksExternally", FeatureFlags.openLinksExternally);

        // Misc
        editor.putBoolean("isMiscEnabled", FeatureFlags.isMiscEnabled);
        editor.putBoolean("disableStoryFlipping", FeatureFlags.disableStoryFlipping);
        editor.putBoolean("disableVideoAutoPlay", FeatureFlags.disableVideoAutoPlay);
        editor.putBoolean("spoofLastSeen", FeatureFlags.spoofLastSeen);
        editor.putBoolean("disableSearchHistory", FeatureFlags.disableSearchHistory);
        editor.putBoolean("confirmCalls", FeatureFlags.confirmCalls);
        editor.putBoolean("spoofLocation", FeatureFlags.spoofLocation);
        editor.putString("spoofLat", String.valueOf(FeatureFlags.spoofLat));
        editor.putString("spoofLng", String.valueOf(FeatureFlags.spoofLng));
        editor.putString("spoofLabel", FeatureFlags.spoofLabel);
        editor.putString("spoofRecent", FeatureFlags.spoofRecent);
        editor.putInt("forceReelQuality", FeatureFlags.forceReelQuality);
        editor.putBoolean("disableRepost", FeatureFlags.disableRepost);
        editor.putBoolean("hideShareCreateGroup", FeatureFlags.hideShareCreateGroup);
        editor.putBoolean("exactTimestamps", FeatureFlags.exactTimestamps);
        editor.putBoolean("likeUndo", FeatureFlags.likeUndo);
        editor.putBoolean("showFollowStatusLabel", FeatureFlags.showFollowStatusLabel);
        editor.putBoolean("showFollowingFollowStatus", FeatureFlags.showFollowingFollowStatus);
        editor.putBoolean("unlockPlusBenefits", FeatureFlags.unlockPlusBenefits);
        editor.putBoolean("confirmLikes", FeatureFlags.confirmLikes);
        editor.putBoolean("confirmFollows", FeatureFlags.confirmFollows);
        editor.putBoolean("profileMenuTools", FeatureFlags.profileMenuTools);
        editor.putBoolean("postCountSpoof", FeatureFlags.postCountSpoof);
        editor.putBoolean("profileSpoofer", FeatureFlags.profileSpoofer);
        editor.putInt("spoofFollowers", FeatureFlags.spoofFollowers);
        editor.putInt("spoofFollowing", FeatureFlags.spoofFollowing);
        editor.putInt("spoofPosts", FeatureFlags.spoofPosts);
        editor.putBoolean("spoofVerified", FeatureFlags.spoofVerified);
        editor.putString("spoofName", FeatureFlags.spoofName);
        editor.putString("spoofUsername", FeatureFlags.spoofUsername);
        editor.putString("spoofBio", FeatureFlags.spoofBio);
        editor.putString("spoofAvatar", FeatureFlags.spoofAvatar);
        editor.putBoolean("profileAnalyzer", FeatureFlags.profileAnalyzer);
        editor.putBoolean("fancyBioFont", FeatureFlags.fancyBioFont);
        editor.putBoolean("savedOnProfile", FeatureFlags.savedOnProfile);
        editor.putBoolean("lockSavedCollections", FeatureFlags.lockSavedCollections);
        editor.putString("lockedCollections", FeatureFlags.lockedCollections);
        editor.putBoolean("storyViewerSearch", FeatureFlags.storyViewerSearch);
        editor.putBoolean("storyLinks", FeatureFlags.storyLinks);
        editor.putBoolean("storyInfo", FeatureFlags.storyInfo);
        editor.putBoolean("commentSearch", FeatureFlags.commentSearch);
        editor.putBoolean("followListExport", FeatureFlags.followListExport);
        editor.putBoolean("followersHistory", FeatureFlags.followersHistory);
        editor.putBoolean("showFollowerToast", FeatureFlags.showFollowerToast);
        editor.putBoolean("showFeatureToasts", FeatureFlags.showFeatureToasts);
        editor.putBoolean("enableStoryMentions", FeatureFlags.enableStoryMentions);
        editor.putBoolean("disableDiscoverPeople", FeatureFlags.disableDiscoverPeople);
        editor.putBoolean("removeBuildExpiredPopup", FeatureFlags.removeBuildExpiredPopup);
        editor.putBoolean("enableCopyComment", FeatureFlags.enableCopyComment);
        editor.putBoolean("enableCaptionCopy", FeatureFlags.enableCaptionCopy);
        editor.putBoolean("disableDoubleTapLike", FeatureFlags.disableDoubleTapLike);
        editor.putBoolean("enablePhotoZoom", FeatureFlags.enablePhotoZoom);
        editor.putBoolean("enablePostDownload", FeatureFlags.enablePostDownload);
        editor.putBoolean("enableStoryDownload", FeatureFlags.enableStoryDownload);
        editor.putBoolean("enableReelDownload", FeatureFlags.enableReelDownload);
        editor.putBoolean("enableProfileDownload", FeatureFlags.enableProfileDownload);
        editor.putBoolean("downloaderUsernameFolder", FeatureFlags.downloaderUsernameFolder);
        editor.putBoolean("downloaderAddTimestamp", FeatureFlags.downloaderAddTimestamp);
        editor.putBoolean("copyMediaLink", FeatureFlags.copyMediaLink);
        editor.putBoolean("saveInstants", FeatureFlags.saveInstants);
        editor.putBoolean("uploadInstants", FeatureFlags.uploadInstants);
        editor.putBoolean("cacheStories", FeatureFlags.cacheStories);
        FeatureFlags.storyCacheHours = StoryCacheRetention.normalize(FeatureFlags.storyCacheHours);
        editor.putInt("storyCacheHours", FeatureFlags.storyCacheHours);
        editor.putBoolean("customFontEnabled", FeatureFlags.customFontEnabled);
        editor.putString("customFontPath", FeatureFlags.customFontPath);
        editor.putString("navTabOrder", FeatureFlags.navTabOrder);
        editor.putString("startupTab", FeatureFlags.startupTab);
        editor.putString("customShareDomain", FeatureFlags.customShareDomain);
        editor.putString("exactTimestampFormat", FeatureFlags.exactTimestampFormat);
        editor.putString("spoofDevice", FeatureFlags.spoofDevice);
        editor.putBoolean("customEmojiEnabled", FeatureFlags.customEmojiEnabled);
        editor.putString("customEmojiPath", FeatureFlags.customEmojiPath);
        editor.putString("downloaderCustomPath", FeatureFlags.downloaderCustomPath);
        editor.putString("downloaderCustomUri",  FeatureFlags.downloaderCustomUri);

        // Custom Theme
        editor.putBoolean("customThemeEnabled", FeatureFlags.customThemeEnabled);
        editor.putInt("themePresetId", FeatureFlags.themePresetId);
        editor.putInt("themeLightPresetId", FeatureFlags.themeLightPresetId);
        editor.putString("themePaletteJson", FeatureFlags.themePaletteJson);

        for (String flag : ExtrasFeatures.flags()) editor.putBoolean(flag, ExtrasFeatures.get(flag));

        editor.apply();
        stampChangedKeys(before);
        // Instagram -> companion: so the companion app shows what was just changed here.
        CompanionSync.pushAsync(appContext);

        FeatureManager.refreshFeatureStatus();
    }

    /** Marks keys whose value this save changed, so an older companion value never overrides them. */
    private static void stampChangedKeys(java.util.Map<String, ?> before) {
        long now = System.currentTimeMillis();
        SharedPreferences.Editor e = null;
        for (java.util.Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            String k = entry.getKey();
            if (k.startsWith("__")) continue;
            if (java.util.Objects.equals(before.get(k), entry.getValue())) continue;
            if (e == null) e = prefs.edit();
            e.putLong("__igts_" + k, now);
        }
        if (e != null) e.apply();
    }

    public static void loadAllFlags(Context context) {
        if (prefs == null) {
            prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        }

        FeatureFlags.isDevEnabled = prefs.getBoolean("isDevEnabled", false);

        // Ghost Mode
        FeatureFlags.isGhostModeEnabled = prefs.getBoolean("isGhostModeEnabled", false);
        FeatureFlags.isGhostSeen = prefs.getBoolean("isGhostSeen", false);
        FeatureFlags.isGhostTyping = prefs.getBoolean("isGhostTyping", false);
        FeatureFlags.isGhostScreenshot = prefs.getBoolean("isGhostScreenshot", false);
        FeatureFlags.isGhostViewOnce = prefs.getBoolean("isGhostViewOnce", false);
        FeatureFlags.isGhostStory = prefs.getBoolean("isGhostStory", false);
        FeatureFlags.manualStorySeen = prefs.getBoolean("manualStorySeen", false);
        FeatureFlags.isGhostLive = prefs.getBoolean("isGhostLive", false);
        FeatureFlags.allowScreenshots = prefs.getBoolean("allowScreenshots", false);
        FeatureFlags.keepEphemeralMessages = prefs.getBoolean("keepEphemeralMessages", false);
        FeatureFlags.permanentViewMode = prefs.getBoolean("permanentViewMode", false);
        FeatureFlags.keepUnsentMessages = prefs.getBoolean("keepUnsentMessages", false);
        FeatureFlags.keepUnsentInChat = prefs.getBoolean("keepUnsentInChat", true);
        FeatureFlags.showUnsentIndicator = prefs.getBoolean("showUnsentIndicator", false);
        FeatureFlags.showUnsentButton = prefs.getBoolean("showUnsentButton", true);
        FeatureFlags.autoClearCache = prefs.getBoolean("autoClearCache", false);
        FeatureFlags.autoClearCacheSizeMb = prefs.getInt("autoClearCacheSizeMb", 100);
        FeatureFlags.removeMetaAI = prefs.getBoolean("removeMetaAI", false);
        FeatureFlags.lockDirectMessages = prefs.getBoolean("lockDirectMessages", false);
        FeatureFlags.lockSpecificMessages = prefs.getBoolean("lockSpecificMessages", false);
        FeatureFlags.lockedDirectThreads = prefs.getString("lockedDirectThreads", "{}");
        FeatureFlags.lockDirectPasscode = prefs.getString("lockDirectPasscode", "");
        FeatureFlags.lockDirectSalt = prefs.getString("lockDirectSalt", "");
        FeatureFlags.lockDirectAlways = prefs.getBoolean("lockDirectAlways", false);
        FeatureFlags.lockWholeApp = prefs.getBoolean("lockWholeApp", false);
        FeatureFlags.hideSpecificChats = prefs.getBoolean("hideSpecificChats", false);
        FeatureFlags.showHideChatButton = prefs.getBoolean("showHideChatButton", true);
        FeatureFlags.downloaderUseOriginal = prefs.getBoolean("downloaderUseOriginal", true);
        FeatureFlags.downloaderQualityMaxShortSide = prefs.getInt("downloaderQualityMaxShortSide", 0);
        FeatureFlags.storyTrayScale = prefs.getInt("storyTrayScale", 100);
        FeatureFlags.feedCornerRadius = prefs.getInt("feedCornerRadius", 0);
        FeatureFlags.storyRingColor = prefs.getString("storyRingColor", "");
        FeatureFlags.downloaderCompatibility = prefs.getBoolean("downloaderCompatibility", false);
        FeatureFlags.downloaderAllowFallback = prefs.getBoolean("downloaderAllowFallback", false);
        FeatureFlags.lockUseFingerprint = prefs.getBoolean("lockUseFingerprint", true);

        // Quick Toggles
        FeatureFlags.quickToggleSeen = prefs.getBoolean("quickToggleSeen", false);
        FeatureFlags.quickToggleTyping = prefs.getBoolean("quickToggleTyping", false);
        FeatureFlags.quickToggleScreenshot = prefs.getBoolean("quickToggleScreenshot", false);
        FeatureFlags.quickToggleViewOnce = prefs.getBoolean("quickToggleViewOnce", false);
        FeatureFlags.quickToggleStory = prefs.getBoolean("quickToggleStory", false);
        FeatureFlags.quickToggleLive = prefs.getBoolean("quickToggleLive", false);
        FeatureFlags.quickToggleEphemeral = prefs.getBoolean("quickToggleEphemeral", false);
        FeatureFlags.quickTogglePermanentView = prefs.getBoolean("quickTogglePermanentView", false);
        FeatureFlags.quickToggleAllowScreenshots = prefs.getBoolean("quickToggleAllowScreenshots", false);

        // Distraction Free
        FeatureFlags.isExtremeMode = prefs.getBoolean("isExtremeMode", false);
        FeatureFlags.isDistractionFree = prefs.getBoolean("isDistractionFree", false);
        FeatureFlags.disableStories = prefs.getBoolean("disableStories", false);
        FeatureFlags.disableFeed = prefs.getBoolean("disableFeed", false);
        FeatureFlags.disableReels = prefs.getBoolean("disableReels", false);
        FeatureFlags.disableReelsExceptDM = prefs.getBoolean("disableReelsExceptDM", false);
        FeatureFlags.disableExplore = prefs.getBoolean("disableExplore", false);
        FeatureFlags.disableComments = prefs.getBoolean("disableComments", false);
        FeatureFlags.dailyReelLimitEnabled = prefs.getBoolean("dailyReelLimitEnabled", false);
        FeatureFlags.dailyReelLimitAllowDM = prefs.getBoolean("dailyReelLimitAllowDM", true);
        FeatureFlags.dailyReelLimit = DailyReelLimit.validLimit(prefs.getInt("dailyReelLimit", 100));
        FeatureFlags.nightReelBlockEnabled = prefs.getBoolean("nightReelBlockEnabled", false);
        FeatureFlags.nightReelBlockStartMinutes = NightReelSchedule.validMinutes(
                prefs.getInt("nightReelBlockStartMinutes", 22 * 60), 22 * 60);
        FeatureFlags.nightReelBlockEndMinutes = NightReelSchedule.validMinutes(
                prefs.getInt("nightReelBlockEndMinutes", 7 * 60), 7 * 60);
        if (FeatureFlags.nightReelBlockStartMinutes == FeatureFlags.nightReelBlockEndMinutes) {
            FeatureFlags.nightReelBlockStartMinutes = 22 * 60;
            FeatureFlags.nightReelBlockEndMinutes = 7 * 60;
        }
        FeatureFlags.nightReelBlockAllowDM = prefs.getBoolean("nightReelBlockAllowDM", true);

        // Clean Feed
        FeatureFlags.hideSuggestionsInFeed = prefs.getBoolean("hideSuggestionsInFeed", false);
        FeatureFlags.hideThreadsSuggestions = prefs.getBoolean("hideThreadsSuggestions", false);
        FeatureFlags.hideLikedPosts = prefs.getBoolean("hideLikedPosts", false);
        FeatureFlags.confirmFeedRefresh = prefs.getBoolean("confirmFeedRefresh", false);
        FeatureFlags.hideCommunityNotes = prefs.getBoolean("hideCommunityNotes", false);
        FeatureFlags.followingOnlyFeed = prefs.getBoolean("followingOnlyFeed", false);
        FeatureFlags.hidePlusUpsells = prefs.getBoolean("hidePlusUpsells", false);
        FeatureFlags.expandCaptions = prefs.getBoolean("expandCaptions", false);
        FeatureFlags.offerAudioDownload = prefs.getBoolean("offerAudioDownload", false);
        FeatureFlags.enableVoiceDownload = prefs.getBoolean("enableVoiceDownload", false);
        FeatureFlags.enableStickerDownload = prefs.getBoolean("enableStickerDownload", false);
        FeatureFlags.enableDmMediaDownload = prefs.getBoolean("enableDmMediaDownload", false);
        FeatureFlags.offerCopyImage = prefs.getBoolean("offerCopyImage", false);
        FeatureFlags.offerMediaInfo = prefs.getBoolean("offerMediaInfo", false);
        FeatureFlags.offerPlaybackSpeed = prefs.getBoolean("offerPlaybackSpeed", false);
        FeatureFlags.maxUploadQuality = prefs.getBoolean("maxUploadQuality", false);
        FeatureFlags.forceReelScrubber = prefs.getBoolean("forceReelScrubber", false);
        FeatureFlags.holdLeftSlowMotion = prefs.getBoolean("holdLeftSlowMotion", false);
        FeatureFlags.keepEditHistory = prefs.getBoolean("keepEditHistory", false);
        FeatureFlags.jumpToFirstMessage = prefs.getBoolean("jumpToFirstMessage", false);
        FeatureFlags.hideChatMenuButton = prefs.getBoolean("hideChatMenuButton", false);
        FeatureFlags.translateMessages = prefs.getBoolean("translateMessages", false);
        FeatureFlags.bulkUnsend = prefs.getBoolean("bulkUnsend", false);
        FeatureFlags.chatExport = prefs.getBoolean("chatExport", false);
        FeatureFlags.hideTabHome = prefs.getBoolean("hideTabHome", false);
        FeatureFlags.hideTabReels = prefs.getBoolean("hideTabReels", false);
        FeatureFlags.hideTabCreate = prefs.getBoolean("hideTabCreate", false);
        FeatureFlags.hideTabMessages = prefs.getBoolean("hideTabMessages", false);
        FeatureFlags.hideTabActivity = prefs.getBoolean("hideTabActivity", false);
        FeatureFlags.hideTabProfile = prefs.getBoolean("hideTabProfile", false);
        FeatureFlags.backLeavesHome = prefs.getBoolean("backLeavesHome", false);
        FeatureFlags.hideNotesTray = prefs.getBoolean("hideNotesTray", false);
        FeatureFlags.hideDmSuggestions = prefs.getBoolean("hideDmSuggestions", false);
        FeatureFlags.hideInstants = prefs.getBoolean("hideInstants", false);
        FeatureFlags.hideDmBlend = prefs.getBoolean("hideDmBlend", false);
        FeatureFlags.readReceiptChats = prefs.getBoolean("readReceiptChats", false);
        FeatureFlags.hideThreadsButton = prefs.getBoolean("hideThreadsButton", false);

        // Ads
        FeatureFlags.isAdBlockEnabled = prefs.getBoolean("isAdBlockEnabled", false);
        FeatureFlags.isAnalyticsBlocked = prefs.getBoolean("isAnalyticsBlocked", false);
        FeatureFlags.disableTrackingLinks = prefs.getBoolean("disableTrackingLinks", false);
        FeatureFlags.openLinksExternally = prefs.getBoolean("openLinksExternally", false);

        // Misc
        FeatureFlags.isMiscEnabled = prefs.getBoolean("isMiscEnabled", false);
        FeatureFlags.disableStoryFlipping = prefs.getBoolean("disableStoryFlipping", false);
        FeatureFlags.disableVideoAutoPlay = prefs.getBoolean("disableVideoAutoPlay", false);
        FeatureFlags.spoofLastSeen = prefs.getBoolean("spoofLastSeen", false);
        FeatureFlags.disableSearchHistory = prefs.getBoolean("disableSearchHistory", false);
        FeatureFlags.confirmCalls = prefs.getBoolean("confirmCalls", false);
        FeatureFlags.spoofLocation = prefs.getBoolean("spoofLocation", false);
        FeatureFlags.spoofLat = readDoublePref(prefs, "spoofLat", 0.0);
        FeatureFlags.spoofLng = readDoublePref(prefs, "spoofLng", 0.0);
        FeatureFlags.spoofLabel = prefs.getString("spoofLabel", "");
        FeatureFlags.spoofRecent = prefs.getString("spoofRecent", "");
        FeatureFlags.forceReelQuality = prefs.getInt("forceReelQuality", 0);
        FeatureFlags.disableRepost = prefs.getBoolean("disableRepost", false);
        FeatureFlags.hideShareCreateGroup = prefs.getBoolean("hideShareCreateGroup", false);
        FeatureFlags.exactTimestamps = prefs.getBoolean("exactTimestamps", false);
        FeatureFlags.likeUndo = prefs.getBoolean("likeUndo", false);
        FeatureFlags.showFollowStatusLabel = prefs.getBoolean("showFollowStatusLabel", false);
        FeatureFlags.showFollowingFollowStatus = prefs.getBoolean("showFollowingFollowStatus", false);
        FeatureFlags.unlockPlusBenefits = prefs.getBoolean("unlockPlusBenefits", false);
        FeatureFlags.confirmLikes = prefs.getBoolean("confirmLikes", false);
        FeatureFlags.confirmFollows = prefs.getBoolean("confirmFollows", false);
        FeatureFlags.profileMenuTools = prefs.getBoolean("profileMenuTools", false);
        FeatureFlags.postCountSpoof = prefs.getBoolean("postCountSpoof", false);
        FeatureFlags.profileSpoofer = prefs.getBoolean("profileSpoofer", false);
        FeatureFlags.spoofFollowers = prefs.getInt("spoofFollowers", -1);
        FeatureFlags.spoofFollowing = prefs.getInt("spoofFollowing", -1);
        FeatureFlags.spoofPosts = prefs.getInt("spoofPosts", -1);
        FeatureFlags.spoofVerified = prefs.getBoolean("spoofVerified", false);
        FeatureFlags.spoofName = prefs.getString("spoofName", "");
        FeatureFlags.spoofUsername = prefs.getString("spoofUsername", "");
        FeatureFlags.spoofBio = prefs.getString("spoofBio", "");
        FeatureFlags.spoofAvatar = prefs.getString("spoofAvatar", "");
        FeatureFlags.profileAnalyzer = prefs.getBoolean("profileAnalyzer", false);
        FeatureFlags.fancyBioFont = prefs.getBoolean("fancyBioFont", false);
        FeatureFlags.savedOnProfile = prefs.getBoolean("savedOnProfile", false);
        FeatureFlags.lockSavedCollections = prefs.getBoolean("lockSavedCollections", false);
        FeatureFlags.lockedCollections = prefs.getString("lockedCollections", "");
        FeatureFlags.storyViewerSearch = prefs.getBoolean("storyViewerSearch", false);
        FeatureFlags.storyLinks = prefs.getBoolean("storyLinks", false);
        FeatureFlags.storyInfo = prefs.getBoolean("storyInfo", false);
        FeatureFlags.commentSearch = prefs.getBoolean("commentSearch", false);
        FeatureFlags.followListExport = prefs.getBoolean("followListExport", false);
        FeatureFlags.followersHistory = prefs.getBoolean("followersHistory", false);
        FeatureFlags.showFollowerToast = prefs.getBoolean("showFollowerToast", false);
        FeatureFlags.showFeatureToasts = prefs.getBoolean("showFeatureToasts", false);
        FeatureFlags.enableStoryMentions = prefs.getBoolean("enableStoryMentions", false);
        FeatureFlags.disableDiscoverPeople = prefs.getBoolean("disableDiscoverPeople", false);
        FeatureFlags.removeBuildExpiredPopup = prefs.getBoolean("removeBuildExpiredPopup", false);
        FeatureFlags.enableCopyComment = prefs.getBoolean("enableCopyComment", false);
        FeatureFlags.enableCaptionCopy = prefs.getBoolean("enableCaptionCopy", false);
        FeatureFlags.disableDoubleTapLike = prefs.getBoolean("disableDoubleTapLike", false);
        FeatureFlags.enablePhotoZoom = prefs.getBoolean("enablePhotoZoom", false);
        FeatureFlags.enablePostDownload = prefs.getBoolean("enablePostDownload", false);
        FeatureFlags.enableStoryDownload = prefs.getBoolean("enableStoryDownload", false);
        FeatureFlags.enableReelDownload = prefs.getBoolean("enableReelDownload", false);
        FeatureFlags.enableProfileDownload = prefs.getBoolean("enableProfileDownload", false);
        FeatureFlags.downloaderUsernameFolder = prefs.getBoolean("downloaderUsernameFolder", false);
        FeatureFlags.downloaderAddTimestamp   = prefs.getBoolean("downloaderAddTimestamp", false);
        FeatureFlags.copyMediaLink            = prefs.getBoolean("copyMediaLink", false);
        FeatureFlags.saveInstants             = prefs.getBoolean("saveInstants", false);
        FeatureFlags.uploadInstants           = prefs.getBoolean("uploadInstants", false);
        FeatureFlags.cacheStories             = prefs.getBoolean("cacheStories", false);
        // Tolerates a wrong-typed value: a bad companion write must not break every later load.
        FeatureFlags.storyCacheHours          = StoryCacheRetention.fromStored(prefs.getAll().get("storyCacheHours"));
        FeatureFlags.customFontEnabled        = prefs.getBoolean("customFontEnabled", false);
        FeatureFlags.customFontPath           = prefs.getString("customFontPath", "");
        FeatureFlags.navTabOrder              = prefs.getString("navTabOrder", "");
        FeatureFlags.startupTab               = prefs.getString("startupTab", "");
        FeatureFlags.customShareDomain        = prefs.getString("customShareDomain", "");
        FeatureFlags.exactTimestampFormat     = prefs.getString("exactTimestampFormat", "");
        FeatureFlags.spoofDevice              = prefs.getString("spoofDevice", "");
        FeatureFlags.customEmojiEnabled       = prefs.getBoolean("customEmojiEnabled", false);
        FeatureFlags.customEmojiPath          = prefs.getString("customEmojiPath", "");
        FeatureFlags.downloaderCustomPath     = prefs.getString("downloaderCustomPath", "");
        FeatureFlags.downloaderCustomUri      = prefs.getString("downloaderCustomUri",  "");

        // Custom Theme
        FeatureFlags.customThemeEnabled = prefs.getBoolean("customThemeEnabled", false);
        FeatureFlags.themePresetId = prefs.getInt("themePresetId", 1);
        // #251: "theme in dark mode only" is the default so a custom theme never breaks light mode
        // (a dark palette's surfaces/text are illegible on IG's light chrome). The old default was 0
        // ("same theme as dark mode"), which rendered light mode unreadable; migrate it to -1 once
        // so existing users are fixed, without overriding anyone who later re-selects it on purpose.
        FeatureFlags.themeLightPresetId = prefs.getInt("themeLightPresetId", -1);
        if (!prefs.getBoolean("themeLightMigratedV1", false)) {
            if (FeatureFlags.themeLightPresetId == 0) FeatureFlags.themeLightPresetId = -1;
            prefs.edit()
                    .putInt("themeLightPresetId", FeatureFlags.themeLightPresetId)
                    .putBoolean("themeLightMigratedV1", true)
                    .apply();
        }
        FeatureFlags.themePaletteJson = prefs.getString("themePaletteJson", "");

        for (String flag : ExtrasFeatures.flags()) ExtrasFeatures.set(flag, prefs.getBoolean(flag, false));

        FeatureManager.refreshFeatureStatus();
    }

    private static double readDoublePref(SharedPreferences p, String key, double fallback) {
        try {
            return Double.parseDouble(p.getString(key, String.valueOf(fallback)));
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    public static int readReelQuality(SharedPreferences preferences) {
        Object stored = preferences.getAll().get("forceReelQuality");
        if (stored instanceof Integer) return (Integer) stored;
        // Older settings stored a toggle; migrate before loading the remaining flags.
        int quality = Boolean.TRUE.equals(stored) ? 1080 : 0;
        if (stored != null) preferences.edit().putInt("forceReelQuality", quality).apply();
        return quality;
    }

    /** The local build's "hideCreateGroupButton" became upstream's "hideShareCreateGroup" (same sheet row). */
    private static void migrateCreateGroupKey(SharedPreferences p) {
        if (!p.contains("hideCreateGroupButton")) return;
        SharedPreferences.Editor e = p.edit();
        if (!p.contains("hideShareCreateGroup")) {
            e.putBoolean("hideShareCreateGroup", p.getBoolean("hideCreateGroupButton", false));
        }
        e.remove("hideCreateGroupButton").apply();
    }

    /**
     * "Block location permission prompts" was folded into Extras "Hide onboarding prompts", which
     * skips the same "Set up on new device" location page. Runs before the Extras flags load.
     * getAll() + Boolean.TRUE.equals: a wrong-typed old value must not throw on every start.
     */
    private static void migrateLocationPromptKey(SharedPreferences p) {
        Object old = p.getAll().get("blockLocationPrompts");
        if (old == null) return;
        SharedPreferences.Editor e = p.edit();
        if (Boolean.TRUE.equals(old)) e.putBoolean("hideOnboardingPrompts", true);
        e.remove("blockLocationPrompts").apply();
    }

    /**
     * Gate for every write that does not come from this process's own menus (companion broadcast,
     * {@link CompanionSync#pull}). A value of the wrong type would make every later getX() throw
     * and disable the module; Extreme Mode must not be loosened from outside its locked menus, nor
     * a passcode lock turned off without the passcode.
     */
    public static boolean acceptsCompanionWrite(SharedPreferences preferences, String key, Object value) {
        if (key == null) return false;
        // The PIN hash/salt and the locked-chat list are never the companion's to set.
        if (PrefsMerge.isPrivate(key)) {
            ModuleLog.line("(InstaEclipse) Sync: refused private key " + key);
            return false;
        }
        java.util.Map<String, ?> all = preferences.getAll();
        Object existing = all.get(key);
        // A null String value removes the key, which can't leave a wrong type behind.
        if (existing != null && value != null && !existing.getClass().isInstance(value)) {
            ModuleLog.line("(InstaEclipse) Sync: refused " + value.getClass().getSimpleName() + " for " + key);
            return false;
        }
        if (isLockedByExtreme(key, value)) {
            ModuleLog.line("(InstaEclipse) Sync: refused " + key + " while Extreme Mode is on");
            return false;
        }
        if (isLockedByPasscode(key, value, all.get("lockDirectPasscode"))) {
            ModuleLog.line("(InstaEclipse) Sync: refused " + key + " while a lock passcode is set");
            return false;
        }
        return true;
    }

    /** True for a lock switch the companion app may turn on but never off (see {@link #isLockedByPasscode}). */
    public static boolean isPasscodeLock(String key) {
        return key != null && PASSCODE_LOCKS.contains(key);
    }

    /**
     * True when a passcode is set ({@code passcode}: the stored "lockDirectPasscode" value) and
     * {@code value} would turn one of its locks off or change the locked collections, which
     * Instagram's own menus do only after asking for the passcode.
     */
    public static boolean isLockedByPasscode(String key, Object value, Object passcode) {
        if (key == null || !(passcode instanceof String) || ((String) passcode).isEmpty()) return false;
        if ("lockedCollections".equals(key)) return true;
        return PASSCODE_LOCKS.contains(key) && !Boolean.TRUE.equals(value);
    }

    /** True when Extreme Mode is on and {@code value} would loosen one of its locked settings. */
    public static boolean isLockedByExtreme(String key, Object value) {
        if (!FeatureFlags.isExtremeMode || key == null) return false;
        if (EXTREME_FROZEN.contains(key)) return true;
        return EXTREME_KEEP_ON.contains(key) && !Boolean.TRUE.equals(value);
    }
}
