package ps.reso.instaeclipse.mods.location;

import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.SystemClock;

import java.lang.reflect.Method;
import java.util.Random;
import java.util.concurrent.Executor;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public class LocationSpoofHook {

    private static final String TAG = "(InstaEclipse | SpoofLocation): ";
    private static volatile boolean sHooked = false;
    private static final Random RANDOM = new Random();
    private static final LocationDelivery.Nesting NESTING = new LocationDelivery.Nesting();

    public void install(ClassLoader classLoader) {
        if (sHooked) return;
        synchronized (LocationSpoofHook.class) {
            if (sHooked) return;
            sHooked = true;
            try {
                hookUnparcel();
                hookGetters();
                hookManager();
                hookPermissions();
                hookNearbySignals();
                FeatureStatusTracker.setHooked("SpoofLocation");
                ModuleLog.ok(TAG + "ready");
                ModuleLog.detail(TAG + "Hooked (parcel + getters + LocationManager + permission + nearby signals)");
            } catch (Throwable t) {
                ModuleLog.error(TAG + "Could not turn on location spoofing", t);
            }
        }
    }

    static boolean spoofing() {
        return FeatureFlags.spoofLocation && LocationPresets.valid(FeatureFlags.spoofLat, FeatureFlags.spoofLng);
    }

    private static void hookUnparcel() throws NoSuchMethodException {
        Method create = Location.CREATOR.getClass().getDeclaredMethod("createFromParcel", Parcel.class);
        XposedBridge.hookMethod(create, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (spoofing() && param.getResult() instanceof Location loc
                        && LocationDelivery.rewritesUnparcelled(loc.getProvider(), isSpoofed(loc))) apply(loc);
            }
        });
    }

    private static void hookGetters() throws NoSuchMethodException {
        XC_MethodHook lazy = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!spoofing() || !(param.thisObject instanceof Location)) return;
                Location loc = (Location) param.thisObject;
                if (isDeviceFix(loc) && !isSpoofed(loc)) apply(loc);
            }
        };
        XposedBridge.hookMethod(Location.class.getDeclaredMethod("getLatitude"), lazy);
        XposedBridge.hookMethod(Location.class.getDeclaredMethod("getLongitude"), lazy);

        XC_MethodHook ACTIVE_ONLY_FALSE = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (spoofing()) param.setResult(false);
            }
        };

        XposedBridge.hookMethod(Location.class.getDeclaredMethod("isFromMockProvider"), ACTIVE_ONLY_FALSE);
        try {
            XposedBridge.hookMethod(Location.class.getDeclaredMethod("isMock"), ACTIVE_ONLY_FALSE);
        } catch (NoSuchMethodException ignored) {}
    }

    private static void hookManager() {
        XC_MethodHook request = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                NESTING.enter();
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                boolean outermost = NESTING.exit();
                if (!spoofing()) return;
                if (param.getThrowable() instanceof SecurityException) param.setResult(null);
                if (!outermost) return;
                LocationListener listener = findListener(param.args);
                if (listener != null) deliver(param.args, listener);
            }
        };
        for (Method m : ps.reso.instaeclipse.utils.core.SafeReflect.methods(LocationManager.class)) {
            switch (m.getName()) {
                case "getLastKnownLocation":
                case "getLastLocation":
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!spoofing()) return;
                            if (param.getThrowable() instanceof SecurityException || param.getResult() == null) {
                                param.setResult(fakeLocation(LocationManager.GPS_PROVIDER));
                            }
                        }
                    });
                    break;
                case "requestLocationUpdates":
                case "requestSingleUpdate":
                    XposedBridge.hookMethod(m, request);
                    break;
                case "getCurrentLocation":
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @SuppressWarnings("unchecked")
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!spoofing()) return;
                            java.util.concurrent.Executor executor = null;
                            java.util.function.Consumer<Location> consumer = null;
                            for (Object a : param.args) {
                                if (a instanceof java.util.concurrent.Executor) executor = (java.util.concurrent.Executor) a;
                                if (a instanceof java.util.function.Consumer) consumer = (java.util.function.Consumer<Location>) a;
                            }
                            if (executor == null || consumer == null) return;
                            java.util.function.Consumer<Location> target = consumer;
                            Location fix = fakeLocation(LocationManager.GPS_PROVIDER);
                            executor.execute(() -> target.accept(fix));
                            param.setResult(null);
                        }
                    });
                    break;
                case "isProviderEnabled":
                case "isLocationEnabled":
                case "isLocationEnabledForUser":
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (spoofing() && m.getReturnType() == boolean.class) param.setResult(true);
                        }
                    });
                    break;
                default:
                    break;
            }
        }
    }

    private static void hookPermissions() {
        try {
            Class<?> impl = Class.forName("android.app.ContextImpl");
            XC_MethodHook grant = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!spoofing() || param.args.length == 0 || !(param.args[0] instanceof String)) return;
                    String perm = (String) param.args[0];
                    if (!perm.equals(android.Manifest.permission.ACCESS_FINE_LOCATION)
                            && !perm.equals(android.Manifest.permission.ACCESS_COARSE_LOCATION)) return;
                    if (param.args.length >= 3 && param.args[2] instanceof Integer
                            && (Integer) param.args[2] != android.os.Process.myUid()) return;
                    param.setResult(android.content.pm.PackageManager.PERMISSION_GRANTED);
                }
            };
            for (Method m : ps.reso.instaeclipse.utils.core.SafeReflect.methods(impl)) {
                String n = m.getName();
                if ((n.equals("checkPermission") || n.equals("checkSelfPermission") || n.equals("checkCallingOrSelfPermission"))
                        && m.getReturnType() == int.class) {
                    XposedBridge.hookMethod(m, grant);
                }
            }
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "Instagram cannot be told it has location access; spoofing still works once Instagram has real location access: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static final String[] CELL_ID_GETTERS = {"getCi", "getCid", "getNci", "getLac", "getTac", "getPci",
            "getPsc", "getCpid", "getBasestationId", "getNetworkId", "getSystemId", "getLatitude", "getLongitude"};
    private static final String[] CELL_IDENTITIES = {"android.telephony.CellIdentityGsm",
            "android.telephony.CellIdentityWcdma", "android.telephony.CellIdentityTdscdma",
            "android.telephony.CellIdentityLte", "android.telephony.CellIdentityNr",
            "android.telephony.CellIdentityCdma"};

    private static void hookNearbySignals() {
      try {
        XC_MethodHook emptyList = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (spoofing()) param.setResult(new java.util.ArrayList<>());
            }
        };
        hookNamed(android.net.wifi.WifiManager.class, "getScanResults", java.util.List.class, emptyList);
        hookNamed(android.net.wifi.WifiInfo.class, "getBSSID", String.class, constant("02:00:00:00:00:00"));
        hookNamed(android.net.wifi.WifiInfo.class, "getSSID", String.class, constant("<unknown ssid>"));
        hookNamed(android.telephony.TelephonyManager.class, "getAllCellInfo", java.util.List.class, emptyList);
        hookNamed(android.telephony.TelephonyManager.class, "getCellLocation", android.telephony.CellLocation.class, constant(null));

        XC_MethodHook unavailable = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!spoofing() || !(param.method instanceof Method)) return;
                param.setResult(((Method) param.method).getReturnType() == long.class ? (Object) Long.MAX_VALUE : Integer.MAX_VALUE);
            }
        };
        for (String name : CELL_IDENTITIES) {
            try {
                Class<?> c = Class.forName(name);
                Method[] methods = ps.reso.instaeclipse.utils.core.SafeReflect.methods(c);
                for (String getter : CELL_ID_GETTERS) {
                    hookNamed(c, methods, getter, int.class, unavailable);
                    hookNamed(c, methods, getter, long.class, unavailable);
                }
            } catch (Throwable ignored) {}
        }

        hookNamed(android.bluetooth.le.BluetoothLeScanner.class, "startScan", void.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (spoofing()) param.setResult(null);
            }
        });
      } catch (Throwable t) {
        ModuleLog.warn(TAG + "Nearby Wi-Fi, cell and Bluetooth info cannot be hidden, so Instagram may still see your real area: "
                + t.getClass().getSimpleName() + ": " + t.getMessage());
      }
    }

    private static XC_MethodHook constant(Object value) {
        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (spoofing()) param.setResult(value);
            }
        };
    }

    private static void hookNamed(Class<?> c, String name, Class<?> type, XC_MethodHook hook) {
        hookNamed(c, ps.reso.instaeclipse.utils.core.SafeReflect.methods(c), name, type, hook);
    }

    private static void hookNamed(Class<?> c, Method[] methods, String name, Class<?> type, XC_MethodHook hook) {
        for (Method m : methods) {
            if (!m.getName().equals(name) || m.getReturnType() != type
                    || java.lang.reflect.Modifier.isAbstract(m.getModifiers())) continue;
            try {
                XposedBridge.hookMethod(m, hook);
            } catch (Throwable t) {
                ModuleLog.warn(TAG + "One source of nearby Wi-Fi, cell or Bluetooth info cannot be hidden; the others still are");
                ModuleLog.detail(TAG + "nearby-signal hook failed: " + c.getSimpleName() + "." + name + ": " + t);
            }
        }
    }

    private static void deliver(Object[] args, LocationListener listener) {
        Location fix = fakeLocation(LocationManager.GPS_PROVIDER);
        Runnable send = () -> {
            try {
                listener.onLocationChanged(fix);
            } catch (Throwable ignored) {}
        };
        try {
            Object target = LocationDelivery.target(args, Looper.class, Looper.myLooper(), Looper.getMainLooper());
            if (target instanceof Executor executor) executor.execute(send);
            else if (target instanceof Looper looper) new Handler(looper).post(send);
        } catch (Throwable t) {
            ModuleLog.detail(TAG + "could not hand out the spoofed fix: " + t);
        }
    }

    private static LocationListener findListener(Object[] args) {
        if (args == null) return null;
        for (Object a : args) {
            if (a instanceof LocationListener) return (LocationListener) a;
        }
        return null;
    }

    static boolean isDeviceFix(Location loc) {
        String p = loc.getProvider();
        return p != null && (p.equals(LocationManager.GPS_PROVIDER) || p.equals(LocationManager.NETWORK_PROVIDER)
                || p.equals(LocationManager.PASSIVE_PROVIDER) || p.equals("fused"));
    }

    static final String MARK = "ie_spoofed";

    private static boolean isSpoofed(Location loc) {
        android.os.Bundle extras = loc.getExtras();
        return extras != null && extras.getBoolean(MARK, false);
    }

    static void apply(Location loc) {
        double[] p = LocationPresets.jitter(FeatureFlags.spoofLat, FeatureFlags.spoofLng, RANDOM.nextDouble(), RANDOM.nextDouble());
        loc.setLatitude(p[0]);
        loc.setLongitude(p[1]);
        loc.setAccuracy(6f + RANDOM.nextFloat() * 10f);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) loc.setMock(false);
        android.os.Bundle extras = loc.getExtras() != null ? new android.os.Bundle(loc.getExtras()) : new android.os.Bundle();
        extras.remove("mockLocation");
        extras.putBoolean(MARK, true);
        loc.setExtras(extras);
    }

    static Location fakeLocation(String provider) {
        Location loc = new Location(provider);
        loc.setTime(System.currentTimeMillis());
        loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        loc.setAltitude(40.0 + RANDOM.nextDouble() * 20.0);
        apply(loc);
        return loc;
    }

}
