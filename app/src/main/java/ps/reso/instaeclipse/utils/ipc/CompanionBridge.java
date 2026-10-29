package ps.reso.instaeclipse.utils.ipc;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import android.view.View;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import ps.reso.instaeclipse.BuildConfig;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.download.DownloadRequest;
import ps.reso.instaeclipse.utils.i18n.I18n;

public final class CompanionBridge {
    public static final String DESCRIPTOR = BuildConfig.APPLICATION_ID + ".CompanionBroker.v1";
    public static final int DOWNLOAD = IBinder.FIRST_CALL_TRANSACTION;
    public static final int LEGACY = DOWNLOAD + 1;
    public static final int ACTIVITY = DOWNLOAD + 2;
    public static final String SERVICE = "ps.reso.instaeclipse.mods.media.DownloadBrokerService";
    static final byte[][] HOST_SIGNERS = {
            hex("5f3e50f435583c9ae626302a71f7340044087a7e2c60adacfc254205a993e305"),
            hex("3a10c50c18ba937506c2875e9047be74f8fd8b86fae2ff9feaa50a81fcb4c014")
    };
    private static final int CONFIG_MAX_BYTES = 64 * 1024;
    static final String UNREACHABLE = "unreachable", TIMEOUT = "timeout", BUSY = "busy", DENIED = "denied",
            FAILED = "failed", INVALID = "invalid", NOT_VISIBLE = "not_visible", LAUNCH_FAILED = "launch_failed";
    private static final ThreadPoolExecutor IPC = ipcPool();
    private CompanionBridge() { }

    static ThreadPoolExecutor ipcPool() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    public static boolean isUnreachable(String code) {
        return UNREACHABLE.equals(code) || TIMEOUT.equals(code) || BUSY.equals(code);
    }

    public static String message(Context context, String code) {
        int text = messageText(code);
        return text == 0 ? code : I18n.t(context, text);
    }

    static int messageText(String code) {
        if (code == null) return 0;
        switch (code) {
            case UNREACHABLE: return R.string.ie_companion_unreachable;
            case TIMEOUT: return R.string.ie_companion_timeout;
            case BUSY: return R.string.ie_companion_busy;
            case DENIED: return R.string.ie_companion_denied;
            case FAILED: return R.string.ie_companion_failed;
            case INVALID: return R.string.ie_companion_invalid;
            case NOT_VISIBLE: return R.string.ie_companion_not_visible;
            case LAUNCH_FAILED: return R.string.ie_companion_launch_failed;
            default: return 0;
        }
    }

    public interface Callback { void onResult(String error); }
    private interface Writer { void write(Parcel parcel); }
    private static final class Main { static final Handler HANDLER = new Handler(Looper.getMainLooper()); }

    public static boolean isTrustedHost(Context context, String packageName) {
        if (context == null || !BrokerPayload.HOST.equals(packageName)) return false;
        try {
            PackageManager pm = context.getPackageManager();
            for (byte[] pin : HOST_SIGNERS)
                if (pm.hasSigningCertificate(packageName, pin, PackageManager.CERT_INPUT_SHA256)) return true;
            return lspatchOriginalSigner(context, packageName);
        } catch (Throwable ignored) { return false; }
    }

    private static volatile String lspatchCheckedFor;
    private static volatile boolean lspatchTrusted;

    private static boolean lspatchOriginalSigner(Context context, String packageName) {
        try {
            android.content.pm.PackageInfo info = context.getPackageManager().getPackageInfo(packageName, 0);
            String key = info.applicationInfo.sourceDir + "@" + info.lastUpdateTime;
            if (key.equals(lspatchCheckedFor)) return lspatchTrusted;
            boolean trusted = false;
            try (java.util.zip.ZipFile apk = new java.util.zip.ZipFile(info.applicationInfo.sourceDir)) {
                java.util.zip.ZipEntry entry = apk.getEntry("assets/lspatch/config.json");
                if (entry != null && entry.getSize() > 0 && entry.getSize() < CONFIG_MAX_BYTES) {
                    byte[] raw;
                    try (InputStream in = apk.getInputStream(entry)) { raw = readBounded(in, CONFIG_MAX_BYTES); }
                    String cert = new org.json.JSONObject(new String(raw, java.nio.charset.StandardCharsets.UTF_8))
                            .optString("originalSignature", "");
                    if (!cert.isEmpty() && cert.length() % 2 == 0 && cert.matches("[0-9a-fA-F]+"))
                        trusted = matchesPin(hex(cert), HOST_SIGNERS);
                }
            }
            lspatchTrusted = trusted;
            lspatchCheckedFor = key;
            return trusted;
        } catch (Throwable ignored) { return false; }
    }

    static byte[] readBounded(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int read; (read = in.read(buffer)) != -1; ) {
            if (out.size() + read > max) throw new IOException();
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    static boolean matchesPin(byte[] der, byte[][] pins) {
        if (der == null) return false;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(der);
            for (byte[] pin : pins) if (MessageDigest.isEqual(pin, digest)) return true;
            return false;
        } catch (GeneralSecurityException missing) { return false; }
    }

    private static byte[] hex(String value) {
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++)
            out[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return out;
    }

    public static boolean isTrustedCaller(Context context, int uid) {
        if (uid == Process.myUid()) return true;
        try {
            PackageManager pm = context.getPackageManager();
            String[] packages = pm.getPackagesForUid(uid);
            return packages != null && packages.length == 1 && BrokerPayload.HOST.equals(packages[0])
                    && pm.getApplicationInfo(BrokerPayload.HOST, 0).uid == uid
                    && isTrustedHost(context, BrokerPayload.HOST);
        } catch (Throwable ignored) { return false; }
    }

    public static void download(Context context, DownloadRequest request, Callback callback) {
        try {
            String json = BrokerPayload.request(request.toJson()).toJson();
            begin(context, DOWNLOAD, parcel -> parcel.writeString(json), callback);
        } catch (RuntimeException invalid) { report(callback, INVALID); }
    }

    public static void saveFrame(Context context, DownloadRequest request, Callback callback) {
        try {
            Map<String, String> values = new LinkedHashMap<>();
            values.put(BrokerPayload.FRAME_REQUEST, request.toJson());
            openActivity(context, BrokerPayload.FRAME, values, callback);
        } catch (RuntimeException invalid) { report(callback, INVALID); }
    }

    public static void downloadLegacy(Context context, String url, String audioUrl, String filename,
            String mime, String owner, Callback callback) {
        Intent legacy = new Intent().putExtra("url", url).putExtra("audioUrl", audioUrl)
                .putExtra("filename", filename).putExtra("mimeType", mime).putExtra("username", owner);
        downloadLegacy(context, legacy, callback);
    }

    public static void downloadLegacy(Context context, Intent intent, Callback callback) {
        try {
            Bundle extras = LegacyBrokerPayload.validate(intent == null ? null : intent.getExtras());
            begin(context, LEGACY, parcel -> parcel.writeBundle(extras), callback);
        } catch (RuntimeException invalid) { report(callback, INVALID); }
    }

    public static boolean launchActivity(Context context, Intent intent) {
        try {
            ComponentName component = intent.getComponent();
            if (component == null || !BuildConfig.APPLICATION_ID.equals(component.getPackageName()))
                throw new IllegalArgumentException();
            String name = component.getClassName();
            Map<String, String> extras = new LinkedHashMap<>();
            Bundle values = intent.getExtras();
            if (values != null) {
                if (values.size() > 4) throw new IllegalArgumentException();
                for (String key : values.keySet()) {
                    Object value = values.get(key);
                    if (BrokerPayload.LOCATION.equals(name) && (key.equals("lat") || key.equals("lng"))
                            && value instanceof Double) extras.put(key, Double.toString((Double) value));
                    else if (value instanceof String) extras.put(key, (String) value);
                    else throw new IllegalArgumentException();
                }
            }
            openActivity(context, name, extras, error -> { if (error != null) errorToast(context, error); });
            return true;
        } catch (RuntimeException invalid) { errorToast(context, INVALID); return false; }
    }

    public static void openActivity(Context context, String name, Map<String, String> values, Callback callback) {
        try {
            Map<String, String> extras = BrokerPayload.activity(name, values);
            begin(context, ACTIVITY, parcel -> {
                parcel.writeString(name); parcel.writeInt(extras.size());
                for (Map.Entry<String, String> extra : extras.entrySet()) {
                    parcel.writeString(extra.getKey()); parcel.writeString(extra.getValue());
                }
            }, callback);
        } catch (RuntimeException invalid) { report(callback, INVALID); }
    }

    private static void begin(Context context, int code, Writer writer, Callback callback) {
        if (context == null) { report(callback, UNREACHABLE); return; }
        Main.HANDLER.post(() -> new Operation(context, code, writer, callback).start());
    }
    private static void report(Callback callback, String error) {
        Main.HANDLER.post(() -> { if (callback != null) callback.onResult(error); });
    }
    private static void errorToast(Context context, String error) {
        if (context == null) return;
        Main.HANDLER.post(() -> ps.reso.instaeclipse.utils.toast.IeToast.makeText(context, message(context, error), Toast.LENGTH_LONG).show());
    }

    private static Activity activity(Context context) {
        for (int depth = 0; context != null && depth < 12; depth++) {
            if (context instanceof Activity) return (Activity) context;
            if (!(context instanceof ContextWrapper)) break;
            Context next = ((ContextWrapper) context).getBaseContext();
            if (next == context) break;
            context = next;
        }
        return null;
    }
    private static boolean visible(Activity activity) {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed()
                && activity.getWindow() != null
                && activity.getWindow().getDecorView().getWindowVisibility() == View.VISIBLE;
    }

    private static final class Operation implements ServiceConnection {
        final Context caller, app;
        final int code;
        final Writer writer;
        final Callback callback;
        final AtomicBoolean complete = new AtomicBoolean();
        final Runnable timeout = () -> {
            ps.reso.instaeclipse.utils.log.ModuleLog.warn("(IE|Companion) The InstaEclipse app did not answer within 10 seconds (it may be frozen or starting slowly)");
            finish(null, TIMEOUT);
        };
        boolean bindAttempted;
        int expectedUid;
        Operation(Context context, int code, Writer writer, Callback callback) {
            caller = context; app = context.getApplicationContext() == null ? context : context.getApplicationContext();
            this.code = code; this.writer = writer; this.callback = callback;
        }
        void start() {
            if (code == ACTIVITY && !visible(activity(caller))) { finish(null, NOT_VISIBLE); return; }
            Main.HANDLER.postDelayed(timeout, 10_000);
            try {
                expectedUid = app.getPackageManager().getApplicationInfo(BuildConfig.APPLICATION_ID, 0).uid;
            } catch (Exception missing) {
                unreachable("not installed for this Instagram (Dual Messenger / Secure Folder / work profile?) " + missing);
                return;
            }
            try {
                bindAttempted = true;
                boolean bound = app.bindService(new Intent().setClassName(BuildConfig.APPLICATION_ID, SERVICE), this, Context.BIND_AUTO_CREATE);
                if (!bound) unreachable("start refused by the system (sleeping app / autostart / battery limits)");
            } catch (Exception error) { unreachable("bind failed: " + error); }
        }
        void unreachable(String why) {
            ps.reso.instaeclipse.utils.log.ModuleLog.warn("(IE|Companion) Could not reach the InstaEclipse app: " + why);
            finish(null, UNREACHABLE);
        }
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (complete.get()) return;
            try { IPC.execute(() -> transact(binder)); }
            catch (RuntimeException busy) { finish(null, BUSY); }
        }
        void transact(IBinder binder) {
            Parcel data = Parcel.obtain(), reply = Parcel.obtain();
            PendingIntent token = null;
            try {
                if (complete.get()) return;
                data.writeInterfaceToken(DESCRIPTOR); writer.write(data);
                if (data.dataSize() > BrokerPayload.MAX_PARCEL_BYTES) throw new IllegalArgumentException();
                if (!binder.transact(code, data, reply, 0)) throw new IllegalStateException();
                reply.readException();
                token = PendingIntent.CREATOR.createFromParcel(reply);
                if (reply.dataAvail() != 0 || token == null || !BuildConfig.APPLICATION_ID.equals(token.getCreatorPackage())
                        || token.getCreatorUid() != expectedUid
                        || (Build.VERSION.SDK_INT >= 31 && (!token.isImmutable()
                            || (code == ACTIVITY ? !token.isActivity() : !token.isForegroundService())))) throw new SecurityException();
                finish(token, null);
            } catch (SecurityException denied) {
                if (token != null) token.cancel();
                finish(null, DENIED);
            } catch (Exception failure) {
                if (token != null) token.cancel();
                finish(null, FAILED);
            } finally { data.recycle(); reply.recycle(); }
        }
        void finish(PendingIntent token, String error) {
            Main.HANDLER.post(() -> {
                if (!complete.compareAndSet(false, true)) { if (token != null) token.cancel(); return; }
                Main.HANDLER.removeCallbacks(timeout);
                if (bindAttempted) { try { app.unbindService(this); } catch (IllegalArgumentException ignored) { } bindAttempted = false; }
                String result = error;
                if (token != null) {
                    try {
                        if (code == ACTIVITY && !visible(activity(caller))) throw new IllegalStateException();
                        Bundle options = null;
                        if (code == ACTIVITY && Build.VERSION.SDK_INT >= 34) {
                            ActivityOptions launch = ActivityOptions.makeBasic();
                            launch.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                            options = launch.toBundle();
                        }
                        token.send(caller, 0, null, null, null, null, options);
                    } catch (Exception failure) { token.cancel(); result = LAUNCH_FAILED; }
                }
                if (callback != null) callback.onResult(result);
            });
        }
        @Override public void onServiceDisconnected(ComponentName name) { finish(null, UNREACHABLE); }
        @Override public void onBindingDied(ComponentName name) { finish(null, UNREACHABLE); }
        @Override public void onNullBinding(ComponentName name) { unreachable("the app returned no connection (outdated or disabled service?)"); }
    }
}
