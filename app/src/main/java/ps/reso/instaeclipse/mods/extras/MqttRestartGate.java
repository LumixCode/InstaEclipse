package ps.reso.instaeclipse.mods.extras;

final class MqttRestartGate {

    private int resumed;
    private boolean pending;

    synchronized boolean switchedOff() {
        pending = resumed == 0;
        return !pending;
    }

    synchronized void switchedOn() {
        pending = false;
    }

    synchronized boolean activityResumed() {
        resumed++;
        boolean start = pending;
        pending = false;
        return start;
    }

    synchronized void activityPaused() {
        if (resumed > 0) resumed--;
    }
}
