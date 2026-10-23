package ps.reso.instaeclipse.mods.ghost;

final class UnsentGate {

    private UnsentGate() {}

    static boolean active(boolean keepUnsentMessages, boolean showUnsentIndicator) {
        return keepUnsentMessages && showUnsentIndicator;
    }
}
