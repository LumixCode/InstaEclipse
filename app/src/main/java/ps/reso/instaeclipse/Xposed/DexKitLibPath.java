package ps.reso.instaeclipse.Xposed;

import java.io.File;
import java.util.function.Predicate;

public final class DexKitLibPath {

    static final String LIB = "libdexkit.so";

    private DexKitLibPath() {}

    public static String choose(String apkPath, String frameworkDir, boolean is64Bit, String[] abis64,
                                String[] abis32, Predicate<File> hasLib) {
        if (frameworkDir != null && usable(frameworkDir, is64Bit, hasLib)) return frameworkDir;
        String libRoot = apkPath.substring(0, apkPath.lastIndexOf('/')) + "/lib/";
        for (String abi : is64Bit ? abis64 : abis32) {
            String dir = libRoot + folder(abi);
            if (hasLib.test(new File(dir))) return dir;
        }
        String first = abis64.length > 0 ? abis64[0] : abis32.length > 0 ? abis32[0] : "";
        return libRoot + folder(first);
    }

    public static boolean usable(String dir, boolean is64Bit, Predicate<File> hasLib) {
        return hasLib.test(new File(dir)) && fits(dir, is64Bit);
    }

    static boolean fits(String dir, boolean is64Bit) {
        String name = new File(dir).getName();
        if (name.equals("arm64") || name.equals("x86_64")) return is64Bit;
        if (name.equals("arm") || name.equals("x86")) return !is64Bit;
        return true;
    }

    static String folder(String abi) {
        if (abi.equalsIgnoreCase("arm64-v8a")) return "arm64";
        if (abi.equalsIgnoreCase("armeabi-v7a") || abi.equalsIgnoreCase("armeabi") || abi.equalsIgnoreCase("armv8i"))
            return "arm";
        if (abi.equalsIgnoreCase("x86")) return "x86";
        if (abi.equalsIgnoreCase("x86_64")) return "x86_64";
        return abi;
    }
}
