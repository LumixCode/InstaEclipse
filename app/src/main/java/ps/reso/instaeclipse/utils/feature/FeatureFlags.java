package ps.reso.instaeclipse.utils.feature;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class FeatureFlags {
    public static volatile boolean downloaderUseOriginal = true;
    public static volatile int downloaderQualityMaxShortSide = 0;
    public static volatile int feedCornerRadius = 0;
    public static volatile int storyTrayScale = 100;
    public static volatile String storyRingColor = "";
    public static volatile boolean downloaderCompatibility = false;
    public static volatile boolean downloaderAllowFallback = false;

    public static volatile boolean isDevEnabled = false;

    public static volatile boolean isGhostSeen = false;
    public static volatile boolean isGhostTyping = false;
    public static volatile boolean isGhostScreenshot = false;
    public static volatile boolean isGhostViewOnce = false;
    public static volatile boolean isGhostStory = false;
    public static volatile boolean manualStorySeen = false;
    public static volatile boolean autoStorySeen = false;
    public static volatile boolean isGhostLive = false;
    public static volatile boolean allowScreenshots = false;
    public static volatile boolean keepEphemeralMessages = false;
    public static volatile boolean permanentViewMode = false;
    public static volatile boolean keepUnsentMessages = false;
    public static volatile boolean keepUnsentInChat = true;
    public static volatile boolean showUnsentIndicator = false;
    public static volatile boolean showUnsentButton = true;

    public static volatile boolean autoClearCache = false;
    public static volatile int autoClearCacheSizeMb = 100;

    public static volatile boolean removeMetaAI = false;

    public static volatile boolean lockDirectMessages = false;
    public static volatile boolean lockSpecificMessages = false;
    public static volatile String lockedDirectThreads = "{}";
    public static volatile String lockDirectPasscode = "";
    public static volatile String lockDirectSalt = "";
    public static volatile boolean lockDirectAlways = false;
    public static volatile boolean lockWholeApp = false;
    public static volatile boolean lockSavedCollections = false;
    public static volatile String lockedCollections = "";
    public static volatile boolean lockUseFingerprint = true;
    public static volatile boolean hideTabHome = false;
    public static volatile boolean hideTabReels = false;
    public static volatile boolean hideTabCreate = false;
    public static volatile boolean hideTabMessages = false;
    public static volatile boolean hideTabActivity = false;
    public static volatile boolean hideTabProfile = false;
    public static volatile boolean backLeavesHome = false;
    public static volatile boolean readReceiptChats = false;
    public static volatile boolean hideDmBlend = false;
    public static volatile boolean hideThreadsButton = false;
    public static volatile boolean hideNotesTray = false;
    public static volatile boolean hideInstants = false;
    public static volatile boolean hideDmSuggestions = false;
    public static volatile boolean chatExport = false;
    public static volatile boolean jumpToFirstMessage = false;
    public static volatile boolean hideChatMenuButton = false;
    public static volatile boolean translateMessages = false;
    public static volatile boolean bulkUnsend = false;
    public static volatile boolean keepEditHistory = false;
    public static volatile boolean offerMediaInfo = false;
    public static volatile boolean offerPlaybackSpeed = false;
    public static volatile boolean forceReelScrubber = false;
    public static volatile boolean holdLeftSlowMotion = false;
    public static volatile boolean offerCopyImage = false;
    public static volatile boolean enableVoiceDownload = false;
    public static volatile boolean enableStickerDownload = false;
    public static volatile boolean enableDmMediaDownload = false;
    public static volatile boolean offerAudioDownload = false;
    public static volatile boolean maxUploadQuality = false;
    public static volatile boolean expandCaptions = false;
    public static volatile boolean hidePlusUpsells = false;
    public static volatile boolean hideCommunityNotes = false;
    public static volatile boolean hideStoriesTray = false;
    public static volatile boolean fixNotificationRegistration = false;
    public static volatile boolean hideOnboardingPrompts = false;
    public static volatile boolean highResolutionImages = false;
    public static volatile boolean airplaneMode = false;
    public static volatile boolean unlimitedAccounts = false;
    public static volatile boolean reelsLockScroll = false;
    public static volatile boolean reelsAutoScroll = false;
    public static volatile boolean disableSwipeToCamera = false;
    public static volatile boolean followingOnlyFeed = false;
    public static volatile boolean confirmFeedRefresh = false;
    public static volatile boolean hideLikedPosts = false;
    public static volatile boolean hideSpecificChats = false;
    public static volatile boolean showHideChatButton = true;

    public static volatile boolean quickToggleSeen = false;
    public static volatile boolean quickToggleTyping = false;
    public static volatile boolean quickToggleScreenshot = false;
    public static volatile boolean quickToggleViewOnce = false;
    public static volatile boolean quickToggleStory = false;
    public static volatile boolean quickToggleLive = false;
    public static volatile boolean quickToggleEphemeral = false;
    public static volatile boolean quickTogglePermanentView = false;
    public static volatile boolean quickToggleAllowScreenshots = false;


    public static volatile boolean isExtremeMode = false;
    public static volatile boolean disableStories = false;
    public static volatile boolean disableFeed = false;
    public static volatile boolean disableReels = false;
    public static volatile boolean disableReelsExceptDM = false;
    public static volatile boolean disableExplore = false;
    public static volatile boolean disableComments = false;
    public static volatile boolean dailyReelLimitEnabled = false;
    public static volatile boolean dailyReelLimitAllowDM = true;
    public static volatile int dailyReelLimit = 100;
    public static volatile boolean nightReelBlockEnabled = false;
    public static volatile int nightReelBlockStartMinutes = 22 * 60;
    public static volatile int nightReelBlockEndMinutes = 7 * 60;
    public static volatile boolean nightReelBlockAllowDM = true;

    public static volatile boolean isAdBlockEnabled = false;
    public static volatile boolean isAnalyticsBlocked = false;
    public static volatile boolean disableTrackingLinks = false;
    public static volatile boolean openLinksExternally = false;

    public static volatile boolean disableStoryFlipping = false;
    public static volatile boolean disableVideoAutoPlay = false;
    public static volatile boolean spoofLastSeen = false;
    public static volatile boolean disableSearchHistory = false;
    public static volatile boolean confirmCalls = false;
    public static volatile boolean showFollowerToast = false;
    public static volatile boolean showFeatureToasts = false;
    public static volatile boolean disableRepost = false;
    public static volatile boolean followListExport = false;
    public static volatile boolean commentSearch = false;
    public static volatile boolean storyInfo = false;
    public static volatile boolean storyLinks = false;
    public static volatile boolean storyViewerSearch = false;
    public static volatile boolean savedOnProfile = false;
    public static volatile boolean showFollowStatusLabel = false;
    public static volatile boolean showFollowingFollowStatus = false;
    public static volatile boolean unlockPlusBenefits = false;
    public static volatile boolean fancyBioFont = false;
    public static volatile boolean likeUndo = false;
    public static volatile boolean confirmLikes = false;
    public static volatile boolean confirmFollows = false;
    public static volatile boolean postCountSpoof = false;
    public static volatile boolean profileMenuTools = false;
    public static volatile boolean profileSpoofer = false;
    public static volatile int     spoofFollowers = -1;
    public static volatile int     spoofFollowing = -1;
    public static volatile int     spoofPosts = -1;
    public static volatile boolean spoofVerified = false;
    public static volatile String  spoofName = "";
    public static volatile String  spoofUsername = "";
    public static volatile String  spoofBio = "";
    public static volatile String  spoofAvatar = "";
    public static volatile boolean profileAnalyzer = false;
    public static volatile boolean exactTimestamps = false;
    public static volatile String  exactTimestampFormat = "";
    public static volatile String  spoofDevice = "";
    public static volatile boolean hideShareCreateGroup = false;


    public static volatile boolean enableStoryMentions = false;
    public static volatile boolean disableDiscoverPeople = false;
    public static volatile boolean removeBuildExpiredPopup = false;
    public static volatile boolean enableCopyComment = false;
    public static volatile boolean enableCaptionCopy = false;
    public static volatile boolean disableDoubleTapLike = false;
    public static volatile boolean enablePhotoZoom = false;

    public static volatile boolean spoofLocation = false;
    public static volatile double spoofLat = 0.0;
    public static volatile double spoofLng = 0.0;
    public static volatile String spoofLabel = "";
    public static volatile String spoofRecent = "";

    public static volatile int forceReelQuality = 0;

    public static volatile boolean customThemeEnabled = false;
    public static volatile int themePresetId = 1;
    public static volatile int themeLightPresetId = -1;
    public static volatile String themePaletteJson = "";

    public static volatile boolean hideSuggestionsInFeed = false;
    public static volatile boolean hideThreadsSuggestions = false;

    public static volatile boolean enablePostDownload = false;
    public static volatile boolean enableStoryDownload = false;
    public static volatile boolean enableReelDownload = false;
    public static volatile boolean enableProfileDownload = false;
    public static volatile boolean downloaderUsernameFolder = false;
    public static volatile boolean downloaderAddTimestamp = false;
    public static volatile boolean copyMediaLink = false;
    public static volatile boolean saveInstants = false;
    public static volatile boolean uploadInstants = false;
    public static volatile boolean cacheStories = false;
    public static volatile int storyCacheHours = 24;
    public static volatile boolean customFontEnabled = false;
    public static volatile String  customShareDomain = "";
    public static volatile String  startupTab = "";
    public static volatile String  navTabOrder = "";
    public static volatile String  customFontPath = "";
    public static volatile boolean customEmojiEnabled = false;
    public static volatile String  customEmojiPath = "";
    public static volatile String  downloaderCustomPath = "";
    public static volatile String  downloaderCustomUri  = "";

    public static final Map<String, Object> DEFAULTS = defaults();

    private static Map<String, Object> defaults() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Field field : FeatureFlags.class.getDeclaredFields()) {
            int m = field.getModifiers();
            if (!Modifier.isStatic(m) || Modifier.isFinal(m) || field.isSynthetic()) continue;
            try {
                Object value = field.get(null);
                if (field.getType() == double.class) out.put(field.getName(), String.valueOf(value));
                else if (value instanceof Boolean || value instanceof Integer || value instanceof String) out.put(field.getName(), value);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        return Collections.unmodifiableMap(out);
    }
}
