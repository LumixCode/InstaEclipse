package ps.reso.instaeclipse.mods.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.Xposed.Module;
import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;
import ps.reso.instaeclipse.utils.core.SettingsManager;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.Found;

public class CustomFontHook {

    public static final int FONT_PICK_REQUEST = 0x1F09;
    public static final int EMOJI_PICK_REQUEST = 0x1F0A;
    private static final String FONT_PREFIX = "ie_custom_font";
    private static final String EMOJI_PREFIX = "ie_custom_emoji";

    private static volatile Typeface userFont;
    private static volatile String loadedFrom;
    private static final ThreadLocal<Boolean> loading = ThreadLocal.withInitial(() -> Boolean.FALSE);

    public void install(ClassLoader cl) {
        installFontHooks(cl);
        if (FeatureFlags.customEmojiEnabled) installEmojiHook(cl);
        deleteSupersededFonts();
    }

    private static void deleteSupersededFonts() {
        final String font = FeatureFlags.customFontPath, emoji = FeatureFlags.customEmojiPath;
        if ((font == null || font.isEmpty()) && (emoji == null || emoji.isEmpty())) return;
        new Thread(() -> {
            try {
                int n = deleteSuperseded(FONT_PREFIX, font) + deleteSuperseded(EMOJI_PREFIX, emoji);
                if (n > 0) ModuleLog.detail("(IE|Font) deleted " + n + " replaced font file(s)");
            } catch (Throwable t) { ModuleLog.detail("(IE|Font) old font file cleanup failed: " + t.getClass().getSimpleName() + ": " + t.getMessage()); }
        }, "IE-FontCleanup").start();
    }

    static int deleteSuperseded(String prefix, String current) {
        if (current == null || current.isEmpty()) return 0;
        File keep = new File(current);
        File dir = keep.getParentFile();
        long written = keep.lastModified();
        if (dir == null || written == 0L) return 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        int n = 0;
        for (File f : files) {
            String name = f.getName();
            if (name.startsWith(prefix) && !name.equals(keep.getName()) && f.isFile()
                    && f.lastModified() < written && f.delete()) n++;
        }
        return n;
    }

    private void installFontHooks(ClassLoader cl) {
        boolean font = FeatureFlags.customFontEnabled && isFile(FeatureFlags.customFontPath);
        boolean emoji = FeatureFlags.customEmojiEnabled && isFile(FeatureFlags.customEmojiPath);
        if (FeatureFlags.customFontEnabled || FeatureFlags.customEmojiEnabled) installTypefaceHooks(font || emoji);
        installPickResultHook();
        if (font) FeatureStatusTracker.setHooked("CustomFont");
        ModuleLog.ok("(IE|Font) ready");
    }

    static boolean isFile(String path) {
        return path != null && !path.isEmpty() && new File(path).isFile();
    }

    @SuppressLint("DiscouragedApi")
    private static int fontId(String name) {
        Context ctx = Module.appContext();
        if (ctx == null) return 0;
        try {
            return ctx.getResources().getIdentifier(name, "font", ctx.getPackageName());
        } catch (Throwable t) {
            return 0;
        }
    }

    private void installTypefaceHooks(boolean setTypeface) {
        IconFontGuard iconFonts = IconFontGuard.resolve(CustomFontHook::fontId);
        XC_MethodHook replace = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (!(FeatureFlags.customFontEnabled || FeatureFlags.customEmojiEnabled)) return;
                if (Boolean.TRUE.equals(loading.get())) return;
                for (Object a : p.args) {
                    if (a instanceof String s) {
                        if (IconFontGuard.iconName(s)) return;
                    } else if (a instanceof File f) {
                        if (IconFontGuard.iconName(f.getName())) return;
                    }
                }
                Typeface tf = getActiveTypeface();
                if (tf != null) p.setResult(tf);
            }
        };
        try {
            XposedHelpers.findAndHookMethod(Typeface.class, "createFromAsset",
                    android.content.res.AssetManager.class, String.class, replace);
        } catch (Throwable ignored) {}
        try {
            XposedHelpers.findAndHookMethod(Typeface.class, "createFromFile", File.class, replace);
        } catch (Throwable ignored) {}
        try {
            XposedHelpers.findAndHookMethod(Typeface.class, "createFromFile", String.class, replace);
        } catch (Throwable ignored) {}

        XC_MethodHook setTf1 = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!(FeatureFlags.customFontEnabled || FeatureFlags.customEmojiEnabled) || Boolean.TRUE.equals(loading.get())) return;
                if (iconFonts.isIcon(p.args[0])) return;
                Typeface tf = getActiveTypeface();
                if (tf == null) return;
                Typeface swap = styledLike(tf, (Typeface) p.args[0]);
                if (p.args[0] != swap) p.args[0] = swap;
            }
        };
        XC_MethodHook setTf2 = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!(FeatureFlags.customFontEnabled || FeatureFlags.customEmojiEnabled) || Boolean.TRUE.equals(loading.get())) return;
                if (iconFonts.isIcon(p.args[0])) return;
                Typeface tf = getActiveTypeface();
                if (tf == null) return;
                Typeface swap = styledLike(tf, (Typeface) p.args[0]);
                if (p.args[0] != swap) p.args[0] = swap;
            }
        };
        if (setTypeface) {
            try {
                XposedHelpers.findAndHookMethod(android.widget.TextView.class, "setTypeface", Typeface.class, setTf1);
            } catch (Throwable ignored) {}
            try {
                XposedHelpers.findAndHookMethod(android.widget.TextView.class, "setTypeface", Typeface.class, int.class, setTf2);
            } catch (Throwable ignored) {}
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                try {
                    XposedHelpers.findAndHookConstructor(android.graphics.fonts.Font.Builder.class,
                            android.content.res.Resources.class, int.class, new XC_MethodHook() {
                                @Override protected void beforeHookedMethod(MethodHookParam p) {
                                    iconFonts.fontResource((int) p.args[1]);
                                }
                            });
                } catch (Throwable ignored) {}
            }
        }

        XC_MethodHook built = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (Boolean.TRUE.equals(loading.get()) || iconFonts.keep(p.getResult()) || p.getResult() == null) return;
                try {
                    Object path = XposedHelpers.getObjectField(p.thisObject, "mPath");
                    if (path != null && IconFontGuard.iconName(path.toString())) return;
                } catch (Throwable ignored) {}
                Typeface tf = getActiveTypeface();
                if (tf != null) p.setResult(Typeface.create(tf, weightOf((Typeface) p.getResult()), ((Typeface) p.getResult()).isItalic()));
            }
        };
        for (String b : new String[]{"android.graphics.Typeface$Builder", "android.graphics.Typeface$CustomFallbackBuilder"}) {
            try {
                XposedHelpers.findAndHookMethod(b, null, "build", built);
            } catch (Throwable ignored) {}
        }
        replaceSystemDefaults();
    }


    private static void replaceSystemDefaults() {
        Typeface tf = getActiveTypeface();
        if (tf == null) return;
        int n = 0;
        try {
            loading.set(Boolean.TRUE);
            for (String f : new String[]{"DEFAULT", "DEFAULT_BOLD", "SANS_SERIF"}) {
                try {
                    Typeface old = (Typeface) XposedHelpers.getStaticObjectField(Typeface.class, f);
                    XposedHelpers.setStaticObjectField(Typeface.class, f, Typeface.create(tf, weightOf(old), old.isItalic()));
                    n++;
                } catch (Throwable ignored) {}
            }
            try {
                XposedHelpers.callStaticMethod(Typeface.class, "setDefault", Typeface.DEFAULT);
            } catch (Throwable t) { ModuleLog.warn("(IE|Font) Could not make the font the default; some text may keep the old font: " + t.getClass().getSimpleName() + ": " + t.getMessage()); }
            try {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Typeface> orig = (java.util.Map<String, Typeface>)
                        XposedHelpers.getStaticObjectField(Typeface.class, "sSystemFontMap");
                java.util.Map<String, Typeface> map = new android.util.ArrayMap<>(orig.size());
                map.putAll(orig);
                for (java.util.Map.Entry<String, Typeface> e : orig.entrySet()) {
                    String k = e.getKey().toLowerCase();
                    if (!(k.startsWith("sans-serif") || k.startsWith("roboto") || k.startsWith("google-sans"))
                            || k.contains("mono")) continue;
                    Typeface old = e.getValue();
                    map.put(e.getKey(), Typeface.create(tf, weightOf(old), old.isItalic()));
                    n++;
                }
                XposedHelpers.setStaticObjectField(Typeface.class, "sSystemFontMap", map);
            } catch (Throwable t) { ModuleLog.warn("(IE|Font) Could not replace the system fonts; some text may keep the old font: " + t.getClass().getSimpleName() + ": " + t.getMessage()); }
        } finally {
            loading.set(Boolean.FALSE);
        }
        ModuleLog.detail("(IE|Font) system defaults replaced: " + n);
    }

    private void installPickResultHook() {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onActivityResult",
                    int.class, int.class, Intent.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                int req = (int) p.args[0];
                                if (req != FONT_PICK_REQUEST && req != EMOJI_PICK_REQUEST) return;
                                if ((int) p.args[1] != Activity.RESULT_OK || p.args[2] == null) return;
                                android.net.Uri uri = ((Intent) p.args[2]).getData();
                                if (uri == null) return;
                                Activity act = (Activity) p.thisObject;
                                if (req == EMOJI_PICK_REQUEST) {
                                    if (saveEmoji(act, uri)) {
                                        FeatureFlags.customEmojiEnabled = true;
                                        SettingsManager.saveAllFlags();
                                        ps.reso.instaeclipse.utils.toast.IeToast.makeText(act,
                                                I18n.t(act, R.string.ie_emoji_saved), android.widget.Toast.LENGTH_LONG).show();
                                    } else {
                                        ps.reso.instaeclipse.utils.toast.IeToast.makeText(act,
                                                I18n.t(act, R.string.ie_font_load_failed), android.widget.Toast.LENGTH_SHORT).show();
                                    }
                                    return;
                                }
                                if (saveFont(act, uri)) {
                                    FeatureFlags.customFontEnabled = true;
                                    SettingsManager.saveAllFlags();
                                    ps.reso.instaeclipse.utils.toast.IeToast.makeText(act,
                                            I18n.t(act, R.string.ie_font_saved), android.widget.Toast.LENGTH_LONG).show();
                                } else {
                                    ps.reso.instaeclipse.utils.toast.IeToast.makeText(act,
                                            I18n.t(act, R.string.ie_font_load_failed), android.widget.Toast.LENGTH_SHORT).show();
                                }
                            } catch (Throwable t) { ModuleLog.error("(IE|Font) Could not use the picked file", t); }
                        }
                    });
        } catch (Throwable ignored) {}
    }

    private static Typeface getUserFont() {
        String path = FeatureFlags.customFontPath;
        if (path == null || path.isEmpty()) return null;
        if (userFont != null && path.equals(loadedFrom)) return userFont;
        try {
            loading.set(Boolean.TRUE);
            userFont = Typeface.createFromFile(path);
            loadedFrom = path;
        } catch (Throwable t) {
            ModuleLog.error("(IE|Font) Could not load the custom font file: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            userFont = null;
        } finally {
            loading.set(Boolean.FALSE);
        }
        return userFont;
    }

    private static Typeface combo;
    private static String comboPrimary, comboEmoji;
    private static volatile Styled styled;

    private static final class Styled {
        final Typeface base;
        final Typeface[] byStyle = new Typeface[2 * 1001];

        Styled(Typeface base) {
            this.base = base;
        }
    }

    private static Typeface getActiveTypeface() {
        if (FeatureFlags.customEmojiEnabled && FeatureFlags.customEmojiPath != null
                && !FeatureFlags.customEmojiPath.isEmpty()) {
            return getCombo();
        }
        if (FeatureFlags.customFontEnabled) return getUserFont();
        return null;
    }

    private static Typeface getCombo() {
        String primaryPath = (FeatureFlags.customFontEnabled && FeatureFlags.customFontPath != null
                && !FeatureFlags.customFontPath.isEmpty()) ? FeatureFlags.customFontPath : SystemSansFont.path();
        String emojiPath = FeatureFlags.customEmojiPath;
        if (combo != null && primaryPath.equals(comboPrimary) && emojiPath.equals(comboEmoji)) return combo;
        try {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
                combo = getUserFont();
            } else {
                android.graphics.fonts.Font primary = new android.graphics.fonts.Font.Builder(
                        new File(primaryPath)).build();
                android.graphics.fonts.Font emoji = new android.graphics.fonts.Font.Builder(
                        new File(emojiPath)).build();
                android.graphics.fonts.FontFamily primFam = new android.graphics.fonts.FontFamily.Builder(primary).build();
                android.graphics.fonts.FontFamily emojiFam = new android.graphics.fonts.FontFamily.Builder(emoji).build();
                loading.set(Boolean.TRUE);
                try {
                    combo = new Typeface.CustomFallbackBuilder(primFam)
                            .addCustomFallback(emojiFam)
                            .build();
                } finally {
                    loading.set(Boolean.FALSE);
                }
            }
            comboPrimary = primaryPath;
            comboEmoji = emojiPath;
        } catch (Throwable t) {
            ModuleLog.warn("(IE|Emoji) Could not combine the emoji font with the text font; using the text font only: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            combo = getUserFont();
        }
        return combo;
    }

    private static boolean saveFont(Activity act, android.net.Uri uri) {
        try {
            File out = copyFont(act, uri, FONT_PREFIX);
            if (out == null) return false;
            Typeface test;
            try { loading.set(Boolean.TRUE); test = Typeface.createFromFile(out); }
            finally { loading.set(Boolean.FALSE); }
            if (test == null) { out.delete(); return false; }
            FeatureFlags.customFontPath = out.getAbsolutePath();
            userFont = test;
            loadedFrom = FeatureFlags.customFontPath;
            return true;
        } catch (Throwable t) {
            ModuleLog.error("(IE|Font) Could not save the picked font: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    private static File copyFont(Activity act, android.net.Uri uri, String prefix) throws java.io.IOException {
        File dir = act.getFilesDir();
        File tmp = File.createTempFile(prefix, ".tmp", dir);
        File out = null;
        try {
            try (InputStream in = act.getContentResolver().openInputStream(uri);
                 FileOutputStream fo = new FileOutputStream(tmp)) {
                if (in == null) return null;
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
            }
            if (!isFont(tmp)) return null;
            File target = new File(dir, prefix + "_" + System.currentTimeMillis() + ".ttf");
            if (tmp.renameTo(target)) out = target;
            return out;
        } finally {
            if (out == null) tmp.delete();
        }
    }

    private static boolean isFont(File file) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                new android.graphics.fonts.Font.Builder(file).build();
                return true;
            }
            Typeface tf;
            try { loading.set(Boolean.TRUE); tf = Typeface.createFromFile(file); }
            finally { loading.set(Boolean.FALSE); }
            return tf != null && tf != Typeface.DEFAULT;
        } catch (Throwable t) {
            ModuleLog.warn("(IE|Font) The picked file is not a font Android can use: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    public static void launchPicker(Activity act) {
        launchPickerFor(act, FONT_PICK_REQUEST, I18n.t(act, R.string.ig_custom_font_pick));
    }

    public static void launchEmojiPicker(Activity act) {
        launchPickerFor(act, EMOJI_PICK_REQUEST, I18n.t(act, R.string.ig_custom_emoji_pick));
    }

    private static void launchPickerFor(Activity act, int req, String title) {
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("*/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            act.startActivityForResult(Intent.createChooser(i, title), req);
        } catch (Throwable t) {
            ps.reso.instaeclipse.utils.toast.IeToast.makeText(act, I18n.t(act, R.string.ie_picker_unavailable),
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void installEmojiHook(final ClassLoader cl) {
        if (!installEmojiWorkerHook(cl)) ModuleLog.error("(IE|Emoji) Will not work: Instagram's emoji loader was not found in this Instagram version");
    }

    private boolean installEmojiWorkerHook(final ClassLoader cl) {
        try {
            java.util.List<Found> found =
                    DexKitQueryCache.methods(
                            org.luckypray.dexkit.query.FindMethod.create().matcher(
                                    org.luckypray.dexkit.query.matchers.MethodMatcher.create()
                                            .name("run")
                                            .usingStrings("fetchFonts failed (", "EmojiCompat.MetadataRepo.create")));
            int n = 0;
            for (Found md : found) {
                java.lang.reflect.Method run = md.getMethodInstance(cl);
                ps.reso.instaeclipse.xposedcompat.XposedBridge.hookMethod(run, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (!FeatureFlags.customEmojiEnabled) return;
                        try {
                            if (failEmojiLoad(p.thisObject, 0)) {
                                p.setResult(null);
                                ModuleLog.detail("(IE|Emoji) EmojiCompat disabled — emoji via font fallback");
                            }
                        } catch (Throwable ignored) {}
                    }
                });
                n++;
            }
            if (n == 0) return false;
            if (FeatureFlags.customEmojiEnabled) FeatureStatusTracker.setHooked("CustomEmoji");
            ModuleLog.ok("(IE|Emoji) ready");
            ModuleLog.detail("(IE|Emoji) emoji2 loader worker hooked x" + n);
            return true;
        } catch (Throwable t) {
            ModuleLog.detail("(IE|Emoji) emoji2 loader worker hook failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    private static boolean failEmojiLoad(Object obj, int depth) throws Exception {
        if (obj == null || depth > 2) return false;
        for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(c)) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                Class<?> t = f.getType();
                if (!t.getName().startsWith("X.")) continue;
                f.setAccessible(true);
                Object v = f.get(obj);
                if (v == null) continue;
                if (java.lang.reflect.Modifier.isAbstract(t.getModifiers()) && !t.isInterface()) {
                    for (java.lang.reflect.Method m : ps.reso.instaeclipse.utils.core.SafeReflect.methods(t)) {
                        Class<?>[] pt = m.getParameterTypes();
                        if (m.getReturnType() == void.class && pt.length == 1 && pt[0] == Throwable.class) {
                            m.setAccessible(true);
                            m.invoke(v, new Exception("ie: emoji via fallback"));
                            return true;
                        }
                    }
                }
                if (failEmojiLoad(v, depth + 1)) return true;
            }
        }
        return false;
    }

    private static boolean saveEmoji(Activity act, android.net.Uri uri) {
        try {
            File out = copyFont(act, uri, EMOJI_PREFIX);
            if (out == null) return false;
            FeatureFlags.customEmojiPath = out.getAbsolutePath();
            combo = null;
            return true;
        } catch (Throwable t) {
            ModuleLog.error("(IE|Emoji) Could not save the picked emoji font: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    private static Typeface styledLike(Typeface active, Typeface like) {
        if (like == null) return active;
        int weight = weightOf(like);
        boolean italic = like.isItalic();
        Styled s = styled;
        if (s == null || s.base != active) styled = s = new Styled(active);
        int i = weight * 2 + (italic ? 1 : 0);
        Typeface t = s.byStyle[i];
        if (t == null) s.byStyle[i] = t = Typeface.create(active, weight, italic);
        return t;
    }

    private static int weightOf(Typeface t) {
        int w = t.getWeight();
        return w < 1 || w > 1000 ? 400 : w;
    }
}
