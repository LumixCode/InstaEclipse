package ps.reso.instaeclipse.utils.core;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.pm.Signature;
import android.os.Build;
import android.os.SystemClock;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

public final class IpcSecurity {

    public static final String PERMISSION = "ps.reso.instaeclipse.permission.MODULE_IPC";
    public static final String EXTRA_NONCE = "ie_nonce";

    static final long PERMISSION_MEMO_MS = 30_000L;

    private static final SecureRandom RNG = new SecureRandom();
    private static final Memo COMPANION_PERMISSION = new Memo(PERMISSION_MEMO_MS);
    static volatile LongSupplier clock = SystemClock::elapsedRealtime;

    private IpcSecurity() {}

    public static void registerCompanionOnlyReceiver(Context context, BroadcastReceiver receiver,
                                                     IntentFilter filter) {
        if (!isCompanionPermission(context)) {
            ps.reso.instaeclipse.utils.log.ModuleLog.warn(
                    "(IE|Ipc) Changes made in the InstaEclipse app cannot reach Instagram while it runs: the app is missing or another app claims its permission");
            ps.reso.instaeclipse.utils.log.ModuleLog.detail(
                    "(IE|Ipc) companion receiver not registered: " + PERMISSION + " is not the companion's");
            return;
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, PERMISSION, null, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter, PERMISSION, null);
        }
    }

    private static volatile Signature[] moduleSigners;

    public static boolean isCompanionPermission(Context context) {
        return COMPANION_PERMISSION.get(clock.getAsLong(), () -> checkCompanionPermission(context));
    }

    static void resetForTest() {
        COMPANION_PERMISSION.clear();
        clock = SystemClock::elapsedRealtime;
    }

    static final class Memo {
        private final long ttlMs;
        private boolean cached;
        private boolean value;
        private long at;

        Memo(long ttlMs) {
            this.ttlMs = ttlMs;
        }

        synchronized boolean get(long now, BooleanSupplier compute) {
            if (cached && now >= at && now - at < ttlMs) return value;
            value = compute.getAsBoolean();
            at = now;
            cached = true;
            return value;
        }

        synchronized void clear() {
            cached = false;
        }
    }

    private static boolean checkCompanionPermission(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            PermissionInfo permission = pm.getPermissionInfo(PERMISSION, 0);
            if (!CommonUtils.MY_PACKAGE_NAME.equals(permission.packageName)
                    || permission.getProtection() != PermissionInfo.PROTECTION_SIGNATURE) return false;
            String module = ps.reso.instaeclipse.xposedcompat.ModuleResources.apkPath();
            if (module == null) return false;
            if (module.equals(pm.getApplicationInfo(CommonUtils.MY_PACKAGE_NAME, 0).sourceDir)) return true;
            Signature[] signers = moduleSigners;
            if (signers == null) {
                PackageInfo archive = pm.getPackageArchiveInfo(module, PackageManager.GET_SIGNING_CERTIFICATES);
                if (archive == null || archive.signingInfo == null) return false;
                signers = archive.signingInfo.getApkContentsSigners();
                moduleSigners = signers;
            }
            if (signers == null || signers.length == 0) return false;
            for (Signature signer : signers) {
                if (!pm.hasSigningCertificate(CommonUtils.MY_PACKAGE_NAME, signer.toByteArray(),
                        PackageManager.CERT_INPUT_RAW_X509)) return false;
            }
            return true;
        } catch (Exception unverified) {
            return false;
        }
    }

    public static ps.reso.instaeclipse.utils.ipc.ModuleReplies.Pending requestReply(
            Context context, Intent request, String replyAction, BroadcastReceiver receiver) {
        Intent addressed = new Intent(request);
        if (addressed.getPackage() == null) addressed.setPackage(CommonUtils.IG_PACKAGE_NAME);
        return ps.reso.instaeclipse.utils.ipc.ModuleReplies.request(context, addressed,
                data -> receiver.onReceive(context, new Intent(replyAction).putExtras(data)));
    }

    public static String supportedCallerPackage(Context context, int callingUid) {
        if (context == null || callingUid == android.os.Process.myUid()) return null;
        return ps.reso.instaeclipse.utils.ipc.CompanionBridge.isTrustedCaller(context, callingUid)
                ? CommonUtils.IG_PACKAGE_NAME : null;
    }

    public static String newNonce() {
        byte[] b = new byte[16];
        RNG.nextBytes(b);
        StringBuilder sb = new StringBuilder(32);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    public static void echoNonce(Intent request, Intent reply) {
        String nonce = request.getStringExtra(EXTRA_NONCE);
        if (nonce != null) reply.putExtra(EXTRA_NONCE, nonce);
    }

    public static boolean nonceMatches(String expected, Intent reply) {
        if (expected == null || reply == null) return false;
        String got = reply.getStringExtra(EXTRA_NONCE);
        if (got == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                got.getBytes(StandardCharsets.UTF_8));
    }
}
