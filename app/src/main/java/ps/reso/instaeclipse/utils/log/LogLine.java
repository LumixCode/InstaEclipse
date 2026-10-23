package ps.reso.instaeclipse.utils.log;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Format of one stored log line body (what follows the timestamp), shared by the writer
 * ({@link ModuleLog}) and the Logs tab. A body starts with a level marker, then the caller and
 * the message: {@code "W/ [ExactTimestampHook.install:76] (ExactTimestamps) Story layout ..."}.
 * Lines written before levels existed have no marker; {@link #legacyLevel} guesses theirs.
 *
 * Pure Java so the parsing is unit-tested on the JVM.
 */
public final class LogLine {

    /** Ordered by severity. DETAIL lines are technical diagnostics, hidden in the simple view. */
    public enum Level {
        DETAIL('D'), INFO('I'), OK('S'), WARN('W'), ERROR('E');

        final char marker;

        Level(char marker) {
            this.marker = marker;
        }
    }

    private static final Pattern CALLER = Pattern.compile("^\\[[\\w$.]+:\\d+] ");
    // "(IE|KeepUnsent) ", "(InstaEclipse | DoubleTapLike): ", "(InstaEclipse): ", "(ExactTimestamps) "
    private static final Pattern TAG = Pattern.compile("^\\(([A-Za-z][\\w| .-]{0,48})\\):? ?");
    private static final Pattern CAMEL = Pattern.compile("(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");

    /** Feature names for tags that do not read well when split at capitals. */
    private static final Map<String, String> NAMES = new HashMap<>();

    static {
        NAMES.put("InstaEclipse", "InstaEclipse");
        NAMES.put("DL", "Downloads");
        NAMES.put("ProfileDL", "Profile picture download");
        NAMES.put("VOBadge", "View-once badge");
        NAMES.put("JumpFirst", "Jump to first message");
        NAMES.put("ReelLimit", "Reels limit");
        NAMES.put("RemoveMetaAI", "Remove Meta AI");
        NAMES.put("DevOptions", "Developer options");
        NAMES.put("DevOptionsEnable", "Developer options");
        NAMES.put("KeepUnsent", "Keep unsent messages");
        NAMES.put("UnsentBtn", "Unsent messages button");
        NAMES.put("UnsentLog", "Unsent messages log");
        NAMES.put("HideChats", "Hidden chats");
        NAMES.put("LockDMs", "Lock chats");
        NAMES.put("Crash", "Crash check");
        NAMES.put("FollowExport", "Follower lists");
        NAMES.put("Repost", "Disable repost");
        NAMES.put("HideButtons", "Hide buttons");
        NAMES.put("ThreadMenu", "Chat menu");
        NAMES.put("ShareSheet", "Share sheet");
        NAMES.put("Ephemeral", "Keep disappearing messages");
        NAMES.put("ViewOnceMedia", "View-once media");
        NAMES.put("ExecGuard", "Crash guard");
        NAMES.put("RemotePrefs", "Settings sync");
        NAMES.put("Font", "Custom font");
        NAMES.put("Emoji", "Emoji font");
        NAMES.put("PhotoZoom", "Photo zoom");
        NAMES.put("DexKit", "code search");
        NAMES.put("DexKitCache", "Code search cache");
        NAMES.put("DevOptionsDebug", "Developer options");
        NAMES.put("Config", "Developer config");
        NAMES.put("Airplane", "Airplane mode");
        NAMES.put("AutoPlayDisable", "Disable video autoplay");
        NAMES.put("BackLeavesHome", "Back leaves Instagram");
        NAMES.put("BuildExpired", "Expired version popup");
        NAMES.put("CacheAutoClear", "Auto clear cache");
        NAMES.put("CallConfirm", "Confirm calls");
        NAMES.put("Caption", "Caption copy");
        NAMES.put("CommunityNotes", "Hide community notes");
        NAMES.put("Companion", "InstaEclipse app connection");
        NAMES.put("Ipc", "InstaEclipse app connection");
        NAMES.put("Conversation", "Open chat");
        NAMES.put("ThreadTracker", "Open chat");
        NAMES.put("ThreadNames", "Chat names");
        NAMES.put("HiddenThreads", "Hidden chats");
        NAMES.put("CountDelta", "Profile count changes");
        NAMES.put("EditHistoryStore", "Edit history");
        NAMES.put("EphemeralHook", "Keep disappearing messages");
        NAMES.put("ExactTime", "Exact timestamps");
        NAMES.put("ExtBrowser", "External browser");
        NAMES.put("FollowConfirm", "Confirm follows");
        NAMES.put("FollowingOnlyFeed", "Following-only feed");
        NAMES.put("FollowingFeed", "Following-only feed");
        NAMES.put("FollowTools", "Follower lists");
        NAMES.put("HighResImages", "High-resolution images");
        NAMES.put("ImportReceiver", "Developer config import");
        NAMES.put("RestoreReceiver", "Settings restore");
        NAMES.put("InboxDiscover", "Discover people in chats");
        NAMES.put("Interceptor", "Network filters");
        NAMES.put("JitGuard", "Stability guard");
        NAMES.put("Launcher", "InstaEclipse screens");
        NAMES.put("LockedColl", "Locked collections");
        NAMES.put("Onboarding", "Skip setup pages");
        NAMES.put("Plus", "Unlock Plus");
        NAMES.put("UnlockPlus", "Unlock Plus");
        NAMES.put("ProfileCopy", "Copy profile text");
        NAMES.put("ReadReceipts", "Read receipts per chat");
        NAMES.put("RefreshConfirm", "Confirm feed refresh");
        NAMES.put("Resources", "Texts and icons");
        NAMES.put("SavedProfile", "Saved on profile");
        NAMES.put("ShareDL", "Shared link download");
        NAMES.put("Store", "Saved data");
        NAMES.put("SwipeCamera", "Disable swipe to camera");
        NAMES.put("Toast", "Pop-up messages");
        NAMES.put("Track", "Tracking links");
        NAMES.put("TrackingLinkDisable", "Tracking links");
        NAMES.put("UnsentExport", "Unsent messages export");
        NAMES.put("UnsentStore", "Unsent message copies");
        NAMES.put("Update", "Update check");
        NAMES.put("ViewPager2", "Swipe lock");
        NAMES.put("ViewerSearch", "Story viewer search");
        NAMES.put("VoiceDL", "Voice message download");
        NAMES.put("Changelog", "Profile changelog");
        NAMES.put("Mention", "Story mentions");
        NAMES.put("CopyComment", "Copy comment");
        NAMES.put("DoubleTapLike", "Disable double-tap like");
        NAMES.put("StoryFlipping", "Disable story auto-advance");
        NAMES.put("DeviceSpoof", "Device spoofing");
        NAMES.put("ExternalLinks", "Open links externally");
        NAMES.put("FollowingStatus", "Follow status in following list");
        NAMES.put("FollowLabel", "Profile follow label");
        NAMES.put("Post", "Post menu");
        NAMES.put("Reel", "Reel menu");
        NAMES.put("Story", "Story download");
        NAMES.put("Highlight", "Highlight download");
        NAMES.put("HighlightTray", "Highlight download all");
        NAMES.put("InstantSave", "Save Instants");
        NAMES.put("InstantUpload", "Instants from gallery");
        NAMES.put("MediaAdapter", "Downloads");
        NAMES.put("Download", "Downloads");
        NAMES.put("Frame", "Save video frame");
        NAMES.put("Export", "Chat export");
        NAMES.put("ChatExport", "Chat export");
        NAMES.put("Service", "background service");
        NAMES.put("Car", "carousel");
        NAMES.put("Type", "file type");
        NAMES.put("Username", "file names");
        NAMES.put("GhostModeSeen", "Hide seen in chats");
        NAMES.put("TypingBlock", "Hide typing");
        NAMES.put("ScreenshotBlock", "Hide screenshot alerts");
        NAMES.put("StoryBlock", "Hide story views");
        NAMES.put("ViewOnce", "Hide view-once opens");
        NAMES.put("ScreenshotPermission", "Allow screenshots");
        NAMES.put("UnsentBubble", "Unsent message colors");
        NAMES.put("UnsentIndicator", "Unsent message marker");
        NAMES.put("ReadReceiptChat", "Read receipts per chat");
        NAMES.put("ManualStorySeen", "Mark story as seen");
        NAMES.put("SearchHistory", "Don't save searches");
        NAMES.put("PROBE", "debug");
        NAMES.put("DistractUI", "Distraction-free mode");
        NAMES.put("HideLiked", "Hide liked posts");
        NAMES.put("HideSuggested", "Hide suggested posts");
        NAMES.put("ProfileSpoof", "Profile spoofer");
        NAMES.put("PostCounts", "Edit post counts");
        NAMES.put("NotesTray", "Hide notes tray");
        NAMES.put("Theme", "Custom theme");
        NAMES.put("RoundedPosts", "Rounded feed posts");
        NAMES.put("StoryTray", "Story tray");
        NAMES.put("FollowsYou", "Follow status label");
        NAMES.put("ReelLabels", "Reel menu labels");
    }

    private LogLine() {}

    /** "W/ " for WARN; ModuleLog prepends it to every body it writes. */
    public static String marker(Level level) {
        return level.marker + "/ ";
    }

    /** The marked level, else a guess for an old unmarked line. */
    public static Level levelOf(String body) {
        Level marked = markedLevel(body);
        return marked != null ? marked : legacyLevel(body);
    }

    static Level markedLevel(String body) {
        if (body == null || body.length() < 3 || body.charAt(1) != '/' || body.charAt(2) != ' ') return null;
        for (Level level : Level.values()) if (level.marker == body.charAt(0)) return level;
        return null;
    }

    /** The body without its level marker, as it looked before levels existed. */
    public static String stripMarker(String body) {
        return markedLevel(body) != null ? body.substring(3) : body;
    }

    /**
     * The level of a message logged through {@link ModuleLog#line}: the status emoji the code
     * already writes, else INFO. Never a keyword guess, so "skipped=0" or a tag named "Crash"
     * is not an error.
     */
    public static Level emojiLevel(String message) {
        if (message == null) return Level.INFO;
        if (message.contains("❌")) return Level.ERROR;
        if (message.contains("⚠")) return Level.WARN;
        if (message.contains("✅") || message.contains("✔")) return Level.OK;
        return Level.INFO;
    }

    /** Lines saved by older builds: status emoji first, then keywords in the message only. */
    static Level legacyLevel(String body) {
        Level emoji = emojiLevel(body);
        if (emoji != Level.INFO) return emoji;
        String message = message(body).toLowerCase(Locale.ROOT);
        if (message.contains("exception") || message.contains("fatal") || message.contains("could not install")
                || message.contains("failed to hook")) return Level.ERROR;
        if (message.contains("warn") || message.contains("timed out")) return Level.WARN;
        if (message.contains("hooked") || message.contains("installed")) return Level.OK;
        return Level.INFO;
    }

    /** Text after the marker, the "[Class.method:line]" caller and the "(tag)". */
    public static String message(String body) {
        String rest = withoutCaller(stripMarker(body));
        Matcher tag = TAG.matcher(rest);
        return tag.find() ? rest.substring(tag.end()) : rest;
    }

    private static String withoutCaller(String body) {
        Matcher caller = CALLER.matcher(body);
        return caller.find() ? body.substring(caller.end()) : body;
    }

    /**
     * The simple view of a body: "Keep unsent messages: hooks installed" instead of
     * "S/ [KeepUnsentMessagesHook.install:412] (IE|KeepUnsent) ✅ hooks installed".
     */
    public static String friendly(String body) {
        String rest = withoutCaller(stripMarker(body));
        Matcher tag = TAG.matcher(rest);
        if (!tag.find()) return stripStatusEmoji(rest);
        String name = featureName(tag.group(1));
        String text = stripStatusEmoji(rest.substring(tag.end()));
        if (name.isEmpty()) return text;
        return text.isEmpty() ? name : name + ": " + text;
    }

    /** "IE|DL|DexKit" -> "Downloads · code search", "InstaEclipse | DoubleTapLike" -> "Double tap like". */
    static String featureName(String tag) {
        StringBuilder out = new StringBuilder();
        for (String raw : tag.split("\\|")) {
            String part = raw.trim();
            if (part.isEmpty() || part.equals("IE")) continue;
            if (part.equals("InstaEclipse") && tag.contains("|")) continue;
            String name = NAMES.get(part);
            if (name == null) name = humanize(part);
            if (out.length() > 0) out.append(" · ");
            out.append(name);
        }
        return out.toString();
    }

    private static String humanize(String camel) {
        String[] words = CAMEL.split(camel);
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (out.length() > 0) {
                out.append(' ');
                // Keep acronyms (DM, UI, AI) upper case; lower-case ordinary words.
                boolean acronym = word.length() > 1 && word.equals(word.toUpperCase(Locale.ROOT));
                out.append(acronym ? word : word.toLowerCase(Locale.ROOT));
            } else {
                out.append(word);
            }
        }
        return out.toString();
    }

    private static String stripStatusEmoji(String text) {
        String out = text;
        for (String emoji : new String[]{"✅", "✔️", "✔", "❌", "⚠️", "⚠"}) {
            if (out.startsWith(emoji)) {
                out = out.substring(emoji.length()).trim();
                break;
            }
        }
        return out;
    }
}
