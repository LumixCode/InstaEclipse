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
        return messages && serializer && writer;
    }

    String banner() {
        return text.get(Label.BANNER);
    }

    String messages(int n) {
        return text.count(Label.MESSAGES, n);
    }

    String edited() {
        return text.get(Label.EDITED);
    }

    String pinned() {
        return "📌 " + text.get(Label.PINNED);
    }

    String forwarded() {
        return "↪ " + text.get(Label.FORWARDED);
    }

    String shared(String type, boolean storyReply, boolean reel) {
        if (storyReply) return "↩ " + text.get(Label.STORY_REPLY);
        Label label = type.contains("story") ? Label.SHARED_STORY : reel ? Label.SHARED_REEL : Label.SHARED_POST;
        return "↗ " + text.get(label);
    }

    String sharedBy(String user, boolean storyReply) {
        return storyReply ? text.get(Label.REPLY_TO, user) : "@" + user;
    }

    String profile() {
        return "👤 " + text.get(Label.SHARED_PROFILE);
    }

    String mention(boolean self) {
        return "✨ " + text.get(self ? Label.MENTION_BY_YOU : Label.MENTION_OF_YOU);
    }
}
