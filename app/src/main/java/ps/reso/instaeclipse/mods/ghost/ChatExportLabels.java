package ps.reso.instaeclipse.mods.ghost;

final class ChatExportLabels {

    enum Label {
        BANNER, MESSAGES, EDITED, PINNED, FORWARDED, STORY_REPLY, REPLY_TO, SHARED_STORY, SHARED_REEL,
        SHARED_POST, SHARED_PROFILE, MENTION_BY_YOU, MENTION_OF_YOU
    }

    interface Text {
        String get(Label label, Object... args);

        String count(Label label, int n);
    }

    private final Text text;

    ChatExportLabels(Text text) {
        this.text = text;
    }

    static boolean ready(boolean messages, boolean serializer, boolean writer) {
        return messages;
    }

    String banner() {
        return "Chat export";
    }

    String messages(int n) {
        return n + " messages";
    }

    String edited() {
        return "edited";
    }

    String pinned() {
        return "📌 pinned";
    }

    String forwarded() {
        return "↪ forwarded";
    }

    String shared(String type, boolean storyReply, boolean reel) {
        boolean story = type.contains("story");
        return storyReply ? "↩ Story reply" : story ? "↗ Shared story" : reel ? "↗ Shared reel" : "↗ Shared post";
    }

    String sharedBy(String user, boolean storyReply) {
        return (storyReply ? "to @" : "@") + user;
    }

    String profile() {
        return "👤 Shared profile";
    }

    String mention(boolean self) {
        return "✨ Mentioned you in a story";
    }

    String previewMention() {
        return "✨ Story mention";
    }
}
