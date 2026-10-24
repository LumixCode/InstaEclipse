package ps.reso.instaeclipse.Xposed;

import android.content.Context;

import org.luckypray.dexkit.DexKitBridge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ps.reso.instaeclipse.mods.ads.AdBlocker;
import ps.reso.instaeclipse.mods.ads.TrackingLinkDisable;
import ps.reso.instaeclipse.mods.core.ExecGuardDispatchers;
import ps.reso.instaeclipse.mods.core.TerminatedExecutorGuard;
import ps.reso.instaeclipse.mods.devops.BuildExpiredPopupHook;
import ps.reso.instaeclipse.mods.devops.DevOptionsUnlockHook;
import ps.reso.instaeclipse.mods.extras.AccountLimitHook;
import ps.reso.instaeclipse.mods.extras.AirplaneModeHook;
import ps.reso.instaeclipse.mods.extras.HighResolutionImageHook;
import ps.reso.instaeclipse.mods.extras.NotificationRegistrationHook;
import ps.reso.instaeclipse.mods.extras.OnboardingPromptHook;
import ps.reso.instaeclipse.mods.extras.ReelsControlsHook;
import ps.reso.instaeclipse.mods.extras.SwipeToCameraHook;
import ps.reso.instaeclipse.mods.feed.ExpandCaptionsHook;
import ps.reso.instaeclipse.mods.feed.FeedPhotoZoomHook;
import ps.reso.instaeclipse.mods.feed.FeedRefreshConfirmHook;
import ps.reso.instaeclipse.mods.feed.FollowingOnlyFeedHook;
import ps.reso.instaeclipse.mods.feed.HideCommunityNotesHook;
import ps.reso.instaeclipse.mods.feed.HideSuggestedFeedItemsHook;
import ps.reso.instaeclipse.mods.feed.PostCountSpoof;
import ps.reso.instaeclipse.mods.feed.RoundedFeedPostsHook;
import ps.reso.instaeclipse.mods.ghost.CallConfirmHook;
import ps.reso.instaeclipse.mods.ghost.ChatExportHook;
import ps.reso.instaeclipse.mods.ghost.EditHistoryHook;
import ps.reso.instaeclipse.mods.ghost.GhostChannelMarkAsReadHook;
import ps.reso.instaeclipse.mods.ghost.GhostDMMarkAsReadHook;
import ps.reso.instaeclipse.mods.ghost.GhostDMSeenHook;
import ps.reso.instaeclipse.mods.ghost.GhostEphemeralKeepHook;
import ps.reso.instaeclipse.mods.ghost.GhostPermanentViewHook;
import ps.reso.instaeclipse.mods.ghost.GhostScreenshotDetectionHook;
import ps.reso.instaeclipse.mods.ghost.GhostStorySeenHook;
import ps.reso.instaeclipse.mods.ghost.GhostTypingIndicatorHook;
import ps.reso.instaeclipse.mods.ghost.GhostViewOnceHook;
import ps.reso.instaeclipse.mods.ghost.HideChatsHook;
import ps.reso.instaeclipse.mods.ghost.JumpToFirstMessageHook;
import ps.reso.instaeclipse.mods.ghost.KeepUnsentMessagesHook;
import ps.reso.instaeclipse.mods.ghost.ManualStorySeenHook;
import ps.reso.instaeclipse.mods.ghost.ReadReceiptChatHook;
import ps.reso.instaeclipse.mods.ghost.ScreenshotPermissionHook;
import ps.reso.instaeclipse.mods.ghost.SearchHistoryHook;
import ps.reso.instaeclipse.mods.ghost.UnsentThreadButtonHook;
import ps.reso.instaeclipse.mods.ghost.ViewOnceBadgeHook;
import ps.reso.instaeclipse.mods.location.LocationSpoofHook;
import ps.reso.instaeclipse.mods.media.FeedVideoDownloadHook;
import ps.reso.instaeclipse.mods.media.ForceReelQualityHook;
import ps.reso.instaeclipse.mods.media.InstantSaveHook;
import ps.reso.instaeclipse.mods.media.InstantUploadHook;
import ps.reso.instaeclipse.mods.media.MaxUploadQualityHook;
import ps.reso.instaeclipse.mods.media.PlaybackSpeedHook;
import ps.reso.instaeclipse.mods.media.PostDownloadContextMenuHook;
import ps.reso.instaeclipse.mods.media.ProfilePicDownloadHook;
import ps.reso.instaeclipse.mods.media.ReelDownloadHook;
import ps.reso.instaeclipse.mods.media.ShareLinkDownloadHook;
import ps.reso.instaeclipse.mods.media.StoryDownloadHook;
import ps.reso.instaeclipse.mods.media.VoiceMessageDownloadHook;
import ps.reso.instaeclipse.mods.misc.CaptionCopyHook;
import ps.reso.instaeclipse.mods.misc.CommentCopyHook;
import ps.reso.instaeclipse.mods.misc.CommentSearchHook;
import ps.reso.instaeclipse.mods.misc.DisableDoubleTapLikeHook;
import ps.reso.instaeclipse.mods.misc.DisableStoryFlippingHook;
import ps.reso.instaeclipse.mods.misc.DisableVideoAutoPlayHook;
import ps.reso.instaeclipse.mods.misc.DoubleTapExtrasHook;
import ps.reso.instaeclipse.mods.misc.ExactTimestampHook;
import ps.reso.instaeclipse.mods.misc.ExternalBrowserHook;
import ps.reso.instaeclipse.mods.misc.FollowConfirmHook;
import ps.reso.instaeclipse.mods.misc.FollowListExportHook;
import ps.reso.instaeclipse.mods.misc.FollowStatusHook;
import ps.reso.instaeclipse.mods.misc.FollowingFollowStatusHook;
import ps.reso.instaeclipse.mods.misc.LikeUndoHook;
import ps.reso.instaeclipse.mods.misc.OpenLinksExternallyHook;
import ps.reso.instaeclipse.mods.misc.ProfileCountDeltaHook;
import ps.reso.instaeclipse.mods.misc.ProfileFollowStatusHook;
import ps.reso.instaeclipse.mods.misc.StoryInfoHook;
import ps.reso.instaeclipse.mods.misc.StoryLikeUndoHook;
import ps.reso.instaeclipse.mods.misc.StoryLinksHook;
import ps.reso.instaeclipse.mods.misc.StoryMentionHook;
import ps.reso.instaeclipse.mods.misc.UnlockPlusBenefitsHook;
import ps.reso.instaeclipse.mods.network.IGNetworkInterceptor;
import ps.reso.instaeclipse.mods.network.NetworkChainEntry;
import ps.reso.instaeclipse.mods.reels.ReelViewerHook;
import ps.reso.instaeclipse.mods.ui.BackLeavesHomeDispatcher;
import ps.reso.instaeclipse.mods.ui.BackLeavesHomeHook;
import ps.reso.instaeclipse.mods.ui.CustomFontHook;
import ps.reso.instaeclipse.mods.ui.DisableRepostHook;
import ps.reso.instaeclipse.mods.ui.HideHeaderButtonsHook;
import ps.reso.instaeclipse.mods.ui.HideNotesTrayHook;
import ps.reso.instaeclipse.mods.ui.InboxDiscoverPeopleHook;
import ps.reso.instaeclipse.mods.ui.LockedCollectionsHook;
import ps.reso.instaeclipse.mods.ui.ProfileFollowsYouHook;
import ps.reso.instaeclipse.mods.ui.ProfileMenuHook;
import ps.reso.instaeclipse.mods.ui.ProfileSpoofHook;
import ps.reso.instaeclipse.mods.ui.ProfileTextCopyHook;
import ps.reso.instaeclipse.mods.ui.ReelMenuLabels;
import ps.reso.instaeclipse.mods.ui.RemoveMetaAIHook;
import ps.reso.instaeclipse.mods.ui.SavedOnProfileHook;
import ps.reso.instaeclipse.mods.ui.ShareSheetCleanupHook;
import ps.reso.instaeclipse.mods.ui.StartupTabHook;
import ps.reso.instaeclipse.mods.ui.StoryTrayHook;
import ps.reso.instaeclipse.mods.ui.StoryViewerSearchHook;
import ps.reso.instaeclipse.mods.ui.UIHookManager;
import ps.reso.instaeclipse.mods.ui.theme.IgThemeHook;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.xposedcompat.XC_LoadPackage;

public class InstallPlan {

    public static final String COULD_NOT_START = "Could not start, so this feature will not work";
    static final String MAIN_SCREEN = "Could not attach to Instagram's main screen; the InstaEclipse menu shortcut, ghost mode icon and feature summary may not show";
    static final String STORY_LIKES = "Could not start for story likes, so undo will not work there";
    static final String COMMENTS_AND_CHATS = "Could not start for comments and chats, so double tap still likes there";
    static final String CACHED_REQUESTS = "Requests Instagram answers from its own cache may not be filtered; all other requests still are";
    static final String BACK_DISPATCHER = "Back on the home feed may not leave Instagram on Android 16 and newer";
    static final String STICKER_SENDER = "Could not start, so stickers and GIFs saved from chats may be named after the chat instead of their sender";

    static final int INSTAGRAM_UI = 1;
    static final int REEL_VIEWER_HOOK = 2;
    static final int DEV_OPTIONS_UNLOCK_HOOK = 3;
    static final int GHOST_DM_SEEN_HOOK = 4;
    static final int GHOST_DM_MARK_AS_READ_HOOK = 5;
    static final int GHOST_CHANNEL_MARK_AS_READ_HOOK = 6;
    static final int GHOST_TYPING_INDICATOR_HOOK = 7;
    static final int GHOST_SCREENSHOT_DETECTION_HOOK = 8;
    static final int SCREENSHOT_PERMISSION_HOOK = 9;
    static final int GHOST_VIEW_ONCE_HOOK = 10;
    static final int GHOST_STORY_SEEN_HOOK = 11;
    static final int KEEP_UNSENT_MESSAGES_HOOK = 12;
    static final int UNSENT_THREAD_BUTTON_HOOK = 13;
    static final int HIDE_CHATS_HOOK = 14;
    static final int INBOX_DISCOVER_PEOPLE_HOOK = 15;
    static final int CUSTOM_FONT_HOOK = 16;
    static final int META_AI = 17;
    static final int META_AI_REELS = 18;
    static final int DISABLE_REPOST_HOOK = 19;
    static final int HIDE_SUGGESTED_FEED_ITEMS_HOOK = 21;
    static final int AD_BLOCKER = 22;
    static final int TRACKING_LINK_DISABLE = 23;
    static final int EXACT_TIMESTAMP_HOOK = 24;
    static final int EXPAND_CAPTIONS_HOOK = 25;
    static final int HIDE_COMMUNITY_NOTES_HOOK = 26;
    static final int REELS_CONTROLS_HOOK = 27;
    static final int ACCOUNT_LIMIT_HOOK = 28;
    static final int AIRPLANE_MODE_HOOK = 29;
    static final int HIGH_RESOLUTION_IMAGE_HOOK = 30;
    static final int ONBOARDING_PROMPT_HOOK = 31;
    static final int NOTIFICATION_REGISTRATION_HOOK = 32;
    static final int STARTUP_TAB_HOOK = 33;
    static final int BACK_LEAVES_HOME_HOOK = 34;
    static final int SWIPE_TO_CAMERA_HOOK = 35;
    static final int FOLLOWING_ONLY_FEED_HOOK = 36;
    static final int PROFILE_FOLLOW_STATUS_HOOK = 37;
    static final int PROFILE_FOLLOWS_YOU_HOOK = 38;
    static final int PROFILE_COUNT_DELTA_HOOK = 39;
    static final int FOLLOW_STATUS_HOOK = 40;
    static final int FOLLOWING_FOLLOW_STATUS_HOOK = 41;
    static final int PLAYBACK_SPEED_HOOK = 42;
    static final int HIDE_NOTES_TRAY_HOOK = 43;
    static final int VOICE_MESSAGE_DOWNLOAD_HOOK = 44;
    static final int EDIT_HISTORY_HOOK = 45;
    static final int JUMP_TO_FIRST_MESSAGE_HOOK = 46;
    static final int READ_RECEIPT_CHAT_HOOK = 47;
    static final int STORY_VIEWER_SEARCH_HOOK = 48;
    static final int UNLOCK_PLUS_BENEFITS_HOOK = 49;
    static final int COMMENT_SEARCH_HOOK = 50;
    static final int CHAT_EXPORT_HOOK = 51;
    static final int FOLLOW_LIST_EXPORT_HOOK = 52;
    static final int LIKE_UNDO_HOOK = 53;
    static final int STORY_LIKE_UNDO_HOOK = 54;
    static final int FOLLOW_CONFIRM_HOOK = 55;
    static final int PROFILE_MENU_HOOK = 56;
    static final int POST_COUNT_SPOOF = 57;
    static final int PROFILE_TEXT_COPY_HOOK = 58;
    static final int PROFILE_SPOOF_HOOK = 59;
    static final int SHARE_SHEET_CLEANUP_HOOK = 60;
    static final int EXTERNAL_BROWSER_HOOK = 61;
    static final int OPEN_LINKS_EXTERNALLY_HOOK = 62;
    static final int DISABLE_STORY_FLIPPING_HOOK = 63;
    static final int STORY_MENTION_HOOK = 64;
    static final int STORY_LINKS_HOOK = 65;
    static final int STORY_INFO_HOOK = 66;
    static final int MANUAL_STORY_SEEN_HOOK = 67;
    static final int COMMENT_COPY_HOOK = 68;
    static final int CAPTION_COPY_HOOK = 69;
    static final int DISABLE_DOUBLE_TAP_LIKE_HOOK = 70;
    static final int DOUBLE_TAP_EXTRAS_HOOK = 71;
    static final int FEED_PHOTO_ZOOM_HOOK = 72;
    static final int LOCATION_SPOOF_HOOK = 73;
    static final int IG_THEME_HOOK = 74;
    static final int FORCE_REEL_QUALITY_HOOK = 75;
    static final int MAX_UPLOAD_QUALITY_HOOK = 76;
    static final int DISABLE_VIDEO_AUTO_PLAY_HOOK = 77;
    static final int BUILD_EXPIRED_POPUP_HOOK = 78;
    static final int FEED_VIDEO_DOWNLOAD_HOOK = 79;
    static final int FEED_VIDEO_DOWNLOAD_CAPTURE = 80;
    static final int POST_DOWNLOAD_CONTEXT_MENU_HOOK = 81;
    static final int REEL_MENU_LABELS = 82;
    static final int INSTANT_SAVE_HOOK = 83;
    static final int INSTANT_UPLOAD_HOOK = 84;
    static final int GHOST_EPHEMERAL_KEEP_HOOK = 85;
    static final int GHOST_PERMANENT_VIEW_HOOK = 86;
    static final int VIEW_ONCE_BADGE_HOOK = 87;
    static final int LOCKED_COLLECTIONS_HOOK = 88;
    static final int SAVED_ON_PROFILE_HOOK = 89;
    static final int HIDE_HEADER_BUTTONS_HOOK = 90;
    static final int SHARE_LINK_DOWNLOAD_HOOK = 91;
    static final int CALL_CONFIRM_HOOK = 92;
    static final int SEARCH_HISTORY_HOOK = 93;
    static final int FEED_REFRESH_CONFIRM_HOOK = 94;
    static final int ROUNDED_FEED_POSTS_HOOK = 95;
    static final int STORY_TRAY_HOOK = 96;
    static final int STORY_DOWNLOAD_HOOK = 97;
    static final int REEL_DOWNLOAD_HOOK = 98;
    static final int PROFILE_PIC_DOWNLOAD_HOOK = 99;
    static final int INTERCEPTOR = 100;
    static final int TERMINATED_EXECUTOR_GUARD = 101;
    static final int EXEC_GUARD_DISPATCHERS = 102;
    static final int INTERCEPTOR_CHAIN_ENTRY = 103;
    static final int BACK_LEAVES_HOME_DISPATCHER = 104;
    static final int VOICE_USER_CACHE_TRACKER = 105;

    private static final List<String> EARLY = Arrays.asList(
            "interceptorChainEntry", "ExecGuardDispatchers", "BackLeavesHomeDispatcher",
            "GhostDMSeenHook", "GhostDMMarkAsReadHook", "GhostChannelMarkAsReadHook", "GhostTypingIndicatorHook",
            "GhostScreenshotDetectionHook", "GhostViewOnceHook", "GhostStorySeenHook", "KeepUnsentMessagesHook",
            "GhostEphemeralKeepHook", "GhostPermanentViewHook", "EditHistoryHook",
            "FollowingOnlyFeedHook", "HideSuggestedFeedItemsHook", "AdBlocker");
    private static final List<String> LAST = Collections.singletonList("DevOptionsUnlockHook");
    private static final List<InstallStep> LEGACY_STEPS;
    private static final List<InstallStep> STEPS;

    static {
        List<InstallStep> s = new ArrayList<>();
        s.add(new InstallStep(INSTAGRAM_UI, "instagramUI", null, "(InstaEclipse): ", MAIN_SCREEN, false, null).inPhaseA());
        s.add(new InstallStep(REEL_VIEWER_HOOK, "ReelViewerHook", null, "(InstaEclipse | ReelViewer): ", COULD_NOT_START, true, null));
        s.add(new InstallStep(DEV_OPTIONS_UNLOCK_HOOK, "DevOptionsUnlockHook", null, "(InstaEclipse | DevOptions): ", COULD_NOT_START, true, null));
        s.add(new InstallStep(GHOST_DM_SEEN_HOOK, "GhostDMSeenHook", null, "(InstaEclipse | GhostSeen): ", COULD_NOT_START, true, null));
        s.add(new InstallStep(GHOST_DM_MARK_AS_READ_HOOK, "GhostDMMarkAsReadHook", null, "(InstaEclipse | MarkAsRead): ", COULD_NOT_START, false, null));
        s.add(new InstallStep(GHOST_CHANNEL_MARK_AS_READ_HOOK, "GhostChannelMarkAsReadHook", null, "(InstaEclipse | ChannelMarkAsRead): ", COULD_NOT_START, false, null));
        s.add(new InstallStep(GHOST_TYPING_INDICATOR_HOOK, "GhostTypingIndicatorHook", null, "(InstaEclipse | GhostTyping): ", COULD_NOT_START, true, null));
        s.add(new InstallStep(GHOST_SCREENSHOT_DETECTION_HOOK, "GhostScreenshotDetectionHook", null, "(InstaEclipse | GhostScreenshot): ", COULD_NOT_START, true, null));
        s.add(new InstallStep(SCREENSHOT_PERMISSION_HOOK, "ScreenshotPermissionHook", null, "(InstaEclipse | ScreenshotPermission): ", COULD_NOT_START, false, null).inPhaseA());
        s.add(new InstallStep(GHOST_VIEW_ONCE_HOOK, "GhostViewOnceHook", null, "(InstaEclipse | GhostViewOnce): ", COULD_NOT_START, true, null));
        s.add(new InstallStep(GHOST_STORY_SEEN_HOOK, "GhostStorySeenHook", null, "(InstaEclipse | GhostStorySeen): ", COULD_NOT_START, true, null));
        s.add(new InstallStep(KEEP_UNSENT_MESSAGES_HOOK, "KeepUnsentMessagesHook", null, "(InstaEclipse | KeepUnsent): ", COULD_NOT_START, true, null));
        s.add(new InstallStep(UNSENT_THREAD_BUTTON_HOOK, "UnsentThreadButtonHook", null, "(InstaEclipse | UnsentBtn): ", COULD_NOT_START, false, null));
        s.add(new InstallStep(HIDE_CHATS_HOOK, "HideChatsHook", null, "(InstaEclipse | HideChats): ", COULD_NOT_START, true, null));
        s.add(new InstallStep(INBOX_DISCOVER_PEOPLE_HOOK, "InboxDiscoverPeopleHook", null, "(InstaEclipse | InboxDiscover): ", COULD_NOT_START, true, null));
        s.add(new InstallStep(CUSTOM_FONT_HOOK, "CustomFontHook", null, "(InstaEclipse | CustomFont): ", COULD_NOT_START, true, null)
                .withLookupFlags("customEmojiEnabled")
                .withRestartKeys("customFontEnabled", "customEmojiEnabled", "customFontPath", "customEmojiPath"));
        s.add(new InstallStep(META_AI, "metaAi", null, "(InstaEclipse | RemoveMetaAI): ", COULD_NOT_START, false, null));
        s.add(new InstallStep(META_AI_REELS, "metaAiReels", new String[]{"metaAi"}, "(InstaEclipse | RemoveMetaAI): ", COULD_NOT_START, true, null));
        s.add(new InstallStep(DISABLE_REPOST_HOOK, "DisableRepostHook", null, "(InstaEclipse | DisableRepost): ", COULD_NOT_START, true, null));
        s.add(new InstallStep(HIDE_SUGGESTED_FEED_ITEMS_HOOK, "HideSuggestedFeedItemsHook", null, "(InstaEclipse | HideSuggested): ", COULD_NOT_START, true, null));
        s.add(new InstallStep(AD_BLOCKER, "AdBlocker", null, "(InstaEclipse | AdBlocker): ", COULD_NOT_START, true, null));
        s.add(new InstallStep(TRACKING_LINK_DISABLE, "TrackingLinkDisable", null, "(InstaEclipse | TrackingLinkDisable): ", COULD_NOT_START, false, null));
        s.add(new InstallStep(EXACT_TIMESTAMP_HOOK, "ExactTimestampHook", null, "(InstaEclipse | ExactTime): ", COULD_NOT_START, true, null));
        s.add(new InstallStep(EXPAND_CAPTIONS_HOOK, "ExpandCaptionsHook", null, "(InstaEclipse | ExpandCaptions): ", COULD_NOT_START, true, "expandCaptions").withRestartKeys("expandCaptions"));
        s.add(new InstallStep(HIDE_COMMUNITY_NOTES_HOOK, "HideCommunityNotesHook", null, "(InstaEclipse | CommunityNotes): ", COULD_NOT_START, true, null));
        s.add(new InstallStep(REELS_CONTROLS_HOOK, "ReelsControlsHook", null, "(InstaEclipse | Reels): ", COULD_NOT_START, true, null));
        s.add(new InstallStep(ACCOUNT_LIMIT_HOOK, "AccountLimitHook", null, "(InstaEclipse | UnlimitedAccounts): ", COULD_NOT_START, true, null));
        s.add(new InstallStep(AIRPLANE_MODE_HOOK, "AirplaneModeHook", null, "(InstaEclipse | Airplane): ", COULD_NOT_START, false, null).inPhaseA());
        s.add(new InstallStep(HIGH_RESOLUTION_IMAGE_HOOK, "HighResolutionImageHook", null, "(InstaEclipse | HighResImages): ", COULD_NOT_START, true, null));
        s.add(new InstallStep(ONBOARDING_PROMPT_HOOK, "OnboardingPromptHook", null, "(InstaEclipse | Onboarding): ", COULD_NOT_START, true, null));
        s.add(new InstallStep(NOTIFICATION_REGISTRATION_HOOK, "NotificationRegistrationHook", null, "(InstaEclipse | NotificationFix): ", COULD_NOT_START, false, null).inPhaseA());
        s.add(new InstallStep(STARTUP_TAB_HOOK, "StartupTabHook", null, "(InstaEclipse | StartupTab): ", COULD_NOT_START, false, "startupTab").inPhaseA());
        s.add(new InstallStep(BACK_LEAVES_HOME_HOOK, "BackLeavesHomeHook", null, "(IE|BackLeavesHome): ", COULD_NOT_START, false, null).inPhaseA());
        s.add(new InstallStep(BACK_LEAVES_HOME_DISPATCHER, "BackLeavesHomeDispatcher", new String[]{"BackLeavesHomeHook"}, "(IE|BackLeavesHome): ", BACK_DISPATCHER, false, null));
        s.add(new InstallStep(SWIPE_TO_CAMERA_HOOK, "SwipeToCameraHook", null, "(InstaEclipse | SwipeCamera): ", COULD_NOT_START, false, null).inPhaseA());
        s.add(new InstallStep(FOLLOWING_ONLY_FEED_HOOK, "FollowingOnlyFeedHook", null, "(InstaEclipse | FollowingOnlyFeed): ", COULD_NOT_START, true, null));
        s.add(new InstallStep(PROFILE_FOLLOW_STATUS_HOOK, "ProfileFollowStatusHook", null, "(InstaEclipse | FollowStatusLabel): ", COULD_NOT_START, true, null));
        s.add(new InstallStep(PROFILE_FOLLOWS_YOU_HOOK, "ProfileFollowsYouHook", null, "(InstaEclipse | FollowsYou): ", COULD_NOT_START, true, null));
        s.add(new InstallStep(PROFILE_COUNT_DELTA_HOOK, "ProfileCountDeltaHook", null, "(IE|CountDelta) ", COULD_NOT_START, true, null));
        s.add(new InstallStep(FOLLOW_STATUS_HOOK, "FollowStatusHook", null, "(InstaEclipse | FollowerToast): ", COULD_NOT_START, true, null));
        s.add(new InstallStep(FOLLOWING_FOLLOW_STATUS_HOOK, "FollowingFollowStatusHook", null, "(InstaEclipse | FollowingFollowStatus): ", COULD_NOT_START, true, null));
        s.add(new InstallStep(PLAYBACK_SPEED_HOOK, "PlaybackSpeedHook", null, "(InstaEclipse | PlaybackSpeed): ", COULD_NOT_START, true, null).withRestartKeys("holdLeftSlowMotion"));
        s.add(new InstallStep(HIDE_NOTES_TRAY_HOOK, "HideNotesTrayHook", null, "(InstaEclipse | NotesTray): ", COULD_NOT_START, true, null));
        s.add(new InstallStep(VOICE_USER_CACHE_TRACKER, "VoiceUserCacheTracker", new String[]{"VoiceMessageDownloadHook"}, "(IE|VoiceDL) ", STICKER_SENDER, false, null));
        s.add(new InstallStep(VOICE_MESSAGE_DOWNLOAD_HOOK, "VoiceMessageDownloadHook", null, "(IE|VoiceDL) ", COULD_NOT_START, true, null)
                .withLookupFlags("bulkUnsend").withRestartKeys("bulkUnsend"));
        s.add(new InstallStep(EDIT_HISTORY_HOOK, "EditHistoryHook", null, "(IE|EditHistory) ", COULD_NOT_START, true, null));
        s.add(new InstallStep(JUMP_TO_FIRST_MESSAGE_HOOK, "JumpToFirstMessageHook", null, "(IE|JumpFirst) ", COULD_NOT_START, false, null));
        s.add(new InstallStep(READ_RECEIPT_CHAT_HOOK, "ReadReceiptChatHook", null, "(IE|ReadReceiptChat) ", COULD_NOT_START, false, null));
        s.add(new InstallStep(STORY_VIEWER_SEARCH_HOOK, "StoryViewerSearchHook", null, "(IE|ViewerSearch) ", COULD_NOT_START, true, null));
        s.add(new InstallStep(UNLOCK_PLUS_BENEFITS_HOOK, "UnlockPlusBenefitsHook", null, "(InstaEclipse | UnlockPlus): ", COULD_NOT_START, true, null));
        s.add(new InstallStep(COMMENT_SEARCH_HOOK, "CommentSearchHook", null, "(IE|CommentSearch) ", COULD_NOT_START, true, null));
        s.add(new InstallStep(CHAT_EXPORT_HOOK, "ChatExportHook", null, "(IE|ChatExport) ", COULD_NOT_START, true, null));
        s.add(new InstallStep(FOLLOW_LIST_EXPORT_HOOK, "FollowListExportHook", null, "(IE|FollowExport) ", COULD_NOT_START, true, null));
        s.add(new InstallStep(LIKE_UNDO_HOOK, "LikeUndoHook", null, "(InstaEclipse | LikeUndo): ", COULD_NOT_START, true, null).withRestartKeys("confirmLikes"));
        s.add(new InstallStep(STORY_LIKE_UNDO_HOOK, "StoryLikeUndoHook", null, "(InstaEclipse | LikeUndo): ", STORY_LIKES, true, null));
        s.add(new InstallStep(FOLLOW_CONFIRM_HOOK, "FollowConfirmHook", null, "(InstaEclipse | FollowConfirm): ", COULD_NOT_START, true, "confirmFollows").withRestartKeys("confirmFollows"));
        s.add(new InstallStep(PROFILE_MENU_HOOK, "ProfileMenuHook", null, "(IE|ProfileMenu) ", COULD_NOT_START, true, null));
        s.add(new InstallStep(POST_COUNT_SPOOF, "PostCountSpoof", null, "(IE|PostCounts) ", COULD_NOT_START, true, "postCountSpoof").withRestartKeys("postCountSpoof"));
        s.add(new InstallStep(PROFILE_TEXT_COPY_HOOK, "ProfileTextCopyHook", null, "(IE|ProfileCopy) ", COULD_NOT_START, true, null));
        s.add(new InstallStep(PROFILE_SPOOF_HOOK, "ProfileSpoofHook", null, "(IE|ProfileSpoof) ", COULD_NOT_START, true, "profileSpoofer|postCountSpoof")
                .withLookupFlags("profileSpoofer").withRestartKeys("profileSpoofer", "postCountSpoof"));
        s.add(new InstallStep(SHARE_SHEET_CLEANUP_HOOK, "ShareSheetCleanupHook", null, "(InstaEclipse | ShareSheet): ", COULD_NOT_START, true, null));
        s.add(new InstallStep(EXTERNAL_BROWSER_HOOK, "ExternalBrowserHook", null, "(InstaEclipse | ExternalBrowser): ", COULD_NOT_START, false, null));
        s.add(new InstallStep(OPEN_LINKS_EXTERNALLY_HOOK, "OpenLinksExternallyHook", null, "(InstaEclipse | OpenLinksExternally): ", COULD_NOT_START, true, null));
        s.add(new InstallStep(DISABLE_STORY_FLIPPING_HOOK, "DisableStoryFlippingHook", null, "(InstaEclipse | StoryFlipping): ", COULD_NOT_START, true, null));
        s.add(new InstallStep(STORY_MENTION_HOOK, "StoryMentionHook", null, "(InstaEclipse | StoryMentions): ", COULD_NOT_START, true, null));
        s.add(new InstallStep(STORY_LINKS_HOOK, "StoryLinksHook", null, "(IE|StoryLinks) ", COULD_NOT_START, true, null));
        s.add(new InstallStep(STORY_INFO_HOOK, "StoryInfoHook", null, "(IE|StoryInfo) ", COULD_NOT_START, true, null));
        s.add(new InstallStep(MANUAL_STORY_SEEN_HOOK, "ManualStorySeenHook", null, "(InstaEclipse | ManualStorySeen): ", COULD_NOT_START, true, null));
        s.add(new InstallStep(COMMENT_COPY_HOOK, "CommentCopyHook", null, "(InstaEclipse | CopyComment): ", COULD_NOT_START, false, null));
        s.add(new InstallStep(CAPTION_COPY_HOOK, "CaptionCopyHook", null, "(InstaEclipse | Caption): ", COULD_NOT_START, true, null));
        s.add(new InstallStep(DISABLE_DOUBLE_TAP_LIKE_HOOK, "DisableDoubleTapLikeHook", null, "(InstaEclipse | DoubleTapLike): ", COULD_NOT_START, true, null));
        s.add(new InstallStep(DOUBLE_TAP_EXTRAS_HOOK, "DoubleTapExtrasHook", null, "(InstaEclipse | DoubleTapLike): ", COMMENTS_AND_CHATS, true, null));
        s.add(new InstallStep(FEED_PHOTO_ZOOM_HOOK, "FeedPhotoZoomHook", null, "(InstaEclipse | PhotoZoom): ", COULD_NOT_START, false, null).inPhaseA());
        s.add(new InstallStep(LOCATION_SPOOF_HOOK, "LocationSpoofHook", null, "(InstaEclipse | SpoofLocation): ", COULD_NOT_START, false, null).inPhaseA());
        s.add(new InstallStep(IG_THEME_HOOK, "IgThemeHook", null, "(InstaEclipse | Theme): ", COULD_NOT_START, false, null).inPhaseA());
        s.add(new InstallStep(FORCE_REEL_QUALITY_HOOK, "ForceReelQualityHook", null, "(InstaEclipse | ForceReelQuality): ", COULD_NOT_START, true, null));
        s.add(new InstallStep(MAX_UPLOAD_QUALITY_HOOK, "MaxUploadQualityHook", null, "(IE|MaxUploadQuality) ", COULD_NOT_START, true, null));
        s.add(new InstallStep(DISABLE_VIDEO_AUTO_PLAY_HOOK, "DisableVideoAutoPlayHook", null, "(InstaEclipse | AutoPlayDisable): ", COULD_NOT_START, true, null));
        s.add(new InstallStep(BUILD_EXPIRED_POPUP_HOOK, "BuildExpiredPopupHook", null, "(InstaEclipse | BuildExpired): ", COULD_NOT_START, true, null));
        s.add(new InstallStep(FEED_VIDEO_DOWNLOAD_HOOK, "FeedVideoDownloadHook", null, "(InstaEclipse | MediaDownload): ", COULD_NOT_START, false, null));
        s.add(new InstallStep(FEED_VIDEO_DOWNLOAD_CAPTURE, "FeedVideoDownloadCapture", new String[]{"FeedVideoDownloadHook"}, "(InstaEclipse | MediaDownload): ", COULD_NOT_START, true, null));
        s.add(new InstallStep(POST_DOWNLOAD_CONTEXT_MENU_HOOK, "PostDownloadContextMenuHook", null, "(InstaEclipse | PostDownload): ", COULD_NOT_START, true, null));
        s.add(new InstallStep(REEL_MENU_LABELS, "ReelMenuLabels", null, "(InstaEclipse | ReelMenuLabels): ", COULD_NOT_START, true, null));
        s.add(new InstallStep(INSTANT_SAVE_HOOK, "InstantSaveHook", null, "(InstaEclipse | InstantSave): ", COULD_NOT_START, true, null));
        s.add(new InstallStep(INSTANT_UPLOAD_HOOK, "InstantUploadHook", null, "(InstaEclipse | InstantUpload): ", COULD_NOT_START, false, null));
        s.add(new InstallStep(GHOST_EPHEMERAL_KEEP_HOOK, "GhostEphemeralKeepHook", null, "(InstaEclipse | EphemeralHook): ", COULD_NOT_START, true, null));
        s.add(new InstallStep(GHOST_PERMANENT_VIEW_HOOK, "GhostPermanentViewHook", null, "(InstaEclipse | ViewOnceMedia): ", COULD_NOT_START, true, null));
        s.add(new InstallStep(VIEW_ONCE_BADGE_HOOK, "ViewOnceBadgeHook", null, "(InstaEclipse | VOBadge): ", COULD_NOT_START, true, null));
        s.add(new InstallStep(LOCKED_COLLECTIONS_HOOK, "LockedCollectionsHook", null, "(IE|LockedColl) ", COULD_NOT_START, false, null).inPhaseA());
        s.add(new InstallStep(SAVED_ON_PROFILE_HOOK, "SavedOnProfileHook", null, "(IE|SavedProfile) ", COULD_NOT_START, true, null)
                .withLookupFlags("savedOnProfile").withRestartKeys("savedOnProfile"));
        s.add(new InstallStep(HIDE_HEADER_BUTTONS_HOOK, "HideHeaderButtonsHook", null, "(IE|HideButtons) ", COULD_NOT_START, true, null));
        s.add(new InstallStep(SHARE_LINK_DOWNLOAD_HOOK, "ShareLinkDownloadHook", null, "(IE|ShareDL) ", COULD_NOT_START, false, null).inPhaseA());
        s.add(new InstallStep(CALL_CONFIRM_HOOK, "CallConfirmHook", null, "(InstaEclipse | CallConfirm): ", COULD_NOT_START, false, "confirmCalls").withRestartKeys("confirmCalls"));
        s.add(new InstallStep(SEARCH_HISTORY_HOOK, "SearchHistoryHook", null, "(InstaEclipse | SearchHistory): ", COULD_NOT_START, true, null));
        s.add(new InstallStep(FEED_REFRESH_CONFIRM_HOOK, "FeedRefreshConfirmHook", null, "(InstaEclipse | RefreshConfirm): ", COULD_NOT_START, false, null).inPhaseA());
        s.add(new InstallStep(ROUNDED_FEED_POSTS_HOOK, "RoundedFeedPostsHook", null, "(InstaEclipse | RoundedPosts): ", COULD_NOT_START, false, null).inPhaseA());
        s.add(new InstallStep(STORY_TRAY_HOOK, "StoryTrayHook", null, "(InstaEclipse | StoryTray): ", COULD_NOT_START, false, null).inPhaseA());
        s.add(new InstallStep(STORY_DOWNLOAD_HOOK, "StoryDownloadHook", null, "(InstaEclipse | StoryDownload): ", COULD_NOT_START, true, null));
        s.add(new InstallStep(REEL_DOWNLOAD_HOOK, "ReelDownloadHook", null, "(InstaEclipse | ReelDownload): ", COULD_NOT_START, true, null));
        s.add(new InstallStep(PROFILE_PIC_DOWNLOAD_HOOK, "ProfilePicDownloadHook", null, "(InstaEclipse | ProfileDownload): ", COULD_NOT_START, true, null));
        s.add(new InstallStep(INTERCEPTOR, "interceptor", null, "(InstaEclipse | Interceptor): ", COULD_NOT_START, false, null).inPhaseA());
        s.add(new InstallStep(INTERCEPTOR_CHAIN_ENTRY, "interceptorChainEntry", new String[]{"interceptor"}, "(InstaEclipse | Interceptor): ", CACHED_REQUESTS, true, null));
        s.add(new InstallStep(TERMINATED_EXECUTOR_GUARD, "TerminatedExecutorGuard", null, "(InstaEclipse | ExecGuard): ", COULD_NOT_START, false, null).inPhaseA());
        s.add(new InstallStep(EXEC_GUARD_DISPATCHERS, "ExecGuardDispatchers", null, "(InstaEclipse | ExecGuard): ", COULD_NOT_START, true, null));
        LEGACY_STEPS = Collections.unmodifiableList(s);
        STEPS = Collections.unmodifiableList(order(s));
    }

    static List<InstallStep> order(List<InstallStep> today) {
        Map<String, InstallStep> byName = new HashMap<>();
        for (InstallStep step : today) byName.put(step.name, step);
        List<InstallStep> out = new ArrayList<>();
        for (InstallStep step : today) if (step.phaseA) out.add(step);
        for (String name : EARLY) out.add(byName.get(name));
        for (InstallStep step : today) {
            if (!step.phaseA && !EARLY.contains(step.name) && !LAST.contains(step.name)) out.add(step);
        }
        for (String name : LAST) out.add(byName.get(name));
        return out;
    }

    public List<InstallStep> steps() {
        return STEPS;
    }

    public List<InstallStep> legacySteps() {
        return LEGACY_STEPS;
    }

    public static int earlyCount(List<InstallStep> background) {
        int n = 0;
        while (n < background.size() && EARLY.contains(background.get(n).name)) n++;
        return n;
    }

    public boolean open(InstallStep step) {
        return step.gateKey == null || gateOpen(step.gateKey);
    }

    public Object flag(String name) {
        try {
            return FeatureFlags.class.getField(name).get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("Unknown feature flag: " + name, e);
        }
    }

    public boolean gateOpen(String gateKey) {
        switch (gateKey) {
            case "confirmFollows":
                return FeatureFlags.confirmFollows;
            case "confirmCalls":
                return FeatureFlags.confirmCalls;
            case "expandCaptions":
                return FeatureFlags.expandCaptions;
            case "startupTab":
                return FeatureFlags.startupTab != null && !FeatureFlags.startupTab.isEmpty();
            case "postCountSpoof":
                return FeatureFlags.postCountSpoof;
            case "profileSpoofer|postCountSpoof":
                return FeatureFlags.profileSpoofer || FeatureFlags.postCountSpoof;
            default:
                throw new IllegalArgumentException("Unknown install gate: " + gateKey);
        }
    }

    public void run(InstallStep step, InstallCtx ctx) throws Throwable {
        switch (step.id) {
            case INSTAGRAM_UI:
                new UIHookManager().mainActivity(ctx.loader);
                return;
            case REEL_VIEWER_HOOK:
                new ReelViewerHook().install(ctx.bridge, ctx.loader);
                return;
            case DEV_OPTIONS_UNLOCK_HOOK:
                new DevOptionsUnlockHook().handleDevOptions(ctx.bridge);
                return;
            case GHOST_DM_SEEN_HOOK:
                new GhostDMSeenHook().handleSeenBlock(ctx.bridge);
                return;
            case GHOST_DM_MARK_AS_READ_HOOK:
                new GhostDMMarkAsReadHook(Module.moduleSourceDir).install(ctx.lpparam.classLoader);
                return;
            case GHOST_CHANNEL_MARK_AS_READ_HOOK:
                new GhostChannelMarkAsReadHook().install(ctx.lpparam.classLoader);
                return;
            case GHOST_TYPING_INDICATOR_HOOK:
                new GhostTypingIndicatorHook().handleTypingBlock(ctx.bridge);
                return;
            case GHOST_SCREENSHOT_DETECTION_HOOK:
                new GhostScreenshotDetectionHook().handleScreenshotBlock(ctx.bridge);
                return;
            case SCREENSHOT_PERMISSION_HOOK:
                new ScreenshotPermissionHook().install(ctx.lpparam.classLoader);
                return;
            case GHOST_VIEW_ONCE_HOOK:
                new GhostViewOnceHook().handleViewOnceBlock(ctx.bridge);
                return;
            case GHOST_STORY_SEEN_HOOK:
                new GhostStorySeenHook().handleStorySeenBlock(ctx.bridge);
                return;
            case KEEP_UNSENT_MESSAGES_HOOK:
                new KeepUnsentMessagesHook().install(ctx.bridge, ctx.lpparam.classLoader);
                return;
            case UNSENT_THREAD_BUTTON_HOOK:
                new UnsentThreadButtonHook().install(ctx.lpparam.classLoader);
                return;
            case HIDE_CHATS_HOOK:
                new HideChatsHook().install(ctx.bridge, ctx.lpparam.classLoader);
                return;
            case INBOX_DISCOVER_PEOPLE_HOOK:
                new InboxDiscoverPeopleHook().install(ctx.bridge, ctx.lpparam.classLoader);
                return;
            case CUSTOM_FONT_HOOK:
                new CustomFontHook().install(ctx.lpparam.classLoader);
                return;
            case META_AI:
                new RemoveMetaAIHook().install(ctx.lpparam.classLoader);
                return;
            case META_AI_REELS:
                new RemoveMetaAIHook().installReels(ctx.bridge, ctx.lpparam.classLoader);
                return;
            case DISABLE_REPOST_HOOK:
                new DisableRepostHook().install(ctx.context, ctx.bridge, ctx.lpparam.classLoader);
                return;
            case HIDE_SUGGESTED_FEED_ITEMS_HOOK:
                new HideSuggestedFeedItemsHook().install(ctx.bridge, ctx.loader);
                return;
            case AD_BLOCKER:
                new AdBlocker().disableSponsoredContent(ctx.bridge, ctx.loader);
                return;
            case TRACKING_LINK_DISABLE:
                new TrackingLinkDisable().disableTrackingLinks(ctx.loader);
                return;
            case EXACT_TIMESTAMP_HOOK:
                new ExactTimestampHook().install(ctx.bridge, ctx.loader);
                return;
            case EXPAND_CAPTIONS_HOOK:
                new ExpandCaptionsHook().install(ctx.bridge);
                return;
            case HIDE_COMMUNITY_NOTES_HOOK:
                new HideCommunityNotesHook().install(ctx.bridge, ctx.loader);
                return;
            case REELS_CONTROLS_HOOK:
                new ReelsControlsHook().install(ctx.bridge, ctx.loader);
                return;
            case ACCOUNT_LIMIT_HOOK:
                new AccountLimitHook().install(ctx.bridge, ctx.loader);
                return;
            case AIRPLANE_MODE_HOOK:
                new AirplaneModeHook().install(ctx.loader);
                return;
            case HIGH_RESOLUTION_IMAGE_HOOK:
                new HighResolutionImageHook().install(ctx.bridge, ctx.loader);
                return;
            case ONBOARDING_PROMPT_HOOK:
                new OnboardingPromptHook().install(ctx.bridge, ctx.loader);
                return;
            case NOTIFICATION_REGISTRATION_HOOK:
                new NotificationRegistrationHook().install();
                return;
            case STARTUP_TAB_HOOK:
                new StartupTabHook().install(ctx.loader);
                return;
            case BACK_LEAVES_HOME_HOOK:
                new BackLeavesHomeHook().install(ctx.loader);
                return;
            case BACK_LEAVES_HOME_DISPATCHER:
                BackLeavesHomeDispatcher.install(ctx.loader);
                return;
            case SWIPE_TO_CAMERA_HOOK:
                new SwipeToCameraHook().install(ctx.loader);
                return;
            case FOLLOWING_ONLY_FEED_HOOK:
                new FollowingOnlyFeedHook().install(ctx.bridge, ctx.loader);
                return;
            case PROFILE_FOLLOW_STATUS_HOOK:
                new ProfileFollowStatusHook().install(ctx.bridge, ctx.loader);
                return;
            case PROFILE_FOLLOWS_YOU_HOOK:
                new ProfileFollowsYouHook().install(ctx.bridge, ctx.loader);
                return;
            case PROFILE_COUNT_DELTA_HOOK:
                new ProfileCountDeltaHook().install(ctx.bridge, ctx.loader);
                return;
            case FOLLOW_STATUS_HOOK:
                new FollowStatusHook().install(ctx.bridge, ctx.loader);
                return;
            case FOLLOWING_FOLLOW_STATUS_HOOK:
                new FollowingFollowStatusHook().install(ctx.context, ctx.bridge, ctx.loader);
                return;
            case PLAYBACK_SPEED_HOOK:
                new PlaybackSpeedHook().install(ctx.bridge, ctx.loader);
                return;
            case HIDE_NOTES_TRAY_HOOK:
                new HideNotesTrayHook().install(ctx.bridge);
                return;
            case VOICE_USER_CACHE_TRACKER:
                VoiceMessageDownloadHook.installUserCacheTracker(ctx.loader);
                return;
            case VOICE_MESSAGE_DOWNLOAD_HOOK:
                new VoiceMessageDownloadHook().install(ctx.bridge, ctx.loader);
                return;
            case EDIT_HISTORY_HOOK:
                new EditHistoryHook().install(ctx.bridge, ctx.loader);
                return;
            case JUMP_TO_FIRST_MESSAGE_HOOK:
                new JumpToFirstMessageHook().install();
                return;
            case READ_RECEIPT_CHAT_HOOK:
                new ReadReceiptChatHook().install(ctx.loader);
                return;
            case STORY_VIEWER_SEARCH_HOOK:
                new StoryViewerSearchHook().install(ctx.bridge, ctx.loader);
                return;
            case UNLOCK_PLUS_BENEFITS_HOOK:
                new UnlockPlusBenefitsHook().install(ctx.bridge, ctx.loader);
                return;
            case COMMENT_SEARCH_HOOK:
                new CommentSearchHook().install(ctx.bridge, ctx.loader);
                return;
            case CHAT_EXPORT_HOOK:
                new ChatExportHook().install(ctx.bridge, ctx.loader);
                return;
            case FOLLOW_LIST_EXPORT_HOOK:
                new FollowListExportHook().install(ctx.bridge, ctx.loader);
                return;
            case LIKE_UNDO_HOOK:
                new LikeUndoHook().install(ctx.bridge);
                return;
            case STORY_LIKE_UNDO_HOOK:
                new StoryLikeUndoHook().install(ctx.bridge);
                return;
            case FOLLOW_CONFIRM_HOOK:
                new FollowConfirmHook().install(ctx.bridge);
                return;
            case PROFILE_MENU_HOOK:
                new ProfileMenuHook().install(ctx.bridge, ctx.loader);
                return;
            case POST_COUNT_SPOOF:
                PostCountSpoof.install(ctx.bridge, ctx.loader);
                return;
            case PROFILE_TEXT_COPY_HOOK:
                new ProfileTextCopyHook().install(ctx.bridge, ctx.loader);
                return;
            case PROFILE_SPOOF_HOOK:
                new ProfileSpoofHook().install(ctx.bridge, ctx.loader);
                return;
            case SHARE_SHEET_CLEANUP_HOOK:
                new ShareSheetCleanupHook().install(ctx.loader);
                return;
            case EXTERNAL_BROWSER_HOOK:
                new ExternalBrowserHook().install();
                return;
            case OPEN_LINKS_EXTERNALLY_HOOK:
                new OpenLinksExternallyHook().install(ctx.bridge, ctx.loader);
                return;
            case DISABLE_STORY_FLIPPING_HOOK:
                new DisableStoryFlippingHook().handleStoryFlippingDisable(ctx.bridge);
                return;
            case STORY_MENTION_HOOK:
                new StoryMentionHook().install(ctx.bridge, ctx.lpparam.classLoader);
                return;
            case STORY_LINKS_HOOK:
                new StoryLinksHook().install(ctx.bridge, ctx.lpparam.classLoader);
                return;
            case STORY_INFO_HOOK:
                new StoryInfoHook().install(ctx.bridge, ctx.lpparam.classLoader);
                return;
            case MANUAL_STORY_SEEN_HOOK:
                new ManualStorySeenHook().install(ctx.bridge, ctx.lpparam.classLoader);
                return;
            case COMMENT_COPY_HOOK:
                new CommentCopyHook().install(ctx.lpparam.classLoader);
                return;
            case CAPTION_COPY_HOOK:
                new CaptionCopyHook().install(ctx.bridge, ctx.lpparam.classLoader);
                return;
            case DISABLE_DOUBLE_TAP_LIKE_HOOK:
                new DisableDoubleTapLikeHook().install(ctx.bridge, ctx.lpparam.classLoader);
                return;
            case DOUBLE_TAP_EXTRAS_HOOK:
                new DoubleTapExtrasHook().install(ctx.bridge, ctx.lpparam.classLoader);
                return;
            case FEED_PHOTO_ZOOM_HOOK:
                new FeedPhotoZoomHook().install(ctx.lpparam.classLoader);
                return;
            case LOCATION_SPOOF_HOOK:
                new LocationSpoofHook().install(ctx.lpparam.classLoader);
                return;
            case IG_THEME_HOOK:
                new IgThemeHook().install(ctx.loader);
                return;
            case FORCE_REEL_QUALITY_HOOK:
                new ForceReelQualityHook().install(ctx.bridge, ctx.lpparam.classLoader);
                return;
            case MAX_UPLOAD_QUALITY_HOOK:
                new MaxUploadQualityHook().install(ctx.bridge, ctx.lpparam.classLoader);
                return;
            case DISABLE_VIDEO_AUTO_PLAY_HOOK:
                new DisableVideoAutoPlayHook().handleAutoPlayDisable(ctx.bridge);
                return;
            case BUILD_EXPIRED_POPUP_HOOK:
                new BuildExpiredPopupHook().install(ctx.bridge, ctx.lpparam.classLoader);
                return;
            case FEED_VIDEO_DOWNLOAD_HOOK:
                new FeedVideoDownloadHook().install(ctx.lpparam.classLoader);
                return;
            case FEED_VIDEO_DOWNLOAD_CAPTURE:
                FeedVideoDownloadHook.installVideoUrlCaptureHook(ctx.bridge, ctx.lpparam.classLoader);
                return;
            case POST_DOWNLOAD_CONTEXT_MENU_HOOK:
                new PostDownloadContextMenuHook().install(ctx.bridge, ctx.lpparam.classLoader);
                return;
            case REEL_MENU_LABELS:
                ReelMenuLabels.install(ctx.bridge, ctx.lpparam.classLoader);
                return;
            case INSTANT_SAVE_HOOK:
                new InstantSaveHook().install(ctx.lpparam.classLoader);
                return;
            case INSTANT_UPLOAD_HOOK:
                new InstantUploadHook().install(ctx.lpparam.classLoader);
                return;
            case GHOST_EPHEMERAL_KEEP_HOOK:
                new GhostEphemeralKeepHook().install(ctx.bridge, ctx.lpparam.classLoader);
                return;
            case GHOST_PERMANENT_VIEW_HOOK:
                new GhostPermanentViewHook().install(ctx.bridge, ctx.lpparam.classLoader);
                return;
            case VIEW_ONCE_BADGE_HOOK:
                new ViewOnceBadgeHook().install(ctx.bridge, ctx.lpparam.classLoader);
                return;
            case LOCKED_COLLECTIONS_HOOK:
                new LockedCollectionsHook().install(ctx.loader);
                return;
            case SAVED_ON_PROFILE_HOOK:
                new SavedOnProfileHook().install(ctx.bridge, ctx.loader);
                return;
            case HIDE_HEADER_BUTTONS_HOOK:
                new HideHeaderButtonsHook().install(ctx.bridge, ctx.loader);
                return;
            case SHARE_LINK_DOWNLOAD_HOOK:
                new ShareLinkDownloadHook().install(ctx.loader);
                return;
            case CALL_CONFIRM_HOOK:
                CallConfirmHook.install();
                return;
            case SEARCH_HISTORY_HOOK:
                SearchHistoryHook.install(ctx.bridge, ctx.loader);
                return;
            case FEED_REFRESH_CONFIRM_HOOK:
                FeedRefreshConfirmHook.install(ctx.loader);
                return;
            case ROUNDED_FEED_POSTS_HOOK:
                RoundedFeedPostsHook.install(ctx.loader);
                return;
            case STORY_TRAY_HOOK:
                StoryTrayHook.install(ctx.loader);
                return;
            case STORY_DOWNLOAD_HOOK:
                new StoryDownloadHook().install(ctx.bridge, ctx.lpparam.classLoader);
                return;
            case REEL_DOWNLOAD_HOOK:
                new ReelDownloadHook().install(ctx.bridge, ctx.lpparam.classLoader);
                return;
            case PROFILE_PIC_DOWNLOAD_HOOK:
                ProfilePicDownloadHook.install(ctx.bridge, ctx.loader);
                return;
            case INTERCEPTOR:
                new IGNetworkInterceptor().installTigon(ctx.lpparam);
                return;
            case INTERCEPTOR_CHAIN_ENTRY:
                NetworkChainEntry.install(ctx.lpparam.classLoader);
                return;
            case TERMINATED_EXECUTOR_GUARD:
                new TerminatedExecutorGuard().install(ctx.lpparam.classLoader);
                return;
            case EXEC_GUARD_DISPATCHERS:
                ExecGuardDispatchers.install(ctx.bridge);
                return;
            default:
                throw new IllegalArgumentException("Unknown install step: " + step.name);
        }
    }

    public static final class InstallCtx {

        public final Context context;
        public final XC_LoadPackage.LoadPackageParam lpparam;
        public final ClassLoader loader;
        public final DexKitBridge bridge;

        public InstallCtx(Context context, XC_LoadPackage.LoadPackageParam lpparam, ClassLoader loader, DexKitBridge bridge) {
            this.context = context;
            this.lpparam = lpparam;
            this.loader = loader;
            this.bridge = bridge;
        }
    }
}
