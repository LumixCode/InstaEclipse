package ps.reso.instaeclipse.mods.misc;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.widget.PopupWindow;
import android.widget.Toast;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.mods.media.CommentMediaDownload;
import ps.reso.instaeclipse.utils.core.JitGuard;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.toast.IeToast;
import ps.reso.instaeclipse.utils.users.UserUtils;

/**
 * Adds a Copy Comment row (comments with text) and a Download GIF / image row (comments with a
 * GIF or photo, see {@link CommentMediaDownload}) to Instagram's own comment long-press menu
 * (IG 446+).
 *
 * The menu is a PopupWindow whose rows the comment presenter builds from (icon, label, callback)
 * row models. Everything is found by shape from the stable effect-handler class, so obfuscated
 * renames don't break discovery:
 *   presenter: a handler field type with a (..., List, float) void method; it gets the comment
 *   popup:     the presenter's PopupWindow field, whose (List) void methods receive the rows
 *   row:       the list's element class and its (Drawable, Drawable, callback, Integer,
 *              String, String, boolean x5) constructor. Instagram calls the callback's void
 *              method on tap, then dismisses the menu when its boolean method returns true.
 */
public class CommentCopyHook {

    private static final String EFFECT_HANDLER_CLASS =
            "com.instagram.comments.mvvm.view.fragment.CommentViewUiEffectHandler$handleCommentUiEffects$1";

    private static final class MenuCall {
        final Object comment;
        boolean rowsSeen, rowAdded;
        MenuCall(Object comment) { this.comment = comment; }
    }
    private static final ThreadLocal<MenuCall> menuCall = new ThreadLocal<>();
    private static final Map<Class<?>, Constructor<?>> rowConstructors = new ConcurrentHashMap<>();
    private static volatile boolean loggedShapeMismatch;

    public void install(ClassLoader classLoader) {
        try {
            Class<?> handler = classLoader.loadClass(EFFECT_HANDLER_CLASS);
            Method presenter = null;
            for (Field f : handler.getDeclaredFields()) {
                for (Method m : f.getType().getDeclaredMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (m.getReturnType() == void.class && p.length == 5
                            && p[3] == List.class && p[4] == float.class) {
                        presenter = m;
                        break;
                    }
                }
                if (presenter != null) break;
            }
            Class<?> popup = null;
            if (presenter != null) {
                for (Field f : presenter.getDeclaringClass().getDeclaredFields()) {
                    if (PopupWindow.class.isAssignableFrom(f.getType())) popup = f.getType();
                }
            }
            int hooked = 0;
            if (popup != null) {
                for (Method m : popup.getDeclaredMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (m.getReturnType() != void.class || p.length != 1 || p[0] != List.class
                            || Modifier.isStatic(m.getModifiers())) continue;
                    XposedBridge.hookMethod(m, ADD_ROW_HOOK);
                    hooked++;
                }
            }
            if (hooked == 0) {
                if (FeatureFlags.enableCopyComment) FeatureStatusTracker.setBroken("CopyComment", R.string.ig_dialog_misc_copy_comment);
                ModuleLog.line("(InstaEclipse | CopyComment): ❌ comment long-press menu not found");
                return;
            }
            XposedBridge.hookMethod(presenter, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    JitGuard.tick(param.method);
                    menuCall.set(new MenuCall(commentArg(param.args)));
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    JitGuard.tick(param.method);
                    MenuCall call = menuCall.get();
                    menuCall.remove();
                    if (call != null && call.rowsSeen && !call.rowAdded && FeatureFlags.enableCopyComment
                            && !loggedShapeMismatch) {
                        loggedShapeMismatch = true;
                        ModuleLog.line("(InstaEclipse | CopyComment): ⚠️ menu rows no longer match, no Copy row");
                    }
                }
            });
            FeatureStatusTracker.setHooked("CopyComment");
            ModuleLog.line("(InstaEclipse | CopyComment): ✅ menu rows via " + presenter.getDeclaringClass().getName()
                    + "." + presenter.getName() + " + " + popup.getName() + " (" + hooked + " list methods)");
        } catch (Throwable t) {
            if (FeatureFlags.enableCopyComment) FeatureStatusTracker.setBroken("CopyComment", R.string.ig_dialog_misc_copy_comment);
            ModuleLog.line("(InstaEclipse | CopyComment): ❌ install – " + t);
        }
    }

    /** The comment model is the presenter argument that carries its author. */
    private static Object commentArg(Object[] args) {
        try {
            Class<?> user = Class.forName("com.instagram.user.model.User", false,
                    args[1] != null ? args[1].getClass().getClassLoader() : CommentCopyHook.class.getClassLoader());
            for (Object arg : args) {
                if (arg != null && !(arg instanceof List) && hasFieldOfType(arg.getClass(), user)) return arg;
            }
        } catch (Throwable ignored) {}
        return args.length > 1 ? args[1] : null;
    }

    private static boolean hasFieldOfType(Class<?> clazz, Class<?> target) {
        for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(clazz)) {
            if (target.isAssignableFrom(f.getType())) return true;
        }
        return false;
    }

    private static final XC_MethodHook ADD_ROW_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            JitGuard.tick(param.method);
            MenuCall call = menuCall.get();
            if (call == null || call.rowAdded || !FeatureFlags.enableCopyComment) return; // one menu, one row
            if (!(param.args[0] instanceof List<?> rows) || rows.isEmpty()) return;
            call.rowsSeen = true;
            try {
                Constructor<?> row = rowConstructor(rows.get(0).getClass());
                if (row == null) return; // not rows we can build
                call.rowAdded = true;
                Object[] comment = CommentMediaDownload.comment(call.comment);
                String text = commentText(call.comment, comment);
                CommentMediaDownload.Found media = comment == null ? null
                        : CommentMediaDownload.find((Class<?>) comment[0], comment[1]);
                if (text == null && media == null) return;
                Context ctx = ((PopupWindow) param.thisObject).getContentView().getContext();
                List<Object> out = new ArrayList<>(rows);
                // Before Instagram's tinted (destructive) rows such as Report, like its own order.
                int at = firstTintedRow(rows);
                if (text != null) {
                    out.add(at++, newRow(row, rows.get(0), ctx, "instagram_copy_pano_outline_24",
                            I18n.t(ctx, R.string.ig_comment_copy_title), () -> copyToClipboard(ctx, text)));
                }
                if (media != null) {
                    String commenter = commenter(call.comment);
                    out.add(at, newRow(row, rows.get(0), ctx, "instagram_download_pano_outline_24",
                            I18n.t(ctx, media.gif ? R.string.ig_comment_download_gif : R.string.ig_comment_download_image),
                            () -> CommentMediaDownload.save(ctx, media, commenter)));
                }
                param.args[0] = out;
            } catch (Throwable t) {
                ModuleLog.line("(InstaEclipse | CopyComment): ❌ menu row – " + t);
            }
        }
    };

    /** (Drawable, Drawable, callback, Integer, String, String, boolean...): 5 flags on 446, 6 on 451. */
    private static Constructor<?> rowConstructor(Class<?> rowClass) {
        Constructor<?> cached = rowConstructors.get(rowClass);
        if (cached != null) return cached;
        for (Constructor<?> c : rowClass.getDeclaredConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length < 11 || p[0] != Drawable.class || p[1] != Drawable.class || !tapAndDismissOnly(p[2])
                    || p[3] != Integer.class || p[4] != String.class || p[5] != String.class) continue;
            boolean flags = true;
            for (int i = 6; i < p.length; i++) flags &= p[i] == boolean.class;
            if (!flags) continue;
            c.setAccessible(true);
            rowConstructors.put(rowClass, c);
            return c;
        }
        return null;
    }

    /**
     * The row callback must be exactly Instagram's tap + dismiss pair. Any other shape could have
     * our proxy run the action without a tap, or return null where Instagram needs a value.
     */
    private static boolean tapAndDismissOnly(Class<?> callback) {
        if (!callback.isInterface()) return false;
        Method[] methods = callback.getMethods();
        if (methods.length != 2) return false;
        boolean tap = false, dismiss = false;
        for (Method m : methods) {
            if (m.getParameterCount() != 0) return false;
            tap |= m.getReturnType() == void.class;
            dismiss |= m.getReturnType() == boolean.class;
        }
        return tap && dismiss;
    }

    /** Destructive rows carry a tint colour; ours goes just before the first one. */
    private static int firstTintedRow(List<?> rows) {
        for (int i = 0; i < rows.size(); i++) {
            Object row = rows.get(i);
            for (Field f : row.getClass().getDeclaredFields()) {
                if (f.getType() != Integer.class) continue;
                try {
                    f.setAccessible(true);
                    if (f.get(row) != null) return i;
                } catch (Throwable ignored) {}
            }
        }
        return rows.size();
    }

    @SuppressLint({"DiscouragedApi", "UseCompatLoadingForDrawables"}) // Instagram's own icons
    private static Object newRow(Constructor<?> row, Object template, Context ctx, String icon,
                                 String label, Runnable onTap) throws Exception {
        Class<?> callbackType = row.getParameterTypes()[2];
        Object callback = Proxy.newProxyInstance(callbackType.getClassLoader(),
                new Class<?>[]{callbackType}, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        switch (method.getName()) {
                            case "equals": return proxy == args[0];
                            case "hashCode": return System.identityHashCode(proxy);
                            default: return "CopyCommentRow";
                        }
                    }
                    if (method.getReturnType() == boolean.class) return true; // dismiss after tap
                    if (method.getReturnType() == void.class) onTap.run();
                    return null;
                });
        Drawable drawable = null;
        int id = ctx.getResources().getIdentifier(icon, "drawable", ctx.getPackageName());
        if (id != 0) drawable = ctx.getDrawable(id);
        // Same arguments Instagram passes for its own untinted rows; the flags follow below.
        Object[] args = new Object[row.getParameterCount()];
        args[1] = drawable;
        args[2] = callback;
        args[4] = label;
        for (int i = 6; i < args.length; i++) args[i] = false;
        Object created = row.newInstance(args);
        copyFlags(template, created);
        return created;
    }

    /**
     * Instagram passes the same flag constants for every row of this menu, but their number and
     * order change between versions (5 on 446, 6 on 451): copy them from one of its own rows.
     */
    private static void copyFlags(Object from, Object to) {
        for (Field f : to.getClass().getDeclaredFields()) {
            if (f.getType() != boolean.class || Modifier.isStatic(f.getModifiers())) continue;
            try {
                f.setAccessible(true);
                f.setBoolean(to, f.getBoolean(from));
            } catch (Throwable ignored) {}
        }
    }

    /**
     * The comment's text, or null when it has none (a GIF or photo on its own). Read through the
     * comment interface's text accessor (02Rd.DhX on 451, which CaptionCopyHook resolves): the menu
     * model also holds ids and other strings, so the length guess is only the fallback.
     */
    private static String commentText(Object holder, Object[] comment) {
        Method accessor = CaptionCopyHook.textAccessor();
        if (comment != null && accessor != null && accessor.getDeclaringClass().isInstance(comment[1])) {
            try {
                Object text = accessor.invoke(comment[1]);
                return text instanceof String s && !s.trim().isEmpty() ? s.trim() : null;
            } catch (Throwable ignored) {} // unfetched Pando field: fall back to the guess
        }
        String text = holder == null ? null : findLongestTextField(holder);
        return text == null || text.trim().isEmpty() ? null : text.trim();
    }

    /** The comment author's username, for the saved file's name. */
    private static String commenter(Object holder) {
        if (holder == null) return null;
        for (Class<?> c = holder.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!f.getType().getName().equals("com.instagram.user.model.User")) continue;
                try {
                    f.setAccessible(true);
                    Object user = f.get(holder);
                    if (user != null) return UserUtils.callUsernameGetter(user);
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    /** The comment text is the model's longest String that isn't an id or a URL. */
    private static String findLongestTextField(Object model) {
        String best = null;
        for (Field f : model.getClass().getDeclaredFields()) {
            if (f.getType() != String.class) continue;
            try {
                f.setAccessible(true);
                String val = (String) f.get(model);
                if (val == null || val.isEmpty()) continue;
                if (val.matches("\\d+")) continue;
                if (val.matches("\\d+_\\d+")) continue;
                // A just-posted comment's temporary id is a UUID, longer than a short reply.
                if (val.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) continue;
                if (val.startsWith("http")) continue;
                if (best == null || val.length() > best.length()) best = val;
            } catch (Throwable ignored) {}
        }
        return best;
    }

    private static void copyToClipboard(final Context ctx, final String text) {
        try {
            ClipboardManager cm = (ClipboardManager)
                    ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("comment", text));
                // Android 13+ confirms every copy itself; a second toast would just repeat it.
                if (android.os.Build.VERSION.SDK_INT < 33) IeToast.makeText(ctx,
                        I18n.t(ctx, R.string.ig_toast_comment_copied), Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            ModuleLog.line("(InstaEclipse | CopyComment): ❌ Copy – " + t.getMessage());
        }
    }
}
