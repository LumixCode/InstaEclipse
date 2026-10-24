package ps.reso.instaeclipse.utils.core;

import android.annotation.SuppressLint;

import org.luckypray.dexkit.DexKitBridge;

import java.util.Locale;

import ps.reso.instaeclipse.Xposed.Module;
import ps.reso.instaeclipse.utils.log.StartupTrace;

public final class ModuleBridge {

    public static final String WAIT = "dexkit.wait";
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
    private static Factory factory = NATIVE;
    private static boolean started;
    private static volatile boolean done;
    private static volatile DexKitBridge bridge;
    private static volatile Throwable failure;

    private ModuleBridge() {}

    public static void start(String libDir, String apkPath) {
        Factory f;
        synchronized (LOCK) {
            if (started) return;
            started = true;
            f = factory;
        }
        if (LegacyPaths.has(LegacyPaths.BRIDGE)) {
            create(f, libDir, apkPath);
            return;
        }
        try {
            Thread t = new Thread(() -> create(f, libDir, apkPath), THREAD);
            t.setPriority(Thread.NORM_PRIORITY);
            t.start();
        } catch (Throwable e) {
            finish(null, e);
        }
    }

    public static DexKitBridge get() {
        if (done) return bridge;
        synchronized (LOCK) {
            if (!started) return null;
        }
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
        synchronized (LOCK) {
            factory = NATIVE;
            started = true;
        }
        finish(testBridge, null);
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
            factory = testFactory;
            started = false;
            done = false;
            bridge = null;
            failure = null;
            Module.dexKitBridge = null;
        }
    }

    private static void create(Factory f, String libDir, String apkPath) {
        StartupTrace.Span span = StartupTrace.begin(StartupTrace.DEXKIT);
        DexKitBridge created = null;
        Throwable error = null;
        try {
            f.load(libDir + LIB);
            DexKitStats.libraryLoaded();
            created = f.create(apkPath);
            if (created == null) error = new IllegalStateException("DexKitBridge.create returned nothing");
            else DexKitStats.bridgeCreated();
        } catch (Throwable t) {
            error = t;
        } finally {
            StartupTrace.end(span);
        }
        finish(created, error);
    }

    private static void finish(DexKitBridge created, Throwable error) {
        synchronized (LOCK) {
            bridge = created;
            failure = created == null ? error : null;
            if (created != null) Module.dexKitBridge = created;
            done = true;
            LOCK.notifyAll();
        }
    }
}
