package ps.reso.instaeclipse.mods.extras;

final class MqttRestartGate {

    synchronized boolean switchedOff() {
        return true;
    }

    synchronized void switchedOn() {
    }

    synchronized boolean activityResumed() {
        return false;
    }

    synchronized void activityPaused() {
    }
}
