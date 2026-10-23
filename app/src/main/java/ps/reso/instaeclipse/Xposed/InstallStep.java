package ps.reso.instaeclipse.Xposed;

import java.util.Set;

public final class InstallStep {

    public final int id;
    public final String name;
    private final String[] skipAliases;
    public final String failTag;
    public final String failText;
    public final boolean dexKit;
    public final String gateKey;

    public InstallStep(int id, String name, String[] skipAliases, String failTag, String failText,
                       boolean dexKit, String gateKey) {
        this.id = id;
        this.name = name;
        this.skipAliases = skipAliases == null ? new String[0] : skipAliases.clone();
        this.failTag = failTag;
        this.failText = failText;
        this.dexKit = dexKit;
        this.gateKey = gateKey;
    }

    public String[] skipAliases() {
        return skipAliases.clone();
    }

    public boolean skippedBy(Set<String> skipped) {
        if (skipped == null || skipped.isEmpty()) return false;
        if (skipped.contains(name)) return true;
        for (String alias : skipAliases) {
            if (skipped.contains(alias)) return true;
        }
        return false;
    }

    @Override
    public String toString() {
        return name;
    }
}
