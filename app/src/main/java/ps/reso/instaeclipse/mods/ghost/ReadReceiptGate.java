package ps.reso.instaeclipse.mods.ghost;

final class ReadReceiptGate {

    private ReadReceiptGate() {}

    static boolean active(boolean ghostSeen, boolean readReceiptChats) {
        return readReceiptChats;
    }
}
