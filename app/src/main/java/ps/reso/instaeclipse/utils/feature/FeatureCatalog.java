package ps.reso.instaeclipse.utils.feature;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.Predicate;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.text.SearchText;

public final class FeatureCatalog {

    public enum Group {
        PRIVACY(0xFF5E5CE6, R.string.feat_group_privacy),
        MEDIA(0xFFFF9F0A, R.string.feat_group_media),
        BROWSE(0xFF30D158, R.string.feat_group_browse),
        SOCIAL(0xFFBF5AF2, R.string.feat_group_social),
        APPEARANCE(0xFFFF375F, R.string.feat_group_appearance),
        TOOLS(0xFF8E8E93, R.string.feat_group_tools);

        public final int accent;
        public final int title;

        Group(int accent, int title) {
            this.accent = accent;
            this.title = title;
        }
    }

    public enum Kind { SWITCH, MASTER, PICKER, ACTION, INFO }

    public enum Side { BOTH, IG_ONLY, COMPANION_ONLY }

    public enum Restart { NONE, ON_ONLY, BOTH }

    public interface Values {
        boolean bool(String key, boolean def);

        int integer(String key, int def);

        String string(String key, String def);
    }

    public static final class Item {
        public final String key;
        public final Kind kind;
        public final int label;
        public final int desc;
        public final int icon;
        public final String[] requires;
        public final String overriddenBy;
        public final Restart restartOn;
        public final boolean defaultOn;
        public final boolean counts;
        public final String[] countRequiresAnyOf;
        public final Side side;
        public final String[] children;
        public final String[] statusKeys;
        public final Predicate<Values> countWhen;
        public final int[] aliases;
        public final String[] words;

        private Item(Builder b) {
            key = b.key;
            kind = b.kind;
            label = b.label;
            desc = b.desc;
            icon = b.icon;
            requires = b.requires;
            overriddenBy = b.overriddenBy;
            restartOn = b.restartOn;
            defaultOn = b.defaultOn;
            counts = b.counts;
            countRequiresAnyOf = b.countRequiresAnyOf;
            side = b.side;
            children = b.children;
            statusKeys = b.statusKeys;
            countWhen = b.countWhen;
            aliases = b.aliases;
            words = b.words;
        }
    }

    public static final class Section {
        public final int header;
        public final List<Item> items;

        private Section(int header, List<Item> items) {
            this.header = header;
            this.items = items;
        }
    }

    public static final class Category {
        public final String key;
        public final Group group;
        public final int icon;
        public final int title;
        public final List<Section> sections;
        public final boolean hidden;

        private Category(String key, Group group, int icon, int title, List<Section> sections, boolean hidden) {
            this.key = key;
            this.group = group;
            this.icon = icon;
            this.title = title;
            this.sections = sections;
            this.hidden = hidden;
        }

        public List<Item> items() {
            List<Item> out = new ArrayList<>();
            for (Section s : sections) out.addAll(s.items);
            return out;
        }
    }

    private static final class Builder {
        final String key;
        final Kind kind;
        final int label;
        final int icon;
        int desc;
        String[] requires = {};
        String overriddenBy;
        Restart restartOn = Restart.NONE;
        boolean defaultOn;
        boolean counts;
        String[] countRequiresAnyOf = {};
        Side side = Side.BOTH;
        String[] children = {};
        String[] statusKeys = {};
        Predicate<Values> countWhen;
        int[] aliases = {};
        String[] words = {};

        Builder(String key, Kind kind, int label, int icon) {
            this.key = key;
            this.kind = kind;
            this.label = label;
            this.icon = icon;
            this.counts = kind == Kind.SWITCH;
        }

        Builder desc(int desc) {
            this.desc = desc;
            return this;
        }

        Builder requires(String... keys) {
            requires = keys;
            return this;
        }

        Builder overriddenBy(String key) {
            overriddenBy = key;
            return this;
        }

        Builder countRequiresAnyOf(String... keys) {
            countRequiresAnyOf = keys;
            return this;
        }

        Builder restart(Restart restart) {
            restartOn = restart;
            return this;
        }

        Builder defaultOn() {
            defaultOn = true;
            return this;
        }

        Builder uncounted() {
            counts = false;
            return this;
        }

        Builder side(Side side) {
            this.side = side;
            return this;
        }

        Builder status(String... keys) {
            statusKeys = keys;
            return this;
        }

        Builder countWhen(Predicate<Values> when) {
            countWhen = when;
            return this;
        }

        Builder aliases(int... ids) {
            aliases = ids;
            return this;
        }

        Builder words(String... list) {
            words = list;
            return this;
        }
    }

    private static final String[] LOCKS =
            {"lockWholeApp", "lockDirectMessages", "lockSpecificMessages", "lockSavedCollections"};
    private static final String[] EXTREME_PARENTS = {"disableFeed", "disableStories", "disableReels", "disableExplore",
            "disableComments", "dailyReelLimitEnabled", "nightReelBlockEnabled"};
    private static final String[] ORDERABLE_TABS =
            {"feed_tab", "clips_tab", "search_tab", "creation_tab", "direct_tab", "news_tab", "profile_tab"};

    public static final List<Category> CATEGORIES = Collections.unmodifiableList(Arrays.asList(
            page("ghost", Group.PRIVACY, R.drawable.ic_eye_off, R.string.ig_dialog_section_ghost_mode,
                    section(R.string.cat_sec_stay_invisible,
                            master("ghostAll", "isGhostSeen", "isGhostTyping", "isGhostStory", "isGhostLive",
                                    "isGhostScreenshot", "isGhostViewOnce"),
                            sw("isGhostSeen", R.string.ig_dialog_ghost_hide_dm_seen, R.drawable.ic_eye_off)
                                    .desc(R.string.cat_isGhostSeen_desc).status("GhostSeen"),
                            sw("isGhostTyping", R.string.ig_dialog_ghost_hide_typing, R.drawable.ic_chat)
                                    .status("GhostTyping"),
                            sw("isGhostStory", R.string.ig_dialog_ghost_hide_story_views, R.drawable.ic_story_ring)
                                    .desc(R.string.cat_isGhostStory_desc).status("GhostStories").words("seen"),
                            sw("isGhostLive", R.string.ig_dialog_ghost_hide_live_presence, R.drawable.ic_live)
                                    .status("GhostLive").words("seen"),
                            sw("isGhostScreenshot", R.string.ig_dialog_ghost_bypass_screenshot, R.drawable.ic_camera)
                                    .desc(R.string.cat_isGhostScreenshot_desc).status("GhostScreenshot"),
                            sw("isGhostViewOnce", R.string.ig_dialog_ghost_hide_view_once, R.drawable.ic_eye_off)
                                    .desc(R.string.cat_isGhostViewOnce_desc).status("GhostViewOnce")),
                    section(R.string.cat_sec_screenshots,
                            sw("allowScreenshots", R.string.cat_allowScreenshots, R.drawable.ic_camera)
                                    .desc(R.string.cat_allowScreenshots_desc).status("AllowScreenshots")
                                    .aliases(R.string.ig_dialog_ghost_allow_screenshots_dms),
                            sw("permanentViewMode", R.string.cat_permanentViewMode, R.drawable.ic_eye)
                                    .desc(R.string.cat_permanentViewMode_desc)
                                    .status("PermanentViewMode", "ViewOnceBadge")
                                    .aliases(R.string.ig_dialog_ghost_permanent_view_once)),
                    section(R.string.cat_sec_seen_online,
                            sw("manualStorySeen", R.string.ig_feature_manual_story_seen, R.drawable.ic_eye)
                                    .desc(R.string.cat_manualStorySeen_desc).requires("isGhostStory")
                                    .status("ManualStorySeen"),
                            sw("readReceiptChats", R.string.ig_dialog_ghost_read_receipt_chats, R.drawable.ic_eye)
                                    .desc(R.string.cat_readReceiptChats_desc_list).requires("isGhostSeen")
                                    .status("ReadReceiptChats"),
                            action("readReceiptChatList", R.string.ig_read_receipt_list_title, R.drawable.ic_chat)
                                    .requires("readReceiptChats").side(Side.IG_ONLY),
                            sw("airplaneMode", R.string.cat_airplaneMode, R.drawable.ic_eye_off)
                                    .desc(R.string.cat_airplaneMode_desc).status("airplaneMode")
                                    .aliases(R.string.ig_dialog_extras_airplane_mode).words("airplane", "offline"),
                            info("activityStatusHint", R.string.ie_ghost_activity_status_hint).words("last seen")),
                    section(0,
                            action("quickToggle", R.string.feat_quick_toggle, R.drawable.ic_tune)
                                    .desc(R.string.cat_quickToggle_desc)
                                    .aliases(R.string.ig_dialog_customize_quick_toggle))),
            page("lock", Group.PRIVACY, R.drawable.ic_shield, R.string.cat_title_lock,
                    section(R.string.cat_sec_passcode,
                            action("lockPasscode", R.string.ig_dialog_misc_lock_dms_set, R.drawable.ic_shield)
                                    .side(Side.IG_ONLY),
                            sw("lockUseFingerprint", R.string.ig_dialog_misc_lock_fingerprint, R.drawable.ic_shield)
                                    .requires(LOCKS).defaultOn().uncounted()),
                    section(R.string.ig_dialog_misc_lock_section,
                            sw("lockWholeApp", R.string.ig_dialog_misc_lock_whole_app, R.drawable.ic_shield)
                                    .desc(R.string.cat_lockWholeApp_desc_grace).status("LockWholeApp"),
                            info("lockWholeAppGrace", R.string.cat_lockWholeAppGrace).requires("lockWholeApp"),
                            sw("lockDirectMessages", R.string.ig_dialog_misc_lock_dms, R.drawable.ic_shield)
                                    .desc(R.string.cat_lockDirectMessages_desc).status("LockDirectMessages"),
                            sw("lockSpecificMessages", R.string.ig_lock_selected_enable, R.drawable.ic_chat)
                                    .desc(R.string.ig_lock_selected_menu_desc),
                            action("lockChooseChats", R.string.ig_lock_select_chats, R.drawable.ic_chat)
                                    .requires("lockSpecificMessages").side(Side.IG_ONLY),
                            sw("lockDirectAlways", R.string.ig_dialog_misc_lock_dms_always, R.drawable.ic_shield)
                                    .desc(R.string.cat_lockDirectAlways_desc).countRequiresAnyOf(LOCKS)
                                    .aliases(R.string.ig_lock_relock_hint),
                            sw("lockSavedCollections", R.string.cat_lockSavedCollections, R.drawable.ic_shield)
                                    .desc(R.string.cat_lockSavedCollections_desc_all_posts).status("LockSavedCollections")
                                    .aliases(R.string.ig_locked_coll_setting, R.string.ig_locked_coll_setting_short)),
                    section(R.string.cat_sec_hidden_chats,
                            sw("hideSpecificChats", R.string.ig_dialog_misc_hide_chats, R.drawable.ic_eye_off)
                                    .desc(R.string.cat_hideSpecificChats_desc).status("HideSpecificChats")
                                    .aliases(R.string.ig_hide_chats_title),
                            sw("showHideChatButton", R.string.ig_hide_chats_show_button, R.drawable.ic_eye)
                                    .requires("hideSpecificChats").defaultOn().uncounted(),
                            action("hideChooseChats", R.string.ig_hide_chats_select, R.drawable.ic_eye)
                                    .requires("hideSpecificChats").side(Side.IG_ONLY))),
            page("ads", Group.PRIVACY, R.drawable.ic_block, R.string.menu_ads_tracking,
                    section(R.string.cat_sec_block,
                            master("adsAll", "isAdBlockEnabled", "isAnalyticsBlocked", "disableTrackingLinks"),
                            sw("isAdBlockEnabled", R.string.ig_dialog_ad_block_ads, R.drawable.ic_shield)
                                    .status("AdBlocker"),
                            sw("isAnalyticsBlocked", R.string.ig_dialog_ad_block_analytics, R.drawable.ic_shield)
                                    .desc(R.string.cat_isAnalyticsBlocked_desc).status("BlockAnalytics"),
                            sw("disableTrackingLinks", R.string.ig_dialog_ad_disable_tracking, R.drawable.ic_link)
                                    .desc(R.string.cat_disableTrackingLinks_desc).status("DisableTrackingLinks")),
                    section(R.string.cat_sec_links,
                            sw("openLinksExternally", R.string.ig_dialog_open_links_externally, R.drawable.ic_link)
                                    .desc(R.string.cat_openLinksExternally_desc).status("OpenLinksExternally"),
                            picker("customShareDomain", R.string.ig_dialog_extras_share_domain, R.drawable.ic_link)
                                    .desc(R.string.cat_customShareDomain_desc).countWhen(FeatureCatalog::sharesDomain)),
                    section(R.string.cat_sec_search,
                            sw("disableSearchHistory", R.string.ig_disable_search_history, R.drawable.ic_search)
                                    .desc(R.string.cat_disableSearchHistory_desc).status("DisableSearchHistory"))),
            page("location", Group.PRIVACY, R.drawable.ic_pin, R.string.ig_dialog_menu_location,
                    section(0,
                            sw("spoofLocation", R.string.ig_dialog_location_spoof_enable, R.drawable.ic_pin)
                                    .desc(R.string.cat_spoofLocation_desc).restart(Restart.ON_ONLY).status("SpoofLocation").words("spoof"),
                            picker("locationPlace", R.string.ig_dialog_location_current_title, R.drawable.ic_search),
                            picker("locationRecent", R.string.ig_dialog_location_recent, R.drawable.ic_timer))),
            page("downloads", Group.MEDIA, R.drawable.ic_download, R.string.ig_dialog_menu_downloader,
                    section(R.string.cat_sec_download_buttons,
                            master("downloadsAll", "enablePostDownload", "enableStoryDownload", "enableReelDownload",
                                    "enableProfileDownload"),
                            sw("enablePostDownload", R.string.ig_dialog_downloader_posts, R.drawable.ic_download)
                                    .status("PostDownload"),
                            sw("enableStoryDownload", R.string.ig_dialog_downloader_stories, R.drawable.ic_download)
                                    .status("StoryDownload"),
                            sw("enableReelDownload", R.string.ig_dialog_downloader_reels, R.drawable.ic_download)
                                    .status("ReelDownload"),
                            sw("enableProfileDownload", R.string.ig_dialog_downloader_profiles, R.drawable.ic_download)
                                    .status("ProfileDownload")),
                    section(R.string.cat_sec_in_chats,
                            sw("enableDmMediaDownload", R.string.cat_enableDmMediaDownload, R.drawable.ic_download)
                                    .desc(R.string.cat_enableDmMediaDownload_desc).restart(Restart.ON_ONLY).status("DmMediaDownload")
                                    .aliases(R.string.ig_dialog_downloader_dm_media),
                            sw("enableVoiceDownload", R.string.cat_enableVoiceDownload, R.drawable.ic_download)
                                    .desc(R.string.cat_enableVoiceDownload_desc).restart(Restart.ON_ONLY).status("VoiceDownload")
                                    .aliases(R.string.ig_dialog_downloader_voice),
                            sw("enableStickerDownload", R.string.cat_enableStickerDownload, R.drawable.ic_download)
                                    .desc(R.string.cat_enableStickerDownload_desc).restart(Restart.ON_ONLY).status("StickerDownload")
                                    .aliases(R.string.ig_dialog_downloader_stickers),
                            sw("saveInstants", R.string.cat_saveInstants, R.drawable.ic_download)
                                    .desc(R.string.cat_saveInstants_desc).status("SaveInstants")
                                    .aliases(R.string.ig_dialog_misc_save_instants)),
                    section(R.string.cat_sec_post_menu,
                            sw("copyMediaLink", R.string.ig_dialog_downloader_copy_link, R.drawable.ic_link)
                                    .desc(R.string.cat_copyMediaLink_desc).status("CopyMediaLink"),
                            sw("offerCopyImage", R.string.ig_dialog_downloader_offer_copy_image, R.drawable.ic_content_copy)
                                    .desc(R.string.cat_offerCopyImage_desc).status("CopyImage"),
                            sw("offerMediaInfo", R.string.ig_dialog_downloader_offer_media_info, R.drawable.ic_search)
                                    .desc(R.string.cat_offerMediaInfo_desc).status("MediaInfo")),
                    section(R.string.ie_download_quality,
                            picker("downloaderUseOriginal", R.string.menu_quality, R.drawable.ic_download)
                                    .defaultOn(),
                            sw("downloaderCompatibility", R.string.ie_download_compatibility, R.drawable.ic_tune)
                                    .uncounted(),
                            sw("downloaderAllowFallback", R.string.ie_download_fallback, R.drawable.ic_download)
                                    .uncounted(),
                            sw("offerAudioDownload", R.string.ig_dialog_downloader_offer_audio, R.drawable.ic_download)
                                    .desc(R.string.cat_offerAudioDownload_desc).status("AudioDownload")),
                    section(R.string.cat_sec_files,
                            picker("downloadFolder", R.string.ig_dialog_downloader_folder, R.drawable.ic_folder)
                                    .side(Side.COMPANION_ONLY),
                            action("downloadFolderReset", R.string.feat_downloader_reset_folder, R.drawable.ic_delete)
                                    .side(Side.COMPANION_ONLY),
                            sw("downloaderUsernameFolder", R.string.ig_dialog_downloader_username_subfolder, R.drawable.ic_folder),
                            sw("downloaderAddTimestamp", R.string.ig_dialog_downloader_add_timestamp, R.drawable.ic_timer)),
                    section(R.string.cat_sec_story_cache,
                            sw("cacheStories", R.string.ig_story_cache_enable, R.drawable.ic_download)
                                    .desc(R.string.cat_cacheStories_desc).status("CacheStories"),
                            picker("storyCacheHours", R.string.ig_story_cache_duration, R.drawable.ic_timer)
                                    .requires("cacheStories"),
                            action("cachedStories", R.string.ig_story_cache_view, R.drawable.ic_eye)
                                    .requires("cacheStories").side(Side.IG_ONLY))),
            page("quality", Group.MEDIA, R.drawable.ic_sparkle, R.string.menu_quality,
                    section(0,
                            picker("forceReelQuality", R.string.ig_dialog_quality_force_reels, R.drawable.ic_movie)
                                    .restart(Restart.ON_ONLY).status("ForceReelQuality").countWhen(FeatureCatalog::forcesReelQuality),
                            sw("highResolutionImages", R.string.ig_dialog_extras_high_res_images, R.drawable.ic_gallery)
                                    .status("highResolutionImages"),
                            sw("maxUploadQuality", R.string.ig_dialog_max_upload_quality, R.drawable.ic_upload)
                                    .status("MaxUploadQuality"),
                            picker("spoofDevice", R.string.cat_spoofDevice, R.drawable.ic_movie)
                                    .desc(R.string.cat_spoofDevice_desc).restart(Restart.BOTH).status("SpoofDevice")
                                    .countWhen(FeatureCatalog::spoofsDevice).aliases(R.string.ig_spoof_device)
                                    .words("spoof"),
                            info("qualityPointer", R.string.cat_qualityPointer))),
            page("feed", Group.BROWSE, R.drawable.ic_home, R.string.menu_feed,
                    section(R.string.ig_dialog_menu_clean_feed,
                            sw("hideSuggestionsInFeed", R.string.ig_dialog_clean_feed_hide_suggested, R.drawable.ic_sparkle)
                                    .status("HideSuggestionsInFeed"),
                            sw("hideThreadsSuggestions", R.string.ig_dialog_clean_feed_hide_threads, R.drawable.ic_sparkle)
                                    .desc(R.string.cat_hideThreadsSuggestions_desc).status("HideThreadsSuggestions"),
                            sw("hideLikedPosts", R.string.ig_dialog_clean_feed_hide_liked, R.drawable.ic_sparkle)
                                    .status("HideLikedPosts"),
                            sw("hideCommunityNotes", R.string.ig_dialog_clean_feed_hide_community_notes, R.drawable.ic_sparkle)
                                    .status("HideCommunityNotes"),
                            sw("followingOnlyFeed", R.string.ig_dialog_clean_feed_following_only, R.drawable.ic_sparkle)
                                    .status("FollowingOnlyFeed"),
                            sw("disableDiscoverPeople", R.string.ig_dialog_misc_disable_discover_people, R.drawable.ic_block)
                                    .desc(R.string.cat_disableDiscoverPeople_desc).status("DisableDiscoverPeople")),
                    section(R.string.cat_sec_posts,
                            sw("expandCaptions", R.string.cat_expandCaptions, R.drawable.ic_sparkle)
                                    .restart(Restart.BOTH).status("ExpandCaptions")
                                    .aliases(R.string.ig_dialog_clean_feed_expand_captions),
                            sw("exactTimestamps", R.string.ig_dialog_misc_exact_timestamps, R.drawable.ic_timer)
                                    .desc(R.string.cat_exactTimestamps_desc).restart(Restart.ON_ONLY).status("ExactTimestamps"),
                            picker("exactTimestampFormat", R.string.ig_timestamp_format, R.drawable.ic_timer)
                                    .requires("exactTimestamps"),
                            sw("enablePhotoZoom", R.string.cat_enablePhotoZoom, R.drawable.ic_search)
                                    .status("PhotoZoom").aliases(R.string.ig_dialog_misc_photo_zoom),
                            sw("disableVideoAutoPlay", R.string.ig_dialog_misc_disable_video_autoplay, R.drawable.ic_movie)
                                    .desc(R.string.cat_disableVideoAutoPlay_desc).status("DisableVideoAutoPlay"),
                            sw("confirmFeedRefresh", R.string.ig_confirm_refresh, R.drawable.ic_restart)
                                    .desc(R.string.cat_confirmFeedRefresh_desc).status("ConfirmFeedRefresh"),
                            picker("feedCornerRadius", R.string.ig_feed_corner_radius, R.drawable.ic_sparkle)
                                    .status("RoundedFeedPosts").countWhen(FeatureCatalog::roundsCorners))),
            page("stories", Group.BROWSE, R.drawable.ic_story_ring, R.string.menu_stories,
                    section(R.string.cat_sec_watching,
                            sw("disableStoryFlipping", R.string.ig_dialog_misc_disable_story_autoswipe, R.drawable.ic_story_ring)
                                    .desc(R.string.cat_disableStoryFlipping_desc).status("DisableStoryFlipping"),
                            sw("enableStoryMentions", R.string.ig_dialog_misc_view_story_mentions, R.drawable.ic_at)
                                    .desc(R.string.cat_enableStoryMentions_desc).status("StoryMentions"),
                            sw("storyLinks", R.string.ig_dialog_misc_story_links, R.drawable.ic_link)
                                    .desc(R.string.cat_storyLinks_desc).status("StoryLinks"),
                            sw("storyInfo", R.string.ig_dialog_misc_story_info, R.drawable.ic_info)
                                    .desc(R.string.cat_storyInfo_desc).status("StoryInfo")),
                    section(R.string.cat_sec_your_stories,
                            sw("storyViewerSearch", R.string.ig_dialog_misc_viewer_search, R.drawable.ic_search)
                                    .status("StoryViewerSearch")),
                    section(R.string.cat_sec_story_tray,
                            sw("hideStoriesTray", R.string.ig_dialog_extras_hide_stories_tray, R.drawable.ic_story_ring)
                                    .desc(R.string.cat_hideStoriesTray_desc).status("hideStoriesTray"),
                            picker("storyTrayScale", R.string.ig_story_tray_size, R.drawable.ic_story_ring)
                                    .status("StoryTraySize").countWhen(FeatureCatalog::shrinksTray),
                            picker("storyRingColor", R.string.ig_story_ring_color, R.drawable.ic_palette)
                                    .status("StoryRingColor").countWhen(FeatureCatalog::coloursRing))),
            page("reels", Group.BROWSE, R.drawable.ic_movie, R.string.menu_reels,
                    section(0,
                            sw("reelsAutoScroll", R.string.ig_dialog_extras_reels_auto_scroll, R.drawable.ic_movie)
                                    .desc(R.string.cat_reelsAutoScroll_desc).status("reelsAutoScroll"),
                            sw("reelsLockScroll", R.string.ig_dialog_extras_reels_lock_scroll, R.drawable.ic_movie)
                                    .status("reelsLockScroll"),
                            sw("forceReelScrubber", R.string.ig_dialog_downloader_reel_scrubber, R.drawable.ic_movie)
                                    .restart(Restart.ON_ONLY).status("ForceReelScrubber"),
                            sw("holdLeftSlowMotion", R.string.cat_holdLeftSlowMotion, R.drawable.ic_movie)
                                    .desc(R.string.cat_holdLeftSlowMotion_desc).restart(Restart.ON_ONLY)
                                    .status("HoldSlowMotion").aliases(R.string.ig_dialog_reels_hold_left_slow),
                            sw("offerPlaybackSpeed", R.string.cat_offerPlaybackSpeed, R.drawable.ic_movie)
                                    .desc(R.string.cat_offerPlaybackSpeed_desc).restart(Restart.ON_ONLY).status("PlaybackSpeed")
                                    .aliases(R.string.ig_dialog_downloader_offer_speed))),
            page("focus", Group.BROWSE, R.drawable.ic_timer, R.string.cat_title_focus,
                    section(R.string.cat_sec_today,
                            info("reelUsage", R.string.ig_reel_usage_count)),
                    section(R.string.cat_sec_turn_off,
                            master("focusAll", "disableFeed", "disableStories", "disableReels", "disableExplore",
                                    "disableComments"),
                            sw("disableFeed", R.string.ig_dialog_distraction_disable_feed, R.drawable.ic_block)
                                    .status("DisableFeedSection").aliases(R.string.ig_dialog_menu_distraction_free)
                                    .words("distraction"),
                            sw("disableStories", R.string.ig_dialog_distraction_disable_stories, R.drawable.ic_story_ring)
                                    .status("DisableStoriesSection").aliases(R.string.ig_dialog_menu_distraction_free)
                                    .words("distraction"),
                            sw("disableReels", R.string.ig_dialog_distraction_disable_reels, R.drawable.ic_movie)
                                    .desc(R.string.cat_disableReels_desc).status("DisableReelsSection")
                                    .aliases(R.string.ig_dialog_menu_distraction_free).words("distraction"),
                            sw("disableReelsExceptDM", R.string.cat_allow_chat_reels, R.drawable.ic_movie)
                                    .requires("disableReels")
                                    .aliases(R.string.ig_dialog_distraction_disable_reels_except_dm),
                            sw("disableExplore", R.string.ig_dialog_distraction_disable_explore, R.drawable.ic_search)
                                    .status("DisableExploreSection").aliases(R.string.ig_dialog_menu_distraction_free)
                                    .words("distraction"),
                            sw("disableComments", R.string.ig_dialog_distraction_disable_comments, R.drawable.ic_chat)
                                    .status("DisableCommentsSection")
                                    .aliases(R.string.ig_dialog_menu_distraction_free).words("distraction")),
                    section(R.string.cat_sec_reel_limits,
                            sw("dailyReelLimitEnabled", R.string.ig_reel_limit_enable, R.drawable.ic_movie)
                                    .overriddenBy("disableReels").status("DailyReelLimit"),
                            picker("dailyReelLimit", R.string.ig_reel_limit_allowance, R.drawable.ic_movie)
                                    .requires("dailyReelLimitEnabled").overriddenBy("disableReels"),
                            sw("dailyReelLimitAllowDM", R.string.cat_allow_chat_reels, R.drawable.ic_chat)
                                    .requires("dailyReelLimitEnabled").overriddenBy("disableReels").defaultOn()
                                    .uncounted().aliases(R.string.ig_reel_limit_allow_dm),
                            sw("nightReelBlockEnabled", R.string.ig_night_reels_enable, R.drawable.ic_timer)
                                    .overriddenBy("disableReels").status("NightReelBlock"),
                            picker("nightReelBlockStartMinutes", R.string.ig_night_reels_start_label, R.drawable.ic_timer)
                                    .requires("nightReelBlockEnabled").overriddenBy("disableReels"),
                            picker("nightReelBlockEndMinutes", R.string.ig_night_reels_end_label, R.drawable.ic_timer)
                                    .requires("nightReelBlockEnabled").overriddenBy("disableReels"),
                            info("nightReelStatus", R.string.ig_night_reels_status_inactive)
                                    .requires("nightReelBlockEnabled"),
                            sw("nightReelBlockAllowDM", R.string.cat_allow_chat_reels, R.drawable.ic_chat)
                                    .requires("nightReelBlockEnabled").overriddenBy("disableReels").defaultOn()
                                    .uncounted().aliases(R.string.ig_night_reels_allow_dm)),
                    section(R.string.cat_sec_less_clutter,
                            sw("removeMetaAI", R.string.ig_dialog_misc_remove_meta_ai, R.drawable.ic_sparkle)
                                    .restart(Restart.ON_ONLY).status("RemoveMetaAI"),
                            sw("hideOnboardingPrompts", R.string.ig_dialog_extras_hide_onboarding, R.drawable.ic_block)
                                    .desc(R.string.cat_hideOnboardingPrompts_desc).status("hideOnboardingPrompts")
                                    .words("location prompt"),
                            sw("hidePlusUpsells", R.string.ig_dialog_clean_feed_hide_plus, R.drawable.ic_sparkle)
                                    .status("HidePlusUpsells")),
                    section(R.string.feat_danger_zone,
                            sw("isExtremeMode", R.string.cat_isExtremeMode, R.drawable.ic_block)
                                    .desc(R.string.cat_isExtremeMode_desc).requires(EXTREME_PARENTS)
                                    .aliases(R.string.ig_dialog_distraction_extreme_mode))),
            page("messages", Group.SOCIAL, R.drawable.ic_chat, R.string.menu_messages,
                    section(R.string.cat_sec_keep_messages,
                            sw("keepEphemeralMessages", R.string.ig_dialog_ghost_keep_disappearing, R.drawable.ic_timer)
                                    .status("KeepEphemeralMessages"),
                            sw("keepUnsentMessages", R.string.ig_dialog_ghost_keep_unsent_chat, R.drawable.ic_chat)
                                    .desc(R.string.cat_keepUnsentMessages_desc).status("KeepUnsentMessages"),
                            sw("keepUnsentInChat", R.string.ig_feature_keep_unsent_in_chat, R.drawable.ic_chat)
                                    .desc(R.string.cat_keepUnsentInChat_desc).requires("keepUnsentMessages")
                                    .defaultOn().uncounted(),
                            sw("showUnsentIndicator", R.string.ig_feature_unsent_indicator, R.drawable.ic_chat)
                                    .desc(R.string.cat_showUnsentIndicator_desc).requires("keepUnsentMessages")
                                    .restart(Restart.ON_ONLY).status("UnsentIndicator"),
                            sw("showUnsentButton", R.string.ig_feature_show_unsent_button, R.drawable.ic_delete)
                                    .requires("keepUnsentMessages").defaultOn().uncounted(),
                            action("unsentLog", R.string.ig_dialog_unsent_title, R.drawable.ic_chat)
                                    .requires("keepUnsentMessages").side(Side.IG_ONLY),
                            sw("keepEditHistory", R.string.ig_dialog_ghost_edit_history, R.drawable.ic_chat)
                                    .desc(R.string.cat_keepEditHistory_desc).status("EditHistory")),
                    section(R.string.cat_sec_chat_tools,
                            sw("jumpToFirstMessage", R.string.cat_jumpToFirstMessage, R.drawable.ic_jump_first)
                                    .desc(R.string.cat_jumpToFirstMessage_desc).status("JumpToFirstMessage")
                                    .aliases(R.string.ig_dialog_ghost_jump_first),
                            sw("chatExport", R.string.cat_chatExport, R.drawable.ic_download)
                                    .desc(R.string.cat_chatExport_desc).status("ChatExport")
                                    .aliases(R.string.ig_dialog_ghost_chat_export),
                            sw("translateMessages", R.string.cat_translateMessages, R.drawable.ic_chat)
                                    .desc(R.string.cat_translateMessages_desc).restart(Restart.ON_ONLY).status("ChatTranslate")
                                    .aliases(R.string.ig_dialog_translate_messages),
                            sw("bulkUnsend", R.string.ig_dialog_bulk_unsend, R.drawable.ic_chat)
                                    .desc(R.string.cat_bulkUnsend_desc).restart(Restart.ON_ONLY).status("BulkUnsend"),
                            sw("confirmCalls", R.string.ig_call_confirm, R.drawable.ic_shield)
                                    .restart(Restart.ON_ONLY).status("ConfirmCalls"),
                            sw("hideChatMenuButton", R.string.ig_hide_chat_menu_button, R.drawable.ic_tune)
                                    .desc(R.string.cat_hideChatMenuButton_desc).status("ChatMenuTripleTap")),
                    section(R.string.cat_sec_inbox,
                            sw("hideNotesTray", R.string.ig_dialog_distraction_hide_notes_tray, R.drawable.ic_block)
                                    .status("HideNotesTray"),
                            sw("hideDmSuggestions", R.string.ig_hide_dm_suggestions, R.drawable.ic_block)
                                    .desc(R.string.cat_hideDmSuggestions_desc).status("HideDmSuggestions"),
                            sw("hideDmBlend", R.string.ig_dialog_hide_dm_blend, R.drawable.ic_block)
                                    .desc(R.string.cat_hideDmBlend_desc).status("HideDmBlend"),
                            sw("hideShareCreateGroup", R.string.ig_dialog_misc_hide_share_create_group, R.drawable.ic_block)
                                    .status("HideShareCreateGroup")),
                    section(R.string.cat_sec_instants,
                            sw("hideInstants", R.string.ig_hide_instants, R.drawable.ic_block).status("HideInstants"),
                            sw("uploadInstants", R.string.ig_dialog_misc_upload_instants, R.drawable.ic_upload)
                                    .restart(Restart.ON_ONLY).status("UploadInstants"))),
            page("interactions", Group.SOCIAL, R.drawable.ic_heart, R.string.menu_likes_comments,
                    section(R.string.cat_sec_likes,
                            sw("disableDoubleTapLike", R.string.ig_dialog_misc_disable_double_tap_like, R.drawable.ic_heart)
                                    .status("DisableDoubleTapLike"),
                            sw("likeUndo", R.string.cat_likeUndo, R.drawable.ic_heart)
                                    .desc(R.string.cat_likeUndo_desc).restart(Restart.ON_ONLY).status("LikeUndo")
                                    .aliases(R.string.ig_dialog_misc_like_undo),
                            sw("confirmLikes", R.string.ig_confirm_likes, R.drawable.ic_heart)
                                    .restart(Restart.ON_ONLY).status("ConfirmLikes"),
                            sw("disableRepost", R.string.ig_dialog_misc_disable_repost, R.drawable.ic_block)
                                    .status("DisableRepost")),
                    section(R.string.cat_sec_comments,
                            sw("enableCopyComment", R.string.ig_dialog_misc_copy_comment, R.drawable.ic_content_copy)
                                    .desc(R.string.cat_enableCopyComment_desc).status("CopyComment"),
                            sw("commentSearch", R.string.ig_dialog_misc_comment_search, R.drawable.ic_search)
                                    .status("CommentSearch"),
                            sw("enableCaptionCopy", R.string.ig_dialog_misc_copy_caption, R.drawable.ic_content_copy)
                                    .desc(R.string.cat_enableCaptionCopy_desc).restart(Restart.ON_ONLY).status("CaptionCopy"))),
            page("profiles", Group.SOCIAL, R.drawable.ic_at, R.string.menu_profiles,
                    section(R.string.cat_sec_followers,
                            sw("showFollowStatusLabel", R.string.cat_showFollowStatusLabel, R.drawable.ic_notification)
                                    .desc(R.string.cat_showFollowStatusLabel_desc).status("FollowStatusLabel")
                                    .aliases(R.string.ig_dialog_misc_profile_follows_label),
                            sw("showFollowerToast", R.string.ig_dialog_misc_show_follower_toast, R.drawable.ic_notification)
                                    .desc(R.string.cat_showFollowerToast_desc).status("FollowerToast"),
                            sw("showFollowingFollowStatus", R.string.ig_feature_following_follow_status, R.drawable.ic_at)
                                    .desc(R.string.cat_showFollowingFollowStatus_desc).status("FollowingFollowStatus"),
                            sw("profileAnalyzer", R.string.cat_profileAnalyzer, R.drawable.ic_search)
                                    .desc(R.string.cat_profileAnalyzer_desc).restart(Restart.ON_ONLY)
                                    .status("ProfileAnalyzer", "FollowersHistory")
                                    .aliases(R.string.ig_profile_analyzer, R.string.ig_feat_followers_history)
                                    .words("unfollow", "followers history"),
                            info("profileAnalyzerHelp", R.string.cat_profileAnalyzerHelp).requires("profileAnalyzer"),
                            action("watchlist", R.string.ig_pa_watchlist, R.drawable.ic_eye)
                                    .requires("profileAnalyzer").side(Side.IG_ONLY),
                            sw("followListExport", R.string.ig_dialog_misc_follow_export, R.drawable.ic_download)
                                    .desc(R.string.cat_followListExport_desc).restart(Restart.ON_ONLY).status("FollowListExport")),
                    section(R.string.cat_sec_profile_tools,
                            sw("profileMenuTools", R.string.cat_profileMenuTools, R.drawable.ic_content_copy)
                                    .desc(R.string.cat_profileMenuTools_desc).status("ProfileMenuTools")
                                    .aliases(R.string.ig_profile_menu_tools),
                            sw("savedOnProfile", R.string.ig_saved_on_profile, R.drawable.ic_save)
                                    .restart(Restart.ON_ONLY).status("SavedOnProfile"),
                            sw("hideThreadsButton", R.string.ig_dialog_hide_threads_button, R.drawable.ic_block)
                                    .overriddenBy("hideThreadsSuggestions").status("HideThreadsButton"),
                            sw("fancyBioFont", R.string.ig_dialog_fancy_bio_font, R.drawable.ic_notification)
                                    .desc(R.string.cat_fancyBioFont_desc).status("FancyBioFont"),
                            sw("confirmFollows", R.string.ig_confirm_follows, R.drawable.ic_notification)
                                    .restart(Restart.ON_ONLY).status("ConfirmFollows")),
                    section(R.string.cat_sec_this_phone,
                            sw("profileSpoofer", R.string.cat_profileSpoofer, R.drawable.ic_eye)
                                    .desc(R.string.cat_profileSpoofer_desc).restart(Restart.ON_ONLY)
                                    .status("ProfileSpoofer").aliases(R.string.ig_profile_spoofer).words("spoof"),
                            action("profileSpoofEdit", R.string.ig_profile_spoofer_edit, R.drawable.ic_tune)
                                    .requires("profileSpoofer").words("spoof"),
                            action("profilePhoto", R.string.ig_profile_spoofer_choose_photo, R.drawable.ic_gallery)
                                    .requires("profileSpoofer").side(Side.COMPANION_ONLY),
                            sw("postCountSpoof", R.string.cat_postCountSpoof, R.drawable.ic_heart)
                                    .desc(R.string.cat_postCountSpoof_desc).restart(Restart.ON_ONLY)
                                    .status("PostCountSpoof").aliases(R.string.ig_post_counts_feature).words("spoof"))),
            page("theme", Group.APPEARANCE, R.drawable.ic_palette, R.string.ig_dialog_menu_theme,
                    section(R.string.theme_section_colors,
                            sw("customThemeEnabled", R.string.theme_enable, R.drawable.ic_palette)
                                    .status("CustomTheme"),
                            action("themeCustomize", R.string.theme_customize, R.drawable.ic_palette),
                            picker("themePresetId", R.string.theme_dark_mode, R.drawable.ic_palette),
                            picker("themeLightPresetId", R.string.theme_light_mode, R.drawable.ic_palette)),
                    section(R.string.theme_section_fonts,
                            sw("customFontEnabled", R.string.ig_dialog_misc_custom_font, R.drawable.ic_palette)
                                    .restart(Restart.ON_ONLY).status("CustomFont"),
                            action("customFontPick", R.string.ig_custom_font_pick, R.drawable.ic_palette)
                                    .side(Side.IG_ONLY),
                            sw("customEmojiEnabled", R.string.ig_dialog_misc_custom_emoji, R.drawable.ic_palette)
                                    .restart(Restart.ON_ONLY).status("CustomEmoji"),
                            action("customEmojiPick", R.string.ig_custom_emoji_pick, R.drawable.ic_palette)
                                    .side(Side.IG_ONLY))),
            page("navigation", Group.APPEARANCE, R.drawable.ic_tune, R.string.menu_navigation,
                    section(R.string.menu_nav_tabs,
                            sw("hideTabHome", R.string.ig_nav_tab_home, R.drawable.ic_home).status("HideNavTabs"),
                            sw("hideTabReels", R.string.ig_nav_tab_reels, R.drawable.ic_movie).status("HideNavTabs"),
                            sw("hideTabCreate", R.string.ig_nav_tab_create, R.drawable.ic_upload)
                                    .status("HideNavTabs"),
                            sw("hideTabMessages", R.string.ig_nav_tab_messages, R.drawable.ic_chat)
                                    .status("HideNavTabs"),
                            sw("hideTabActivity", R.string.ig_nav_tab_activity, R.drawable.ic_heart)
                                    .status("HideNavTabs"),
                            sw("hideTabProfile", R.string.ig_nav_tab_profile, R.drawable.ic_at).status("HideNavTabs")),
                    section(R.string.menu_nav_order,
                            picker("navTabOrder", R.string.ig_nav_tab_order, R.drawable.ic_tune)
                                    .countWhen(FeatureCatalog::reordersTabs),
                            picker("startupTab", R.string.ig_dialog_extras_startup_tab, R.drawable.ic_tune)
                                    .status("StartupTab").countWhen(FeatureCatalog::picksStartupTab)),
                    section(R.string.menu_nav_gestures,
                            sw("disableSwipeToCamera", R.string.ig_dialog_extras_no_swipe_camera, R.drawable.ic_camera)
                                    .desc(R.string.cat_disableSwipeToCamera_desc).status("disableSwipeToCamera"),
                            sw("backLeavesHome", R.string.ig_back_leaves_home, R.drawable.ic_home)
                                    .desc(R.string.cat_backLeavesHome_desc).status("BackLeavesHome"))),
            page("app", Group.TOOLS, R.drawable.ic_settings_gear, R.string.menu_app,
                    section(0,
                            sw("unlimitedAccounts", R.string.ig_dialog_extras_unlimited_accounts, R.drawable.ic_settings_gear)
                                    .status("unlimitedAccounts"),
                            sw("removeBuildExpiredPopup", R.string.cat_removeBuildExpiredPopup, R.drawable.ic_block)
                                    .desc(R.string.cat_removeBuildExpiredPopup_desc).status("RemoveBuildExpiredPopup")
                                    .aliases(R.string.ig_dialog_dev_remove_build_expired),
                            sw("fixNotificationRegistration", R.string.cat_fixNotificationRegistration, R.drawable.ic_notification)
                                    .desc(R.string.cat_fixNotificationRegistration_desc)
                                    .status("fixNotificationRegistration")
                                    .aliases(R.string.ig_dialog_extras_fix_notifications),
                            sw("unlockPlusBenefits", R.string.cat_unlockPlusBenefits, R.drawable.ic_sparkle)
                                    .desc(R.string.cat_unlockPlusBenefits_desc).status("UnlockPlusBenefits")
                                    .aliases(R.string.ig_feature_unlock_plus),
                            sw("showFeatureToasts", R.string.cat_showFeatureToasts, R.drawable.ic_notification)
                                    .desc(R.string.cat_showFeatureToasts_desc)
                                    .aliases(R.string.ig_dialog_misc_show_feature_toasts)),
                    section(R.string.cat_sec_storage,
                            sw("autoClearCache", R.string.ig_dialog_auto_clear_cache, R.drawable.ic_timer)
                                    .desc(R.string.cat_autoClearCache_desc).status("AutoClearCache"),
                            picker("autoClearCacheSizeMb", R.string.cat_auto_clear_size, R.drawable.ic_folder)
                                    .requires("autoClearCache")
                                    .aliases(R.string.feat_cache_size_limit, R.string.ig_dialog_auto_clear_cache_size_v2)),
                    section(R.string.cat_sec_troubleshooting,
                            action("rescan", R.string.cat_rescan, R.drawable.ic_restart)
                                    .desc(R.string.cat_rescan_desc).side(Side.IG_ONLY)
                                    .aliases(R.string.ig_dialog_clear_cache))),
            page("backup", Group.TOOLS, R.drawable.ic_save, R.string.ig_dialog_menu_backup_restore,
                    section(0,
                            action("settingsBackup", R.string.ig_dialog_backup_settings, R.drawable.ic_save)
                                    .desc(R.string.tool_backup_sub),
                            action("settingsRestore", R.string.ig_dialog_restore_settings, R.drawable.ic_folder)
                                    .desc(R.string.tool_restore_sub),
                            action("dataBackup", R.string.cat_dataBackup, R.drawable.ic_download)
                                    .desc(R.string.cat_dataBackup_desc).side(Side.IG_ONLY)
                                    .aliases(R.string.ig_data_backup),
                            action("dataRestore", R.string.ig_data_restore, R.drawable.ic_folder).side(Side.IG_ONLY))),
            page("restart", Group.TOOLS, R.drawable.ic_restart, R.string.ig_dialog_menu_restart,
                    section(0,
                            action("restartNow", R.string.ig_dialog_restart_now, R.drawable.ic_restart),
                            action("restartClearCache", R.string.ig_dialog_restart_clear_cache, R.drawable.ic_delete)
                                    .side(Side.IG_ONLY))),
            page("dev", Group.TOOLS, R.drawable.ic_code, R.string.ig_dialog_menu_dev_options,
                    section(0,
                            sw("isDevEnabled", R.string.ig_dialog_dev_enable, R.drawable.ic_tune)
                                    .desc(R.string.cat_isDevEnabled_desc).status("DevOptions")),
                    section(R.string.feat_config,
                            action("devImport", R.string.ig_dialog_dev_import, R.drawable.ic_download),
                            action("devExport", R.string.ig_dialog_dev_export, R.drawable.ic_upload),
                            action("devReset", R.string.ig_dialog_dev_restore_default_config, R.drawable.ic_restart))),
            page("about", Group.TOOLS, R.drawable.ic_info, R.string.ig_dialog_menu_about),
            hidden("qt", Group.PRIVACY, 0, R.string.feat_quick_toggle,
                    section(0,
                            master("qtAll", "quickToggleSeen", "quickToggleTyping", "quickToggleScreenshot",
                                    "quickToggleViewOnce", "quickToggleStory", "quickToggleLive",
                                    "quickToggleEphemeral", "quickTogglePermanentView", "quickToggleAllowScreenshots"),
                            sw("quickToggleSeen", R.string.ig_dialog_ghost_hide_dm_seen, R.drawable.ic_eye_off)
                                    .uncounted().aliases(R.string.ig_dialog_quick_hide_seen).words("ghost"),
                            sw("quickToggleTyping", R.string.ig_dialog_ghost_hide_typing, R.drawable.ic_chat)
                                    .uncounted().aliases(R.string.ig_dialog_quick_hide_typing).words("ghost"),
                            sw("quickToggleScreenshot", R.string.ig_dialog_ghost_bypass_screenshot, R.drawable.ic_camera)
                                    .uncounted().aliases(R.string.ig_dialog_quick_disable_screenshot).words("ghost"),
                            sw("quickToggleViewOnce", R.string.ig_dialog_ghost_hide_view_once, R.drawable.ic_eye_off)
                                    .uncounted().aliases(R.string.ig_dialog_quick_hide_view_once).words("ghost"),
                            sw("quickToggleStory", R.string.ig_dialog_ghost_hide_story_views, R.drawable.ic_story_ring)
                                    .uncounted().aliases(R.string.ig_dialog_quick_hide_story_seen).words("ghost"),
                            sw("quickToggleLive", R.string.ig_dialog_ghost_hide_live_presence, R.drawable.ic_live)
                                    .uncounted().aliases(R.string.ig_dialog_quick_hide_live_seen).words("ghost"),
                            sw("quickToggleEphemeral", R.string.ig_dialog_ghost_keep_disappearing, R.drawable.ic_timer)
                                    .uncounted().aliases(R.string.ig_dialog_quick_keep_ephemeral).words("ghost"),
                            sw("quickTogglePermanentView", R.string.cat_permanentViewMode, R.drawable.ic_eye)
                                    .uncounted().aliases(R.string.ig_dialog_quick_permanent_view).words("ghost"),
                            sw("quickToggleAllowScreenshots", R.string.cat_allowScreenshots, R.drawable.ic_camera)
                                    .uncounted().aliases(R.string.ig_dialog_quick_allow_screenshots).words("ghost")))
    ));

    private static final Map<String, Item> ITEMS = new LinkedHashMap<>();
    private static final Map<String, Category> BY_KEY = new LinkedHashMap<>();

    static {
        for (Category c : CATEGORIES) {
            BY_KEY.put(c.key, c);
            for (Item i : c.items()) ITEMS.put(i.key, i);
        }
    }

    private FeatureCatalog() {}

    public static Item item(String key) {
        return ITEMS.get(key);
    }

    public static Category category(String key) {
        return BY_KEY.get(key);
    }

    public static int countOn(Category category, Values values) {
        int on = 0;
        for (Item item : category.items()) if (counted(item, values)) on++;
        return on;
    }

    public static int countAll(Values values) {
        int on = 0;
        for (Category c : CATEGORIES) if (!c.hidden) on += countOn(c, values);
        return on;
    }

    public static boolean matches(Item item, Category category, String query, IntFunction<String> text) {
        String q = SearchText.fold(query == null ? "" : query.trim());
        if (q.isEmpty()) return false;
        if (contains(text, item.label, q) || contains(text, item.desc, q) || contains(text, category.title, q)) {
            return true;
        }
        for (Section s : category.sections) {
            if (s.items.contains(item) && contains(text, s.header, q)) return true;
        }
        for (int alias : item.aliases) if (contains(text, alias, q)) return true;
        for (String word : item.words) if (SearchText.fold(word).contains(q)) return true;
        return false;
    }

    public static Set<String> restartKeys(Map<String, ?> before, Map<String, ?> after) {
        Set<String> out = new LinkedHashSet<>();
        for (Map.Entry<String, ?> entry : after.entrySet()) {
            Item item = ITEMS.get(entry.getKey());
            if (item == null || item.restartOn == Restart.NONE) continue;
            Object now = entry.getValue();
            Object was = before.containsKey(item.key) ? before.get(item.key)
                    : item.kind == Kind.SWITCH ? (Object) item.defaultOn : null;
            if (Objects.equals(was, now)) continue;
            if (item.restartOn == Restart.ON_ONLY && (!on(now) || on(was))) continue;
            out.add(item.key);
        }
        return out;
    }

    private static boolean on(Object value) {
        return Boolean.TRUE.equals(value) || value instanceof Number && ((Number) value).intValue() > 0;
    }

    public static Group groupOfStatusKey(String key) {
        for (Category c : CATEGORIES) {
            for (Item i : c.items()) {
                for (String s : i.statusKeys) if (s.equals(key)) return c.group;
            }
        }
        return Group.TOOLS;
    }

    private static boolean counted(Item item, Values values) {
        if (item.kind == Kind.PICKER) return item.countWhen != null && item.countWhen.test(values);
        if (item.kind != Kind.SWITCH || !item.counts || !on(item.key, values)) return false;
        if (item.overriddenBy != null && on(item.overriddenBy, values)) return false;
        return anyOn(item.requires, values) && anyOn(item.countRequiresAnyOf, values);
    }

    private static boolean on(String key, Values values) {
        Item item = ITEMS.get(key);
        return values.bool(key, item != null && item.defaultOn);
    }

    private static boolean anyOn(String[] keys, Values values) {
        if (keys.length == 0) return true;
        for (String key : keys) if (on(key, values)) return true;
        return false;
    }

    private static boolean contains(IntFunction<String> text, int id, String folded) {
        if (id == 0) return false;
        String s = text.apply(id);
        return s != null && SearchText.fold(s).contains(folded);
    }

    private static boolean sharesDomain(Values v) {
        return !v.string("customShareDomain", "").isEmpty();
    }

    private static boolean forcesReelQuality(Values v) {
        return v.integer("forceReelQuality", 0) != 0;
    }

    private static boolean spoofsDevice(Values v) {
        return !v.string("spoofDevice", "").isEmpty();
    }

    private static boolean roundsCorners(Values v) {
        return v.integer("feedCornerRadius", 0) > 0;
    }

    private static boolean shrinksTray(Values v) {
        return v.integer("storyTrayScale", 100) < 100;
    }

    private static boolean coloursRing(Values v) {
        return !v.string("storyRingColor", "").isEmpty();
    }

    private static boolean picksStartupTab(Values v) {
        return !v.string("startupTab", "").isEmpty();
    }

    private static boolean reordersTabs(Values v) {
        String saved = v.string("navTabOrder", "");
        if (saved == null || saved.trim().isEmpty()) return false;
        List<String> all = Arrays.asList(ORDERABLE_TABS);
        List<String> order = new ArrayList<>();
        for (String name : saved.split(",")) {
            String tab = name.trim();
            if (all.contains(tab) && !order.contains(tab)) order.add(tab);
        }
        for (String tab : ORDERABLE_TABS) if (!order.contains(tab)) order.add(tab);
        return !order.equals(all);
    }

    private static Builder sw(String key, int label, int icon) {
        return new Builder(key, Kind.SWITCH, label, icon);
    }

    private static Builder master(String key, String... children) {
        Builder b = new Builder(key, Kind.MASTER, R.string.cat_all, 0);
        b.children = children;
        return b;
    }

    private static Builder picker(String key, int label, int icon) {
        return new Builder(key, Kind.PICKER, label, icon);
    }

    private static Builder action(String key, int label, int icon) {
        return new Builder(key, Kind.ACTION, label, icon);
    }

    private static Builder info(String key, int label) {
        return new Builder(key, Kind.INFO, label, 0);
    }

    private static Section section(int header, Builder... builders) {
        List<Item> items = new ArrayList<>();
        for (Builder b : builders) items.add(new Item(b));
        return new Section(header, Collections.unmodifiableList(items));
    }

    private static Category page(String key, Group group, int icon, int title, Section... sections) {
        return new Category(key, group, icon, title, Collections.unmodifiableList(Arrays.asList(sections)), false);
    }

    private static Category hidden(String key, Group group, int icon, int title, Section... sections) {
        return new Category(key, group, icon, title, Collections.unmodifiableList(Arrays.asList(sections)), true);
    }
}
