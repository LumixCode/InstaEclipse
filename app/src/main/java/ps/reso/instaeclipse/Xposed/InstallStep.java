package ps.reso.instaeclipse.Xposed;

import java.util.Set;

public final class InstallStep {

    private static final String[] NONE = new String[0];

    public final int id;
    public final String name;
    private final String[] skipAliases;
    public final String failTag;
    public final String failText;
    public final boolean dexKit;
    public final String gateKey;
    public final boolean phaseA;
    private final String[] lookupFlags;
    private final String[] restartKeys;
    private final String[] features;

    public InstallStep(int id, String name, String[] skipAliases, String failTag, String failText,
                       boolean dexKit, String gateKey) {
        this(id, name, skipAliases, failTag, failText, dexKit, gateKey, false, NONE, NONE, NONE);
    }

    private InstallStep(int id, String name, String[] skipAliases, String failTag, String failText,
                        boolean dexKit, String gateKey, boolean phaseA, String[] lookupFlags, String[] restartKeys,
                        String[] features) {
        this.id = id;
        this.name = name;
        this.skipAliases = skipAliases == null ? NONE : skipAliases.clone();
        this.failTag = failTag;
        this.failText = failText;
        this.dexKit = dexKit;
        this.gateKey = gateKey;
        this.phaseA = phaseA;
        this.lookupFlags = lookupFlags == null ? NONE : lookupFlags.clone();
        this.restartKeys = restartKeys == null ? NONE : restartKeys.clone();
        this.features = features == null ? NONE : features.clone();
    }

    public InstallStep inPhaseA() {
        return new InstallStep(id, name, skipAliases, failTag, failText, dexKit, gateKey, true, lookupFlags, restartKeys, features);
    }

    public InstallStep withLookupFlags(String... flags) {
        return new InstallStep(id, name, skipAliases, failTag, failText, dexKit, gateKey, phaseA, flags, restartKeys, features);
    }

    public InstallStep withRestartKeys(String... keys) {
        return new InstallStep(id, name, skipAliases, failTag, failText, dexKit, gateKey, phaseA, lookupFlags, keys, features);
    }

    public InstallStep withFeatures(String... keys) {
        return new InstallStep(id, name, skipAliases, failTag, failText, dexKit, gateKey, phaseA, lookupFlags, restartKeys, keys);
    }

    public String[] skipAliases() {
        return skipAliases.clone();
    }

    public String[] lookupFlags() {
        return lookupFlags.clone();
    }

    public String[] restartKeys() {
        return restartKeys.clone();
    }

    public String[] features() {
        return features.clone();
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
