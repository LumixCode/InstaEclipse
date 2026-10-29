package ps.reso.instaeclipse.utils.core;

import android.annotation.SuppressLint;

import org.luckypray.dexkit.DexKitBridge;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import ps.reso.instaeclipse.utils.log.StartupTrace;

public final class ModuleBridge {

    public static final String WAIT = "dexkit.wait";
    public static final String STAMP = "q__bridgeAtStartup";
    public static final String LOAD = "load";
    public static final String ATTACH = "attach";
    public static final String INSTALL = "install";
    public static final String RUNTIME = "runtime";
    static final String THREAD = "IE-DexKit";
    static final String LIB = "/libdexkit.so";

    interface Factory {
        void load(String libPath);

        DexKitBridge create(String apkPath);
    }

    static final Factory NATIVE = new Factory() {
        @SuppressLint("UnsafeDynamicallyLoadedCode")
        @Override
        public void load(String libPath) {
            System.load(libPath);
        }

        @Override
        public DexKitBridge create(String apkPath) {
            return DexKitBridge.create(apkPath);
        }
    };

    private static final Object LOCK = new Object();
    private static final Object STAMP_LOCK = new Object();
    private static final Set<String> REQUESTERS = new LinkedHashSet<>();
    private static Factory factory = NATIVE;
    private static String libDir;
    private static String apkPath;
    private static boolean started;
    private static String startedBy;
    private static volatile boolean done;
    private static volatile DexKitBridge bridge;
    private static volatile Throwable failure;
    private static volatile boolean needed;
    private static boolean runtimeNoted;
    private static int generation;

    private ModuleBridge() {}

    public static void prepare(String moduleLibDir, String hostApkPath) {
        synchronized (LOCK) {
            libDir = moduleLibDir;
            apkPath = hostApkPath;
        }
    }

    public static void start(String moduleLibDir, String hostApkPath) {
        prepare(moduleLibDir, hostApkPath);
        start(LOAD);
    }

    public static boolean start(String reason) {
        return begin(reason, true);
    }

    public static boolean shouldStart(boolean legacyWarmbridge, String stamp, boolean scheduleNeedsBridge) {
        return legacyWarmbridge || stamp == null || "1".equals(stamp) || scheduleNeedsBridge;
    }

    public static String stamp() {
        return DexKitCache.loadSaved(STAMP);
    }

    public static DexKitBridge get() {
        String segment = DexKitStats.segment();
        boolean install = !DexKitStats.NONE.equals(segment);
        if (!install && DexKitStats.onMainThread()) return request();
        if (install) noteInstall(segment);
        else noteRuntime();
        if (done) return bridge;
        if (!begin(install ? INSTALL : RUNTIME, true)) return null;
        return await();
    }

    public static DexKitBridge request() {
        noteRuntime();
        if (done) return bridge;
        begin(RUNTIME, false);
        return done ? bridge : null;
    }

    public static void saveStamp() {
        synchronized (STAMP_LOCK) {
            String value = needed ? "1" : "0";
            if (!value.equals(DexKitCache.loadSaved(STAMP))) DexKitCache.saveString(STAMP, value);
        }
    }

    public static List<String> requesters() {
        synchronized (LOCK) {
            return new ArrayList<>(REQUESTERS);
        }
    }

    public static boolean started() {
        synchronized (LOCK) {
            return started;
        }
    }

    public static String startedBy() {
        synchronized (LOCK) {
            return startedBy;
        }
    }

    public static boolean needed() {
        return needed;
    }

    public static DexKitBridge peek() {
        return bridge;
    }

    public static boolean failed() {
        return done && bridge == null;
    }

    public static Throwable error() {
        return failure;
    }

    public static String unavailableLine(int features) {
        return unavailableLine(failure, features);
    }

    public static String unavailableDetail() {
        return unavailableDetail(failure);
    }

    static String unavailableLine(Throwable error, int features) {
        String reason = error == null ? "the code search library did not start"
                : error.getClass().getSimpleName() + ": " + error.getMessage();
        return "(IE|DexKit) Instagram's code can't be searched on this device (" + reason + "); "
                + features + (features == 1 ? " feature that needs it stays off" : " features that need it stay off");
    }

    static String unavailableDetail(Throwable error) {
        String message = error == null || error.getMessage() == null ? "" : error.getMessage().toLowerCase(Locale.ROOT);
        if (!message.contains("align") && !message.contains("page size") && !message.contains("page-size")
                && !message.contains("pagesize")) return null;
        return "(IE|DexKit) The library was rejected over memory page size or alignment: this phone probably uses "
                + "16 KB pages, which this build of libdexkit.so does not support";
    }

    public static void useForTest(DexKitBridge testBridge) {
        int gen;
        synchronized (LOCK) {
            factory = NATIVE;
            started = true;
            startedBy = "test";
            gen = generation;
        }
        finish(testBridge, null, gen);
    }

    public static void failForTest(Error loadError) {
        resetForTest(new Factory() {
            @Override
            public void load(String libPath) {
                throw loadError;
            }

            @Override
            public DexKitBridge create(String apkPath) {
                throw loadError;
            }
        });
    }

    public static void resetForTest() {
        resetForTest(NATIVE);
    }

    static void resetForTest(Factory testFactory) {
        synchronized (LOCK) {
            generation++;
            factory = testFactory;
            libDir = null;
            apkPath = null;
            started = false;
            startedBy = null;
            done = false;
            bridge = null;
            failure = null;
            REQUESTERS.clear();
        }
        synchronized (STAMP_LOCK) {
            needed = false;
            runtimeNoted = false;
        }
    }

    private static void noteInstall(String segment) {
        String step = DexKitStats.currentStep();
        synchronized (LOCK) {
            if (!DexKitStats.NONE.equals(step)) REQUESTERS.add(step);
        }
        if (!DexKitStats.BACKGROUND.equals(segment)) needed = true;
    }

    private static void noteRuntime() {
        synchronized (STAMP_LOCK) {
            needed = true;
            if (runtimeNoted) return;
            runtimeNoted = true;
            if (!"1".equals(DexKitCache.loadSaved(STAMP))) DexKitCache.saveString(STAMP, "1");
        }
    }

    private static boolean begin(String reason, boolean allowSync) {
        Factory f;
        String lib;
        String apk;
        int gen;
        synchronized (LOCK) {
            if (started) return true;
            if (apkPath == null) return false;
            started = true;
            startedBy = reason;
            f = factory;
            lib = libDir;
            apk = apkPath;
            gen = generation;
        }
        if (allowSync && LegacyPaths.has(LegacyPaths.BRIDGE)) {
            create(f, lib, apk, gen);
            return true;
        }
        try {
            Thread t = new Thread(() -> create(f, lib, apk, gen), THREAD);
            t.setPriority(Thread.NORM_PRIORITY);
            t.start();
        } catch (Throwable e) {
            finish(null, e, gen);
        }
        return true;
    }

    private static DexKitBridge await() {
        if (done) return bridge;
        StartupTrace.Span span = StartupTrace.begin(WAIT);
        boolean interrupted = false;
        synchronized (LOCK) {
            while (!done) {
                try {
                    LOCK.wait();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        }
        StartupTrace.end(span);
        if (interrupted) Thread.currentThread().interrupt();
        return bridge;
    }

    private static void create(Factory f, String lib, String apk, int gen) {
        StartupTrace.Span span = StartupTrace.begin(StartupTrace.DEXKIT);
        DexKitBridge created = null;
        Throwable error = null;
        try {
            f.load(lib + LIB);
            DexKitStats.libraryLoaded();
            created = f.create(apk);
            if (created == null) error = new IllegalStateException("DexKitBridge.create returned nothing");
            else DexKitStats.bridgeCreated();
        } catch (Throwable t) {
            error = t;
        } finally {
            StartupTrace.end(span);
        }
        finish(created, error, gen);
    }

    private static void finish(DexKitBridge created, Throwable error, int gen) {
        synchronized (LOCK) {
            if (gen != generation) return;
            bridge = created;
            failure = created == null ? error : null;
            done = true;
            LOCK.notifyAll();
        }
    }
}
